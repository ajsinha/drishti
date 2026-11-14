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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.api.write.WriteCommand;
import com.ash.drishti.api.write.WriteResult;
import com.ash.drishti.testkit.DatedSourceContract;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * The Kafka plugin as a blotter's source and its write path, against an in-process broker: every change of a kind pushed live, the
 * kind's state in one pass, and {@code kafka:} commands: the version guard naming who changed the record, the change id making a resend
 * a no-op, a command message keyed by its change id, and the amended record written back to the trade topic.
 */
class KafkaBlotterTest {

    static EmbeddedKafkaKraftBroker broker;
    static KafkaProducer<String, String> producer;
    static KafkaSourcePlugin plugin;

    @BeforeAll
    static void start() throws Exception {
        broker = new EmbeddedKafkaKraftBroker(1, 1, "blotter-trades", "blotter-amends");
        broker.afterPropertiesSet();
        Properties p = new Properties();
        p.put("bootstrap.servers", broker.getBrokersAsString());
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        producer = new KafkaProducer<>(p);
        send("MX-1", "{\"TRADE_ID\":\"MX-1\",\"VERSION\":1,\"BOOK\":\"B1\",\"UPDATED_BY\":\"ann\"}");
        send("MX-1", "{\"TRADE_ID\":\"MX-1\",\"VERSION\":2,\"BOOK\":\"B2\",\"UPDATED_BY\":\"kim\",\"UPDATED_AT\":\"10:02:11\"}");
        send("MX-2", "{\"TRADE_ID\":\"MX-2\",\"VERSION\":1,\"BOOK\":\"B1\"}");
        plugin = new KafkaSourcePlugin();
        plugin.start(DatedSourceContract.context(Map.of("bootstrap-servers", broker.getBrokersAsString(), "topics", "blotter-trades",
                "kind.blotter-trades", "trade", "id-field.blotter-trades", "TRADE_ID", "source-name", "trade-kafka", "poll-ms", "20")));
        waitFor(() -> "UP".equals(plugin.health()), 30);
    }

    @AfterAll
    static void stop() {
        plugin.close();
        producer.close(Duration.ofSeconds(2));
        broker.destroy();
    }

    static void send(String key, String value) throws Exception {
        producer.send(new ProducerRecord<>("blotter-trades", key, value)).get(10, TimeUnit.SECONDS);
    }

    static void waitFor(BooleanSupplier ok, int seconds) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!ok.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(ok.getAsBoolean()).as("condition within " + seconds + " s").isTrue();
    }

    static WriteCommand command(String shape, String topic, String entity, Object basedOn, String changeId, String field, Object value) {
        Map<String, Object> block = Map.of("topic", topic, "shape", shape, "version-field", "VERSION", "changed-by", "UPDATED_BY", "changed-at", "UPDATED_AT",
                "change-id-field", "DRISHTI_CHANGE_ID");
        Map<String, Object> params = new HashMap<>();
        params.put("kind", "trade");
        params.put("entity", entity);
        params.put("basedOn", basedOn);
        params.put("changeId", changeId);
        params.put("field", field);
        params.put("value", value);
        params.put("by", "ann");
        params.put("at", "2026-11-14T10:05:00Z");
        return new WriteCommand("amend-trade", block, params);
    }

    @Test
    void everyChangeOfTheKindIsPushedAndTheStateIsReadInOnePass() throws Exception {
        List<EntityDocument> seen = new CopyOnWriteArrayList<>();
        try (Subscription s = plugin.subscribeKind("trade", seen::add)) {
            long t0 = System.nanoTime();
            send("MX-3", "{\"TRADE_ID\":\"MX-3\",\"VERSION\":1,\"BOOK\":\"B3\"}");
            waitFor(() -> seen.stream().anyMatch(d -> d.ref().id().equals("MX-3")), 10);
            assertThat((System.nanoTime() - t0) / 1_000_000).as("pushed within a second").isLessThan(1_000);
        }
        List<EntityDocument> all = new ArrayList<>();
        assertThat(plugin.documents("trade", AsOf.LATEST, all::add)).isTrue();
        Map<String, Object> mx1 = all.stream().filter(d -> d.ref().id().equals("MX-1")).findFirst().map(d -> (Map<String, Object>) cast(d.data().unwrap()))
                .orElseThrow();
        assertThat(mx1).containsEntry("BOOK", "B2");
        assertThat(all).extracting(d -> d.ref().id()).contains("MX-1", "MX-2", "MX-3");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void aCommandIsSentOnceKeyedByItsChangeIdAndAStaleOneIsRefusedSayingWhoChangedIt() throws Exception {
        assertThat(KafkaWrites.check("amend-trade", Map.of("topic", "t"), Set.of())).anyMatch(p -> p.contains("version-field"))
                .anyMatch(p -> p.contains("change-id-field"));
        waitFor(() -> plugin.fetch(EntityRef.of("trade", "MX-1")).isPresent(), 10);
        WriteResult stale = plugin.write(command("command", "blotter-amends", "MX-1", 1L, "chg-A", "BOOK", "B9"));
        assertThat(stale.status()).isEqualTo(WriteResult.Status.CONFLICTED);
        assertThat(stale.reason()).contains("version 2").contains("changed by kim").contains("made on version 1");
        assertThat(plugin.write(command("command", "blotter-amends", "MX-1", 2L, "chg-B", "BOOK", "B9")).status()).isEqualTo(WriteResult.Status.APPLIED);
        assertThat(plugin.write(command("command", "blotter-amends", "MX-1", 2L, "chg-B", "BOOK", "B9")).status()).isEqualTo(WriteResult.Status.ALREADY);
        List<ConsumerRecord<String, String>> amends = read("blotter-amends");
        assertThat(amends).hasSize(1);
        assertThat(amends.get(0).key()).isEqualTo("chg-B");
        assertThat(new String(amends.get(0).headers().lastHeader("drishti-change-id").value())).isEqualTo("chg-B");
        assertThat(amends.get(0).value()).contains("\"basedOn\":2").contains("\"value\":\"B9\"");
    }

    @Test
    void aDocumentCommandWritesTheAmendedRecordBackWithTheNextVersion() throws Exception {
        waitFor(() -> plugin.fetch(EntityRef.of("trade", "MX-2")).isPresent(), 10);
        assertThat(plugin.write(command("document", "blotter-trades", "MX-2", 1L, "chg-C#1", "BOOK", "B7")).status()).isEqualTo(WriteResult.Status.APPLIED);
        // the second field of the same change builds on the first before the stream brings it back
        assertThat(plugin.write(command("document", "blotter-trades", "MX-2", 1L, "chg-C#2", "NOTIONAL", 5)).status()).isEqualTo(WriteResult.Status.APPLIED);
        waitFor(() -> {
            try {
                Object v = plugin.fetch(EntityRef.of("trade", "MX-2")).map(d -> cast(d.data().unwrap()).get("VERSION")).orElse(null);
                return v != null && ((Number) v).longValue() == 3;
            } catch (Exception e) {
                return false;
            }
        }, 10);
        Map<String, Object> now = cast(plugin.fetch(EntityRef.of("trade", "MX-2")).orElseThrow().data().unwrap());
        assertThat(now).containsEntry("BOOK", "B7").containsEntry("DRISHTI_CHANGE_ID", "chg-C#2").containsEntry("UPDATED_BY", "ann");
        assertThat(((Number) now.get("NOTIONAL")).longValue()).isEqualTo(5);
        // a resend after a failover: the record already carries the change id
        assertThat(plugin.write(command("document", "blotter-trades", "MX-2", 1L, "chg-C#2", "NOTIONAL", 5)).status()).isEqualTo(WriteResult.Status.ALREADY);
    }

    static List<ConsumerRecord<String, String>> read(String topic) {
        Properties p = new Properties();
        p.put("bootstrap.servers", broker.getBrokersAsString());
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            TopicPartition tp = new TopicPartition(topic, 0);
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            long end = c.endOffsets(List.of(tp)).get(tp);
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (c.position(tp) < end && System.nanoTime() < until) {
                c.poll(Duration.ofMillis(100)).forEach(out::add);
            }
            return out;
        }
    }
}
