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

    JsonNode validate(String who, String expr, String scope) throws Exception {
        return ok(call("POST", "/api/v1/bi/expr/validate", reader(who), Map.of("expr", expr, "scope", scope, "model", "lang-model@1")));
    }

    static List<String> codes(JsonNode v) {
        List<String> out = new ArrayList<>();
        v.path("problems").forEach(p -> out.add(p.path("code").asText()));
        return out;
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
        assertThat(old.path("valid").asBoolean()).isTrue();
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
