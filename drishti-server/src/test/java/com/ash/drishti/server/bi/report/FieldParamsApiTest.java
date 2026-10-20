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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Field parameters in report wells (docs/architecture/BI_DESIGN.md section 12.2, wave D server item 5; docs/guides/REPORTS.md section 21), over the whole
 * stack with security on: a report on a model whose column chart names {@code param.breakdown} (DESK or PORTFOLIO) on its axis and {@code param.metric}
 * (MTM or NOTIONAL) in its values. The reader's parameter values switch what it draws, each answer equal to an independent sum of the lake's rows, and a
 * masked measure stays masked when it is the one chosen; the report schema refuses what cannot work ({@code DRS-9037}).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/fp-api-${random.uuid}/identity.db", "drishti.packs.overlay=target/fp-api-overlay/added.yaml",
        "drishti.bi.dir=target/fp-api-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/fp-api-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=target/test-data/fp-api-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.fp-lake.plugin=file", "drishti.sources.connectors.fp-lake.kinds[0]=trade",
        "drishti.sources.connectors.fp-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.fp-lake.settings.layout.trade.columns=TRADE_ID,DESK,PORTFOLIO,LEGAL_ENTITY,NOTIONAL,MTM,TRADE_DATE"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldParamsApiTest {

    static final String DAY = "2026-10-08";
    static final String REPORT = "file:report:fp-report";
    static final String[] DESKS = {"DESK-FX", "DESK-FI", "DESK-EQD"};
    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    static double notional(int i) {
        return 1_000_000.0 * (1 + i % 7);
    }

    static double mtm(int i) {
        return ((i % 9) - 4) * 25_000.0;
    }

    static String desk(int i) {
        return DESKS[i % 3];
    }

    static String portfolio(int i) {
        return "BOOK-" + (i % 4);
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = Files.createTempDirectory("drishti-fp-lake");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            lines.add("{\"id\":\"" + (1000 + i) + "\",\"TRADE_ID\":\"" + (1000 + i) + "\",\"DESK\":\"" + desk(i) + "\",\"PORTFOLIO\":\"" + portfolio(i) + "\",\"LEGAL_ENTITY\":\"LE-"
                    + (i % 2) + "\",\"NOTIONAL\":" + notional(i) + ",\"MTM\":" + mtm(i) + ",\"TRADE_DATE\":\"" + DAY + "\"}");
        }
        Path f = root.resolve("lake").resolve(DAY).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
        r.add("drishti.sources.connectors.fp-lake.settings.root", () -> root.resolve("lake").toString());
    }

    static {
        try {
            Path folder = Path.of("target/fp-api-folder");
            Path datasets = Files.createDirectories(folder.resolve("datasets"));
            Path models = Files.createDirectories(folder.resolve("models"));
            Path reports = Files.createDirectories(folder.resolve("reports"));
            Files.writeString(datasets.resolve("fp-trade.dataset.yaml"), """
                    rachana: 2
                    dataset: fp-trade
                    version: 1.0.0
                    source: { connector: fp-lake, kind: trade }
                    grain: { keys: [id] }
                    columns:
                      TRADE_ID:     { type: string }
                      DESK:         { type: string }
                      PORTFOLIO:    { type: string }
                      LEGAL_ENTITY: { type: string }
                      NOTIONAL:     { type: decimal, aggregate: sum }
                      MTM:          { type: decimal, aggregate: sum }
                    security:
                      rows:
                        - { role: [fp-risk, fp-analyst, fp-author, admin], where: "true" }
                      masked: [MTM]
                    """);
            Files.writeString(models.resolve("fp-model.model.yaml"), """
                    rachana: 2
                    model: fp-model
                    version: 1.0.0
                    datasets:
                      trade: { dataset: fp-trade, role: fact }
                    measures:
                      MTM:      { expr: "sum(trade.MTM)" }
                      NOTIONAL: { expr: "sum(trade.NOTIONAL)" }
                      Trades:   { expr: "count(trade)" }
                    parameters:
                      breakdown: { kind: field, fields: [trade.DESK, trade.PORTFOLIO], default: trade.DESK, title: Break down by }
                      metric:    { kind: field, fields: [MTM, NOTIONAL], default: MTM, title: Show }
                      shock:     { kind: number, default: 0, min: -1, max: 1, step: 0.1 }
                    """);
            Files.writeString(reports.resolve("fp-report.report.yaml"), """
                    report: fp-report
                    title: Field parameters
                    model: fp-model@1
                    pages:
                      - id: main
                        title: Main
                        visuals:
                          - { id: chart, type: column, title: By the choice, at: { x: 0, y: 0, w: 8, h: 5 }, wells: { axis: [param.breakdown], values: [param.metric] },
                              sort: { by: param.metric, desc: true } }
                          - { id: count, type: card, title: Trades, at: { x: 8, y: 0, w: 4, h: 2 }, wells: { values: [Trades] } }
                          - { id: tbl, type: table, title: Table, at: { x: 0, y: 5, w: 8, h: 5 }, wells: { columns: [param.breakdown, param.metric] } }
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

    @BeforeAll
    void setUp() throws Exception {
        for (String[] r : new String[][] {{"fp-risk", "true"}, {"fp-analyst", "false"}, {"fp-author", "true"}}) {
            var res = mvc.perform(put("/api/v1/admin/role-definitions/" + r[0]).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "raw", Boolean.parseBoolean(r[1]), "bi",
                            "fp-author".equals(r[0]) ? List.of("bi", "bi-author") : List.of("bi"))))).andReturn().getResponse();
            assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        }
        for (String[] u : new String[][] {{"rita", "fp-risk"}, {"ann", "fp-analyst"}, {"wes", "fp-author"}}) {
            var res = mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", u[0], "displayName", u[0], "email", u[0] + "@desk.test", "desk", "", "roles", List.of(u[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andReturn().getResponse();
            assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
        }
    }

    MvcResult call(String method, String path, String who, Object body) throws Exception {
        var req = "GET".equals(method) ? get(java.net.URI.create(path)) : post(java.net.URI.create(path));
        req.header("Authorization", who);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req).andReturn();
    }

    JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 299);
        return json.readTree(r.getResponse().getContentAsString());
    }

    JsonNode chart(String who, Map<String, Object> params) throws Exception {
        return visual("chart", who, params);
    }

    JsonNode visual(String id, String who, Map<String, Object> params) throws Exception {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("dates", Map.of("from", DAY, "to", DAY));
        if (params != null) {
            state.put("params", params);
        }
        return ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", who, Map.of("page", "main", "visual", id, "state", state)));
    }

    /** By the first column's value, the second column's number (null when masked). */
    static Map<String, Double> byKey(JsonNode a) {
        Map<String, Double> out = new TreeMap<>();
        for (JsonNode row : a.path("rows")) {
            out.put(row.get(0).asText(), row.get(1).isNull() || row.get(1).isTextual() ? null : row.get(1).asDouble());
        }
        return out;
    }

    static Map<String, Double> expected(java.util.function.IntFunction<String> key, java.util.function.IntToDoubleFunction value) {
        Map<String, Double> out = new TreeMap<>();
        for (int i = 0; i < 36; i++) {
            out.merge(key.apply(i), value.applyAsDouble(i), Double::sum);
        }
        return out;
    }

    @Test
    void theReadersChoiceSwitchesTheFieldAndTheMeasureTheChartDraws() throws Exception {
        String rita = as("rita", "fp-risk");
        JsonNode dflt = chart(rita, null);
        assertThat(dflt.path("wells").path("axis").get(0).asText()).isEqualTo("trade.DESK");
        assertThat(dflt.path("wells").path("values").get(0).asText()).isEqualTo("MTM");
        assertThat(byKey(dflt)).isEqualTo(expected(FieldParamsApiTest::desk, FieldParamsApiTest::mtm));
        assertThat(dflt.path("mq").toString()).contains("trade.DESK").contains("MTM");

        JsonNode book = chart(rita, Map.of("breakdown", "trade.PORTFOLIO"));
        assertThat(book.path("wells").path("axis").get(0).asText()).isEqualTo("trade.PORTFOLIO");
        assertThat(byKey(book)).isEqualTo(expected(FieldParamsApiTest::portfolio, FieldParamsApiTest::mtm));

        JsonNode both = chart(rita, Map.of("breakdown", "trade.PORTFOLIO", "metric", "NOTIONAL"));
        assertThat(both.path("wells").path("values").get(0).asText()).isEqualTo("NOTIONAL");
        assertThat(byKey(both)).isEqualTo(expected(FieldParamsApiTest::portfolio, FieldParamsApiTest::notional));
        List<Double> sorted = new ArrayList<>();
        both.path("rows").forEach(r -> sorted.add(r.get(1).asDouble()));
        assertThat(sorted).as("sort: { by: param.metric } sorts by the measure chosen").isSortedAccordingTo(java.util.Comparator.reverseOrder());

        JsonNode count = ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", rita, Map.of("page", "main", "visual", "count",
                "state", Map.of("dates", Map.of("from", DAY, "to", DAY), "params", Map.of("breakdown", "trade.PORTFOLIO")))));
        assertThat(count.has("wells")).as("a visual without a field parameter answers as before").isFalse();
        assertThat(count.path("rows").get(0).get(0).asDouble()).isEqualTo(36);
    }

    @Test
    void aMaskedMeasureStaysMaskedWhenTheReaderChoosesIt() throws Exception {
        String ann = as("ann", "fp-analyst");
        JsonNode masked = visual("tbl", ann, null);
        assertThat(masked.path("wells").path("columns").toString()).contains("trade.DESK").contains("MTM");
        assertThat(masked.path("meta").path("masked").toString()).contains("MTM");
        assertThat(byKey(masked).values()).allMatch(v -> v == null);
        MvcResult sorted = call("POST", "/api/v1/bi/reports/" + REPORT + "/query", ann, Map.of("page", "main", "visual", "chart", "state", Map.of("dates", Map.of("from", DAY, "to", DAY))));
        assertThat(sorted.getResponse().getContentAsString()).as("the chart sorts by the measure chosen, and a masked one cannot be sorted on").contains("DRS-9021");
        JsonNode open = chart(ann, Map.of("metric", "NOTIONAL"));
        assertThat(byKey(open)).isEqualTo(expected(FieldParamsApiTest::desk, FieldParamsApiTest::notional));
        assertThat(byKey(visual("tbl", ann, Map.of("metric", "NOTIONAL", "breakdown", "trade.PORTFOLIO")))).isEqualTo(expected(FieldParamsApiTest::portfolio,
                FieldParamsApiTest::notional));
    }

    @Test
    void aValueThatIsNotAChoiceIsRefusedAndTheModelQueryTakesAMeasureParameterToo() throws Exception {
        String rita = as("rita", "fp-risk");
        MvcResult bad = call("POST", "/api/v1/bi/reports/" + REPORT + "/query", rita, Map.of("page", "main", "visual", "chart",
                "state", Map.of("params", Map.of("breakdown", "trade.LEGAL_ENTITY"))));
        assertThat(bad.getResponse().getStatus()).isEqualTo(422);
        assertThat(bad.getResponse().getContentAsString()).contains("DRS-9037").contains("trade.PORTFOLIO");
        JsonNode mq = ok(call("POST", "/api/v1/bi/mq", rita, Map.of("mq", 2, "model", "fp-model@1", "select", Map.of("fields", List.of("param.breakdown"),
                "measures", List.of("param.metric")), "params", Map.of("metric", "NOTIONAL"), "time", Map.of("as-of", DAY))));
        assertThat(byKey(mq)).isEqualTo(expected(FieldParamsApiTest::desk, FieldParamsApiTest::notional));
    }

    @Test
    void theReportSchemaChecksAFieldParameterInAWell() throws Exception {
        String wes = as("wes", "fp-author");
        String head = "report: fp-check\nmodel: fp-model@1\npages:\n  - id: p\n    title: P\n    visuals:\n";
        JsonNode good = ok(call("POST", "/api/v1/bi/reports/validate", wes, Map.of("yaml", head
                + "      - { id: t, type: table, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { columns: [param.breakdown, param.metric, Trades] } }\n")));
        assertThat(good.path("valid").asBoolean()).as(good.toString()).isTrue();
        Map<String, String> cases = Map.of(
                "      - { id: c, type: column, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.DESK], values: [param.breakdown] } }\n", "takes measures",
                "      - { id: c, type: column, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [param.metric], values: [MTM] } }\n", "takes fields",
                "      - { id: c, type: column, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [param.nope], values: [MTM] } }\n", "no parameter 'nope'",
                "      - { id: c, type: column, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [param.shock], values: [MTM] } }\n", "what-if parameter",
                "      - { id: s, type: slicer, at: { x: 0, y: 0, w: 3, h: 4 }, wells: { field: [param.breakdown] } }\n", "a slicer slices one field");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            JsonNode v = ok(call("POST", "/api/v1/bi/reports/validate", wes, Map.of("yaml", head + c.getKey())));
            assertThat(v.path("valid").asBoolean()).as(c.getKey()).isFalse();
            assertThat(v.path("problems").toString()).as(c.getKey()).contains("DRS-9037").contains(c.getValue());
        }
        JsonNode onDataset = ok(call("POST", "/api/v1/bi/reports/validate", wes, Map.of("yaml", head.replace("model: fp-model@1", "dataset: fp-trade")
                + "      - { id: c, type: column, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [param.breakdown], values: [NOTIONAL] } }\n")));
        assertThat(onDataset.path("problems").toString()).contains("DRS-9037").contains("belong to a model");
        JsonNode described = ok(call("GET", "/api/v1/bi/reports/" + REPORT, wes, null));
        assertThat(described.path("dataset").path("parameters").toString()).as("the Studio offers the model's field parameters").contains("breakdown").contains("metric");
    }
}
