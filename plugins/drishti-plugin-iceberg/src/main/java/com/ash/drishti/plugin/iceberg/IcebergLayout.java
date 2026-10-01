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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.PartitionData;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * The table layout the connector reads fastest, shared by the connector, {@link IcebergLoader} and
 * {@link IcebergMaintenance}: one table per kind with columns {@code id}, {@code doc} (the whole JSON document),
 * {@code business_date} (an identity partition) and the pack's promoted fields beside them ({@code counterparty.id} is
 * column {@code counterparty__id}; numbers are {@code double}, everything else {@code string}); each business day
 * sorted by id and cut into files of {@code file-rows} rows, each file its own id range; small Parquet row groups, so
 * one entity is one small row group; column metrics (minimum and maximum) for every column but {@code doc}.
 */
public final class IcebergLayout {

    public static final String ID = "id";
    public static final String DOC = "doc";
    public static final String DATE = "business_date";
    /** Table property: the rows per file the layout cuts each day into (maintenance re-cuts drifted days to it). */
    public static final String FILE_ROWS = "drishti.layout.file-rows";
    public static final int DEFAULT_FILE_ROWS = 250_000;
    /**
     * About 900 trade documents a row group (measured: zstd packs a 6.3 KB trade into about 0.7 KB, and the writer
     * measures a row group compressed), so one entity's read decodes about 6 MB of documents.
     */
    public static final long DEFAULT_ROW_GROUP_BYTES = 1024L * 1024;

    /** One entity's row for a business day: its document and its promoted fields (path to a Double, a String or null). */
    public record Row(String id, String doc, Map<String, Object> columns) {}

    private IcebergLayout() {
    }

    /** The column a promoted document path is stored in. */
    public static String column(String path) {
        return path.replace(".", "__");
    }

    /** The table's schema: id, doc, business_date, then each promoted path (true: a number). */
    public static Schema schema(Map<String, Boolean> promoted) {
        List<Types.NestedField> fields = new ArrayList<>();
        fields.add(Types.NestedField.required(1, ID, Types.StringType.get()));
        fields.add(Types.NestedField.optional(2, DOC, Types.StringType.get()));
        fields.add(Types.NestedField.required(3, DATE, Types.DateType.get()));
        int next = 4;
        for (Map.Entry<String, Boolean> e : promoted.entrySet()) {
            fields.add(Types.NestedField.optional(next++, column(e.getKey()), e.getValue() ? Types.DoubleType.get() : Types.StringType.get()));
        }
        return new Schema(fields);
    }

    public static PartitionSpec spec(Schema schema) {
        return PartitionSpec.builderFor(schema).identity(DATE).build();
    }

    public static SortOrder sortOrder(Schema schema) {
        return SortOrder.builderFor(schema).asc(ID).build();
    }

    /**
     * Table properties: small row groups ({@code rowGroupBytes} of compressed data, about 900 trades at 1 MB), metrics
     * for every column except {@code doc} (two copies of a JSON document per file in every manifest would make planning
     * read megabytes), a small dictionary page (so {@code doc} falls back to plain encoding at once, while ids and
     * promoted text still use dictionaries), zstd, and old metadata files removed after each commit.
     */
    public static Map<String, String> properties(long rowGroupBytes, int fileRows) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(TableProperties.FORMAT_VERSION, "2");
        p.put(TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES, String.valueOf(rowGroupBytes));
        p.put(TableProperties.PARQUET_COMPRESSION, "zstd");
        p.put(TableProperties.PARQUET_DICT_SIZE_BYTES, String.valueOf(256 * 1024));
        p.put(TableProperties.PARQUET_ROW_GROUP_CHECK_MIN_RECORD_COUNT, "10");     // small groups: their size checked early
        p.put(TableProperties.METRICS_MODE_COLUMN_CONF_PREFIX + DOC, "none");
        p.put(TableProperties.METRICS_MODE_COLUMN_CONF_PREFIX + ID, "full");
        p.put(TableProperties.METADATA_DELETE_AFTER_COMMIT_ENABLED, "true");
        p.put(TableProperties.METADATA_PREVIOUS_VERSIONS_MAX, "50");
        p.put(TableProperties.COMMIT_NUM_RETRIES, "10");
        p.put(FILE_ROWS, String.valueOf(fileRows));
        return p;
    }

    /**
     * The kind's table, created in the layout if it does not exist; promoted paths it lacks are added as columns (a
     * path's type is fixed once the column exists).
     */
    public static Table open(IcebergLake lake, String kind, Map<String, Boolean> promoted, long rowGroupBytes, int fileRows) {
        Table t = lake.load(kind).orElse(null);
        if (t == null) {
            Schema s = schema(promoted);
            try {
                return lake.create(kind, s, spec(s), sortOrder(s), properties(rowGroupBytes, fileRows));
            } catch (org.apache.iceberg.exceptions.AlreadyExistsException e) {
                t = lake.load(kind).orElseThrow(() -> e);           // a concurrent writer created it
            }
        }
        Schema has = t.schema();
        List<Map.Entry<String, Boolean>> missing = promoted.entrySet().stream().filter(e -> has.findField(column(e.getKey())) == null).toList();
        if (!missing.isEmpty()) {
            UpdateSchema u = t.updateSchema();
            missing.forEach(e -> u.addColumn(column(e.getKey()), e.getValue() ? Types.DoubleType.get() : Types.StringType.get()));
            u.commit();
        }
        return t;
    }

    /** The partition value of a business date (an identity partition on a date: days since the epoch). */
    public static PartitionData partition(PartitionSpec spec, LocalDate date) {
        PartitionData p = new PartitionData(spec.partitionType());
        p.set(0, (int) date.toEpochDay());
        return p;
    }

    /**
     * Writes one business day's rows, which must come sorted by id, into files of {@code fileRows} rows each (each
     * file its own id range). Nothing is committed: {@link #commitDay} makes the files the day's data.
     */
    public static List<DataFile> writeDay(Table table, LocalDate date, Iterator<Row> sorted, int fileRows) {
        Schema schema = table.schema();
        PartitionSpec spec = table.spec();
        PartitionData partition = partition(spec, date);
        List<Types.NestedField> promoted = schema.columns().stream()
                .filter(f -> !f.name().equals(ID) && !f.name().equals(DOC) && !f.name().equals(DATE)).toList();
        List<DataFile> files = new ArrayList<>();
        GenericRecord record = GenericRecord.create(schema);
        DataWriter<org.apache.iceberg.data.Record> writer = null;
        long rows = 0;
        try {
            while (sorted.hasNext()) {
                Row row = sorted.next();
                if (writer == null) {
                    String name = date + "-" + String.format("%05d", files.size()) + "-" + UUID.randomUUID() + ".parquet";
                    writer = Parquet.writeData(table.io().newOutputFile(table.locationProvider().newDataLocation(spec, partition, name)))
                            .forTable(table).withPartition(partition).withSortOrder(table.sortOrder())
                            .createWriterFunc(GenericParquetWriter::create).overwrite().build();
                }
                record.setField(ID, row.id());
                record.setField(DOC, row.doc());
                record.setField(DATE, date);
                for (Types.NestedField f : promoted) {
                    String path = f.name().replace("__", ".");
                    record.setField(f.name(), row.columns().containsKey(path) ? value(f.type(), row.columns().get(path)) : null);
                }
                writer.write(record);
                if (++rows % fileRows == 0) {
                    writer.close();
                    files.add(writer.toDataFile());
                    writer = null;
                }
            }
            if (writer != null) {
                writer.close();
                files.add(writer.toDataFile());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** A promoted value as the column's type: a number column takes numbers (text that parses), text takes anything. */
    static Object value(Type type, Object v) {
        if (v == null) {
            return null;
        }
        if (type.typeId() == Type.TypeID.DOUBLE) {
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
            return String.valueOf(d.longValue());                     // 42.0 promoted into a text column reads "42"
        }
        return String.valueOf(v);
    }

    /**
     * Makes {@code files} the business day's data in one commit: the day's previous files (if any) are replaced, so
     * loading a day again is idempotent and a reader sees the old day or the new one, never a mix.
     */
    public static void commitDay(Table table, LocalDate date, List<DataFile> files) {
        commitDays(table, Map.of(date, files));
    }

    /** {@link #commitDay} for several days in one commit (each listed day replaced by its files). */
    public static void commitDays(Table table, Map<LocalDate, List<DataFile>> days) {
        if (days.isEmpty()) {
            return;
        }
        Object[] dates = days.keySet().stream().map(LocalDate::toString).toArray();
        OverwriteFiles o = table.newOverwrite().overwriteByRowFilter(Expressions.in(DATE, dates));
        days.values().forEach(files -> files.forEach(o::addFile));
        o.set("drishti.business-dates", days.size() == 1 ? dates[0].toString() : days.size() + " days").commit();
    }

    /** The table's file-rows (the layout's), or the default. */
    public static int fileRows(Table table) {
        return Integer.parseInt(table.properties().getOrDefault(FILE_ROWS, String.valueOf(DEFAULT_FILE_ROWS)));
    }
}
