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

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.gov.ImpactAnalyzer;
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
 * Composed datasets over the whole stack (RUPAKA.md section 20.10, docs/guides/DATASETS.md section 11): two datasets over one small lake, each with its own row
 * rules and masks, and datasets composed of them. Checks the SQL, that masks and row rules of <em>each source</em> apply before the join for two readers, the
 * refusal of a masked join key, the fan-out warning, the refusal of a cycle, key types, and that impact analysis sees a composed dataset break when a source changes.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/bi-compose-${random.uuid}/identity.db", "drishti.packs.overlay=target/bi-compose-overlay/added.yaml",
        "drishti.bi.dir=target/bi-compose-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/bi-compose-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=target/test-data/bi-compose-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.test-lake.plugin=file", "drishti.sources.connectors.test-lake.kinds[0]=trade",
        "drishti.sources.connectors.test-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.test-lake.settings.layout.trade.columns=desk,book,currency,notional,mtm,counterparty.id,tradeDate",
        "drishti.bi.transform.python=../drishti-console/.venv/bin/python", "drishti.bi.transform.runner=../drishti-console/core/transform_cli.py"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComposedDatasetApiTest {

    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired ImpactAnalyzer impact;
    @Autowired BiService bi;
    final ObjectMapper json = new ObjectMapper();

    static final String HEAD = """
            rachana: 1
            title: %s
            tables:
              trades:
                source: { connector: test-lake, kind: trade }
                columns:
                  desk: string
                  currency: string
                  counterpartyId: { path: counterparty.id, type: string }
                  notional: decimal
                  mtm: decimal
                  tradeDate: date
            """;

    static void write(String file, String text) {
        writeIn("datasets", file + ".dataset.yaml", text);
    }

    static void writeIn(String folder, String file, String text) {
        try {
            Path dir = Path.of("target/bi-compose-folder/" + folder);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(file), text);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static boolean pythonInstalled() {
        return Files.isExecutable(Path.of("../drishti-console/.venv/bin/python"));
    }

    static {
        // source one: P&L by desk and counterparty; a desk-head sees their own desks only
        write("lake-a", "dataset: lake-a\n" + HEAD.formatted("Lake A") + """
                measures:
                  MTM: { expr: "sum(trades.mtm)" }
                  Trades: { expr: "count(trades)" }
                security:
                  rows:
                    - { role: desk-head, where: "trades.desk in user.desks" }
                    - { role: bi-risk, where: "true" }
                    - { role: bi-analyst, where: "true" }
                    - { role: admin, where: "true" }
                    - { role: raw-author, where: "true" }
                  masked: [mtm]
                """);
        // source two: gross notional, every reader sees every row
        write("lake-b", "dataset: lake-b\n" + HEAD.formatted("Lake B") + """
                measures:
                  Gross: { expr: "sum(trades.notional)" }
                security:
                  rows:
                    - { role: desk-head, where: "true" }
                    - { role: bi-risk, where: "true" }
                    - { role: bi-analyst, where: "true" }
                    - { role: admin, where: "true" }
                    - { role: raw-author, where: "true" }
                """);
        // composed on the counterparty (a column the server masks for a reader without raw)
        write("lake-ab", """
                rachana: 1
                dataset: lake-ab
                title: A and B per counterparty
                from:
                  a: { dataset: lake-a, by: ["trades.counterpartyId as cp"], measures: [MTM] }
                  b: { dataset: lake-b, by: ["trades.counterpartyId as cp"], measures: [Gross] }
                join:
                  - { left: a, right: b, on: [cp], type: inner }
                measures:
                  MTM per gross: "measure('MTM') / measure('Gross')"
                """);
        // composed on the desk: source A is cut to the desk-head's desks, B is not
        write("lake-ab-desk", """
                rachana: 1
                dataset: lake-ab-desk
                title: A and B per desk
                from:
                  a: { dataset: lake-a, by: ["trades.desk"], measures: [MTM, Trades] }
                  b: { dataset: lake-b, by: ["trades.desk"], measures: [Gross] }
                join:
                  - { left: a, right: b, on: [desk], type: left }
                measures:
                  MTM per gross: "measure('MTM') / measure('Gross')"
                """);
        // a join that repeats rows: A by desk and currency, B by desk and currency, joined on desk only
        write("lake-fanout", """
                rachana: 1
                dataset: lake-fanout
                title: Fan out
                from:
                  a: { dataset: lake-a, by: ["trades.desk", "trades.currency"], measures: [MTM] }
                  b: { dataset: lake-b, by: ["trades.desk", "trades.currency as ccy"], measures: [Gross] }
                join:
                  - { left: a, right: b, on: [desk], type: inner }
                """);
        write("lake-fanout-ok", """
                rachana: 1
                dataset: lake-fanout-ok
                title: Fan out accepted
                from:
                  a: { dataset: lake-a, by: ["trades.desk", "trades.currency"], measures: [MTM] }
                  b: { dataset: lake-b, by: ["trades.desk", "trades.currency as ccy"], measures: [Gross] }
                join:
                  - { left: a, right: b, on: [desk], type: inner, allow-repeat: true }
                """);
        writeIn("python", "merge-ab.python.yaml", """
                rachana: 1
                python: merge-ab
                interface: transform
                inputs: [lake-a, lake-b]
                packages: [pandas]
                target: server
                timeout: 20
                memory: 512
                source: |
                  def run(a, b):
                      m = a.merge(b, on="desk", how="left").rename(columns={"MTM": "mtm", "Gross": "gross"})
                      m["share"] = m["mtm"] / m["gross"]
                      return m[["desk", "mtm", "gross", "share"]]
                """);
        // the same Transform, columns declared (a contract) and not declared (learned from the first result)
        String derived = """
                rachana: 1
                dataset: %s
                title: Derived
                derived:
                  transform: merge-ab
                  inputs:
                    a: { dataset: lake-a, by: ["trades.desk"], measures: [MTM] }
                    b: { dataset: lake-b, by: ["trades.desk"], measures: [Gross] }
                """;
        write("lake-derived", derived.formatted("lake-derived") + """
                columns: { desk: string, mtm: decimal, gross: decimal, share: decimal }
                measures:
                  Share: "ratio(sum(result.mtm), sum(result.gross))"
                """);
        write("lake-derived-learned", derived.formatted("lake-derived-learned"));
        String multi = """
                rachana: 1
                report: %s
                title: Several datasets
                dataset: lake-ab-desk
                %s
                pages:
                  - id: p
                    title: P
                    visuals:
                      - { id: by-desk, type: bar, title: MTM and gross by desk, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [joined.desk], values: [MTM, Gross] } }
                      - { id: pick, type: slicer, title: Desk, dataset: lake-b, at: { x: 6, y: 0, w: 3, h: 2 }, wells: { field: [trades.desk] }, format: { mode: list, scope: report } }
                      - { id: b-bar, type: bar, title: Gross by desk (lake-b), dataset: lake-b, at: { x: 0, y: 4, w: 6, h: 4 }, wells: { axis: [trades.desk], values: [Gross] } }
                """;
        writeIn("reports", "lake-multi.report.yaml", multi.formatted("lake-multi", "links:\n  - fields: [trades.desk, joined.desk]"));
        writeIn("reports", "lake-multi-nolink.report.yaml", multi.formatted("lake-multi-nolink", ""));
        writeIn("reports", "lake-multi-bad.report.yaml", multi.formatted("lake-multi-bad", "links:\n  - fields: [trades.desk, nothing.here]").replace("dataset: lake-b,", "dataset: no-such-dataset,"));
        write("lake-cycle-1", "rachana: 1\ndataset: lake-cycle-1\nfrom:\n  x: { dataset: lake-cycle-2, by: [\"joined.k\"], measures: [M] }\n");
        write("lake-cycle-2", "rachana: 1\ndataset: lake-cycle-2\nfrom:\n  x: { dataset: lake-cycle-1, by: [\"joined.k\"], measures: [M] }\n");
        write("lake-types", """
                rachana: 1
                dataset: lake-types
                from:
                  a: { dataset: lake-a, by: ["trades.tradeDate as k"], measures: [MTM] }
                  b: { dataset: lake-b, by: ["trades.desk as k"], measures: [Gross] }
                join:
                  - { left: a, right: b, on: [k] }
                """);
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = Files.createTempDirectory("drishti-bi-compose-lake");
        for (String day : List.of("2026-10-07", "2026-10-08")) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                String desk = i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C";
                lines.add("{\"id\":\"T-" + day.substring(8) + "-" + i + "\",\"tradeId\":\"T-" + day.substring(8) + "-" + i + "\",\"desk\":\"" + desk + "\",\"currency\":\"" + (i % 2 == 0 ? "USD" : "EUR")
                        + "\",\"counterparty\":{\"id\":\"CP-" + (i % 4) + "\"},\"notional\":" + 1000.0 * (i + 1) + ",\"mtm\":" + ((i % 5) - 2) * 100.0 + ",\"tradeDate\":\"" + day + "\"}");
            }
            Path f = root.resolve("lake").resolve(day).resolve("trade.jsonl");
            Files.createDirectories(f.getParent());
            Files.write(f, lines, StandardCharsets.UTF_8);
        }
        r.add("drishti.sources.connectors.test-lake.settings.root", () -> root.resolve("lake").toString());
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

    String ann() {
        return as("ann", "bi-analyst");
    }

    String rae() {
        return as("rae", "raw-author");
    }

    String wes() {
        return as("wes", "report-author");
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

    JsonNode query(String dataset, String who, String body) throws Exception {
        return ok(call("POST", "/api/v1/bi/datasets/file:dataset:" + dataset + "/query", who, body));
    }

    static Map<String, Double> byFirst(JsonNode answer, int col) {
        Map<String, Double> out = new TreeMap<>();
        for (JsonNode row : answer.path("rows")) {
            out.put(row.get(0).asText(), row.get(col).isNull() ? null : row.get(col).asDouble());
        }
        return out;
    }

    @BeforeAll
    void setUp() throws Exception {
        role("bi-risk", List.of("bi"), true);
        role("desk-head", List.of("bi"), true);
        role("bi-analyst", List.of("bi"), false);
        role("report-author", List.of("bi", "bi-author"), false);
        role("raw-author", List.of("bi", "bi-author"), true);
        for (String u : List.of("rita:bi-risk:", "hana:desk-head:DESK-A", "ann:bi-analyst:", "wes:report-author:", "rae:raw-author:")) {
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

    /** The truth, worked out from the same numbers the lake was written from. */
    static double mtm(String cp, String desk) {
        double sum = 0;
        for (int i = 0; i < 24; i++) {
            String d = i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C";
            if ((cp == null || cp.equals("CP-" + (i % 4))) && (desk == null || desk.equals(d))) {
                sum += ((i % 5) - 2) * 100.0;
            }
        }
        return sum;
    }

    static double gross(String cp, String desk) {
        double sum = 0;
        for (int i = 0; i < 24; i++) {
            String d = i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C";
            if ((cp == null || cp.equals("CP-" + (i % 4))) && (desk == null || desk.equals(d))) {
                sum += 1000.0 * (i + 1);
            }
        }
        return sum;
    }

    // ---- the tests ---------------------------------------------------------------------------------------------------

    @Test @Order(1)
    void aComposedDatasetHasOneJoinedTableWithTheColumnsAndMeasuresOfItsSources() throws Exception {
        JsonNode m = ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-ab", rita(), null));
        assertThat(m.path("valid").asBoolean()).as(m.path("problems").toString()).isTrue();
        assertThat(m.path("tables").size()).isEqualTo(1);
        assertThat(m.path("tables").get(0).path("name").asText()).isEqualTo("joined");
        List<String> cols = new ArrayList<>();
        m.path("tables").get(0).path("columns").forEach(c -> cols.add(c.path("name").asText()));
        assertThat(cols).contains("cp", "MTM", "Gross");
        List<String> measures = new ArrayList<>();
        m.path("measures").forEach(x -> measures.add(x.path("name").asText()));
        assertThat(measures).containsExactly("MTM", "Gross", "MTM per gross");               // each source measure, then the dataset's own
        assertThat(m.path("composition").path("kind").asText()).isEqualTo("composed");
        assertThat(m.path("composition").path("sources").size()).isEqualTo(2);
        assertThat(m.path("composition").path("joins").get(0).path("on").toString()).contains("cp");
    }

    @Test @Order(2)
    void theComposedMeasureIsTheRatioOfTheJoinedSumsAndTheSqlIsAJoin() throws Exception {
        JsonNode a = query("lake-ab", rita(), "{\"fields\":[\"joined.cp\"],\"measures\":[\"MTM\",\"Gross\",\"MTM per gross\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        Map<String, Double> perCp = byFirst(a, 3);
        assertThat(perCp.keySet()).containsExactly("CP-0", "CP-1", "CP-2", "CP-3");
        for (String cp : perCp.keySet()) {
            assertThat(byFirst(a, 1).get(cp)).isEqualTo(mtm(cp, null));
            assertThat(byFirst(a, 2).get(cp)).isEqualTo(gross(cp, null));
            assertThat(perCp.get(cp)).isCloseTo(mtm(cp, null) / gross(cp, null), org.assertj.core.data.Offset.offset(1e-12));
        }
        assertThat(a.path("meta").path("layer").asText()).isEqualTo("composed");
        // an author sees the SQL: the sources' answers joined, the dataset's measure over the joined sums, values bound
        JsonNode ex = ok(call("POST", "/api/v1/bi/datasets/file:dataset:lake-ab/query?explain=true", rae(),
                "{\"fields\":[\"joined.cp\"],\"measures\":[\"MTM per gross\"],\"dates\":{\"from\":\"2026-10-08\"}}"));
        String sql = ex.path("meta").path("sql").asText();
        assertThat(sql).contains("INNER JOIN").contains("sum(\"joined\".\"MTM\")").contains("sum(\"joined\".\"Gross\")").contains("GROUP BY");
        // totals without a field: the join is of the grouped answers, so the total is the sum over counterparties
        JsonNode t = query("lake-ab", rita(), "{\"measures\":[\"MTM\",\"Gross\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(t.path("rows").get(0).get(1).asDouble()).isEqualTo(gross(null, null));
    }

    @Test @Order(3)
    void eachSourceAppliesItsOwnRowRulesBeforeTheJoinSoTwoReadersSeeDifferentRows() throws Exception {
        String body = "{\"fields\":[\"joined.desk\"],\"measures\":[\"MTM\",\"Trades\",\"Gross\"],\"dates\":{\"from\":\"2026-10-08\"}}";
        JsonNode risk = query("lake-ab-desk", rita(), body);
        assertThat(byFirst(risk, 1).keySet()).containsExactly("DESK-A", "DESK-B", "DESK-C");
        assertThat(byFirst(risk, 3).get("DESK-B")).isEqualTo(gross(null, "DESK-B"));
        // the desk-head's source A holds DESK-A only (the rule runs before the join); B is whole but a left join keeps A's rows
        JsonNode head = query("lake-ab-desk", hana(), body);
        assertThat(byFirst(head, 1).keySet()).containsExactly("DESK-A");
        assertThat(byFirst(head, 1).get("DESK-A")).isEqualTo(mtm(null, "DESK-A"));
        assertThat(byFirst(head, 3).get("DESK-A")).isEqualTo(gross(null, "DESK-A"));          // B's number for the desk is whole: B's own rule lets the desk-head see all
        assertThat(head.path("rowCount").asInt()).isEqualTo(1);
    }

    @Test @Order(4)
    void aJoinOnAColumnMaskedForTheReaderIsRefusedAndNeverMatchesEverythingToEverything() throws Exception {
        // counterpartyId is on the server's redact list: ann has no raw, so each source answers one constant for it
        refused(call("POST", "/api/v1/bi/datasets/file:dataset:lake-ab/query", ann(), "{\"fields\":[\"joined.cp\"],\"measures\":[\"MTM\"]}"), 403, "DRS-9021");
        // a reader with the raw power is fine (checked in the second test); the masked non-key measure of a source is null, not an error
        JsonNode ok = query("lake-ab-desk", ann(), "{\"fields\":[\"joined.desk\"],\"measures\":[\"MTM\",\"Gross\",\"MTM per gross\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(ok.path("rowCount").asInt()).isEqualTo(3);
        // lake-a masks mtm for ann: the composed dataset shows MTM, and the measure of its own made from it, as masked (null), and Gross as it is
        assertThat(byFirst(ok, 1).values()).containsOnlyNulls();
        assertThat(byFirst(ok, 3).values()).containsOnlyNulls();
        assertThat(byFirst(ok, 2).get("DESK-A")).isEqualTo(gross(null, "DESK-A"));
        assertThat(ok.path("meta").path("masked").toString()).contains("MTM").contains("MTM per gross").doesNotContain("Gross\"");
    }

    @Test @Order(5)
    void aJoinThatRepeatsRowsIsRefusedUnlessAllowedAndACycleKeyTypesAndMissingSourcesAreRefused() throws Exception {
        JsonNode fan = ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-fanout", rita(), null));
        assertThat(fan.path("problems").toString()).contains("DRS-9015").contains("many-to-many");
        assertThat(fan.path("valid").asBoolean()).isFalse();                                    // an error: Check and publish refuse it
        assertThat(fan.path("problems").toString()).contains("aggregate the many side").contains("allow-repeat: true");
        JsonNode accepted = ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-fanout-ok", rita(), null));
        assertThat(accepted.path("valid").asBoolean()).isTrue();                                // opted in: a warning, and the join is marked
        assertThat(accepted.path("problems").toString()).contains("DRS-9015").contains("\"level\":\"warning\"");
        assertThat(accepted.path("composition").path("joins").get(0).path("allowRepeat").asBoolean()).isTrue();
        JsonNode cycle = ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-cycle-1", rita(), null));
        assertThat(cycle.path("valid").asBoolean()).isFalse();
        assertThat(cycle.path("problems").toString()).contains("DRS-9016");
        JsonNode types = ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-types", rita(), null));
        assertThat(types.path("valid").asBoolean()).isFalse();
        assertThat(types.path("problems").toString()).contains("DRS-9014").contains("date").contains("string");
        JsonNode missing = ok(call("POST", "/api/v1/bi/datasets/validate", wes(), Map.of("yaml", "dataset: x\nfrom:\n  a: { dataset: nothing-here, by: [\"t.c\"], measures: [M] }\n")));
        assertThat(missing.path("valid").asBoolean()).isFalse();
        assertThat(missing.path("problems").toString()).contains("DRS-9014").contains("nothing-here");
        JsonNode noMeasure = ok(call("POST", "/api/v1/bi/datasets/validate", wes(), Map.of("yaml",
                "dataset: x\nfrom:\n  a: { dataset: lake-a, by: [\"trades.desk\"], measures: [Nope] }\n  b: { dataset: lake-b, by: [\"trades.desk\"], measures: [Gross] }\njoin:\n  - { left: a, right: b, on: [desk] }\n")));
        assertThat(noMeasure.path("problems").toString()).contains("DRS-9014").contains("no measure 'Nope'");
        // querying an invalid composed dataset is refused, not answered
        refused(call("POST", "/api/v1/bi/datasets/file:dataset:lake-cycle-1/query", rita(), "{\"measures\":[\"M\"]}"), 422, "DRS-9010");
    }

    @Test @Order(6)
    void aChangeToASourceThatBreaksAComposedDatasetIsCaughtByImpactAnalysis() throws Exception {
        BiStore.Item a = bi.datasetNamed("lake-a").orElseThrow();
        String before = bi.sourceBody(a);
        String after = before.replace("  MTM: { expr: \"sum(trades.mtm)\" }\n", "  Mark: { expr: \"sum(trades.mtm)\" }\n");
        assertThat(after).isNotEqualTo(before);
        ImpactAnalyzer.Result res = impact.impact(a, before, after);
        assertThat(res.change().breaking()).isTrue();
        List<String> broken = res.published().stream().map(h -> h.report().id()).toList();
        assertThat(broken).contains("file:dataset:lake-ab", "file:dataset:lake-ab-desk");
        assertThat(res.published().stream().filter(h -> h.report().id().equals("file:dataset:lake-ab")).findFirst().orElseThrow().uses().toString()).contains("measure MTM");
        // a change that keeps every name used breaks nothing
        assertThat(impact.impact(a, before, before + "\n# a comment\n").published()).isEmpty();
        // lineage: the sources are what the composed dataset is built from, and it is below them
        assertThat(impact.dependentsOn(a).stream().map(BiStore.Item::id).toList()).contains("file:dataset:lake-ab");
    }

    @Test @Order(7)
    void aResultIsCachedPerReaderAndTheCompositionIsOnlyAsFreshAsItsSources() throws Exception {
        String body = "{\"fields\":[\"joined.desk\"],\"measures\":[\"Gross\"],\"dates\":{\"from\":\"2026-10-08\"}}";
        JsonNode first = query("lake-ab-desk", rita(), body);
        JsonNode second = query("lake-ab-desk", rita(), body);
        assertThat(second.path("meta").path("cached").asBoolean()).isTrue();
        assertThat(second.path("rows")).isEqualTo(first.path("rows"));
        assertThat(query("lake-ab-desk", hana(), body).path("meta").path("cached").asBoolean()).isFalse();
    }

    // ---- derived: a Python Transform ---------------------------------------------------------------------------------

    long transformRuns() throws Exception {
        return ok(call("GET", "/api/v1/bi/engine", admin(), null)).path("transforms").path("runs").asLong();
    }

    @Test @Order(8)
    void aDerivedDatasetIsMadeByAPythonTransformFromTheReadersOwnAnswersAndKeptPerReader() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(pythonInstalled(), "the console's Python environment (drishti-console/.venv) is needed");
        long before = transformRuns();
        JsonNode risk = query("lake-derived", rita(), "{\"fields\":[\"result.desk\",\"result.share\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(byFirst(risk, 1).keySet()).containsExactly("DESK-A", "DESK-B", "DESK-C");
        for (String desk : byFirst(risk, 1).keySet()) {
            assertThat(byFirst(risk, 1).get(desk)).isCloseTo(mtm(null, desk) / gross(null, desk), org.assertj.core.data.Offset.offset(1e-12));
        }
        assertThat(risk.path("meta").path("layer").asText()).isEqualTo("derived");
        assertThat(transformRuns()).isEqualTo(before + 1);
        // another query by the same reader, on the same inputs and dates: the frame is kept, the Transform does not run again
        JsonNode share = query("lake-derived", rita(), "{\"measures\":[\"Share\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(share.path("rows").get(0).get(0).asDouble()).isCloseTo(mtm(null, null) / gross(null, null), org.assertj.core.data.Offset.offset(1e-12));
        assertThat(transformRuns()).isEqualTo(before + 1);
        // another reader: the Transform is given that reader's rows (the desk-head's rule cuts source A to DESK-A), so it runs for them and the frame differs
        JsonNode head = query("lake-derived", hana(), "{\"fields\":[\"result.desk\",\"result.share\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(byFirst(head, 1).keySet()).containsExactly("DESK-A");
        assertThat(transformRuns()).isEqualTo(before + 2);
        // another business date is another version of the inputs
        query("lake-derived", rita(), "{\"fields\":[\"result.desk\"],\"dates\":{\"from\":\"2026-10-07\"}}");
        assertThat(transformRuns()).isEqualTo(before + 3);
    }

    @Test @Order(9)
    void aDerivedDatasetWithoutDeclaredColumnsLearnsThemFromTheFirstRun() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(pythonInstalled(), "the console's Python environment (drishti-console/.venv) is needed");
        JsonNode m = ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-derived-learned", rae(), null));
        assertThat(m.path("problems").toString()).contains("DRS-9017").contains("not known yet");
        refused(call("POST", "/api/v1/bi/datasets/file:dataset:lake-derived-learned/transform/run", rita(), "{}"), 403, "DRS-9002");           // authors only
        JsonNode run = ok(call("POST", "/api/v1/bi/datasets/file:dataset:lake-derived-learned/transform/run", rae(), "{}"));
        assertThat(run.path("columns").toString()).contains("\"desk\"").contains("\"share\"").contains("decimal");
        JsonNode after = ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-derived-learned", rae(), null));
        assertThat(after.path("tables").get(0).path("columns").toString()).contains("share");
        assertThat(after.path("problems").toString()).doesNotContain("not known yet");
        JsonNode rows = query("lake-derived-learned", rae(), "{\"fields\":[\"result.desk\",\"result.gross\"]}");
        assertThat(rows.path("rowCount").asInt()).isEqualTo(3);
        refused(call("POST", "/api/v1/bi/datasets/file:dataset:lake-a/transform/run", rae(), "{}"), 422, "DRS-9020");                          // an ordinary dataset has no Transform
    }

    @Test @Order(10)
    void aTransformThatIsNotOneOrDisagreesWithItsInputsIsRefused() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(pythonInstalled(), "the console's Python environment (drishti.console/.venv) is needed");
        writeIn("datasets", "lake-derived-wrong.dataset.yaml", """
                rachana: 1
                dataset: lake-derived-wrong
                derived:
                  transform: merge-ab
                  inputs:
                    a: { dataset: lake-b, by: ["trades.desk"], measures: [Gross] }
                    b: { dataset: lake-a, by: ["trades.desk"], measures: [MTM] }
                columns: { desk: string }
                """);
        writeIn("datasets", "lake-derived-notransform.dataset.yaml", """
                rachana: 1
                dataset: lake-derived-notransform
                derived:
                  transform: lake-a
                  inputs:
                    a: { dataset: lake-b, by: ["trades.desk"], measures: [Gross] }
                columns: { desk: string }
                """);
        Thread.sleep(2500);                                                                                                  // the folder is re-read every second
        refused(call("POST", "/api/v1/bi/datasets/file:dataset:lake-derived-wrong/query", rita(), "{\"fields\":[\"result.desk\"]}"), 422, "DRS-9075");
        refused(call("POST", "/api/v1/bi/datasets/file:dataset:lake-derived-notransform/query", rita(), "{\"fields\":[\"result.desk\"]}"), 422, "DRS-9070");
    }

    // ---- reports over several datasets -------------------------------------------------------------------------------

    JsonNode visual(String report, String who, String visual, String state) throws Exception {
        return ok(call("POST", "/api/v1/bi/reports/file:report:" + report + "/query", who, "{\"page\":\"p\",\"visual\":\"" + visual + "\",\"state\":" + state + "}"));
    }

    @Test @Order(11)
    void aVisualMayNameItsOwnDatasetAndTheReportShowsEachDatasetsModel() throws Exception {
        JsonNode r = ok(call("GET", "/api/v1/bi/reports/file:report:lake-multi", rita(), null));
        assertThat(r.path("valid").asBoolean()).as(r.path("problems").toString()).isTrue();
        assertThat(r.path("report").path("links").get(0).path("fields").toString()).contains("trades.desk").contains("joined.desk");
        assertThat(r.path("report").path("pages").get(0).path("visuals").get(1).path("dataset").asText()).isEqualTo("lake-b");
        assertThat(r.path("datasets").has("lake-b")).isTrue();                                 // the designer's Fields pane needs the other dataset's model
        assertThat(r.path("datasets").path("lake-b").path("tables").get(0).path("name").asText()).isEqualTo("trades");
        JsonNode own = visual("lake-multi", rita(), "b-bar", "{}");
        assertThat(byFirst(own, 1).keySet()).containsExactly("DESK-A", "DESK-B", "DESK-C");
        assertThat(own.path("meta").path("layer").asText()).isNotEqualTo("composed");          // lake-b is an ordinary dataset
        assertThat(visual("lake-multi", rita(), "by-desk", "{}").path("meta").path("layer").asText()).isEqualTo("composed");
    }

    @Test @Order(12)
    void aSlicerOfOneDatasetFiltersTheVisualsOfAnotherOnlyThroughALink() throws Exception {
        String state = "{\"slicers\":{\"pick\":[\"DESK-B\"]}}";
        assertThat(byFirst(visual("lake-multi", rita(), "by-desk", state), 1).keySet()).containsExactly("DESK-B");            // the link maps trades.desk to joined.desk
        assertThat(byFirst(visual("lake-multi", rita(), "b-bar", state), 1).keySet()).containsExactly("DESK-B");               // its own dataset needs no link
        assertThat(byFirst(visual("lake-multi-nolink", rita(), "by-desk", state), 1).keySet()).containsExactly("DESK-A", "DESK-B", "DESK-C");   // no link: the choice does not reach it
        // a click on a mark of the other dataset's visual is a selection, mapped the same way
        String click = "{\"selections\":[{\"visual\":\"b-bar\",\"field\":\"trades.desk\",\"values\":[\"DESK-C\"]}]}";
        assertThat(byFirst(visual("lake-multi", rita(), "by-desk", click), 1).keySet()).containsExactly("DESK-C");
    }

    @Test @Order(13)
    void everyQueryOfAMultiDatasetReportIsRunAsTheReader() throws Exception {
        // the desk-head's source A holds DESK-A only; lake-b lets them see all desks, so the two visuals differ for them
        assertThat(byFirst(visual("lake-multi", hana(), "by-desk", "{}"), 1).keySet()).containsExactly("DESK-A");
        assertThat(byFirst(visual("lake-multi", hana(), "b-bar", "{}"), 1).keySet()).containsExactly("DESK-A", "DESK-B", "DESK-C");
        // a choice that the reader's rows do not contain leaves nothing, it does not widen them
        assertThat(visual("lake-multi", hana(), "by-desk", "{\"slicers\":{\"pick\":[\"DESK-B\"]}}").path("rowCount").asInt()).isZero();
    }

    @Test @Order(14)
    void aReportThatNamesAMissingDatasetOrALinkToNothingIsRefusedWithItsCodes() throws Exception {
        JsonNode bad = ok(call("GET", "/api/v1/bi/reports/file:report:lake-multi-bad", rita(), null));
        assertThat(bad.path("valid").asBoolean()).isFalse();
        assertThat(bad.path("problems").toString()).contains("DRS-9034").contains("no-such-dataset").contains("DRS-9031").contains("nothing.here");
        refused(call("POST", "/api/v1/bi/reports/file:report:lake-multi-bad/query", rita(), "{\"page\":\"p\",\"visual\":\"by-desk\",\"state\":{}}"), 422, "DRS-9034");
    }

    @Test @Order(15)
    void lineageAndImpactSeeAReportReadingSeveralDatasets() throws Exception {
        BiStore.Item b = bi.datasetNamed("lake-b").orElseThrow();
        String before = bi.sourceBody(b);
        ImpactAnalyzer.Result res = impact.impact(b, before, before.replace("  Gross: { expr: \"sum(trades.notional)\" }\n", ""));
        List<String> broken = res.published().stream().map(h -> h.report().id()).toList();
        assertThat(broken).contains("file:report:lake-multi", "file:dataset:lake-ab", "file:dataset:lake-ab-desk");       // reports by a visual's own dataset, and composed datasets
        String uses = res.published().stream().filter(h -> h.report().id().equals("file:report:lake-multi")).findFirst().orElseThrow().uses().toString();
        assertThat(uses).contains("measure Gross").doesNotContain("measure MTM");                                          // only what is read from lake-b
        JsonNode lineage = ok(call("GET", "/api/v1/bi/items/file:dataset:lake-ab/lineage", admin(), null));
        assertThat(lineage.path("upstream").toString()).contains("lake-a").contains("lake-b");
    }
}
