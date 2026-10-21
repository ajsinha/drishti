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
package com.ash.drishti.server.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The render API over the whole stack (UI_BOARD.md phase P3) with security on and a small lake read through a real file connector: the 3.1
 * {@code book-trades} board with its trades and flows over a BI model and its ticket over the lake's trade entity, rendered as rita (every desk, raw),
 * ann (every desk, {@code mtm} masked) and hana (a desk-head: DESK-A's rows only); a selection repainting the details on the open stream; a second viewer
 * of rita's access profile sharing her answer; an embed-scoped refusal of an unsaved board.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.security.redact=mtm",
        "drishti.identity.database-url=jdbc:sqlite:target/ui-render-${random.uuid}/identity.db", "drishti.packs.overlay=target/ui-render-overlay/added.yaml",
        "drishti.data.dir=target/ui-render-data/${random.uuid}", "drishti.ui.dir=../config/ui",
        "drishti.bi.dir=target/ui-render-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/ui-render-store/${random.uuid}",
        "drishti.sources.connectors-dir=target/test-data/ui-render-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.bt-lake.plugin=file", "drishti.sources.connectors.bt-lake.kinds[0]=trade",
        "drishti.sources.connectors.bt-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.bt-lake.settings.layout.trade.columns=tradeId,desk,book,currency,notional,mtm,tradeDate"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiRenderApiTest {

    static final String DAY = "2026-10-08";
    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = Files.createTempDirectory("drishti-ui-render-lake");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            String desk = i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C";
            lines.add("{\"id\":\"BT-" + i + "\",\"tradeId\":\"BT-" + i + "\",\"desk\":\"" + desk + "\",\"book\":\"BOOK-" + (i % 3 + 1)
                    + "\",\"currency\":\"USD\",\"notional\":" + 1000.0 * (i + 1) + ",\"mtm\":" + (i - 4) * 100.0 + ",\"tradeDate\":\"" + DAY + "\"}");
        }
        Path f = root.resolve("lake").resolve(DAY).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
        r.add("drishti.sources.connectors.bt-lake.settings.root", () -> root.resolve("lake").toString());
    }

    static {
        try {
            Path folder = Path.of("target/ui-render-folder");
            Files.createDirectories(folder.resolve("datasets"));
            Files.createDirectories(folder.resolve("models"));
            Files.writeString(folder.resolve("datasets/bt-trade.dataset.yaml"), """
                    rachana: 2
                    dataset: bt-trade
                    version: 1.0.0
                    source: { connector: bt-lake, kind: trade }
                    grain: { keys: [id] }
                    columns:
                      tradeId:   { type: string }
                      desk:      { type: string }
                      currency:  { type: string }
                      tradeDate: { type: date }
                      notional:  { type: decimal, aggregate: sum }
                      mtm:       { type: decimal, aggregate: sum }
                    security:
                      rows:
                        - { role: desk-head, where: "desk in user.desks" }
                        - { role: [bi-risk, bi-analyst, admin], where: "true" }
                      masked: [mtm]
                    """);
            Files.writeString(folder.resolve("models/bt-model.model.yaml"), """
                    rachana: 2
                    model: bt-model
                    version: 1.0.0
                    datasets:
                      trades: { dataset: bt-trade, role: fact }
                    measures:
                      Notional: { expr: "sum(trades.notional)" }
                      MTM:      { expr: "sum(trades.mtm)" }
                    """);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static final String BOARD = """
            rachana: 1
            board: book-trades
            title: Book trades
            params:
              as-of: { type: date, default: "%s" }
            chips:
              trades:
                use: filterable-table@2
                title: Trades
                supply:
                  model: bt-model@1
                  query:
                    dimensions: [trades.tradeId, trades.desk]
                    measures: [Notional, MTM]
                    time: { as-of: "%%{board.params.as-of}" }
                map:
                  rows.id: trades.tradeId
                  rows.label: trades.desk
                  rows.measures: [Notional, MTM]
              ticket:
                use: entity-card@1
                title: Trade ticket
                supply: { connector: bt-lake/trade }
                map:
                  doc.id: tradeId
                  doc.title: desk
                  doc.fields: [book, notional, mtm]
              flows:
                use: cashflow-ladder@1
                title: Cash flows
                supply:
                  model: bt-model@1
                  query:
                    dimensions: [trades.tradeDate, trades.currency, trades.tradeId]
                    measures: [Notional]
                    time: { as-of: "%%{board.params.as-of}" }
                map:
                  rows.date: trades.tradeDate
                  rows.group: trades.currency
                  rows.amount: Notional
                  rows.trade: trades.tradeId
            traces:
              - { from: trades.selected, to: ticket.id }
              - { from: trades.selected, to: flows.filter, transform: { filter-on: rows.trade } }
              - { from: board.params.as-of, to: trades.date-to }
            layout:
              desktop:
                columns: 12
                areas:
                  - { chip: trades, at: [1, 1], span: [7, 8] }
                  - { chip: ticket, at: [8, 1], span: [5, 4] }
                  - { chip: flows,  at: [8, 5], span: [5, 4] }
            """.formatted(DAY);

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
            case "rob" -> as("rob", "bi-risk");
            case "hana" -> as("hana", "desk-head");
            default -> as("ann", "bi-analyst");
        };
    }

    @BeforeAll
    void setUp() throws Exception {
        role("bi-risk", true);
        role("desk-head", true);
        role("bi-analyst", false);
        for (String u : List.of("rita:bi-risk:", "rob:bi-risk:", "hana:desk-head:DESK-A", "ann:bi-analyst:")) {
            String[] n = u.split(":", -1);
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "desk", n[2], "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
    }

    void role(String name, boolean raw) throws Exception {
        var r = mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "raw", raw, "layout", true, "bi", List.of("bi"))))).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    /** The SSE events of a response so far, as (name, data). */
    List<Object[]> events(MockHttpServletResponse res) throws Exception {
        List<Object[]> out = new ArrayList<>();
        for (String block : res.getContentAsString(StandardCharsets.UTF_8).split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                }
            }
            if (name != null) {
                out.add(new Object[] {name, json.readTree(data.toString())});
            }
        }
        return out;
    }

    static List<JsonNode> chip(List<Object[]> events, String inst) {
        return events.stream().filter(e -> e[0].equals("chip") && ((JsonNode) e[1]).path("instance").asText().equals(inst)).map(e -> (JsonNode) e[1]).toList();
    }

    /** A dynamic render to {@code done} ({@code stream: once}). */
    List<Object[]> renderOnce(String who) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/ui/render").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("board", BOARD, "stream", "once")))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        r.getAsyncResult(60_000);
        return events(r.getResponse());
    }

    @Test
    void threeReadersSeeTheirOwnMasksAndRowsAndADetailFollowsTheSelection() throws Exception {
        for (String who : List.of("rita", "ann", "hana")) {
            List<Object[]> ev = renderOnce(reader(who));
            assertThat(ev.get(0)[0]).isEqualTo("render");
            assertThat(ev.get(1)[0]).isEqualTo("layout");
            assertThat(ev.get(ev.size() - 1)[0]).isEqualTo("done");
            JsonNode trades = chip(ev, "trades").get(0);
            assertThat(trades.path("state").asText()).as(who + ": " + trades).isEqualTo("data");
            JsonNode rows = trades.path("viewmodel").path("pins").path("rows");
            List<String> desks = new ArrayList<>();
            rows.forEach(r -> desks.add(r.path("label").asText()));
            String mtm = rows.get(0).path("measures").get(1).path("value").asText();
            switch (who) {
                case "rita" -> {
                    assertThat(rows).hasSize(9);
                    assertThat(mtm).isNotEqualTo(BoardRender.MASK);
                }
                case "ann" -> {
                    assertThat(rows).hasSize(9);
                    rows.forEach(r -> assertThat(r.path("measures").get(1).path("value").asText()).isEqualTo(BoardRender.MASK));
                    assertThat(trades.path("meta").path("masked").toString()).contains("measures");
                }
                default -> assertThat(desks).isNotEmpty().allMatch("DESK-A"::equals);              // hana: the other desks' rows are absent
            }
            assertThat(chip(ev, "ticket").get(0).path("state").asText()).isEqualTo("waiting");
        }
    }

    @Test
    void aSelectionOnTheOpenStreamRepaintsTheTicketAndTheFlowsAsTheReader() throws Exception {
        for (String who : List.of("rita", "ann")) {
            MvcResult r = mvc.perform(post("/api/v1/ui/render").header("Authorization", reader(who)).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("board", BOARD)))).andReturn();
            long deadline = System.currentTimeMillis() + 60_000;
            while (events(r.getResponse()).stream().noneMatch(e -> e[0].equals("done")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            List<Object[]> first = events(r.getResponse());
            String renderId = ((JsonNode) first.get(0)[1]).path("renderId").asText();
            JsonNode keys = chip(first, "trades").get(0).path("viewmodel").path("keys");
            String selected = keys.get(3).asText();
            mvc.perform(post("/api/v1/ui/renders/" + renderId + "/pins").header("Authorization", reader(who)).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("instance", "trades", "pin", "selected", "value", selected)))).andExpect(status().isOk());
            // another person may not drive this render
            mvc.perform(post("/api/v1/ui/renders/" + renderId + "/pins").header("Authorization", reader("rob")).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("instance", "trades", "pin", "selected", "value", selected)))).andExpect(status().isForbidden());
            List<Object[]> all = events(r.getResponse());
            JsonNode ticket = chip(all, "ticket").get(1);
            assertThat(ticket.path("state").asText()).as(ticket.toString()).isEqualTo("data");
            JsonNode doc = ticket.path("viewmodel").path("pins").path("doc");
            assertThat(doc.path("id").asText()).isEqualTo(selected);
            String mtm = doc.path("fields").get(2).path("value").asText();
            assertThat(mtm.equals(BoardRender.MASK)).as(who + " mtm " + mtm).isEqualTo("ann".equals(who));
            JsonNode flows = chip(all, "flows").get(1).path("viewmodel").path("pins").path("rows");
            assertThat(flows).hasSize(1);
            assertThat(flows.get(0).path("trade").asText()).isEqualTo(selected);
            assertThat(chip(all, "trades")).hasSize(1);                                          // the master is not painted again
            mvc.perform(delete("/api/v1/ui/renders/" + renderId).header("Authorization", reader(who))).andExpect(status().isOk());
            mvc.perform(post("/api/v1/ui/renders/" + renderId + "/pins").header("Authorization", reader(who)).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("instance", "trades", "pin", "selected", "value", selected)))).andExpect(status().isGone());
        }
    }

    @Test
    void aSecondViewerOfTheSameAccessProfileSharesTheAnswerAMaskedOneNever() throws Exception {
        renderOnce(reader("rita"));
        JsonNode rob = chip(renderOnce(reader("rob")), "trades").get(0);
        assertThat(rob.path("meta").path("reused").asBoolean()).as(rob.toString()).isTrue();
        JsonNode ann = chip(renderOnce(reader("ann")), "trades").get(0);
        assertThat(ann.path("viewmodel").path("pins").path("rows").get(0).path("measures").get(1).path("value").asText()).isEqualTo(BoardRender.MASK);
    }

    @Test
    void aBadBoardIsRefusedWithItsProblemsBeforeAnyStream() throws Exception {
        var res = mvc.perform(post("/api/v1/ui/render").header("Authorization", reader("rita")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("board", BOARD.replace("to: ticket.id", "to: ticket.nope"))))).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(422);
        assertThat(res.getContentAsString()).contains("DRS-2071").contains("BC-06");
    }
}
