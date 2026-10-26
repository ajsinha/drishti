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
package com.ash.drishti.deltalake.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.DataWriteContext;
import io.delta.kernel.Operation;
import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.Transaction;
import io.delta.kernel.TransactionBuilder;
import io.delta.kernel.TransactionCommitResult;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.defaults.internal.data.DefaultColumnarBatch;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Literal;
import io.delta.kernel.hook.PostCommitHook;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.SingleAction;
import io.delta.kernel.types.ArrayType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.DataFileStatus;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Drishti's own Delta Lake writer: writes the staged files of a {@link LakeManifest} as Parquet through Delta Kernel and commits
 * them, with the files of the replaced business dates (or partitions) removed, as ONE version: a reader sees the whole load or
 * none of it. Local tables only; no Hadoop file system is used (see {@link LocalWriteFileIO}).
 *
 * <p>Rows are read from the staged files a batch at a time and handed to the Parquet writer as they are read, so memory holds
 * one batch, the open Parquet row group and the commit's file list, whatever the size of the load.
 *
 * <p>The table is created with the manifest's schema and partitioning on the first write. An existing table keeps its own: a
 * column the table does not have is left out (and reported), a column the load does not have is written as null.
 */
public final class LakeWriter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENGINE_INFO = "Drishti lake build";
    /**
     * The most bytes one Parquet row group holds: DRISHTI_LAKE_ROW_GROUP_KB (default 256), or DRISHTI_LAKE_ROW_GROUP_MB. A single
     * entity's read decodes at most its row group (its page, with the page index), so small groups make first reads cheap.
     */
    static final long ROW_GROUP_BYTES = System.getenv("DRISHTI_LAKE_ROW_GROUP_MB") != null
            ? Long.parseLong(System.getenv("DRISHTI_LAKE_ROW_GROUP_MB")) * 1024 * 1024
            : Long.parseLong(System.getenv().getOrDefault("DRISHTI_LAKE_ROW_GROUP_KB", "256")) * 1024;
    /** The most bytes one Parquet page holds (DRISHTI_LAKE_PAGE_KB, default 64): the unit the page index can skip to. */
    static final long PAGE_BYTES = Long.parseLong(System.getenv().getOrDefault("DRISHTI_LAKE_PAGE_KB", "64")) * 1024;

    /** What a write did. */
    public record Result(long version, int files, long rows, int removed, List<String> leftOut) {}

    private final Engine engine;

    public LakeWriter() {
        // SNAPPY as delta-rs writes (Kernel writes uncompressed otherwise); small row groups and pages (Parquet's defaults are 128 MB
        // and 1 MB): a single entity's read decodes its row group, or with the page index only the page that holds it
        this(DefaultEngine.create(new LocalWriteFileIO(Map.of("parquet.compression", "SNAPPY", "parquet.block.size", String.valueOf(ROW_GROUP_BYTES),
                "parquet.page.size", String.valueOf(PAGE_BYTES)))));
    }

    LakeWriter(Engine engine) {
        this.engine = engine;
    }

    /** Writes the manifest's parts; {@code partsDone} is told after each part. */
    public Result write(LakeManifest m, IntConsumer partsDone) throws IOException {
        Files.createDirectories(LocalWriteFileIO.local(m.table()));
        Table table = Table.forPath(engine, m.table());
        Snapshot before;
        try {
            before = table.getLatestSnapshot(engine);
        } catch (TableNotFoundException e) {
            before = null;
        }
        StructType wanted = schema(m);
        TransactionBuilder tb;
        if (before == null) {
            tb = table.createTransactionBuilder(engine, ENGINE_INFO, Operation.CREATE_TABLE)
                    .withSchema(engine, wanted).withPartitionColumns(engine, m.partitionColumns());
            if (!m.properties().isEmpty()) {
                tb = tb.withTableProperties(engine, m.properties());        // retention (7 years for the risk lake), append-only
            }
        } else {
            if (!before.getPartitionColumnNames().equals(m.partitionColumns())) {
                throw new IllegalStateException(m.table() + " is partitioned by " + before.getPartitionColumnNames() + ", this load by "
                        + m.partitionColumns());
            }
            tb = table.createTransactionBuilder(engine, ENGINE_INFO, Operation.WRITE);
        }
        Transaction txn = tb.build(engine);
        StructType schema = txn.getSchema(engine);
        List<String> leftOut = new ArrayList<>();
        for (StructField f : wanted.fields()) {
            if (schema.indexOf(f.getName()) < 0) {
                leftOut.add(f.getName());
            }
        }
        Row state = txn.getTransactionState(engine);
        List<Row> actions = new ArrayList<>();
        if (before != null && !m.mode().equals("append")) {
            actions.addAll(removals(before, m));
        }
        int removed = actions.size();
        long rows = 0;
        int files = 0;
        int done = 0;
        for (LakeManifest.Part part : m.parts()) {
            Map<String, Literal> pv = partitionValues(m, part, schema);
            DataWriteContext ctx = Transaction.getWriteContext(engine, state, pv);
            List<Column> stats = new ArrayList<>();
            for (Column c : ctx.getStatisticsColumns()) {
                if (m.statsColumns().contains(c.getNames()[0])) {
                    stats.add(c);
                }
            }
            try (Batches data = new Batches(m, part, schema);
                 CloseableIterator<FilteredColumnarBatch> physical = Transaction.transformLogicalData(engine, state, data, pv);
                 CloseableIterator<DataFileStatus> written = engine.getParquetHandler().writeParquetFiles(ctx.getTargetDirectory(), physical, stats);
                 CloseableIterator<Row> adds = Transaction.generateAppendActions(engine, state, written, ctx)) {
                while (adds.hasNext()) {
                    actions.add(adds.next());
                    files++;
                }
                rows += data.rows;
            }
            partsDone.accept(++done);
        }
        TransactionCommitResult result = txn.commit(engine, CloseableIterable.inMemoryIterable(toIterator(actions)));
        for (PostCommitHook hook : result.getPostCommitHooks()) {
            try {
                hook.threadSafeInvoke(engine);                  // a checkpoint every so many versions keeps the log short to read
            } catch (IOException | RuntimeException e) {
                // a hook that fails leaves the commit as it is; the next write or lake maintenance does it
            }
        }
        return new Result(result.getVersion(), files, rows, removed, leftOut);
    }

    /** The table's columns: id, doc, business_date, the partition columns, the promoted columns. */
    static StructType schema(LakeManifest m) {
        StructType s = new StructType();
        for (LakeManifest.Column c : m.columns()) {
            s = s.add(c.name(), type(c.type()), true);
        }
        return s;
    }

    /** A vector column's type: Parquet LIST of DOUBLE, elements nullable (a missing scenario value is null, never zero). */
    static final ArrayType VECTOR = new ArrayType(DoubleType.DOUBLE, true);

    static DataType type(String t) {
        return switch (t) {
            case "double" -> DoubleType.DOUBLE;
            case "long" -> LongType.LONG;
            case "date" -> DateType.DATE;
            case "vector" -> VECTOR;            // a P&L vector: list<double> (VAR_GUIDE.md section 29.3)
            default -> StringType.STRING;
        };
    }

    private static Map<String, Literal> partitionValues(LakeManifest m, LakeManifest.Part part, StructType schema) {
        Map<String, Literal> out = new HashMap<>();
        for (String name : m.partitionColumns()) {
            String v = part.values().get(name);
            DataType t = schema.get(name).getDataType();
            if (v == null) {
                out.put(name, Literal.ofNull(t));
            } else if (t instanceof DateType) {
                out.put(name, Literal.ofDate((int) LocalDate.parse(v).toEpochDay()));
            } else {
                out.put(name, Literal.ofString(v));
            }
        }
        return out;
    }

    /** Remove actions for the files of the dates (or the exact partitions) this load replaces. */
    private List<Row> removals(Snapshot s, LakeManifest m) throws IOException {
        Set<String> dates = new HashSet<>();
        Set<Map<String, String>> partitions = new HashSet<>();
        for (LakeManifest.Part p : m.parts()) {
            dates.add(p.values().get("business_date"));
            partitions.add(p.values());
        }
        boolean byDate = m.mode().equals("overwrite-dates");
        long now = System.currentTimeMillis();
        List<Row> out = new ArrayList<>();
        Scan scan = s.getScanBuilder().build();
        try (CloseableIterator<FilteredColumnarBatch> it = scan.getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        Row r = rows.next();
                        Map<String, String> pv = InternalScanFileUtils.getPartitionValues(r);
                        Map<String, String> mine = new HashMap<>();
                        m.partitionColumns().forEach(c -> mine.put(c, pv.get(c)));
                        if (byDate ? dates.contains(pv.get("business_date")) : partitions.contains(mine)) {
                            Row add = r.getStruct(r.getSchema().indexOf("add"));
                            out.add(SingleAction.createRemoveFileSingleAction(new AddFile(add).toRemoveFileRow(true, Optional.of(now))));
                        }
                    }
                }
            }
        }
        return out;
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
            public void close() {}
        };
    }

    /** A staged file read as column batches of the table's schema, one batch at a time. */
    private static final class Batches implements CloseableIterator<FilteredColumnarBatch> {
        private final LakeManifest m;
        private final LakeManifest.Part part;
        private final StructType schema;
        private final BufferedReader in;
        private FilteredColumnarBatch next;
        private boolean eof;
        long rows;

        Batches(LakeManifest m, LakeManifest.Part part, StructType schema) throws IOException {
            this.m = m;
            this.part = part;
            this.schema = schema;
            this.in = Files.newBufferedReader(part.file(), StandardCharsets.UTF_8);
        }

        @Override
        public boolean hasNext() {
            if (next == null && !eof) {
                next = read();
            }
            return next != null;
        }

        @Override
        public FilteredColumnarBatch next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            FilteredColumnarBatch b = next;
            next = null;
            return b;
        }

        private FilteredColumnarBatch read() {
            int n = schema.length();
            List<JsonNode> batch = new ArrayList<>();
            try {
                String line;
                while (batch.size() < m.batchRows() && (line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        batch.add(JSON.readTree(line));
                    }
                }
                if (batch.isEmpty()) {
                    eof = true;
                    return null;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ColumnVector[] vectors = new ColumnVector[n];
            for (int c = 0; c < n; c++) {
                StructField f = schema.at(c);
                Object[] values = new Object[batch.size()];
                for (int r = 0; r < batch.size(); r++) {
                    values[r] = value(f, batch.get(r));
                }
                vectors[c] = new Vector(f.getDataType(), values);
            }
            rows += batch.size();
            return new FilteredColumnarBatch(new DefaultColumnarBatch(batch.size(), schema, vectors), Optional.empty());
        }

        private Object value(StructField f, JsonNode row) {
            String name = f.getName();
            DataType t = f.getDataType();
            JsonNode v;
            if (name.equals("id")) {
                v = row.get(0);
            } else if (name.equals("doc")) {
                v = row.get(1);
            } else if (part.values().containsKey(name)) {
                String pv = part.values().get(name);
                return pv == null ? null : t instanceof DateType ? (int) LocalDate.parse(pv).toEpochDay() : pv;
            } else {
                v = row.path(2).get(name);
            }
            if (v == null || v.isNull()) {
                return null;
            }
            if (t instanceof DoubleType) {
                return v.isNumber() ? v.asDouble() : null;
            }
            if (t instanceof LongType) {
                return v.isNumber() ? v.asLong() : null;
            }
            if (t instanceof ArrayType) {
                if (!v.isArray()) {
                    return null;
                }
                double[] out = new double[v.size()];
                for (int i = 0; i < out.length; i++) {
                    JsonNode x = v.get(i);
                    out[i] = x != null && x.isNumber() ? x.asDouble() : Double.NaN;
                }
                return out;
            }
            if (t instanceof DateType) {
                return (int) LocalDate.parse(v.asText().substring(0, 10)).toEpochDay();
            }
            return v.isTextual() ? v.asText() : v.toString();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /**
     * One column of a batch over plain values: String, Double, Long, Integer (days since the epoch) for a date, or double[] for a vector
     * (NaN elements written as null).
     */
    private record Vector(DataType type, Object[] values) implements ColumnVector {
        @Override
        public DataType getDataType() {
            return type;
        }

        @Override
        public int getSize() {
            return values.length;
        }

        @Override
        public void close() {}

        @Override
        public boolean isNullAt(int rowId) {
            return values[rowId] == null;
        }

        @Override
        public String getString(int rowId) {
            return (String) values[rowId];
        }

        @Override
        public double getDouble(int rowId) {
            return (Double) values[rowId];
        }

        @Override
        public int getInt(int rowId) {
            return (Integer) values[rowId];
        }

        @Override
        public long getLong(int rowId) {
            return (Long) values[rowId];
        }

        @Override
        public io.delta.kernel.data.ArrayValue getArray(int rowId) {
            double[] a = (double[]) values[rowId];
            return new io.delta.kernel.data.ArrayValue() {
                @Override
                public int getSize() {
                    return a.length;
                }

                @Override
                public ColumnVector getElements() {
                    return new Elements(a);
                }
            };
        }
    }

    /** A vector's elements as a column. */
    private record Elements(double[] a) implements ColumnVector {
        @Override
        public DataType getDataType() {
            return DoubleType.DOUBLE;
        }

        @Override
        public int getSize() {
            return a.length;
        }

        @Override
        public void close() {}

        @Override
        public boolean isNullAt(int rowId) {
            return Double.isNaN(a[rowId]);
        }

        @Override
        public double getDouble(int rowId) {
            return a[rowId];
        }
    }
}
