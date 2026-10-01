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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.parquet.Parquet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tables written by {@link IcebergLoader} from JSON lines in random order (40 trades a day over two days, files of 15
 * rows, row groups of a few rows, a sort that spills every row): the layout is what the connector reads fastest, the
 * fast paths answer exactly what the documents say, delete files are honoured everywhere, time travel reads the table
 * as it was, and maintenance keeps it in shape.
 */
class IcebergLayoutTest {

    static final LocalDate D1 = LocalDate.of(2026, 9, 29);
    static final LocalDate D2 = LocalDate.of(2026, 9, 30);
    static final List<String> PROMOTED = List.of("mtm", "book", "nettingSet", "counterparty.id");
    static final ObjectMapper JSON = new ObjectMapper();

    Path root;

    static String doc(int i, LocalDate d) {
        ObjectNode n = JSON.createObjectNode();
        n.put("tradeId", String.format("T-%03d", i));
        if (i != 7) {
            n.put("mtm", (d.equals(D1) ? -20_000 : -20_100) + 1_000 * i);       // T-007 has none
        }
        n.put("book", i % 2 == 0 ? "BOOK-A" : "BOOK-B");
        n.put("nettingSet", "NS-" + (i % 3));
        n.putObject("counterparty").put("id", "CP-" + (i % 4)).put("name", "Party " + (i % 4));
        return n.toString();
    }

    static String line(int i, LocalDate d, String doc) throws IOException {
        ObjectNode l = JSON.createObjectNode();
        l.put("domain", "desk").put("kind", "trade").put("id", String.format("T-%03d", i)).put("date", d.toString()).put("doc", doc);
        var parsed = JSON.readTree(doc);
        ObjectNode cols = l.putObject("columns");
        if (parsed.has("mtm")) {
            cols.put("mtm", parsed.get("mtm").asDouble());
        } else {
            cols.putNull("mtm");
        }
        cols.put("book", parsed.get("book").asText()).put("nettingSet", parsed.get("nettingSet").asText())
                .put("counterparty.id", parsed.get("counterparty").get("id").asText());
        return l.toString();
    }

    void load(List<String> lines) throws Exception {
        Path f = Files.createTempFile(root, "rows", ".jsonl");
        Files.write(f, lines);
        IcebergLoader.main(new String[] {f.toString(), root.toString(), "--file-rows", "15", "--row-group-mb", "0.0001", "--buffer-mb", "0",
            "--threads", "3", "--spill-dir", root.resolve("spill").toString()});
    }

    @BeforeEach
    void write() throws Exception {
        root = Files.createTempDirectory("drishti-iceberg-layout");
        List<String> lines = new ArrayList<>();
        for (LocalDate d : List.of(D1, D2)) {
            for (int i = 1; i <= 40; i++) {
                lines.add(line(i, d, doc(i, d)));
            }
        }
        lines.add("{\"domain\":\"desk\",\"kind\":\"counterparty\",\"id\":\"CP-1\",\"date\":\"2026-09-30\",\"doc\":\"{\\\"id\\\":\\\"CP-1\\\"}\"}");
        Collections.shuffle(lines, new Random(7));
        load(lines);
    }

    IcebergSourcePlugin plugin(boolean laidOut, String... extra) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "desk", "source-name", "lake"));
        if (laidOut) {
            s.put("layout.trade.columns", String.join(",", PROMOTED) + (extra.length > 0 ? "," + String.join(",", extra) : ""));
        }
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    Table table() {
        return IcebergLake.of(Map.of("root", root.toString(), "domain", "desk")).load("trade").orElseThrow();
    }

    @Test
    void eachDayIsSortedIntoFilesOfDisjointIdRangesWithSmallRowGroupsAndNoDocumentMetrics() throws Exception {
        Table t = table();
        Map<LocalDate, List<DataFile>> byDay = new HashMap<>();
        try (CloseableIterable<FileScanTask> tasks = t.newScan().includeColumnStats().planFiles()) {
            for (FileScanTask task : tasks) {
                byDay.computeIfAbsent(LocalDate.ofEpochDay(task.file().partition().get(0, Integer.class)), k -> new ArrayList<>()).add(task.file());
            }
        }
        assertThat(byDay).containsOnlyKeys(D1, D2);
        for (List<DataFile> files : byDay.values()) {
            assertThat(files).hasSize(3);                                       // 40 rows in files of 15
            assertThat(files.stream().mapToLong(DataFile::recordCount).sum()).isEqualTo(40);
            files.sort(java.util.Comparator.comparing(f -> lower(f)));
            for (int i = 1; i < files.size(); i++) {
                assertThat(lower(files.get(i))).isGreaterThan(upper(files.get(i - 1)));
            }
            for (DataFile f : files) {
                assertThat(f.lowerBounds()).doesNotContainKey(2);                // no bounds of doc in the manifests
            }
            long groups = 0;
            for (DataFile f : files) {
                groups += rowGroups(f).size();
            }
            assertThat(groups).isGreaterThan(files.size());                     // small row groups: several a file
        }
        assertThat(t.sortOrder().fields()).hasSize(1);
        assertThat(t.schema().findField("counterparty__id")).isNotNull();
        assertThat(t.schema().findField("mtm").type().typeId()).isEqualTo(org.apache.iceberg.types.Type.TypeID.DOUBLE);
        try (var left = Files.walk(root.resolve("spill"))) {
            assertThat(left.filter(Files::isRegularFile)).isEmpty();               // the sort spilled, and its run files are gone
        }
    }

    static List<org.apache.parquet.hadoop.metadata.BlockMetaData> rowGroups(DataFile f) throws IOException {
        try (var reader = org.apache.parquet.hadoop.ParquetFileReader.open(org.apache.parquet.hadoop.util.HadoopInputFile.fromPath(
                new org.apache.hadoop.fs.Path(f.location()), new org.apache.hadoop.conf.Configuration()))) {
            return reader.getRowGroups();
        }
    }

    static String lower(DataFile f) {
        return org.apache.iceberg.types.Conversions.fromByteBuffer(org.apache.iceberg.types.Types.StringType.get(), f.lowerBounds().get(1)).toString();
    }

    static String upper(DataFile f) {
        return org.apache.iceberg.types.Conversions.fromByteBuffer(org.apache.iceberg.types.Types.StringType.get(), f.upperBounds().get(1)).toString();
    }

    @Test
    void oneEntityIsReadFromItsFileOnEachDate() throws Exception {
        IcebergSourcePlugin p = plugin(true);
        assertThat(p.fetch(EntityRef.of("trade", "T-033")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(12_900);
        assertThat(p.fetch(EntityRef.of("trade", "T-033"), AsOf.of(D1)).orElseThrow().data().get("mtm").asDouble()).isEqualTo(13_000);
        assertThat(p.fetch(EntityRef.of("trade", "T-001")).orElseThrow().data().get("counterparty").get("name").asText()).isEqualTo("Party 1");
        assertThat(p.fetch(EntityRef.of("trade", "T-999"))).isEmpty();
        assertThat(p.search("trade", "T-03", 50)).hasSize(10);                 // ids from the id column alone
        assertThat(p.search("counterparty", "cp", 5)).hasSize(1);
        assertThat(p.cacheStats()).containsEntry("partitions", 0L);           // no whole day was loaded
    }

    @Test
    void columnsAnswerWhatTheDocumentsSay() throws Exception {
        IcebergSourcePlugin p = plugin(true);
        assertThat(p.columnar("trade")).containsExactlyInAnyOrderElementsOf(PROMOTED);
        ColumnSet c = p.columns("trade", List.of("mtm", "book", "counterparty.id"), AsOf.LATEST).orElseThrow();
        assertThat(c.size()).isEqualTo(40);
        assertThat(c.businessDate()).isEqualTo(D2);
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            if (doc.get("mtm").isMissing()) {
                assertThat(c.value("mtm", i)).isNull();
            } else {
                assertThat((Double) c.value("mtm", i)).isEqualTo(doc.get("mtm").asDouble());
            }
            assertThat(c.value("book", i)).isEqualTo(doc.get("book").asText());
            assertThat(c.value("counterparty.id", i)).isEqualTo(doc.get("counterparty").get("id").asText());
        }
        assertThat(p.columns("trade", List.of("notional"), AsOf.LATEST)).isEmpty();
    }

    @Test
    void reverseLookupsFromColumnsMatchTheDocuments() throws Exception {
        IcebergSourcePlugin laidOut = plugin(true);
        IcebergSourcePlugin documents = plugin(false);
        for (String target : List.of("NS-2", "BOOK-A", "CP-1")) {
            List<EntityRef> fast = laidOut.reverse(EntityRef.of("x", target), "trade", AsOf.LATEST);
            assertThat(fast).isNotEmpty().containsExactlyInAnyOrderElementsOf(documents.reverse(EntityRef.of("x", target), "trade", AsOf.LATEST));
        }
        assertThat(laidOut.reverse(EntityRef.of("netting-set", "NS-2"), "trade", AsOf.LATEST)).hasSize(13);
        assertThat(documents.columnar("trade")).isEmpty();
    }

    @Test
    void healthSaysWhenATableIsNotLaidOutAsDeclared() throws Exception {
        assertThat(plugin(true).health()).isEqualTo("UP");
        assertThat(plugin(true, "notional").health()).contains("not laid out as the pack declares: trade (4 of 5 columns)");
    }

    @Test
    void deleteFilesAreHonouredByEveryReadAndRelayoutFoldsThemIn() throws Exception {
        Table t = table();
        deletePosition(t, D2, "T-005");
        deleteEquality(t, D2, "T-010");
        IcebergSourcePlugin p = plugin(true);
        assertThat(p.fetch(EntityRef.of("trade", "T-005"))).isEmpty();
        assertThat(p.fetch(EntityRef.of("trade", "T-010"))).isEmpty();
        assertThat(p.fetch(EntityRef.of("trade", "T-006"))).isPresent();
        assertThat(p.fetch(EntityRef.of("trade", "T-005"), AsOf.of(D1))).isPresent();     // only the newest day lost them
        ColumnSet c = p.columns("trade", List.of("mtm"), AsOf.LATEST).orElseThrow();
        assertThat(c.size()).isEqualTo(38);
        assertThat(c.ids()).doesNotContain("T-005", "T-010");
        assertThat(p.search("trade", "T-005", 5)).isEmpty();
        assertThat(p.reverse(EntityRef.of("x", "NS-1"), "trade", AsOf.LATEST)).doesNotContain(EntityRef.of("trade", "T-010"));
        assertThat(p.cacheStats()).containsEntry("daysWithDeletes", 1L);

        assertThat(IcebergMaintenance.relayout(t, false, root.resolve("spill"), 1 << 20)).isEqualTo(1);   // only the day with deletes
        IcebergSourcePlugin after = plugin(true);
        assertThat(after.cacheStats()).containsEntry("daysWithDeletes", 0L);
        assertThat(after.columns("trade", List.of("mtm"), AsOf.LATEST).orElseThrow().size()).isEqualTo(38);
        assertThat(after.fetch(EntityRef.of("trade", "T-005"))).isEmpty();
        assertThat(after.fetch(EntityRef.of("trade", "T-033")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(12_900);
        assertThat(IcebergMaintenance.relayout(t, false, root.resolve("spill"), 1 << 20)).isZero();      // in shape now
    }

    @Test
    void anAppendOutOfOrderIsDriftAndAMissingColumnIsFilledFromTheDocuments() throws Exception {
        Table t = table();
        // another writer appends two trades to the newest day as one small file: its ids overlap the day's files
        IcebergLayout.Row r1 = new IcebergLayout.Row("T-0105", doc(105, D2), Map.of("mtm", 1.0, "book", "BOOK-A", "nettingSet", "NS-0"));
        IcebergLayout.Row r2 = new IcebergLayout.Row("T-0205", doc(205, D2), Map.of("mtm", 2.0, "book", "BOOK-B", "nettingSet", "NS-1"));
        var append = t.newAppend();
        IcebergLayout.writeDay(t, D2, List.of(r1, r2).iterator(), 15).forEach(append::appendFile);
        append.commit();
        IcebergSourcePlugin p = plugin(true);
        assertThat(p.fetch(EntityRef.of("trade", "T-0105"))).isPresent();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.LATEST).orElseThrow().size()).isEqualTo(42);
        assertThat(IcebergMaintenance.relayout(t, false, root.resolve("spill"), 1 << 20)).isEqualTo(1);
        t.refresh();
        try (CloseableIterable<FileScanTask> tasks = t.newScan().filter(Expressions.equal("business_date", D2.toString())).planFiles()) {
            assertThat(tasks).hasSize(3);                                       // 42 rows: three files again
        }
        // a field promoted later: the column is added and filled from each document
        IcebergMaintenance.main(new String[] {root.toString(), "--domain", "desk", "--kinds", "trade", "--columns", "trade:counterparty.name",
            "--expire-hours", "-1", "--spill-dir", root.resolve("spill").toString()});
        IcebergSourcePlugin later = plugin(true, "counterparty.name");
        assertThat(later.health()).isEqualTo("UP");
        ColumnSet c = later.columns("trade", List.of("counterparty.name"), AsOf.of(D1)).orElseThrow();
        assertThat(c.texts().get("counterparty.name")).contains("Party 1").doesNotContainNull();
    }

    @Test
    void knownAtReadsTheTableAsItWasAndReloadingADayReplacesIt() throws Exception {
        Instant before = Instant.now();
        Thread.sleep(20);
        ObjectNode restated = (ObjectNode) JSON.readTree(doc(1, D2));
        restated.put("mtm", 555).put("restated", true);
        load(List.of(line(1, D2, restated.toString()), line(2, D2, doc(2, D2))));          // the day reloaded: now two trades
        IcebergSourcePlugin p = plugin(true);
        assertThat(p.fetch(EntityRef.of("trade", "T-001")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(555);
        assertThat(p.fetch(EntityRef.of("trade", "T-003"))).isEmpty();          // gone from the reloaded day
        AsOf then = new AsOf(D2, before);
        assertThat(p.fetch(EntityRef.of("trade", "T-001"), then).orElseThrow().data().get("mtm").asDouble()).isEqualTo(-19_100);
        assertThat(p.fetch(EntityRef.of("trade", "T-003"), then)).isPresent();
        assertThat(p.columns("trade", List.of("mtm"), then).orElseThrow().size()).isEqualTo(40);
        assertThat(p.fetch(EntityRef.of("trade", "T-001"), new AsOf(D2, Instant.parse("2000-01-01T00:00:00Z")))).isEmpty();   // nothing known then
    }

    @Test
    void retentionRemovesOldDaysAndExpiresTheirSnapshots() throws Exception {
        Table t = table();
        assertThat(IcebergMaintenance.keepDays(t, 1)).isEqualTo(1);
        IcebergMaintenance.rewriteManifests(t);
        IcebergMaintenance.expireSnapshots(t, 0);
        IcebergSourcePlugin p = plugin(true);
        assertThat(p.fetch(EntityRef.of("trade", "T-001"), AsOf.of(D1))).isEmpty();
        assertThat(p.fetch(EntityRef.of("trade", "T-001"))).isPresent();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D1))).isEmpty();  // not held: the next store is asked
        t.refresh();
        assertThat(t.snapshots()).hasSize(1);
    }

    @Test
    void loadingTheSameRowsAgainChangesNothing() throws Exception {
        long before = rows(table());
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            lines.add(line(i, D1, doc(i, D1)));
        }
        load(lines);
        assertThat(rows(table())).isEqualTo(before);
    }

    static long rows(Table t) throws IOException {
        long n = 0;
        try (CloseableIterable<FileScanTask> tasks = t.newScan().planFiles()) {
            for (FileScanTask task : tasks) {
                n += task.file().recordCount();
            }
        }
        return n;
    }

    /** A position delete (what Spark's merge-on-read DELETE writes) of one row of the day. */
    static void deletePosition(Table t, LocalDate day, String id) throws IOException {
        try (CloseableIterable<FileScanTask> tasks = t.newScan().filter(Expressions.equal("business_date", day.toString())).planFiles()) {
            for (FileScanTask task : tasks) {
                long pos = 0;
                try (CloseableIterable<Record> rows = IcebergTable.open(t.io(), t.schema(), task, new Schema(t.schema().findField("id")),
                        Expressions.alwaysTrue())) {
                    for (Record r : rows) {
                        if (id.equals(r.get(0))) {
                            PositionDeleteWriter<Record> w = Parquet.writeDeletes(t.io().newOutputFile(
                                            t.locationProvider().newDataLocation(t.spec(), task.file().partition(), "pos-" + id + ".parquet")))
                                    .forTable(t).rowSchema(null).withPartition(task.file().partition()).buildPositionWriter();
                            try (w) {
                                w.write(PositionDelete.<Record>create().set(task.file().location(), pos, null));
                            }
                            t.newRowDelta().addDeletes(w.toDeleteFile()).commit();
                            return;
                        }
                        pos++;
                    }
                }
            }
        }
        throw new IllegalArgumentException(id);
    }

    /** An equality delete on id (what Flink upserts and Trino row-level deletes may write). */
    static void deleteEquality(Table t, LocalDate day, String id) throws IOException {
        Schema eq = new Schema(t.schema().findField("id"));
        EqualityDeleteWriter<Record> w = Parquet.writeDeletes(t.io().newOutputFile(
                        t.locationProvider().newDataLocation(t.spec(), IcebergLayout.partition(t.spec(), day), "eq-" + id + ".parquet")))
                .forTable(t).withPartition(IcebergLayout.partition(t.spec(), day)).rowSchema(eq).equalityFieldIds(1)
                .createWriterFunc(GenericParquetWriter::create).buildEqualityWriter();
        GenericRecord r = GenericRecord.create(eq);
        r.setField("id", id);
        try (w) {
            w.write(r);
        }
        DeleteFile f = w.toDeleteFile();
        t.newRowDelta().addDeletes(f).commit();
    }
}
