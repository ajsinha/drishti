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
        "drishti.sources.connectors.test-lake.settings.layout.trade.columns=desk,book,currency,notional,mtm,counterparty.id,tradeDate"})
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
        try {
            Path datasets = Path.of("target/bi-compose-folder/datasets");
            Files.createDirectories(datasets);
            Files.writeString(datasets.resolve(file + ".dataset.yaml"), text);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
                  MTM per gross: "[MTM] / [Gross]"
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
                  MTM per gross: "[MTM] / [Gross]"
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
        JsonNode ok = query("lake-ab-desk", ann(), "{\"fields\":[\"joined.desk\"],\"measures\":[\"MTM\",\"Gross\"],\"dates\":{\"from\":\"2026-10-08\"}}");
        assertThat(ok.path("rowCount").asInt()).isEqualTo(3);
    }

    @Test @Order(5)
    void aJoinThatRepeatsRowsIsWarnedAboutAndACycleKeyTypesAndMissingSourcesAreRefused() throws Exception {
        JsonNode fan = ok(call("GET", "/api/v1/bi/datasets/file:dataset:lake-fanout", rita(), null));
        assertThat(fan.path("problems").toString()).contains("DRS-9015").contains("many-to-many");
        assertThat(fan.path("valid").asBoolean()).isTrue();                                     // a warning: the dataset still works
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
}
