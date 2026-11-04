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

import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.deltalake.write.TableUpkeep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.delta.kernel.Operation;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Undo a load by its id (docs/architecture/DATA_LOAD_RULES.md section 6.3): every row a load wrote carries its {@code _load_id}; undoing
 * it removes exactly those rows, one commit per table and date, in every table of the lake that has them (an archive's tables together).
 * A load that replaced a date is undone by restoring the date as it was before it (the rows of the version before the load's commit), with
 * the rows later loads appended kept. Delta tables here; Iceberg tables by the Iceberg maintenance ({@code IcebergMaintenance --undo}).
 *
 * <pre>LakeUndo --lake ROOT --load-id ID [--table domain/table ...] [--dry-run] [--json]</pre>
 * Exit codes: 0 undone (or nothing to undo, said so), 1 failed.
 */
public final class LakeUndo {

    static final ObjectMapper JSON = new ObjectMapper();

    /** What undoing did to one table and date: the load's rows removed, rows restored from before a replace, the version committed. */
    public record Done(String table, String date, long removed, long restored, boolean replaced, long version) {}

    private LakeUndo() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    public static int run(String[] args, PrintStream out) {
        String root = null;
        String id = null;
        List<String> only = new ArrayList<>();
        boolean dry = false;
        boolean json = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--lake" -> root = args[++i];
                case "--load-id" -> id = args[++i];
                case "--table" -> only.add(args[++i]);
                case "--dry-run" -> dry = true;
                case "--json" -> json = true;
                default -> {
                    out.println("usage: LakeUndo --lake ROOT --load-id ID [--table domain/table ...] [--dry-run] [--json]");
                    return 1;
                }
            }
        }
        if (root == null || id == null) {
            out.println("lakeundo: give --lake ROOT and --load-id ID (a load's id: its summary's loads, or a row's _load_id)");
            return 1;
        }
        try {
            List<Done> done = undo(root, id, only, dry);
            if (json) {
                out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(done));
            } else if (done.isEmpty()) {
                out.println("lakeundo: no row of load " + id + " in " + root);
            } else {
                for (Done d : done) {
                    out.println((dry ? "would undo " : "undone ") + d.table() + " " + d.date() + ": " + d.removed() + " rows of load " + id + " removed"
                            + (d.replaced() ? ", " + d.restored() + " rows of the date before it restored" : "") + (dry ? "" : " (version " + d.version() + ")"));
                }
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            out.println("lakeundo: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Undoes load {@code id} in every table of the lake at {@code root}, Delta here and Iceberg by the Iceberg maintenance when it is on the
     * class path (the server's): a line per table and date ({@code table}, {@code format}, {@code date}, {@code removed}, {@code restored},
     * {@code replaced}). {@code only}: these {@code domain/table}s (Delta); empty: every one.
     */
    public static List<Map<String, Object>> undoAll(String root, String id, List<String> only, boolean dryRun) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Done d : undo(root, id, only, dryRun)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", d.table());
            m.put("format", "delta");
            m.put("date", d.date());
            m.put("removed", d.removed());
            m.put("restored", d.restored());
            m.put("replaced", d.replaced());
            out.add(m);
        }
        Class<?> ice;
        try {
            ice = Class.forName("com.ash.drishti.plugin.iceberg.IcebergMaintenance");
        } catch (ClassNotFoundException e) {
            return out;                                         // no Iceberg here: Delta tables only
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) ice.getMethod("undoLake", String.class, String.class, boolean.class, Path.class)
                .invoke(null, root, id, dryRun, Path.of(System.getProperty("java.io.tmpdir")));
        for (Map<String, Object> u : lines) {
            Map<String, Object> m = new LinkedHashMap<>(u);
            m.put("format", "iceberg");
            out.add(m);
        }
        return out;
    }

    /** Undoes load {@code id} in the Delta tables under {@code root} ({@code only}: these {@code domain/table}s; empty: every one). */
    public static List<Done> undo(String root, String id, List<String> only, boolean dryRun) throws IOException {
        List<Done> out = new ArrayList<>();
        for (Path t : deltaTables(Path.of(root))) {
            String name = Path.of(root).relativize(t).toString().replace('\\', '/');
            if (!only.isEmpty() && !only.contains(name)) {
                continue;
            }
            out.addAll(undoTable(t.toString(), name, id, dryRun));
        }
        return out;
    }

    /** The Delta tables under the lake root: {@code <domain>/<table>/_delta_log}. */
    static List<Path> deltaTables(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> domains = Files.list(root)) {
            for (Path d : domains.filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> tables = Files.list(d)) {
                    tables.filter(t -> Files.isDirectory(t.resolve("_delta_log"))).sorted().forEach(out::add);
                }
            }
        }
        return out;
    }

    /** One table: each date whose files may hold the load's rows is rewritten without them (restored from before when it replaced). */
    static List<Done> undoTable(String table, String name, String id, boolean dryRun) throws IOException {
        Engine engine = LakeWriter.engineFor(table, Map.of());
        Snapshot s;
        try {
            s = Table.forPath(engine, table).getLatestSnapshot(engine);
        } catch (TableNotFoundException e) {
            return List.of();
        }
        if (s.getSchema().indexOf(RowPlan.LOAD_ID) < 0) {
            return List.of();                                   // a table from before load ids: no load can be found in it
        }
        List<Done> out = new ArrayList<>();
        for (String date : candidateDates(engine, s, id)) {
            List<TableUpkeep.Staged> now = TableUpkeep.rows(engine, s, pv -> date.equals(pv.get("business_date")), true);
            if (now == null) {
                throw new IOException(name + ": a column the undo cannot carry");
            }
            List<TableUpkeep.Staged> kept = new ArrayList<>();
            long removed = 0;
            for (TableUpkeep.Staged r : now) {
                if (id.equals(JSON.readTree(r.line()).path("columns").path(RowPlan.LOAD_ID).asText(null))) {
                    removed++;
                } else {
                    kept.add(r);
                }
            }
            if (removed == 0) {
                continue;
            }
            long replacedAt = replacedAt(Path.of(table), date, id);
            long restored = 0;
            if (replacedAt > 0) {
                Snapshot before = Table.forPath(engine, table).getSnapshotAsOfVersion(engine, replacedAt - 1);
                List<TableUpkeep.Staged> old = TableUpkeep.rows(engine, before, pv -> date.equals(pv.get("business_date")), true);
                if (old != null) {
                    restored = old.size();
                    List<TableUpkeep.Staged> all = new ArrayList<>(old);
                    all.addAll(kept);
                    kept = all;
                }
            }
            long version = dryRun ? -1 : rewrite(engine, table, s, date, kept);
            out.add(new Done(name, date, removed, restored, replacedAt > 0, version));
        }
        return out;
    }

    /** The dates whose files' {@code _load_id} statistics may hold {@code id} (a file without them may too). */
    static List<String> candidateDates(Engine engine, Snapshot s, String id) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        try (CloseableIterator<FilteredColumnarBatch> it = s.getScanBuilder().build().getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        Row f = rows.next();
                        String date = InternalScanFileUtils.getPartitionValues(f).get("business_date");
                        String[] b = bounds(f);
                        if (date != null && (b[0] == null || b[1] == null || (b[0].compareTo(id) <= 0 && b[1].compareTo(id) >= 0))) {
                            out.add(date);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** A scan file's {@code _load_id} minimum and maximum from its add action's statistics (null when not stated). */
    private static String[] bounds(Row scanFile) {
        try {
            Row add = scanFile.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL);
            int at = add.getSchema().indexOf("stats");
            if (at < 0 || add.isNullAt(at)) {
                return new String[2];
            }
            JsonNode st = JSON.readTree(add.getString(at));
            return new String[] {st.path("minValues").path(RowPlan.LOAD_ID).asText(null), st.path("maxValues").path(RowPlan.LOAD_ID).asText(null)};
        } catch (IOException | RuntimeException e) {
            return new String[2];
        }
    }

    /**
     * The version of the commit that wrote the load's rows of {@code date} when that commit also removed the date's earlier files (a replace),
     * else 0: read from the table's log (a local table; one in object storage is undone as an append).
     */
    static long replacedAt(Path table, String date, String id) throws IOException {
        Path log = table.resolve("_delta_log");
        if (!Files.isDirectory(log)) {
            return 0;
        }
        List<Path> commits;
        try (Stream<Path> s = Files.list(log)) {
            commits = s.filter(p -> p.getFileName().toString().matches("\\d{20}\\.json")).sorted().toList().reversed();
        }
        for (Path c : commits) {
            boolean adds = false;
            boolean removes = false;
            for (String line : Files.readAllLines(c, StandardCharsets.UTF_8)) {
                JsonNode n = JSON.readTree(line);
                JsonNode add = n.get("add");
                if (add != null && date.equals(add.path("partitionValues").path("business_date").asText(null))) {
                    String stats = add.path("stats").asText("");
                    JsonNode st = stats.isEmpty() ? null : JSON.readTree(stats);
                    String lo = st == null ? null : st.path("minValues").path(RowPlan.LOAD_ID).asText(null);
                    String hi = st == null ? null : st.path("maxValues").path(RowPlan.LOAD_ID).asText(null);
                    adds |= lo != null && hi != null && lo.compareTo(id) <= 0 && hi.compareTo(id) >= 0;
                }
                JsonNode rem = n.get("remove");
                if (rem != null && date.equals(rem.path("partitionValues").path("business_date").asText(null))) {
                    removes = true;
                }
            }
            if (adds) {
                return removes ? Long.parseLong(c.getFileName().toString().substring(0, 20)) : 0;
            }
        }
        return 0;
    }

    /** The date rewritten with {@code rows} (one commit: its files replaced); with none, the date's files removed. Returns the version. */
    static long rewrite(Engine engine, String table, Snapshot s, String date, List<TableUpkeep.Staged> rows) throws IOException {
        List<String> partitions = s.getPartitionColumnNames();
        if (rows.isEmpty()) {
            List<Row> removes = LakeWriter.removals(engine, s, pv -> date.equals(pv.get("business_date")));
            var txn = Table.forPath(engine, table).createTransactionBuilder(engine, "Drishti lake undo", Operation.WRITE).build(engine);
            return txn.commit(engine, CloseableIterable.inMemoryIterable(toIterator(removes))).getVersion();
        }
        Path dir = Files.createTempDirectory("drishti-undo-");
        try {
            Map<Map<String, String>, BufferedWriter> files = new LinkedHashMap<>();
            Map<Map<String, String>, Path> paths = new LinkedHashMap<>();
            Map<Map<String, String>, Long> counts = new LinkedHashMap<>();
            for (TableUpkeep.Staged r : rows) {
                Map<String, String> pv = r.partitionValues();
                BufferedWriter w = files.get(pv);
                if (w == null) {
                    Path p = dir.resolve("part-" + files.size() + ".rows");
                    w = Files.newBufferedWriter(p, StandardCharsets.UTF_8);
                    files.put(pv, w);
                    paths.put(pv, p);
                }
                w.write(r.line());
                w.write('\n');
                counts.merge(pv, 1L, Long::sum);
            }
            for (BufferedWriter w : files.values()) {
                w.close();
            }
            List<LakeManifest.Column> columns = TableUpkeep.columns(engine, table);
            List<String> promoted = new ArrayList<>();
            for (LakeManifest.Column c : columns) {
                if (!List.of("id", "doc").contains(c.name()) && !partitions.contains(c.name())) {
                    promoted.add(c.name());
                }
            }
            List<LakeManifest.Part> parts = new ArrayList<>();
            paths.forEach((pv, p) -> parts.add(new LakeManifest.Part(p, counts.get(pv), pv)));
            LakeManifest m = new LakeManifest(table, partitions, columns, promoted, "overwrite-dates", 50_000,
                    List.of("id", "business_date", RowPlan.LOAD_ID), parts);
            return LakeWriter.forTable(table, Map.of()).write(m, n -> { }).version();
        } finally {
            try (Stream<Path> s2 = Files.walk(dir)) {
                s2.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static <T> CloseableIterator<T> toIterator(List<T> list) {
        java.util.Iterator<T> it = list.iterator();
        return new CloseableIterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public T next() {
                return it.next();
            }

            @Override
            public void close() {
            }
        };
    }

    /** The undo's lines as JSON (for the server's Admin answer). */
    static ArrayNode json(List<Done> done) {
        ArrayNode a = JSON.createArrayNode();
        for (Done d : done) {
            ObjectNode n = a.addObject();
            n.put("table", d.table()).put("date", d.date()).put("removed", d.removed()).put("restored", d.restored()).put("replaced", d.replaced())
                    .put("version", d.version());
        }
        return a;
    }
}
