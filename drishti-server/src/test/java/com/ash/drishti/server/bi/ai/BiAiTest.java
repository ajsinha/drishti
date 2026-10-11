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
package com.ash.drishti.server.bi.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.ai.CallContext;
import com.ash.drishti.ai.ChatRequest;
import com.ash.drishti.ai.ChatResponse;
import com.ash.drishti.ai.LlmException;
import com.ash.drishti.ai.LlmGateway;
import com.ash.drishti.ai.LlmPolicy;
import com.ash.drishti.ai.LlmRegistry;
import com.ash.drishti.ai.ModelInfo;
import com.ash.drishti.ai.llm.LLMFactory;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.ai.AiProperties;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * BI on the AI layer (docs/guides/BI_AI.md) with the mock provider only: the prompt carries the dataset model and no row, a draft is shown only if the report
 * checker passes it, a question runs on the engine as the reader (two readers, two answers; a masked field stays masked), and a number's lineage and breakdown
 * come from the engine. Security on, a small generated lake.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/bi-ai-${random.uuid}/identity.db", "drishti.packs.overlay=target/bi-ai-overlay/added.yaml",
        "drishti.bi.dir=target/bi-ai-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/bi-ai-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=target/test-data/bi-ai-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.ai-lake.plugin=file", "drishti.sources.connectors.ai-lake.kinds[0]=trade",
        "drishti.sources.connectors.ai-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.ai-lake.settings.layout.trade.columns=desk,book,currency,notional,mtm,tradeDate",
        "drishti.ai.enabled=true", "drishti.ai.dir=target/test-data/bi-ai/ai", "drishti.ai.default-model=mock/m", "drishti.ai.watch=off",
        "drishti.ai.policy.features.[bi-draft].roles[0]=report-author"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BiAiTest {

    static final String REPORT = "file:report:lake-report";
    static final JsonNode DATES = new ObjectMapper().createObjectNode().set("dates", new ObjectMapper().createObjectNode().put("from", "2026-10-08").put("to", "2026-10-08"));
    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired LlmRegistry registry;
    @Autowired BiService bi;
    @Autowired BiReportService reports;
    @Autowired DatasetService datasets;
    @Autowired AuditLog audit;
    final ObjectMapper json = new ObjectMapper();

    static void day(String day) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            lines.add("{\"id\":\"T-" + day.substring(8) + "-" + i + "\",\"tradeId\":\"T-" + day.substring(8) + "-" + i + "\",\"desk\":\"DESK-" + (char) ('A' + i % 3)
                    + "\",\"book\":\"BOOK-" + (i % 3 + 1) + "\",\"currency\":\"" + (i % 2 == 0 ? "USD" : "EUR") + "\",\"notional\":" + 1000.0 * (i + 1) + ",\"mtm\":"
                    + ((i % 5) - 2) * 100.0 + ",\"tradeDate\":\"" + day + "\"}");
        }
        Path f = root.resolve("lake").resolve(day).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = Files.createTempDirectory("drishti-bi-ai-lake");
        day("2026-10-08");
        r.add("drishti.sources.connectors.ai-lake.settings.root", () -> root.resolve("lake").toString());
    }

    static {
        try {
            Path datasets = Path.of("target/bi-ai-folder/datasets");
            Path reports = Path.of("target/bi-ai-folder/reports");
            Path ai = Path.of("target/test-data/bi-ai/ai");
            Files.createDirectories(datasets);
            Files.createDirectories(reports);
            Files.createDirectories(ai);
            Files.writeString(datasets.resolve("lake-report.dataset.yaml"), """
                    rachana: 1
                    dataset: lake-report
                    title: Lake for reports
                    description: Trades of the lake
                    tables:
                      trades:
                        source: { connector: ai-lake, kind: trade }
                        columns:
                          tradeId: { type: string, key: true }
                          desk: { type: string, description: The desk that owns the trade }
                          book: string
                          currency: string
                          notional: decimal
                          mtm: decimal
                          tradeDate: date
                    measures:
                      Notional: { expr: "sum(trades.notional)", description: Sum of the notional }
                      MTM: { expr: "sum(trades.mtm)" }
                      Trades: { expr: "count(trades)" }
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
                          - { id: by-desk, type: bar, title: Notional by desk, at: { x: 0, y: 2, w: 6, h: 4 }, wells: { axis: [trades.desk], values: [Notional] } }
                    """);
            Files.writeString(ai.resolve("mock.yaml"), """
                    type: mock
                    models:
                      - name: m
                    mock:
                      rules:
                        - match: "INVALIDDRAFT"
                          reply: "report: bad\\ndataset: lake-report\\npages:\\n  - id: p\\n    visuals:\\n      - { id: v, type: bar, at: { x: 0, y: 0, w: 4, h: 3 }, wells: { axis: [trades.nope], values: [Nothing] } }\\n"
                        - match: "TASK: draft-report"
                          reply: |
                            ```yaml
                            rachana: 1
                            report: drafted
                            version: 1
                            title: Drafted
                            dataset: something-else
                            pages:
                              - id: main
                                title: Main
                                visuals:
                                  - { id: by-desk, type: bar, title: Notional by desk, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trades.desk], values: [Notional] }, alt: Bar chart of notional by desk }
                            ```
                        - match: "QUESTION: [^\\n]*mtm"
                          reply: '{"fields":["trades.desk"],"measures":["MTM"],"dates":{"from":"2026-10-08","to":"2026-10-08"}}'
                        - match: "QUESTION: [^\\n]*nonsense"
                          reply: '{"fields":["trades.nope"],"measures":["Notional"]}'
                        - match: "QUESTION: [^\\n]*desk"
                          reply: '{"fields":["trades.desk"],"measures":["Notional"],"dates":{"from":"2026-10-08","to":"2026-10-08"},"orderBy":[{"by":"Notional","desc":true}]}'
                        - match: "QUESTION: [^\\n]*total"
                          reply: '{"fields":[],"measures":["Notional"],"dates":{"from":"2026-10-08","to":"2026-10-08"}}'
                    """);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    ResultActions send(String path, String who, Object body) throws Exception {
        return mvc.perform(post(java.net.URI.create(path)).header("Authorization", who).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @BeforeAll
    void setUp() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        role(admin, "bi-risk", List.of("bi"), true);
        role(admin, "desk-head", List.of("bi"), true);
        role(admin, "bi-analyst", List.of("bi"), false);
        role(admin, "report-author", List.of("bi", "bi-author"), false);
        role(admin, "other-author", List.of("bi", "bi-author"), false);
        role(admin, "plain", List.of(), false);
        for (String u : List.of("rita:bi-risk:", "hana:desk-head:DESK-A", "ann:bi-analyst:", "wes:report-author:", "ora:other-author:", "nobi:plain:")) {
            String[] n = u.split(":", -1);
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "desk", n[2], "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
    }

    void role(String admin, String name, List<String> biPowers, boolean raw) throws Exception {
        mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "raw", raw, "bi", biPowers)))).andExpect(status().isOk());
    }

    /** A service over the same layer whose gateway remembers every prompt it is given. */
    BiAiService spying(List<String> prompts, String values) {
        LlmPolicy capture = new LlmPolicy() {
            @Override public ChatRequest before(CallContext ctx, ModelInfo model, ChatRequest request) {
                prompts.add(request.system() + "\n" + request.messages().get(request.messages().size() - 1).content());
                return request;
            }

            @Override public void after(CallContext ctx, ModelInfo model, ChatRequest request, ChatResponse response, LlmException failure, long millis) {}
        };
        return new BiAiService(new LLMFactory(new LlmGateway(registry, capture), null), new AiProperties(true, null, null, null, null, null),
                new BiAiProperties(null, values, null, null, null, null, null, null), bi, reports, datasets, audit);
    }

    @Test
    void theDraftPromptCarriesTheModelAndNoRowAndTheDraftPassesTheCheckerBeforeItIsShown() throws Exception {
        List<String> prompts = new ArrayList<>();
        Principal wes = new Principal("wes", List.of("report-author"));
        Map<String, Object> d = spying(prompts, "labels-only").draft(wes, "lake-report", "notional by desk");
        assertThat(d.get("valid")).isEqualTo(true);
        assertThat((String) d.get("yaml")).contains("dataset: lake-report").doesNotContain("something-else").doesNotContain("```");
        String prompt = String.join("\n", prompts);
        assertThat(prompt).contains("trades.desk string - The desk that owns the trade").contains("Notional - Sum of the notional").contains("notional by desk");
        assertThat(prompt).as("no row, no value, no measure definition").doesNotContain("DESK-A").doesNotContain("T-08").doesNotContain("sum(trades.notional)").doesNotContain("24000");
        JsonNode http = ok(send("/api/v1/bi/ai/draft", as("wes", "report-author"), Map.of("dataset", "lake-report", "request", "notional by desk")));
        assertThat(http.path("valid").asBoolean()).isTrue();
        assertThat(http.path("saved").asBoolean()).isFalse();
        send("/api/v1/bi/ai/draft", as("wes", "report-author"), Map.of("dataset", "lake-report", "request", "INVALIDDRAFT")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRS-4020"));
        send("/api/v1/bi/ai/draft", as("ann", "bi-analyst"), Map.of("dataset", "lake-report", "request", "x")).andExpect(status().isForbidden());
        send("/api/v1/bi/ai/draft", as("ora", "other-author"), Map.of("dataset", "lake-report", "request", "x")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-4018"));                      // an author, but the AI policy opens bi-draft to report-author only
        send("/api/v1/bi/ai/draft", as("wes", "report-author"), Map.of("dataset", "lake-report", "request", " ")).andExpect(status().isBadRequest());
    }

    @Test
    void anAskedQuestionRunsOnTheEngineAsTheReaderSoTwoReadersGetTwoAnswers() throws Exception {
        JsonNode rita = ok(send("/api/v1/bi/ai/ask", as("rita", "bi-risk"), Map.of("report", REPORT, "question", "notional by desk")));
        JsonNode hana = ok(send("/api/v1/bi/ai/ask", as("hana", "desk-head"), Map.of("report", REPORT, "question", "notional by desk")));
        assertThat(rita.path("rows")).hasSize(3);
        assertThat(hana.path("rows")).as("a desk head's row rule applies to the model's query").hasSize(1);
        assertThat(hana.path("rows").get(0).get(0).asText()).isEqualTo("DESK-A");
        assertThat(rita.path("query").path("fields").toString()).isEqualTo("[\"trades.desk\"]");
        assertThat(rita.path("visual").asText()).isEqualTo("bar");
        assertThat(rita.path("summary").isNull()).as("labels-only: the model never sees the answer").isTrue();
        JsonNode total = ok(send("/api/v1/bi/ai/ask", as("rita", "bi-risk"), Map.of("dataset", "file:dataset:lake-report", "question", "the total")));
        assertThat(total.path("visual").asText()).isEqualTo("card");
        JsonNode ann = ok(send("/api/v1/bi/ai/ask", as("ann", "bi-analyst"), Map.of("report", REPORT, "question", "mtm by desk")));
        assertThat(ann.path("meta").path("masked").toString()).as("a masked field stays masked").contains("MTM");
        send("/api/v1/bi/ai/ask", as("rita", "bi-risk"), Map.of("report", REPORT, "question", "nonsense")).andExpect(status().isUnprocessableEntity());
        send("/api/v1/bi/ai/ask", as("rita", "bi-risk"), Map.of("report", REPORT, "question", "something with no rule")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRS-4020"));
        send("/api/v1/bi/ai/ask", as("nobi", "plain"), Map.of("report", REPORT, "question", "notional by desk")).andExpect(status().isForbidden());
    }

    @Test
    void theAskPromptHasNoRowAndAggregatesModeGivesTheModelOnlyTheReadersOwnUnmaskedAggregates() throws Exception {
        List<String> labels = new ArrayList<>();
        Principal hana = new Principal("hana", List.of("desk-head"));
        spying(labels, "labels-only").ask(hana, REPORT, "", "notional by desk");
        assertThat(labels).hasSize(1);
        assertThat(labels.get(0)).doesNotContain("DESK-A").doesNotContain("T-08").contains("trades.desk");
        List<String> agg = new ArrayList<>();
        Map<String, Object> a = spying(agg, "aggregates").ask(hana, REPORT, "", "notional by desk");
        assertThat(agg).hasSize(2);
        assertThat(agg.get(1)).contains("DESK-A").doesNotContain("DESK-B").doesNotContain("DESK-C");
        assertThat(a.get("summary")).isNotNull();
        List<String> masked = new ArrayList<>();
        spying(masked, "aggregates").ask(new Principal("ann", List.of("bi-analyst")), REPORT, "", "mtm by desk");
        assertThat(masked.get(1)).as("a column masked for the reader is not worded by the model").doesNotContain("MTM").contains("trades.desk");
    }

    @Test
    void explainGivesTheLineageTheEnginesBreakdownAndRespectsTheFeatureRoles() throws Exception {
        Map<String, Object> body = Map.of("report", REPORT, "page", "main", "visual", "by-desk", "state", Map.of("dates", Map.of("from", "2026-10-08", "to", "2026-10-08")));
        JsonNode r = ok(send("/api/v1/bi/ai/explain", as("rita", "bi-risk"), body));
        assertThat(r.path("measure").asText()).isEqualTo("Notional");
        assertThat(r.path("lineage").path("dataset").path("name").asText()).isEqualTo("lake-report");
        assertThat(r.path("lineage").path("measure").path("expr").asText()).isEqualTo("sum(trades.notional)");
        assertThat(r.path("lineage").path("sources").get(0).path("connector").asText()).isEqualTo("ai-lake");
        assertThat(r.path("lineage").path("sources").get(0).path("kind").asText()).isEqualTo("trade");
        assertThat(r.path("lineage").path("columns").get(0).path("name").asText()).isEqualTo("trades.notional");
        assertThat(r.path("value").asDouble()).isEqualTo(300000.0);
        JsonNode first = r.path("breakdown").get(0);
        assertThat(first.path("dimension").asText()).isEqualTo("trades.desk");
        assertThat(first.path("members")).hasSize(3);
        assertThat(first.path("members").get(0).path("share").asDouble()).isGreaterThan(0);
        assertThat(r.path("narrative").isNull()).as("labels-only: no model call, no wording").isTrue();
        send("/api/v1/bi/ai/explain", as("rita", "bi-risk"), Map.of("report", REPORT, "page", "main", "visual", "total", "measure", "Nope")).andExpect(status().isBadRequest());
        List<String> prompts = new ArrayList<>();
        Map<String, Object> w = spying(prompts, "aggregates").explain(new Principal("rita", List.of("bi-risk")), REPORT, "main", "by-desk", "", DATES);
        assertThat(w.get("narrative")).isNotNull();
        assertThat(prompts.get(0)).contains("TOTAL: 300000").contains("DESK-A").doesNotContain("T-08");
    }

    @Test
    void theHooksAreRefusedWhenTheAiLayerIsOffAndStatusSaysSo() throws Exception {
        BiAiService off = new BiAiService(new LLMFactory(new LlmGateway(registry, LlmPolicy.none()), null), new AiProperties(null, null, null, null, null, null),
                BiAiProperties.defaults(), bi, reports, datasets, audit);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> off.ask(new Principal("rita", List.of("bi-risk")), REPORT, "", "x")).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("switched off");
        mvc.perform(get("/api/v1/bi/ai/status").header("Authorization", as("wes", "report-author"))).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.values").value("labels-only")).andExpect(jsonPath("$.draft").value(true));
        mvc.perform(get("/api/v1/bi/ai/status").header("Authorization", as("ann", "bi-analyst"))).andExpect(jsonPath("$.draft").value(false));
    }
}
