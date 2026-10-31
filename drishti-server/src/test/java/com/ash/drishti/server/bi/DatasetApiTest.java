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
package com.ash.drishti.server.bi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.bi.engine.ArrowTestReader;
import com.ash.drishti.server.bi.migrate.ModelConverter;
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
 * Rupaka phase 2 over the whole stack, security on, over a small generated lake read through a real file connector: the dataset API (list, model,
 * validate, query as JSON and Arrow, explain), row rules and masks for three roles, the layers (rollup, columns), the result cache and a data-load
 * signal that rebuilds exactly the date it names, sample visibility, the model started from the packs, and the refusals with their DRS codes.
 *
 * <p>Roles: bi-risk (raw), desk-head (raw, rows of their desks), analyst (no raw), author (bi-author), plain (no BI power).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/bi-dataset-${random.uuid}/identity.db", "drishti.packs.overlay=${drishti.test.dir}/bi-dataset-overlay/added.yaml",
        "drishti.bi.dir=${drishti.test.dir}/bi-dataset-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=${drishti.test.dir}/bi-dataset-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=${drishti.test.dir}/bi-dataset-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.test-lake.plugin=file", "drishti.sources.connectors.test-lake.kinds[0]=trade",
        "drishti.sources.connectors.test-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.test-lake.settings.layout.trade.columns=desk,book,currency,notional,mtm,counterparty.id,tradeDate",
        "drishti.sources.connectors.test-ref.plugin=file", "drishti.sources.connectors.test-ref.kinds[0]=book",
        "drishti.sources.connectors.test-ref.settings.rescan-seconds=3600", "drishti.sources.connectors.test-ref.settings.layout.book.columns=name,desk"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatasetApiTest {

    static final String DS = "file:dataset:lake-test";
    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    static String trade(String id, String desk, String book, String ccy, String cp, double notional, double mtm, String day) {
        return "{\"id\":\"" + id + "\",\"tradeId\":\"" + id + "\",\"desk\":\"" + desk + "\",\"book\":\"" + book + "\",\"currency\":\"" + ccy + "\",\"counterparty\":{\"id\":\"" + cp
                + "\"},\"notional\":" + notional + ",\"mtm\":" + mtm + ",\"tradeDate\":\"" + day + "\"}";
    }

    static void day(String day, double scale) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            lines.add(trade("T-" + day.substring(8) + "-" + i, i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C", "BOOK-" + (i % 3 + 1), i % 2 == 0 ? "USD" : "EUR", "CP-" + (i % 4),
                    1000.0 * (i + 1) * scale, ((i % 5) - 2) * 100.0, day));
        }
        Path f = root.resolve("lake").resolve(day).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = com.ash.drishti.server.TestRun.temp("drishti-bi-lake");
        day("2026-10-07", 1);
        day("2026-10-08", 1);
        Files.createDirectories(root.resolve("ref"));
        Files.write(root.resolve("ref").resolve("book.jsonl"), List.of("{\"id\":\"BOOK-1\",\"bookId\":\"BOOK-1\",\"name\":\"Rates one\",\"desk\":\"DESK-A\"}",
                "{\"id\":\"BOOK-2\",\"bookId\":\"BOOK-2\",\"name\":\"FX one\",\"desk\":\"DESK-B\"}", "{\"id\":\"BOOK-3\",\"bookId\":\"BOOK-3\",\"name\":\"Credit one\",\"desk\":\"DESK-C\"}"));
        r.add("drishti.sources.connectors.test-lake.settings.root", () -> root.resolve("lake").toString());
        r.add("drishti.sources.connectors.test-ref.settings.root", () -> root.resolve("ref").toString());
    }

    static {
        try {
            Path datasets = com.ash.drishti.server.TestRun.root().resolve("bi-dataset-folder/datasets");
            Files.createDirectories(datasets);
            Files.writeString(datasets.resolve("lake-test.dataset.yaml"), """
                    rachana: 1
                    dataset: lake-test
                    title: Lake test
                    description: Trades read through a file connector, for the API test.
                    tables:
                      trades:
                        source: { connector: test-lake, kind: trade }
                        columns:
                          desk: string
                          book: string
                          currency: string
                          counterpartyId: { path: counterparty.id, type: string }
                          notional: decimal
                          mtm: decimal
                          tradeDate: date
                      books:
                        source: { connector: test-ref, kind: book }
                        columns:
                          name: string
                          desk: string
                    relationships:
                      - { from: trades.book, to: books.id, cardinality: many-to-one }
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
                    rollups:
                      - { name: by_desk, table: trades, by: [desk, currency], measures: [Notional, MTM, Trades] }
                    """);
            // since 2.2 a dataset is asked through its model: the converter writes lake-test's datasets and model beside it (BI_DESIGN.md 8.3)
            Path models = Files.createDirectories(datasets.resolveSibling("models"));
            ModelConverter.Result converted = ModelConverter.convert(List.of(new ModelConverter.Source("datasets/lake-test.dataset.yaml", "dataset",
                    Files.readString(datasets.resolve("lake-test.dataset.yaml")))), ModelConverter.Exposure.NOTE);
            for (Map.Entry<String, String> w : converted.writes().entrySet()) {
                Files.writeString((w.getKey().startsWith("models/") ? models : datasets).resolve(w.getKey().substring(w.getKey().indexOf('/') + 1)), w.getValue());
            }
            Files.writeString(datasets.resolve("hidden-sample.dataset.yaml"), "dataset: hidden-sample\ntitle: A sample\nsample: true\ntables:\n  t:\n    source: { kind: trade }\n    columns: { desk: string }\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---- plumbing ----------------------------------------------------------------------------------------------------

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

    String hugo() {
        return as("hugo", "desk-head");
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

    static final String MODEL = "lake-test@1";
    static final java.util.Set<String> MEASURES = java.util.Set.of("Notional", "MTM", "Trades", "Big");

    /** A 2.1 dataset query written as the model query it is since 2.2 (what the 410 of POST /datasets/{id}/query hands back). */
    String mq(String body) throws Exception {
        JsonNode b = json.readTree(body);
        Map<String, Object> q = new java.util.LinkedHashMap<>();
        q.put("mq", 1);
        q.put("model", MODEL);
        Map<String, Object> select = new java.util.LinkedHashMap<>();
        select.put("fields", b.path("fields").isArray() ? json.convertValue(b.path("fields"), List.class) : List.of());
        select.put("measures", b.path("measures").isArray() ? json.convertValue(b.path("measures"), List.class) : List.of());
        q.put("select", select);
        List<Object> where = new ArrayList<>();
        List<Object> having = new ArrayList<>();
        for (JsonNode f : b.path("filters")) {
            Map<String, Object> one = new java.util.LinkedHashMap<>();
            boolean measure = MEASURES.contains(f.path("field").asText());
            one.put(measure ? "measure" : "field", f.path("field").asText());
            one.put("op", f.path("op").asText());
            if (f.has("values")) {
                one.put(measure ? "value" : "values", measure ? json.convertValue(f.path("values").get(0), Object.class) : json.convertValue(f.path("values"), List.class));
            }
            (measure ? having : where).add(one);
        }
        if (!where.isEmpty()) {
            q.put("where", where);
        }
        if (!having.isEmpty()) {
            q.put("having", having);
        }
        JsonNode d = b.path("dates");
        if (d.has("from")) {
            q.put("time", !d.has("to") || d.path("to").asText().equals(d.path("from").asText()) ? Map.of("as-of", d.path("from").asText())
                    : Map.of("window", Map.of("from", d.path("from").asText(), "to", d.path("to").asText())));
        }
        return json.writeValueAsString(q);
    }

    JsonNode query(String who, String body) throws Exception {
        return ok(call("POST", "/api/v1/bi/mq", who, mq(body)));
    }

    static Map<String, Double> byFirstColumn(JsonNode answer, int valueColumn) {
        Map<String, Double> out = new TreeMap<>();
        for (JsonNode row : answer.path("rows")) {
            out.put(row.get(0).asText(), row.get(valueColumn).isNull() ? null : row.get(valueColumn).asDouble());
        }
        return out;
    }

    static double expected(String desk, int day, double scale) {
        double sum = 0;
        for (int i = 0; i < 24; i++) {
            String d = i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C";
            if (desk == null || d.equals(desk)) {
                sum += 1000.0 * (i + 1) * scale;
            }
        }
        return sum;
    }

    @BeforeAll
    void setUp() throws Exception {
        role("bi-risk", List.of("bi"), true, false);
        role("desk-head", List.of("bi"), true, false);
        role("bi-analyst", List.of("bi"), false, false);
        role("report-author", List.of("bi", "bi-author"), false, false);
        role("plain", List.of(), false, false);
        for (String u : List.of("rita:bi-risk:", "hana:desk-head:DESK-A", "hugo:desk-head:DESK-B; DESK-C", "ann:bi-analyst:", "wes:report-author:", "nobi:plain:")) {
            String[] n = u.split(":", -1);
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "desk", n[2], "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
    }

    void role(String name, List<String> bi, boolean raw, boolean approve) throws Exception {
        var r = mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", approve, "raw", raw, "bi", bi)))).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    // ---- the tests ---------------------------------------------------------------------------------------------------

    @Test @Order(1)
    void theCatalogueListsDatasetsWithTheirHealthAndHidesSamplesFromThoseWhoMayNotSeeThem() throws Exception {
        JsonNode list = ok(call("GET", "/api/v1/bi/datasets", rita(), null)).path("datasets");
        assertThat(list.toString()).contains(DS).contains("\"valid\":true").doesNotContain("hidden-sample");
        assertThat(ok(call("GET", "/api/v1/bi/datasets", admin(), null)).path("datasets").toString()).contains("hidden-sample");   // developers mode: the admin sees samples
        refused(call("GET", "/api/v1/bi/datasets/file:dataset:hidden-sample", rita(), null), 404, "DRS-9001");
        refused(call("GET", "/api/v1/bi/datasets", nobi(), null), 403, "DRS-9002");
        refused(call("POST", "/api/v1/bi/mq", nobi(), mq("{\"measures\":[\"Trades\"]}")), 403, "DRS-9002");
        refused(call("GET", "/api/v1/bi/datasets/nothing", rita(), null), 404, "DRS-9001");
    }

    @Test @Order(2)
    void theModelShowsTablesRelationshipsMeasuresAndWhatIsMaskedForTheReader() throws Exception {
        JsonNode m = ok(call("GET", "/api/v1/bi/datasets/" + DS, rita(), null));
        assertThat(m.path("valid").asBoolean()).isTrue();
        assertThat(m.path("tables").size()).isEqualTo(2);
        assertThat(m.path("relationships").get(0).path("from").asText()).isEqualTo("trades.book");
        assertThat(m.path("measures").size()).isEqualTo(3);
        assertThat(m.path("tables").get(0).path("access").asText()).isEqualTo("ok");
        assertThat(maskedColumns(m)).isEmpty();
        assertThat(maskedColumns(ok(call("GET", "/api/v1/bi/datasets/" + DS, ann(), null)))).contains("trades.mtm");
        assertThat(m.path("security").path("rows").toString()).contains("\"appliesToYou\":true");
    }

    List<String> maskedColumns(JsonNode model) {
        List<String> out = new ArrayList<>();
        model.path("tables").forEach(t -> t.path("columns").forEach(c -> {
            if (c.path("masked").asBoolean()) {
                out.add(t.path("name").asText() + "." + c.path("name").asText());
            }
        }));
        return out;
    }

    @Test @Order(3)
    void validateFindsMistakesWithTheirCodesAndPaths() throws Exception {
        refused(call("POST", "/api/v1/bi/datasets/validate", rita(), Map.of("yaml", "dataset: x")), 403, "DRS-9002");     // authors only
        JsonNode good = ok(call("POST", "/api/v1/bi/datasets/validate", wes(), Map.of("yaml", "dataset: x\ntables:\n  t:\n    source: { kind: trade }\n    columns: { a: string }\nmeasures:\n  N: { expr: \"count(t)\" }\n")));
        assertThat(good.path("valid").asBoolean()).isTrue();
        JsonNode bad = ok(call("POST", "/api/v1/bi/datasets/validate", wes(), Map.of("yaml", "dataset: x\ntables:\n  t:\n    source: { kind: trade }\n    columns: { a: string }\nmeasures:\n  N: { expr: \"sum(t.nope)\" }\n  M: { expr: \"lag(sum(t.a))\" }\n")));
        assertThat(bad.path("valid").asBoolean()).isFalse();
        assertThat(bad.path("problems").toString()).contains("DRS-9011").contains("measures.N").contains("measures.M");
        // a table over a connector nobody configured is "has errors", not a good dataset that fails at its first query
        JsonNode ghost = ok(call("POST", "/api/v1/bi/datasets/validate", wes(), Map.of("yaml", "dataset: x\ntables:\n  t:\n    source: { connector: no-such-lake, kind: trade }\n    columns: { a: string }\nmeasures:\n  N: { expr: \"count(t)\" }\n")));
        assertThat(ghost.path("valid").asBoolean()).isFalse();
        assertThat(ghost.path("problems").toString()).contains("no-such-lake").contains("does not exist");
    }

    @Test @Order(4)
    void aRiskReaderGetsEveryDeskAndTheNumbersAddUp() throws Exception {
        JsonNode a = query(rita(), "{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\",\"MTM\",\"Trades\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(a.path("rowCount").asInt()).isEqualTo(3);
        Map<String, Double> notional = byFirstColumn(a, 1);
        assertThat(notional.get("DESK-A")).isEqualTo(expected("DESK-A", 8, 1));
        assertThat(notional.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(expected(null, 8, 1));
        // (2.1 answered this from the by_desk rollup; a model whose dataset carries row rules reads its columns: see the 2.2 removal notes)
        assertThat(a.path("meta").path("layer").asText()).isIn("rollup", "columns");
        assertThat(a.path("meta").path("dates").toString()).contains("2026-10-08");
        assertThat(a.path("columns").get(0).path("type").asText()).isEqualTo("string");
        assertThat(a.path("columns").get(3).path("type").asText()).isEqualTo("integer");
        // a join to the books table (a cross-connector many-to-one) is answered from the columns layer
        JsonNode j = query(rita(), "{\"fields\":[\"books.name\"],\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(j.path("meta").path("layer").asText()).isEqualTo("columns");
        assertThat(byFirstColumn(j, 1).keySet()).containsExactly("Credit one", "FX one", "Rates one");
        // two dates: the trades are a daily snapshot, so over a window Notional is the last date's, not the two added (2.3.0, DRS-9159)
        JsonNode both = query(rita(), "{\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-07\",\"to\":\"2026-10-08\"}}");
        assertThat(both.path("rows").get(0).get(0).asDouble()).isEqualTo(expected(null, 8, 1));
        assertThat(both.path("meta").path("warnings").toString()).contains("DRS-9159");
    }

    @Test @Order(5)
    void threeReadersSeeDifferentRowsAndFieldsFromTheSameQuery() throws Exception {
        String body = "{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\",\"MTM\"],\"dates\":{\"from\":\"2026-10-08\"}}";
        JsonNode head = query(hana(), body);
        assertThat(byFirstColumn(head, 1).keySet()).containsExactly("DESK-A");
        JsonNode two = query(hugo(), body);
        assertThat(byFirstColumn(two, 1).keySet()).containsExactly("DESK-B", "DESK-C");
        JsonNode risk = query(rita(), body);
        assertThat(byFirstColumn(risk, 1).keySet()).containsExactly("DESK-A", "DESK-B", "DESK-C");
        assertThat(byFirstColumn(risk, 2).values()).doesNotContainNull();
        JsonNode analyst = query(ann(), body);                                                       // all rows, mtm masked
        assertThat(byFirstColumn(analyst, 1)).hasSize(3);
        assertThat(byFirstColumn(analyst, 2).values()).containsOnlyNulls();
        assertThat(analyst.path("meta").path("masked").toString()).contains("MTM");
        // a reader with no rule for their roles gets nothing, and is told why
        JsonNode none = query(wes(), body);
        assertThat(none.path("rowCount").asInt()).isZero();
        assertThat(none.path("meta").path("warnings").toString()).contains("no row rule");
        // the desk-head cannot widen the rule by filtering
        JsonNode widened = query(hana(), "{\"fields\":[\"trades.desk\"],\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.desk\",\"op\":\"in\",\"values\":[\"DESK-B\",\"DESK-C\"]}],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(widened.path("rowCount").asInt()).isZero();
    }

    @Test @Order(6)
    void aMaskedFieldCannotBeFilteredOnAndTheGlobalRedactListAppliesToo() throws Exception {
        refused(call("POST", "/api/v1/bi/mq", ann(), mq("{\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.mtm\",\"op\":\"gt\",\"values\":[0]}]}")), 403, "DRS-9161");
        // counterpartyId is on drishti.security.redact: masked for a reader without raw although the dataset does not list it
        JsonNode a = query(ann(), "{\"fields\":[\"trades.counterpartyId\"],\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(a.path("rowCount").asInt()).isEqualTo(1);
        assertThat(a.path("rows").get(0).get(0).asText()).isEqualTo("\u2022\u2022\u2022");
        assertThat(query(rita(), "{\"fields\":[\"trades.counterpartyId\"],\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-08\"}}").path("rowCount").asInt()).isEqualTo(4);
    }

    @Test @Order(7)
    void arrowAnswersCarryTheLayerRowsAndMasksInHeaders() throws Exception {
        var r = call("POST", "/api/v1/bi/mq?format=arrow", ann(),
                mq("{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\",\"MTM\"],\"dates\":{\"from\":\"2026-10-08\"}}")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(r.getContentType()).isEqualTo("application/vnd.apache.arrow.stream");
        assertThat(r.getHeader("X-Bi-Layer")).isIn("rollup", "columns");
        assertThat(r.getHeader("X-Bi-Masked")).contains("MTM");
        ArrowTestReader.Table t = ArrowTestReader.read(r.getContentAsByteArray());
        assertThat(t.rows()).isEqualTo(3);
        assertThat(t.columns().keySet()).containsExactly("trades.desk", "Notional", "MTM");
        assertThat(t.columns().get("MTM")).containsOnlyNulls();
        assertThat((Double) t.columns().get("Notional").get(0)).isGreaterThan(0);
    }

    @Test @Order(8)
    void badQueriesAreRefusedWithTheirCodes() throws Exception {
        refused(call("POST", "/api/v1/bi/mq", rita(), mq("{\"fields\":[\"trades.nope\"]}")), 422, "DRS-9152");
        refused(call("POST", "/api/v1/bi/mq", rita(), mq("{\"measures\":[\"Nope\"]}")), 422, "DRS-9152");
        // an injection attempt is a value: it matches nothing, and the explain text shows a placeholder, not the value
        JsonNode inj = ok(call("POST", "/api/v1/bi/mq", wes(), explain(mq(
                "{\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.desk\",\"op\":\"eq\",\"values\":[\"x' OR '1'='1\"]}],\"dates\":{\"from\":\"2026-10-08\"}}"))));
        assertThat(inj.path("rows").get(0).get(0).asInt()).isZero();
        assertThat(ok(call("POST", "/api/v1/bi/mq", rita(), explain(mq("{\"measures\":[\"Trades\"]}")))).path("meta").path("sql").isTextual()).isFalse();     // only authors see the SQL
        assertThat(inj.path("meta").path("sql").asText()).contains("?").doesNotContain("OR '1'");
        refused(call("POST", "/api/v1/bi/mq?format=xml", rita(), mq("{\"measures\":[\"Trades\"]}")), 422, "DRS-9150");
        // the 2.1 route answers 410 with the model query it would have been
        var gone = call("POST", "/api/v1/bi/datasets/" + DS + "/query", rita(), "{\"fields\":[\"trades.desk\"],\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-08\"}}")
                .andExpect(status().isGone()).andReturn().getResponse();
        JsonNode equivalent = json.readTree(gone.getContentAsString());
        assertThat(equivalent.path("mq").path("model").asText()).isEqualTo(MODEL);
        assertThat(equivalent.path("mq").path("select").path("measures").toString()).contains("Trades");
        assertThat(equivalent.path("mq").path("time").path("as-of").asText()).isEqualTo("2026-10-08");
        assertThat(ok(call("POST", "/api/v1/bi/mq", rita(), json.writeValueAsString(equivalent.path("mq")))).path("rows").size()).as("the equivalent runs").isEqualTo(3);
    }

    static String explain(String mq) {
        return mq.substring(0, mq.length() - 1) + ",\"options\":{\"explain\":true}}";
    }

    @Test @Order(9)
    void identicalQueriesAreCachedPerReaderAndADataLoadRebuildsTheDateItNames() throws Exception {
        String body = "{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-08\"}}";
        JsonNode first = query(rita(), body);
        JsonNode second = query(rita(), body);
        assertThat(second.path("meta").path("cached").asBoolean()).isTrue();
        assertThat(second.path("rows")).isEqualTo(first.path("rows"));
        assertThat(query(hana(), body).path("meta").path("cached").asBoolean()).isFalse();           // another reader's rows: another entry
        // the lake is reloaded for 2026-10-08 with twice the notional, and the load is announced
        day("2026-10-08", 2);
        call("POST", "/api/v1/packs/finance/loads", admin(), Map.of("kind", "trade", "businessDate", "2026-10-08", "status", "ready", "rows", 24, "batchId", "bi-test-" + java.util.UUID.randomUUID())).andExpect(status().is2xxSuccessful());
        double want = expected("DESK-A", 8, 2);
        double got = 0;
        boolean fresh = false;
        for (int i = 0; i < 100 && !fresh; i++) {
            JsonNode a = query(rita(), body);
            got = byFirstColumn(a, 1).get("DESK-A");
            fresh = got == want && !a.path("meta").path("cached").asBoolean();
            if (!fresh) {
                Thread.sleep(100);
            }
        }
        assertThat(got).isEqualTo(want);
        // the other date was not touched
        assertThat(query(rita(), "{\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-07\"}}").path("rows").get(0).get(0).asDouble()).isEqualTo(expected(null, 7, 1));
    }

    @Test @Order(10)
    void refreshClearsTheLayerAndAuthorsOnlyMayAskForIt() throws Exception {
        refused(call("POST", "/api/v1/bi/datasets/" + DS + "/refresh", rita(), "{}"), 403, "DRS-9002");
        ok(call("POST", "/api/v1/bi/datasets/" + DS + "/refresh", wes(), "{}"));
    }

    @Test @Order(11)
    void aModelStartedFromPackKindsHasTheLinksThePacksDeclare() throws Exception {
        refused(call("POST", "/api/v1/bi/model/start", rita(), Map.of("kinds", List.of("trade"))), 403, "DRS-9002");
        JsonNode kinds = ok(call("GET", "/api/v1/bi/model/kinds", rita(), null)).path("tables");
        assertThat(kinds.toString()).contains("\"kind\":\"trade\"").contains("\"kind\":\"book\"");
        JsonNode draft = ok(call("POST", "/api/v1/bi/model/start", wes(), Map.of("kinds", List.of("trade", "book"), "name", "from-packs")));
        assertThat(draft.path("yaml").asText()).contains("dataset: from-packs").contains("kind: trade").contains("kind: book");
        assertThat(draft.path("relationships").toString()).contains("\"fromTable\":\"trade\"").contains("\"toTable\":\"book\"");   // trade.book -> book: the finance pack's graph
        JsonNode checked = ok(call("POST", "/api/v1/bi/datasets/validate", wes(), Map.of("yaml", draft.path("yaml").asText())));
        assertThat(checked.path("valid").asBoolean()).as(checked.toString()).isTrue();
        refused(call("POST", "/api/v1/bi/model/start", wes(), Map.of("kinds", List.of("no-such-kind"))), 422, "DRS-9020");
        ok(call("POST", "/api/v1/bi/model/infer", wes(), Map.of("kinds", List.of("trade", "book"))));
    }

    @Test @Order(12)
    void theEngineReportsItsLimitsAndCountersToBiAdministrators() throws Exception {
        refused(call("GET", "/api/v1/bi/engine", rita(), null), 403, "DRS-9002");
        JsonNode e = ok(call("GET", "/api/v1/bi/engine", admin(), null));
        assertThat(e.path("engine").asText()).isEqualTo("duckdb");
        assertThat(e.path("queries").asInt()).isGreaterThan(5);
        assertThat(e.path("limits").path("maxRows").asInt()).isEqualTo(100000);
    }

    @Test @Order(13)
    void aCopyInMyWorkspaceIsADraftOnlyItsOwnerReadsAndItIsNoLongerAModel() throws Exception {
        String yaml = java.nio.file.Files.readString(com.ash.drishti.server.TestRun.root().resolve("bi-dataset-folder/datasets/lake-test.dataset.yaml"))
                .replace("Trades: { expr: \"count(trades)\" }", "Trades: { expr: \"count(trades)\" }\n  Big: { expr: \"sum(trades.notional, where=trades.notional > 10000)\" }");
        JsonNode created = ok(call("POST", "/api/v1/bi/workspaces/me/items", wes(), Map.of("kind", "dataset", "title", "My copy", "body", yaml)));
        String id = created.path("id").asText();
        refused(call("GET", "/api/v1/bi/datasets/" + id, rita(), null), 404, "DRS-9001");                 // a personal draft is private
        JsonNode mine = ok(call("GET", "/api/v1/bi/datasets/" + id, wes(), null));
        assertThat(mine.path("measures").toString()).contains("Big");
        // a 2.1 dataset is no longer asked as a model since the compatibility reader went (2.2): the message says how to convert it
        var refusedModel = call("POST", "/api/v1/bi/mq", wes(), "{\"mq\":1,\"model\":\"dataset:" + id + "\",\"select\":{\"measures\":[\"Big\"]}}").andReturn().getResponse();
        assertThat(refusedModel.getContentAsString()).contains("DRS-9151").contains("drishti.py bi migrate --models");
    }
}
