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

import com.ash.drishti.api.LoadGuard;
import com.ash.drishti.plugin.iceberg.IcebergLayout.Row;
import com.ash.drishti.plugin.iceberg.KindTable.Day;
import com.ash.drishti.plugin.iceberg.KindTable.Layout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Types;

/**
 * Keeps Iceberg tables bounded and in {@link IcebergLayout}: {@code java -cp <plugin classpath>
 * com.ash.drishti.plugin.iceberg.IcebergMaintenance [root] --domain D[,D…] [options]} (run nightly, from cron or a
 * scheduler). Each step is an ordinary Iceberg commit, so readers never see a half-done change:
 *
 * <ul>
 *   <li>{@code --keep-days N}: deletes the business days older than the table's newest N on or before
 *       {@code --as-of} (today; a day after it is neither counted nor deleted), a metadata-only delete of whole
 *       partitions; it refuses to delete more than {@code --max-drop-share} (0.5) of a table's rows without
 *       {@code --force-drop} ({@link LoadGuard});</li>
 *   <li>{@code --relayout}: rewrites, sorted by id into files of the table's {@code file-rows}, each business day that
 *       drifted from the layout (an append whose ids overlap the day's files, a file over {@code file-rows}, more files
 *       than needed, delete files, a promoted column missing from a file); {@code --force} rewrites every day;
 *       {@code --columns kind:path,path} adds promoted columns, filled from the documents;</li>
 *   <li>{@code --rewrite-manifests}: regroups the manifests one per month, so planning a seven-year table opens a
 *       few dozen manifests instead of one per daily commit;</li>
 *   <li>{@code --expire-hours H} (168): expires snapshots older than that (keeping the current one) and deletes the
 *       files only they referenced; time travel reaches back that far;</li>
 *   <li>{@code --remove-orphans --older-than-hours H} (72, the least it takes): deletes the files under each table's location that no
 *       snapshot references and that are older than H hours ({@link OrphanFiles}: what a load that died before its commit left).</li>
 * </ul>
 *
 * <p>{@code --floor-days D} is the retention floor of Admin → Lakes ({@code tools/lake/maintconfig.py}: the {@code risk} and
 * {@code risk-results} domains keep 7 years, 2,557 days): keep-days never drops a business date within D days of {@code --as-of},
 * and expiry never removes a snapshot younger than D days, whatever the other options say. {@code --dry-run} commits nothing and
 * deletes nothing: it says which dates keep-days would drop and which orphans would go. {@code --json} prints one line of JSON a
 * table (its files, MB, dates and snapshots before and after, and what each step did), which {@code lake maintain} reads.
 *
 * <p>Catalog options as {@link IcebergLoader}: {@code --engine native|library|auto} (as the connector's; {@code native}, drishti-iceberg,
 * needs no Hadoop), {@code --catalog rest --uri …}, {@code --set key=value}.
 * {@code --kinds k1,k2} limits the tables; {@code --spill-dir} and {@code --buffer-mb} bound a rewrite's sort.
 */
public final class IcebergMaintenance {

    private static final ObjectMapper JSON = new ObjectMapper();

    private IcebergMaintenance() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> settings = new HashMap<>();
        settings.put("root", args.length > 0 && !args[0].startsWith("--") ? args[0] : com.ash.drishti.api.DataDir.under("iceberg"));
        List<String> domains = new ArrayList<>(List.of(""));
        List<String> kinds = List.of();
        Map<String, List<String>> addColumns = new HashMap<>();
        int keepDays = 0;
        long expireHours = 168;
        boolean relayout = false;
        boolean force = false;
        boolean manifests = false;
        long budget = 1024L * 1024 * 1024;
        Path spill = Path.of(System.getProperty("java.io.tmpdir"));
        int floorDays = 0;
        long orphanHours = OrphanFiles.MIN_AGE_HOURS;
        boolean orphans = false;
        boolean dryRun = false;
        boolean json = false;
        for (int i = 0; i < args.length; i++) {
            String v = i + 1 < args.length ? args[i + 1] : "";
            switch (args[i]) {
                case "--domain" -> domains = Arrays.asList(v.split(","));
                case "--kinds" -> kinds = Arrays.asList(v.split(","));
                case "--catalog", "--uri", "--warehouse", "--credential", "--token", "--engine" -> settings.put(args[i].substring(2), v);
                case "--set" -> settings.put(v.substring(0, v.indexOf('=')), v.substring(v.indexOf('=') + 1));
                case "--keep-days" -> keepDays = Integer.parseInt(v);
                case "--expire-hours" -> expireHours = Long.parseLong(v);
                case "--buffer-mb" -> budget = Long.parseLong(v) * 1024 * 1024;
                case "--spill-dir" -> spill = Path.of(v);
                case "--columns" -> addColumns.put(v.substring(0, v.indexOf(':')), Arrays.asList(v.substring(v.indexOf(':') + 1).split(",")));
                case "--floor-days" -> floorDays = Integer.parseInt(v);
                case "--older-than-hours" -> orphanHours = Long.parseLong(v);
                case "--remove-orphans" -> {
                    orphans = true;
                    continue;
                }
                case "--dry-run" -> {
                    dryRun = true;
                    continue;
                }
                case "--json" -> {
                    json = true;
                    continue;
                }
                case "--relayout" -> {
                    relayout = true;
                    continue;
                }
                case "--force" -> {
                    force = true;
                    continue;
                }
                case "--rewrite-manifests" -> {
                    manifests = true;
                    continue;
                }
                default -> {
                    continue;
                }
            }
            i++;
        }
        if (orphans && orphanHours < OrphanFiles.MIN_AGE_HOURS) {
            System.err.println("iceberg: --older-than-hours " + orphanHours + " is under " + OrphanFiles.MIN_AGE_HOURS
                    + ": a file younger may belong to a load that has not committed yet");
            System.exit(2);
        }
        LoadGuard guard = LoadGuard.fromArgs(args);
        LocalDate floor = floorDays > 0 ? guard.asOf().minusDays(floorDays - 1L) : null;
        if (floorDays > 0 && expireHours >= 0 && expireHours < floorDays * 24L) {
            expireHours = floorDays * 24L;                     // time travel is kept as long as the dates are
        }
        int failures = 0;
        for (String domain : domains) {
            Map<String, String> s = new HashMap<>(settings);
            s.put("domain", domain.trim());
            Map<String, String> ns = new HashMap<>(s);
            ns.put("engine", "native");                        // orphans and the report read the table with drishti-iceberg, whichever engine wrote it
            try (LayoutLake lake = LayoutLake.of(s); LayoutLake view = LayoutLake.of(ns)) {
                for (String kind : kinds.isEmpty() ? lake.kinds() : kinds) {
                    LayoutLake.LayoutTable t = lake.load(kind).orElse(null);
                    if (t == null) {
                        continue;
                    }
                    String name = (domain.isBlank() ? "" : domain + "/") + kind;
                    Map<String, Object> report = new java.util.LinkedHashMap<>();
                    report.put("table", name);
                    report.put("dryRun", dryRun);
                    if (floor != null) {
                        report.put("floor", Map.of("days", floorDays, "keepsDatesFrom", floor.toString(), "expireHours", expireHours));
                    }
                    com.ash.drishti.iceberg.Table native0 = ((LayoutLake.Native.Kind) view.load(kind).orElseThrow()).t();
                    try {
                        report.put("before", stats(native0));
                        if (keepDays > 0) {
                            if (dryRun) {
                                report.put("keepDays", Map.of("wouldRemove", dropping(native0, keepDays, guard, floor)));
                            } else {
                                int n = t.keepDays(keepDays, guard, floor);
                                report.put("keepDays", Map.of("removed", n));
                                say(json, "%s: %d business days removed%n", name, n);
                            }
                        }
                        if (!dryRun && addColumns.containsKey(kind)) {
                            t.addColumns(addColumns.get(kind));
                        }
                        if (!dryRun && (relayout || addColumns.containsKey(kind))) {
                            int n = t.relayout(force || addColumns.containsKey(kind), spill.resolve("drishti-iceberg-" + ProcessHandle.current().pid()), budget);
                            report.put("relayout", n);
                            say(json, "%s: %d business days rewritten%n", name, n);
                        }
                        if (!dryRun && manifests) {
                            t.rewriteManifests();
                            report.put("rewriteManifests", true);
                        }
                        if (!dryRun && expireHours >= 0) {
                            t.expireSnapshots(expireHours);
                            report.put("expireHours", expireHours);
                        }
                        if (orphans) {
                            native0.refresh();
                            List<OrphanFiles.Orphan> found = OrphanFiles.find(native0, orphanHours, System.currentTimeMillis());
                            int removed = dryRun ? 0 : OrphanFiles.remove(native0, found);
                            Map<String, Object> o = new java.util.LinkedHashMap<>();
                            o.put("olderThanHours", orphanHours);
                            o.put("files", found.size());
                            o.put("mb", Math.round(found.stream().mapToLong(OrphanFiles.Orphan::size).sum() / 10_485.76) / 100.0);
                            o.put(dryRun ? "wouldRemove" : "removed", dryRun ? found.size() : removed);
                            o.put("first", found.stream().limit(5).map(OrphanFiles.Orphan::path).toList());
                            report.put("orphans", o);
                            say(json, "%s: %d orphan files %s%n", name, found.size(), dryRun ? "would be removed" : "removed (" + removed + ")");
                        }
                        native0.refresh();
                        report.put("after", stats(native0));
                    } catch (RuntimeException e) {
                        failures++;
                        report.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                        if (!json) {
                            throw e;
                        }
                    }
                    if (json) {
                        System.out.println(JSON.writeValueAsString(report));
                    }
                }
            }
        }
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void say(boolean json, String format, Object... args) {
        if (!json) {
            System.out.printf(format, args);
        }
    }

    /**
     * The first business date a keep-days of {@code keep} keeps (the dates before it go), with the floor applied: no date on or
     * after {@code floor} goes. Empty when nothing would go.
     */
    static java.util.Optional<LocalDate> keepFrom(java.util.NavigableSet<LocalDate> days, int keep, LoadGuard guard, LocalDate floor) {
        var from = guard.keepFromNewest(days, keep);
        if (from.isEmpty()) {
            return from;
        }
        LocalDate cut = floor != null && floor.isBefore(from.get()) ? floor : from.get();
        return days.isEmpty() || !days.first().isBefore(cut) ? java.util.Optional.empty() : java.util.Optional.of(cut);
    }

    /** The business dates a keep-days would drop now (a dry run), read by drishti-iceberg. */
    private static List<String> dropping(com.ash.drishti.iceberg.Table t, int keep, LoadGuard guard, LocalDate floor) {
        if (t.currentSnapshot().isEmpty()) {
            return List.of();
        }
        try (ExecutorService pool = IcebergTable.virtualPool()) {
            var days = new NativeKindTable(t, pool).layout(t.currentSnapshot().get().snapshotId(), false).days();
            var from = keepFrom(days.navigableKeySet(), keep, guard, floor);
            return from.isEmpty() ? List.of() : days.headMap(from.get(), false).keySet().stream().map(LocalDate::toString).toList();
        }
    }

    /** A table's size as maintenance reports it: data files, MB, business dates, the newest date and its rows, snapshots. */
    static Map<String, Object> stats(com.ash.drishti.iceberg.Table t) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("snapshots", t.metadata().snapshots().size());
        if (t.currentSnapshot().isEmpty()) {
            m.putAll(Map.of("files", 0, "mb", 0.0, "dates", 0));
            return m;
        }
        try (ExecutorService pool = IcebergTable.virtualPool()) {
            Layout l = new NativeKindTable(t, pool).layout(t.currentSnapshot().get().snapshotId(), false);
            long files = 0;
            long bytes = 0;
            for (Day d : l.days().values()) {
                for (com.ash.drishti.iceberg.scan.FileTask f : NativeKindTable.tasks(d)) {
                    files++;
                    bytes += f.file().fileSizeInBytes();
                }
            }
            m.put("files", files);
            m.put("mb", Math.round(bytes / 10_485.76) / 100.0);
            m.put("dates", l.days().size());
            if (!l.days().isEmpty()) {
                m.put("latestDate", LocalDate.MIN.equals(l.days().lastKey()) ? null : l.days().lastKey().toString());
                m.put("latestRows", l.days().lastEntry().getValue().rows());
            }
        }
        return m;
    }

    /** Adds promoted columns ({@code paths}, typed by the newest day's documents) to the library-engine table {@code t}. */
    static void addColumns(IcebergLake lake, String kind, Table t, List<String> paths) {
        Map<String, Boolean> cols = new LinkedHashMap<>();
        paths.forEach(p -> cols.put(p.trim(), false));
        inferTypes(t, cols);
        IcebergLayout.open(lake, kind, cols, IcebergLayout.DEFAULT_ROW_GROUP_BYTES, IcebergLayout.fileRows(t));
        t.refresh();
    }

    /** {@link #keepDays(Table, int, LoadGuard)} counting back from today. */
    static int keepDays(Table t, int keep) {
        return keepDays(t, keep, LoadGuard.fromArgs(new String[0]));
    }

    static int keepDays(Table t, int keep, LoadGuard guard) {
        return keepDays(t, keep, guard, null);
    }

    /**
     * Deletes the business days older than the table's newest {@code keep} on or before the guard's as-of date (a day
     * after it is neither counted nor deleted), never one on or after {@code floor} (the retention floor; null: none); refused
     * when that is more than the guard's share of the table's rows. Returns how many days went.
     */
    static int keepDays(Table t, int keep, LoadGuard guard, LocalDate floor) {
        t.refresh();
        if (t.currentSnapshot() == null) {
            return 0;
        }
        java.util.NavigableMap<LocalDate, Day> days;
        try (ExecutorService pool = IcebergTable.virtualPool()) {
            days = layout(t, pool, false).days();
        }
        var from = keepFrom(days.navigableKeySet(), keep, guard, floor);
        if (from.isEmpty()) {
            return 0;
        }
        var old = days.headMap(from.get(), false);
        guard.checkDrop(t.name(), old.values().stream().mapToLong(Day::rows).sum(), days.values().stream().mapToLong(Day::rows).sum(), "rows");
        t.newDelete().deleteFromRowFilter(Expressions.lessThan(IcebergLayout.DATE, from.get().toString())).commit();
        return old.size();
    }

    private static Layout layout(Table t, ExecutorService pool, boolean stats) {
        return new IcebergTable(t, pool).layout(t.currentSnapshot().snapshotId(), stats);
    }

    /** Expires snapshots older than {@code hours} (always keeping the current one) and deletes files only they used. */
    static void expireSnapshots(Table t, long hours) {
        t.refresh();
        if (t.currentSnapshot() != null) {
            t.expireSnapshots().expireOlderThan(System.currentTimeMillis() - hours * 3_600_000L).retainLast(1).commit();
        }
    }

    /** One manifest per month of business dates: planning opens a month's manifest, not one per daily commit. */
    static void rewriteManifests(Table t) {
        t.refresh();
        if (t.currentSnapshot() != null) {
            t.rewriteManifests().clusterBy(f -> {
                Integer days = f.partition().size() > 0 ? f.partition().get(0, Integer.class) : null;
                return days == null ? "none" : YearMonth.from(LocalDate.ofEpochDay(days)).toString();
            }).commit();
        }
    }

    /** Makes text-only paths numbers when the newest day's documents hold numbers there. */
    private static void inferTypes(Table t, Map<String, Boolean> cols) {
        try (ExecutorService pool = IcebergTable.virtualPool()) {
            Layout l = layout(t, pool, false);
            if (l.days().isEmpty()) {
                return;
            }
            Day newest = l.days().lastEntry().getValue();
            try (CloseableIterable<Record> rows = IcebergTable.open(t.io(), t.schema(), IcebergTable.tasks(newest).get(0),
                    new Schema(t.schema().findField(IcebergLayout.DOC)), Expressions.alwaysTrue())) {
                int n = 0;
                Map<String, Integer> seen = new HashMap<>();
                for (Record r : rows) {
                    if (r.get(0) == null || n++ > 1000) {
                        break;
                    }
                    JsonNode doc = JSON.readTree(r.get(0).toString());
                    for (String p : cols.keySet()) {
                        JsonNode v = at(doc, p);
                        seen.merge(p, v == null || v.isNull() ? 0 : v.isNumber() ? 1 : 2, (x, y) -> x | y);
                    }
                }
                seen.forEach((p, f) -> cols.put(p, f == 1));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static JsonNode at(JsonNode doc, String path) {
        JsonNode n = doc;
        for (String part : path.split("\\.")) {
            n = n == null ? null : n.get(part);
        }
        return n;
    }

    /**
     * True for a table the parallel lake loader laid out by more than the id ({@code drishti.layout.sort-by}: legal entity, desk, id; a
     * file of a day then overlaps another's id range by design): relayout leaves its days in their order (2.9.6); loading a day again
     * re-lays it.
     */
    static boolean shardSorted(java.util.Map<String, String> properties) {
        String by = properties.getOrDefault("drishti.layout.sort-by", "").trim();
        return !by.isEmpty() && !by.equals("id");
    }

    /**
     * Rewrites the days that drifted from the layout (every day when {@code force}), each in one commit that replaces
     * the day's files and delete files; returns how many days were rewritten.
     */
    static int relayout(Table t, boolean force, Path spillDir, long budget) {
        t.refresh();
        if (t.currentSnapshot() == null || shardSorted(t.properties())) {
            return 0;                                           // laid out by the parallel loader's sort-by: left in its order
        }
        int fileRows = IcebergLayout.fileRows(t);
        int rewritten = 0;
        try (ExecutorService pool = IcebergTable.virtualPool()) {
            Layout l = layout(t, pool, true);
            Schema schema = t.schema();
            List<Types.NestedField> promoted = schema.columns().stream().filter(f -> f.fieldId() > 3).toList();
            for (Day day : l.days().values()) {
                if (LocalDate.MIN.equals(day.date()) || !(force || drifted(day, fileRows, promoted))) {
                    continue;
                }
                try (SortedRuns rows = new SortedRuns(spillDir, "relayout-" + day.date())) {
                    for (FileScanTask task : IcebergTable.tasks(day)) {
                        copy(t, schema, task, promoted, rows, budget);
                    }
                    List<DataFile> files = IcebergLayout.writeDay(t, day.date(), rows.merged(), fileRows);
                    RewriteFiles rw = t.newRewrite().validateFromSnapshot(l.snapshotId());
                    java.util.Set<String> gone = new java.util.HashSet<>();
                    for (FileScanTask task : IcebergTable.tasks(day)) {
                        rw.deleteFile(task.file());
                        for (DeleteFile d : task.deletes()) {
                            Integer p = d.partition().size() > 0 ? d.partition().get(0, Integer.class) : null;
                            if (p != null && p == day.date().toEpochDay() && gone.add(d.location())) {
                                rw.deleteFile(d);         // the day's own delete files: their rows are gone from the new files
                            }
                        }
                    }
                    files.forEach(rw::addFile);
                    rw.commit();
                    rewritten++;
                }
            }
        }
        return rewritten;
    }

    /** True when the day is not as the layout writes it. */
    private static boolean drifted(Day day, int fileRows, List<Types.NestedField> promoted) {
        if (day.deletes() || day.files().size() > Math.max(1, (day.rows() + fileRows - 1) / fileRows)) {
            return true;
        }
        List<String[]> ranges = new ArrayList<>();
        for (FileScanTask task : IcebergTable.tasks(day)) {
            DataFile f = task.file();
            if (f.recordCount() > fileRows) {
                return true;
            }
            // a vector column (a list) has its metrics under its element's id: only scalar columns say a file predates them
            if (hasCounts(f) && promoted.stream().anyMatch(c -> c.type().isPrimitiveType() && !f.valueCounts().containsKey(c.fieldId()))) {
                return true;                                   // written before a promoted column was added
            }
            String lo = bound(f.lowerBounds());
            String hi = bound(f.upperBounds());
            if (lo == null || hi == null) {
                return true;
            }
            ranges.add(new String[] {lo, hi});
        }
        ranges.sort(java.util.Comparator.comparing(r -> r[0]));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0].compareTo(ranges.get(i - 1)[1]) <= 0) {
                return true;                                   // two files share an id range: an append out of order
            }
        }
        return false;
    }

    /** Whether the file's metrics list its columns (they do unless the table's metrics mode is none). */
    private static boolean hasCounts(DataFile f) {
        return f.valueCounts() != null && !f.valueCounts().isEmpty();
    }

    private static String bound(Map<Integer, ByteBuffer> bounds) {
        ByteBuffer b = bounds == null ? null : bounds.get(1);
        return b == null ? null : Conversions.<CharSequence>fromByteBuffer(Types.StringType.get(), b).toString();
    }

    /** Copies one file's live rows (deletes applied) into the sorter, filling promoted columns the file lacks from the document. */
    private static void copy(Table t, Schema schema, FileScanTask task, List<Types.NestedField> promoted, SortedRuns rows, long budget) {
        List<Types.NestedField> cols = new ArrayList<>();
        cols.add(schema.findField(IcebergLayout.ID));
        cols.add(schema.findField(IcebergLayout.DOC));
        cols.addAll(promoted);
        DataFile f = task.file();
        try (CloseableIterable<Record> it = IcebergTable.open(t.io(), schema, task, new Schema(cols), Expressions.alwaysTrue())) {
            for (Record r : it) {
                Map<String, Object> values = new LinkedHashMap<>();
                JsonNode doc = null;
                for (int c = 0; c < promoted.size(); c++) {
                    Types.NestedField field = promoted.get(c);
                    String path = field.name().replace("__", ".");
                    // a vector column is copied as it is (its document holds only a marker, never the values)
                    boolean inFile = !field.type().isPrimitiveType() || !hasCounts(f) || f.valueCounts().containsKey(field.fieldId());
                    Object v = r.get(c + 2);
                    if (v instanceof List<?> list) {
                        v = new ArrayList<>(list);                // the reader reuses its containers: the row keeps its own copy
                    }
                    if (!inFile && r.get(1) != null) {
                        doc = doc == null ? JSON.readTree(r.get(1).toString()) : doc;
                        JsonNode n = at(doc, path);
                        v = n == null || n.isNull() || n.isContainerNode() ? null : n.isNumber() ? (Object) n.asDouble() : n.asText();
                    }
                    values.put(path, v);
                }
                rows.add(new Row(r.get(0).toString(), r.get(1) == null ? null : r.get(1).toString(), values));
                if (rows.bufferedBytes() > budget) {
                    rows.spill();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
