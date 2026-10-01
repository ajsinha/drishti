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
package com.ash.drishti.plugin.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/** The Kafka live source against an in-process KRaft broker: state from the log, live pushes, tombstones, both shapes. */
class KafkaSourcePluginTest {

    static EmbeddedKafkaKraftBroker broker;
    static KafkaProducer<String, String> producer;
    static KafkaSourcePlugin plugin;

    @BeforeAll
    static void start() throws Exception {
        broker = new EmbeddedKafkaKraftBroker(1, 1, "trades", "entities");
        broker.afterPropertiesSet();
        Properties p = new Properties();
        p.put("bootstrap.servers", broker.getBrokersAsString());
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        producer = new KafkaProducer<>(p);
        // history written before Drishti starts: the latest message per entity wins
        send("trades", "T-1", "{\"tradeId\":\"T-1\",\"mtm\":1,\"nettingSet\":\"NS-1\"}");
        send("trades", "T-1", "{\"tradeId\":\"T-1\",\"mtm\":2,\"nettingSet\":\"NS-1\"}");
        send("trades", "T-2", "{\"tradeId\":\"T-2\",\"mtm\":5,\"nettingSet\":\"NS-9\"}");
        send("trades", "T-2", null);                                                     // tombstone: T-2 is gone
        send("entities", "netting-set/NS-1", "{\"kind\":\"netting-set\",\"id\":\"NS-1\",\"doc\":{\"nettingSetId\":\"NS-1\",\"netMtm\":2}}");
        send("entities", "NS-2", "{\"kind\":\"netting-set\",\"id\":\"NS-2\",\"doc\":{\"nettingSetId\":\"NS-2\",\"netMtm\":7}}");  // keyed by the bare id
        send("entities", "x", "not json");                                               // skipped
        plugin = new KafkaSourcePlugin();
        plugin.start(DatedSourceContract.context(Map.of("bootstrap-servers", broker.getBrokersAsString(), "topics", "trades,entities",
                "kind.trades", "trade", "id-field.trades", "tradeId", "source-name", "trade-stream", "poll-ms", "50")));
        waitFor(() -> "UP".equals(plugin.health()), 30);
    }

    @AfterAll
    static void stop() {
        plugin.close();
        producer.close(Duration.ofSeconds(2));
        broker.destroy();
    }

    static void send(String topic, String key, String value) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
    }

    static void waitFor(BooleanSupplier ok, int seconds) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!ok.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(50);
        }
        assertThat(ok.getAsBoolean()).as("condition within " + seconds + " s").isTrue();
    }

    @Test
    void theLogRebuildsTheLatestStateOfEveryEntity() throws Exception {
        EntityDocument t1 = plugin.fetch(EntityRef.of("trade", "T-1")).orElseThrow();
        assertThat(t1.data().get("mtm").asDouble()).isEqualTo(2);
        assertThat(t1.provenance().live()).isTrue();
        assertThat(t1.provenance().source()).isEqualTo("trade-stream");
        assertThat(plugin.fetch(EntityRef.of("trade", "T-2"))).isEmpty();
        assertThat(plugin.fetch(EntityRef.of("netting-set", "NS-1")).orElseThrow().data().get("netMtm").asDouble()).isEqualTo(2);
        assertThat(plugin.fetch(EntityRef.of("netting-set", "NS-2")).orElseThrow().data().get("netMtm").asDouble()).isEqualTo(7);
        assertThat(plugin.manifest().kinds()).contains("trade", "netting-set");
        assertThat(plugin.manifest().capabilities().live()).isTrue();
    }

    @Test
    void newMessagesArePushedToOpenViews() throws Exception {
        send("trades", "T-7", "{\"tradeId\":\"T-7\",\"mtm\":1}");
        waitFor(() -> plugin.fetch(EntityRef.of("trade", "T-7")).isPresent(), 15);
        CompletableFuture<EntityDocument> pushed = new CompletableFuture<>();
        try (var sub = plugin.subscribe(EntityRef.of("trade", "T-7"), d -> {
            if (d.data().get("mtm").asDouble() == 3) {
                pushed.complete(d);
            }
        })) {
            send("trades", "T-7", "{\"tradeId\":\"T-7\",\"mtm\":3}");
            assertThat(pushed.get(15, TimeUnit.SECONDS).provenance().generation()).isPositive();
        }
        assertThat(plugin.fetch(EntityRef.of("trade", "T-7")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(3);
    }

    @Test
    void aTombstoneIsPushedToOpenViewsAndLeavesTypeAhead() throws Exception {
        EntityRef t8 = EntityRef.of("trade", "T-8");
        send("trades", "T-8", "{\"tradeId\":\"T-8\",\"mtm\":1}");
        waitFor(() -> !plugin.search("trade", "T-8", 5).isEmpty(), 15);
        CompletableFuture<EntityDocument> gone = new CompletableFuture<>();
        try (var sub = plugin.subscribe(t8, d -> {
            if (d.deleted()) {
                gone.complete(d);
            }
        })) {
            send("trades", "T-8", null);
            EntityDocument d = gone.get(15, TimeUnit.SECONDS);
            assertThat(d.ref()).isEqualTo(t8);
            assertThat(d.data().isMissing()).isTrue();
            assertThat(d.provenance().source()).isEqualTo("trade-stream");
            assertThat(d.provenance().fetchedAt()).isNotNull();
        }
        assertThat(plugin.fetch(t8)).isEmpty();
        assertThat(plugin.search("trade", "T-8", 5)).isEmpty();

        // the envelope shape deletes with a null doc; a re-created entity comes back to type-ahead
        EntityRef ns3 = EntityRef.of("netting-set", "NS-3");
        send("entities", "netting-set/NS-3", "{\"kind\":\"netting-set\",\"id\":\"NS-3\",\"doc\":{\"netMtm\":1}}");
        waitFor(() -> !plugin.search("netting-set", "NS-3", 5).isEmpty(), 15);
        CompletableFuture<EntityDocument> envelopeGone = new CompletableFuture<>();
        try (var sub = plugin.subscribe(ns3, d -> {
            if (d.deleted()) {
                envelopeGone.complete(d);
            }
        })) {
            send("entities", "netting-set/NS-3", "{\"kind\":\"netting-set\",\"id\":\"NS-3\",\"doc\":null}");
            assertThat(envelopeGone.get(15, TimeUnit.SECONDS).ref()).isEqualTo(ns3);
        }
        assertThat(plugin.search("netting-set", "NS-3", 5)).isEmpty();
        send("trades", "T-8", "{\"tradeId\":\"T-8\",\"mtm\":4}");
        waitFor(() -> !plugin.search("trade", "T-8", 5).isEmpty(), 15);
    }

    @Test
    void searchCoversTheStreamAndReverseLookupsAreLeftToTheStores() {
        assertThat(plugin.search("trade", "t-1", 5)).extracting(h -> h.ref().id()).contains("T-1");
        assertThat(plugin.manifest().capabilities().reverseLookup()).isFalse();
    }

    @Test
    void withNoCacheEveryReadGoesBackToTheLogByOffset() throws Exception {
        KafkaSourcePlugin lean = new KafkaSourcePlugin();
        lean.start(DatedSourceContract.context(Map.of("bootstrap-servers", broker.getBrokersAsString(), "topics", "trades",
                "kind", "trade", "id-field", "tradeId", "cache-mb", "0", "source-name", "lean")));
        try {
            waitFor(() -> "UP".equals(lean.health()), 30);
            assertThat(lean.fetch(EntityRef.of("trade", "T-1")).orElseThrow().data().get("tradeId").asText()).isEqualTo("T-1");
            assertThat(lean.fetch(EntityRef.of("trade", "T-2"))).isEmpty();                 // deleted by its tombstone
        } finally {
            lean.close();
        }
    }

    @Test
    void theDiskCacheServesTheDaysLiveDataFromTheConnectorsOwnStore() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("drishti-cache");
        KafkaSourcePlugin disked = new KafkaSourcePlugin();
        disked.start(DatedSourceContract.context(Map.of("bootstrap-servers", broker.getBrokersAsString(), "topics", "trades",
                "kind", "trade", "id-field", "tradeId", "cache-mb", "0", "source-name", "desk-stream",
                "disk-cache.enabled", "true", "disk-cache.root", root.toString(), "disk-cache.reset-at", "02:00")));
        try {
            waitFor(() -> "UP".equals(disked.health()), 30);
            assertThat(disked.fetch(EntityRef.of("trade", "T-1")).orElseThrow().data().get("tradeId").asText()).isEqualTo("T-1");
            assertThat(disked.diskCache().hits()).isPositive();                         // served from RocksDB, not Kafka
            assertThat(root.resolve("desk-stream")).isDirectory();                      // the connector's own store
            assertThat(disked.fetch(EntityRef.of("trade", "T-2"))).isEmpty();
            disked.diskCache().clear();                                                 // what the nightly reset does
            assertThat(disked.fetch(EntityRef.of("trade", "T-1"))).isPresent();          // still answered, from the log
        } finally {
            disked.close();
        }
    }

    @Test
    void ticksModeKeepsNothingButStillPushes() throws Exception {
        KafkaSourcePlugin ticks = new KafkaSourcePlugin();
        ticks.start(DatedSourceContract.context(Map.of("bootstrap-servers", broker.getBrokersAsString(), "topics", "trades",
                "kind", "trade", "id-field", "tradeId", "mode", "ticks", "source-name", "ticks")));
        try {
            waitFor(() -> "UP".equals(ticks.health()), 30);
            assertThat(ticks.fetch(EntityRef.of("trade", "T-1"))).isEmpty();                 // the store serves the entity
            CompletableFuture<EntityDocument> pushed = new CompletableFuture<>();
            try (var sub = ticks.subscribe(EntityRef.of("trade", "T-9"), pushed::complete)) {
                send("trades", "T-9", "{\"tradeId\":\"T-9\",\"mtm\":42}");
                assertThat(pushed.get(15, TimeUnit.SECONDS).data().get("mtm").asDouble()).isEqualTo(42);
            }
        } finally {
            ticks.close();
        }
    }

    @Test
    void inTicksModeItServesNoReadsButPushesEveryUpdateForItsKinds() throws Exception {
        KafkaSourcePlugin ticks = new KafkaSourcePlugin();
        ticks.start(DatedSourceContract.context(Map.of("bootstrap-servers", broker.getBrokersAsString(), "topics", "trades",
                "kind", "trade", "id-field", "tradeId", "mode", "ticks", "source-name", "ticks")));
        try {
            waitFor(() -> "UP".equals(ticks.health()), 30);
            assertThat(ticks.fetch(EntityRef.of("trade", "T-1"))).isEmpty();                   // keeps nothing: the lake answers reads
            assertThat(ticks.pushes(EntityRef.of("trade", "T-1"))).isTrue();
            assertThat(ticks.pushes(EntityRef.of("curve", "C-1"))).isFalse();
            assertThat(plugin.pushes(EntityRef.of("trade", "T-1"))).isFalse();                // state mode serves what it pushes
            CompletableFuture<EntityDocument> pushed = new CompletableFuture<>();
            try (var sub = ticks.subscribe(EntityRef.of("trade", "T-44"), pushed::complete)) {
                send("trades", "T-44", "{\"tradeId\":\"T-44\",\"mtm\":9}");
                assertThat(pushed.get(15, TimeUnit.SECONDS).data().get("mtm").asDouble()).isEqualTo(9);
            }
        } finally {
            ticks.close();
        }
    }
}
