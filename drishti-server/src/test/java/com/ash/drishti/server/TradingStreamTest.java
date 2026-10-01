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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Live trades from Kafka: with the trading stream switched on, a trade published to the topic is what Live shows. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading", "DRISHTI_STREAM_TRADING=true",
        "logging.level.org.apache.kafka=ERROR", "logging.level.kafka=ERROR"})
@AutoConfigureMockMvc
class TradingStreamTest {

    static final EmbeddedKafkaKraftBroker BROKER = new EmbeddedKafkaKraftBroker(1, 1, "drishti.trading.trades");

    static {
        BROKER.afterPropertiesSet();
        Properties p = new Properties();
        p.put("bootstrap.servers", BROKER.getBrokersAsString());
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            String sample = Files.readString(Path.of("../packs/trading/samples/trade/MX-20000001.json"));
            String streamed = sample.replaceFirst("\"mtm\": -?[0-9.]+", "\"mtm\": 1234567");
            producer.send(new ProducerRecord<>("drishti.trading.trades", "MX-20000001", streamed)).get(10, TimeUnit.SECONDS);
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
        BROKER.destroy();
    }

    @Autowired MockMvc mvc;

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
}
