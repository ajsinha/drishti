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
package com.ash.drishti.server.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The editors' API (EDITORS.md sections 1 to 4) on a real server over the demo source: checks as typed with problems at their lines, the
 * visual side's document written back as the layer's YAML, the Text toggle's statement, a preview read as the person (totals by the pivot
 * engine), a model's measures with the engine's "adds up?", the Access matrix, saving and proposing with four eyes, the per-person view, and
 * the Python table's text work. Users: ann writes (bi-author), bob writes and approves, cat reads (bi), dan has no BI.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/editors-${random.uuid}/identity.db",
        "drishti.bi.dir=${drishti.test.dir}/editors-bi-folder-${random.uuid}", "drishti.bi.query.store-dir=${drishti.test.dir}/editors-store/${random.uuid}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditorApiTest {

    static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("drishti-editors-test");
            Files.createDirectories(ROOT.resolve("trading"));
            Files.writeString(ROOT.resolve("trading/trade_summary.table.yaml"), """
                    # shipped for the test
                    rachana: 3
                    table: trading.trade_summary
                    from: { kind: trade }
                    columns:
                      PRODUCT: { path: $.productType, type: string }
                      MTM:     { path: $.mtm, type: decimal }
                    """);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void clean() throws java.io.IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(ROOT)) {
            for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }

    @DynamicPropertySource
    static void tables(DynamicPropertyRegistry r) {
        r.add("drishti.tables.root", ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();
    String ws;

    String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    MvcResult call(String who, String role, MockHttpServletRequestBuilder r, Object body) throws Exception {
        r = r.header("Authorization", as(who, role));
        if (body != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(r).andReturn();
    }

    JsonNode ok(String who, String role, MockHttpServletRequestBuilder r, Object body) throws Exception {
        MvcResult res = call(who, role, r, body);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isBetween(200, 299);
        return json.readTree(res.getResponse().getContentAsString());
    }

    JsonNode ann(MockHttpServletRequestBuilder r, Object body) throws Exception {
        return ok("ann", "writer", r, body);
    }

    @BeforeAll
    void setUp() throws Exception {
        role("writer", List.of("bi", "bi-author"), false);
        role("signoff", List.of("bi", "bi-author"), true);
        role("reader", List.of("bi"), false);
        role("nobi", List.of(), false);
        for (String u : List.of("ann:writer", "bob:signoff", "cat:reader", "dan:nobi")) {
            String[] n = u.split(":");
            ok("drishti-dev-admin", "admin", post("/api/v1/admin/users"), Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test",
                    "roles", List.of(n[1]), "password", "long-enough-pass-1"));
        }
        ws = ann(post("/api/v1/bi/workspaces"), Map.of("name", "Editors Desk")).path("id").asText();
        ann(put("/api/v1/bi/workspaces/" + ws + "/members/bob"), Map.of("role", "member"));
    }

    void role(String name, List<String> bi, boolean approve) throws Exception {
        ok("drishti-dev-admin", "admin", put("/api/v1/admin/role-definitions/" + name), Map.of("kinds", List.of("*"), "approve", approve, "bi", bi));
    }

    static final String TABLE = """
            rachana: 3
            table: mine.trade_view
            title: Trades as I see them
            from: { kind: trade }
            columns:
              ID:      { path: $.tradeId, type: string, key: true }
              PRODUCT: { path: $.productType, type: string }
              MTM:     { path: $.mtm, type: decimal }
              MTM_K:   { expr: "$.mtm / 1000", type: decimal }
            """;

    @Test
    void aCheckGivesTheDocumentEachKeysLineTheStatementAndProblemsAtTheirLines() throws Exception {
        JsonNode c = ann(post("/api/v1/tables/editor/check"), Map.of("kind", "table", "yaml", TABLE));
        assertThat(c.path("ok").asBoolean()).as(c.toString()).isTrue();
        assertThat(c.path("doc").path("columns").path("MTM_K").path("expr").asText()).isEqualTo("$.mtm / 1000");
        assertThat(c.path("lines").path("columns.MTM_K.expr").asInt()).isEqualTo(9);
        assertThat(c.path("statement").asText()).startsWith("table mine.trade_view from trade").contains("$.mtm / 1000 as MTM_K: decimal");
        assertThat(c.path("nameFree").asBoolean()).isTrue();

        JsonNode bad = ann(post("/api/v1/tables/editor/check"), Map.of("kind", "table", "yaml", TABLE.replace("type: decimal }\n  MTM_K", "type: money }\n  MTM_K")));
        JsonNode p = first(bad.path("problems"), "DRS-9605");
        assertThat(p.path("line").asInt()).isEqualTo(8);

        JsonNode broken = ann(post("/api/v1/tables/editor/check"), Map.of("kind", "table", "yaml", "rachana: 3\ntable: [x\n"));
        assertThat(broken.path("ok").asBoolean()).isFalse();
        assertThat(broken.path("problems").get(0).path("line").asInt()).isPositive();

        JsonNode taken = ann(post("/api/v1/tables/editor/check"), Map.of("kind", "table", "yaml", TABLE.replace("mine.trade_view", "trading.trade_summary")));
        assertThat(taken.path("nameFree").asBoolean()).isFalse();
        JsonNode stored = ann(post("/api/v1/tables/editor/check"), Map.of("kind", "table", "yaml", TABLE.replace("mine.trade_view", "trade")));
        assertThat(stored.path("nameFree").asBoolean()).isFalse();
    }

    @Test
    void aVisualEditWritesTheLayersYamlKeepingTheHeader() throws Exception {
        JsonNode c = ann(post("/api/v1/tables/editor/check"), Map.of("kind", "table", "yaml", "# my notes\n" + TABLE));
        Map<String, Object> doc = json.convertValue(c.path("doc"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> cols = (Map<String, Object>) doc.get("columns");
        cols.put("DESK", Map.of("path", "$.desk", "type", "string"));
        JsonNode after = ann(post("/api/v1/tables/editor/check"), Map.of("kind", "table", "yaml", "# my notes\n" + TABLE, "doc", doc));
        assertThat(after.path("yaml").asText()).startsWith("# my notes\nrachana: 3\ntable: mine.trade_view").contains("DESK:");
        assertThat(after.path("lines").path("columns.DESK").asInt()).isPositive();
    }

    @Test
    void theTextStatementReadsBackOntoTheTable() throws Exception {
        String statement = "table mine.trade_view from trade\nselect $.tradeId as ID: string key,\n       $.mtm * 2 as MTM2: decimal\nwhere $.mtm != null\n";
        JsonNode s = ann(post("/api/v1/tables/editor/statement"), Map.of("statement", statement, "yaml", TABLE));
        assertThat(s.path("ok").asBoolean()).as(s.toString()).isTrue();
        assertThat(s.path("doc").path("filter").asText()).isEqualTo("$.mtm != null");
        assertThat(s.path("doc").path("columns").path("MTM2").path("expr").asText()).isEqualTo("$.mtm * 2");
        assertThat(s.path("doc").path("columns").has("PRODUCT")).isFalse();
        JsonNode bad = ann(post("/api/v1/tables/editor/statement"), Map.of("statement", "table mine.x from trade\nselect $.mtm + 1\n", "yaml", TABLE));
        assertThat(bad.path("ok").asBoolean()).isFalse();
        assertThat(bad.path("problems").get(0).path("message").asText()).contains("name the computed column");
    }

    @Test
    void thePreviewReadsAsThePersonAndTheTotalsAreThePivotEnginesSums() throws Exception {
        JsonNode pv = ann(post("/api/v1/tables/editor/preview"), Map.of("kind", "table", "yaml", TABLE));
        assertThat(pv.path("count").asInt()).isPositive();
        assertThat(pv.path("rows").size()).isLessThanOrEqualTo(20);
        assertThat(pv.path("columns").toString()).contains("MTM_K");
        assertThat(pv.path("totals").has("MTM")).isTrue();
        assertThat(pv.path("totals").path("MTM_K").asDouble()).isCloseTo(pv.path("totals").path("MTM").asDouble() / 1000, org.assertj.core.data.Offset.offset(1.0));
        assertThat(pv.path("checks").toString()).contains("unique across every table").containsAnyOf("dates follow the source", "an undated source");
        assertThat(pv.path("cost").path("crossConnector").asBoolean()).isFalse();
        assertThat(pv.path("cost").path("sides").get(0).path("rows").asLong()).isEqualTo(pv.path("count").asLong());
    }

    @Test
    void aModelsMeasuresCarryTheEnginesAddsUpAndTheirGrandTotals() throws Exception {
        String model = """
                rachana: 3
                model: mine-risk
                tables:
                  t: { table: trading.trade_summary, role: fact }
                measures:
                  MTM:      { expr: "sum(t.MTM)" }
                  Avg MTM:  { expr: "avg(t.MTM)" }
                  Per trade: { expr: "sum(t.MTM) / count(t)" }
                """;
        JsonNode c = ann(post("/api/v1/tables/editor/check"), Map.of("kind", "model", "yaml", model));
        assertThat(c.path("ok").asBoolean()).as(c.toString()).isTrue();
        JsonNode pv = ann(post("/api/v1/tables/editor/preview"), Map.of("kind", "model", "yaml", model));
        JsonNode mtm = byName(pv.path("measures"), "MTM");
        assertThat(mtm.path("word").asText()).isEqualTo("yes");
        assertThat(mtm.path("value").isNumber()).as(pv.toString()).isTrue();
        assertThat(mtm.path("explain").asText()).contains("sum");
        assertThat(byName(pv.path("measures"), "Avg MTM").path("addsUp").asBoolean()).isFalse();
        assertThat(byName(pv.path("measures"), "Per trade").path("word").asText()).isEqualTo("ratio");
        JsonNode access = ann(post("/api/v1/tables/editor/access"), Map.of("yaml", model));
        JsonNode writer = byField(access.path("roles"), "role", "writer");
        assertThat(writer.path("edit").asBoolean()).isTrue();
        assertThat(byField(access.path("roles"), "role", "reader").path("edit").asBoolean()).isFalse();
    }

    @Test
    void saveProposeAndFourEyes() throws Exception {
        String yaml = TABLE.replace("mine.trade_view", "mine.reviewed_view");
        JsonNode saved = ann(post("/api/v1/tables/editor/save"), Map.of("kind", "table", "yaml", yaml, "workspace", ws));
        String item = saved.path("item").asText();
        assertThat(saved.path("status").asText()).isEqualTo("draft");
        JsonNode objects = ann(get("/api/v1/tables/editor/objects"), null);
        assertThat(objects.path("tables").toString()).contains("mine.reviewed_view").contains("trading.trade_summary");
        JsonNode opened = ann(get("/api/v1/tables/editor/object").param("kind", "table").param("item", item), null);
        assertThat(opened.path("yaml").asText()).isEqualTo(yaml);
        assertThat(ann(post("/api/v1/tables/editor/propose"), Map.of("item", item, "note", "please")).path("status").asText()).isEqualTo("proposed");
        assertThat(call("ann", "writer", post("/api/v1/approvals/bi/" + item + "/approve"), Map.of()).getResponse().getStatus()).isEqualTo(403);
        JsonNode approved = ok("bob", "signoff", post("/api/v1/approvals/bi/" + item + "/approve"), Map.of("comment", "fine"));
        assertThat(approved.path("status").asText()).isEqualTo("approved");
        JsonNode history = ann(get("/api/v1/tables/editor/history").param("item", item), null);
        assertThat(history.path("versions").size()).isPositive();
        // a reader may look, not save
        assertThat(call("cat", "reader", post("/api/v1/tables/editor/save"), Map.of("kind", "table", "yaml", yaml)).getResponse().getStatus()).isEqualTo(403);
        assertThat(ok("cat", "reader", get("/api/v1/tables/editor/objects"), null).path("canEdit").asBoolean()).isFalse();
        // no BI, no editors
        assertThat(call("dan", "nobi", get("/api/v1/tables/editor/objects"), null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anInvalidTableCannotBeProposedPastTheLayersChecks() throws Exception {
        String yaml = TABLE.replace("mine.trade_view", "mine.broken_view").replace("$.mtm / 1000", "$.mtm / 'x'");
        String item = ann(post("/api/v1/tables/editor/save"), Map.of("kind", "table", "yaml", yaml, "workspace", ws)).path("item").asText();
        ann(post("/api/v1/tables/editor/propose"), Map.of("item", item));
        MvcResult r = call("bob", "signoff", post("/api/v1/approvals/bi/" + item + "/approve"), Map.of());
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(r.getResponse().getContentAsString()).contains("cannot be published");
    }

    @Test
    void theViewIsRememberedPerPerson() throws Exception {
        ann(put("/api/v1/tables/editor/prefs"), Map.of("mode", "yaml", "yamlOpen", false));
        JsonNode mine = ann(get("/api/v1/tables/editor/prefs"), null);
        assertThat(mine.path("mode").asText()).isEqualTo("yaml");
        assertThat(mine.path("yamlOpen").asBoolean()).isFalse();
        assertThat(ok("cat", "reader", get("/api/v1/tables/editor/prefs"), null).path("mode").asText()).isEqualTo("both");
        assertThat(call("ann", "writer", put("/api/v1/tables/editor/prefs"), Map.of("mode", "diagram")).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void newTableStepsSuggestColumnsFromASampleOfTheKind() throws Exception {
        JsonNode s = ann(post("/api/v1/tables/editor/suggest"), Map.of("kind", "trade", "name", "mine.trades"));
        assertThat(s.path("doc").path("table").asText()).isEqualTo("mine.trades");
        assertThat(s.path("doc").path("columns").size()).isPositive();
        assertThat(s.path("documents").asInt()).isPositive();
        JsonNode sources = ann(get("/api/v1/tables/editor/sources"), null);
        assertThat(sources.path("kinds").toString()).contains("\"trade\"");
        assertThat(sources.path("functions").toString()).contains("\"sum\"");
    }

    @Test
    void pythonTablesTemplatesNotebookAndContract() throws Exception {
        JsonNode t = ann(get("/api/v1/tables/editor/python/templates"), null);
        assertThat(t.path("templates").size()).isEqualTo(4);
        JsonNode nb = ann(post("/api/v1/tables/editor/python/notebook"), Map.of("cells", List.of("import pandas as pd\nlegs = drishti.table(\"trading.trade_legs\")",
                "legs[\"X\"] = legs[\"NOTIONAL\"] * 2\nlegs")));
        assertThat(nb.path("source").asText()).startsWith("import pandas as pd\n\ndef build(inputs, params):").contains("inputs[\"trade_legs\"]").contains("    return legs");
        assertThat(nb.path("inputs").path("trade_legs").asText()).isEqualTo("trading.trade_legs");
        JsonNode c = ann(post("/api/v1/tables/editor/python/contract"), Map.of("source", "out[\"HEADROOM\"] = 1\nreturn out[[\"DESK\", \"HEADROOM\"]]",
                "columns", Map.of("DESK", "string")));
        assertThat(c.path("columns").path("DESK").path("type").asText()).isEqualTo("string");
        assertThat(c.path("columns").path("HEADROOM").path("type").asText()).isEqualTo("decimal");
        String py = """
                rachana: 3
                table: mine.headroom
                from: { python: mine-headroom, params: { pool: default, runs: on-demand } }
                columns:
                  DESK:     { type: string, key: true }
                  HEADROOM: { type: decimal }
                """;
        JsonNode saved = ann(post("/api/v1/tables/editor/save"), Map.of("kind", "table", "yaml", py, "workspace", ws,
                "python", Map.of("name", "mine.headroom", "source", "def build(inputs, params):\n    return None\n", "inputs", List.of("trading.trade_summary"))));
        assertThat(saved.path("pythonItem").asText()).isNotBlank();
        JsonNode opened = ann(get("/api/v1/tables/editor/object").param("kind", "table").param("item", saved.path("item").asText()), null);
        assertThat(opened.path("python").path("source").asText()).contains("def build(inputs, params)");
        JsonNode pv = ann(post("/api/v1/tables/editor/preview"), Map.of("kind", "table", "yaml", py));
        assertThat(pv.path("python").asBoolean()).isTrue();
    }

    @Test
    void impactListsWhatUsesATable() throws Exception {
        String model = "rachana: 3\nmodel: mine-impact\ntables:\n  t: { table: trading.trade_summary, role: fact }\nmeasures:\n  N: { expr: \"count(t)\" }\n";
        ann(post("/api/v1/tables/editor/save"), Map.of("kind", "model", "yaml", model, "workspace", ws));
        JsonNode i = ann(get("/api/v1/tables/editor/impact").param("kind", "table").param("name", "trading.trade_summary"), null);
        assertThat(i.path("usedBy").toString()).contains("mine-impact");
    }

    static JsonNode first(JsonNode list, String code) {
        for (JsonNode n : list) {
            if (code.equals(n.path("code").asText())) {
                return n;
            }
        }
        throw new AssertionError("no " + code + " in " + list);
    }

    static JsonNode byName(JsonNode list, String name) {
        return byField(list, "name", name);
    }

    static JsonNode byField(JsonNode list, String field, String value) {
        for (JsonNode n : list) {
            if (value.equals(n.path(field).asText())) {
                return n;
            }
        }
        throw new AssertionError("no " + value + " in " + list);
    }
}
