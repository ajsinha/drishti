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
     * @param idScanState the scan state for reading the id column alone, with deletion vectors applied
     */
    record Layout(long version, long timestamp, Row scanState, NavigableMap<LocalDate, List<Row>> files, StructType schema,
            boolean deletionVectors, Row idScanState) {}

    /**
     * The files a business date is read from, as one short text (their paths, sorted, hashed): a date restated, or a table removed and
     * written again, has new files; a date untouched by a later commit keeps its stamp. {@code none} when the date has no files.
     */
    static String filesStamp(Layout layout, LocalDate date) {
        List<Row> files = layout.files().get(date);
        if (files == null || files.isEmpty()) {
            return "none";
        }
        List<String> paths = new java.util.ArrayList<>();
        for (Row r : files) {
            FileStatus fs = InternalScanFileUtils.getAddFileStatus(r);
            paths.add(fs.getPath() + ":" + fs.getSize());
        }
        java.util.Collections.sort(paths);
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n", paths).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One business date's ids, sorted, each with the index of the file that holds it (in the date's file list). */
    /** A date's ids, sorted, and the file each is in: in the heap ({@link HeapIdMap}) or memory-mapped from a file ({@link MappedIdMap}). */
    interface IdMap {
        int size();

        /** The i-th id in order. */
        String id(int i);

        /** The file index of {@code id}, or -1. */
        int file(String id);

        /** What it holds in the heap (for the cache's budget). */
        long bytes();

        /** Every id in order (built for the type-ahead; a mapped map makes the array on each call). */
        default String[] ids() {
            String[] out = new String[size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = id(i);
            }
            return out;
        }
    }

    record HeapIdMap(String[] idsInOrder, int[] files) implements IdMap {
        public int size() {
            return idsInOrder.length;
        }

        public String id(int i) {
            return idsInOrder[i];
        }

        public int file(String id) {
            int i = java.util.Arrays.binarySearch(idsInOrder, id);
            return i < 0 ? -1 : files[i];
        }

        public String[] ids() {
            return idsInOrder;
        }

        public long bytes() {
            long b = 64L + 4L * files.length;
            for (String s : idsInOrder) {
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

    private volatile String identity;

    private static String host() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            return "localhost";
        }
    }
    private static final java.util.regex.Pattern META_ID = java.util.regex.Pattern.compile("\"metaData\"\\s*:\\s*\\{[^}]*?\"id\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * The same for every server that reads this table (Drishti*net's replicas): the table's id from the {@code metaData} of its first
     * commit when the log is on a local disk and still holds it, else its location. Read once.
     */
    String identity() {
        String id = identity;
        if (id != null) {
            return id;
        }
        // an object store's address names the data wherever it is read from; a local path only on this machine (the same path on
        // another machine is another table), so it carries the host: two servers are taken for replicas only when they surely are
        boolean remote = path.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") && !path.startsWith("file:");
        id = "location:" + (remote ? "" : host() + ":") + path.replaceAll("/+$", "");
        try {
            java.nio.file.Path root = path.startsWith("file:") ? java.nio.file.Path.of(java.net.URI.create(path)) : java.nio.file.Path.of(path);
            java.nio.file.Path first = root.resolve("_delta_log").resolve("00000000000000000000.json");
            if (java.nio.file.Files.isRegularFile(first)) {
                var m = META_ID.matcher(java.nio.file.Files.readString(first));
                if (m.find()) {
                    id = "delta:" + m.group(1);
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // an object store or an unreadable log: the location says it
        }
        identity = id;
        return id;
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

    /**
     * One business date's size from the add actions of a snapshot (no data file is read).
     *
     * @param records         the sum of {@code numRecords} of the files whose statistics carry it
     * @param bytes           the bytes of those files
     * @param bytesUncounted  the bytes of the files without a record count
     * @param filesUncounted  how many files have no record count
     */
    record DayCount(long records, long bytes, long bytesUncounted, int filesUncounted) {}

    private static final java.util.regex.Pattern NUM_RECORDS = java.util.regex.Pattern.compile("\"numRecords\"\\s*:\\s*(\\d+)");

    /**
     * Every business date's size at a snapshot version, from the log's add actions and their statistics (docs/architecture/BI_RUNS.md section
     * 4.1: {@code SourcePlugin.rowEstimate}). Reads the log only, as {@link #layout} does, with the statistics column asked for.
     */
    NavigableMap<LocalDate, DayCount> counts(long version) {
        Snapshot s;
        try {
            s = Table.forPath(engine, path).getSnapshotAsOfVersion(engine, version);
        } catch (TableNotFoundException e) {
            return new TreeMap<>();
        }
        Scan scan = s.getScanBuilder().withReadSchema(new StructType().add(idColumn, StringType.STRING)).build();
        NavigableMap<LocalDate, long[]> sums = new TreeMap<>();
        CloseableIterator<FilteredColumnarBatch> files = scan instanceof io.delta.kernel.internal.ScanImpl si ? si.getScanFiles(engine, true) : scan.getScanFiles(engine);
        try (CloseableIterator<FilteredColumnarBatch> it = files) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        Row r = rows.next();
                        String d = InternalScanFileUtils.getPartitionValues(r).get(dateColumn);
                        LocalDate date = d == null ? LocalDate.MIN : LocalDate.parse(d);
                        long size = InternalScanFileUtils.getAddFileStatus(r).getSize();
                        long[] sum = sums.computeIfAbsent(date, k -> new long[4]);
                        Long n = numRecords(r);
                        if (n == null) {
                            sum[2] += size;
                            sum[3]++;
                        } else {
                            sum[0] += n;
                            sum[1] += size;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        NavigableMap<LocalDate, DayCount> out = new TreeMap<>();
        sums.forEach((d, v) -> out.put(d, new DayCount(v[0], v[1], v[2], (int) v[3])));
        return out;
    }

    /** {@code numRecords} of a scan file's add action statistics, or null when it has none. */
    private static Long numRecords(Row scanFile) {
        try {
            Row add = scanFile.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL);
            int at = add.getSchema().indexOf("stats");
            if (at < 0 || add.isNullAt(at)) {
                return null;
            }
            java.util.regex.Matcher m = NUM_RECORDS.matcher(add.getString(at));
            return m.find() ? Long.parseLong(m.group(1)) : null;
        } catch (RuntimeException e) {
            return null;
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
        Row idScanState = s.getScanBuilder().withReadSchema(new StructType().add(idColumn, StringType.STRING)).build().getScanState(engine);
        return Optional.of(new Layout(s.getVersion(), s.getTimestamp(engine), scan.getScanState(engine), byDate, s.getSchema(), dvs,
                idScanState));
    }

    /**
     * The date's ids, read from the id column alone (no document is read), sorted, with the file each is in. A file
     * with a deletion vector is read through Kernel so its deleted rows are left out (type-ahead must not offer them).
     */
    IdMap ids(Layout layout, LocalDate date) {
        List<Row> files = layout.files().getOrDefault(date, List.of());
        List<String> ids = new ArrayList<>();
        List<Integer> fileOf = new ArrayList<>();
        StructType schema = new StructType().add(idColumn, StringType.STRING);
        for (int f = 0; f < files.size(); f++) {
            int file = f;
            java.util.function.Consumer<FilteredColumnarBatch> sink = batch -> {
                io.delta.kernel.data.ColumnVector v = batch.getData().getColumnVector(batch.getData().getSchema().indexOf(idColumn));
                Optional<io.delta.kernel.data.ColumnVector> selected = batch.getSelectionVector();
                for (int r = 0; r < batch.getData().getSize(); r++) {
                    boolean live = selected.isEmpty() || (!selected.get().isNullAt(r) && selected.get().getBoolean(r));
                    if (live && !v.isNullAt(r)) {
                        ids.add(v.getString(r));
                        fileOf.add(file);
                    }
                }
            };
            Row fileRow = files.get(f);
            if (InternalScanFileUtils.getDeletionVectorDescriptorFromRow(fileRow) == null) {
                raw(fileRow, schema, Optional.empty(), batch -> sink.accept(new FilteredColumnarBatch(batch, Optional.empty())));
            } else {
                live(layout.idScanState(), fileRow, sink);
            }
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
        return new HeapIdMap(sortedIds, sortedFiles);
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
            // a text column is read in the type the table has (an old table's TRADE_ID may be a number) and turned into its text
            io.delta.kernel.types.DataType have = layout.schema() == null || layout.schema().indexOf(pathToColumn.get(p)) < 0 ? null
                    : layout.schema().get(pathToColumn.get(p)).getDataType();
            schema = schema.add(pathToColumn.get(p), numeric.get(p) ? io.delta.kernel.types.DoubleType.DOUBLE
                    : have instanceof io.delta.kernel.types.StringType || have == null || !isScalar(have) ? StringType.STRING : have);
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
            // a partition column (PRODUCT_TYPE=Cash/) is not in the Parquet file: its one value for the file is in the log
            Map<String, String> partition = InternalScanFileUtils.getPartitionValues(file);
            raw(file, schema, Optional.empty(), batch -> {
                int n = batch.getSize();
                io.delta.kernel.data.ColumnVector idv = batch.getColumnVector(0);
                for (int r = 0; r < n; r++) {
                    ids.add(idv.isNullAt(r) ? "" : idv.getString(r));
                }
                for (int c = 0; c < paths.size(); c++) {
                    String p = paths.get(c);
                    io.delta.kernel.data.ColumnVector v = batch.getColumnVector(c + 1);
                    String fixed = partition.get(pathToColumn.get(p));
                    if (numeric.get(p)) {
                        double[] chunk = new double[n];
                        double whole = fixed == null ? Double.NaN : partitionNumber(fixed);
                        for (int r = 0; r < n; r++) {
                            chunk[r] = fixed != null ? whole : v.isNullAt(r) ? Double.NaN : v.getDouble(r);
                        }
                        nums.get(p).add(chunk);
                    } else {
                        Map<String, String> pool = interned.get(p);
                        List<String> out = texts.get(p);
                        for (int r = 0; r < n; r++) {
                            String s = fixed != null ? fixed : v.isNullAt(r) ? null : text(v, r);
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

    /**
     * The date's rows with one vector column ({@code list<double>}, or {@code list<float>}: exposure paths may be float32,
     * EXPOSURE_GUIDE.md section 15.3) and some text columns, streamed to {@code sink} batch by batch (VAR_GUIDE.md section 29.3): one
     * buffer per read, reused for every row, so a date of millions of vectors is never held here. A float32 element is widened to a
     * double exactly. Read as stored (no deletion vectors: the vector tables are written without them); partition values come from the log.
     */
    void vectors(Layout layout, LocalDate date, String vectorColumn, List<String> textColumns, com.ash.drishti.api.VectorSink sink) {
        List<Row> files = layout.files().getOrDefault(date, List.of());
        StructType schema = new StructType().add(idColumn, StringType.STRING).add(vectorColumn, vectorType(layout, vectorColumn));
        for (String c : textColumns) {
            // read in the type the table has (QUANTITY is a double): its value comes back as text (a number keeps its digits)
            io.delta.kernel.types.DataType have = layout.schema() == null || layout.schema().indexOf(c) < 0 ? null
                    : layout.schema().get(c).getDataType();
            schema = schema.add(c, have != null && isScalar(have) ? have : StringType.STRING);
        }
        double[][] buf = {new double[512]};
        String[] texts = new String[textColumns.size()];
        StructType read = schema;
        for (Row file : files) {
            Map<String, String> partition = InternalScanFileUtils.getPartitionValues(file);
            raw(file, read, Optional.empty(), batch -> {
                int n = batch.getSize();
                io.delta.kernel.data.ColumnVector idv = batch.getColumnVector(0);
                io.delta.kernel.data.ColumnVector vec = batch.getColumnVector(1);
                for (int r = 0; r < n; r++) {
                    for (int c = 0; c < texts.length; c++) {
                        String fixed = partition.get(textColumns.get(c));
                        io.delta.kernel.data.ColumnVector tv = batch.getColumnVector(c + 2);
                        texts[c] = fixed != null ? fixed : tv.isNullAt(r) ? null : text(tv, r);
                    }
                    String id = idv.isNullAt(r) ? "" : idv.getString(r);
                    if (vec.isNullAt(r)) {
                        sink.row(id, buf[0], -1, texts);
                        continue;
                    }
                    int len = copy(vec.getArray(r), buf);
                    sink.row(id, buf[0], len, texts);
                }
            });
        }
    }

    /** One entity's vector columns on one date (a document's page puts them back in place of their markers); empty when none. */
    Map<String, double[]> vectorsOf(Layout layout, LocalDate date, int fileIndex, String id, List<String> vectorColumns) {
        List<Row> files = layout.files().getOrDefault(date, List.of());
        if (fileIndex < 0 || fileIndex >= files.size() || vectorColumns.isEmpty()) {
            return Map.of();
        }
        StructType schema = new StructType().add(idColumn, StringType.STRING);
        for (String c : vectorColumns) {
            schema = schema.add(c, vectorType(layout, c));
        }
        io.delta.kernel.expressions.Predicate eq = new io.delta.kernel.expressions.Predicate("=",
                new io.delta.kernel.expressions.Column(idColumn), io.delta.kernel.expressions.Literal.ofString(id));
        Map<String, double[]> out = new LinkedHashMap<>();
        raw(files.get(fileIndex), schema, Optional.of(eq), batch -> {
            io.delta.kernel.data.ColumnVector idv = batch.getColumnVector(0);
            for (int r = 0; r < batch.getSize() && out.isEmpty(); r++) {
                if (idv.isNullAt(r) || !id.equals(idv.getString(r))) {
                    continue;
                }
                for (int c = 0; c < vectorColumns.size(); c++) {
                    io.delta.kernel.data.ColumnVector v = batch.getColumnVector(c + 1);
                    if (!v.isNullAt(r)) {
                        double[][] one = {new double[0]};
                        int len = copy(v.getArray(r), one);
                        out.put(vectorColumns.get(c), java.util.Arrays.copyOf(one[0], len));
                    }
                }
            }
        });
        return out;
    }

    /** A vector column's read type: the table's own element type when it is {@code float} (float32), else {@code list<double>}. */
    static io.delta.kernel.types.ArrayType vectorType(Layout layout, String column) {
        if (layout.schema() != null && layout.schema().indexOf(column) >= 0
                && layout.schema().get(column).getDataType() instanceof io.delta.kernel.types.ArrayType at
                && at.getElementType() instanceof io.delta.kernel.types.FloatType) {
            return new io.delta.kernel.types.ArrayType(io.delta.kernel.types.FloatType.FLOAT, true);
        }
        return new io.delta.kernel.types.ArrayType(io.delta.kernel.types.DoubleType.DOUBLE, true);
    }

    /** An array cell's values into {@code buf[0]} (grown when short): doubles, or floats widened; a null element is NaN. Returns the length. */
    private static int copy(io.delta.kernel.data.ArrayValue a, double[][] buf) {
        int len = a.getSize();
        if (buf[0].length < len) {
            buf[0] = new double[Math.max(len, buf[0].length * 2)];
        }
        io.delta.kernel.data.ColumnVector el = a.getElements();
        double[] b = buf[0];
        boolean f32 = el.getDataType() instanceof io.delta.kernel.types.FloatType;
        for (int i = 0; i < len; i++) {
            b[i] = el.isNullAt(i) ? Double.NaN : f32 ? el.getFloat(i) : el.getDouble(i);
        }
        return len;
    }

    private static boolean isScalar(io.delta.kernel.types.DataType t) {
        return t instanceof io.delta.kernel.types.LongType || t instanceof io.delta.kernel.types.IntegerType
                || t instanceof io.delta.kernel.types.ShortType || t instanceof io.delta.kernel.types.ByteType
                || t instanceof io.delta.kernel.types.DoubleType || t instanceof io.delta.kernel.types.FloatType
                || t instanceof io.delta.kernel.types.DecimalType || t instanceof io.delta.kernel.types.BooleanType;
    }

    /** A cell as text: a number keeps its digits (1000008, never 1000008.0), so an id written as a number reads as the id. */
    static String text(io.delta.kernel.data.ColumnVector v, int r) {
        io.delta.kernel.types.DataType t = v.getDataType();
        if (t instanceof io.delta.kernel.types.LongType) {
            return Long.toString(v.getLong(r));
        }
        if (t instanceof io.delta.kernel.types.IntegerType) {
            return Integer.toString(v.getInt(r));
        }
        if (t instanceof io.delta.kernel.types.ShortType) {
            return Short.toString(v.getShort(r));
        }
        if (t instanceof io.delta.kernel.types.ByteType) {
            return Byte.toString(v.getByte(r));
        }
        if (t instanceof io.delta.kernel.types.DoubleType) {
            return com.ash.drishti.api.IdText.of(v.getDouble(r));
        }
        if (t instanceof io.delta.kernel.types.FloatType) {
            return com.ash.drishti.api.IdText.of(v.getFloat(r));
        }
        if (t instanceof io.delta.kernel.types.DecimalType) {
            return v.getDecimal(r).stripTrailingZeros().toPlainString();
        }
        if (t instanceof io.delta.kernel.types.BooleanType) {
            return Boolean.toString(v.getBoolean(r));
        }
        return v.getString(r);
    }

    private static double partitionNumber(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
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

    /** Reads a file's rows as the table holds them ({@code scanState}'s columns, deletion vectors applied). */
    private void live(Row scanState, Row fileRow, java.util.function.Consumer<FilteredColumnarBatch> sink) {
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(scanState);
        FileStatus fs = InternalScanFileUtils.getAddFileStatus(fileRow);
        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler()
                .readParquetFiles(Utils.singletonCloseableIterator(fs), physical, Optional.empty());
             CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, scanState, fileRow,
                     raw.map(FileReadResult::getData))) {
            while (data.hasNext()) {
                sink.accept(data.next());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every entity in one business date's partition: id to JSON text. */
    Map<String, String> read(Layout layout, LocalDate date) {
        Map<String, String> out = new LinkedHashMap<>();
        forEachDoc(layout, date, out::put);
        return out;
    }

    /**
     * Every (id, document) of the date, file by file and batch by batch, kept nowhere: one pass over the files, each row
     * group decoded once (a whole day for derived pages, without holding the day in memory).
     */
    void forEachDoc(Layout layout, LocalDate date, java.util.function.BiConsumer<String, String> sink) {
        List<Row> files = layout.files().get(date);
        if (files == null) {
            return;
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
                                sink.accept(r.getString(idIx), r.getString(docIx));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
