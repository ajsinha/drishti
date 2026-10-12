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
package com.ash.drishti.server.bi.report;

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
 * Rupaka phase 3 over the whole stack, security on, over a small generated lake read through a real file connector: a shipped report on a shipped dataset
 * is read, each kind of visual query runs on the engine as three different people (rows and masks), slicers, cross-filter, highlight, drill-down, top-N
 * and "show the rows" give the right numbers, the designer's endpoints check and write YAML, and the refusals carry their DRS codes.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/bi-report-${random.uuid}/identity.db", "drishti.packs.overlay=target/bi-report-overlay/added.yaml",
        "drishti.bi.dir=target/bi-report-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/bi-report-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=target/test-data/bi-report-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.rep-lake.plugin=file", "drishti.sources.connectors.rep-lake.kinds[0]=trade",
        "drishti.sources.connectors.rep-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.rep-lake.settings.layout.trade.columns=desk,book,currency,notional,mtm,tradeDate"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BiReportApiTest {

    static final String REPORT = "file:report:lake-report";
    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    static String desk(int i) {
        return i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C";
    }

    static void day(String day) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            lines.add("{\"id\":\"T-" + day.substring(8) + "-" + i + "\",\"tradeId\":\"T-" + day.substring(8) + "-" + i + "\",\"desk\":\"" + desk(i) + "\",\"book\":\"BOOK-" + (i % 3 + 1)
                    + "\",\"currency\":\"" + (i % 2 == 0 ? "USD" : "EUR") + "\",\"notional\":" + 1000.0 * (i + 1) + ",\"mtm\":" + ((i % 5) - 2) * 100.0 + ",\"tradeDate\":\"" + day + "\"}");
        }
        Path f = root.resolve("lake").resolve(day).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = Files.createTempDirectory("drishti-bi-report-lake");
        day("2026-10-07");
        day("2026-10-08");
        r.add("drishti.sources.connectors.rep-lake.settings.root", () -> root.resolve("lake").toString());
    }

    static {
        try {
            Path datasets = Path.of("target/bi-report-folder/datasets");
            Path reports = Path.of("target/bi-report-folder/reports");
            Files.createDirectories(datasets);
            Files.createDirectories(reports);
            Files.writeString(datasets.resolve("lake-report.dataset.yaml"), """
                    rachana: 1
                    dataset: lake-report
                    title: Lake for reports
                    tables:
                      trades:
                        source: { connector: rep-lake, kind: trade }
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
                      Average notional: { expr: "avg(trades.notional)" }
                      Largest: { expr: "max(trades.notional)" }
                      Notional ytd: { expr: "ytd(sum(trades.notional), trades.tradeDate)" }
                    hierarchies:
                      Desk and book: [trades.desk, trades.book]
                    security:
                      rows:
                        - { role: desk-head, where: "trades.desk in user.desks" }
                        - { role: bi-risk, where: "true" }
                        - { role: bi-analyst, where: "true" }
                        - { role: report-author, where: "true" }
                        - { role: admin, where: "true" }
                      masked: [mtm]
                    """);
            Files.writeString(reports.resolve("lake-report.report.yaml"), """
                    report: lake-report
                    title: Lake report
                    dataset: lake-report
                    pages:
                      - id: main
                        title: Main
                        visuals:
                          - { id: total, type: card, title: Notional, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Notional] } }
                          - { id: ccy, type: slicer, title: Currency, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { field: [trades.currency] }, format: { mode: list } }
                          - { id: by-desk, type: bar, title: Notional by desk, at: { x: 0, y: 2, w: 6, h: 4 }, wells: { axis: [trades.desk], values: [Notional] }, hierarchy: Desk and book }
                          - { id: by-ccy, type: column, title: Notional by currency, at: { x: 6, y: 2, w: 6, h: 4 }, wells: { axis: [trades.currency], values: [Notional] } }
                          - { id: top2, type: bar, title: Top 2 desks, at: { x: 0, y: 6, w: 6, h: 4 }, wells: { axis: [trades.desk], values: [Notional] },
                              filters: [{ field: trades.desk, op: top, n: 2, by: Notional }] }
                          - { id: rows, type: table, title: Trades, at: { x: 6, y: 6, w: 6, h: 4 }, wells: { columns: [trades.tradeId, trades.desk, Notional] }, limit: 5 }
                      - id: other
                        title: Other
                        visuals: []
                    """);
            Files.writeString(datasets.resolve("restricted.dataset.yaml"), Files.readString(datasets.resolve("lake-report.dataset.yaml")).replace("dataset: lake-report", "dataset: restricted")
                    .replace("title: Lake for reports", "title: Restricted\naudience: { roles: [bi-risk] }"));
            Files.writeString(reports.resolve("uses-restricted.report.yaml"), "report: uses-restricted\ndataset: restricted\npages:\n  - id: p\n    visuals:\n"
                    + "      - { id: n, type: card, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Trades] } }\n");
            Files.writeString(reports.resolve("one-bad.report.yaml"), "report: one-bad\ndataset: lake-report\npages:\n  - id: p\n    visuals:\n"
                    + "      - { id: good, type: card, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Notional] } }\n"
                    + "      - { id: bad, type: card, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [NoSuchMeasure] } }\n");
            Files.writeString(reports.resolve("broken.report.yaml"), "report: broken\ndataset: no-such-dataset\npages:\n  - { id: p, visuals: [] }\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    String admin() {
        return as("drishti-dev-admin", "admin");
    }

    String rita() {
        return as("rita", "bi-risk");
    }

    String hana() {
        return as("hana", "desk-head");
    }

    String ann() {
        return as("ann", "bi-analyst");
    }

    String wes() {
        return as("wes", "report-author");
    }

    String nobi() {
        return as("nobi", "plain");
    }

    ResultActions call(String method, String path, String who, Object body) throws Exception {
        var req = switch (method) {
            case "POST" -> post(java.net.URI.create(path));
            case "PUT" -> put(java.net.URI.create(path));
            default -> get(java.net.URI.create(path));
        };
        req.header("Authorization", who);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    void refused(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    /** One visual under a state; the lake holds 2026-10-07 and 2026-10-08, so unless the state names dates the query reads the 8th. */
    JsonNode visual(String who, String page, String visual, String state) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode st = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(state);
        if (!st.has("dates")) {
            st.putObject("dates").put("from", "2026-10-08").put("to", "2026-10-08");
        }
        return ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", who, "{\"page\":\"" + page + "\",\"visual\":\"" + visual + "\",\"state\":" + st + "}"));
    }

    static Map<String, Double> byFirst(JsonNode answer, int col) {
        Map<String, Double> out = new TreeMap<>();
        for (JsonNode row : answer.path("rows")) {
            out.put(row.get(0).asText(), row.get(col).asDouble());
        }
        return out;
    }

    static double notional(java.util.function.IntPredicate which, int days) {
        double sum = 0;
        for (int i = 0; i < 24; i++) {
            if (which.test(i)) {
                sum += 1000.0 * (i + 1);
            }
        }
        return sum;
    }

    @BeforeAll
    void setUp() throws Exception {
        role("bi-risk", List.of("bi"), true);
        role("desk-head", List.of("bi"), true);
        role("bi-analyst", List.of("bi"), false);
        role("report-author", List.of("bi", "bi-author"), false);
        role("plain", List.of(), false);
        for (String u : List.of("rita:bi-risk:", "hana:desk-head:DESK-A", "ann:bi-analyst:", "wes:report-author:", "nobi:plain:")) {
            String[] n = u.split(":", -1);
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "desk", n[2], "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
    }

    void role(String name, List<String> bi, boolean raw) throws Exception {
        var r = mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "raw", raw, "bi", bi)))).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    // ---- the tests ---------------------------------------------------------------------------------------------------

    @Test @Order(1)
    void aReportIsReadWithItsModelItsProblemsAndItsDatasetsFields() throws Exception {
        JsonNode r = ok(call("GET", "/api/v1/bi/reports/" + REPORT, rita(), null));
        assertThat(r.path("valid").asBoolean()).isTrue();
        assertThat(r.path("report").path("pages")).hasSize(2);
        assertThat(r.path("report").path("pages").get(0).path("visuals").get(2).path("hierarchy").asText()).isEqualTo("Desk and book");
        assertThat(r.path("dataset").path("tables").get(0).path("name").asText()).isEqualTo("trades");
        assertThat(r.path("dataset").path("measures")).hasSize(6);
        assertThat(r.path("dataset").has("security")).as("a reader is not told the row rules of the dataset through the report").isFalse();
        refused(call("GET", "/api/v1/bi/reports/" + REPORT, nobi(), null), 403, "DRS-9002");
        refused(call("GET", "/api/v1/bi/reports/file:dataset:lake-report", rita(), null), 404, "DRS-9001");        // a dataset is not a report
        JsonNode broken = ok(call("GET", "/api/v1/bi/reports/file:report:broken", rita(), null));
        assertThat(broken.path("valid").asBoolean()).isFalse();
        assertThat(broken.path("problems").toString()).contains("DRS-9034");
    }

    @Test @Order(2)
    void aCardAndABarAddUpOnTheEngineAndTheRowsRulesDifferPerPerson() throws Exception {
        double all = notional(i -> true, 1);
        JsonNode card = visual(rita(), "main", "total", "{\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(card.path("rows").get(0).get(0).asDouble()).isEqualTo(all);
        assertThat(card.path("query").path("measures").toString()).isEqualTo("[\"Notional\"]");
        JsonNode bar = visual(rita(), "main", "by-desk", "{}");
        assertThat(byFirst(bar, 1)).containsEntry("DESK-A", notional(i -> i % 3 == 0, 1)).containsEntry("DESK-B", notional(i -> i % 3 == 1, 1)).hasSize(3);
        assertThat(bar.path("meta").path("millis").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(byFirst(visual(hana(), "main", "by-desk", "{}"), 1)).as("a desk head sees only her desk").containsOnlyKeys("DESK-A");
        assertThat(visual(ann(), "main", "total", "{}").path("rows").get(0).get(0).asDouble()).as("the mask is on MTM, not notional").isEqualTo(all);
    }

    @Test @Order(3)
    void aSlicerFiltersTheOthersAndASelectionCrossFiltersOrHighlights() throws Exception {
        String usd = "{\"slicers\":{\"ccy\":[\"USD\"]}}";
        assertThat(visual(rita(), "main", "by-desk", usd).path("rows").get(0).get(1).asDouble()).isPositive();
        assertThat(byFirst(visual(rita(), "main", "by-ccy", usd), 1)).as("the slicer is on currency, and the chart by currency is filtered by it").containsOnlyKeys("USD");
        assertThat(byFirst(visual(rita(), "main", "ccy", usd), 0).keySet()).as("a slicer lists every value, not only the chosen").containsExactlyInAnyOrder("USD", "EUR");
        String pick = "{\"selections\":[{\"visual\":\"by-desk\",\"field\":\"trades.desk\",\"values\":[\"DESK-B\"]}]}";
        assertThat(visual(rita(), "main", "total", pick).path("rows").get(0).get(0).asDouble()).isEqualTo(notional(i -> i % 3 == 1, 1));
        assertThat(byFirst(visual(rita(), "main", "by-desk", pick), 1)).as("the selecting visual is not filtered by itself").hasSize(3);
        String highlight = "{\"selections\":[{\"visual\":\"by-desk\",\"field\":\"trades.desk\",\"values\":[\"DESK-B\"],\"highlight\":true}]}";
        JsonNode h = visual(rita(), "main", "by-ccy", highlight);
        assertThat(byFirst(h, 1)).as("the whole answer stays").hasSize(2);
        assertThat(byFirst(h.path("highlight"), 1).values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(notional(i -> i % 3 == 1, 1));
    }

    @Test @Order(4)
    void drillingDownTopNAndShowTheRowsGiveTheRightRows() throws Exception {
        JsonNode books = visual(rita(), "main", "by-desk", "{\"drill\":[\"DESK-A\"]}");
        assertThat(books.path("columns").get(0).path("name").asText()).isEqualTo("trades.book");
        assertThat(byFirst(books, 1).values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(notional(i -> i % 3 == 0, 1));
        JsonNode top = visual(rita(), "main", "top2", "{}");
        assertThat(top.path("rowCount").asInt()).isEqualTo(2);
        assertThat(byFirst(top, 1)).containsOnlyKeys("DESK-C", "DESK-B");                         // the two largest notionals
        JsonNode rows = visual(rita(), "main", "by-desk", "{\"rows\":{\"field\":\"trades.desk\",\"values\":[\"DESK-B\"]},\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(rows.path("rowCount").asInt()).isEqualTo(8);
        assertThat(rows.path("columns").get(0).path("name").asText()).isEqualTo("trades.id");
        JsonNode masked = visual(ann(), "main", "by-desk", "{\"rows\":true,\"limit\":3}");
        assertThat(masked.path("rowCount").asInt()).isEqualTo(3);
        assertThat(masked.path("meta").path("masked").toString()).contains("trades.mtm");
        JsonNode table = visual(rita(), "main", "rows", "{}");
        assertThat(table.path("rowCount").asInt()).as("the visual's own limit").isEqualTo(5);
    }

    @Test @Order(5)
    void badStateAndBadReferencesAreRefusedWithTheirCodes() throws Exception {
        String base = "/api/v1/bi/reports/" + REPORT + "/query";
        refused(call("POST", base, rita(), "{\"page\":\"nope\",\"visual\":\"total\"}"), 422, "DRS-9035");
        refused(call("POST", base, rita(), "{\"page\":\"main\",\"visual\":\"nope\"}"), 422, "DRS-9035");
        refused(call("POST", base, rita(), "{\"page\":\"main\",\"visual\":\"total\",\"state\":{\"slicers\":{\"zzz\":[\"x\"]}}}"), 422, "DRS-9035");
        refused(call("POST", base, rita(), "{\"page\":\"main\",\"visual\":\"total\",\"state\":[1]}"), 422, "DRS-9035");
        refused(call("POST", base, nobi(), "{\"page\":\"main\",\"visual\":\"total\"}"), 403, "DRS-9002");
        refused(call("POST", "/api/v1/bi/reports/file:report:broken/query", rita(), "{\"page\":\"p\",\"visual\":\"x\"}"), 422, "DRS-9034");
        refused(call("POST", base, rita(), "{\"page\":\"main\",\"visual\":\"total\",\"state\":{\"slicers\":{\"ccy\":[\"USD\"]},\"drill\":[\"x\"]}}"), 422, "DRS-9035");        // the card does not drill
    }

    @Test @Order(6)
    void anAuthorChecksDraftYamlFormatsItAndPreviewsAVisualButAReaderMayNot() throws Exception {
        String yaml = """
                report: draft
                dataset: lake-report
                pages:
                  - id: p
                    visuals:
                      - { id: a, type: bar, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trades.desk], values: [Notional, trades.mtm] } }
                      - { id: b, type: shape, at: { x: 6, y: 0, w: 6, h: 4 } }
                """;
        refused(call("POST", "/api/v1/bi/reports/validate", rita(), Map.of("yaml", yaml)), 403, "DRS-9002");
        JsonNode v = ok(call("POST", "/api/v1/bi/reports/validate", wes(), Map.of("yaml", yaml, "quality", true)));
        assertThat(v.path("valid").asBoolean()).isFalse();
        assertThat(v.path("problems").toString()).contains("DRS-9032").contains("DRS-9033").contains("A11Y");
        JsonNode good = ok(call("POST", "/api/v1/bi/reports/validate", wes(), Map.of("yaml", yaml.replace(", trades.mtm", "").replace("type: shape", "type: card, wells: { values: [MTM] }"))));
        assertThat(good.path("valid").asBoolean()).isTrue();
        JsonNode written = ok(call("POST", "/api/v1/bi/reports/format", wes(), Map.of("report", good.path("report"))));
        assertThat(written.path("yaml").asText()).contains("report: draft").contains("pages:");
        JsonNode again = ok(call("POST", "/api/v1/bi/reports/validate", wes(), Map.of("yaml", written.path("yaml").asText())));
        assertThat(again.path("report")).isEqualTo(good.path("report"));
        String goodYaml = yaml.replace(", trades.mtm", "").replace("type: shape", "type: card, wells: { values: [MTM] }");
        JsonNode preview = ok(call("POST", "/api/v1/bi/reports/query", wes(), Map.of("yaml", goodYaml, "page", "p", "visual", "a", "state", Map.of("dates", Map.of("from", "2026-10-08")))));
        assertThat(preview.path("rowCount").asInt()).isGreaterThan(0);
        refused(call("POST", "/api/v1/bi/reports/query", rita(), Map.of("yaml", goodYaml, "page", "p", "visual", "a")), 403, "DRS-9002");
        JsonNode cat = ok(call("GET", "/api/v1/bi/reports/catalogue", rita(), null));
        assertThat(cat.path("visuals").has("waterfall")).isTrue();
        assertThat(cat.path("reserved").toString()).contains("shape").doesNotContain("python").doesNotContain("entity-view");
        assertThat(ok(call("GET", "/api/v1/bi/reports/dataset?ref=lake-report", wes(), null)).path("measures")).hasSize(6);
        refused(call("GET", "/api/v1/bi/reports/dataset?ref=lake-report", rita(), null), 403, "DRS-9002");
        refused(call("GET", "/api/v1/bi/reports/dataset?ref=nope", wes(), null), 422, "DRS-9034");
    }

    @Test @Order(6)
    void aPublishedReportReadsItsDatasetWithItsPublishersGrantsButADraftOnlyWithItsAuthorsOwn() throws Exception {
        // the dataset is in the catalogue for bi-risk only: ann (an analyst) cannot find it, yet the published report over it answers her, as her
        JsonNode viaReport = ok(call("POST", "/api/v1/bi/reports/file:report:uses-restricted/query", ann(),
                "{\"page\":\"p\",\"visual\":\"n\",\"state\":{\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-08\"}}}"));
        assertThat(viaReport.path("rows").get(0).get(0).asInt()).isEqualTo(24);
        refused(call("GET", "/api/v1/bi/datasets/file:dataset:restricted", ann(), null), 404, "DRS-9001");
        // a draft naming it, from an author who may not read it, does not find it
        String draft = "report: d\ndataset: restricted\npages:\n  - id: p\n    visuals: []\n";
        assertThat(ok(call("POST", "/api/v1/bi/reports/validate", wes(), Map.of("yaml", draft))).path("problems").toString()).contains("DRS-9034").contains("no dataset 'restricted'");
        refused(call("GET", "/api/v1/bi/reports/dataset?ref=restricted", wes(), null), 422, "DRS-9034");
    }

    @Test @Order(7)
    void aReportDraftIsPrivateAndPublishingChecksItsStructure() throws Exception {
        String yaml = "report: mine\ndataset: lake-report\npages:\n  - id: p\n    visuals:\n      - { id: a, type: card, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Trades] } }\n";
        JsonNode item = ok(call("POST", "/api/v1/bi/workspaces/me/items", wes(), Map.of("kind", "report", "title", "My report", "body", yaml)));
        String id = item.path("id").asText();
        refused(call("POST", "/api/v1/bi/reports/" + id + "/query", rita(), "{\"page\":\"p\",\"visual\":\"a\"}"), 404, "DRS-9001");
        assertThat(ok(call("POST", "/api/v1/bi/reports/" + id + "/query", wes(), "{\"page\":\"p\",\"visual\":\"a\",\"state\":{\"dates\":{\"from\":\"2026-10-08\"}}}")).path("rows").get(0).get(0).asDouble()).isEqualTo(24.0);
        String shared = ok(call("POST", "/api/v1/bi/workspaces", wes(), Map.of("name", "Report desk", "description", "Reports"))).path("id").asText();
        ok(call("POST", "/api/v1/bi/items/" + id + "/move", wes(), Map.of("workspace", shared)));
        ok(call("PUT", "/api/v1/bi/items/" + id, wes(), Map.of("body", "report: mine\npages: []\n")));
        refused(call("POST", "/api/v1/bi/items/" + id + "/publish", wes(), Map.of()), 422, "DRS-9030");
        ok(call("PUT", "/api/v1/bi/items/" + id, wes(), Map.of("body", yaml)));
        ok(call("POST", "/api/v1/bi/items/" + id + "/publish", wes(), Map.of()));
    }

    // ---- the visuals of wave 3: true totals, distributions, the map ----------------------------------------------------

    static final String DRAFT_HEAD = "report: d\ndataset: lake-report\npages:\n  - id: p\n    visuals:\n";

    JsonNode draft(String who, String visualYaml, String visual, String state) throws Exception {
        Map<String, Object> body = Map.of("yaml", DRAFT_HEAD + "      - " + visualYaml + "\n", "page", "p", "visual", visual, "state", json.readTree(state));
        return ok(call("POST", "/api/v1/bi/reports/query", who, body));
    }

    static final String DAY = "{\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-08\"}}";
    static final String AT = ", at: { x: 0, y: 0, w: 12, h: 6 }";

    @Test @Order(8)
    void aMatrixWithSubtotalsCarriesTheEnginesTotalsForTheMeasuresThatDoNotAddUp() throws Exception {
        JsonNode a = draft(wes(), "{ id: m, type: matrix" + AT + ", wells: { rows: [trades.desk], columns: [trades.currency], values: [Notional, Average notional, Largest] }, format: { subtotals: true } }", "m", DAY);
        JsonNode totals = a.path("totals");
        assertThat(totals).isNotEmpty();
        JsonNode grand = null;
        JsonNode perDesk = null;
        for (JsonNode t : totals) {
            if (t.path("by").isEmpty()) {
                grand = t;
            } else if (t.path("by").toString().equals("[\"trades.desk\"]")) {
                perDesk = t;
            }
        }
        assertThat(grand).isNotNull();
        assertThat(grand.path("columns").toString()).isEqualTo("[\"Average notional\",\"Largest\"]");           // Notional adds up: the client sums it
        assertThat(grand.path("rows").get(0).get(0).asDouble()).isEqualTo(notional(i -> true, 1) / 24, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(grand.path("rows").get(0).get(1).asDouble()).isEqualTo(24000.0);                               // a largest is a largest, not a sum of largests
        assertThat(a.path("rows").size()).isEqualTo(6);
        // the rows of the matrix itself are untouched, and a person without the raw power gets totals under the same rules as the rows
        String hanaTotals = draft(wes(), "{ id: m, type: table" + AT + ", wells: { columns: [trades.desk, Trades, Average notional] }, format: { totals: true } }", "m", DAY).path("totals").toString();
        assertThat(hanaTotals).contains("Trades").contains("Average notional");
        JsonNode plain = draft(wes(), "{ id: m, type: matrix" + AT + ", wells: { rows: [trades.desk], values: [Average notional] } }", "m", DAY);
        assertThat(plain.has("totals")).as("no subtotals asked for, no totals computed").isFalse();
    }

    @Test @Order(9)
    void aHistogramAndABoxPlotCameBackBinnedAndSummarisedByTheServer() throws Exception {
        JsonNode h = draft(wes(), "{ id: h, type: histogram" + AT + ", wells: { axis: [trades.tradeId], values: [Notional] }, format: { bins: 4 } }", "h", DAY);
        assertThat(h.path("columns").get(0).path("name").asText()).isEqualTo("bin");
        assertThat(h.path("rows").size()).isEqualTo(4);
        long total = 0;
        for (JsonNode r : h.path("rows")) {
            total += r.get(1).asLong();
        }
        assertThat(total).isEqualTo(24);                                                                          // every trade is in one bar
        assertThat(h.path("rows").get(0).get(1).asLong()).isEqualTo(6);                                           // notional 1000..24000 in four equal bands: 6 each
        JsonNode b = draft(wes(), "{ id: b, type: box" + AT + ", wells: { axis: [trades.desk], details: [trades.tradeId], values: [Notional] } }", "b", DAY);
        assertThat(b.path("rows").size()).isEqualTo(3);
        assertThat(b.path("columns").get(3).path("name").asText()).isEqualTo("median");
        for (JsonNode r : b.path("rows")) {
            assertThat(r.get(6).asLong()).isEqualTo(8);
            assertThat(r.get(1).asDouble()).isLessThanOrEqualTo(r.get(3).asDouble());
            assertThat(r.get(3).asDouble()).isLessThanOrEqualTo(r.get(5).asDouble());
        }
        // a masked measure (the author lacks raw: mtm is masked) has no bars and no boxes, and says so
        JsonNode masked = draft(wes(), "{ id: h, type: histogram" + AT + ", wells: { axis: [trades.tradeId], values: [MTM] } }", "h", DAY);
        assertThat(masked.path("rows")).isEmpty();
        assertThat(masked.path("meta").path("masked").toString()).contains("MTM");
    }

    @Test @Order(10)
    void theNewVisualsAreInTheCatalogueAndAMapNeedsAPlace() throws Exception {
        JsonNode cat = ok(call("GET", "/api/v1/bi/reports/catalogue", wes(), null));
        for (String t : List.of("map", "funnel", "histogram", "box", "bubble")) {
            assertThat(cat.path("visuals").has(t)).as(t).isTrue();
            assertThat(cat.path("reserved").toString()).doesNotContain("\"" + t + "\"");
        }
        assertThat(cat.path("visuals").path("map").path("wells").has("latitude")).isTrue();
        JsonNode v = ok(call("POST", "/api/v1/bi/reports/validate", wes(), Map.of("yaml", DRAFT_HEAD + "      - { id: m, type: map" + AT + ", wells: { values: [Trades] } }\n")));
        assertThat(v.path("valid").asBoolean()).isFalse();
        assertThat(v.path("problems").toString()).contains("a country code");
    }

    @Test @Order(98)
    void oneBadVisualIsOneErrorAndTheOthersStillDraw() throws Exception {
        String base = "/api/v1/bi/reports/file:report:one-bad/query";
        assertThat(ok(call("POST", base, rita(), "{\"page\":\"p\",\"visual\":\"good\"}")).path("rows").get(0).get(0).asDouble()).isPositive();
        refused(call("POST", base, rita(), "{\"page\":\"p\",\"visual\":\"bad\"}"), 422, "DRS-9034");
    }

    @Test @Order(11)
    void aTimeIntelligenceMeasureAnswersThroughAVisualAsOfTheEndOfTheDates() throws Exception {
        JsonNode card = draft(wes(), "{ id: c, type: card" + AT + ", wells: { values: [Notional ytd] } }", "c", DAY);
        assertThat(card.path("rows").get(0).get(0).asDouble()).isEqualTo(notional(i -> true, 1));               // every trade is dated this year, up to the 8th
        JsonNode ds = ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-report", wes(), null));
        assertThat(ds.path("refresh").path("scheduled").asBoolean()).isFalse();
        assertThat(ds.path("refresh").path("calendar").asText()).isNotEmpty();
        assertThat(ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-report/refresh", rita(), null)).path("scheduled").asBoolean()).isFalse();
    }
}
