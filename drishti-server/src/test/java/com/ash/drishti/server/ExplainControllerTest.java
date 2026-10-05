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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.engine.explain.ExplainService;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * About this page, layers 3 and 4 (docs/architecture/CONTEXT_HELP.md): {@code GET /api/v1/views/{kind}/{id}/explain} says
 * where the data came from and how fresh it is, which Sutra was chosen and why, and leaves the view itself untouched.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.desk.kinds[0]=trade", "drishti.security.roles.curves.kinds[0]=curve",
        "drishti.identity.database-url=jdbc:sqlite:target/explain-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class ExplainControllerTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired ExplainService explain;
    private final ObjectMapper json = new ObjectMapper();

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
    }

    private JsonNode read(String url, String role, String... params) throws Exception {
        var req = get(url).header("Authorization", as(role));
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void saysWhereTheDataCameFromAndWhichSutraWasChosen() throws Exception {
        JsonNode e = read("/api/v1/views/trade/IRS-48213/explain", "admin");
        assertThat(e.path("ref").path("kind").asText()).isEqualTo("trade");
        assertThat(e.path("mnemonic").asText()).isEqualTo("TRD");
        assertThat(e.path("locale").asText()).isEqualTo("en");
        JsonNode data = e.path("data");
        assertThat(data.path("source").asText()).isEqualTo("aero-risk");
        assertThat(data.path("health").asText()).isIn("up", "degraded", "down");
        assertThat(data.path("current").asBoolean()).isTrue();
        assertThat(data.path("fetchedAt").asText()).isNotBlank();
        assertThat(data.path("linked").path("budgetMs").asLong()).isPositive();
        assertThat(data.path("linked").path("denied").asInt()).isZero();

        JsonNode layout = e.path("layout");
        assertThat(layout.path("label").asText()).isEqualTo("Sutra irs-vanilla v3 + inference");
        JsonNode sutra = layout.path("sutra");
        assertThat(sutra.path("name").asText()).isEqualTo("irs-vanilla");
        assertThat(sutra.path("version").asInt()).isEqualTo(3);
        assertThat(sutra.path("priority").asInt()).isEqualTo(10);
        assertThat(sutra.path("where").asText()).contains("productType == 'IRS'");
        assertThat(sutra.path("description").asText()).contains("interest rate swap");
        assertThat(layout.path("candidates")).isNotEmpty();
        for (JsonNode c : layout.path("candidates")) {
            assertThat(c.path("name").asText()).isNotEqualTo("irs-vanilla");
            assertThat(c.path("result").asText()).isIn("true", "false", "error", "masked");
        }
        assertThat(layout.path("candidates").findValuesAsText("name")).contains("fx-swap");

        JsonNode next = e.path("next");
        assertThat(next.path("panelKinds").findValuesAsText("")).isNotNull();
        assertThat(next.path("panelKinds").toString()).contains("tabs", "table", "line", "links");
        assertThat(next.path("keys").findValuesAsText("key")).contains("F7", "F8", "F9");
        assertThat(e.path("timings").path("explain").asDouble()).isNotNegative();
        assertThat(e.toString()).doesNotContain("null");   // empty blocks are omitted, not sent as nulls
    }

    @Test
    void theViewIsUntouchedByExplaining() throws Exception {
        String before = stripTimings(read("/api/v1/views/trade/IRS-48213", "admin"));
        read("/api/v1/views/trade/IRS-48213/explain", "admin");
        assertThat(stripTimings(read("/api/v1/views/trade/IRS-48213", "admin"))).isEqualTo(before);
    }

    private static String stripTimings(JsonNode view) {
        ((ObjectNode) view).remove("timings");
        ((ObjectNode) view.path("provenance")).remove("fetchedAt");
        return view.toString();
    }

    @Test
    void theAnswerIsCachedPerUserAndPurged() throws Exception {
        explain.purge();
        read("/api/v1/views/trade/IRS-48213/explain", "admin");
        long cached = explain.size();
        assertThat(cached).isPositive();
        read("/api/v1/views/trade/IRS-48213/explain", "admin");
        assertThat(explain.size()).isEqualTo(cached);                 // served from the cache
        read("/api/v1/views/trade/IRS-48213/explain", "desk");
        assertThat(explain.size()).isEqualTo(cached + 1);             // another user, another answer
        explain.purge();
        assertThat(explain.size()).isZero();
    }

    @Test
    void aNewerGenerationOnTheServerIsSaid() throws Exception {
        JsonNode now = read("/api/v1/views/trade/IRS-48213/explain", "admin");
        long gen = now.path("generation").asLong();
        assertThat(now.has("newer")).isFalse();
        assertThat(read("/api/v1/views/trade/IRS-48213/explain", "admin", "generation", String.valueOf(gen)).has("newer")).isFalse();
        JsonNode newer = read("/api/v1/views/trade/IRS-48213/explain", "admin", "generation", String.valueOf(gen - 1));
        assertThat(newer.path("newer").asBoolean()).isTrue();
        assertThat(newer.path("generation").asLong()).isEqualTo(gen);
    }

    @Test
    void panelNarrowingAndTheProblemCodes() throws Exception {
        assertThat(read("/api/v1/views/trade/IRS-48213/explain", "admin", "panel", "curve").path("ref").path("id").asText()).isEqualTo("IRS-48213");
        mvc.perform(get("/api/v1/views/trade/IRS-48213/explain").param("panel", "nope").header("Authorization", as("admin")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-4006"));
        mvc.perform(get("/api/v1/views/trade/NOPE-1/explain").header("Authorization", as("admin")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-1001"));
        mvc.perform(get("/api/v1/views/trade/IRS-48213/explain").header("Authorization", as("curves")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        mvc.perform(get("/api/v1/views/trade/IRS-48213/explain")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/views/trade/IRS-48213/explain").param("asOf", "yesterday").header("Authorization", as("admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4003"));
    }
}
