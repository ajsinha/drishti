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

import static com.ash.drishti.plugin.kafka.KafkaSourcePluginTest.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * A suspended Kafka connector (docs/architecture/LIFECYCLE.md section 4) stops pulling and keeps its positions; resumed, it carries on from
 * them: every message produced before, during and after the suspension is applied exactly once (none lost, none read twice), and what it held
 * before is served unchanged after.
 */
class KafkaSuspendResumeTest {

    @Test
    void resumesFromTheKeptOffsetsWithNoLostOrDuplicatedRows() throws Exception {
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 2, "ticks");
        broker.afterPropertiesSet();
        KafkaSourcePlugin plugin = new KafkaSourcePlugin();
        Properties p = new Properties();
        p.put("bootstrap.servers", broker.getBrokersAsString());
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            plugin.start(DatedSourceContract.context(Map.of("bootstrap-servers", broker.getBrokersAsString(), "topics", "ticks", "kind.ticks", "tick",
                    "id-field.ticks", "id", "source-name", "ticks-feed", "poll-ms", "50")));
            waitFor(() -> "UP".equals(plugin.health()), 60);
            for (int i = 0; i < 50; i++) {
                send(producer, "T-" + i, i);
            }
            waitFor(() -> plugin.appliedRecords() == 50, 30);
            String before = plugin.fetch(EntityRef.of("tick", "T-7")).orElseThrow().data().toString();

            plugin.suspend();
            Thread.sleep(500);                                         // the consumer pauses at its next poll
            long atSuspend = plugin.appliedRecords();
            for (int i = 50; i < 120; i++) {
                send(producer, "T-" + i, i);
            }
            Thread.sleep(1500);
            assertThat(plugin.appliedRecords()).as("nothing is pulled while suspended").isEqualTo(atSuspend);
            assertThat(atSuspend).as("every message before the suspension, once").isEqualTo(50);

            plugin.resume();
            waitFor(() -> plugin.appliedRecords() >= 120, 30);
            for (int i = 120; i < 150; i++) {
                send(producer, "T-" + i, i);
            }
            waitFor(() -> plugin.appliedRecords() >= 150, 30);
            Thread.sleep(500);
            assertThat(plugin.appliedRecords()).as("every message exactly once: none lost, none read twice").isEqualTo(150);
            for (int i : new int[] {0, 49, 50, 119, 120, 149}) {
                assertThat(plugin.fetch(EntityRef.of("tick", "T-" + i))).as("T-" + i).isPresent()
                        .hasValueSatisfying(d -> assertThat(d.data().get("n").asInt()).isEqualTo(i));
            }
            assertThat(plugin.fetch(EntityRef.of("tick", "T-7")).orElseThrow().data().toString()).as("kept state served unchanged").isEqualTo(before);
        } finally {
            plugin.close();
            broker.destroy();
        }
    }

    private static void send(KafkaProducer<String, String> producer, String key, int n) throws Exception {
        producer.send(new ProducerRecord<>("ticks", key, "{\"id\":\"" + key + "\",\"n\":" + n + "}")).get(10, TimeUnit.SECONDS);
    }
}
