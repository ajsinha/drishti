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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Live trades from Kafka: with the trading stream switched on, a trade published to the topic is what Live shows, and a
 * tombstone reaches an open view's live stream as a {@code deleted} patch and takes the trade out of type-ahead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading", "DRISHTI_STREAM_TRADING=true",
        "logging.level.org.apache.kafka=ERROR", "logging.level.kafka=ERROR"})
@AutoConfigureMockMvc
class TradingStreamTest {

    static final EmbeddedKafkaKraftBroker BROKER = new EmbeddedKafkaKraftBroker(1, 1, "drishti.trading.trades");
    static final KafkaProducer<String, String> PRODUCER;
    static final String SAMPLE;

    static {
        BROKER.afterPropertiesSet();
        Properties p = new Properties();
        p.put("bootstrap.servers", BROKER.getBrokersAsString());
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        PRODUCER = new KafkaProducer<>(p);
        try {
            SAMPLE = Files.readString(Path.of("../packs/trading/samples/trade/MX-20000001.json"));
            String streamed = SAMPLE.replaceFirst("\"mtm\": -?[0-9.]+", "\"mtm\": 1234567");
            PRODUCER.send(new ProducerRecord<>("drishti.trading.trades", "MX-20000001", streamed)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("DRISHTI_KAFKA_BOOTSTRAP", BROKER::getBrokersAsString);
    }

    @AfterAll
    static void stop() {
        PRODUCER.close(java.time.Duration.ofSeconds(2));
        BROKER.destroy();
    }

    @Autowired MockMvc mvc;
    @LocalServerPort int port;

    @Test
    void liveTradesComeFromTheStream() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String source = "";
        while (!"trading-stream".equals(source) && System.nanoTime() < until) {
            Thread.sleep(200);
            String body = mvc.perform(get("/api/v1/views/trade/MX-20000001")).andReturn().getResponse().getContentAsString();
            source = com.jayway.jsonpath.JsonPath.read(body, "$.provenance.source");
        }
        mvc.perform(get("/api/v1/views/trade/MX-20000001"))
                .andExpect(jsonPath("$.provenance.source").value("trading-stream"))
                .andExpect(jsonPath("$.provenance.live").value(true))
                .andExpect(jsonPath("$.strip[?(@.label == 'MTM (USD)')].text").value(org.hamcrest.Matchers.hasItem("+1,234,567")));
    }

    @Test
    void aTombstoneReachesTheOpenViewAsADeletedPatchAndLeavesTypeAhead() throws Exception {
        String id = "MX-29999999";
        PRODUCER.send(new ProducerRecord<>("drishti.trading.trades", id, SAMPLE.replace("MX-20000001", id))).get(10, TimeUnit.SECONDS);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (mvc.perform(get("/api/v1/views/trade/" + id)).andReturn().getResponse().getStatus() != 200 && System.nanoTime() < until) {
            Thread.sleep(200);
        }
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!mvc.perform(get("/api/v1/command/suggest").param("q", id)).andReturn().getResponse().getContentAsString().contains(id)
                && System.nanoTime() < until) {
            Thread.sleep(200);
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        HttpResponse<java.io.InputStream> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/views/trade/" + id + "/stream"))
                .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        String deleted = null;
        boolean restored = false;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            String event = null;
            String line;
            long deadline = System.currentTimeMillis() + 30_000;
            while (deleted == null && (line = in.readLine()) != null && System.currentTimeMillis() < deadline) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:") && "view".equals(event)) {
                    PRODUCER.send(new ProducerRecord<>("drishti.trading.trades", id, null)).get(10, TimeUnit.SECONDS);   // the tombstone
                } else if (line.startsWith("data:") && "frame".equals(event) && line.contains("\"op\":\"deleted\"")) {
                    deleted = line.substring(5);
                }
            }
            assertThat(deleted).as("a deleted patch on the live stream").isNotNull();
            assertThat(mvc.perform(get("/api/v1/command/suggest").param("q", id)).andReturn().getResponse().getContentAsString()).doesNotContain(id);
            assertThat(mvc.perform(get("/api/v1/views/trade/" + id)).andReturn().getResponse().getStatus()).isEqualTo(404);
            // the trade comes back: the open view is told it is restored
            PRODUCER.send(new ProducerRecord<>("drishti.trading.trades", id, SAMPLE.replace("MX-20000001", id))).get(10, TimeUnit.SECONDS);
            while (!restored && (line = in.readLine()) != null && System.currentTimeMillis() < deadline) {
                restored = line.startsWith("data:") && line.contains("\"op\":\"restored\"");
            }
        }
        assertThat(restored).as("a restored patch once the trade is back").isTrue();
        var frame = new com.fasterxml.jackson.databind.ObjectMapper().readTree(deleted);
        var patch = frame.get("patches").get(0);
        assertThat(patch.get("op").asText()).isEqualTo("deleted");
        assertThat(java.time.Instant.parse(patch.get("at").asText())).isAfter(java.time.Instant.now().minusSeconds(120));
    }
}
