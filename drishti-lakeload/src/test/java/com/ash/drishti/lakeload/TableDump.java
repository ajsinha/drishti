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

import com.ash.drishti.deltalake.NativeEngine;
import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.read.TaskReader;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.types.NestedField;
import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.data.ArrayValue;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.ArrayType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A whole table read back as plain values, every row and every column, so two tables compare with {@code equals}: a row is
 * {@code {column: value}} with the date as yyyy-MM-dd and a vector as a list (null elements kept), keyed {@code date|id} (a duplicate
 * id gets {@code #2}, {@code #3}).
 */
final class TableDump {

    private TableDump() {}

    /** The latest version of a Delta table; {@code settings} the S3 ones (empty for local). */
    static Map<String, Map<String, Object>> delta(String table, Map<String, String> settings) throws Exception {
        Engine engine = settings.isEmpty() ? NativeEngine.create() : NativeEngine.create(settings);
        Snapshot s = io.delta.kernel.Table.forPath(engine, table).getLatestSnapshot(engine);
        Scan scan = s.getScanBuilder().build();
        Row state = scan.getScanState(engine);
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
        Map<String, Map<String, Object>> out = new TreeMap<>();
        try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> fileRows = files.next().getRows()) {
                    while (fileRows.hasNext()) {
                        Row file = fileRows.next();
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(file);
                        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler().readParquetFiles(Utils.singletonCloseableIterator(fs),
                                physical, Optional.empty());
                             CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, state, file, raw.map(FileReadResult::getData))) {
                            while (data.hasNext()) {
                                FilteredColumnarBatch b = data.next();
                                StructType schema = b.getData().getSchema();
                                int n = b.getData().getSize();
                                Optional<ColumnVector> sel = b.getSelectionVector();
                                for (int r = 0; r < n; r++) {
                                    if (sel.isPresent() && (sel.get().isNullAt(r) || !sel.get().getBoolean(r))) {
                                        continue;
                                    }
                                    Map<String, Object> row = new TreeMap<>();
                                    for (int c = 0; c < schema.length(); c++) {
                                        row.put(schema.at(c).getName(), value(b.getData().getColumnVector(c), r));
                                    }
                                    put(out, row);
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Object value(ColumnVector v, int r) {
        if (v.isNullAt(r)) {
            return null;
        }
        DataType t = v.getDataType();
        if (t instanceof DoubleType) {
            return v.getDouble(r);
        }
        if (t instanceof LongType) {
            return (double) v.getLong(r);
        }
        if (t instanceof DateType) {
            return LocalDate.ofEpochDay(v.getInt(r)).toString();
        }
        if (t instanceof ArrayType) {
            ArrayValue a = v.getArray(r);
            ColumnVector e = a.getElements();
            List<Double> out = new ArrayList<>();
            for (int i = 0; i < a.getSize(); i++) {
                out.add(e.isNullAt(i) ? null : e.getDataType() instanceof FloatType ? (double) e.getFloat(i) : e.getDouble(i));
            }
            return out;
        }
        return v.getString(r);
    }

    /** The current snapshot of an Iceberg table {@code <root>/<domain>/<table>} (or a catalog's: {@code settings}). */
    static Map<String, Map<String, Object>> iceberg(String root, String domain, String table, Map<String, String> settings) throws Exception {
        Map<String, String> s = new java.util.HashMap<>(settings);
        s.put("root", root);
        s.put("domain", domain);
        Map<String, Map<String, Object>> out = new TreeMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Catalog c = Catalogs.open(s)) {
            Table t = c.load(table).orElseThrow();
            var schema = t.metadata().schema();
            List<Integer> ids = schema.columns().stream().map(NestedField::id).toList();
            for (FileTask task : t.plan(t.currentSnapshot().orElseThrow(), Expr.TRUE, pool)) {
                try (TaskReader rows = t.rows(task, schema, ids, Expr.TRUE, false)) {
                    while (rows.hasNext()) {
                        Object[] v = rows.next();
                        Map<String, Object> row = new TreeMap<>();
                        for (int i = 0; i < ids.size(); i++) {
                            row.put(schema.columns().get(i).name(), iceberg(v[i]));
                        }
                        put(out, row);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    private static Object iceberg(Object v) {
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof Integer days) {
            return LocalDate.ofEpochDay(days).toString();
        }
        if (v instanceof List<?> l) {
            List<Double> out = new ArrayList<>();
            l.forEach(x -> out.add(x == null ? null : ((Number) x).doubleValue()));
            return out;
        }
        if (v instanceof CharSequence cs) {
            return cs.toString();
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return v;
    }

    private static void put(Map<String, Map<String, Object>> out, Map<String, Object> row) {
        String key = row.get("business_date") + "|" + row.get("id");
        String k = key;
        for (int i = 2; out.containsKey(k); i++) {
            k = key + "#" + i;
        }
        out.put(k, row);
    }

    /** The rows with their {@code doc} values only (what any two paths must agree on whatever their column sets). */
    static Map<String, Object> docs(Map<String, Map<String, Object>> t) {
        Map<String, Object> out = new TreeMap<>();
        t.forEach((k, r) -> out.put(k, r.get("doc")));
        return out;
    }

    /** Drops the columns that are null on every row (a column one path makes and the other does not). */
    static Map<String, Map<String, Object>> withoutEmptyColumns(Map<String, Map<String, Object>> t) {
        java.util.Set<String> used = new java.util.HashSet<>();
        t.values().forEach(r -> r.forEach((c, v) -> {
            if (v != null) {
                used.add(c);
            }
        }));
        Map<String, Map<String, Object>> out = new TreeMap<>();
        t.forEach((k, r) -> {
            Map<String, Object> x = new TreeMap<>(r);
            x.keySet().retainAll(used);
            out.put(k, x);
        });
        return out;
    }
}
