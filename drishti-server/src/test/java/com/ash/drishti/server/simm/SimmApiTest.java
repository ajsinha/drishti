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
package com.ash.drishti.server.simm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The SIMM API through the server on the hand-built examples (the EXAMPLE scope, no data needed) with the TOY parameter file (not ISDA's):
 * the margin both ways, the steps, the versions, the playground with a comparison in words, the workbook and the empty template, a recipe
 * saved and replayed to the cent, and the refusals.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance", "drishti.sources.plugins.demo.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false", "drishti.var.official.on-start=false", "drishti.var.official.publish=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.frtb.parameters-dir=../config/packs/market-risk/capital",
        "drishti.simm.parameters-dir=../drishti-compute/src/test/resources/simm", "drishti.simm.recipes-dir=target/simm-recipes-${random.uuid}",
        "drishti.database.sqlite.file=target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class SimmApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private MockHttpServletRequestBuilder req(MockHttpServletRequestBuilder b, String... params) {
        b = b.header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300));
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return b;
    }

    private JsonNode ask(String path, int status, String... params) throws Exception {
        MvcResult r = mvc.perform(req(get(path), params)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void theMarginOfTheExamplesBothWaysWithItsSteps() throws Exception {
        JsonNode a = ask("/api/v1/simm", 200, "nettingSet", "EXAMPLE");
        assertThat(a.get("SIMM_VERSION").asText()).isEqualTo("TOY-1");
        assertThat(a.get("PARAMETER_SHA256").asText()).hasSize(64);
        assertThat(a.get("IM_COLLECT").asDouble()).isGreaterThan(a.get("IM_POST").asDouble());
        assertThat(a.get("BY_PRODUCT_CLASS")).hasSize(2);
        JsonNode ir = a.get("BY_RISK_CLASS").get(0);
        assertThat(ir.get("RISK_CLASS").asText()).isEqualTo("InterestRate");
        assertThat(ir.get("DELTA").asDouble()).isBetween(357_483.425, 357_483.435);
        JsonNode usd = ask("/api/v1/simm", 200, "nettingSet", "EXAMPLE", "override", "InterestRate.concentration-thresholds.delta.1=3000");
        assertThat(usd.get("BY_RISK_CLASS").get(0).get("DELTA").asDouble()).isBetween(382_700.575, 382_700.585);
        assertThat(usd.get("REGULATORY").asBoolean()).isFalse();
        JsonNode steps = ask("/api/v1/simm/explain", 200, "nettingSet", "EXAMPLE", "class", "CreditQ");
        assertThat(steps.get("charges").get(0).get("buckets").get(0).get("K").asDouble()).isBetween(786_129.755, 786_129.765);
        assertThat(steps.get("concentration")).isNotEmpty();
        JsonNode v = ask("/api/v1/simm/versions", 200);
        assertThat(v.get("default").asText()).isEqualTo("TOY-1");
        ask("/api/v1/simm", 400, "nettingSet", "EXAMPLE", "concentration", "maybe");
        ask("/api/v1/simm", 400, "nettingSet", "EXAMPLE", "override", "InterestRate.no-such=1");
        ask("/api/v1/simm", 502, "nettingSet", "EXAMPLE", "version", "2.8+2512");
        ask("/api/v1/simm", 400);
    }

    @Test
    void thePlaygroundComparesASwitchInWordsAndARecipeReplaysToTheCent() throws Exception {
        JsonNode p = ask("/api/v1/simm/playground", 200, "nettingSet", "EXAMPLE", "compare", "hvr=off");
        assertThat(p.get("choices")).hasSize(3);
        assertThat(p.get("grid").size()).isPositive();
        assertThat(p.get("explainSteps").size()).isGreaterThan(10);
        assertThat(p.get("compare").get("words").asText()).contains("hvr: on → off");
        MvcResult saved = mvc.perform(req(post("/api/v1/simm/playground/recipes"), "nettingSet", "EXAMPLE", "concentration", "off")).andReturn();
        assertThat(saved.getResponse().getStatus()).as(saved.getResponse().getContentAsString()).isEqualTo(200);
        String id = json.readTree(saved.getResponse().getContentAsString()).get("id").asText();
        JsonNode replay = ask("/api/v1/simm/playground/recipes/" + id, 200);
        assertThat(replay.get("reproduced").asText()).startsWith("reproduced to the cent");
        ask("/api/v1/simm/playground/recipes/SM-NONE-0", 404);
        ask("/api/v1/simm/playground/recipes/FR-NONE-0", 400);
    }

    @Test
    void theWorkbookAndTheEmptyTemplateDownload() throws Exception {
        for (String path : List.of("/api/v1/simm/playground.xlsx", "/api/v1/simm/playground/template")) {
            MvcResult started = mvc.perform(req(get(path), "nettingSet", "EXAMPLE")).andExpect(request().asyncStarted()).andReturn();
            byte[] xlsx = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(xlsx.length).isGreaterThan(10_000);
            assertThat(new String(xlsx, 0, 2, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        }
    }
}
