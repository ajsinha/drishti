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
 * Wave C (2.4.0) data prep over the whole stack, security on (docs/guides/BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 17, docs/architecture/BI_DESIGN.md section 11.1, item P5): a Transform's input may be a pack
 * kind read straight through a connector, as the reader. The input preview the Transform editor uses ({@code POST /api/v1/bi/python/input}) answers the
 * reader's rows (a role that may not read the kind gets none of it), filtered and cut; a bad input is refused with its path; a Python dataset whose input is a kind is checked
 * and, with the console's Python environment, run as the reader; its lineage reaches the kind and the connector.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/tin-api-${random.uuid}/identity.db", "drishti.packs.overlay=${drishti.test.dir}/tin-api-overlay/added.yaml",
        "drishti.bi.dir=${drishti.test.dir}/tin-api-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=${drishti.test.dir}/tin-api-store/${random.uuid}",
        "drishti.sources.connectors-dir=${drishti.test.dir}/tin-api-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.tin-lake.plugin=file", "drishti.sources.connectors.tin-lake.kinds[0]=trade",
        "drishti.sources.connectors.tin-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.tin-lake.settings.layout.trade.columns=desk,book,currency,notional,mtm,tradeDate"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransformInputsApiTest {

    static final String DAY = "2026-10-08";
    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = com.ash.drishti.server.TestRun.temp("drishti-tin-api-lake");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String desk = i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C";
            lines.add("{\"id\":\"T-" + i + "\",\"tradeId\":\"T-" + i + "\",\"desk\":\"" + desk + "\",\"book\":\"BOOK-" + (i % 3 + 1) + "\",\"currency\":\"USD\",\"notional\":"
                    + 1000.0 * (i + 1) + ",\"mtm\":" + ((i % 5) - 2) * 100.0 + ",\"tradeDate\":\"" + DAY + "\"}");
        }
        Path f = root.resolve("lake").resolve(DAY).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
        r.add("drishti.sources.connectors.tin-lake.settings.root", () -> root.resolve("lake").toString());
    }

    static {
        try {
            Path folder = com.ash.drishti.server.TestRun.root().resolve("tin-api-folder");
            Path python = Files.createDirectories(folder.resolve("python"));
            Files.writeString(python.resolve("desk-notional-prep.python.yaml"), """
                    rachana: 1
                    python: desk-notional-prep
                    title: Desk notional prep
                    version: 1
                    interface: transform
                    inputs: [trades]
                    packages: [pandas]
                    target: server
                    source: |
                      def run(trades, params):
                          # step: Keep the rows with a notional
                          df = trades.dropna(subset=["notional"])
                          # step: Total notional per desk
                          df = df.groupby("desk", as_index=False)["notional"].sum()
                          return df
                    """);
            Path datasets = Files.createDirectories(folder.resolve("datasets"));
            Files.writeString(datasets.resolve("desk-notional-prep.dataset.yaml"), """
                    rachana: 2
                    dataset: desk-notional-prep
                    version: 1.0.0
                    source:
                      python: desk-notional-prep
                      inputs:
                        trades: { kind: trade, connector: tin-lake, fields: [desk, notional] }
                    grain: { keys: [desk] }
                    columns:
                      desk:     { type: string, role: key }
                      notional: { type: decimal, aggregate: sum }
                    """);
            Path models = Files.createDirectories(folder.resolve("models"));
            Files.writeString(models.resolve("desk-notional-prep.model.yaml"), """
                    rachana: 2
                    model: desk-notional-prep
                    version: 1.0.0
                    datasets:
                      desks: { dataset: desk-notional-prep, role: fact }
                    measures:
                      Notional: { expr: "sum(desks.notional)" }
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
        role("bi-risk", List.of("*"));
        role("curves-only", List.of("curve"));
        for (String u : List.of("rita:bi-risk:", "cora:curves-only:")) {
            String[] n = u.split(":", -1);
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "desk", n[2], "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
    }

    void role(String name, List<String> kinds) throws Exception {
        var r = mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", kinds, "approve", false, "raw", true, "bi", List.of("bi"))))).andReturn().getResponse();
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

    static Map<String, Double> byDesk(JsonNode answer) {
        int desk = -1;
        int notional = -1;
        for (int i = 0; i < answer.path("columns").size(); i++) {
            String n = answer.path("columns").get(i).path("name").asText();
            desk = "desk".equals(n) ? i : desk;
            notional = "notional".equals(n) ? i : notional;
        }
        Map<String, Double> out = new TreeMap<>();
        for (JsonNode r : answer.path("rows")) {
            out.merge(r.get(desk).asText(), r.get(notional).asDouble(), Double::sum);
        }
        return out;
    }

    @Test @Order(1)
    void aKindInputIsTheReadersRowsThroughTheNamedConnectorNamedWithoutTheAlias() throws Exception {
        // the kind as the pack routes it first: its answer must not be served again for the same kind through another connector
        call("POST", "/api/v1/bi/python/input", as("rita", "bi-risk"), Map.of("input", Map.of("kind", "trade", "fields", List.of("desk", "notional")), "asOf", DAY)).andReturn();
        Map<String, Object> in = Map.of("kind", "trade", "connector", "tin-lake", "fields", List.of("desk", "notional"));
        JsonNode rita = ok(call("POST", "/api/v1/bi/python/input", as("rita", "bi-risk"), Map.of("input", in, "asOf", DAY)));
        assertThat(rita.path("kind").asBoolean()).isTrue();
        assertThat(rita.path("model").asText()).startsWith("dataset:pack.");
        assertThat(rita.path("columns").toString()).contains("\"desk\"").contains("\"notional\"").doesNotContain("trade.desk");
        assertThat(rita.path("rowCount").asInt()).as(rita.toString()).isEqualTo(24);
        Map<String, Double> all = byDesk(rita);
        assertThat(all).containsOnlyKeys("DESK-A", "DESK-B", "DESK-C");
        assertThat(all.get("DESK-A")).isEqualTo(92_000.0);                          // 1+4+7+...+22 thousand
        JsonNode cora = ok(call("POST", "/api/v1/bi/python/input", as("cora", "curves-only"), Map.of("input", in, "asOf", DAY)));
        assertThat(cora.path("rowCount").asInt()).as("the kind's entitlement applies: a role that may not read trades gets none").isZero();
    }

    @Test @Order(2)
    void aKindInputTakesFiltersAndALimitAndArrowIsServed() throws Exception {
        Map<String, Object> in = Map.of("kind", "trade", "connector", "tin-lake", "fields", List.of("desk", "notional"),
                "where", List.of(Map.of("field", "desk", "op", "in", "values", List.of("DESK-B"))));
        JsonNode b = ok(call("POST", "/api/v1/bi/python/input", as("rita", "bi-risk"), Map.of("input", in, "asOf", DAY)));
        assertThat(byDesk(b)).containsOnlyKeys("DESK-B");
        JsonNode cut = ok(call("POST", "/api/v1/bi/python/input", as("rita", "bi-risk"), Map.of("input", in, "asOf", DAY, "limit", 3)));
        assertThat(cut.path("rowCount").asInt()).isEqualTo(3);
        var res = call("POST", "/api/v1/bi/python/input?format=arrow", as("rita", "bi-risk"), Map.of("input", in, "asOf", DAY)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-Bi-Rows")).isEqualTo("8");
        assertThat(res.getContentAsByteArray().length).isGreaterThan(100);
    }

    @Test @Order(3)
    void aBadInputIsRefusedWithItsPath() throws Exception {
        var unknown = call("POST", "/api/v1/bi/python/input", as("rita", "bi-risk"), Map.of("input", Map.of("kind", "trade", "connector", "tin-lake", "fields", List.of("nope"))))
                .andReturn().getResponse();
        assertThat(unknown.getStatus()).isEqualTo(422);
        assertThat(unknown.getContentAsString()).contains("DRS-9108").contains("nope");
        var key = call("POST", "/api/v1/bi/python/input", as("rita", "bi-risk"), Map.of("input", Map.of("kind", "trade", "colour", "red"))).andReturn().getResponse();
        assertThat(key.getStatus()).isEqualTo(422);
        assertThat(key.getContentAsString()).contains("colour");
        var kind = call("POST", "/api/v1/bi/python/input", as("rita", "bi-risk"), Map.of("input", Map.of("kind", "no-such-kind"))).andReturn().getResponse();
        assertThat(kind.getStatus()).isEqualTo(422);
        assertThat(kind.getContentAsString()).contains("DRS-9102").contains("no-such-kind");
    }

    @Test @Order(4)
    void aPythonDatasetWithAKindInputChecksAndItsLineageReachesTheConnector() throws Exception {
        JsonNode checked = ok(call("GET", "/api/v1/bi/layer-datasets/file:dataset:desk-notional-prep", as("rita", "bi-risk"), null));
        assertThat(checked.toString()).doesNotContain("\"level\":\"error\"");
        JsonNode lineage = ok(call("GET", "/api/v1/bi/lineage/desk-notional-prep@1", as("rita", "bi-risk"), null));
        assertThat(lineage.toString()).as(lineage.toString()).contains("pack-kind").contains("tin-lake").contains("desk-notional-prep");
    }

    @Test @Order(5)
    void aPythonDatasetWithAKindInputRunsAsTheReader() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("..", "drishti-console", ".venv", "bin", "python").toAbsolutePath()),
                "the console's Python environment (drishti-console/.venv, with pandas and pyarrow) is needed");   // as LanguagesApiTest
        Map<String, Object> q = Map.of("mq", 1, "model", "desk-notional-prep@1", "select", Map.of("fields", List.of("desks.desk"), "measures", List.of("Notional")),
                "time", Map.of("as-of", DAY));
        JsonNode rita = ok(call("POST", "/api/v1/bi/mq", as("rita", "bi-risk"), q));
        Map<String, Double> desks = new TreeMap<>();
        rita.path("rows").forEach(r -> desks.put(r.get(0).asText(), r.get(1).asDouble()));
        assertThat(desks).as(rita.toString()).containsEntry("DESK-A", 92_000.0).containsEntry("DESK-B", 100_000.0).containsEntry("DESK-C", 108_000.0);
        JsonNode cora = ok(call("POST", "/api/v1/bi/mq", as("cora", "curves-only"), q));
        assertThat(cora.path("rows").size()).as("the input was asked as cora, who may not read trades: nothing").isZero();
    }
}
