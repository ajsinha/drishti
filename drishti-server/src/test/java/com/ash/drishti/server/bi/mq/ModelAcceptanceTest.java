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
package com.ash.drishti.server.bi.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The wave W1 acceptance (docs/architecture/BI_MODEL_SPEC.md section 10.4, criterion 1): <em>a report, an alert and an Analyse slice ask the same model
 * through the same query language and get identical, per-reader masked answers</em>, over the whole stack with security on and a small lake read through a
 * real file connector. Three readers: rita (every desk, raw), hana (a desk-head: DESK-A's rows only) and ann (every desk, no raw: {@code mtm} is masked).
 * Also: a standing query's answer after the data changes equals the one-shot answer (section 5.8), and a dataset whose source is another model's answer
 * (section 1.2 D) is asked as the reader, with a cycle refused ({@code DRS-9109}).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/mq-accept-${random.uuid}/identity.db", "drishti.packs.overlay=target/mq-accept-overlay/added.yaml",
        "drishti.bi.dir=target/mq-accept-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/mq-accept-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=target/test-data/mq-accept-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.acc-lake.plugin=file", "drishti.sources.connectors.acc-lake.kinds[0]=trade",
        "drishti.sources.connectors.acc-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.acc-lake.settings.layout.trade.columns=desk,book,currency,notional,mtm,tradeDate"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModelAcceptanceTest {

    static final String DS = "file:dataset:acc-trades";
    static final String REPORT = "file:report:acc-report";
    static final String DAY = "2026-10-08";
    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired StandingQueries standing;
    final ObjectMapper json = new ObjectMapper();

    static void day(String day, double scale) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String desk = i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C";
            lines.add("{\"id\":\"T-" + day.substring(8) + "-" + i + "\",\"tradeId\":\"T-" + day.substring(8) + "-" + i + "\",\"desk\":\"" + desk + "\",\"book\":\"BOOK-" + (i % 3 + 1)
                    + "\",\"currency\":\"USD\",\"notional\":" + 1000.0 * (i + 1) * scale + ",\"mtm\":" + ((i % 5) - 2) * 100.0 + ",\"tradeDate\":\"" + day + "\"}");
        }
        Path f = root.resolve("lake").resolve(day).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = Files.createTempDirectory("drishti-mq-accept-lake");
        day("2026-10-07", 1);
        day(DAY, 1);
        r.add("drishti.sources.connectors.acc-lake.settings.root", () -> root.resolve("lake").toString());
    }

    static {
        try {
            Path folder = Path.of("target/mq-accept-folder");
            Path datasets = Files.createDirectories(folder.resolve("datasets"));
            Path reports = Files.createDirectories(folder.resolve("reports"));
            Path models = Files.createDirectories(folder.resolve("models"));
            Files.writeString(datasets.resolve("acc-trades.dataset.yaml"), """
                    rachana: 1
                    dataset: acc-trades
                    title: Acceptance trades
                    tables:
                      trades:
                        source: { connector: acc-lake, kind: trade }
                        columns:
                          tradeId: { type: string, key: true }
                          desk: string
                          book: string
                          currency: string
                          notional: decimal
                          mtm: decimal
                          tradeDate: date
                    measures:
                      Notional: { expr: "sum(trades.notional)" }
                      MTM: { expr: "sum(trades.mtm)" }
                      Trades: { expr: "count(trades)" }
                    security:
                      rows:
                        - { role: desk-head, where: "trades.desk in user.desks" }
                        - { role: bi-risk, where: "true" }
                        - { role: bi-analyst, where: "true" }
                        - { role: admin, where: "true" }
                      masked: [mtm]
                    """);
            Files.writeString(reports.resolve("acc-report.report.yaml"), """
                    report: acc-report
                    title: Acceptance report
                    dataset: acc-trades
                    pages:
                      - id: main
                        title: Main
                        visuals:
                          - { id: total, type: card, title: Notional, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Notional] } }
                          - { id: mtm, type: card, title: MTM, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }
                          - { id: by-desk, type: bar, title: By desk, at: { x: 0, y: 2, w: 6, h: 4 }, wells: { axis: [trades.desk], values: [Notional, MTM] } }
                    """);
            // a model of the model layer over the same lake, and a dataset whose source is that model's answer (wave W1 package S4)
            Files.writeString(datasets.resolve("acc-trade.dataset.yaml"), """
                    rachana: 2
                    dataset: acc-trade
                    version: 1.0.0
                    source: { connector: acc-lake, kind: trade }
                    grain: { keys: [id] }
                    columns:
                      desk:     { type: string }
                      notional: { type: decimal, aggregate: sum }
                      mtm:      { type: decimal, aggregate: sum }
                    security:
                      rows:
                        - { role: desk-head, where: "desk in user.desks" }
                        - { role: [bi-risk, bi-analyst, admin], where: "true" }
                      masked: [mtm]
                    """);
            Files.writeString(models.resolve("acc-model.model.yaml"), """
                    rachana: 2
                    model: acc-model
                    version: 1.0.0
                    datasets:
                      trades: { dataset: acc-trade, role: fact }
                    measures:
                      Notional: { expr: "sum(trades.notional)" }
                      Trades:   { expr: "count(trades)" }
                    """);
            Files.writeString(datasets.resolve("desk-book.dataset.yaml"), """
                    rachana: 2
                    dataset: desk-book
                    version: 1.0.0
                    source:
                      model: acc-model@1
                      query: { select: { fields: [trades.desk], measures: [Notional, Trades] } }
                    grain: { keys: [desk] }
                    columns:
                      desk:     { type: string, path: trades.desk, role: key }
                      notional: { type: decimal, path: Notional, aggregate: sum }
                      trades:   { type: integer, path: Trades, aggregate: sum }
                    """);
            Files.writeString(models.resolve("desk-view.model.yaml"), """
                    rachana: 2
                    model: desk-view
                    version: 1.0.0
                    datasets:
                      books: { dataset: desk-book, role: fact }
                    measures:
                      Book notional: { expr: "sum(books.notional)" }
                      Book trades:   { expr: "sum(books.trades)" }
                    """);
            // a cycle: a dataset read from a model that holds that dataset
            Files.writeString(datasets.resolve("loop-a.dataset.yaml"), """
                    rachana: 2
                    dataset: loop-a
                    version: 1.0.0
                    source:
                      model: loop-model@1
                      query: { select: { fields: [a.desk], measures: [Total] } }
                    grain: { keys: [desk] }
                    columns:
                      desk:  { type: string, role: key }
                      total: { type: decimal, path: Total, aggregate: sum }
                    """);
            Files.writeString(models.resolve("loop-model.model.yaml"), """
                    rachana: 2
                    model: loop-model
                    version: 1.0.0
                    datasets:
                      a: { dataset: loop-a, role: fact }
                    measures:
                      Total: { expr: "sum(a.total)" }
                    """);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @AfterAll
    void cleanUp() throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    String admin() {
        return as("drishti-dev-admin", "admin");
    }

    String reader(String name) {
        return switch (name) {
            case "rita" -> as("rita", "bi-risk");
            case "hana" -> as("hana", "desk-head");
            default -> as("ann", "bi-analyst");
        };
    }

    @BeforeAll
    void setUp() throws Exception {
        role("bi-risk", true);
        role("desk-head", true);
        role("bi-analyst", false);
        for (String u : List.of("rita:bi-risk:", "hana:desk-head:DESK-A", "ann:bi-analyst:")) {
            String[] n = u.split(":", -1);
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "desk", n[2], "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
    }

    void role(String name, boolean raw) throws Exception {
        var r = mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "raw", raw, "bi", List.of("bi"))))).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    ResultActions call(String method, String path, String who, Object body) throws Exception {
        var req = "POST".equals(method) ? post(java.net.URI.create(path)) : get(java.net.URI.create(path));
        req.header("Authorization", who);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    JsonNode ok(ResultActions r) throws Exception {
        var res = r.andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isBetween(200, 299);
        return json.readTree(res.getContentAsString());
    }

    /** Desk to [Notional, MTM] (MTM null when masked) of an answer whose columns are desk, Notional, MTM. */
    static Map<String, List<Object>> byDesk(JsonNode answer) {
        boolean mtmMasked = answer.path("meta").path("masked").toString().contains("MTM");
        Map<String, List<Object>> out = new TreeMap<>();
        for (JsonNode row : answer.path("rows")) {
            List<Object> v = new ArrayList<>();
            v.add(row.get(1).asDouble());
            v.add(mtmMasked || row.get(2).isNull() ? null : row.get(2).asDouble());
            out.put(row.get(0).asText(), v);
        }
        return out;
    }

    JsonNode reportVisual(String who, String visual) throws Exception {
        return ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", reader(who),
                "{\"page\":\"main\",\"visual\":\"" + visual + "\",\"state\":{\"dates\":{\"from\":\"" + DAY + "\",\"to\":\"" + DAY + "\"}}}"));
    }

    JsonNode alertPreview(String who, String source) throws Exception {
        return ok(call("POST", "/api/v1/me/measure-alerts/preview", reader(who), "{\"title\":\"t\",\"source\":" + source
                + ",\"condition\":{\"type\":\"threshold\",\"op\":\"above\",\"value\":0},\"trigger\":{\"mode\":\"schedule\",\"schedule\":\"hourly\"}}"));
    }

    // ---- the acceptance ----------------------------------------------------------------------------------------------

    @Test @Order(1)
    void aReportAnAlertAndAnAnalyseSliceAskTheSameModelAndGetTheSameMaskedAnswersPerReader() throws Exception {
        Map<String, Map<String, List<Object>>> seen = new TreeMap<>();
        for (String who : List.of("rita", "hana", "ann")) {
            // the report visual: its model query is the one the other two send
            JsonNode visual = reportVisual(who, "by-desk");
            assertThat(visual.path("mq").path("model").asText()).as("the visual ran as a model query").isEqualTo(DS);
            assertThat(visual.path("mq").path("select").path("measures").toString()).contains("Notional").contains("MTM");
            Map<String, List<Object>> fromReport = byDesk(visual);

            // Analyse: the extract-and-slice path (the dataset query, translated) and the slice as a model query of its own
            JsonNode extract = ok(call("POST", "/api/v1/bi/datasets/" + DS + "/query", reader(who),
                    "{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\",\"MTM\"],\"dates\":{\"from\":\"" + DAY + "\"}}"));
            JsonNode slice = ok(call("POST", "/api/v1/bi/mq", reader(who), Map.of("mq", 1, "model", "acc-trades", "select",
                    Map.of("fields", List.of("trades.desk"), "measures", List.of("Notional", "MTM")), "time", Map.of("as-of", DAY))));
            assertThat(byDesk(extract)).as(who + ": extract = report").isEqualTo(fromReport);
            assertThat(byDesk(slice)).as(who + ": slice = report").isEqualTo(fromReport);
            assertThat(slice.path("columns").get(2).path("masked").asBoolean()).isEqualTo("ann".equals(who));

            // the alert: on the visual and on the model, the same number
            double total = fromReport.values().stream().mapToDouble(v -> (Double) v.get(0)).sum();
            JsonNode onVisual = alertPreview(who, "{\"type\":\"visual\",\"report\":\"" + REPORT + "\",\"visual\":\"total\",\"state\":{\"dates\":{\"from\":\"" + DAY + "\",\"to\":\"" + DAY + "\"}}}");
            JsonNode onModel = alertPreview(who, "{\"type\":\"model\",\"model\":\"acc-trades\",\"query\":{\"select\":{\"measures\":[\"Notional\"]},\"time\":{\"as-of\":\"" + DAY + "\"}}}");
            assertThat(onVisual.path("status").asText()).isEqualTo("ok");
            assertThat(onModel.path("status").asText()).isEqualTo("ok");
            assertThat(onVisual.path("value").asDouble()).isEqualTo(total);
            assertThat(onModel.path("value").asDouble()).isEqualTo(total);
            // the masked measure: masked in all three for ann, a number equal in all three for the others
            JsonNode mtmVisual = alertPreview(who, "{\"type\":\"visual\",\"report\":\"" + REPORT + "\",\"visual\":\"mtm\",\"state\":{\"dates\":{\"from\":\"" + DAY + "\",\"to\":\"" + DAY + "\"}}}");
            JsonNode mtmModel = alertPreview(who, "{\"type\":\"model\",\"model\":\"acc-trades\",\"query\":{\"select\":{\"measures\":[\"MTM\"]},\"time\":{\"as-of\":\"" + DAY + "\"}}}");
            if ("ann".equals(who)) {
                assertThat(mtmVisual.path("status").asText()).isEqualTo("masked");
                assertThat(mtmModel.path("status").asText()).isEqualTo("masked");
                assertThat(fromReport.values()).allMatch(v -> v.get(1) == null);
            } else {
                double mtm = fromReport.values().stream().mapToDouble(v -> (Double) v.get(1)).sum();
                assertThat(mtmVisual.path("value").asDouble()).isEqualTo(mtm);
                assertThat(mtmModel.path("value").asDouble()).isEqualTo(mtm);
            }
            seen.put(who, fromReport);
        }
        // and the readers differ as their rules say: hana sees DESK-A only, ann sees rita's notional without the MTM
        assertThat(seen.get("hana").keySet()).containsExactly("DESK-A");
        assertThat(seen.get("hana").get("DESK-A")).isEqualTo(seen.get("rita").get("DESK-A"));
        assertThat(seen.get("rita").keySet()).containsExactly("DESK-A", "DESK-B", "DESK-C");
        assertThat(seen.get("ann").keySet()).isEqualTo(seen.get("rita").keySet());
        seen.get("rita").forEach((desk, v) -> assertThat(seen.get("ann").get(desk).get(0)).isEqualTo(v.get(0)));
    }

    @Test @Order(2)
    void aConsumerRefusalKeepsItsEndpointsCodeAndCarriesTheModelQueryProblem() throws Exception {
        call("POST", "/api/v1/bi/datasets/" + DS + "/query", reader("ann"), "{\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.mtm\",\"op\":\"gt\",\"values\":[0]}]}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-9021")).andExpect(jsonPath("$.problems[0].code").value("DRS-9161"));
        call("POST", "/api/v1/bi/datasets/" + DS + "/query", reader("rita"), "{\"fields\":[\"trades.nope\"],\"measures\":[\"Trades\"]}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-9020")).andExpect(jsonPath("$.problems[0].code").value("DRS-9152"));
    }

    // ---- standing queries --------------------------------------------------------------------------------------------

    @Test @Order(3)
    void aStandingQueryAfterTheDataChangesEqualsTheOneShotAnswer() throws Exception {
        Map<String, Object> q = Map.of("mq", 1, "model", "acc-trades", "select", Map.of("fields", List.of("trades.desk"), "measures", List.of("Notional", "Trades")),
                "sort", List.of(Map.of("by", "trades.desk")));
        Map<String, String> subs = new TreeMap<>();
        Map<String, JsonNode> before = new TreeMap<>();
        for (String who : List.of("rita", "hana")) {
            JsonNode sub = ok(call("POST", "/api/v1/bi/mq/subscribe", reader(who), Map.of("query", q, "throttle", "100ms")));
            before.put(who, ok(call("POST", "/api/v1/bi/mq", reader(who), q)).path("rows"));
            assertThat(sub.path("answer").path("rows")).as(who + ": the first answer is the one-shot answer").isEqualTo(before.get(who));
            subs.put(who, sub.path("subscription").asText());
        }
        // the lake is reloaded for the newest date with twice the notional, and the load is announced
        day(DAY, 2);
        call("POST", "/api/v1/packs/finance/loads", admin(), Map.of("kind", "trade", "businessDate", DAY, "status", "ready", "rows", 24, "batchId", "mq-accept-" + java.util.UUID.randomUUID()))
                .andExpect(status().is2xxSuccessful());
        for (String who : List.of("rita", "hana")) {
            Object streamed = null;
            for (int i = 0; i < 100 && streamed == null; i++) {
                Thread.sleep(150);
                for (StandingQueries.Event e : standing.poll(List.of(subs.get(who)))) {
                    if ("answer".equals(e.name())) {
                        streamed = e.data().get("rows");
                    }
                }
            }
            assertThat(streamed).as(who + ": the subscription delivered after the change").isNotNull();
            JsonNode now = ok(call("POST", "/api/v1/bi/mq", reader(who), q)).path("rows");
            assertThat(json.writeValueAsString(streamed)).as(who + ": standing = one-shot after the change").isEqualTo(json.writeValueAsString(now));
            assertThat(now).as(who + ": the change reached the answer").isNotEqualTo(before.get(who));
        }
    }

    // ---- a dataset whose source is a model ---------------------------------------------------------------------------

    @Test @Order(4)
    void aDatasetReadFromAModelAsksThatModelAsTheReaderAndACycleIsRefused() throws Exception {
        for (String file : List.of("datasets/desk-book.dataset.yaml", "models/desk-view.model.yaml")) {
            JsonNode checked = ok(call("POST", "/api/v1/bi/models/validate", as("wes", "admin"), Map.of("yaml", Files.readString(Path.of("target/mq-accept-folder").resolve(file)))));
            assertThat(checked.path("valid").asBoolean()).as(file + ": " + checked.path("problems")).isTrue();
        }
        Map<String, Object> q = Map.of("mq", 1, "model", "desk-view@1", "select", Map.of("fields", List.of("books.desk"), "measures", List.of("Book notional", "Book trades")),
                "time", Map.of("as-of", "2026-10-07"));
        JsonNode rita = ok(call("POST", "/api/v1/bi/mq", reader("rita"), q));
        JsonNode hana = ok(call("POST", "/api/v1/bi/mq", reader("hana"), q));
        JsonNode inner = ok(call("POST", "/api/v1/bi/mq", reader("rita"), Map.of("mq", 1, "model", "acc-model@1", "select",
                Map.of("fields", List.of("trades.desk"), "measures", List.of("Notional", "Trades")), "time", Map.of("as-of", "2026-10-07"))));
        Map<String, Double> outer = new TreeMap<>();
        rita.path("rows").forEach(r -> outer.put(r.get(0).asText(), r.get(1).asDouble()));
        Map<String, Double> direct = new TreeMap<>();
        inner.path("rows").forEach(r -> direct.put(r.get(0).asText(), r.get(1).asDouble()));
        assertThat(outer).hasSize(3).isEqualTo(direct);
        assertThat(rita.path("rows").get(0).get(2).asInt()).isEqualTo(8);
        // the inner query ran as hana: her desk only
        assertThat(hana.path("rows")).hasSize(1);
        assertThat(hana.path("rows").get(0).get(0).asText()).isEqualTo("DESK-A");
        assertThat(hana.path("rows").get(0).get(1).asDouble()).isEqualTo(direct.get("DESK-A"));
        // the cycle: refused when asked, and by the Check of the dataset
        call("POST", "/api/v1/bi/mq", reader("rita"), Map.of("mq", 1, "model", "loop-model@1", "select", Map.of("measures", List.of("Total"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-9109"));
        JsonNode check = ok(call("POST", "/api/v1/bi/models/validate", as("wes", "admin"),
                Map.of("yaml", Files.readString(Path.of("target/mq-accept-folder/datasets/loop-a.dataset.yaml")))));
        assertThat(check.path("valid").asBoolean()).isFalse();
        assertThat(check.path("problems").toString()).contains("DRS-9109");
    }
}
