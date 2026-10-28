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
package com.ash.drishti.plugin.icebergext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Schema;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.parquet.Parquet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The external-table connector over the shapes other engines write (ICEBERG_EXTERNAL_CONNECTOR.md section 12), from the
 * pyiceberg fixtures: a Spark-style trade table (partitioned by the day of a timestamp, not sorted by id, with a
 * merge-on-read position delete added by the Iceberg library), a Snowflake-style counterparty table (identifier fields,
 * nested structs, a map, decimals, timestamptz, an equality delete), a current-only reference table, and a table renamed
 * and evolved. For each: one entity, a search on a column, type-ahead, a reverse link, time travel, and the document's
 * JSON compared with pyiceberg's rows.
 */
class IcebergExternalPluginTest {

    static final com.ash.drishti.common.JsonCodec CODEC = new com.ash.drishti.common.JsonCodec();
    static Path lake;
    static ExecutorService pool;
    static Instant beforeDeletes;
    static final LocalDate D28 = LocalDate.of(2026, 9, 28);
    static final LocalDate D29 = LocalDate.of(2026, 9, 29);
    static final LocalDate D30 = LocalDate.of(2026, 9, 30);

    @BeforeAll
    static void lake() throws Exception {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        lake = ExternalFixtures.temp("drishti-iceberg-external");
        ExternalFixtures.copy("spark_trades", lake, "mart", "spark_trades", pool);
        ExternalFixtures.copy("snowflake_counterparties", lake, "refdata", "counterparties", pool);
        ExternalFixtures.copy("ref_currencies", lake, "refdata", "currencies", pool);
        ExternalFixtures.copy("t_day", lake, "refdata", "no_id_table", pool);
        ExternalFixtures.copy("spark_trades", lake, "mart", "unsorted", pool, 4);
        Thread.sleep(20);
        beforeDeletes = Instant.now();
        Thread.sleep(20);
        HadoopTables tables = new HadoopTables(new org.apache.hadoop.conf.Configuration());
        org.apache.iceberg.Table trades = tables.load(lake.resolve("mart/spark_trades").toUri().toString());
        deletePosition(trades, "T-007", D30);
        org.apache.iceberg.Table cps = tables.load(lake.resolve("refdata/counterparties").toUri().toString());
        deleteEquality(cps, "CP-2", D30);
    }

    @AfterAll
    static void done() {
        pool.close();
        ExternalFixtures.delete(lake);
    }

    /** Spark's merge-on-read DELETE: a position delete of one row of the day. */
    static void deletePosition(org.apache.iceberg.Table t, String tradeId, LocalDate day) throws Exception {
        try (CloseableIterable<FileScanTask> tasks = t.newScan().planFiles()) {
            for (FileScanTask task : tasks) {
                long pos = 0;
                try (CloseableIterable<Record> rows = Parquet.read(t.io().newInputFile(task.file().location()))
                        .project(new Schema(t.schema().findField("TRADE_ID"), t.schema().findField("EVENT_TS")))
                        .createReaderFunc(s -> org.apache.iceberg.data.parquet.GenericParquetReaders.buildReader(
                                new Schema(t.schema().findField("TRADE_ID"), t.schema().findField("EVENT_TS")), s))
                        .build()) {
                    for (Record r : rows) {
                        java.time.LocalDateTime ts = (java.time.LocalDateTime) r.get(1);
                        if (tradeId.equals(r.get(0)) && ts.toLocalDate().equals(day)) {
                            PositionDeleteWriter<Record> w = Parquet.writeDeletes(t.io().newOutputFile(
                                            t.locationProvider().newDataLocation(t.spec(), task.file().partition(), "pos-" + tradeId + ".parquet")))
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
        throw new IllegalArgumentException(tradeId);
    }

    /** Flink's upsert: an equality delete on CPTY_ID in one day's partition. */
    static void deleteEquality(org.apache.iceberg.Table t, String id, LocalDate day) throws Exception {
        Schema eq = new Schema(t.schema().findField("CPTY_ID"));
        org.apache.iceberg.PartitionData p = new org.apache.iceberg.PartitionData(t.spec().partitionType());
        p.set(0, (int) day.toEpochDay());
        EqualityDeleteWriter<Record> w = Parquet.writeDeletes(t.io().newOutputFile(t.locationProvider().newDataLocation(t.spec(), p, "eq-" + id + ".parquet")))
                .forTable(t).withPartition(p).rowSchema(eq).equalityFieldIds(t.schema().findField("CPTY_ID").fieldId())
                .createWriterFunc(GenericParquetWriter::create).buildEqualityWriter();
        GenericRecord r = GenericRecord.create(eq);
        r.setField("CPTY_ID", id);
        try (w) {
            w.write(r);
        }
        t.newRowDelta().addDeletes(w.toDeleteFile()).commit();
    }

    static IcebergExternalPlugin plugin(Map<String, String> more) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", lake.toString(), "engine", "native", "decimals", "string"));
        s.put("tables.trade.table", "mart.spark_trades");
        s.put("tables.trade.id", "SOURCE_SYSTEM,TRADE_ID");
        s.put("tables.trade.columns.exclude", "_FILE");
        s.put("tables.trade.links.COUNTERPARTY", "counterparty");
        s.put("tables.counterparty.table", "refdata.counterparties");
        s.put("tables.counterparty.id", "CPTY_ID");
        s.put("tables.currency.table", "refdata.currencies");
        s.putAll(more);
        IcebergExternalPlugin p = new IcebergExternalPlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    /** pyiceberg's row of a fixture, by its columns, as the connector writes it (renamed and excluded columns applied). */
    static JsonNode pyicebergRow(String fixture, String column, String value, String dateColumn, String datePrefix) {
        JsonNode snaps = ExternalFixtures.expected().get(fixture).path("snapshots");
        for (JsonNode r : snaps.get(snaps.size() - 1).path("rows")) {
            if (value.equals(r.path(column).asText()) && (dateColumn == null || r.path(dateColumn).asText().startsWith(datePrefix))) {
                return r;
            }
        }
        throw new AssertionError("no row " + column + "=" + value);
    }

    @Test
    void aSparkStyleTableServesTradesWithItsMergeOnReadDeletesApplied() throws Exception {
        IcebergExternalPlugin p = plugin(Map.of());
        try {
            assertThat(p.health()).isEqualTo("UP");
            EntityDocument d = p.fetch(EntityRef.of("trade", "MUREX:T-007"), AsOf.of(D29)).orElseThrow();
            assertThat(d.provenance().businessDate()).isEqualTo(D29);
            ObjectNode want = (ObjectNode) pyicebergRow("spark_trades", "TRADE_ID", "T-007", "EVENT_TS", "2026-09-29").deepCopy();
            want.remove("_FILE");
            assertThat(ExternalFixtures.JSON.readTree(CODEC.toJson(d.data()))).isEqualTo(want);
            assertThat(p.fetch(EntityRef.of("trade", "MUREX:T-007"))).isEmpty();                       // deleted on the 30th
            assertThat(p.fetch(EntityRef.of("trade", "MUREX:T-007"), new AsOf(D30, beforeDeletes))).isPresent();   // known before the delete
            assertThat(p.fetch(EntityRef.of("trade", "MUREX:T-009"))).isPresent();
            assertThat(p.fetch(EntityRef.of("trade", "CALYPSO:T-009"))).isEmpty();                     // wrong source system
            ColumnSet c = p.columns("trade", List.of("BOOK", "MTM"), AsOf.LATEST).orElseThrow();
            assertThat(c.size()).isEqualTo(5);
            assertThat(c.businessDate()).isEqualTo(D30);
            assertThat(p.columnar("trade")).contains("TRADE_ID", "EVENT_TS", "MTM", "COUNTERPARTY").doesNotContain("_FILE");
            assertThat(p.search("trade", "MUREX:T-00", 10)).isNotEmpty();
            List<EntityRef> refs = p.reverse(EntityRef.of("counterparty", "CP-1"), "trade", AsOf.LATEST);
            assertThat(refs).extracting(EntityRef::id).containsExactlyInAnyOrder("MUREX:T-001", "MUREX:T-005", "MUREX:T-009");
            assertThat(p.fetch(EntityRef.of("trade", "MUREX:T-007"), AsOf.of(D28))).isPresent();
        } finally {
            p.close();
        }
    }

    @Test
    void aSnowflakeStyleTableKeepsNestedTypesDecimalsAndTimesInItsDocuments() throws Exception {
        IcebergExternalPlugin p = plugin(Map.of());
        try {
            EntityDocument d = p.fetch(EntityRef.of("counterparty", "CP-1"), AsOf.of(D30)).orElseThrow();
            JsonNode want = pyicebergRow("snowflake_counterparties", "CPTY_ID", "CP-1", "AS_OF", "2026-09-30");
            assertThat(ExternalFixtures.JSON.readTree(CODEC.toJson(d.data()))).isEqualTo(want);
            assertThat(d.data().get("ADDRESS").get("CITY").asText()).isEqualTo("Paris");
            assertThat(d.data().get("UPDATED_AT").asText()).endsWith("Z");
            assertThat(p.fetch(EntityRef.of("counterparty", "CP-2"), AsOf.of(D30))).isEmpty();         // the equality delete
            assertThat(p.fetch(EntityRef.of("counterparty", "CP-2"), AsOf.of(D29))).isPresent();       // an older day keeps it
            ColumnSet c = p.columns("counterparty", List.of("ADDRESS.CITY", "LIMIT_AMT"), AsOf.of(D29)).orElseThrow();
            assertThat(c.size()).isEqualTo(4);
            assertThat(c.texts().get("ADDRESS.CITY")).contains("Tokyo");
        } finally {
            p.close();
        }
        IcebergExternalPlugin joined = plugin(Map.of("tables.counterparty.id", ""));               // the identifier fields: CPTY_ID and AS_OF
        try {
            assertThat(joined.fetch(EntityRef.of("counterparty", "CP-1:2026-09-29"), AsOf.of(D29))).isPresent();
        } finally {
            joined.close();
        }
    }

    @Test
    void aTableWithoutADateIsCurrentOnly() throws Exception {
        IcebergExternalPlugin p = plugin(Map.of());
        try {
            EntityDocument d = p.fetch(EntityRef.of("currency", "JPY"), AsOf.of(LocalDate.of(2020, 1, 1))).orElseThrow();
            assertThat(d.data().get("DECIMALS").asDouble()).isZero();
            assertThat(d.provenance().businessDate()).isNull();
            assertThat(p.search("currency", "GBP", 5)).extracting(h -> h.ref().id()).startsWith("GBP");
        } finally {
            p.close();
        }
    }

    @Test
    void aRenamedAndEvolvedTableReadsEachSnapshotWithItsOwnSchema() throws Exception {
        Map<String, String> s = new HashMap<>(ExternalFixtures.metadataSettings(Map.of("position", "schema_evolution")));
        s.put("engine", "native");
        s.put("decimals", "string");
        s.put("tables.position.id", "id");
        s.put("tables.position.columns.rename.portfolio", "PORTFOLIO");
        IcebergExternalPlugin p = new IcebergExternalPlugin();
        p.start(DatedSourceContract.context(s));
        try {
            JsonNode now = ExternalFixtures.JSON.readTree(CODEC.toJson(p.fetch(EntityRef.of("position", "C")).orElseThrow().data()));
            assertThat(now.path("PORTFOLIO").asText()).isEqualTo("B3");
            assertThat(now.path("qty").asLong()).isEqualTo(30_000_000_000L);
            assertThat(now.has("old")).isFalse();
            JsonNode snaps = ExternalFixtures.expected().get("schema_evolution").path("snapshots");
            long first = snaps.get(0).path("timestamp-ms").asLong();
            JsonNode then = ExternalFixtures.JSON.readTree(CODEC.toJson(p.fetch(EntityRef.of("position", "A"), new AsOf(null, Instant.ofEpochMilli(first)))
                    .orElseThrow().data()));
            assertThat(then.path("book").asText()).isEqualTo("B1");                                  // the column's name at the time
            assertThat(then.path("old").asText()).isEqualTo("o1");
            assertThat(p.fetch(EntityRef.of("position", "C"), new AsOf(null, Instant.ofEpochMilli(first)))).isEmpty();
            assertThat(p.columnar("position")).contains("PORTFOLIO", "desk", "qty");
        } finally {
            p.close();
        }
    }

    @Test
    void tablesAutoServesANamespaceAndNamesATableWithoutAnId() throws Exception {
        IcebergExternalPlugin p = plugin(Map.of("tables", "auto", "namespace", "refdata", "tables.counterparty.table", "refdata.counterparties"));
        try {
            assertThat(p.manifest().kinds()).contains("currencies", "counterparty", "trade");
            assertThat(p.notServed()).containsKey("no-id-table");
            assertThat(p.health()).contains("no-id-table").contains("no id");
            assertThat(p.fetch(EntityRef.of("currencies", "EUR"))).isPresent();
            var schemas = p.starterSchemas();
            assertThat(schemas.get("trade").path("properties").path("COUNTERPARTY").path("x-drishti-link").asText()).isEqualTo("counterparty");
            assertThat(schemas.get("counterparty").path("properties").path("CPTY_ID").path("x-drishti-role").asText()).isEqualTo("key");
            assertThat(schemas.get("counterparty").path("properties").path("AS_OF").path("x-drishti-role").asText()).isEqualTo("date");
            assertThat(schemas.get("counterparty").path("properties").path("ADDRESS").path("type").asText()).isEqualTo("object");
        } finally {
            p.close();
        }
    }

    @Test
    void anUnsortedTableFindsOneEntityThroughItsIdIndex() throws Exception {
        IcebergExternalPlugin p = plugin(Map.of("tables.loose.table", "mart.unsorted", "tables.loose.id", "TRADE_ID", "id-index-warn-rows", "3"));
        try {
            for (String id : List.of("T-001", "T-003", "T-005", "T-007", "T-009", "T-011")) {
                assertThat(p.fetch(EntityRef.of("loose", id), AsOf.of(D30))).as(id).isPresent();
            }
            assertThat(p.fetch(EntityRef.of("loose", "T-002"), AsOf.of(D30))).isEmpty();
            assertThat((Long) p.cacheStats().get("idIndexes")).isPositive();
            assertThat(p.health()).contains("loose: the id index of one day holds 6 ids; sort the table by its id");
        } finally {
            p.close();
        }
    }

    @Test
    void aTableFolderWithoutMetadataNamesTheFilesLookedForAndDegradesTheConnector() throws Exception {
        Path meta = lake.resolve("qa").resolve("ghost").resolve("metadata");                 // QA2-203
        java.nio.file.Files.createDirectories(meta);
        Map<String, String> ghost = Map.of("tables.qa-ghost.table", "qa.ghost", "tables.qa-ghost.id", "POSITION_ID");
        try {
            IcebergExternalPlugin p = plugin(ghost);
            try {
                assertThat(p.health()).startsWith("DEGRADED: ").contains("qa-ghost: no table qa.ghost: looked in ")
                        .contains("version-hint.text, v<N>.metadata.json and a catalog's NNNNN-<uuid>.metadata.json (the folder is empty)");
                assertThat(p.notServed().get("qa-ghost")).contains("qa/ghost/metadata");
                assertThat(p.fetch(EntityRef.of("currency", "JPY"), AsOf.of(LocalDate.of(2020, 1, 1)))).as("the other tables still served").isPresent();
            } finally {
                p.close();
            }
            java.nio.file.Files.writeString(meta.resolve("00001-abc.metadata.json.tmp"), "{}");
            p = plugin(ghost);
            try {
                assertThat(p.health()).startsWith("DEGRADED: ").contains("(it holds 00001-abc.metadata.json.tmp)");
            } finally {
                p.close();
            }
        } finally {
            java.nio.file.Files.deleteIfExists(meta.resolve("00001-abc.metadata.json.tmp"));
            java.nio.file.Files.deleteIfExists(meta);
            java.nio.file.Files.deleteIfExists(meta.getParent());
            java.nio.file.Files.deleteIfExists(meta.getParent().getParent());
        }
    }

    @Test
    void theLibraryEngineIsRefused() {
        assertThatThrownBy(() -> plugin(Map.of("engine", "library"))).hasMessageContaining("drishti-iceberg");
    }
}
