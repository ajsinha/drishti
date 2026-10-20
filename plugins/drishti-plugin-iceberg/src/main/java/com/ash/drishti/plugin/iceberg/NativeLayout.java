/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.meta.Transform;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.write.Operations;
import com.ash.drishti.iceberg.write.ParquetDataWriter;
import com.ash.drishti.plugin.iceberg.IcebergLayout.Row;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link IcebergLayout} written by drishti-iceberg, the native engine (no Hadoop): the same schema (field ids 1
 * {@code id}, 2 {@code doc}, 3 {@code business_date}, then the promoted columns), the same identity partition on
 * {@code business_date}, sort order, table properties and files of {@code file-rows} rows, so tables move between the
 * engines unchanged. Stateless.
 */
final class NativeLayout {

    private NativeLayout() {}

    /** The layout's schema (as {@link IcebergLayout#schema}). */
    static Schema schema(Map<String, Boolean> promoted) {
        List<NestedField> fields = new ArrayList<>();
        fields.add(NestedField.required(1, IcebergLayout.ID, Type.Primitive.STRING));
        fields.add(NestedField.optional(2, IcebergLayout.DOC, Type.Primitive.STRING));
        fields.add(NestedField.required(3, IcebergLayout.DATE, Type.Primitive.DATE));
        int next = 4;
        for (Map.Entry<String, Boolean> e : promoted.entrySet()) {
            fields.add(NestedField.optional(next++, IcebergLayout.column(e.getKey()), e.getValue() ? Type.Primitive.DOUBLE : Type.Primitive.STRING));
        }
        return new Schema(0, fields);
    }

    /** The layout's table properties (as {@link IcebergLayout#properties}), with the format version for a new table. */
    static Map<String, String> properties(long rowGroupBytes, int fileRows) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("format-version", "2");
        p.put("write.parquet.row-group-size-bytes", String.valueOf(rowGroupBytes));
        p.put("write.parquet.compression-codec", "zstd");
        p.put("write.parquet.dict-size-bytes", String.valueOf(256 * 1024));
        p.put("write.parquet.row-group-check-min-record-count", "10");
        p.put("write.metadata.metrics.column." + IcebergLayout.DOC, "none");
        p.put("write.metadata.metrics.column." + IcebergLayout.ID, "full");
        p.put("write.metadata.delete-after-commit.enabled", "true");
        p.put("write.metadata.previous-versions-max", "50");
        p.put("commit.retry.num-retries", "10");
        p.put(IcebergLayout.FILE_ROWS, String.valueOf(fileRows));
        return p;
    }

    /** The kind's table, created in the layout if it does not exist; promoted paths it lacks are added as columns. */
    static Table open(Catalog catalog, String kind, Map<String, Boolean> promoted, long rowGroupBytes, int fileRows) {
        try {
            Table t = catalog.load(kind).orElse(null);
            if (t == null) {
                Schema s = schema(promoted);
                PartitionSpec spec = new PartitionSpec(0, List.of(new PartitionSpec.Field(3, 1000, IcebergLayout.DATE, Transform.Identity.INSTANCE)));
                SortOrder order = new SortOrder(1, List.of(new SortOrder.Field(1, Transform.Identity.INSTANCE, true, true)));
                try {
                    return catalog.create(kind, s, spec, order, properties(rowGroupBytes, fileRows));
                } catch (Catalog.TableExists e) {
                    t = catalog.load(kind).orElseThrow(() -> e);    // a concurrent writer created it
                }
            }
            Map<String, Type> missing = new LinkedHashMap<>();
            for (Map.Entry<String, Boolean> e : promoted.entrySet()) {
                if (t.metadata().schema().field(IcebergLayout.column(e.getKey())) == null) {
                    missing.put(IcebergLayout.column(e.getKey()), e.getValue() ? Type.Primitive.DOUBLE : Type.Primitive.STRING);
                }
            }
            if (!missing.isEmpty()) {
                Operations.addColumns(t, missing);
            }
            return t;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The table's file-rows (the layout's), or the default. */
    static int fileRows(Table t) {
        return Integer.parseInt(t.metadata().property(IcebergLayout.FILE_ROWS, String.valueOf(IcebergLayout.DEFAULT_FILE_ROWS)));
    }

    /** The index of the {@code business_date} identity field in the table's current spec. */
    static int dateIndex(PartitionSpec spec, Schema schema) {
        NestedField date = schema.field(IcebergLayout.DATE);
        for (int i = 0; i < spec.fields().size(); i++) {
            if (date != null && spec.fields().get(i).sourceId() == date.id() && spec.fields().get(i).transform() instanceof Transform.Identity) {
                return i;
            }
        }
        throw new IllegalStateException("not in the layout: no identity partition on " + IcebergLayout.DATE);
    }

    /** The business date of a data or delete file (null for another spec without the date). */
    static LocalDate dateOf(Table t, DataFile f) {
        PartitionSpec spec = t.metadata().spec(f.specId());
        NestedField date = t.metadata().schema().field(IcebergLayout.DATE);
        for (int i = 0; i < spec.fields().size(); i++) {
            if (date != null && spec.fields().get(i).sourceId() == date.id() && spec.fields().get(i).transform() instanceof Transform.Identity) {
                Object v = f.partition()[i];
                return v == null ? null : LocalDate.ofEpochDay(((Number) v).longValue());
            }
        }
        return null;
    }

    /**
     * Writes one business day's rows, which must come sorted by id, into files of {@code fileRows} rows each (each
     * file its own id range). Nothing is committed: {@link #commitDays} makes the files the day's data.
     */
    static List<DataFile> writeDay(Table table, LocalDate date, Iterator<Row> sorted, int fileRows) {
        Schema schema = table.metadata().schema();
        PartitionSpec spec = table.metadata().spec();
        int dateIndex = dateIndex(spec, schema);
        Object[] partition = new Object[spec.fields().size()];
        partition[dateIndex] = (int) date.toEpochDay();
        List<NestedField> cols = schema.columns();
        Map<String, String> props = table.metadata().properties();
        int sortOrder = table.metadata().sortOrder().orderId();
        String dir = table.metadata().location().replaceAll("/+$", "") + "/data/" + IcebergLayout.DATE + "=" + date + "/";
        List<DataFile> files = new ArrayList<>();
        ParquetDataWriter w = null;
        long rows = 0;
        try {
            while (sorted.hasNext()) {
                Row row = sorted.next();
                if (w == null) {
                    String name = date + "-" + String.format("%05d", files.size()) + "-" + UUID.randomUUID() + ".parquet";
                    w = new ParquetDataWriter(table.io(), dir + name, schema, props, spec.specId(), partition, sortOrder);
                }
                Object[] values = new Object[cols.size()];
                for (int i = 0; i < cols.size(); i++) {
                    NestedField f = cols.get(i);
                    values[i] = switch (f.name()) {
                        case IcebergLayout.ID -> row.id();
                        case IcebergLayout.DOC -> row.doc();
                        case IcebergLayout.DATE -> (int) date.toEpochDay();
                        default -> {
                            String path = f.name().replace("__", ".");
                            yield row.columns().containsKey(path) ? value(f.type(), row.columns().get(path)) : null;
                        }
                    };
                }
                w.write(values);
                if (++rows % fileRows == 0) {
                    files.add(w.finish());
                    w = null;
                }
            }
            if (w != null) {
                files.add(w.finish());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** A promoted value as the column's type, as {@link IcebergLayout#value} does for the library. */
    static Object value(Type type, Object v) {
        if (v == null) {
            return null;
        }
        if (type.id() == Type.Id.DOUBLE) {
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf(d.longValue());                 // 42.0 promoted into a text column reads "42"
        }
        return String.valueOf(v);
    }

    /** Makes each day's files that day's data, in one commit that replaces the days listed (loading a day again is idempotent). */
    static void commitDays(Table table, Map<LocalDate, List<DataFile>> days) {
        if (days.isEmpty()) {
            return;
        }
        Set<LocalDate> dates = new HashSet<>(days.keySet());
        List<DataFile> files = new ArrayList<>();
        days.values().forEach(files::addAll);
        String label = days.size() == 1 ? days.keySet().iterator().next().toString() : days.size() + " days";
        Operations.replacePartitions(table, f -> dates.contains(dateOf(table, f)), files, Map.of("drishti.business-dates", label));
    }
}
