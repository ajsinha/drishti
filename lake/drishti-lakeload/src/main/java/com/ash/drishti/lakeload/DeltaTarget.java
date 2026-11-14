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

import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import com.ash.drishti.iceberg.write.ParquetDataWriter;
import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.S3Settings;
import io.delta.kernel.DataWriteContext;
import io.delta.kernel.Operation;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.Transaction;
import io.delta.kernel.TransactionBuilder;
import io.delta.kernel.TransactionCommitResult;
import io.delta.kernel.data.Row;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.SingleAction;
import io.delta.kernel.internal.util.ColumnMapping;
import io.delta.kernel.internal.util.FileNames;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Literal;
import io.delta.kernel.hook.PostCommitHook;
import io.delta.kernel.statistics.DataFileStatistics;
import io.delta.kernel.types.ArrayType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.DataFileStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A Delta table in the layout {@code drishti.py lake build} writes and the Delta connector reads ({@code id}, {@code doc},
 * {@code business_date}, the partition columns, the promoted columns, {@code list<double>} vector columns; partitioned by
 * {@code business_date} then the partition columns). The Parquet files are written by the loader's threads (Drishti's own writer, no
 * Hadoop) into the folders Delta Kernel names, and each date is committed through Kernel as ONE version that adds its files and removes
 * the date's earlier ones, as {@link LakeWriter} does. A new table is created by its first date's commit, with {@code target.properties}.
 * An existing table keeps its schema and partitioning: a load column it lacks is left out (and reported), a column the load lacks is null.
 * The two load columns ({@code _load_id}, {@code _source_file}) are the exception: a table made before them gains them, as two nullable
 * columns added at the end in one metadata commit ({@link #withLoadColumns}); its files are not rewritten, their rows read them as null
 * (the oldest load).
 */
final class DeltaTarget implements TableTarget {

    static final String ENGINE_INFO = "Drishti parallel loader";   // the commits' engineInfo (lake undo's too)
    /** The writer's row groups and pages: as {@link LakeWriter} (DRISHTI_LAKE_ROW_GROUP_KB, 256; DRISHTI_LAKE_PAGE_KB, 64). */
    private static final long ROW_GROUP = System.getenv("DRISHTI_LAKE_ROW_GROUP_MB") != null
            ? Long.parseLong(System.getenv("DRISHTI_LAKE_ROW_GROUP_MB")) * 1024 * 1024
            : Long.parseLong(System.getenv().getOrDefault("DRISHTI_LAKE_ROW_GROUP_KB", "256")) * 1024;
    private static final long PAGE = Long.parseLong(System.getenv().getOrDefault("DRISHTI_LAKE_PAGE_KB", "64")) * 1024;

    private final String path;
    private final LoadConfig cfg;
    private final RowPlan plan;
    private final Engine engine;
    private final FileAccess io;
    private final Table table;
    private List<String> partitionColumns;
    private StructType schema;
    private Schema fileSchema;
    private Source[] sources;
    private Map<String, String> options;
    private final List<String> leftOut = new ArrayList<>();
    private Snapshot before;
    private Transaction txn;
    private Row state;
    private final Map<List<String>, DataWriteContext> contexts = new HashMap<>();

    DeltaTarget(LoadConfig cfg, RowPlan plan) throws IOException {
        this.cfg = cfg;
        this.plan = plan;
        this.path = cfg.tablePath();
        Map<String, String> s3 = cfg.target().storage();
        this.engine = LakeWriter.engineFor(path, s3);
        this.io = new FileAccess(S3Settings.from(s3), com.ash.drishti.iceberg.io.Relocation.none(), null);
        if (LocalPaths.isLocal(path)) {
            Files.createDirectories(Path.of(LocalPaths.toLocal(path, LocalPaths.WINDOWS)));
        }
        this.table = Table.forPath(engine, path);
    }

    @Override
    public void open(Columns c) throws IOException {
        List<String> parts = new ArrayList<>();
        parts.add("business_date");
        plan.partition.forEach(p -> parts.add(RowPlan.column(p)));
        before = snapshot();
        StructType wanted = new StructType().add("id", StringType.STRING, true).add("doc", StringType.STRING, true).add("business_date", DateType.DATE, true);
        for (String p : plan.partition) {
            wanted = wanted.add(RowPlan.column(p), StringType.STRING, true);
        }
        for (int i = 0; i < c.promoted().size(); i++) {
            wanted = wanted.add(c.promoted().get(i), c.list()[i] ? new ArrayType(StringType.STRING, true)
                    : c.numeric()[i] ? DoubleType.DOUBLE : StringType.STRING, true);
        }
        for (RowPlan.Vector v : plan.vectors) {
            wanted = wanted.add(v.column(), new ArrayType(DoubleType.DOUBLE, true), true);
        }
        if (RowPlan.loadColumns) {
            wanted = wanted.add(RowPlan.LOAD_ID, StringType.STRING, true).add(RowPlan.SOURCE_FILE, StringType.STRING, true);
        }
        if (before == null) {
            schema = wanted;
            partitionColumns = parts;
        } else {
            if (!before.getPartitionColumnNames().equals(parts)) {
                throw new IllegalStateException(path + " is partitioned by " + before.getPartitionColumnNames() + ", this load by " + parts
                        + " (give the table's partition-by, or write a new table)");
            }
            if (RowPlan.loadColumns) {
                before = withLoadColumns(before);
            }
            schema = before.getSchema();
            partitionColumns = before.getPartitionColumnNames();
            for (StructField f : wanted.fields()) {
                if (schema.indexOf(f.getName()) < 0) {
                    leftOut.add(f.getName());
                }
            }
        }
        List<NestedField> fields = new ArrayList<>();
        int id = 1;
        List<String> vectors = new ArrayList<>();
        for (StructField f : schema.fields()) {
            if (partitionColumns.contains(f.getName())) {
                continue;                                          // a partition column lives in the folder names, not in the files
            }
            Type t = icebergType(f, id + 1000);
            if (t instanceof Type.ListType l && !(l.element() instanceof Type.Primitive p && p.id() == Type.Id.STRING)) {
                vectors.add(f.getName());                            // a vector's encoding; a list attribute (array<string>) is plain
            }
            fields.add(NestedField.optional(id++, f.getName(), t));
        }
        fileSchema = new Schema(0, fields);
        sources = TableTarget.sources(fileSchema, plan);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("write.parquet.row-group-size-bytes", String.valueOf(ROW_GROUP));
        base.put("write.parquet.page-size-bytes", String.valueOf(PAGE));
        base.put("write.metadata.metrics.default", "full");             // Delta statistics: exact minimum and maximum
        base.put("write.metadata.metrics.column.doc", "none");
        options = TableTarget.writerOptions(base, cfg.target().layout(), "snappy", vectors);
    }

    /**
     * A table made before the load columns, with {@code _load_id} and {@code _source_file} added: one commit of a {@code metaData} action
     * whose schema is the table's with the two nullable string columns at the end (what Delta's merge-schema writes; Kernel itself evolves
     * a schema only under column mapping). No file is rewritten. Written with put-if-absent through the engine; when another writer took
     * the version, read again. A table under column mapping or in-commit timestamps keeps its schema (the columns left out, reported).
     */
    private Snapshot withLoadColumns(Snapshot snap) throws IOException {
        for (int attempt = 0; ; attempt++) {
            StructType s = snap.getSchema();
            List<String> add = new ArrayList<>();
            for (String c : List.of(RowPlan.LOAD_ID, RowPlan.SOURCE_FILE)) {
                if (s.indexOf(c) < 0) {
                    add.add(c);
                }
            }
            if (add.isEmpty()) {
                return snap;
            }
            SnapshotImpl si = (SnapshotImpl) snap;
            Metadata meta = si.getMetadata();
            Map<String, String> conf = meta.getConfiguration();
            if (ColumnMapping.isColumnMappingModeEnabled(ColumnMapping.getColumnMappingMode(conf))
                    || "true".equalsIgnoreCase(conf.get("delta.enableInCommitTimestamps"))) {
                return snap;                                            // left out by open(): the report lists them
            }
            StructType grown = s;
            for (String c : add) {
                grown = grown.add(c, StringType.STRING, true);
            }
            Row action = SingleAction.createMetadataSingleAction(meta.withNewSchema(grown).toRow());
            String file = FileNames.deltaFile(si.getLogPath(), si.getVersion() + 1);
            try {
                engine.getJsonHandler().writeJsonFileAtomically(file, Utils.singletonCloseableIterator(action), false);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                if (attempt >= 10) {
                    throw new IOException(path + ": the load columns could not be added (the table changed ten times meanwhile)", e);
                }
            }
            snap = table.getLatestSnapshot(engine);
        }
    }

    private static Type icebergType(StructField f, int elementId) {
        DataType t = f.getDataType();
        if (t instanceof StringType) {
            return Type.Primitive.STRING;
        }
        if (t instanceof DoubleType) {
            return Type.Primitive.DOUBLE;
        }
        if (t instanceof LongType) {
            return Type.Primitive.LONG;
        }
        if (t instanceof DateType) {
            return Type.Primitive.DATE;
        }
        if (t instanceof ArrayType a && (a.getElementType() instanceof DoubleType || a.getElementType() instanceof FloatType)) {
            return new Type.ListType(elementId, false, a.getElementType() instanceof FloatType ? Type.Primitive.FLOAT : Type.Primitive.DOUBLE);
        }
        if (t instanceof ArrayType a && a.getElementType() instanceof StringType) {
            return new Type.ListType(elementId, false, Type.Primitive.STRING);           // a list attribute (DATA_LAKE_GUIDE.md section 2.4)
        }
        throw new IllegalStateException("the table's column " + f.getName() + " is " + t + ": the loader writes string, double, long, date, "
                + "list<double> and list<string> columns");
    }

    private Snapshot snapshot() {
        try {
            return table.getLatestSnapshot(engine);
        } catch (TableNotFoundException e) {
            return null;
        }
    }

    @Override
    public void begin(LocalDate date) {
        TransactionBuilder tb;
        if (before == null) {
            tb = table.createTransactionBuilder(engine, ENGINE_INFO, Operation.CREATE_TABLE).withSchema(engine, schema)
                    .withPartitionColumns(engine, partitionColumns);
            if (!cfg.target().properties().isEmpty()) {
                tb = tb.withTableProperties(engine, cfg.target().properties());
            }
        } else {
            tb = table.createTransactionBuilder(engine, ENGINE_INFO, Operation.WRITE);
        }
        if (cfg.target().appends() && cfg.target().batchId() != null) {   // the batch's id in the commit: Delta's guard against a second commit
            tb = tb.withTransactionId(engine, appId(date), 1);
        }
        txn = tb.build(engine);
        state = txn.getTransactionState(engine);
        synchronized (contexts) {
            contexts.clear();
        }
    }

    private DataWriteContext context(LocalDate date, String[] values) {
        List<String> k = new ArrayList<>(List.of(date.toString()));
        k.addAll(java.util.Arrays.asList(values));
        synchronized (contexts) {
            return contexts.computeIfAbsent(k, x -> Transaction.getWriteContext(engine, state, literals(date, values)));
        }
    }

    private Map<String, Literal> literals(LocalDate date, String[] values) {
        Map<String, Literal> out = new HashMap<>();
        out.put("business_date", Literal.ofDate((int) date.toEpochDay()));
        for (int i = 1; i < partitionColumns.size(); i++) {
            String v = i - 1 < values.length ? values[i - 1] : null;
            out.put(partitionColumns.get(i), v == null || v.isEmpty() ? Literal.ofNull(StringType.STRING) : Literal.ofString(v));   // empty is null, as in Spark
        }
        return out;
    }

    @Override
    public FileOut newFile(LocalDate date, String[] partitionValues, String name) throws IOException {
        String dir = LakeWriter.targetDirectory(context(date, partitionValues)).replaceAll("/+$", "");
        String file = dir + "/" + name;
        ParquetDataWriter w = new ParquetDataWriter(io, file, fileSchema, options, 0, new Object[0], null);
        return new ParquetOut(w, fileSchema, sources, date, false, file, partitionValues, x -> {
            try {
                return x.finish();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @Override
    public long commit(LocalDate date, List<WrittenFile> files) throws IOException {
        List<Row> actions = new ArrayList<>();
        if (before != null && !cfg.target().appends()) {   // replace: the date's earlier files go in the same commit; append keeps them
            String day = date.toString();
            actions.addAll(LakeWriter.removals(engine, before, pv -> day.equals(pv.get("business_date"))));
        }
        Map<List<String>, List<DataFileStatus>> byPartition = new LinkedHashMap<>();
        for (WrittenFile f : files) {
            byPartition.computeIfAbsent(java.util.Arrays.asList(f.partition()), k -> new ArrayList<>()).add(status((DataFile) f.handle()));
        }
        for (Map.Entry<List<String>, List<DataFileStatus>> e : byPartition.entrySet()) {
            String[] values = e.getKey().toArray(new String[0]);
            try (CloseableIterator<Row> adds = Transaction.generateAppendActions(engine, state, iterator(e.getValue()), context(date, values))) {
                while (adds.hasNext()) {
                    actions.add(adds.next());
                }
            }
        }
        TransactionCommitResult result = txn.commit(engine, CloseableIterable.inMemoryIterable(iterator(actions)));
        for (PostCommitHook hook : result.getPostCommitHooks()) {
            try {
                hook.threadSafeInvoke(engine);                     // a checkpoint every so many versions, as lake build's writer
            } catch (IOException | RuntimeException e) {
                // the commit stands; the next write or lake maintain checkpoints
            }
        }
        before = snapshot();
        return result.getVersion();
    }

    @Override
    public boolean appended(LocalDate date) {
        return cfg.target().appends() && cfg.target().batchId() != null && before != null
                && before.getLatestTransactionVersion(engine, appId(date)).isPresent();
    }

    /** The application id of a batch's append of a date ({@code drishti-batch:<batch>:<date>}), version 1 once committed. */
    private String appId(LocalDate date) {
        return "drishti-batch:" + cfg.target().batchId() + ":" + date;
    }

    /** A written file as Kernel takes it: path, size, and statistics (row count, null counts, minimum and maximum of the scalar columns). */
    private DataFileStatus status(DataFile f) {
        Map<Column, Literal> min = new HashMap<>();
        Map<Column, Literal> max = new HashMap<>();
        Map<Column, Long> nulls = new HashMap<>();
        for (NestedField c : fileSchema.columns()) {
            if (!(c.type() instanceof Type.Primitive p) || c.name().equals("doc")) {
                continue;
            }
            Column col = new Column(c.name());
            Long n = f.nullValueCounts().get(c.id());
            if (n != null) {
                nulls.put(col, n);
            }
            byte[] lo = f.lowerBounds().get(c.id());
            byte[] hi = f.upperBounds().get(c.id());
            if (lo != null && hi != null) {
                min.put(col, literal(p, Values.fromBytes(p, lo)));
                max.put(col, literal(p, Values.fromBytes(p, hi)));
            }
        }
        DataFileStatistics stats = new DataFileStatistics(f.recordCount(), min, max, nulls, Optional.empty());
        return new DataFileStatus(f.path(), f.fileSizeInBytes(), System.currentTimeMillis(), Optional.of(stats));
    }

    private static Literal literal(Type.Primitive p, Object v) {
        return switch (p.id()) {
            case DOUBLE -> Literal.ofDouble(((Number) v).doubleValue());
            case LONG -> Literal.ofLong(((Number) v).longValue());
            case DATE -> Literal.ofDate(((Number) v).intValue());
            default -> Literal.ofString(v.toString());
        };
    }

    private static <T> CloseableIterator<T> iterator(List<T> list) {
        Iterator<T> it = list.iterator();
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

    @Override
    public Map<String, String> columnTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (StructField f : schema.fields()) {
            DataType t = f.getDataType();
            out.put(f.getName(), t instanceof ArrayType a ? (a.getElementType() instanceof StringType ? "list" : "vector") : t instanceof DoubleType ? "double" : t instanceof DateType ? "date"
                    : t instanceof LongType ? "long" : "string");
        }
        return out;
    }

    @Override
    public List<String> leftOut() {
        return leftOut;
    }

    @Override
    public void close() {
        io.close();
    }
}
