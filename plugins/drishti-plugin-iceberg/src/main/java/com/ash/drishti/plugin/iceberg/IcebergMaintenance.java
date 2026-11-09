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
import com.ash.drishti.plugin.iceberg.KindTable.Day;
import com.ash.drishti.plugin.iceberg.KindTable.Layout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

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

    /**
     * Undoes load {@code id} in every Iceberg table of the lake at {@code root} (a local folder: {@code <domain>/<table>}; DATA_LAKE_GUIDE.md
     * section 6.3): a line per table and date undone ({@code table}, {@code date}, {@code removed}, {@code restored}, {@code replaced}).
     */
    public static List<Map<String, Object>> undoLake(String root, String id, boolean dryRun, Path spillDir) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        Path base = Path.of(root);
        if (!java.nio.file.Files.isDirectory(base)) {
            return out;
        }
        List<Path> domains;
        try (var s = java.nio.file.Files.list(base)) {
            domains = s.filter(java.nio.file.Files::isDirectory).filter(d -> !d.getFileName().toString().startsWith("_")).sorted().toList();
        }
        for (Path d : domains) {
            Map<String, String> ns = new HashMap<>(Map.of("root", root, "domain", d.getFileName().toString()));
            try (LayoutLake view = LayoutLake.of(ns)) {
                for (String kind : view.kinds()) {
                    com.ash.drishti.iceberg.Table t = ((LayoutLake.Native.Kind) view.load(kind).orElseThrow()).t();
                    for (Map<String, Object> line : NativeMaintenance.undo(t, id, spillDir, 256L << 20, dryRun)) {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("table", d.getFileName() + "/" + kind);
                        m.putAll(line);
                        out.add(m);
                    }
                }
            }
        }
        return out;
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
        String undo = null;                                     // a load id: its rows removed (DATA_LAKE_GUIDE.md section 5.5)
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
                case "--undo" -> undo = v;                       // a load id: its rows removed (DATA_LAKE_GUIDE.md section 5.5)
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
                        if (undo != null) {
                            List<Map<String, Object>> lines = NativeMaintenance.undo(native0, undo, spill.resolve("drishti-iceberg-" + ProcessHandle.current().pid()),
                                    budget, dryRun);
                            report.put("undo", lines);
                            for (Map<String, Object> u : lines) {
                                say(json, "%s %s: %s rows of load %s removed%s%n", name, u.get("date"), u.get("removed"), undo,
                                        Boolean.TRUE.equals(u.get("replaced")) ? ", " + u.get("restored") + " rows of the date before it restored" : "");
                            }
                        }
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
        try (ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
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
        try (ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
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
}
