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
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/** The Kafka source starts before its broker exists, keeps retrying, and serves the stream once the broker is up. */
class KafkaReconnectTest {

    @Test
    void startsBeforeTheBrokerAndConnectsWhenItComesUp() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        KafkaSourcePlugin plugin = new KafkaSourcePlugin();
        plugin.start(DatedSourceContract.context(Map.of("bootstrap-servers", "localhost:" + port, "topics", "trades", "kind.trades", "trade",
                "id-field.trades", "tradeId", "source-name", "late-stream", "poll-ms", "50",
                "client.default.api.timeout.ms", "3000", "client.reconnect.backoff.max.ms", "500")));
        Thread.sleep(1500);
        assertThat(plugin.fetch(EntityRef.of("trade", "T-7"))).isEmpty();                   // up, with nothing yet
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1, "trades");
        broker.afterPropertiesSet();
        // the embedded broker picks its own port: the address the connector was given starts answering now, as a
        // broker coming up there would (the client then follows the broker's advertised address)
        int brokerPort = Integer.parseInt(broker.getBrokersAsString().replaceAll(".*:", ""));
        ServerSocket relay = relay(port, brokerPort);
        try {
            Properties p = new Properties();
            p.put("bootstrap.servers", broker.getBrokersAsString());
            p.put("key.serializer", StringSerializer.class.getName());
            p.put("value.serializer", StringSerializer.class.getName());
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
                producer.send(new ProducerRecord<>("trades", "T-7", "{\"tradeId\":\"T-7\",\"mtm\":42}")).get(10, TimeUnit.SECONDS);
            }
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            waitFor(() -> {
                seen.add(plugin.health());
                return "UP".equals(plugin.health());
            }, 60);

            waitFor(() -> {
                try {
                    return plugin.fetch(EntityRef.of("trade", "T-7")).map(d -> d.data().get("mtm").asDouble() == 42).orElse(false);
                } catch (Exception e) {
                    return false;
                }
            }, 30);
        } finally {
            plugin.close();
            relay.close();
            broker.destroy();
        }
    }

    /** Forwards connections on {@code from} to {@code to}, byte for byte, on virtual threads. */
    private static ServerSocket relay(int from, int to) throws java.io.IOException {
        ServerSocket server = new ServerSocket(from, 50, java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    java.net.Socket in = server.accept();
                    java.net.Socket out = new java.net.Socket("localhost", to);
                    Thread.ofVirtual().start(() -> pipe(in, out));
                    Thread.ofVirtual().start(() -> pipe(out, in));
                } catch (java.io.IOException e) {
                    return;
                }
            }
        });
        return server;
    }

    private static void pipe(java.net.Socket a, java.net.Socket b) {
        try (var i = a.getInputStream(); var o = b.getOutputStream()) {
            i.transferTo(o);
        } catch (java.io.IOException ignored) {
            // one side closed
        }
    }
}
