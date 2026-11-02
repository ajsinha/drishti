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

import com.ash.drishti.lakeio.FileEntry;
import com.ash.drishti.lakeio.mem.MemoryStorage;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The upkeep of a Delta table held in memory (docs/architecture/IN_MEMORY_LAKE.md, sections 5, 6 and 13a): its rows read back as staged
 * lines (a merge, a compaction), its old versions expired the moment they are past {@code keep-versions} (a version still readable holds
 * its files; one past it costs memory for nothing), its files measured. The caller holds the table's write lock: a file written but not
 * committed yet would otherwise look unreferenced.
 */
public final class TableUpkeep {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LOG_FILE = Pattern.compile("^(\\d{20})\\..*");

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
        Snapshot s = Table.forPath(engine, table).getLatestSnapshot(engine);
        StructType schema = s.getSchema();
        List<String> parts = s.getPartitionColumnNames();
        for (StructField f : schema.fields()) {
            if (!parts.contains(f.getName()) && !simple(f.getDataType())) {
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
            String type = t instanceof DoubleType ? "double" : t instanceof LongType || t instanceof IntegerType ? "long" : t instanceof DateType ? "date" : "string";
            out.add(new LakeManifest.Column(f.getName(), type));
        }
        return out;
    }

    /** The table's partition columns. */
    public static List<String> partitionColumns(Engine engine, String table) {
        return Table.forPath(engine, table).getLatestSnapshot(engine).getPartitionColumnNames();
    }

    /** The data files of a version, as qualified paths of {@code store}. */
    static Set<String> filesOf(Engine engine, MemoryStorage store, Snapshot s) throws IOException {
        Set<String> out = new HashSet<>();
        try (CloseableIterator<FilteredColumnarBatch> files = s.getScanBuilder().build().getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> rows = files.next().getRows()) {
                    while (rows.hasNext()) {
                        out.add(store.qualify(InternalScanFileUtils.getAddFileStatus(rows.next()).getPath()));
                    }
                }
            }
        }
        return out;
    }

    /** The table's shape now: version, data files and their bytes, files under {@code smallBytes}, rows (from the files' statistics). */
    public static Shape shape(Engine engine, MemoryStorage store, String table, long smallBytes) throws IOException {
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

    /**
     * Frees what versions past {@code keepVersions} held: data files no kept version references (and files of writes that never
     * committed), and the log before the newest checkpoint at or below the oldest kept version. Returns the bytes freed (chunks).
     */
    public static long expire(Engine engine, MemoryStorage store, String table, int keepVersions) throws IOException {
        Table t = Table.forPath(engine, table);
        Snapshot latest = t.getLatestSnapshot(engine);
        long v = latest.getVersion();
        long oldest = Math.max(0, v - Math.max(1, keepVersions) + 1);
        Set<String> live = new HashSet<>(filesOf(engine, store, latest));
        for (long x = oldest; x < v; x++) {
            live.addAll(filesOf(engine, store, t.getSnapshotAsOfVersion(engine, x)));
        }
        String root = store.qualify(table);
        String log = root + "/_delta_log/";
        long freed = 0;
        for (FileEntry f : store.filesIn(table)) {
            if (f.path().startsWith(log) || live.contains(f.path())) {
                continue;
            }
            freed += chunked(store, f.size());
            store.delete(f.path());
        }
        long checkpoint = lastCheckpoint(store, log);
        if (checkpoint > 0 && checkpoint <= oldest) {
            for (FileEntry f : store.filesIn(root + "/_delta_log")) {
                String name = f.path().substring(log.length());
                Matcher m = LOG_FILE.matcher(name);
                if (m.matches() && Long.parseLong(m.group(1)) < checkpoint) {
                    freed += chunked(store, f.size());
                    store.delete(f.path());
                }
            }
        }
        return freed;
    }

    private static long chunked(MemoryStorage store, long length) {
        int c = store.pool().chunkBytes();
        return (length + c - 1) / c * c;
    }

    private static long lastCheckpoint(MemoryStorage store, String log) {
        try {
            String text = new String(store.readAll(log + "_last_checkpoint"), StandardCharsets.UTF_8);
            return JSON.readTree(text).path("version").asLong(-1);
        } catch (IOException e) {
            return -1;
        }
    }
}
