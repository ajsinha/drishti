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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Intraday updates from the PV topic (VAR_GUIDE.md section 8.4, Kafka), over the in-process Kafka the Kafka plugin's tests use (no
 * Docker, no .exe): each message's value JSONL text, one or more records; the {@code var-pv-stream} connector collects a window, keeps
 * the latest record per trade, appends the window to the lake as one intraday batch and announces it; the intraday layer applies it as it
 * applies a file. A message of several records (the latest of a trade winning, a blank line skipped); a message mixing trades, a cancel
 * line and a malformed line (rejected with its place, the others applied); a tombstone ignored; an early flush at {@code max-trades};
 * "apply now" through the API; the guards (another date, before the morning run, another scenario set, a failed scenario, a
 * redelivery); a restart replaying the topic without applying anything again; file batches and Kafka windows mixed on one date; and the
 * result equal, to the cent, to the same final data
 * loaded as one run.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.kinds[4]=scenario-pv", "drishti.sources.connectors.risk-lake.kinds[5]=fx-scenario",
        "drishti.sources.connectors.risk-lake.kinds[6]=fx-spot",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,RUN_KIND,COMPUTED_AT",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,BASE_PV,VALUATION_DATE,BASE_VALUATION_DATE,TRADE_STATUS,"
                + "MATURITY_DATE,STATUS,SCENARIO_SET_ID,BATCH_ID,RUN_KIND,COMPUTED_AT",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.vectors=SCENARIO_PV",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.connectors.pv-updates.plugin=var-pv-stream", "drishti.sources.connectors.pv-updates.kinds[0]=pv-update",
        "drishti.sources.connectors.pv-updates.settings.topic=pv-updates", "drishti.sources.connectors.pv-updates.settings.poll-ms=50",
        "drishti.var.intraday.kafka.window=10m", "drishti.var.intraday.kafka.max-trades=3",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=EUR",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarPvStreamTest {

    /** The date the topic updates (the newest with trades). */
    static final LocalDate DAY = VarLakeFixture.DAY;
    /** The same final data loaded as one run. */
    static final LocalDate ONE_RUN = DAY.minusDays(1);
    static Path lake;
    static EmbeddedKafkaKraftBroker broker;
    static KafkaProducer<String, String> producer;
    static final List<Object[]> SENT = new ArrayList<>();
    static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void setUp(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-pv-stream-lake-" + System.nanoTime())).toAbsolutePath();
        VarIntradayLake.morning(lake, ONE_RUN, DAY);
        List<Map<String, Object>> v = new ArrayList<>();                   // ONE_RUN: the day's final state as one run
        for (Map<String, Object> row : VarIntradayLake.morningVectors(ONE_RUN)) {
            if (row.get("TRADE_ID").equals("T1")) {
                row.put("QUANTITY", 150.0);
                row.put("PNL", VarLakeFixture.scale(VarLakeFixture.U, 150));
            }
            if (row.get("TRADE_ID").equals("T2")) {
                row.put("TRADE_STATUS", "CANCELLED");
            }
            v.add(row);
        }
        v.add(VarIntradayLake.vector(ONE_RUN, "N1", "BOOK-3", "DESK-Y", "LE-B", "USD", -30, VarLakeFixture.scale(VarLakeFixture.V, -30), "NEW",
                "FULL", "B-" + ONE_RUN + "-01", ONE_RUN + VarIntradayLake.MORNING_AT));
        VarIntradayLake.writeVectors(lake, v, "overwrite-dates");
        VarIntradayLake.writePvs(lake, List.of(
                VarIntradayLake.pv(ONE_RUN, "P1", "EUR", 1200, VarPvFixture.plus(1200, VarLakeFixture.U, 30), "FULL", "B-" + ONE_RUN + "-01",
                        ONE_RUN + VarIntradayLake.MORNING_AT, "HS-20"),
                VarIntradayLake.morningPvs(ONE_RUN).get(1),
                VarIntradayLake.pv(ONE_RUN, "NP", "CAD", 500, VarPvFixture.plus(500, VarLakeFixture.V, 3), "FULL", "B-" + ONE_RUN + "-01",
                        ONE_RUN + VarIntradayLake.MORNING_AT, "HS-20")), "overwrite-dates");
        broker = new EmbeddedKafkaKraftBroker(1, 2, "pv-updates");
        broker.afterPropertiesSet();
        Properties p = new Properties();
        p.put("bootstrap.servers", broker.getBrokersAsString());
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        producer = new KafkaProducer<>(p);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
        r.add("drishti.sources.connectors.pv-updates.settings.bootstrap-servers", broker::getBrokersAsString);
        r.add("drishti.sources.connectors.pv-updates.settings.lake", lake::toString);
    }

    @AfterAll
    static void tearDown() {
        producer.close(Duration.ofSeconds(2));
        broker.destroy();
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired VarOfficial official;

    private String token(String role) {
        return "Bearer " + tokens.mint("rita", List.of(role), 300);
    }

    private JsonNode ask(String url, String scope, LocalDate day, String ccy) throws Exception {
        var req = get(url).param("scope", scope).param("asOf", day.toString()).header("Authorization", token("risk"));
        if (ccy != null) {
            req = req.param("ccy", ccy);
        }
        return JSON.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** One message: its value JSONL text, one record a line (a String line is sent as it is: a malformed line); the key names no trade. */
    static void send(String key, Object... records) throws Exception {
        StringBuilder v = new StringBuilder();
        for (Object r : records) {
            v.append(r instanceof String t ? t : JSON.writeValueAsString(r)).append('\n');
        }
        producer.send(new ProducerRecord<>("pv-updates", key, v.toString())).get(10, TimeUnit.SECONDS);
        SENT.add(new Object[] {key, v.toString()});
    }

    static void tombstone(String key) throws Exception {
        producer.send(new ProducerRecord<>("pv-updates", key, null)).get(10, TimeUnit.SECONDS);
    }

    static VarPvStreamPlugin stream() {
        return VarPvStreamPlugin.RUNNING.get("pv-updates");
    }

    static void waitFor(BooleanSupplier ok, int seconds) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!ok.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(50);
        }
        assertThat(ok.getAsBoolean()).as("waited " + seconds + " s").isTrue();
    }

    private long count(String k) {
        return ((Number) stream().describe().get(k)).longValue();
    }

    private JsonNode applyNow() throws Exception {
        return JSON.readTree(mvc.perform(post("/api/v1/var/intraday/streams/pv-updates/apply").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> vec(String id, double qty, double[] pnl, String at) {
        String book = id.equals("N1") ? "BOOK-3" : "BOOK-1";
        return VarIntradayLake.vector(DAY, id, book, id.equals("N1") ? "DESK-Y" : "DESK-X", id.equals("N1") ? "LE-B" : "LE-A", "USD", qty, pnl,
                id.equals("N1") ? "NEW" : "AMENDED", "INTRADAY", "ignored", DAY + at);
    }

    @Test
    @Order(1)
    void aMessageOfSeveralRecordsTheLatestOfEachTradeWinning() throws Exception {
        assertThat(svc.props().intraday().kafka().maxTrades()).isEqualTo(3);                      // drishti.var.intraday.kafka
        double morning = ask("/api/v1/var/scope", "BANK", DAY, null).get("var").asDouble();
        assertThat(morning).isPositive();
        ask("/api/v1/var/scope", "BANK", DAY, "EUR");
        waitFor(() -> stream() != null && String.valueOf(stream().describe().get("state")).startsWith("reading"), 60);
        send("batch-7", vec("T1", 150, VarLakeFixture.scale(VarLakeFixture.U, 150), "T10:00:00Z"),    // one message, three records:
                vec("T1", 120, VarLakeFixture.scale(VarLakeFixture.U, 120), "T09:30:00Z"),               // T1's latest COMPUTED_AT wins
                "",                                                                                        // a blank line: skipped
                vec("N1", -30, VarLakeFixture.scale(VarLakeFixture.V, -30), "T10:00:00Z"));
        waitFor(() -> ((Number) stream().describe().get("waiting")).intValue() == 2, 30);
        JsonNode r = applyNow();                                                                    // "apply now", through the API
        assertThat(r.get("status").asText()).as(r.toString()).isEqualTo("applied");
        assertThat(r.get("rows").asInt()).isEqualTo(2);
        assertThat(r.get("why").asText()).isEqualTo("apply now");
        waitFor(() -> svc.date(DAY).book().intraday() != null && svc.date(DAY).book().intraday().info().trades() == 2, 30);
        JsonNode t1 = ask("/api/v1/var/scope", "TRD:T1", DAY, null);
        assertThat(t1.get("var").asDouble()).isEqualTo(1500);                                    // 150 x the worst unit move of 10
        assertThat(t1.at("/intraday/batches/0").asText()).startsWith("K-" + DAY);
    }

    @Test
    @Order(2)
    void aFileBatchBetweenWindowsThenAMessageMixingTradesACancelAndAMalformedLine() throws Exception {
        String before = svc.loader().version(DAY);                                                  // a file batch, between two windows
        VarIntradayLake.writePvs(lake, List.of(VarIntradayLake.pv(DAY, "P1", "EUR", 1100, VarPvFixture.plus(1100, VarLakeFixture.U, 20), "INTRADAY",
                "F-1", DAY + "T10:30:00Z", "HS-20")), "append");
        waitFor(() -> !svc.loader().version(DAY).equals(before), 20);
        official.onLoad("scenario-pv", DAY);
        long batches = count("batches");
        long rejected = count("rejected");
        tombstone("T2");                                                                             // a tombstone: no record, ignored
        Map<String, Object> cancel = vec("T2", -40, null, "T11:00:00Z");                             // a cancel line, as in the files
        cancel.put("TRADE_STATUS", "CANCELLED");
        send(null, VarIntradayLake.pv(DAY, "NP", "CAD", 500, VarPvFixture.plus(500, VarLakeFixture.V, 3), "INTRADAY", "x", DAY + "T11:00:00Z",
                "HS-20"),
                "{\"TRADE_ID\": \"T9\", \"PNL\": [1, 2,",                                              // malformed: rejected, the rest applied
                cancel,
                VarIntradayLake.pv(DAY, "P1", "EUR", 1200, VarPvFixture.plus(1200, VarLakeFixture.U, 30), "INTRADAY", "x", DAY + "T11:00:00Z",
                        "HS-20"));                                                                  // three trades: max-trades, an early flush
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (count("batches") != batches + 1 && System.nanoTime() < until) {
            Thread.sleep(50);
        }
        assertThat(count("batches")).as(String.valueOf(stream().describe())).isEqualTo(batches + 1);
        assertThat(count("rejected")).isEqualTo(rejected + 1);
        assertThat(count("tombstonesIgnored")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<String> bad = (List<String>) stream().describe().get("rejections");
        assertThat(bad.get(bad.size() - 1)).startsWith("DRS-5201 p").contains("line 2");
        waitFor(() -> svc.date(DAY).book().intraday().info().trades() == 5, 30);
        assertThat(ask("/api/v1/var/scope", "TRD:T2", DAY, null).at("/provenance/unknown").toString()).contains("T2");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> w = (List<Map<String, Object>>) stream().describe().get("lastWindows");
        assertThat(w.get(w.size() - 1).get("why")).isEqualTo("max-trades");
    }

    @Test
    @Order(3)
    void theGuardsIgnoreOrRefuseAndNeverFillWithZero() throws Exception {
        long other = count("otherDate");
        long early = count("beforeMorning");
        long same = count("notNewer");
        long rejected = count("rejected");
        Map<String, Object> otherDay = vec("T3", 1, VarLakeFixture.scale(VarLakeFixture.U, 1), "T12:00:00Z");
        otherDay.put("BUSINESS_DATE", ONE_RUN.toString());
        send("T3", otherDay);
        send("T5", vec("T5", 1, VarLakeFixture.scale(VarLakeFixture.V, 1), "T05:00:00Z"));       // before the morning run (06:00)
        double[] nan = VarLakeFixture.scale(VarLakeFixture.U, 1);
        List<Object> withNull = new ArrayList<>();
        for (double x : nan) {
            withNull.add(x);
        }
        withNull.set(3, null);
        Map<String, Object> failed = vec("T4", 1, null, "T12:00:00Z");
        failed.put("PNL", withNull);
        send("T4", failed);
        send("P2", VarIntradayLake.pv(DAY, "P2", "CAD", 7777, VarPvFixture.plus(7777, VarLakeFixture.V, 1), "INTRADAY", "x", DAY + "T12:00:00Z",
                "HS-99"));
        send("T1", vec("T1", 150, VarLakeFixture.scale(VarLakeFixture.U, 150), "T10:00:00Z"));    // a redelivery: not newer
        // three keys flush early (max-trades), the rest when asked: ask until every message has been through
        waitFor(() -> {
            stream().applyNow();
            stream().flushIfDue();
            return count("otherDate") == other + 1 && count("beforeMorning") == early + 1 && count("notNewer") == same + 1
                    && count("rejected") == rejected + 2;
        }, 30);
        @SuppressWarnings("unchecked")
        List<String> why = (List<String>) stream().describe().get("rejections");
        assertThat(String.join("\n", why)).contains("DRS-5206 T4 (p").contains("DRS-5203 P2 (p");
        assertThat(ask("/api/v1/var/scope", "BANK", DAY, null).at("/provenance/intradayRejections").toString()).contains("DRS-5206 T4")
                .contains("DRS-5203 P2").contains("DRS-5201 p");
        assertThat(svc.date(DAY).book().intraday().info().trades()).isEqualTo(5);                  // nothing of these applied
    }

    @Test
    @Order(4)
    void filesAndWindowsMixedEqualTheFinalDataLoadedAsOneRunToTheCent() throws Exception {
        VarDate one = svc.date(ONE_RUN);
        for (String ccy : new String[] {null, "EUR", "CAD"}) {
            for (String node : one.book().nodes().keySet()) {
                JsonNode a = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, DAY, ccy);
                JsonNode e = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, ONE_RUN, ccy);
                assertThat(a.get("series").toString()).as(node + " " + ccy).isEqualTo(e.get("series").toString());
                assertThat(a.get("var").asDouble()).as(node + " " + ccy).isEqualTo(e.get("var").asDouble());
                assertThat(a.get("es").asDouble()).as(node + " " + ccy).isEqualTo(e.get("es").asDouble());
            }
        }
        VarService.Scoped a = svc.scoped(null, "NODE:MGMT~BANK", DAY, null);
        VarService.Scoped e = svc.scoped(null, "NODE:MGMT~BANK", ONE_RUN, null);
        for (int j = 0; j < a.vector().length; j++) {
            assertThat(a.vector()[j]).isCloseTo(e.vector()[j], within(0.005));
        }
    }

    private static long parquets(String kind) throws java.io.IOException {
        try (java.util.stream.Stream<Path> w = Files.walk(lake.resolve("risk").resolve(kind))) {
            return w.filter(f -> f.toString().endsWith(".parquet")).count();
        }
    }

    @Test
    @Order(5)
    void aRestartReplaysTheTopicAndAppliesNothingAgain() throws Exception {
        long rows = parquets("pnl-vector") + parquets("scenario-pv");
        double before = svc.scoped(null, "NODE:MGMT~BANK", DAY, null).measures().var();
        svc.reload(DAY);                                                                            // the server again: the lake rebuilds the day
        assertThat(svc.scoped(null, "NODE:MGMT~BANK", DAY, null).measures().var()).isCloseTo(before, within(0.005));
        // a new stream reads the topic from the beginning: every message is already in the lake, nothing is written again
        VarPvStream again = new VarPvStream(svc, lake, (k, w) -> VarConfiguration.layout(null, k, w), (k, d, b, n) -> official.onLoad(k, d));
        VarPvWindow w = new VarPvWindow(Duration.ofMinutes(5), 1000);
        long off = 0;
        for (Object[] s : SENT) {
            for (VarPvWindow.Msg m : VarPvStreamPlugin.records(new org.apache.kafka.clients.consumer.ConsumerRecord<>("pv-updates", 0, off++,
                    (String) s[0], (String) s[1]))) {
                if (m.bad() == null) {
                    w.add(m, Instant.now());
                }
            }
        }
        Map<String, Object> r = again.apply(w.drain(), "replay");
        assertThat(r.get("status")).as(r.toString()).isEqualTo("nothing new");
        long after = parquets("pnl-vector") + parquets("scenario-pv");
        assertThat(after).isEqualTo(rows);
        JsonNode st = JSON.readTree(mvc.perform(get("/api/v1/var/intraday/streams").header("Authorization", token("risk")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(st.get(0).get("topic").asText()).isEqualTo("pv-updates");
        Map<String, Object> once = new LinkedHashMap<>(again.status());
        assertThat(((Number) once.get("applied")).longValue()).isZero();
    }
}
