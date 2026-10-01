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
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericDeleteFilter;
import org.apache.iceberg.data.IdentityPartitionConverters;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.util.PartitionUtil;
import org.apache.iceberg.util.SnapshotUtil;

/**
 * One kind's Iceberg table: a row per entity per business date ({@link IcebergLayout}). A snapshot's data files are
 * planned once and grouped by business date ({@link Layout}); a date's files are then read on demand with Iceberg's
 * own Parquet reader, only the columns asked for, with the row-group filter of the read (one entity: {@code id = …}).
 * Delete files (position and equality deletes, written by Spark, Trino, Flink or Snowflake row-level operations) are
 * applied by every read. Immutable per snapshot; thread-safe.
 */
final class IcebergTable {

    /**
     * One business date's data in a snapshot.
     *
     * @param files its data files, each with the delete files that apply to it
     * @param rows the rows the files hold, before deletes
     * @param deletes true when delete files apply to any of them
     * @param fingerprint identifies the files (and delete files): a cache key that survives commits of other dates
     */
    record Day(LocalDate date, List<FileScanTask> files, long rows, boolean deletes, long fingerprint) {}

    /** A snapshot's dates, newest last. */
    record Layout(long snapshotId, long timestamp, Schema schema, NavigableMap<LocalDate, Day> days) {
        boolean deletes() {
            return days.values().stream().anyMatch(Day::deletes);
        }
    }

    /** One business date's ids, sorted, each with the index of the file that holds it (in the day's file list). */
    record IdMap(String[] ids, int[] files) {
        int file(String id) {
            int i = Arrays.binarySearch(ids, id);
            return i < 0 ? -1 : files[i];
        }

        long bytes() {
            long b = 64L + 4L * files.length;
            for (String s : ids) {
                b += 48L + s.length();
            }
            return b;
        }
    }

    private final String kind;
    private final IcebergLake lake;
    private final ExecutorService pool;
    private volatile Table table;

    IcebergTable(String kind, IcebergLake lake, ExecutorService pool) {
        this.kind = kind;
        this.lake = lake;
        this.pool = pool;
    }

    /** A table already loaded (maintenance). */
    IcebergTable(Table table, ExecutorService pool) {
        this(table.name(), null, pool);
        this.table = table;
    }

    /** The table, loaded once; empty while it does not exist. */
    Optional<Table> table() {
        Table t = table;
        if (t == null) {
            t = lake.load(kind).orElse(null);
            table = t;
        }
        return Optional.ofNullable(t);
    }

    /** Re-reads the table's metadata (its current snapshot); returns the current snapshot id, or empty. */
    Optional<Long> refresh() {
        Optional<Table> t = table();
        if (t.isEmpty()) {
            return Optional.empty();
        }
        t.get().refresh();
        return Optional.ofNullable(t.get().currentSnapshot()).map(Snapshot::snapshotId);
    }

    /** The current snapshot id, without re-reading metadata. */
    Optional<Long> current() {
        return table().map(Table::currentSnapshot).map(Snapshot::snapshotId);
    }

    /**
     * The snapshot as known at {@code knownAt}: empty when nothing had been committed yet (or the snapshots of that
     * time were expired), and the current one when the time is after the latest commit.
     */
    Optional<Long> snapshotAt(Instant knownAt) {
        return table().map(t -> {
            Long id = SnapshotUtil.nullableSnapshotIdAsOfTime(t, knownAt.toEpochMilli());
            return id != null && t.snapshot(id) != null ? id : null;
        });
    }

    /** Plans the snapshot's files, grouped by business date (the manifests the snapshot lists are read once). */
    Layout layout(long snapshotId) {
        return layout(snapshotId, false);
    }

    /** {@link #layout(long)}, with each file's column metrics (bounds, counts) when {@code stats}. */
    Layout layout(long snapshotId, boolean stats) {
        Table t = table().orElseThrow();
        Snapshot s = t.snapshot(snapshotId);
        if (s == null) {
            t.refresh();
            s = t.snapshot(snapshotId);
        }
        Schema schema = SnapshotUtil.schemaFor(t, snapshotId);
        Map<LocalDate, List<FileScanTask>> byDate = new TreeMap<>();
        // no scan report in the log for every snapshot planned (Iceberg logs each one at INFO by default)
        var scan = t.newScan().useSnapshot(snapshotId).planWith(pool).metricsReporter(report -> { });
        try (CloseableIterable<FileScanTask> tasks = (stats ? scan.includeColumnStats() : scan).planFiles()) {
            for (FileScanTask task : tasks) {
                byDate.computeIfAbsent(dateOf(task), k -> new ArrayList<>()).add(task);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        NavigableMap<LocalDate, Day> days = new TreeMap<>();
        byDate.forEach((date, files) -> {
            files.sort(Comparator.comparing(f -> f.file().location()));
            long rows = 0;
            long fp = 1125899906842597L;
            boolean deletes = false;
            for (FileScanTask f : files) {
                rows += f.file().recordCount();
                fp = 31 * fp + f.file().location().hashCode();
                for (var d : f.deletes()) {
                    deletes = true;
                    fp = 31 * fp + d.location().hashCode();
                }
            }
            days.put(date, new Day(date, List.copyOf(files), rows, deletes, fp ^ ((long) files.size() << 48)));
        });
        return new Layout(snapshotId, s == null ? 0 : s.timestampMillis(), schema, days);
    }

    /** The business date a file belongs to (its identity partition on {@code business_date}); MIN for an undated table. */
    private static LocalDate dateOf(FileScanTask task) {
        List<PartitionField> fields = task.spec().fields();
        for (int i = 0; i < fields.size(); i++) {
            PartitionField f = fields.get(i);
            if (f.transform().isIdentity() && IcebergLayout.DATE.equals(task.spec().schema().findColumnName(f.sourceId()))) {
                Integer days = task.file().partition().get(i, Integer.class);
                return days == null ? LocalDate.MIN : LocalDate.ofEpochDay(days);
            }
        }
        return LocalDate.MIN;
    }

    /**
     * Opens one data file with only {@code projection}'s columns, Parquet row groups skipped by {@code filter}, and the
     * file's delete files applied. Rows that pass the filter's row groups but not the filter itself are returned too:
     * callers check the value they asked for.
     */
    static CloseableIterable<Record> open(FileIO io, Schema tableSchema, FileScanTask task, Schema projection, Expression filter) {
        if (task.file().format() != FileFormat.PARQUET) {
            throw new UnsupportedOperationException("only Parquet data files are read, not " + task.file().format() + ": " + task.file().location());
        }
        GenericDeleteFilter deletes = task.deletes().isEmpty() ? null : new GenericDeleteFilter(io, task, tableSchema, projection);
        Schema read = deletes == null ? projection : deletes.requiredSchema();
        Map<Integer, ?> constants = PartitionUtil.constantsMap(task, IdentityPartitionConverters::convertConstant);
        CloseableIterable<Record> rows = Parquet.read(io.newInputFile(task.file().location(), task.file().fileSizeInBytes()))
                .project(read)
                .createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(read, fileSchema, constants))
                .filter(filter)
                .reuseContainers()
                .build();
        return deletes == null ? rows : deletes.filter(rows);
    }

    /** The columns in the order asked (records are read by position). */
    private static Schema select(Schema schema, List<String> columns) {
        return new Schema(columns.stream().map(schema::findField).toList());
    }

    /** Reads each of the day's files on its own virtual thread (a day has a few large files), in file order. */
    private <T> List<T> perFile(Day day, java.util.function.IntFunction<T> read) {
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

    private void scan(Layout l, Day day, int file, List<String> columns, Expression filter, Consumer<Record> sink) {
        Table t = table().orElseThrow();
        try (CloseableIterable<Record> rows = open(t.io(), l.schema(), day.files().get(file), select(l.schema(), columns), filter)) {
            for (Record r : rows) {
                sink.accept(r);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The day's ids, read from the id column alone (no document is read), sorted, with the file each is in. */
    IdMap ids(Layout l, Day day) {
        List<String[]> perFile = perFile(day, f -> {
            List<String> ids = new ArrayList<>();
            scan(l, day, f, List.of(IcebergLayout.ID), Expressions.alwaysTrue(), r -> {
                Object v = r.get(0);
                if (v != null) {
                    ids.add(v.toString());
                }
            });
            return ids.toArray(new String[0]);
        });
        int n = perFile.stream().mapToInt(a -> a.length).sum();
        String[] ids = new String[n];
        int[] files = new int[n];
        // files of one laid-out day hold disjoint id ranges: concatenated in order of their first id, they are sorted
        Integer[] order = new Integer[perFile.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparing(i -> perFile.get(i).length == 0 ? "" : perFile.get(i)[0]));
        int at = 0;
        for (int f : order) {
            String[] chunk = perFile.get(f);
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
            return new IdMap(sortedIds, sortedFiles);
        }
        return new IdMap(ids, files);
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
        scan(l, day, file, List.of(IcebergLayout.ID, IcebergLayout.DOC), Expressions.equal(IcebergLayout.ID, id), r -> {
            if (found[0] == null && id.equals(String.valueOf(r.get(0))) && r.get(1) != null) {
                found[0] = r.get(1).toString();
            }
        });
        return Optional.ofNullable(found[0]);
    }

    /** True when a column of this type is read as numbers. */
    static boolean numeric(Type type) {
        return switch (type.typeId()) {
            case DOUBLE, FLOAT, LONG, INTEGER, DECIMAL -> true;
            default -> false;
        };
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
            num[c] = numeric(l.schema().findField(pathToColumn.get(paths.get(c))).type());
        }
        List<Object[]> perFile = perFile(day, f -> {
            List<String> ids = new ArrayList<>();
            List<List<Object>> values = new ArrayList<>();
            paths.forEach(p -> values.add(new ArrayList<>()));
            scan(l, day, f, cols, Expressions.alwaysTrue(), r -> {
                Object id = r.get(0);
                ids.add(id == null ? "" : id.toString());
                for (int c = 0; c < paths.size(); c++) {
                    Object v = r.get(c + 1);
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
        for (int f = 0; f < day.files().size(); f++) {
            scan(l, day, f, List.of(IcebergLayout.ID, IcebergLayout.DOC), Expressions.alwaysTrue(), r -> {
                if (r.get(0) != null && r.get(1) != null) {
                    out.put(r.get(0).toString(), r.get(1).toString());
                }
            });
        }
        return out;
    }

    /** A fresh pool for callers outside the connector (maintenance): virtual threads. */
    static ExecutorService virtualPool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
