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
package com.ash.drishti.plugin.delta;

import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One Delta table of entities: a row per entity per business date, {@code (id STRING, doc STRING)} with the
 * date as the partition column. Reads go through Delta Kernel (no Spark): the snapshot's file list is grouped by
 * partition, and a partition's Parquet files are read on demand. Immutable per snapshot; thread-safe.
 */
final class DeltaTable {

    /**
     * A snapshot's files by business date.
     *
     * @param schema the table's columns (promoted ones beside id and doc)
     * @param deletionVectors true when a file carries a deletion vector (column reads then go through documents)
     */
    record Layout(long version, long timestamp, Row scanState, NavigableMap<LocalDate, List<Row>> files, StructType schema,
            boolean deletionVectors) {}

    /** One business date's ids, sorted, each with the index of the file that holds it (in the date's file list). */
    record IdMap(String[] ids, int[] files) {
        int file(String id) {
            int i = java.util.Arrays.binarySearch(ids, id);
            return i < 0 ? -1 : files[i];
        }

        long bytes() {
            long b = 64L + 4L * files.length;
            for (String s : ids) {
                b += 48L + 2L * s.length();
            }
            return b;
        }
    }

    private final Engine engine;
    private final String path;
    private final String idColumn;
    private final String docColumn;
    private final String dateColumn;

    DeltaTable(Engine engine, String path, String idColumn, String docColumn, String dateColumn) {
        this.engine = engine;
        this.path = path;
        this.idColumn = idColumn;
        this.docColumn = docColumn;
        this.dateColumn = dateColumn;
    }

    String path() {
        return path;
    }

    /**
     * The snapshot as known at {@code knownAt}: null when the table did not exist yet (nothing was known then), and
     * the latest snapshot when the time is after the latest commit (what is known now was known then too).
     */
    private Snapshot asOf(Table t, Instant knownAt) {
        try {
            return t.getSnapshotAsOfTimestamp(engine, knownAt.toEpochMilli());
        } catch (io.delta.kernel.exceptions.KernelException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("before the earliest available version")) {
                return null;
            }
            if (m.contains("after the latest")) {
                return t.getLatestSnapshot(engine);
            }
            throw e;
        }
    }

    /** The latest snapshot's layout, or the one as known at {@code knownAt}; empty if the table does not exist. */
    Optional<Layout> layout(Instant knownAt) {
        Snapshot s;
        try {
            Table t = Table.forPath(engine, path);
            s = knownAt == null ? t.getLatestSnapshot(engine) : asOf(t, knownAt);
        } catch (TableNotFoundException e) {
            return Optional.empty();
        }
        if (s == null) {
            return Optional.empty();                     // nothing had been written yet at that time
        }
        Scan scan = s.getScanBuilder().withReadSchema(new StructType().add(idColumn, StringType.STRING).add(docColumn, StringType.STRING)).build();
        NavigableMap<LocalDate, List<Row>> byDate = new TreeMap<>();
        boolean dvs = false;
        try (CloseableIterator<FilteredColumnarBatch> it = scan.getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        Row r = rows.next();
                        String d = InternalScanFileUtils.getPartitionValues(r).get(dateColumn);
                        LocalDate date = d == null ? LocalDate.MIN : LocalDate.parse(d);
                        byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(r);
                        dvs |= InternalScanFileUtils.getDeletionVectorDescriptorFromRow(r) != null;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Optional.of(new Layout(s.getVersion(), s.getTimestamp(engine), scan.getScanState(engine), byDate, s.getSchema(), dvs));
    }

    /** The date's ids, read from the id column alone (no document is read), sorted, with the file each is in. */
    IdMap ids(Layout layout, LocalDate date) {
        List<Row> files = layout.files().getOrDefault(date, List.of());
        List<String> ids = new ArrayList<>();
        List<Integer> fileOf = new ArrayList<>();
        StructType schema = new StructType().add(idColumn, StringType.STRING);
        for (int f = 0; f < files.size(); f++) {
            int file = f;
            raw(files.get(f), schema, Optional.empty(), batch -> {
                io.delta.kernel.data.ColumnVector v = batch.getColumnVector(0);
                for (int r = 0; r < batch.getSize(); r++) {
                    if (!v.isNullAt(r)) {
                        ids.add(v.getString(r));
                        fileOf.add(file);
                    }
                }
            });
        }
        Integer[] order = new Integer[ids.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparing(ids::get));
        String[] sortedIds = new String[order.length];
        int[] sortedFiles = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            sortedIds[i] = ids.get(order[i]);
            sortedFiles[i] = fileOf.get(order[i]);
        }
        return new IdMap(sortedIds, sortedFiles);
    }

    /**
     * One entity's document from one file: the reader is given {@code id = …}, so Parquet skips the row groups whose
     * statistics cannot hold it (a table sorted by id reads one small row group). Deletion vectors are honoured.
     */
    Optional<String> doc(Layout layout, LocalDate date, int fileIndex, String id) {
        List<Row> files = layout.files().getOrDefault(date, List.of());
        if (fileIndex < 0 || fileIndex >= files.size()) {
            return Optional.empty();
        }
        Row fileRow = files.get(fileIndex);
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(layout.scanState());
        io.delta.kernel.expressions.Predicate eq = new io.delta.kernel.expressions.Predicate("=",
                new io.delta.kernel.expressions.Column(idColumn), io.delta.kernel.expressions.Literal.ofString(id));
        FileStatus fs = InternalScanFileUtils.getAddFileStatus(fileRow);
        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler()
                .readParquetFiles(Utils.singletonCloseableIterator(fs), physical, Optional.of(eq));
             CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, layout.scanState(), fileRow,
                     raw.map(FileReadResult::getData))) {
            while (data.hasNext()) {
                FilteredColumnarBatch b = data.next();
                ColumnarBatch cols = b.getData();
                int idIx = cols.getSchema().indexOf(idColumn);
                int docIx = cols.getSchema().indexOf(docColumn);
                try (CloseableIterator<Row> rows = b.getRows()) {
                    while (rows.hasNext()) {
                        Row r = rows.next();
                        if (!r.isNullAt(idIx) && id.equals(r.getString(idIx)) && !r.isNullAt(docIx)) {
                            return Optional.of(r.getString(docIx));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Optional.empty();
    }

    /**
     * Columns of the date's rows, read without documents: the id and each named column ({@code physical} name to
     * whether it is a number). Repeated text values (books, desks, currencies) share one string.
     */
    com.ash.drishti.api.ColumnSet columns(Layout layout, LocalDate date, Map<String, String> pathToColumn, Map<String, Boolean> numeric) {
        List<Row> files = layout.files().getOrDefault(date, List.of());
        StructType schema = new StructType().add(idColumn, StringType.STRING);
        List<String> paths = new ArrayList<>(pathToColumn.keySet());
        for (String p : paths) {
            schema = schema.add(pathToColumn.get(p), numeric.get(p) ? io.delta.kernel.types.DoubleType.DOUBLE : StringType.STRING);
        }
        List<String> ids = new ArrayList<>();
        Map<String, List<double[]>> nums = new LinkedHashMap<>();
        Map<String, List<String>> texts = new LinkedHashMap<>();
        Map<String, Map<String, String>> interned = new java.util.HashMap<>();
        paths.forEach(p -> {
            if (numeric.get(p)) {
                nums.put(p, new ArrayList<>());
            } else {
                texts.put(p, new ArrayList<>());
                interned.put(p, new java.util.HashMap<>());
            }
        });
        for (Row file : files) {
            raw(file, schema, Optional.empty(), batch -> {
                int n = batch.getSize();
                io.delta.kernel.data.ColumnVector idv = batch.getColumnVector(0);
                for (int r = 0; r < n; r++) {
                    ids.add(idv.isNullAt(r) ? "" : idv.getString(r));
                }
                for (int c = 0; c < paths.size(); c++) {
                    String p = paths.get(c);
                    io.delta.kernel.data.ColumnVector v = batch.getColumnVector(c + 1);
                    if (numeric.get(p)) {
                        double[] chunk = new double[n];
                        for (int r = 0; r < n; r++) {
                            chunk[r] = v.isNullAt(r) ? Double.NaN : v.getDouble(r);
                        }
                        nums.get(p).add(chunk);
                    } else {
                        Map<String, String> pool = interned.get(p);
                        List<String> out = texts.get(p);
                        for (int r = 0; r < n; r++) {
                            String s = v.isNullAt(r) ? null : v.getString(r);
                            out.add(s == null || pool.size() > 200_000 ? s : pool.computeIfAbsent(s, x -> x));
                        }
                    }
                }
            });
        }
        Map<String, double[]> numbers = new LinkedHashMap<>();
        nums.forEach((p, chunks) -> {
            double[] all = new double[ids.size()];
            int at = 0;
            for (double[] c : chunks) {
                System.arraycopy(c, 0, all, at, c.length);
                at += c.length;
            }
            numbers.put(p, all);
        });
        Map<String, String[]> text = new LinkedHashMap<>();
        texts.forEach((p, list) -> text.put(p, list.toArray(new String[0])));
        return new com.ash.drishti.api.ColumnSet(ids.toArray(new String[0]), numbers, text, date == LocalDate.MIN ? null : date);
    }

    /** Reads a file's Parquet columns as they are stored (no deletion vectors, no partition values). */
    private void raw(Row fileRow, StructType schema, Optional<io.delta.kernel.expressions.Predicate> filter,
            java.util.function.Consumer<ColumnarBatch> sink) {
        FileStatus fs = InternalScanFileUtils.getAddFileStatus(fileRow);
        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler().readParquetFiles(Utils.singletonCloseableIterator(fs), schema, filter)) {
            while (raw.hasNext()) {
                sink.accept(raw.next().getData());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every entity in one business date's partition: id to JSON text. */
    Map<String, String> read(Layout layout, LocalDate date) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Row> files = layout.files().get(date);
        if (files == null) {
            return out;
        }
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(layout.scanState());
        for (Row fileRow : files) {
            FileStatus fs = InternalScanFileUtils.getAddFileStatus(fileRow);
            try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler()
                    .readParquetFiles(Utils.singletonCloseableIterator(fs), physical, Optional.empty());
                 CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, layout.scanState(), fileRow,
                         raw.map(FileReadResult::getData))) {
                while (data.hasNext()) {
                    FilteredColumnarBatch b = data.next();
                    ColumnarBatch cols = b.getData();
                    int idIx = cols.getSchema().indexOf(idColumn);
                    int docIx = cols.getSchema().indexOf(docColumn);
                    try (CloseableIterator<Row> rows = b.getRows()) {
                        while (rows.hasNext()) {
                            Row r = rows.next();
                            if (!r.isNullAt(idIx) && !r.isNullAt(docIx)) {
                                out.put(r.getString(idIx), r.getString(docIx));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }
}
