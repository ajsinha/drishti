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
 * The languages of wave A over the whole stack, security on (docs/architecture/BI_DESIGN.md sections 3.3 and 3.4): the DEL function catalogue, checking an
 * expression against a model as the caller sees it (a masked field is refused in a filter, a hidden one does not exist), the converter for one expression
 * and for an item's YAML; Drishti SQL over the same model. A small lake read through a real file connector; readers rita (every desk, raw), hana (a
 * desk-head: DESK-A only) and ann (no raw: {@code mtm} masked).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/lang-api-${random.uuid}/identity.db", "drishti.packs.overlay=target/lang-api-overlay/added.yaml",
        "drishti.bi.dir=target/lang-api-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/lang-api-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=target/test-data/lang-api-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.lang-lake.plugin=file", "drishti.sources.connectors.lang-lake.kinds[0]=trade",
        "drishti.sources.connectors.lang-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.lang-lake.settings.layout.trade.columns=desk,book,currency,notional,mtm,tradeDate"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LanguagesApiTest {

    static final String DAY = "2026-10-08";
    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = Files.createTempDirectory("drishti-lang-api-lake");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String desk = i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C";
            lines.add("{\"id\":\"T-" + i + "\",\"tradeId\":\"T-" + i + "\",\"desk\":\"" + desk + "\",\"book\":\"BOOK-" + (i % 3 + 1) + "\",\"currency\":\"USD\",\"notional\":"
                    + 1000.0 * (i + 1) + ",\"mtm\":" + ((i % 5) - 2) * 100.0 + ",\"tradeDate\":\"" + DAY + "\"}");
        }
        Path f = root.resolve("lake").resolve(DAY).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
        r.add("drishti.sources.connectors.lang-lake.settings.root", () -> root.resolve("lake").toString());
    }

    static {
        try {
            Path folder = Path.of("target/lang-api-folder");
            Path datasets = Files.createDirectories(folder.resolve("datasets"));
            Path models = Files.createDirectories(folder.resolve("models"));
            Files.writeString(datasets.resolve("lang-trade.dataset.yaml"), """
                    rachana: 2
                    expressions: 2
                    dataset: lang-trade
                    version: 1.0.0
                    source: { connector: lang-lake, kind: trade }
                    grain: { keys: [id] }
                    columns:
                      desk:      { type: string }
                      book:      { type: string }
                      currency:  { type: string }
                      notional:  { type: decimal, aggregate: sum }
                      mtm:       { type: decimal, aggregate: sum }
                      tradeDate: { type: date }
                    security:
                      rows:
                        - { role: desk-head, where: "desk in user.desks" }
                        - { role: [bi-risk, bi-analyst, admin], where: "True" }
                      masked: [mtm]
                      hidden-for: { roles: [bi-analyst], columns: [book] }
                    """);
            Files.writeString(models.resolve("lang-model.model.yaml"), """
                    rachana: 2
                    expressions: 2
                    model: lang-model
                    version: 1.0.0
                    datasets:
                      trades: { dataset: lang-trade, role: fact }
                    measures:
                      Notional: { id: m_notional, expr: "sum(trades.notional)" }
                      MTM:      { id: m_mtm, expr: "sum(trades.mtm)" }
                      Trades:   { id: m_trades, expr: "count(trades)" }
                      Open MTM: { id: m_open, expr: "sum(trades.mtm, where=trades.currency == 'USD')" }
                      Per trade: { id: m_per, expr: "measure('m_notional') / measure('Trades')" }
                    fields:
                      trades.desk:     { display: Desk, folder: Organisation, description: The trading desk, category: none }
                      trades.notional: { display: Notional amount, format: amount0, folder: Amounts, summarise: sum }
                    perspectives:
                      desk-view: { title: Desk view, fields: [trades.desk, trades.notional], measures: [Notional, Trades] }
                    security:
                      hidden-for: { roles: [bi-analyst], fields: [trades.currency], measures: [Open MTM] }
                    tests:
                      - { name: DESK-A notional, sql: "SELECT MEASURE('Notional') FROM trades WHERE trades.desk = 'DESK-A' AS OF DATE '2026-10-08'", expect: 92000, role: bi-risk }
                      - { name: by desk, sql: "SELECT trades.desk, MEASURE('Trades') AS n FROM trades GROUP BY trades.desk AS OF DATE '2026-10-08'", row: { trades.desk: DESK-B }, column: n, expect: 8, role: bi-risk }
                    """);
            Files.writeString(datasets.resolve("lang-desk-sql.dataset.yaml"), """
                    rachana: 2
                    expressions: 2
                    dataset: lang-desk-sql
                    version: 1.0.0
                    source:
                      model: lang-model@1
                      sql: "SELECT trades.desk AS desk, MEASURE('Notional') AS notional, MEASURE('Trades') AS trades FROM trades GROUP BY trades.desk"
                    grain: { keys: [desk] }
                    columns:
                      desk:     { type: string, role: key }
                      notional: { type: decimal, aggregate: sum }
                      trades:   { type: integer, aggregate: sum }
                    """);
            Path python = Files.createDirectories(folder.resolve("python"));
            Files.writeString(python.resolve("notional-bucket.python.yaml"), """
                    rachana: 1
                    python: notional-bucket
                    title: Notional bucket
                    version: 1
                    interface: transform
                    inputs: [rows]
                    packages: [pandas]
                    target: server
                    source: |
                      def run(rows, params):
                          out = rows.copy()
                          out[params["column"]] = (out["notional"] >= 12000).map({True: "big", False: "small"})
                          return out
                    """);
            Files.writeString(datasets.resolve("lang-bucketed.dataset.yaml"), """
                    rachana: 2
                    expressions: 2
                    dataset: lang-bucketed
                    version: 1.0.0
                    extends: lang-trade@1
                    derive:
                      bucket:    { python: notional-bucket, inputs: [notional], type: string }
                      notionalK: { expr: "notional / 1000", type: decimal }
                    """);
            Files.writeString(models.resolve("lang-bucket-view.model.yaml"), """
                    rachana: 2
                    expressions: 2
                    model: lang-bucket-view
                    version: 1.0.0
                    datasets:
                      trades: { dataset: lang-bucketed, role: fact }
                    measures:
                      Trades: { expr: "count(trades)" }
                      K:      { expr: "sum(trades.notionalK)" }
                    """);
            Files.writeString(models.resolve("lang-guarded.model.yaml"), """
                    rachana: 2
                    expressions: 2
                    model: lang-guarded
                    version: 1.0.0
                    audience: { roles: [bi-risk, admin] }
                    datasets:
                      trades: { dataset: lang-trade, role: fact }
                    measures:
                      Notional: { id: m_notional, expr: "sum(trades.notional)" }
                      Trades:   { id: m_trades, expr: "count(trades)" }
                    """);
            Path reports = Files.createDirectories(folder.resolve("reports"));
            Files.writeString(reports.resolve("lang-guarded-report.report.yaml"), """
                    report: lang-guarded-report
                    title: Guarded report
                    model: lang-guarded@1
                    pages:
                      - id: main
                        title: Main
                        visuals:
                          - { id: by-desk, type: bar, title: By desk, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trades.desk], values: [Notional] } }
                    """);
            Files.writeString(reports.resolve("lang-local-report.report.yaml"), """
                    rachana: 2
                    report: lang-local-report
                    title: Own measures and a perspective
                    model: lang-model@1
                    perspective: desk-view
                    measures:
                      Half notional: { expr: "measure('Notional') / 2", format: amount0 }
                    pages:
                      - id: main
                        title: Main
                        visuals:
                          - { id: by-desk, type: table, title: By desk, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { columns: [trades.desk, Notional, Half notional] } }
                    """);
            Files.writeString(models.resolve("lang-desk-view.model.yaml"), """
                    rachana: 2
                    expressions: 2
                    model: lang-desk-view
                    version: 1.0.0
                    datasets:
                      desks: { dataset: lang-desk-sql, role: fact }
                    measures:
                      Desk notional: { expr: "sum(desks.notional)" }
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
        for (String u : List.of("rita:bi-risk:", "hana:desk-head:DESK-A", "hugo:desk-head:DESK-A", "ann:bi-analyst:")) {
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

    JsonNode validate(String who, String expr, String scope) throws Exception {
        return ok(call("POST", "/api/v1/bi/expr/validate", reader(who), Map.of("expr", expr, "scope", scope, "model", "lang-model@1")));
    }

    static List<String> codes(JsonNode v) {
        List<String> out = new ArrayList<>();
        v.path("problems").forEach(p -> out.add(p.path("code").asText()));
        return out;
    }

    // ---- the plan ------------------------------------------------------------------------------------------------------

    @Test @Order(10)
    void anAnswerNamesItsPlanAndSignatureAndIsSharedByReadersOfTheSameSecurity() throws Exception {
        Map<String, Object> q = Map.of("mq", 1, "model", "lang-model@1", "select", Map.of("fields", List.of("trades.desk"), "measures", List.of("Notional", "Trades")),
                "time", Map.of("as-of", DAY));
        JsonNode first = ok(call("POST", "/api/v1/bi/mq", reader("hana"), q));
        JsonNode again = ok(call("POST", "/api/v1/bi/mq", as("hugo", "desk-head"), q));      // another desk-head of DESK-A: the same effective security
        JsonNode rita = ok(call("POST", "/api/v1/bi/mq", reader("rita"), q));
        assertThat(first.path("meta").path("plan").asText()).startsWith("p_").isEqualTo(rita.path("meta").path("plan").asText());
        assertThat(again.path("meta").path("signature").asText()).isEqualTo(first.path("meta").path("signature").asText());
        assertThat(again.path("meta").path("cached").asBoolean()).isTrue();
        assertThat(again.path("rows")).isEqualTo(first.path("rows"));
        assertThat(rita.path("meta").path("signature").asText()).isNotEqualTo(first.path("meta").path("signature").asText());
        assertThat(rita.path("rows").size()).isEqualTo(3);
        JsonNode explained = ok(call("POST", "/api/v1/bi/mq", reader("hana"), Map.of("mq", 1, "model", "lang-model@1", "select", Map.of("fields", List.of("trades.desk"),
                "measures", List.of("Notional")), "where", List.of(Map.of("field", "trades.currency", "op", "eq", "value", "USD")), "time", Map.of("as-of", DAY),
                "options", Map.of("explain", true))));
        String tree = explained.path("meta").path("explain").toString();
        assertThat(tree).contains("SecureScan").contains("\"rules\":1").contains("\"pushed\"").doesNotContain("DESK-A");
        if (System.getProperty("capture") != null) {
            System.out.println("CAPTURE6 " + first.path("meta"));
            System.out.println("CAPTURE7 " + again.path("meta"));
            System.out.println("CAPTURE8 " + explained.path("meta").path("explain"));
        }
    }

    // ---- Drishti SQL --------------------------------------------------------------------------------------------------

    JsonNode sql(String who, String statement) throws Exception {
        return ok(call("POST", "/api/v1/bi/sql", reader(who), Map.of("model", "lang-model@1", "sql", statement)));
    }

    @Test @Order(20)
    void drishtiSqlAnswersAsTheCallerWithTheMqsPlan() throws Exception {
        String statement = "SELECT trades.desk AS desk, MEASURE('Notional') AS notional, COUNT(*) AS n FROM trades GROUP BY trades.desk ORDER BY desk AS OF DATE '" + DAY + "'";
        JsonNode rita = sql("rita", statement);
        JsonNode hana = sql("hana", statement);
        assertThat(rita.path("rows").size()).isEqualTo(3);
        assertThat(rita.path("columns").get(1).path("name").asText()).isEqualTo("notional");
        assertThat(rita.path("meta").path("shape").asText()).isEqualTo("mq");
        assertThat(hana.path("rows").size()).isEqualTo(1);
        assertThat(hana.path("rows").get(0).get(0).asText()).isEqualTo("DESK-A");
        JsonNode viaMq = ok(call("POST", "/api/v1/bi/mq", reader("hana"), Map.of("mq", 1, "model", "lang-model@1", "select", Map.of("fields", List.of("trades.desk"),
                "measures", List.of("Notional")), "time", Map.of("as-of", DAY))));
        assertThat(hana.path("rows").get(0).get(1).asDouble()).isEqualTo(viaMq.path("rows").get(0).get(1).asDouble());
        var csv = call("POST", "/api/v1/bi/sql", reader("rita"), Map.of("model", "lang-model@1", "sql", statement, "format", "csv")).andReturn().getResponse();
        assertThat(csv.getContentAsString()).startsWith("desk,notional,n\r\nDESK-A,92000.0,8");
        JsonNode explain = sql("hana", "EXPLAIN " + statement);
        assertThat(explain.path("text").asText()).contains("SecureScan trades").contains("rules: 1 applied").doesNotContain("DESK-A");
        JsonNode composite = sql("rita", "WITH d AS (SELECT trades.desk AS desk, MEASURE('Notional') AS notional FROM trades GROUP BY trades.desk AS OF DATE '" + DAY
                + "') SELECT d.desk, d.notional, RANK() OVER (ORDER BY d.notional DESC) AS r FROM d ORDER BY r");
        assertThat(composite.path("meta").path("shape").asText()).isEqualTo("composite");
        assertThat(composite.path("rows").get(0).get(2).asInt()).isEqualTo(1);
        var refused = call("POST", "/api/v1/bi/sql", reader("rita"), Map.of("model", "lang-model@1", "sql", "SELECT trades.desk FROM trades JOIN desks ON trades.desk = desks.x"))
                .andReturn().getResponse();
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("DRS-9171");
        JsonNode v = ok(call("POST", "/api/v1/bi/sql/validate", reader("ann"), Map.of("model", "lang-model@1", "sql", "SELECT trades.book FROM trades")));
        assertThat(v.path("valid").asBoolean()).isFalse();
        assertThat(v.path("problems").get(0).path("code").asText()).as("book is hidden from ann: it does not exist").isEqualTo("DRS-9171");
        JsonNode ok = ok(call("POST", "/api/v1/bi/sql/validate", reader("rita"), Map.of("model", "lang-model@1", "sql", statement)));
        assertThat(ok.path("valid").asBoolean()).isTrue();
        assertThat(ok.path("plan").asText()).isEqualTo(viaPlan("rita"));
        if (System.getProperty("capture") != null) {
            System.out.println("CAPTURE9 " + rita);
            System.out.println("CAPTURE10 " + explain.path("text").asText().replace("\n", " || "));
            System.out.println("CAPTURE11 " + composite);
            System.out.println("CAPTURE12 " + refused.getContentAsString());
        }
    }

    String viaPlan(String who) throws Exception {
        return ok(call("POST", "/api/v1/bi/mq/validate", reader(who), Map.of("mq", 1, "model", "lang-model@1", "select", Map.of("fields", List.of("trades.desk"),
                "measures", List.of("Notional", "n")), "define", Map.of("n", "count(trades)"), "sort", List.of(Map.of("by", "trades.desk")), "time",
                Map.of("as-of", DAY)))).path("plan").asText();
    }

    @Test @Order(21)
    void aSqlViewDatasetIsAskedAsTheReaderAndAModelsSqlTestsRun() throws Exception {
        Map<String, Object> q = Map.of("mq", 1, "model", "lang-desk-view@1", "select", Map.of("fields", List.of("desks.desk"), "measures", List.of("Desk notional")), "time",
                Map.of("as-of", DAY));
        JsonNode rita = ok(call("POST", "/api/v1/bi/mq", reader("rita"), q));
        JsonNode hana = ok(call("POST", "/api/v1/bi/mq", reader("hana"), q));
        assertThat(rita.path("rows").size()).isEqualTo(3);
        assertThat(hana.path("rows").size()).as("the view's statement ran as hana: her desk only").isEqualTo(1);
        JsonNode tests = ok(call("POST", "/api/v1/bi/items/file:model:lang-model/tests", admin(), Map.of()));
        assertThat(tests.path("total").asInt()).as(tests.toString()).isEqualTo(2);
        assertThat(tests.path("passed").asInt()).as(tests.toString()).isEqualTo(2);
    }

    @Test @Order(22)
    void aPythonColumnIsComputedOverTheReadersRowsAndTheLineageReachesThePackKind() throws Exception {
        Map<String, Object> q = Map.of("mq", 1, "model", "lang-bucket-view@1", "select", Map.of("fields", List.of("trades.bucket"), "measures", List.of("Trades", "K")), "time",
                Map.of("as-of", DAY));
        JsonNode rita = ok(call("POST", "/api/v1/bi/mq", reader("rita"), q));
        Map<String, Integer> buckets = new java.util.TreeMap<>();
        rita.path("rows").forEach(r -> buckets.put(r.get(0).asText(), r.get(1).asInt()));
        assertThat(buckets).as(rita.toString()).containsEntry("big", 13).containsEntry("small", 11);
        JsonNode hana = ok(call("POST", "/api/v1/bi/mq", reader("hana"), q));
        int hanaTrades = 0;
        for (JsonNode r : hana.path("rows")) {
            hanaTrades += r.get(1).asInt();
        }
        assertThat(hanaTrades).as("the base's rows were asked as hana: DESK-A only").isEqualTo(8);
        JsonNode lineage = ok(call("GET", "/api/v1/bi/lineage/lang-bucket-view@1", reader("rita"), null));
        assertThat(lineage.path("datasets").path("trades").toString()).contains("lang-bucketed").contains("lang-trade").contains("pack-kind").contains("trade");
        assertThat(lineage.path("problems").size()).isZero();
        if (System.getProperty("capture") != null) {
            System.out.println("CAPTURE13 " + lineage);
        }
    }

    // ---- model semantics and access ------------------------------------------------------------------------------------

    static JsonNode field(JsonNode describe, String name) {
        for (JsonNode d : describe.path("datasets")) {
            for (JsonNode f : d.path("fields")) {
                if (name.equals(f.path("field").asText())) {
                    return f;
                }
            }
        }
        return null;
    }

    static List<String> measureNames(JsonNode describe) {
        List<String> out = new ArrayList<>();
        describe.path("measures").forEach(m -> out.add(m.path("name").asText()));
        return out;
    }

    @Test @Order(30)
    void describeCarriesFieldPropertiesPerspectivesAndHidesWhatIsHiddenForTheReader() throws Exception {
        JsonNode rita = ok(call("GET", "/api/v1/bi/models/lang-model@1/describe", reader("rita"), null));
        assertThat(field(rita, "trades.desk").path("display").asText()).isEqualTo("Desk");
        assertThat(field(rita, "trades.desk").path("folder").asText()).isEqualTo("Organisation");
        assertThat(field(rita, "trades.notional").path("format").asText()).isEqualTo("amount0");
        assertThat(field(rita, "trades.notional").path("summarise").asText()).isEqualTo("sum");
        assertThat(rita.path("perspectives").toString()).contains("desk-view");
        assertThat(field(rita, "trades.currency")).isNotNull();
        JsonNode persp = ok(call("GET", "/api/v1/bi/models/lang-model@1/describe?perspective=desk-view", reader("rita"), null));
        assertThat(field(persp, "trades.currency")).isNull();
        assertThat(measureNames(persp)).containsExactlyInAnyOrder("Notional", "Trades");
        JsonNode ann = ok(call("GET", "/api/v1/bi/models/lang-model@1/describe", reader("ann"), null));
        assertThat(field(ann, "trades.currency")).as("hidden-for ann's role: absent").isNull();
        assertThat(measureNames(ann)).doesNotContain("Open MTM").contains("Notional");
        var refused = call("POST", "/api/v1/bi/mq", reader("ann"), Map.of("mq", 1, "model", "lang-model@1", "select", Map.of("fields", List.of("trades.currency"),
                "measures", List.of("Trades")))).andReturn().getResponse();
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("DRS-9152").contains("no field 'trades.currency'");
        var measure = call("POST", "/api/v1/bi/mq", reader("ann"), Map.of("mq", 1, "model", "lang-model@1", "select", Map.of("measures", List.of("Open MTM"))))
                .andReturn().getResponse();
        assertThat(measure.getContentAsString()).contains("DRS-9152").contains("no measure 'Open MTM'");
        assertThat(codes(validate("ann", "sum(trades.notional, where=trades.currency == 'USD')", "aggregate"))).contains("DRS-9152");
    }

    @Test @Order(31)
    void aReportOnlyReaderAsksWithinTheReportsEnvelope() throws Exception {
        var direct = call("POST", "/api/v1/bi/mq", reader("hana"), Map.of("mq", 1, "model", "lang-guarded@1", "select", Map.of("measures", List.of("Notional"))))
                .andReturn().getResponse();
        assertThat(direct.getContentAsString()).as("no read on the model: no free querying").contains("DRS-9151");
        String visual = "{\"page\":\"main\",\"visual\":\"by-desk\",\"state\":{\"dates\":{\"from\":\"" + DAY + "\",\"to\":\"" + DAY + "\"}}}";
        JsonNode viaReport = ok(call("POST", "/api/v1/bi/reports/file:report:lang-guarded-report/query", reader("hana"), visual));
        assertThat(viaReport.path("rows").size()).as(viaReport.toString()).isEqualTo(1);
        assertThat(viaReport.path("rows").get(0).get(0).asText()).as("her own row rule still applies").isEqualTo("DESK-A");
        String outside = "{\"page\":\"main\",\"visual\":\"by-desk\",\"state\":{\"carry\":[{\"field\":\"trades.currency\",\"op\":\"eq\",\"values\":[\"USD\"]}],"
                + "\"dates\":{\"from\":\"" + DAY + "\",\"to\":\"" + DAY + "\"}}}";
        var out = call("POST", "/api/v1/bi/reports/file:report:lang-guarded-report/query", reader("hana"), outside).andReturn().getResponse();
        assertThat(out.getContentAsString()).as("a filter the report never makes").contains("DRS-9151").contains("this report cannot ask that");
        JsonNode rita = ok(call("POST", "/api/v1/bi/reports/file:report:lang-guarded-report/query", reader("rita"), outside));
        assertThat(rita.path("rows").size()).as("with read on the model: free").isEqualTo(3);
        JsonNode envelope = ok(call("GET", "/api/v1/bi/reports/file:report:lang-guarded-report/envelope", reader("rita"), null));
        assertThat(envelope.path("fields").toString()).contains("trades.desk");
        assertThat(envelope.path("measures").toString()).contains("Notional").doesNotContain("Trades");
        var notOwner = call("POST", "/api/v1/bi/models/lang-model@1/preview-as", reader("rita"), Map.of("role", "bi-analyst")).andReturn().getResponse();
        assertThat(notOwner.getStatus()).as("only a bi-admin or the owner previews as another role").isEqualTo(403);
        JsonNode asAnalyst = ok(call("POST", "/api/v1/bi/models/lang-model@1/preview-as", admin(), Map.of("role", "bi-analyst",
                "query", Map.of("select", Map.of("fields", List.of("trades.desk"), "measures", List.of("Notional", "MTM"))))));
        assertThat(asAnalyst.path("previewAs").path("role").asText()).isEqualTo("bi-analyst");
        assertThat(asAnalyst.path("rows").size()).as(asAnalyst.toString()).isEqualTo(3);
        assertThat(asAnalyst.path("meta").path("masked").toString()).as("the analyst lacks raw: MTM (over the masked mtm) masked").contains("MTM");
        JsonNode asDeskHead = ok(call("POST", "/api/v1/bi/models/lang-model@1/preview-as", admin(), Map.of("role", "desk-head",
                "query", Map.of("select", Map.of("fields", List.of("trades.desk"), "measures", List.of("Notional"))))));
        assertThat(asDeskHead.path("rows").size()).as("no user attributes: desk in user.desks gives no rows").isZero();
        assertThat(ok(call("GET", "/api/v1/admin/audit?limit=50", admin(), null)).findValuesAsText("action")).contains("bi-model-preview-as");
        JsonNode opened = ok(call("GET", "/api/v1/bi/reports/file:report:lang-guarded-report", reader("hana"), null));
        assertThat(opened.path("valid").asBoolean()).as("a reader of the report opens it without read on the model: " + opened.path("problems")).isTrue();
        assertThat(opened.path("dataset").path("envelope").asBoolean()).as("the Fields pane shows the report's envelope").isTrue();
        assertThat(opened.path("dataset").path("measures").toString()).contains("Notional").doesNotContain("\"Trades\"");
        JsonNode hanaEnvelope = ok(call("GET", "/api/v1/bi/reports/file:report:lang-guarded-report/envelope", reader("hana"), null));
        assertThat(hanaEnvelope.path("measures").toString()).contains("Notional");
        JsonNode impact = ok(call("GET", "/api/v1/bi/models/lang-guarded@1/impact?target=Notional", reader("rita"), null));
        assertThat(impact.path("consumers").toString()).contains("lang-guarded-report");
        JsonNode none = ok(call("GET", "/api/v1/bi/models/lang-guarded@1/impact?target=Trades", reader("rita"), null));
        assertThat(none.path("consumers").size()).isZero();
        if (System.getProperty("capture") != null) {
            System.out.println("CAPTURE14 " + out.getContentAsString());
            System.out.println("CAPTURE15 " + envelope);
            System.out.println("CAPTURE16 " + impact);
            System.out.println("CAPTURE17 " + field(ok(call("GET", "/api/v1/bi/models/lang-model@1/describe", reader("rita"), null)), "trades.notional"));
        }
    }

    // ---- DEL ---------------------------------------------------------------------------------------------------------

    @Test @Order(1)
    void theFunctionCatalogueIsServed() throws Exception {
        JsonNode f = ok(call("GET", "/api/v1/bi/expr/functions", reader("rita"), null));
        assertThat(f.path("language").asText()).isEqualTo("DEL");
        assertThat(f.path("functions").size()).isGreaterThan(50);
        JsonNode sum = null;
        for (JsonNode x : f.path("functions")) {
            if ("sum".equals(x.path("name").asText())) {
                sum = x;
            }
        }
        assertThat(sum).isNotNull();
        assertThat(sum.path("signature").asText()).isEqualTo("sum(x, where=None)");
        assertThat(sum.path("params").get(1).path("keywordOnly").asBoolean()).isTrue();
        assertThat(sum.path("examples").size()).isGreaterThanOrEqualTo(2);
        JsonNode row = ok(call("GET", "/api/v1/bi/expr/functions?scope=row", reader("rita"), null));
        assertThat(row.path("functions").toString()).doesNotContain("\"name\":\"sum\"").contains("\"name\":\"upper\"");
    }

    @Test @Order(2)
    void anExpressionIsCheckedAgainstTheModelAsTheCallerSeesIt() throws Exception {
        JsonNode v = validate("rita", "sum(trades.notional, where=trades.desk == 'DESK-A') / measure('m_trades')", "aggregate");
        if (System.getProperty("capture") != null) {
            System.out.println("CAPTURE1 " + v);
            System.out.println("CAPTURE2 " + validate("rita", "trades.notional * 2", "aggregate"));
            System.out.println("CAPTURE3 " + validate("ann", "count(trades, where=trades.mtm < 0)", "aggregate"));
            System.out.println("CAPTURE4 " + validate("rita", "[Notional] / [Trades]", "aggregate"));
            System.out.println("CAPTURE5 " + ok(call("POST", "/api/v1/bi/expr/convert", reader("rita"), Map.of("expr", "sum(trades.mtm, trades.status == 'Live')"))));
        }
        assertThat(v.path("valid").asBoolean()).as(v.toString()).isTrue();
        assertThat(v.path("syntax").asText()).isEqualTo("del2");
        assertThat(v.path("uses").path("measures").toString()).contains("Trades");
        assertThat(v.path("type").asText()).isEqualTo("decimal");

        JsonNode bare = validate("rita", "trades.notional * 2", "aggregate");
        assertThat(bare.path("valid").asBoolean()).isFalse();
        assertThat(bare.path("problems").get(0).path("fix").asText()).isEqualTo("wrap it: sum(trades.notional)");
        assertThat(bare.path("problems").get(0).path("at").path("start").asInt()).isZero();

        // ann: mtm is masked (a filter on it is refused); book is hidden from her role (it does not exist)
        assertThat(codes(validate("ann", "count(trades, where=trades.mtm < 0)", "aggregate"))).contains("DRS-9161");
        assertThat(codes(validate("rita", "count(trades, where=trades.mtm < 0)", "aggregate"))).isEmpty();
        assertThat(codes(validate("ann", "distinct(trades.book)", "aggregate"))).contains("DRS-9152");
        assertThat(codes(validate("rita", "distinct(trades.book)", "aggregate"))).isEmpty();

        JsonNode row = ok(call("POST", "/api/v1/bi/expr/validate", reader("rita"), Map.of("expr", "notional / 1000000 if currency == 'USD' else None", "scope", "row",
                "model", "lang-model@1", "alias", "trades")));
        assertThat(row.path("valid").asBoolean()).as(row.toString()).isTrue();
        assertThat(row.path("alias").asText()).isEqualTo("trades");

        JsonNode old = validate("rita", "[Notional] / [Trades]", "aggregate");
        assertThat(old.path("syntax").asText()).isEqualTo("legacy");
        assertThat(old.path("converted").asText()).isEqualTo("measure('Notional') / measure('Trades')");
        assertThat(codes(old)).containsExactly("DRS-9100");
        assertThat(old.path("valid").asBoolean()).as("refused since the end of wave A").isFalse();
    }

    /** A report's own measures travel as the model query's define, and its perspective narrows its Fields pane. */
    @Test @Order(5)
    void aReportsOwnMeasuresAndPerspectiveAreHonoured() throws Exception {
        JsonNode rep = ok(call("GET", "/api/v1/bi/reports/file:report:lang-local-report", reader("rita"), null));
        assertThat(rep.path("valid").asBoolean()).as(rep.path("problems").toString()).isTrue();
        assertThat(rep.path("report").path("measures").has("Half notional")).isTrue();
        assertThat(rep.path("report").path("perspective").asText()).isEqualTo("desk-view");
        assertThat(rep.path("dataset").path("perspective").asText()).as("the Fields pane is the perspective").isEqualTo("desk-view");
        assertThat(rep.path("dataset").path("measures").findValuesAsText("name")).containsExactlyInAnyOrder("Notional", "Trades");
        String visual = "{\"page\":\"main\",\"visual\":\"by-desk\",\"state\":{\"dates\":{\"from\":\"" + DAY + "\",\"to\":\"" + DAY + "\"}}}";
        JsonNode rows = ok(call("POST", "/api/v1/bi/reports/file:report:lang-local-report/query", reader("rita"), visual));
        assertThat(rows.path("rows").size()).as(rows.toString()).isEqualTo(3);
        for (JsonNode r : rows.path("rows")) {
            assertThat(r.get(2).asDouble()).as("Half notional = Notional / 2").isEqualTo(r.get(1).asDouble() / 2);
        }
        JsonNode clash = ok(call("POST", "/api/v1/bi/reports/validate", admin(), Map.of("yaml", """
                rachana: 2
                report: clash
                model: lang-model@1
                measures:
                  Notional: "sum(trades.notional) * 2"
                pages: [{ id: p, title: P, visuals: [] }]
                """)));
        assertThat(clash.path("problems").findValuesAsText("code")).contains("DRS-9154");
    }

    /** The designer's unsaved model is checked as it stands, and a measure a model query would refuse (two facts with no path) is said while it is written. */
    @Test @Order(4)
    void anUnsavedDraftIsCheckedAndAggregatesAcrossUnjoinedFactsAreFlagged() throws Exception {
        String draft = """
                rachana: 2
                expressions: 2
                model: lang-draft
                version: 1.0.0
                datasets:
                  trades: { dataset: lang-trade, role: fact }
                  others: { dataset: lang-trade, role: fact }
                measures:
                  Notional: { expr: "sum(trades.notional)" }
                """;
        JsonNode fine = ok(call("POST", "/api/v1/bi/expr/validate", reader("rita"), Map.of("expr", "sum(trades.notional) / measure('Notional')", "yaml", draft)));
        assertThat(fine.path("valid").asBoolean()).as(fine.toString()).isTrue();
        assertThat(fine.path("draft").asBoolean()).isTrue();
        JsonNode across = ok(call("POST", "/api/v1/bi/expr/validate", reader("rita"), Map.of("expr", "sum(trades.notional, where=others.desk == 'DESK-A')",
                "yaml", draft)));
        assertThat(codes(across)).as(across.toString()).contains("DRS-9156");
        JsonNode dataset = ok(call("POST", "/api/v1/bi/expr/validate", reader("rita"), Map.of("expr", "notional * 2", "scope", "row", "yaml", """
                rachana: 2
                dataset: lang-unsaved
                version: 1.0.0
                extends: lang-trade@1
                """)));
        assertThat(dataset.path("valid").asBoolean()).as(dataset.toString()).isTrue();
    }

    @Test @Order(3)
    void theConverterRewritesOneExpressionOrAWholeItemKeepingComments() throws Exception {
        JsonNode one = ok(call("POST", "/api/v1/bi/expr/convert", reader("rita"), Map.of("expr", "sum(trades.mtm, trades.status == 'Live')")));
        assertThat(one.path("converted").asText()).isEqualTo("sum(trades.mtm, where=trades.status == 'Live')");
        assertThat(one.path("changed").asBoolean()).isTrue();
        String yaml = """
                rachana: 1
                dataset: x   # a comment that stays
                measures:
                  MTM: { expr: "sum(trades.mtm)" }            # unchanged
                  Ratio: { expr: "[MTM] / [Trades]", format: signed2 }
                  Short: "count(trades, trades.mtm < 0)"
                security:
                  rows:
                    - { role: risk, where: "true" }
                """;
        JsonNode item = ok(call("POST", "/api/v1/bi/expr/convert", reader("rita"), Map.of("yaml", yaml)));
        String out = item.path("yaml").asText();
        assertThat(out).contains("# a comment that stays").contains("# unchanged").contains("expressions: 2")
                .contains("Ratio: { expr: \"measure('MTM') / measure('Trades')\", format: signed2 }")
                .contains("Short: \"count(trades, where=trades.mtm < 0)\"")
                .contains("where: \"True\"");
        assertThat(item.path("expressions").asInt()).isEqualTo(4);
        assertThat(item.path("changes").size()).isEqualTo(3);
    }
}
