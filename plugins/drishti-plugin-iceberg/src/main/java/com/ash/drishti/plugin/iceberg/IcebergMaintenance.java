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
import com.ash.drishti.plugin.iceberg.IcebergTable.Day;
import com.ash.drishti.plugin.iceberg.IcebergTable.Layout;
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
 *       files only they referenced; time travel reaches back that far.</li>
 * </ul>
 *
 * <p>Catalog options as {@link IcebergLoader}: {@code --catalog rest --uri …}, {@code --set key=value}.
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
        for (int i = 0; i < args.length; i++) {
            String v = i + 1 < args.length ? args[i + 1] : "";
            switch (args[i]) {
                case "--domain" -> domains = Arrays.asList(v.split(","));
                case "--kinds" -> kinds = Arrays.asList(v.split(","));
                case "--catalog", "--uri", "--warehouse", "--credential", "--token" -> settings.put(args[i].substring(2), v);
                case "--set" -> settings.put(v.substring(0, v.indexOf('=')), v.substring(v.indexOf('=') + 1));
                case "--keep-days" -> keepDays = Integer.parseInt(v);
                case "--expire-hours" -> expireHours = Long.parseLong(v);
                case "--buffer-mb" -> budget = Long.parseLong(v) * 1024 * 1024;
                case "--spill-dir" -> spill = Path.of(v);
                case "--columns" -> addColumns.put(v.substring(0, v.indexOf(':')), Arrays.asList(v.substring(v.indexOf(':') + 1).split(",")));
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
        LoadGuard guard = LoadGuard.fromArgs(args);
        for (String domain : domains) {
            Map<String, String> s = new HashMap<>(settings);
            s.put("domain", domain.trim());
            try (IcebergLake lake = IcebergLake.of(s)) {
                for (String kind : kinds.isEmpty() ? lake.kinds() : kinds) {
                    Table t = lake.load(kind).orElse(null);
                    if (t == null) {
                        continue;
                    }
                    String name = (domain.isBlank() ? "" : domain + "/") + kind;
                    if (keepDays > 0) {
                        System.out.printf("%s: %d business days removed%n", name, keepDays(t, keepDays, guard));
                    }
                    if (addColumns.containsKey(kind)) {
                        Map<String, Boolean> cols = new LinkedHashMap<>();
                        addColumns.get(kind).forEach(p -> cols.put(p.trim(), false));
                        inferTypes(t, cols);
                        IcebergLayout.open(lake, kind, cols, IcebergLayout.DEFAULT_ROW_GROUP_BYTES, IcebergLayout.fileRows(t));
                        t.refresh();
                    }
                    if (relayout || addColumns.containsKey(kind)) {
                        System.out.printf("%s: %d business days rewritten%n", name,
                                relayout(t, force || addColumns.containsKey(kind), spill.resolve("drishti-iceberg-" + ProcessHandle.current().pid()), budget));
                    }
                    if (manifests) {
                        rewriteManifests(t);
                    }
                    if (expireHours >= 0) {
                        expireSnapshots(t, expireHours);
                    }
                }
            }
        }
    }

    /** {@link #keepDays(Table, int, LoadGuard)} counting back from today. */
    static int keepDays(Table t, int keep) {
        return keepDays(t, keep, LoadGuard.fromArgs(new String[0]));
    }

    /**
     * Deletes the business days older than the table's newest {@code keep} on or before the guard's as-of date (a day
     * after it is neither counted nor deleted); refused when that is more than the guard's share of the table's rows.
     * Returns how many days went.
     */
    static int keepDays(Table t, int keep, LoadGuard guard) {
        t.refresh();
        if (t.currentSnapshot() == null) {
            return 0;
        }
        java.util.NavigableMap<LocalDate, Day> days;
        try (ExecutorService pool = IcebergTable.virtualPool()) {
            days = layout(t, pool, false).days();
        }
        var from = guard.keepFromNewest(days.keySet(), keep);
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
            try (CloseableIterable<Record> rows = IcebergTable.open(t.io(), l.schema(), newest.files().get(0),
                    new Schema(l.schema().findField(IcebergLayout.DOC)), Expressions.alwaysTrue())) {
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
     * Rewrites the days that drifted from the layout (every day when {@code force}), each in one commit that replaces
     * the day's files and delete files; returns how many days were rewritten.
     */
    static int relayout(Table t, boolean force, Path spillDir, long budget) {
        t.refresh();
        if (t.currentSnapshot() == null) {
            return 0;
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
                    for (FileScanTask task : day.files()) {
                        copy(t, schema, task, promoted, rows, budget);
                    }
                    List<DataFile> files = IcebergLayout.writeDay(t, day.date(), rows.merged(), fileRows);
                    RewriteFiles rw = t.newRewrite().validateFromSnapshot(l.snapshotId());
                    java.util.Set<String> gone = new java.util.HashSet<>();
                    for (FileScanTask task : day.files()) {
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
        for (FileScanTask task : day.files()) {
            DataFile f = task.file();
            if (f.recordCount() > fileRows) {
                return true;
            }
            if (hasCounts(f) && promoted.stream().anyMatch(c -> !f.valueCounts().containsKey(c.fieldId()))) {
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
                    boolean inFile = !hasCounts(f) || f.valueCounts().containsKey(field.fieldId());
                    Object v = r.get(c + 2);
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
