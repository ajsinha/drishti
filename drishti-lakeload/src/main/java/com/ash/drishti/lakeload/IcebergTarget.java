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
package com.ash.drishti.lakeload;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.meta.Transform;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.write.Operations;
import com.ash.drishti.iceberg.write.ParquetDataWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Iceberg table in the Iceberg connector's layout ({@code IcebergLayout} of drishti-plugin-iceberg, as {@code lake build --format
 * iceberg} and the Iceberg loader write it): field ids 1 {@code id} (required), 2 {@code doc}, 3 {@code business_date} (required), then the
 * partition and promoted columns ({@code double} or {@code string}) and the vector columns ({@code list<double>}, optional elements); an
 * identity partition on {@code business_date}; the sort order by id; zstd; the layout's table properties. Through drishti-iceberg (the
 * native engine, no Hadoop): a path-based table under {@code <lake>/<domain>/<table>} on disk or S3, or a REST catalog
 * ({@code target.iceberg}). Each date is ONE commit: an overwrite that replaces the date's partition with the new files.
 *
 * <p>Sorted by other columns than the id ({@code sort-by}), the files say so: they carry no sort order id, and the table gets the property
 * {@code drishti.layout.sort-by}, which lake maintain reads so that it does not re-sort such a day by id.
 */
final class IcebergTarget implements TableTarget {

    /** The table property naming a layout sorted by more than the id (lake maintain leaves such days in their order). */
    static final String SORT_BY = "drishti.layout.sort-by";
    private static final int DEFAULT_FILE_ROWS = 250_000;
    private static final long DEFAULT_ROW_GROUP = 1024L * 1024;

    private final LoadConfig cfg;
    private final RowPlan plan;
    private final Catalog catalog;
    private final String name;
    private Table table;
    private Schema schema;
    private Source[] sources;
    private Map<String, String> options;
    private int dateIndex;
    private Integer sortOrderId;
    private final List<String> leftOut = new ArrayList<>();

    IcebergTarget(LoadConfig cfg, RowPlan plan) {
        this.cfg = cfg;
        this.plan = plan;
        String[] dt = cfg.domainAndTable();
        Map<String, String> settings = new HashMap<>(cfg.target().storage());
        settings.putAll(cfg.target().iceberg());
        settings.putIfAbsent("root", cfg.lakeRoot());
        settings.put("domain", dt[0]);
        this.catalog = Catalogs.open(settings);
        this.name = dt[1];
    }

    @Override
    public void open(Columns c) throws IOException {
        Map<String, Type> want = new LinkedHashMap<>();
        for (String p : plan.partition) {
            want.put(RowPlan.column(p), Type.Primitive.STRING);
        }
        for (int i = 0; i < c.promoted().size(); i++) {
            // a number column when every value seen is a number; a column never seen with a value is text (as the Iceberg loader types it)
            want.put(c.promoted().get(i), c.numeric()[i] && c.seen()[i] ? Type.Primitive.DOUBLE : Type.Primitive.STRING);
        }
        List<String> vectors = plan.vectors.stream().map(RowPlan.Vector::column).toList();
        table = catalog.load(name).orElse(null);
        if (table == null) {
            List<NestedField> fields = new ArrayList<>();
            fields.add(NestedField.required(1, "id", Type.Primitive.STRING));
            fields.add(NestedField.optional(2, "doc", Type.Primitive.STRING));
            fields.add(NestedField.required(3, "business_date", Type.Primitive.DATE));
            int next = 4;
            for (Map.Entry<String, Type> e : want.entrySet()) {
                fields.add(NestedField.optional(next++, e.getKey(), e.getValue()));
            }
            for (String v : vectors) {
                int id = next++;
                fields.add(NestedField.optional(id, v, new Type.ListType(next++, false, Type.Primitive.DOUBLE)));
            }
            PartitionSpec spec = new PartitionSpec(0, List.of(new PartitionSpec.Field(3, 1000, "business_date", Transform.Identity.INSTANCE)));
            SortOrder order = new SortOrder(1, List.of(new SortOrder.Field(1, Transform.Identity.INSTANCE, true, true)));
            try {
                table = catalog.create(name, new Schema(0, fields), spec, order, properties());
            } catch (Catalog.TableExists e) {
                table = catalog.load(name).orElseThrow(() -> e);        // another writer created it
            }
        }
        Map<String, Type> missing = new LinkedHashMap<>();
        want.forEach((col, type) -> {
            if (table.metadata().schema().field(col) == null) {
                missing.put(col, type);
            }
        });
        for (String v : vectors) {
            if (table.metadata().schema().field(v) == null) {
                missing.put(v, new Type.ListType(0, false, Type.Primitive.DOUBLE));     // ids assigned by the commit
            }
        }
        if (!missing.isEmpty()) {
            Operations.addColumns(table, missing);
            table.refresh();
        }
        if (!plan.sortedById() && !String.join(",", plan.sortNames).equals(table.metadata().property(SORT_BY, ""))) {
            Operations.setProperties(table, Map.of(SORT_BY, String.join(",", plan.sortNames)));
            table.refresh();
        }
        schema = table.metadata().schema();
        sources = TableTarget.sources(schema, plan);
        dateIndex = dateIndex(table);
        sortOrderId = plan.sortedById() ? table.metadata().sortOrder().orderId() : 0;
        List<String> listCols = new ArrayList<>();
        for (NestedField f : schema.columns()) {
            if (f.type() instanceof Type.ListType) {
                listCols.add(f.name());
            }
        }
        options = TableTarget.writerOptions(table.metadata().properties(), cfg.target().layout(),
                table.metadata().property("write.parquet.compression-codec", "zstd"), listCols);
    }

    /** The layout's table properties (as the Iceberg loader creates a table), with the sort columns when they are more than the id. */
    private Map<String, String> properties() {
        long rowGroup = cfg.target().layout().rowGroupBytes() > 0 ? cfg.target().layout().rowGroupBytes() : DEFAULT_ROW_GROUP;
        Map<String, String> p = new LinkedHashMap<>();
        p.put("format-version", "2");
        p.put("write.parquet.row-group-size-bytes", String.valueOf(rowGroup));
        p.put("write.parquet.compression-codec", "zstd");
        p.put("write.parquet.dict-size-bytes", String.valueOf(256 * 1024));
        p.put("write.parquet.row-group-check-min-record-count", "10");
        p.put("write.metadata.metrics.column.doc", "none");
        p.put("write.metadata.metrics.column.id", "full");
        p.put("write.metadata.delete-after-commit.enabled", "true");
        p.put("write.metadata.previous-versions-max", "50");
        p.put("commit.retry.num-retries", "10");
        p.put("drishti.layout.file-rows", String.valueOf(DEFAULT_FILE_ROWS));
        if (!plan.sortedById()) {
            p.put(SORT_BY, String.join(",", plan.sortNames));
        }
        return p;
    }

    private static int dateIndex(Table t) {
        PartitionSpec spec = t.metadata().spec();
        NestedField date = t.metadata().schema().field("business_date");
        for (int i = 0; i < spec.fields().size(); i++) {
            if (date != null && spec.fields().get(i).sourceId() == date.id() && spec.fields().get(i).transform() instanceof Transform.Identity) {
                return i;
            }
        }
        throw new IllegalStateException(t.name() + " is not in the layout: no identity partition on business_date");
    }

    private LocalDate dateOf(DataFile f) {
        PartitionSpec spec = table.metadata().spec(f.specId());
        NestedField date = table.metadata().schema().field("business_date");
        for (int i = 0; i < spec.fields().size(); i++) {
            if (date != null && spec.fields().get(i).sourceId() == date.id() && spec.fields().get(i).transform() instanceof Transform.Identity) {
                Object v = f.partition()[i];
                return v == null ? null : LocalDate.ofEpochDay(((Number) v).longValue());
            }
        }
        return null;
    }

    @Override
    public void begin(LocalDate date) {}

    @Override
    public FileOut newFile(LocalDate date, String[] partitionValues, String fileName) throws IOException {
        PartitionSpec spec = table.metadata().spec();
        Object[] partition = new Object[spec.fields().size()];
        partition[dateIndex] = (int) date.toEpochDay();
        String path = table.metadata().location().replaceAll("/+$", "") + "/data/business_date=" + date + "/" + fileName;
        ParquetDataWriter w = new ParquetDataWriter(table.io(), path, schema, options, spec.specId(), partition, sortOrderId);
        return new ParquetOut(w, schema, sources, date, true, path, partitionValues, x -> {
            try {
                return x.finish();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @Override
    public long commit(LocalDate date, List<WrittenFile> files) {
        List<DataFile> add = new ArrayList<>();
        files.forEach(f -> add.add((DataFile) f.handle()));
        Snapshot s = Operations.replacePartitions(table, f -> date.equals(dateOf(f)), add, Map.of("drishti.business-dates", date.toString(),
                "drishti.loader", "parallel")).orElseThrow(() -> new IllegalStateException("the commit of " + date + " made no snapshot"));
        return s.snapshotId();
    }

    @Override
    public Map<String, String> columnTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (NestedField f : schema.columns()) {
            out.put(f.name(), f.type() instanceof Type.ListType ? "vector" : f.type().toString().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    @Override
    public List<String> leftOut() {
        return leftOut;
    }

    @Override
    public void close() {
        catalog.close();
    }
}
