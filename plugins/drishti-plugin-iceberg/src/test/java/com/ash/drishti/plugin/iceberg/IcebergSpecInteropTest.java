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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.read.TaskReader;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.write.Operations;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Iceberg spec compliance, both directions, against the Apache Iceberg Java library (docs/guides/DATA_LAKE_GUIDE.md, "Iceberg spec
 * compliance"):
 *
 * <ul>
 *   <li>the library writes a table partitioned by identity, bucket, truncate, year, month, day and hour, with partition values of every
 *       special character class (space, /, %, an encoded-looking %2F, :, =, ., .., \, control characters, unicode, empty, null), at a
 *       {@code file:///} location as pyiceberg and the library name it; then position and equality deletes. drishti-iceberg reads every
 *       row the library reads, computes every partition value the library computed, and prunes to exactly the matching rows;</li>
 *   <li>Drishti writes a table (through iceberg-core, its FileIO and Parquet writer) with every special partition value; the library
 *       reads every row;</li>
 *   <li>drishti-iceberg expires snapshots of a library table holding a tag and a branch: the tag's and the branch's snapshots stay and
 *       the library still reads them;</li>
 *   <li>the library engine's lake at a root with a space, a % and unicode in its name is that folder (not java.nio's encoded spelling);</li>
 *   <li>Drishti's writer refuses a table another engine re-partitioned rather than write files whose other partition values read as null.</li>
 * </ul>
 */
class IcebergSpecInteropTest {

    /** Every special character class of the Delta interop test, as identity partition values. */
    static final List<String> VALUES = Arrays.asList("plain", "a b", "a/b", "100%", "a%2Fb", "a%20b", "a:b", "a=b", ".", "..", "a\\b", "x\u0001y",
            "tab\there", "new\nline", "quote\"s", "é漢字", "emoji 😀", "a+b", "a?b#c", "a*b", "~home", "x".repeat(200), "",
            "__HIVE_DEFAULT_PARTITION__", "null", null);

    static final Schema SCHEMA = new Schema(Types.NestedField.optional(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "g", Types.StringType.get()), Types.NestedField.optional(3, "s", Types.StringType.get()),
            Types.NestedField.optional(4, "d", Types.DateType.get()), Types.NestedField.optional(5, "ts", Types.TimestampType.withoutZone()),
            Types.NestedField.optional(6, "n", Types.DecimalType.of(9, 2)), Types.NestedField.optional(7, "m", Types.DateType.get()),
            Types.NestedField.optional(8, "h", Types.TimestampType.withZone()));

    static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA).identity("g").bucket("id", 4).truncate("s", 3).year("d").month("m")
            .day("ts").hour("h").truncate("n", 10).bucket("s", 5).build();

    static Path dir;
    static ExecutorService pool;
    static Table lib;

    @BeforeAll
    static void write(@TempDir Path tmp) throws Exception {
        dir = tmp;
        pool = Executors.newVirtualThreadPerTaskExecutor();
        String location = "file://" + tmp.resolve("wh/ns/special").toString();   // file:///…, the form pyiceberg and the library write
        lib = new HadoopTables(new Configuration()).create(SCHEMA, SPEC, Map.of("format-version", "2"), location);
        Map<PartitionKey, List<Record>> byPartition = new LinkedHashMap<>();
        InternalRecordWrapper wrap = new InternalRecordWrapper(SCHEMA.asStruct());
        for (int i = 0; i < VALUES.size(); i++) {
            GenericRecord r = GenericRecord.create(SCHEMA);
            r.setField("id", (long) i * 7919 - 50_000);
            r.setField("g", VALUES.get(i));
            r.setField("s", VALUES.get(i));
            r.setField("d", LocalDate.of(1969, 12, 25).plusDays(i * 37L));
            r.setField("ts", LocalDateTime.of(1969, 12, 31, 22, 30).plus(i * 5L, ChronoUnit.HOURS));
            r.setField("n", new BigDecimal(i * 13 - 150).movePointLeft(2));
            r.setField("m", LocalDate.of(1969, 11, 3).plusDays(i * 11L));
            r.setField("h", java.time.OffsetDateTime.of(1969, 12, 31, 21, 59, 59, 0, java.time.ZoneOffset.UTC).plusMinutes(i * 47L));
            PartitionKey key = new PartitionKey(SPEC, SCHEMA);
            key.partition(wrap.wrap(r));
            byPartition.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        var append = lib.newAppend();
        int n = 0;
        for (var e : byPartition.entrySet()) {
            String path = lib.locationProvider().newDataLocation(SPEC, e.getKey(), "part-" + n++ + ".parquet");
            DataWriter<Record> w = Parquet.writeData(lib.io().newOutputFile(path)).forTable(lib).withSpec(SPEC).withPartition(e.getKey())
                    .createWriterFunc(GenericParquetWriter::create).overwrite().build();
            try (w) {
                e.getValue().forEach(w::write);
            }
            append.appendFile(w.toDataFile());
        }
        append.commit();
    }

    @AfterAll
    static void close() {
        pool.close();
    }

    static String metadataFile(Table t) {
        return ((HasTableOperations) t).operations().current().metadataFileLocation();
    }

    /** The rows the library reads, as "id|g". */
    static TreeSet<String> libraryRows(Table t) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        try (CloseableIterable<Record> rows = IcebergGenerics.read(t).build()) {
            rows.forEach(r -> out.add(r.getField("id") + "|" + (r.getField("g") == null ? "<null>" : r.getField("g"))));
        }
        return out;
    }

    /** The rows drishti-iceberg reads with {@code filter}, as "id|g"; each file's partition tuple checked against Drishti's transforms. */
    static TreeSet<String> drishtiRows(Table t, Expr filter) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        try (Catalog c = Catalogs.open(Map.of("metadata-location.special", metadataFile(t)))) {
            com.ash.drishti.iceberg.Table d = c.load("special").orElseThrow();
            var schema = d.metadata().schema();
            List<Integer> ids = schema.columns().stream().map(f -> f.id()).toList();
            for (FileTask task : d.plan(d.currentSnapshot().orElseThrow(), filter, pool)) {
                try (TaskReader r = d.rows(task, schema, ids, filter, false)) {
                    while (r.hasNext()) {
                        Object[] row = r.next();
                        if (!Expr.TRUE.equals(filter) && !Objects.equals(row[1], ((Expr.Pred) filter).values().isEmpty() ? null : ((Expr.Pred) filter).value())) {
                            continue;                                    // pruning is by file: rows of other values in a kept file are fine
                        }
                        out.add(row[0] + "|" + (row[1] == null ? "<null>" : row[1]));
                        for (int i = 0; i < task.spec().fields().size(); i++) {
                            var f = task.spec().fields().get(i);
                            Object want = f.transform().apply(task.spec().sourceType(schema, i), row[schema.columns().indexOf(schema.field(f.sourceId()))]);
                            Object got = task.file().partition()[i];
                            assertThat(same(want, got)).as("%s of %s: Drishti %s, the library %s", f.transform(), row[1], want, got).isTrue();
                        }
                    }
                }
            }
        }
        return out;
    }

    static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y && !(a instanceof BigDecimal) && !(b instanceof BigDecimal)) {
            return x.longValue() == y.longValue();
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    @Test
    void theLibrarysSpecialPartitionValuesAndTransformsReadAsTheLibraryReadsThem() throws Exception {
        List<String> paths = new ArrayList<>();
        lib.newScan().planFiles().forEach(f -> paths.add(f.file().location()));
        assertThat(paths).anyMatch(p -> p.contains("g=a%2Fb/")).anyMatch(p -> p.contains("g=100%25/")).anyMatch(p -> p.contains("g=a%252Fb/"));
        TreeSet<String> want = libraryRows(lib);
        assertThat(want).hasSize(VALUES.size());
        assertThat(drishtiRows(lib, Expr.TRUE)).containsExactlyElementsOf(want);
        for (String v : VALUES) {
            Expr f = v == null ? new Expr.Pred(2, Expr.Op.IS_NULL, List.of()) : Expr.eq(2, v);
            assertThat(drishtiRows(lib, f)).as("g = %s", v).containsExactly((VALUES.indexOf(v) * 7919L - 50_000) + "|" + (v == null ? "<null>" : v));
        }
    }

    @Test
    void theLibrarysPositionAndEqualityDeletesInSpecialPartitionsApply() throws Exception {
        Table t = new HadoopTables(new Configuration()).create(SCHEMA, PartitionSpec.builderFor(SCHEMA).identity("g").build(),
                Map.of("format-version", "2"), "file://" + dir.resolve("wh/ns/deletes"));
        var spec = t.spec();
        var append = t.newAppend();
        for (String v : Arrays.asList("a/b", "100%", "..", "null", null)) {
            PartitionKey key = new PartitionKey(spec, SCHEMA);
            GenericRecord first = GenericRecord.create(SCHEMA);
            first.setField("g", v);
            key.partition(first);
            DataWriter<Record> w = Parquet.writeData(t.io().newOutputFile(t.locationProvider().newDataLocation(spec, key, "d-" + UUID.randomUUID() + ".parquet"))).forTable(t)
                    .withSpec(spec).withPartition(key).createWriterFunc(GenericParquetWriter::create).overwrite().build();
            try (w) {
                for (long id = 0; id < 3; id++) {
                    GenericRecord r = GenericRecord.create(SCHEMA);
                    r.setField("id", id);
                    r.setField("g", v);
                    w.write(r);
                }
            }
            append.appendFile(w.toDataFile());
        }
        append.commit();
        Map<String, DataFile> files = new HashMap<>();
        try (CloseableIterable<FileScanTask> tasks = t.newScan().planFiles()) {
            tasks.forEach(f -> files.put(String.valueOf(f.file().partition().get(0, String.class)), f.file()));
        }
        // position delete: row 1 of the a/b file
        PartitionKey ab = new PartitionKey(spec, SCHEMA);
        GenericRecord abRow = GenericRecord.create(SCHEMA);
        abRow.setField("g", "a/b");
        ab.partition(abRow);
        PositionDeleteWriter<Record> pw = Parquet.writeDeletes(t.io().newOutputFile(t.locationProvider().newDataLocation(spec, ab, "pos.parquet")))
                .forTable(t).rowSchema(null).withSpec(spec).withPartition(ab).overwrite().buildPositionWriter();
        try (pw) {
            pw.write(PositionDelete.<Record>create().set(files.get("a/b").location(), 1L, null));
        }
        // equality delete: id 2 in the null partition only (the text "null" partition beside it keeps its id 2, and so does "..")
        PartitionKey dots = new PartitionKey(spec, SCHEMA);
        GenericRecord dotsRow = GenericRecord.create(SCHEMA);
        dotsRow.setField("g", null);
        dots.partition(dotsRow);
        Schema eq = new Schema(SCHEMA.findField("id"));
        EqualityDeleteWriter<Record> ew = Parquet.writeDeletes(t.io().newOutputFile(t.locationProvider().newDataLocation(spec, dots, "eq.parquet")))
                .forTable(t).withSpec(spec).withPartition(dots).rowSchema(eq).equalityFieldIds(1).createWriterFunc(GenericParquetWriter::create)
                .overwrite().buildEqualityWriter();
        GenericRecord gone = GenericRecord.create(eq);
        gone.setField("id", 2L);
        try (ew) {
            ew.write(gone);
        }
        t.newRowDelta().addDeletes(pw.toDeleteFile()).addDeletes(ew.toDeleteFile()).commit();
        TreeSet<String> want = libraryRows(t);
        assertThat(want).hasSize(13).doesNotContain("1|a/b", "2|<null>").contains("2|null", "2|..", "1|100%", "1|<null>");
        TreeSet<String> got = new TreeSet<>();
        try (Catalog c = Catalogs.open(Map.of("metadata-location.deletes", metadataFile(t)))) {
            com.ash.drishti.iceberg.Table d = c.load("deletes").orElseThrow();
            var schema = d.metadata().schema();
            for (FileTask task : d.plan(d.currentSnapshot().orElseThrow(), Expr.TRUE, pool)) {
                try (TaskReader r = d.rows(task, schema, List.of(1, 2), Expr.TRUE, false)) {
                    while (r.hasNext()) {
                        Object[] row = r.next();
                        got.add(row[0] + "|" + (row[1] == null ? "<null>" : row[1]));
                    }
                }
            }
        }
        assertThat(got).containsExactlyElementsOf(want);
    }

    /** The other direction: Drishti writes through iceberg-core with its own FileIO and Parquet writer; the Apache library reads every partition. */
    @Test
    void drishtisTableWithEverySpecialPartitionValueReadsInTheLibrary(@TempDir Path root) throws Exception {
        var cols = List.of(com.ash.drishti.iceberg.types.NestedField.optional(1, "id", com.ash.drishti.iceberg.types.Type.Primitive.LONG),
                com.ash.drishti.iceberg.types.NestedField.optional(2, "g", com.ash.drishti.iceberg.types.Type.Primitive.STRING));
        var schema = new com.ash.drishti.iceberg.types.Schema(0, cols);
        var spec = new com.ash.drishti.iceberg.meta.PartitionSpec(0, List.of(new com.ash.drishti.iceberg.meta.PartitionSpec.Field(2, 1000, "g",
                com.ash.drishti.iceberg.meta.Transform.Identity.INSTANCE)));
        try (var c = com.ash.drishti.iceberg.catalog.Catalogs.open(Map.of("root", root.toString(), "domain", "x"))) {
            var t = c.create("t", schema, spec, com.ash.drishti.iceberg.meta.SortOrder.UNSORTED, Map.of("format-version", "2"));
            var ice = t.iceberg();
            List<com.ash.drishti.iceberg.manifest.DataFile> files = new java.util.ArrayList<>();
            for (int i = 0; i < VALUES.size(); i++) {
                String v = VALUES.get(i);
                PartitionKey key = new PartitionKey(ice.spec(), ice.schema());
                key.set(0, v);
                String path = ice.locationProvider().newDataLocation(ice.spec(), key, "f" + i + ".parquet");
                try (var w = new com.ash.drishti.iceberg.write.ParquetDataWriter(t.io(), path, t.metadata().schema(), t.metadata().properties(), 0,
                        new Object[] {v}, 0)) {
                    w.write(new Object[] {(long) i, v});
                    files.add(w.finish());
                }
            }
            com.ash.drishti.iceberg.write.Operations.append(t, files, Map.of());
            java.util.Set<String> want = new java.util.HashSet<>();
            for (int i = 0; i < VALUES.size(); i++) {
                want.add(i + "|" + VALUES.get(i));
            }
            Table lib = new HadoopTables(new Configuration()).load(t.metadata().location());
            java.util.Set<String> got = new java.util.HashSet<>();
            try (CloseableIterable<Record> rows = IcebergGenerics.read(lib).build()) {
                rows.forEach(r -> got.add(r.getField("id") + "|" + r.getField("g")));
            }
            assertThat(got).isEqualTo(want);
        }
    }

    @Test
    void expiringSnapshotsKeepsTheTagsAndBranchesTheLibraryKeeps() throws Exception {
        Path root = dir.resolve("refs");
        String location = root.resolve("ns/tagged").toString();
        HadoopTables tables = new HadoopTables(new Configuration());
        Schema s = new Schema(Types.NestedField.optional(1, "id", Types.LongType.get()));
        Table t = tables.create(s, PartitionSpec.unpartitioned(), Map.of("format-version", "2"), location);
        List<Long> snapshots = new ArrayList<>();
        for (long i = 0; i < 4; i++) {
            DataWriter<Record> w = Parquet.writeData(t.io().newOutputFile(location + "/data/f" + i + ".parquet")).forTable(t)
                    .withSpec(PartitionSpec.unpartitioned()).createWriterFunc(GenericParquetWriter::create).overwrite().build();
            GenericRecord r = GenericRecord.create(s);
            r.setField("id", i);
            try (w) {
                w.write(r);
            }
            t.newAppend().appendFile(w.toDataFile()).commit();
            snapshots.add(t.currentSnapshot().snapshotId());
        }
        t.manageSnapshots().createTag("eod", snapshots.get(0)).createBranch("audit", snapshots.get(1)).commit();
        Thread.sleep(5);
        try (Catalog c = Catalogs.open(Map.of("root", root.toString(), "domain", "ns"))) {
            com.ash.drishti.iceberg.Table d = c.load("tagged").orElseThrow();
            assertThat(d.metadata().allRefs()).containsKeys("main", "eod", "audit");
            assertThat(Operations.expireSnapshots(d, System.currentTimeMillis(), 1)).isEqualTo(1);    // only the untagged third
        }
        Table after = tables.load(location);
        assertThat(after.refs()).containsKeys("main", "eod", "audit");
        assertThat(after.snapshot(snapshots.get(0))).isNotNull();
        assertThat(after.snapshot(snapshots.get(2))).isNull();
        try (CloseableIterable<Record> rows = IcebergGenerics.read(after).useSnapshot(snapshots.get(0)).build()) {
            List<Object> ids = new ArrayList<>();
            rows.forEach(r -> ids.add(r.getField("id")));
            assertThat(ids).containsExactly(0L);
        }
        assertThat(libraryRows(after)).containsExactly("0|<null>", "1|<null>", "2|<null>", "3|<null>");
    }

    @Test
    void theLibraryEnginesLakeIsTheFolderItsRootNames() throws Exception {
        Path root = dir.resolve("my lake 100% é");
        OracleLake lake = OracleLake.of(Map.of("root", root.toString(), "domain", "desk"));
        OracleLayout.open(lake, "trades", Map.of("mtm", true), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 1000);
        assertThat(root.resolve("desk/trades/metadata")).isDirectory();
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("my")).toList()).containsExactly("my lake 100% é");
        }
        try (Catalog c = Catalogs.open(Map.of("root", root.toString(), "domain", "desk"))) {
            assertThat(c.load("trades")).isPresent();                     // the native engine finds the same table
        }
    }

    @Test
    void drishtisWriterRefusesATableAnotherEngineRepartitioned() throws Exception {
        Path root = dir.resolve("repart");
        OracleLake lake = OracleLake.of(Map.of("root", root.toString(), "domain", "desk"));
        Table t = OracleLayout.open(lake, "trades", Map.of("mtm", true), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 1000);
        t.updateSpec().addField(org.apache.iceberg.expressions.Expressions.bucket(IcebergLayout.ID, 8)).commit();
        try (Catalog c = Catalogs.open(Map.of("root", root.toString(), "domain", "desk"))) {
            com.ash.drishti.iceberg.Table d = c.load("trades").orElseThrow();
            assertThatThrownBy(() -> NativeLayout.writeDay(d, LocalDate.of(2026, 11, 6), Collections.emptyIterator(), 1000))
                    .hasMessageContaining("bucket[8]").hasMessageContaining("partitioned by business_date alone");
        }
    }
}
