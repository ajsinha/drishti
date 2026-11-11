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
package com.ash.drishti.deltalake.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The upkeep of a Delta table that is rewritten in place (the in-memory lake's merges and compactions, HOW_IT_FITS.md
 * sections 5 and 13a): its rows read back as staged lines, its columns and partition columns, its files measured. Freeing what old versions
 * held is the in-memory lake's own ({@code drishti-lakeload}), since only a memory store frees files that way.
 */
public final class TableUpkeep {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TableUpkeep() {}

    /** A staged row of the table: its partition values and its line ({@code {"id", "doc", "columns"}}), as the lake writer reads it. */
    public record Staged(Map<String, String> partitionValues, String id, String line) {}

    /** What a table holds now: its version, data files, bytes (their lengths), and how many are below a size. */
    public record Shape(long version, int files, long bytes, int smallFiles, long rows) {}

    /**
     * The rows of the latest version whose partition values {@code keep} accepts, as staged lines; null when the table has a column the
     * lines cannot carry (a vector), so it is left as it is.
     */
    public static List<Staged> rows(Engine engine, String table, Predicate<Map<String, String>> keep) throws IOException {
        return rows(engine, Table.forPath(engine, table).getLatestSnapshot(engine), keep, false);
    }

    /**
     * The rows of snapshot {@code s} whose partition values {@code keep} accepts, as staged lines; with {@code vectors} a {@code list<double>}
     * or {@code list<float>} column is carried too (a JSON array), else such a table gives null.
     */
    public static List<Staged> rows(Engine engine, Snapshot s, Predicate<Map<String, String>> keep, boolean vectors) throws IOException {
        StructType schema = s.getSchema();
        List<String> parts = s.getPartitionColumnNames();
        for (StructField f : schema.fields()) {
            if (!parts.contains(f.getName()) && !simple(f.getDataType()) && !(vectors && vector(f.getDataType()))) {
                return null;
            }
        }
        Scan scan = s.getScanBuilder().build();
        Row state = scan.getScanState(engine);
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
        List<Staged> out = new ArrayList<>();
        try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> fileRows = files.next().getRows()) {
                    while (fileRows.hasNext()) {
                        Row file = fileRows.next();
                        Map<String, String> pv = InternalScanFileUtils.getPartitionValues(file);
                        if (!keep.test(pv)) {
                            continue;
                        }
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(file);
                        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler()
                                .readParquetFiles(Utils.singletonCloseableIterator(fs), physical, Optional.empty());
                             CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, state, file,
                                     raw.map(FileReadResult::getData))) {
                            while (data.hasNext()) {
                                try (CloseableIterator<Row> rows = data.next().getRows()) {
                                    while (rows.hasNext()) {
                                        out.add(staged(rows.next(), parts, new LinkedHashMap<>(pv)));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean vector(DataType t) {
        return t instanceof io.delta.kernel.types.ArrayType a
                && (a.getElementType() instanceof DoubleType || a.getElementType() instanceof io.delta.kernel.types.FloatType);
    }

    private static boolean simple(DataType t) {
        return t instanceof StringType || t instanceof DoubleType || t instanceof LongType || t instanceof DateType || t instanceof IntegerType;
    }

    private static Staged staged(Row r, List<String> parts, Map<String, String> pv) {
        StructType sc = r.getSchema();
        ObjectNode line = JSON.createObjectNode();
        ObjectNode columns = JSON.createObjectNode();
        String id = null;
        for (int i = 0; i < sc.length(); i++) {
            StructField f = sc.at(i);
            String name = f.getName();
            if (parts.contains(name) || r.isNullAt(i)) {
                continue;
            }
            DataType t = f.getDataType();
            if (name.equals("id")) {
                id = r.getString(i);
                line.put("id", id);
            } else if (name.equals("doc")) {
                line.put("doc", r.getString(i));
            } else if (t instanceof StringType) {
                columns.put(name, r.getString(i));
            } else if (t instanceof DoubleType) {
                columns.put(name, r.getDouble(i));
            } else if (t instanceof LongType) {
                columns.put(name, r.getLong(i));
            } else if (t instanceof IntegerType) {
                columns.put(name, r.getInt(i));
            } else if (t instanceof DateType) {
                columns.put(name, LocalDate.ofEpochDay(r.getInt(i)).toString());
            } else if (vector(t)) {
                io.delta.kernel.data.ArrayValue a = r.getArray(i);
                io.delta.kernel.data.ColumnVector e = a.getElements();
                com.fasterxml.jackson.databind.node.ArrayNode arr = columns.putArray(name);
                boolean f32 = ((io.delta.kernel.types.ArrayType) t).getElementType() instanceof io.delta.kernel.types.FloatType;
                for (int k = 0; k < a.getSize(); k++) {
                    if (e.isNullAt(k)) {
                        arr.addNull();
                    } else {
                        arr.add(f32 ? (double) e.getFloat(k) : e.getDouble(k));
                    }
                }
            }
        }
        line.set("columns", columns);
        return new Staged(pv, id, line.toString());
    }

    /** The table's columns as a manifest names them, from its own schema (a rewrite keeps the table's schema). */
    public static List<LakeManifest.Column> columns(Engine engine, String table) {
        Snapshot s = Table.forPath(engine, table).getLatestSnapshot(engine);
        List<LakeManifest.Column> out = new ArrayList<>();
        for (StructField f : s.getSchema().fields()) {
            DataType t = f.getDataType();
            String type = t instanceof DoubleType ? "double" : t instanceof LongType || t instanceof IntegerType ? "long" : t instanceof DateType ? "date"
                    : vector(t) ? (((io.delta.kernel.types.ArrayType) t).getElementType() instanceof io.delta.kernel.types.FloatType ? "vector32" : "vector")
                    : "string";
            out.add(new LakeManifest.Column(f.getName(), type));
        }
        return out;
    }

    /** The table's partition columns. */
    public static List<String> partitionColumns(Engine engine, String table) {
        return Table.forPath(engine, table).getLatestSnapshot(engine).getPartitionColumnNames();
    }

    /** The table's shape now: version, data files and their bytes, files under {@code smallBytes}, rows (from the files' statistics). */
    public static Shape shape(Engine engine, String table, long smallBytes) throws IOException {
        Snapshot s = Table.forPath(engine, table).getLatestSnapshot(engine);
        int files = 0;
        int small = 0;
        long bytes = 0;
        long rows = 0;
        try (CloseableIterator<FilteredColumnarBatch> it = s.getScanBuilder().build().getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rs = it.next().getRows()) {
                    while (rs.hasNext()) {
                        Row r = rs.next();
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(r);
                        files++;
                        bytes += fs.getSize();
                        small += fs.getSize() < smallBytes ? 1 : 0;
                        Row add = r.getStruct(r.getSchema().indexOf("add"));
                        int st = add.getSchema().indexOf("stats");
                        if (st >= 0 && !add.isNullAt(st)) {
                            Matcher m = Pattern.compile("\"numRecords\":(\\d+)").matcher(add.getString(st));
                            rows += m.find() ? Long.parseLong(m.group(1)) : 0;
                        }
                    }
                }
            }
        }
        return new Shape(s.getVersion(), files, bytes, small, files > 0 && rows == 0 ? -1 : rows);   // -1: the scan carried no statistics
    }
}
