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
package com.ash.drishti.server.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A trade blotter's correction written back to its own source system through Kafka (KERNEL_AND_MODULES.md section 18.6), end to end against an
 * in-process broker: Endur's trade comes from the source module's live connector (trade-endur-live); a correction waits for a second person
 * (four eyes, the site's policy for source amendments) and nothing is written until it is approved; approved, exactly one new version of the
 * record is written to Endur's topic; a correction made on the old version is refused, naming who changed the record.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading",
        "DRISHTI_STREAM_ENDUR=true", "logging.level.org.apache.kafka=ERROR", "logging.level.kafka=ERROR",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.edit.file=../../config/edit/edit.yaml", "DRISHTI_EDIT_ENABLED=true",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/kafka-edit-${random.uuid}.db",
        "drishti.security.roles.trader.kinds[0]=*", "drishti.security.roles.trader.raw=true", "drishti.security.roles.trader.edit=true",
        "drishti.security.roles.checker.kinds[0]=*", "drishti.security.roles.checker.raw=true", "drishti.security.roles.checker.approve=true",
        "drishti.security.roles.admin.kinds[0]=*", "drishti.security.roles.admin.admin=true"})
@AutoConfigureMockMvc
class KafkaTradeEditTest {

    static final String TOPIC = "drishti.trading.endur.trades";
    static final EmbeddedKafkaKraftBroker BROKER = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
    static final KafkaProducer<String, String> PRODUCER;

    static {
        BROKER.afterPropertiesSet();
        Properties p = new Properties();
        p.put("bootstrap.servers", BROKER.getBrokersAsString());
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        PRODUCER = new KafkaProducer<>(p);
        try {
            PRODUCER.send(new ProducerRecord<>(TOPIC, "EN-1", "{\"TRADE_ID\":\"EN-1\",\"TRADE_VERSION\":1,\"PORTFOLIO\":\"BOOK-A\",\"PRICE\":10.5,"
                    + "\"QUANTITY\":100,\"TRADE_STATUS\":\"NEW\",\"UPDATED_BY\":\"ann\",\"SOURCE_UPDATE_TIME\":\"2026-11-14T10:00:00Z\"}"))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("broker.default.bootstrap-servers", BROKER::getBrokersAsString);   // the default broker by name ([broker.default])
    }

    @AfterAll
    static void stop() {
        PRODUCER.close(Duration.ofSeconds(2));
        BROKER.destroy();
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired ConnectorWrites writes;
    private final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode call(String method, String url, String auth, Object body, int expect) throws Exception {
        var req = "POST".equals(method) ? post(url) : get(url);
        req = req.header("Authorization", auth);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        var res = mvc.perform(req).andReturn().getResponse();
        String out = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(res.getStatus()).as(method + " " + url + ": " + out).isEqualTo(expect);
        return out.isBlank() ? json.createObjectNode() : json.readTree(out);
    }

    /** Every message on Endur's topic, in order. */
    private static List<Map<String, Object>> topic() {
        Properties p = new Properties();
        p.put("bootstrap.servers", BROKER.getBrokersAsString());
        p.put("group.id", "check-" + UUID.randomUUID());
        p.put("auto.offset.reset", "earliest");
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        List<Map<String, Object>> out = new ArrayList<>();
        ObjectMapper m = new ObjectMapper();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(TOPIC));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < end) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> v = m.readValue(r.value(), Map.class);
                        out.add(v);
                    } catch (Exception e) {
                        out.add(Map.of("raw", String.valueOf(r.value())));
                    }
                }
            }
        }
        return out;
    }

    private JsonNode waitFor(String id, String state, String auth) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        JsonNode c = null;
        while (System.nanoTime() < until) {
            c = call("GET", "/api/v1/changes/" + id, auth, null, 200);
            if (state.equals(c.path("state").asText())) {
                return c;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(id + " is " + (c == null ? "?" : c.path("state").asText()) + ", not " + state + ": " + c);
    }

    @Test
    void aCorrectionWaitsForASecondPersonIsWrittenOnceToItsOwnSystemAndAStaleOneIsRefusedNamingWhoChangedIt() throws Exception {
        writes.set("trade-endur-live", true, "the test's write path", "admin");
        String tia = as("tia", "trader");
        String kim = as("kim", "checker");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);       // the live connector has read Endur's trade
        while (mvc.perform(get("/api/v1/views/trade/EN-1").header("Authorization", tia)).andReturn().getResponse().getStatus() != 200
                && System.nanoTime() < until) {
            Thread.sleep(250);
        }
        assertThat(topic()).hasSize(1);

        // the blotter's edit is a Data Protocol write on the trade table, handed to the write-back as the person with its change id
        JsonNode sent = call("POST", "/api/v1/rdp/tables/trade/write", tia, Map.of("id", "01JKAFKAEDIT0000000000001", "op", "update",
                "key", List.of("EN-1"), "basedOn", 1, "reason", "booked to the wrong book", "set", Map.of("PORTFOLIO", "BOOK-B"),
                "from", Map.of("PORTFOLIO", "BOOK-A")), 200);
        assertThat(sent.path("state").asText()).as("four eyes: the site's policy for a source amendment").isEqualTo("held");
        JsonNode mine = call("GET", "/api/v1/changes?mine=true&kind=trade&entity=EN-1", tia, null, 200).path("changes").get(0);
        String id = mine.path("id").asText();
        assertThat(mine.path("state").asText()).isEqualTo("awaiting");
        assertThat(topic()).as("nothing is written before the approval").hasSize(1);
        assertThat(call("POST", "/api/v1/approvals/change/" + id + "/approve", tia, Map.of(), 403).path("code").asText()).isNotBlank();
        call("POST", "/api/v1/approvals/change/" + id + "/approve", kim, Map.of("comment", "checked"), 200);
        waitFor(id, "applied", kim);
        List<Map<String, Object>> after = topic();
        assertThat(after).as("exactly one new version written to Endur's own topic").hasSize(2);
        assertThat(after.get(1)).containsEntry("PORTFOLIO", "BOOK-B").containsEntry("TRADE_VERSION", 2).containsKey("DRISHTI_CHANGE_ID");

        // made on version 1 after version 2 was written: refused, naming who made version 2
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String refused = "";
        while (System.nanoTime() < until) {
            var r = mvc.perform(post("/api/v1/rdp/tables/trade/write").header("Authorization", tia)   // a trader (the checker holds no edit power)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("id", "01JKAFKAEDIT0000000000002", "op", "update", "key", List.of("EN-1"), "basedOn", 1,
                            "reason", "late", "set", Map.of("PRICE", 11), "from", Map.of("PRICE", 10.5))))).andReturn().getResponse();
            if (r.getStatus() == 409) {
                refused = r.getContentAsString(StandardCharsets.UTF_8);
                break;
            }
            Thread.sleep(250);
        }
        assertThat(refused).contains("rdp.write.conflict").contains(String.valueOf(after.get(1).get("UPDATED_BY")));
        assertThat(topic()).as("a refused correction writes nothing").hasSize(2);
    }
}
