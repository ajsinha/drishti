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
    void reverseLookupAndSearchCoverTheStream() {
        assertThat(plugin.reverse(EntityRef.of("netting-set", "NS-1"), "trade")).containsExactly(EntityRef.of("trade", "T-1"));
        assertThat(plugin.search("trade", "t-1", 5)).extracting(h -> h.ref().id()).contains("T-1");
    }
}
