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

import com.ash.drishti.common.RowRetention;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.deltalake.write.TableUpkeep;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Row retention of a durable table (docs/guides/DATA_LAKE_GUIDE.md, "Row retention"): the {@code retention: {ttl, on, max-rows}} of a memory
 * table, for a Delta table, an Iceberg table and a files store, by the same rule ({@link RowRetention}), run by the lake maintenance (the one
 * upkeep schedule: {@code lake maintain}). Each business date holding a row the rule drops is rewritten without it, one commit a date (a date
 * left empty has its files removed); the old files go when the table's vacuum (Delta) or snapshot expiry (Iceberg) says. Delta tables here;
 * Iceberg tables by {@code IcebergMaintenance.retentionTable} when the Iceberg plugin is on the class path (the server's); a files store's
 * {@code <root>/<domain>/<date>/<kind>.jsonl} day files are rewritten through a temporary file moved into place.
 *
 * <pre>LakeRetention --lake ROOT --format delta|iceberg|files --table domain/table [--ttl 8h] [--on FIELD] [--max-rows N] [--dry-run] [--json]
 * LakeRetention --lake ROOT --format delta|iceberg|files --table domain/table --dates 2026-11-16[,2026-11-01..2026-11-10] [--dry-run] [--json]</pre>
 * A table's age is its {@code on} field; without it the {@code arrived_at} column, else the row's business date. {@code --dates} removes
 * whole business dates instead ({@code lake delete}, DATA_LAKE_GUIDE.md section 5.8): every file of those dates, one commit for all of them
 * (a Delta remove commit, an Iceberg partition delete, a files store's day files deleted); its answer also lists the dates the table
 * held before ({@code held}). Exit codes: 0 done, 1 failed.
 */
public final class LakeRetention {

    static final ObjectMapper JSON = new ObjectMapper();

    private LakeRetention() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    public static int run(String[] args, PrintStream out) {
        String root = null;
        String format = "delta";
        String table = null;
        String ttl = null;
        String on = null;
        Integer max = null;
        String dates = null;
        boolean dry = false;
        boolean json = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--lake" -> root = args[++i];
                    case "--format" -> format = args[++i];
                    case "--table" -> table = args[++i];
                    case "--ttl" -> ttl = args[++i];
                    case "--on" -> on = args[++i];
                    case "--max-rows" -> max = Integer.valueOf(args[++i]);
                    case "--dates" -> dates = args[++i];
                    case "--dry-run" -> dry = true;
                    case "--json" -> json = true;
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (root == null || table == null) {
                throw new IllegalArgumentException("give --lake ROOT and --table domain/table");
            }
            if (dates != null) {
                Map<String, Object> done = dropDates(root, format, table, dates(dates), dry);
                out.println(json ? JSON.writeValueAsString(done) : table + ": " + done.get("dropped") + " rows " + (dry ? "would be deleted" : "deleted")
                        + " in " + ((List<?>) done.get("dates")).size() + " business dates");
                return 0;
            }
            Map<String, Object> done = apply(root, format, table, new RowRetention(ttl, on, max), Instant.now(), dry);
            out.println(json ? JSON.writeValueAsString(done) : table + ": " + done.get("dropped") + " rows " + (dry ? "would be dropped" : "dropped")
                    + " in " + ((List<?>) done.get("dates")).size() + " business dates");
            return 0;
        } catch (Exception e) {
            out.println("lakeretention: " + e.getMessage());
            return 1;
        }
    }

    /**
     * The retention applied to {@code domain/table} of the lake (or files store) at {@code root}: {@code table}, {@code format}, {@code rows}
     * (before), {@code dropped}, {@code dates} (a line per business date changed: {@code date}, {@code rows}, {@code dropped}).
     */
    public static Map<String, Object> apply(String root, String format, String table, RowRetention r, Instant now, boolean dryRun) throws Exception {
        List<Map<String, Object>> dates = switch (format) {
            case "delta" -> delta(root, table, r, now, dryRun);
            case "files" -> files(root, table, r, now, dryRun);
            case "iceberg" -> iceberg(root, table, r, dryRun);
            default -> throw new IllegalArgumentException("format " + format + " is delta, iceberg or files");
        };
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table", table);
        m.put("format", format);
        m.put("dryRun", dryRun);
        m.put("dropped", dates.stream().mapToLong(d -> ((Number) d.get("dropped")).longValue()).sum());
        m.put("dates", dates);
        return m;
    }

    /**
     * The dates a {@code --dates} list names: {@code yyyy-MM-dd} each, or a range {@code from..to} (both ends included), comma separated.
     * Every item is checked as a date (a typo is refused, never matched as nothing).
     */
    public static java.util.function.Predicate<String> dates(String spec) {
        java.util.function.Predicate<String> any = d -> false;
        for (String item : spec.split(",")) {
            String it = item.strip();
            if (it.isEmpty()) {
                continue;
            }
            int dots = it.indexOf("..");
            java.time.LocalDate from = java.time.LocalDate.parse(dots < 0 ? it : it.substring(0, dots).strip());
            java.time.LocalDate to = dots < 0 ? from : java.time.LocalDate.parse(it.substring(dots + 2).strip());
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("the range " + it + " ends before it starts");
            }
            any = any.or(d -> {
                try {
                    java.time.LocalDate x = java.time.LocalDate.parse(d);
                    return !x.isBefore(from) && !x.isAfter(to);
                } catch (java.time.format.DateTimeParseException e) {
                    return false;
                }
            });
        }
        return any;
    }

    /**
     * Whole business dates removed from {@code domain/table} ({@code lake delete}): {@code table}, {@code format}, {@code dryRun},
     * {@code held} (the dates the table held before), {@code dropped} (rows), {@code dates} (a line per date removed: {@code date},
     * {@code rows}, {@code dropped}; {@code rows} is -1 when a Delta file states no row count), {@code version} (Delta: the commit).
     */
    public static Map<String, Object> dropDates(String root, String format, String table, java.util.function.Predicate<String> wanted, boolean dryRun)
            throws Exception {
        Map<String, Object> got = switch (format) {
            case "delta" -> deltaDates(root, table, wanted, dryRun);
            case "files" -> filesDates(root, table, wanted, dryRun);
            case "iceberg" -> icebergDates(root, table, wanted, dryRun);
            default -> throw new IllegalArgumentException("format " + format + " is delta, iceberg or files");
        };
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) got.get("dates");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table", table);
        m.put("format", format);
        m.put("dryRun", dryRun);
        m.put("held", got.get("held"));
        m.put("dropped", lines.stream().mapToLong(d -> Math.max(0, ((Number) d.get("dropped")).longValue())).sum());
        m.put("dates", lines);
        if (got.containsKey("version")) {
            m.put("version", got.get("version"));
        }
        return m;
    }

    /** A Delta table: the dates' files removed in one commit (the writer's remove actions, as an undo that leaves a date empty). */
    static Map<String, Object> deltaDates(String root, String table, java.util.function.Predicate<String> wanted, boolean dryRun) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        String path = Path.of(root).resolve(table).toString();
        Engine engine = LakeWriter.engineFor(path, Map.of());
        Snapshot s;
        try {
            s = Table.forPath(engine, path).getLatestSnapshot(engine);
        } catch (TableNotFoundException e) {
            throw new IOException("no Delta table " + table + " in " + root);
        }
        java.util.SortedMap<String, Long> rows = rowsPerDate(engine, s);
        List<Map<String, Object>> lines = new ArrayList<>();
        rows.forEach((d, n) -> {
            if (wanted.test(d)) {
                line(lines, d, n, n);
            }
        });
        out.put("held", new ArrayList<>(rows.keySet()));
        out.put("dates", lines);
        if (!dryRun && !lines.isEmpty()) {
            java.util.Set<String> gone = new java.util.HashSet<>();
            lines.forEach(l -> gone.add((String) l.get("date")));
            out.put("version", LakeUndo.removeDates(engine, path, s, gone::contains));
        }
        return out;
    }

    /** The rows of each business date of a Delta snapshot, from its files' statistics (no data file opened; -1: a file without them). */
    static java.util.SortedMap<String, Long> rowsPerDate(Engine engine, Snapshot s) throws IOException {
        java.util.SortedMap<String, Long> out = new TreeMap<>();
        // the files' statistics come with the scan files only when asked for (ScanImpl's includeStats)
        try (io.delta.kernel.utils.CloseableIterator<io.delta.kernel.data.FilteredColumnarBatch> it =
                ((io.delta.kernel.internal.ScanImpl) s.getScanBuilder().build()).getScanFiles(engine, true)) {
            while (it.hasNext()) {
                try (io.delta.kernel.utils.CloseableIterator<io.delta.kernel.data.Row> files = it.next().getRows()) {
                    while (files.hasNext()) {
                        io.delta.kernel.data.Row f = files.next();
                        String d = io.delta.kernel.internal.InternalScanFileUtils.getPartitionValues(f).get("business_date");
                        if (d != null) {
                            out.merge(d, numRecords(f), (a, b) -> a < 0 || b < 0 ? -1 : a + b);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static long numRecords(io.delta.kernel.data.Row scanFile) {
        try {
            io.delta.kernel.data.Row add = scanFile.getStruct(io.delta.kernel.internal.InternalScanFileUtils.ADD_FILE_ORDINAL);
            int at = add.getSchema().indexOf("stats");
            if (at < 0 || add.isNullAt(at)) {
                return -1;
            }
            return JSON.readTree(add.getString(at)).path("numRecords").asLong(-1);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** A files store: the dates' day files ({@code <root>/<domain>/<date>/<kind>.jsonl}) deleted. */
    static Map<String, Object> filesDates(String root, String table, java.util.function.Predicate<String> wanted, boolean dryRun) throws IOException {
        int slash = table.indexOf('/');
        String domain = slash < 0 ? "" : table.substring(0, slash);
        String kind = table.substring(slash + 1);
        Path base = domain.isEmpty() ? Path.of(root) : Path.of(root).resolve(domain);
        List<String> held = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Path> gone = new ArrayList<>();
        if (Files.isDirectory(base)) {
            try (Stream<Path> days = Files.list(base)) {
                for (Path d : days.filter(Files::isDirectory).sorted().toList()) {
                    Path f = d.resolve(kind + ".jsonl");
                    if (!Files.isRegularFile(f)) {
                        continue;
                    }
                    String date = d.getFileName().toString();
                    held.add(date);
                    if (wanted.test(date)) {
                        long n;
                        try (Stream<String> ls = Files.lines(f, StandardCharsets.UTF_8)) {
                            n = ls.filter(l -> !l.isBlank()).count();
                        }
                        line(lines, date, n, n);
                        gone.add(f);
                    }
                }
            }
        }
        if (!dryRun) {
            for (Path f : gone) {
                Files.delete(f);                                       // a reader sees the whole day or none of it
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("held", held);
        out.put("dates", lines);
        return out;
    }

    /** An Iceberg table, by the Iceberg plugin (on the server's class path): whole partitions deleted, one commit. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> icebergDates(String root, String table, java.util.function.Predicate<String> wanted, boolean dryRun) throws Exception {
        Class<?> ice;
        try {
            ice = Class.forName("com.ash.drishti.plugin.iceberg.IcebergMaintenance");
        } catch (ClassNotFoundException e) {
            throw new IOException("the Iceberg plugin is not on the class path");
        }
        int slash = table.indexOf('/');
        try {
            return (Map<String, Object>) ice.getMethod("dropDates", String.class, String.class, String.class, java.util.function.Predicate.class, boolean.class)
                    .invoke(null, root, slash < 0 ? "" : table.substring(0, slash), table.substring(slash + 1), wanted, dryRun);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() instanceof Exception x ? x : e;
        }
    }

    private static List<Map<String, Object>> line(List<Map<String, Object>> out, String date, long rows, long dropped) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("rows", rows);
        m.put("dropped", dropped);
        out.add(m);
        return out;
    }

    /** A Delta table: every date holding a dropped row is rewritten (one commit), by the writer that commits a load. */
    static List<Map<String, Object>> delta(String root, String table, RowRetention r, Instant now, boolean dryRun) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        String path = Path.of(root).resolve(table).toString();
        Engine engine = LakeWriter.engineFor(path, Map.of());
        Snapshot s;
        try {
            s = Table.forPath(engine, path).getLatestSnapshot(engine);
        } catch (TableNotFoundException e) {
            return out;
        }
        if (!r.active()) {
            return out;
        }
        // one business-date partition at a time (never the whole table in memory): a first pass reads each partition for the ages of its
        // rows (only when max-rows needs the table-wide cut: the ages, not the rows, are kept), a second judges and rewrites the partitions
        // that hold a dropped row
        java.util.SortedSet<String> dates = TableUpkeep.partitionValues(engine, s, "business_date");
        List<Instant> ages = new ArrayList<>();
        if (r.maxRows() != null) {
            for (String date : dates) {
                List<TableUpkeep.Staged> part = TableUpkeep.rows(engine, s, pv -> date.equals(pv.get("business_date")), true);
                if (part == null) {
                    throw new IOException(table + ": a column the retention cannot carry");
                }
                for (TableUpkeep.Staged st : part) {
                    ages.add(r.ageOfLine(st.line(), RowRetention.dayStart(date)));
                }
            }
        }
        RowRetention.Judge judge = r.judge(ages, now);
        for (String date : dates) {
            List<TableUpkeep.Staged> part = TableUpkeep.rows(engine, s, pv -> date.equals(pv.get("business_date")), true);
            if (part == null) {
                throw new IOException(table + ": a column the retention cannot carry");
            }
            List<TableUpkeep.Staged> kept = new ArrayList<>(part.size());
            for (TableUpkeep.Staged st : part) {
                if (judge.keeps(r.ageOfLine(st.line(), RowRetention.dayStart(date)))) {
                    kept.add(st);
                }
            }
            if (kept.size() == part.size()) {
                continue;
            }
            line(out, date, part.size(), part.size() - kept.size());
            if (!dryRun) {
                LakeUndo.rewrite(engine, path, s, date, kept);
                s = Table.forPath(engine, path).getLatestSnapshot(engine);
            }
        }
        return out;
    }

    /** A files store: each day file holding a dropped row is rewritten through a temporary file moved over it. */
    static List<Map<String, Object>> files(String root, String table, RowRetention r, Instant now, boolean dryRun) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        int slash = table.indexOf('/');
        String domain = slash < 0 ? "" : table.substring(0, slash);
        String kind = table.substring(slash + 1);
        Path base = domain.isEmpty() ? Path.of(root) : Path.of(root).resolve(domain);
        if (!Files.isDirectory(base)) {
            return out;
        }
        Map<String, Path> where = new TreeMap<>();
        try (Stream<Path> days = Files.list(base)) {
            for (Path d : days.filter(Files::isDirectory).sorted().toList()) {
                Path f = d.resolve(kind + ".jsonl");
                if (Files.isRegularFile(f)) {
                    where.put(d.getFileName().toString(), f);
                }
            }
        }
        if (!r.active()) {
            return out;
        }
        // one day file at a time: the ages of the rows are read as a stream (only when max-rows needs the table-wide cut), then each day
        // file is judged and rewritten alone
        List<Instant> ages = new ArrayList<>();
        if (r.maxRows() != null) {
            for (Map.Entry<String, Path> e : where.entrySet()) {
                Instant start = RowRetention.dayStart(e.getKey());
                try (Stream<String> ls = Files.lines(e.getValue(), StandardCharsets.UTF_8)) {
                    ls.filter(l -> !l.isBlank()).forEach(l -> ages.add(r.ageOfLine(l, start)));
                }
            }
        }
        RowRetention.Judge judge = r.judge(ages, now);
        ages.clear();
        for (Map.Entry<String, Path> e : where.entrySet()) {
            Instant start = RowRetention.dayStart(e.getKey());
            List<String> ls = Files.readAllLines(e.getValue(), StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
            List<String> keep = new ArrayList<>();
            for (String l : ls) {
                if (judge.keeps(r.ageOfLine(l, start))) {
                    keep.add(l);
                }
            }
            if (keep.size() == ls.size()) {
                continue;
            }
            line(out, e.getKey(), ls.size(), ls.size() - keep.size());
            if (dryRun) {
                continue;
            }
            Path f = where.get(e.getKey());
            if (keep.isEmpty()) {
                Files.delete(f);                                   // a day left empty: its file goes
                continue;
            }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, (String.join("\n", keep) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);   // a running server never reads half a day
        }
        return out;
    }

    /** An Iceberg table, by the Iceberg plugin (on the server's class path), as {@link LakeUndo#undoAll} does. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> iceberg(String root, String table, RowRetention r, boolean dryRun) throws Exception {
        Class<?> ice;
        try {
            ice = Class.forName("com.ash.drishti.plugin.iceberg.IcebergMaintenance");
        } catch (ClassNotFoundException e) {
            throw new IOException("the Iceberg plugin is not on the class path");
        }
        int slash = table.indexOf('/');
        return (List<Map<String, Object>>) ice.getMethod("retentionTable", String.class, String.class, String.class, String.class, String.class,
                Integer.class, boolean.class, Path.class).invoke(null, root, slash < 0 ? "" : table.substring(0, slash), table.substring(slash + 1),
                r.ttl(), r.on(), r.maxRows(), dryRun, Path.of(System.getProperty("java.io.tmpdir")));
    }
}
