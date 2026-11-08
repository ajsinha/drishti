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

import com.ash.drishti.api.ColumnSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * One kind's Iceberg table as the connector reads it, whichever engine reads it ({@code native}: drishti-iceberg,
 * {@code library}: Apache Iceberg Java): a row per entity per business date ({@link IcebergLayout}). A snapshot's
 * data files are planned once and grouped by business date ({@link Layout}); a date's files are then read on demand,
 * only the columns asked for, with the row-group filter of the read (one entity: {@code id = …}). Delete files are
 * applied by every read. An engine supplies planning and the reading of one file ({@link #scan}); what is read from
 * the rows (id maps, documents, column sets) is the same for both. Immutable per snapshot; thread-safe.
 */
abstract class KindTable {

    /**
     * One business date's data in a snapshot.
     *
     * @param files its data files (the engine's own task type), each with the delete files that apply to it
     * @param rows the rows the files hold, before deletes
     * @param deletes true when delete files apply to any of them
     * @param fingerprint identifies the files (and delete files): a cache key that survives commits of other dates
     */
    record Day(LocalDate date, List<?> files, long rows, boolean deletes, long fingerprint) {}

    /**
     * A snapshot's dates, newest last.
     *
     * @param columns the snapshot schema's top-level columns: name to whether it is read as numbers
     */
    record Layout(long snapshotId, long timestamp, Map<String, Boolean> columns, NavigableMap<LocalDate, Day> days) {
        boolean deletes() {
            return days.values().stream().anyMatch(Day::deletes);
        }

        boolean hasColumn(String name) {
            return columns.containsKey(name);
        }

        boolean numeric(String name) {
            return Boolean.TRUE.equals(columns.get(name));
        }
    }

    /**
     * One business date's ids, sorted, each with the index of the file that holds it (in the day's file list): in the heap
     * ({@link Sorted}) or memory-mapped from a file under {@code cache/<connector>/idmaps} ({@link MappedIdMap}).
     */
    interface IdMap {
        int size();

        /** The {@code i}th id, in sorted order. */
        String id(int i);

        /** The index of the file holding {@code id}, or -1. */
        int file(String id);

        /** What it weighs in the heap (a mapped map: almost nothing). */
        long bytes();

        /** The ids in order, as a view. */
        default List<String> ids() {
            return new java.util.AbstractList<>() {
                @Override
                public String get(int i) {
                    return id(i);
                }

                @Override
                public int size() {
                    return IdMap.this.size();
                }
            };
        }
    }

    /** An id map in the heap: the ids sorted and the file of each. */
    record Sorted(String[] idArray, int[] files) implements IdMap {
        @Override
        public int size() {
            return idArray.length;
        }

        @Override
        public String id(int i) {
            return idArray[i];
        }

        @Override
        public int file(String id) {
            int i = Arrays.binarySearch(idArray, id);
            return i < 0 ? -1 : files[i];
        }

        @Override
        public long bytes() {
            long b = 64L + 4L * files.length;
            for (String s : idArray) {
                b += 48L + s.length();
            }
            return b;
        }
    }

    protected final ExecutorService pool;

    KindTable(ExecutorService pool) {
        this.pool = pool;
    }

    /** Re-reads the table's metadata (its current snapshot); returns the current snapshot id, or empty. */
    abstract Optional<Long> refresh();

    /** The current snapshot id, without re-reading metadata. */
    abstract Optional<Long> current();

    /**
     * The snapshot as known at {@code knownAt}: empty when nothing had been committed yet (or the snapshots of that
     * time were expired), and the current one when the time is after the latest commit.
     */
    abstract Optional<Long> snapshotAt(Instant knownAt);

    /** The snapshot's sequence number (0 in a v1 table), for provenance. */
    abstract long sequenceNumber(long snapshotId);

    /** The table's {@code table-uuid} from its metadata, and its location: empty while the table does not exist. */
    abstract Optional<String[]> uuidAndLocation();

    /** Plans the snapshot's files, grouped by business date (the manifests the snapshot lists are read once). */
    final Layout layout(long snapshotId) {
        return layout(snapshotId, false);
    }

    /** {@link #layout(long)}, with each file's column metrics (bounds, counts) when {@code stats}. */
    abstract Layout layout(long snapshotId, boolean stats);

    /**
     * Reads one file of the day: the columns named, in order, of each live row (deletes applied); with {@code idEquals}
     * Parquet skips every row group whose id statistics cannot hold it (rows of the remaining groups are returned too:
     * callers check the id). Values: text as String, numbers as Number.
     */
    abstract void scan(Layout l, Day day, int file, List<String> columns, String idEquals, Consumer<Object[]> sink);

    /** The fingerprint of a day's files: their paths and their delete files' paths. */
    /**
     * A text column's bounds in one data file of the day, from its manifest entry: {min, max, nulls} ({@code nulls} "0" when the file
     * has no row without a value), each null when not stated. A VaR shard's read skips the files that hold none of its keys
     * (DATA_ARCHITECTURE.md#13-var-sharding-how-it-is-built); an engine that cannot say gives nothing, and the file is read.
     */
    String[] textBounds(Layout l, Day day, int file, String column) {
        return new String[3];
    }

    static long fingerprint(List<String> paths, int files) {
        long fp = 1125899906842597L;
        for (String p : paths) {
            fp = 31 * fp + p.hashCode();
        }
        return fp ^ ((long) files << 48);
    }

    /** Reads each of the day's files on its own virtual thread (a day has a few large files), in file order. */
    <T> List<T> perFile(Day day, IntFunction<T> read) {
        List<Future<T>> running = new ArrayList<>();
        for (int i = 0; i < day.files().size(); i++) {
            int file = i;
            running.add(pool.submit(() -> read.apply(file)));
        }
        List<T> out = new ArrayList<>();
        try {
            for (Future<T> f : running) {
                out.add(f.get());
            }
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** The column every row carries: the load that wrote it ({@code <run>.<file>}, in time order; DATA_LAKE_GUIDE.md). */
    static final String LOAD_ID = "_load_id";

    /**
     * Per snapshot and day: the ids more than one load wrote, each with the index of the file whose copy readers see (the newest load:
     * the largest {@code _load_id}, then the later file); empty when none. Kept for a few days.
     */
    private final Map<String, Map<String, Integer>> shadows = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true));   // at most 64, the least used first out

    /** The day's shadowed ids ({@link #shadows}), computed by {@link #ids} when the table has {@code _load_id}. */
    Map<String, Integer> shadow(Layout l, Day day) {
        if (day.files().size() < 2 || !l.hasColumn(LOAD_ID)) {
            return Map.of();
        }
        String key = l.snapshotId() + "|" + day.date();
        Map<String, Integer> s = shadows.get(key);
        if (s == null) {
            ids(l, day);
            s = shadows.getOrDefault(key, Map.of());
        }
        return s;
    }

    /** Whether the row of {@code id} in file {@code file} is the one readers see (not shadowed by a newer load's copy). */
    static boolean seen(Map<String, Integer> shadow, String id, int file) {
        if (shadow.isEmpty()) {
            return true;
        }
        Integer w = shadow.get(id);
        return w == null || w == file;
    }

    /**
     * The day's ids, read from the id column alone (no document is read), sorted, with the file each is in. An id that several loads
     * wrote (appends: DATA_LAKE_GUIDE.md) is the newest load's: the largest {@code _load_id}, then the later file.
     */
    IdMap ids(Layout l, Day day) {
        boolean loads = day.files().size() > 1 && l.hasColumn(LOAD_ID);
        List<String> read = loads ? List.of(IcebergLayout.ID, LOAD_ID) : List.of(IcebergLayout.ID);
        List<String[][]> perFile = perFile(day, f -> {
            List<String> ids = new ArrayList<>();
            List<String> lids = new ArrayList<>();
            scan(l, day, f, read, null, r -> {
                if (r[0] != null) {
                    ids.add(r[0].toString());
                    lids.add(loads && r[1] != null ? r[1].toString() : "");
                }
            });
            return new String[][] {ids.toArray(new String[0]), lids.toArray(new String[0])};
        });
        if (loads) {
            return newestWins(l, day, perFile);
        }
        List<String[]> idsPerFile = perFile.stream().map(x -> x[0]).toList();
        int n = idsPerFile.stream().mapToInt(a -> a.length).sum();
        String[] ids = new String[n];
        int[] files = new int[n];
        // files of one laid-out day hold disjoint id ranges: concatenated in order of their first id, they are sorted
        Integer[] order = new Integer[idsPerFile.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparing(i -> idsPerFile.get(i).length == 0 ? "" : idsPerFile.get(i)[0]));
        int at = 0;
        for (int f : order) {
            String[] chunk = idsPerFile.get(f);
            System.arraycopy(chunk, 0, ids, at, chunk.length);
            Arrays.fill(files, at, at + chunk.length, f);
            at += chunk.length;
        }
        if (!sorted(ids)) {                                      // not laid out: sort ids and their files together
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) {
                idx[i] = i;
            }
            Arrays.sort(idx, Comparator.comparing(i -> ids[i]));
            String[] sortedIds = new String[n];
            int[] sortedFiles = new int[n];
            for (int i = 0; i < n; i++) {
                sortedIds[i] = ids[idx[i]];
                sortedFiles[i] = files[idx[i]];
            }
            return new Sorted(sortedIds, sortedFiles);
        }
        return new Sorted(ids, files);
    }

    /** The ids of a day several loads may have written: sorted by id, load id and file; each id's last copy (the newest load's) kept. */
    private IdMap newestWins(Layout l, Day day, List<String[][]> perFile) {
        List<String> ids = new ArrayList<>();
        List<String> lids = new ArrayList<>();
        List<Integer> fileOf = new ArrayList<>();
        for (int f = 0; f < perFile.size(); f++) {
            String[][] x = perFile.get(f);
            for (int i = 0; i < x[0].length; i++) {
                ids.add(x[0][i]);
                lids.add(x[1][i]);
                fileOf.add(f);
            }
        }
        Integer[] idx = new Integer[ids.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, Comparator.comparing((Integer i) -> ids.get(i)).thenComparing(lids::get).thenComparing(fileOf::get));
        List<String> outIds = new ArrayList<>();
        List<Integer> outFiles = new ArrayList<>();
        Map<String, Integer> shadow = new HashMap<>();
        for (int i : idx) {
            int n = outIds.size();
            if (n > 0 && outIds.get(n - 1).equals(ids.get(i))) {
                outFiles.set(n - 1, fileOf.get(i));
                shadow.put(ids.get(i), fileOf.get(i));
                continue;
            }
            outIds.add(ids.get(i));
            outFiles.add(fileOf.get(i));
        }
        synchronized (shadows) {                              // a plain map (no subclass: nothing here is Serializable), trimmed by hand
            shadows.put(l.snapshotId() + "|" + day.date(), shadow.isEmpty() ? Map.of() : shadow);
            var eldest = shadows.keySet().iterator();
            while (shadows.size() > 64 && eldest.hasNext()) {
                eldest.next();
                eldest.remove();
            }
        }
        return new Sorted(outIds.toArray(new String[0]), outFiles.stream().mapToInt(Integer::intValue).toArray());
    }

    private static boolean sorted(String[] ids) {
        for (int i = 1; i < ids.length; i++) {
            if (ids[i - 1].compareTo(ids[i]) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * One entity's document from one file: the reader is given {@code id = …}, so the row groups whose id statistics
     * cannot hold it are skipped (a day sorted by id reads one small row group). Deletes are applied.
     */
    Optional<String> doc(Layout l, Day day, int file, String id) {
        if (file < 0 || file >= day.files().size()) {
            return Optional.empty();
        }
        String[] found = new String[1];
        scan(l, day, file, List.of(IcebergLayout.ID, IcebergLayout.DOC), id, r -> {
            if (found[0] == null && id.equals(String.valueOf(r[0])) && r[1] != null) {
                found[0] = r[1].toString();
            }
        });
        return Optional.ofNullable(found[0]);
    }

    /**
     * Columns of the day's rows, read without documents: the id and each named column ({@code pathToColumn}: document
     * path to column name). Numbers as doubles (NaN for none); repeated text values (books, desks, currencies) share
     * one string.
     */
    ColumnSet columns(Layout l, Day day, Map<String, String> pathToColumn) {
        List<String> paths = new ArrayList<>(pathToColumn.keySet());
        List<String> cols = new ArrayList<>(List.of(IcebergLayout.ID));
        paths.forEach(p -> cols.add(pathToColumn.get(p)));
        boolean[] num = new boolean[paths.size()];
        for (int c = 0; c < paths.size(); c++) {
            num[c] = l.numeric(pathToColumn.get(paths.get(c)));
        }
        Map<String, Integer> shadow = shadow(l, day);
        List<Object[]> perFile = perFile(day, f -> {
            List<String> ids = new ArrayList<>();
            List<List<Object>> values = new ArrayList<>();
            paths.forEach(p -> values.add(new ArrayList<>()));
            scan(l, day, f, cols, null, r -> {
                Object id = r[0];
                if (!seen(shadow, id == null ? "" : id.toString(), f)) {
                    return;                                      // an older load's copy of an id a newer load wrote
                }
                ids.add(id == null ? "" : id.toString());
                for (int c = 0; c < paths.size(); c++) {
                    Object v = r[c + 1];
                    values.get(c).add(v == null ? null : num[c] ? (Object) ((Number) v).doubleValue() : v.toString());
                }
            });
            return new Object[] {ids, values};
        });
        int n = perFile.stream().mapToInt(o -> ((List<?>) o[0]).size()).sum();
        String[] ids = new String[n];
        Map<String, double[]> numbers = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        List<Map<String, String>> pools = new ArrayList<>();
        for (int c = 0; c < paths.size(); c++) {
            if (num[c]) {
                numbers.put(paths.get(c), new double[n]);
            } else {
                texts.put(paths.get(c), new String[n]);
            }
            pools.add(new HashMap<>());
        }
        int at = 0;
        for (Object[] chunk : perFile) {
            @SuppressWarnings("unchecked")
            List<String> chunkIds = (List<String>) chunk[0];
            @SuppressWarnings("unchecked")
            List<List<Object>> values = (List<List<Object>>) chunk[1];
            for (int i = 0; i < chunkIds.size(); i++) {
                ids[at + i] = chunkIds.get(i);
            }
            for (int c = 0; c < paths.size(); c++) {
                List<Object> v = values.get(c);
                if (num[c]) {
                    double[] out = numbers.get(paths.get(c));
                    for (int i = 0; i < v.size(); i++) {
                        out[at + i] = v.get(i) == null ? Double.NaN : (Double) v.get(i);
                    }
                } else {
                    String[] out = texts.get(paths.get(c));
                    Map<String, String> pool = pools.get(c);
                    for (int i = 0; i < v.size(); i++) {
                        String s = (String) v.get(i);
                        out[at + i] = s == null || pool.size() > 200_000 ? s : pool.computeIfAbsent(s, x -> x);
                    }
                }
            }
            at += chunkIds.size();
        }
        return new ColumnSet(ids, numbers, texts, LocalDate.MIN.equals(day.date()) ? null : day.date());
    }

    /** Every entity of one business day: id to JSON text (for small tables only). */
    Map<String, String> read(Layout l, Day day) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Integer> shadow = shadow(l, day);
        for (int f = 0; f < day.files().size(); f++) {
            int file = f;
            scan(l, day, f, List.of(IcebergLayout.ID, IcebergLayout.DOC), null, r -> {
                if (r[0] != null && r[1] != null && seen(shadow, r[0].toString(), file)) {
                    out.put(r[0].toString(), r[1].toString());
                }
            });
        }
        return out;
    }
}
