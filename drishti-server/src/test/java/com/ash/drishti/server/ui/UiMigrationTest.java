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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.rachana.ui.UiDocuments;
import com.ash.drishti.rachana.ui.migrate.Conversion;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase P5 over the whole stack with security on and the trading pack's committed sample trades: the dry run, the proof of every trading entity
 * page for three people (full: every kind, raw; masked: every kind, NOTIONAL and TRADER_ID masked; restricted: the trade kinds only, so links to
 * counterparties and books are refused), a deliberately broken chip failing the proof at the right path, and the per-pack switch to boards and back.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading", "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=NOTIONAL,TRADER_ID", "drishti.ui.dir=../config/ui",
        "drishti.identity.database-url=jdbc:sqlite:target/ui-migrate-${random.uuid}/identity.db",
        "drishti.data.dir=target/ui-migrate-data/${random.uuid}",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.restricted.kinds[0]=trade", "drishti.security.roles.restricted.kinds[1]=counterparty-trades",
        "drishti.security.roles.restricted.kinds[2]=portfolio-trades", "drishti.security.roles.restricted.kinds[3]=legal-entity-trades",
        "drishti.security.roles.restricted.kinds[4]=index-trades", "drishti.security.roles.restricted.raw=true",
        "drishti.security.roles.boss.kinds[0]=*", "drishti.security.roles.boss.raw=true", "drishti.security.roles.boss.admin=true"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UiMigrationTest {

    static final List<Principal> PEOPLE = List.of(new Principal("fay", List.of("full")), new Principal("max", List.of("masked")),
            new Principal("ria", List.of("restricted")));

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired BoardProof proof;
    @Autowired PackScreens screens;
    @Autowired StructuredSearch search;
    final ObjectMapper json = new ObjectMapper();
    ObjectNode proven;

    String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    /** Records of the derived kinds (a counterparty's, a book's, a legal entity's and an index's trades), named from the sample trades. */
    Map<String, List<String>> derived() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (StructuredSearch.Row r : search.run(SearchQuery.parse("trade limit 40"), AsOf.LATEST, UnaryOperator.identity()).rows()) {
            add(m, "counterparty-trades", r.values().get("COUNTERPARTY"));
            add(m, "portfolio-trades", r.values().get("PORTFOLIO"));
            add(m, "legal-entity-trades", r.values().get("LEGAL_ENTITY"));
            add(m, "index-trades", r.values().get("INDEX_NAME"));
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, List.copyOf(v).subList(0, Math.min(3, v.size()))));
        return out;
    }

    private static void add(Map<String, Set<String>> m, String kind, Object v) {
        if (v != null && !String.valueOf(v).isBlank()) {
            m.computeIfAbsent(kind, k -> new LinkedHashSet<>()).add(String.valueOf(v));
        }
    }

    @Test
    @Order(1)
    void theDryRunConvertsEveryTradingPageAndSavesNothing() throws Exception {
        String body = mvc.perform(post("/api/v1/ui/migrate/dry-run").header("Authorization", as("bo", "boss")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pack\":\"trading\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode r = json.readTree(body);
        assertThat(r.path("entityPages").asInt()).isEqualTo(134);
        assertThat(r.path("fails").asInt()).isZero();
        String list = mvc.perform(get("/api/v1/ui/migrate/trading").header("Authorization", as("bo", "boss"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains(r.path("run").asText() + ".json");
        mvc.perform(post("/api/v1/ui/migrate/dry-run").header("Authorization", as("fay", "full")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pack\":\"trading\"}")).andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    void everyTradingPageIsProvenForThreePeople() {
        proven = proof.prove("trading", PEOPLE, 3, derived(), null);
        System.out.println("P5 proof, trading: proven " + proven.path("proven") + ", differs " + proven.path("differs") + ", no sample "
                + proven.path("noSample") + ", comparisons " + proven.path("comparisons"));
        for (JsonNode s : proven.path("screens")) {
            if (!"proven".equals(s.path("result").asText())) {
                System.out.println("  " + s);
            }
        }
        assertThat(proven.path("differs").asInt()).as(proven.toString()).isZero();
        assertThat(proven.path("proven").asInt()).isGreaterThan(100);
    }

    @Test
    @Order(3)
    void aBrokenChipFailsTheProofWithThePathOfTheDifference() throws Exception {
        Map<String, PackScreens.Served> boards = screens.boards("trading");
        String sutra = "counterparty-trades";
        PackScreens.Served good = boards.get(sutra);
        ObjectNode doc = good.conversion().document().deepCopy();
        assertThat(doc.path("chips").path("pay30").path("use").asText()).isEqualTo("headline-figure@1");   // a Chip Store chip since 12.4
        ((ObjectNode) doc.path("chips").path("pay30")).put("title", "Payments, next 31 days");   // the fault
        Conversion c = good.conversion();
        Conversion broken = new Conversion(c.screen(), c.pack(), c.kind(), c.board(), doc, c.status(), c.seeds(), c.locals(), c.links(), c.traces(),
                c.reason(), c.problems());
        UiDocuments.readBoard(doc.toString());
        ObjectNode r = proof.prove("trading", PEOPLE, 1, derived(), Map.of(sutra, screens.served(broken)));
        JsonNode s = r.path("screens").get(0);
        assertThat(s.path("result").asText()).isEqualTo("differs");
        assertThat(s.path("difference").path("path").asText()).isEqualTo("panels[1].title");
        assertThat(s.path("difference").path("page").asText()).isEqualTo("Payments, next 30 days");
        assertThat(s.path("difference").path("board").asText()).isEqualTo("Payments, next 31 days");
    }

    @Test
    @Order(4)
    void theSwitchServesBoardsAndSwitchesBackAtOnce() throws Exception {
        String id = search.run(SearchQuery.parse("trade limit 1"), AsOf.LATEST, UnaryOperator.identity()).rows().get(0).ref().id();
        mvc.perform(put("/api/v1/ui/packs/trading").header("Authorization", as("bo", "boss")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"screens\":\"sutras\"}")).andExpect(status().isOk());      // whatever ui.yaml ships (every pack on boards since 2026-10-21)
        MockHttpServletResponse old = mvc.perform(get("/api/v1/views/trade/" + id).header("Authorization", as("max", "masked")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(old.getHeader("X-Drishti-Board")).isNull();
        mvc.perform(put("/api/v1/ui/packs/trading").header("Authorization", as("bo", "boss")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"screens\":\"boards\"}")).andExpect(status().isOk());
        MockHttpServletResponse onBoards = mvc.perform(get("/api/v1/views/trade/" + id).header("Authorization", as("max", "masked")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(onBoards.getHeader("X-Drishti-Board")).startsWith("trading-");
        JsonNode a = json.readTree(old.getContentAsString(StandardCharsets.UTF_8));
        JsonNode b = json.readTree(onBoards.getContentAsString(StandardCharsets.UTF_8));
        assertThat(b.path("panels")).hasSameSizeAs(a.path("panels"));
        assertThat(b.path("strip")).isEqualTo(a.path("strip"));
        assertThat(b.path("title")).isEqualTo(a.path("title"));
        mvc.perform(put("/api/v1/ui/packs/trading").header("Authorization", as("bo", "boss")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"screens\":\"sutras\"}")).andExpect(status().isOk());
        assertThat(mvc.perform(get("/api/v1/views/trade/" + id).header("Authorization", as("max", "masked"))).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Drishti-Board")).isNull();
        mvc.perform(put("/api/v1/ui/packs/trading").header("Authorization", as("bo", "boss")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"screens\":\"tiles\"}")).andExpect(status().isBadRequest());
    }

    /** P4b: the designer's "Open as board" and "Export Sutra" through P5's converter, and the board-local chips as Chip Store candidates. */
    @Test
    @Order(5)
    void theDesignerConvertsOneSutraAndListsTheCandidates() throws Exception {
        String sutra = java.nio.file.Files.readString(java.nio.file.Path.of("../config/packs/trading/sutras/pages/counterparty-trades.v1.sutra.yaml"));
        String body = json.writeValueAsString(Map.of("sutra", sutra));
        JsonNode r = json.readTree(mvc.perform(post("/api/v1/ui/convert/sutra").header("Authorization", as("bo", "boss"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.path("pack").asText()).isEqualTo("trading");
        assertThat(r.path("document").path("board").asText()).isEqualTo("trading-counterparty-trades");
        assertThat(r.path("document").path("params").path("id").path("of").asText()).isEqualTo("trading/counterparty-trades");
        assertThat(r.path("locals").toString()).contains("local-pay30");
        JsonNode back = json.readTree(mvc.perform(post("/api/v1/ui/convert/board").header("Authorization", as("bo", "boss"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("document", r.path("document")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(back.path("yaml").asText()).contains("sutra: trading-counterparty-trades").contains("Payments, next 30 days");
        mvc.perform(post("/api/v1/ui/convert/sutra").header("Authorization", as("bo", "boss")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sutra\":\"block: x\"}")).andExpect(status().isBadRequest());
        JsonNode list = json.readTree(mvc.perform(get("/api/v1/ui/candidates").param("pack", "trading").header("Authorization", as("bo", "boss")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(list.toString()).contains("\"chip\":\"local-pay30\"").doesNotContain("local-header");
        mvc.perform(get("/api/v1/ui/candidates/trading/trading-counterparty-trades/local-pay30").header("Authorization", as("bo", "boss")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document.chip").value("local-pay30"));
        mvc.perform(get("/api/v1/ui/candidates").header("Authorization", as("max", "masked"))).andExpect(status().isForbidden());
    }
}
