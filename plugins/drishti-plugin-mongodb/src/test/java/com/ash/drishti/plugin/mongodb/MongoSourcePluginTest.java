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
package com.ash.drishti.plugin.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.testkit.DatedSourceContract;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The MongoDB connector against MongoDB in Docker, loaded with the contract's rows: the same tests as the Delta Lake,
 * PostgreSQL and Aerospike connectors. Trades are stored as JSON text, counterparties as embedded documents, so both
 * document formats are read. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoSourcePluginTest extends DatedSourceContract {

    /** 7.0: MongoDB 8.0 refuses to start on Linux 6.19 and newer (SERVER-121912) and 8.2 crashed there under load. */
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7").withExposedPorts(27017)
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    private static MongoSourcePlugin plugin;

    private static String uri() {
        return "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017);
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        JsonCodec codec = new JsonCodec();
        try (MongoClient c = MongoClients.create(uri())) {
            MongoDatabase db = c.getDatabase("drishti");
            MongoLayout.prepare(db, "desk", false);
            var coll = db.getCollection("desk", BsonDocument.class);
            var narrow = db.getCollection(MongoLayout.columnsCollection("desk"), BsonDocument.class);
            for (Row r : ROWS) {
                Map<String, Object> promoted = new HashMap<>();
                if (r.kind().equals("trade")) {                        // the layout below promotes these two
                    var doc = codec.read(r.json());
                    promoted.put("mtm", doc.get("mtm").isNull() ? null : doc.get("mtm").asDouble());
                    promoted.put("nettingSet", doc.get("nettingSet").isNull() ? null : doc.get("nettingSet").asText());
                }
                MongoLayout.DocFormat format = r.kind().equals("trade") ? MongoLayout.DocFormat.STRING : MongoLayout.DocFormat.BSON;
                BsonDocument d = MongoLayout.record(r.kind(), r.id(), r.date(), r.json(), promoted, format, null);
                coll.replaceOne(Filters.eq("_id", d.get("_id")), d, new com.mongodb.client.model.ReplaceOptions().upsert(true));
                if (!promoted.isEmpty()) {
                    narrow.replaceOne(Filters.eq("_id", d.get("_id")), MongoLayout.columnsRecord(d), new com.mongodb.client.model.ReplaceOptions().upsert(true));
                }
            }
        }
        MongoSourcePlugin p = new MongoSourcePlugin();
        p.start(context(Map.of("uri", uri(), "database", "drishti", "collection", "desk", "mode.counterparty", "effective",
                "source-name", "desk-mongodb", "layout.trade.columns", "mtm,nettingSet", "read-threads", "3")));
        plugin = p;
        return p;
    }

    @Test
    void aDaysPromotedFieldsAnswerSearchesAndReverseLookupsWithoutDocuments() throws Exception {
        SourcePlugin p = plugin();
        assertThat(p.columnar("trade")).containsExactlyInAnyOrder("mtm", "nettingSet");
        ColumnSet c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST).orElseThrow();
        assertThat(c.ids()).containsExactly("T-1", "T-2");
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            assertThat((Double) c.value("mtm", i)).isEqualTo(doc.get("mtm").asDouble());
            assertThat(c.value("nettingSet", i)).isEqualTo(doc.get("nettingSet").asText());
        }
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D1)).orElseThrow().ids()).containsExactly("T-1", "T-2", "T-3");
        assertThat(p.columns("trade", List.of("notional"), AsOf.LATEST)).isEmpty();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(LocalDate.of(2026, 1, 2)))).isEmpty();   // a day it does not hold
        assertThat(p.reverse(EntityRef.of("netting-set", "NS-A"), "trade", AsOf.LATEST)).containsExactly(EntityRef.of("trade", "T-1"),
                EntityRef.of("trade", "T-2"));
    }

    @Test
    void anEffectiveKindWithoutColumnsIsSearchedInItsDocuments() throws Exception {
        // embedded documents, each entity's latest on or before the date
        assertThat(plugin().reverse(EntityRef.of("x", "CP-X"), "counterparty", AsOf.of(D2))).containsExactly(EntityRef.of("counterparty", "CP-X"));
        assertThat(plugin().reverse(EntityRef.of("x", "CP-Y"), "counterparty", AsOf.of(D2))).isEmpty();
        assertThat(plugin().reverse(EntityRef.of("x", "CP-X"), "counterparty", AsOf.of(LocalDate.of(2026, 9, 1)))).isEmpty();
        assertThat(plugin().cacheStats()).containsEntry("kinds", 2).containsEntry("ids", 3);
        assertThat(plugin().lastUpdate()).isNotNull();
        assertThat(plugin().health()).isEqualTo("UP");
    }

    @Test
    void theLoaderIsIdempotentAndKeepsTheNewestDays() throws Exception {
        String lines = """
                {"domain":"books","kind":"trade","id":"MX-1","date":"2026-09-28","doc":"{\\"tradeId\\":\\"MX-1\\",\\"mtm\\":1}","columns":{"mtm":1,"book":"B-1"}}
                {"domain":"books","kind":"trade","id":"MX-1","date":"2026-09-29","doc":"{\\"tradeId\\":\\"MX-1\\",\\"mtm\\":2}","columns":{"mtm":2,"book":"B-1"}}
                {"domain":"books","kind":"trade","id":"MX-2","date":"2026-09-29","doc":"{\\"tradeId\\":\\"MX-2\\"}","columns":{"mtm":null,"book":"B-2"}}
                {"domain":"books","kind":"trade","id":"MX-1","date":"2026-09-30","doc":"{\\"tradeId\\":\\"MX-1\\",\\"mtm\\":3}","columns":{"mtm":3,"book":"B-1"}}
                """;
        try (MongoClient c = MongoClients.create(uri())) {
            MongoDatabase db = c.getDatabase("loader");
            for (int run = 0; run < 2; run++) {
                MongoLoader loader = new MongoLoader(db, MongoLayout.DocFormat.STRING, 0, 2, 2);
                assertThat(loader.load(new BufferedReader(new StringReader(lines)))).isEqualTo(4);
            }
            var books = db.getCollection("books");
            assertThat(books.countDocuments()).isEqualTo(4);
            Document mx2 = books.find(Filters.eq("_id", "trade/MX-2/20260929")).first();
            assertThat(mx2).isNotNull();
            assertThat(mx2.get("c", Document.class)).containsEntry("book", "B-2").containsEntry("mtm", null);
            assertThat(MongoLayout.missingIndexes(books)).isEmpty();
            var columns = db.getCollection("books_columns");
            assertThat(columns.countDocuments()).isEqualTo(4);
            assertThat(columns.find(Filters.eq("_id", "trade/MX-1/20260930")).first()).doesNotContainKey("doc").containsKey("c");
            assertThat(new MongoLoader(db, MongoLayout.DocFormat.STRING, 0, 1000, 4).keepDays(2, List.of("books"))).isEqualTo(1);
            assertThat(books.distinct("date", Integer.class).into(new java.util.ArrayList<>())).containsExactlyInAnyOrder(20260929, 20260930);
            assertThat(columns.countDocuments()).isEqualTo(3);
            // without the _columns collection a day's columns are read from the documents, and health says so
            columns.drop();
            MongoSourcePlugin p = new MongoSourcePlugin();
            p.start(context(Map.of("uri", uri(), "database", "loader", "collection", "books", "layout.trade.columns", "mtm,book")));
            try {
                ColumnSet day = p.columns("trade", List.of("mtm", "book"), AsOf.of(LocalDate.of(2026, 9, 29))).orElseThrow();
                assertThat(day.ids()).containsExactly("MX-1", "MX-2");
                assertThat(day.value("mtm", 0)).isEqualTo(2.0);
                assertThat(day.value("mtm", 1)).isNull();
                assertThat(day.value("book", 1)).isEqualTo("B-2");
                assertThat(p.health()).startsWith("UP (no books_columns collection");
            } finally {
                p.close();
            }
        }
    }

    @Test
    void promotedPathsAreFieldsOfC() {
        assertThat(MongoLayout.field("counterparty.id")).isEqualTo("counterparty__id");
        assertThat(MongoLayout.docKey("trade", "MX-1", LocalDate.of(2026, 9, 30))).isEqualTo("trade/MX-1/20260930");
        assertThat(MongoLayout.date(MongoLayout.day(LocalDate.of(2026, 9, 30)))).isEqualTo("2026-09-30");
    }
}
