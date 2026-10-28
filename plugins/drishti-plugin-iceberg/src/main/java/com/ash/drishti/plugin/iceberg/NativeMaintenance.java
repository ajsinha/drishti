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
import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import com.ash.drishti.iceberg.write.Operations;
import com.ash.drishti.plugin.iceberg.IcebergLayout.Row;
import com.ash.drishti.plugin.iceberg.KindTable.Day;
import com.ash.drishti.plugin.iceberg.KindTable.Layout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * {@link IcebergMaintenance}'s steps on the native engine, each one commit of drishti-iceberg: retention by business
 * day (a metadata-only delete of whole partitions, refused beyond the guard's share), expiring snapshots and the files
 * only they used, one manifest per month of business dates, and the relayout of drifted days (sorted by id into files
 * of the table's {@code file-rows}, validated against the snapshot read, delete files folded in), with promoted
 * columns a file lacks filled from its documents. The same rules as the library engine's, so either engine maintains
 * a table the other wrote. Stateless.
 */
final class NativeMaintenance {

    private static final ObjectMapper JSON = new ObjectMapper();

    private NativeMaintenance() {}

    private static Layout layout(Table t, ExecutorService pool) {
        return new NativeKindTable(t, pool).layout(t.currentSnapshot().orElseThrow().snapshotId(), true);
    }

    /** As {@link IcebergMaintenance#keepDays(org.apache.iceberg.Table, int, LoadGuard, LocalDate)}. */
    static int keepDays(Table t, int keep, LoadGuard guard) {
        return keepDays(t, keep, guard, null);
    }

    /** As {@link IcebergMaintenance#keepDays(org.apache.iceberg.Table, int, LoadGuard, LocalDate)}: no date on or after {@code floor} goes. */
    static int keepDays(Table t, int keep, LoadGuard guard, LocalDate floor) {
        t.refresh();
        if (t.currentSnapshot().isEmpty()) {
            return 0;
        }
        NavigableMap<LocalDate, Day> days;
        try (ExecutorService pool = IcebergTable.virtualPool()) {
            days = layout(t, pool).days();
        }
        var from = IcebergMaintenance.keepFrom(days.navigableKeySet(), keep, guard, floor);
        if (from.isEmpty()) {
            return 0;
        }
        var old = days.headMap(from.get(), false);
        if (old.isEmpty()) {
            return 0;
        }
        guard.checkDrop(t.name(), old.values().stream().mapToLong(Day::rows).sum(), days.values().stream().mapToLong(Day::rows).sum(), "rows");
        LocalDate cut = from.get();
        Operations.deletePartitions(t, f -> {
            LocalDate d = NativeLayout.dateOf(t, f);
            return d != null && d.isBefore(cut);
        }, Map.of("drishti.keep-days", String.valueOf(keep)));
        return old.size();
    }

    /** As {@link IcebergMaintenance#expireSnapshots(org.apache.iceberg.Table, long)}: the current snapshot always stays. */
    static void expireSnapshots(Table t, long hours) {
        t.refresh();
        if (t.currentSnapshot().isPresent()) {
            Operations.expireSnapshots(t, System.currentTimeMillis() - hours * 3_600_000L, 1);
        }
    }

    /** As {@link IcebergMaintenance#rewriteManifests(org.apache.iceberg.Table)}: one manifest per month of business dates. */
    static void rewriteManifests(Table t) {
        t.refresh();
        if (t.currentSnapshot().isPresent()) {
            Operations.rewriteManifests(t, f -> {
                LocalDate d = NativeLayout.dateOf(t, f);
                return d == null ? "none" : YearMonth.from(d).toString();
            });
        }
    }

    /** Makes text-only paths numbers when the newest day's documents hold numbers there (as the library's maintenance). */
    static void inferTypes(Table t, Map<String, Boolean> cols) {
        try (ExecutorService pool = IcebergTable.virtualPool()) {
            NativeKindTable k = new NativeKindTable(t, pool);
            Layout l = layout(t, pool);
            if (l.days().isEmpty()) {
                return;
            }
            Day newest = l.days().lastEntry().getValue();
            Map<String, Integer> seen = new HashMap<>();
            int[] n = {0};
            k.scan(l, newest, 0, List.of(IcebergLayout.DOC), null, r -> {
                if (r[0] == null || n[0]++ > 1000) {
                    return;
                }
                try {
                    JsonNode doc = JSON.readTree(r[0].toString());
                    for (String p : cols.keySet()) {
                        JsonNode v = at(doc, p);
                        seen.merge(p, v == null || v.isNull() ? 0 : v.isNumber() ? 1 : 2, (x, y) -> x | y);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            seen.forEach((p, f) -> cols.put(p, f == 1));
        }
    }

    private static JsonNode at(JsonNode doc, String path) {
        JsonNode n = doc;
        for (String part : path.split("\\.")) {
            n = n == null ? null : n.get(part);
        }
        return n;
    }

    /** As {@link IcebergMaintenance#relayout}: rewrites the days that drifted (every day when {@code force}); returns how many. */
    static int relayout(Table t, boolean force, Path spillDir, long budget) {
        t.refresh();
        if (t.currentSnapshot().isEmpty()) {
            return 0;
        }
        int fileRows = NativeLayout.fileRows(t);
        int rewritten = 0;
        try (ExecutorService pool = IcebergTable.virtualPool()) {
            NativeKindTable k = new NativeKindTable(t, pool);
            Layout l = layout(t, pool);
            List<NestedField> promoted = t.metadata().schema().columns().stream().filter(f -> f.id() > 3).toList();
            for (Day day : l.days().values()) {
                if (LocalDate.MIN.equals(day.date()) || !(force || drifted(day, fileRows, promoted))) {
                    continue;
                }
                try (SortedRuns rows = new SortedRuns(spillDir, "relayout-" + day.date())) {
                    for (int f = 0; f < day.files().size(); f++) {
                        copy(k, l, day, f, promoted, rows, budget);
                    }
                    List<DataFile> files = NativeLayout.writeDay(t, day.date(), rows.merged(), fileRows);
                    Set<String> read = new HashSet<>();
                    Set<String> remove = new HashSet<>();
                    for (FileTask task : NativeKindTable.tasks(day)) {
                        read.add(task.file().path());
                        remove.add(task.file().path());
                        for (DataFile d : task.deletes()) {
                            if (day.date().equals(NativeLayout.dateOf(t, d))) {
                                read.add(d.path());
                                remove.add(d.path());     // the day's own delete files: their rows are gone from the new files
                            }
                        }
                    }
                    LocalDate date = day.date();
                    try {
                        Operations.rewriteFiles(t, remove, files, f -> date.equals(NativeLayout.dateOf(t, f)), read);
                    } catch (RuntimeException e) {
                        files.forEach(f -> deleteQuietly(t, f.path()));      // no orphans from a rewrite that did not commit
                        throw e;
                    }
                    rewritten++;
                }
            }
        }
        return rewritten;
    }

    private static void deleteQuietly(Table t, String path) {
        try {
            String r = t.io().resolve(path);
            t.io().storage(r).delete(r);
        } catch (IOException | RuntimeException e) {
            // left for an orphan clean-up
        }
    }

    /** True when the day is not as the layout writes it. */
    private static boolean drifted(Day day, int fileRows, List<NestedField> promoted) {
        if (day.deletes() || day.files().size() > Math.max(1, (day.rows() + fileRows - 1) / fileRows)) {
            return true;
        }
        List<String[]> ranges = new ArrayList<>();
        for (FileTask task : NativeKindTable.tasks(day)) {
            DataFile f = task.file();
            if (f.recordCount() > fileRows) {
                return true;
            }
            // a vector column (a list) has its metrics under its element's id: only scalar columns say a file predates them
            if (!f.valueCounts().isEmpty() && promoted.stream().anyMatch(c -> c.type() instanceof Type.Primitive && !f.valueCounts().containsKey(c.id()))) {
                return true;                                   // written before a promoted column was added
            }
            byte[] lo = f.lowerBounds().get(1);
            byte[] hi = f.upperBounds().get(1);
            if (lo == null || hi == null) {
                return true;
            }
            ranges.add(new String[] {(String) Values.fromBytes(Type.Primitive.STRING, lo), (String) Values.fromBytes(Type.Primitive.STRING, hi)});
        }
        ranges.sort(Comparator.comparing(r -> r[0]));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0].compareTo(ranges.get(i - 1)[1]) <= 0) {
                return true;                                   // two files share an id range: an append out of order
            }
        }
        return false;
    }

    /** Copies one file's live rows (deletes applied) into the sorter, filling promoted columns the file lacks from the document. */
    private static void copy(NativeKindTable k, Layout l, Day day, int file, List<NestedField> promoted, SortedRuns rows, long budget) {
        DataFile f = NativeKindTable.tasks(day).get(file).file();
        List<String> cols = new ArrayList<>(List.of(IcebergLayout.ID, IcebergLayout.DOC));
        promoted.forEach(p -> cols.add(p.name()));
        k.scan(l, day, file, cols, null, r -> {
            Map<String, Object> values = new LinkedHashMap<>();
            JsonNode doc = null;
            for (int c = 0; c < promoted.size(); c++) {
                NestedField field = promoted.get(c);
                String path = field.name().replace("__", ".");
                // a vector column is copied as it is (its document holds only a marker, never the values)
                boolean inFile = !(field.type() instanceof Type.Primitive) || f.valueCounts().isEmpty() || f.valueCounts().containsKey(field.id());
                Object v = r[c + 2];
                if (!inFile && r[1] != null) {
                    try {
                        doc = doc == null ? JSON.readTree(r[1].toString()) : doc;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    JsonNode n = at(doc, path);
                    v = n == null || n.isNull() || n.isContainerNode() ? null : n.isNumber() ? (Object) n.asDouble() : n.asText();
                }
                values.put(path, v);
            }
            rows.add(new Row(r[0].toString(), r[1] == null ? null : r[1].toString(), values));
            if (rows.bufferedBytes() > budget) {
                rows.spill();
            }
        });
    }
}
