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
package com.ash.drishti.server.saccr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * The SA-CCR API through the server on the hand-built cases (the EXAMPLE scope, no data needed): the page's one answer with its
 * waterfall, a case alone, the parameters with their paragraphs, the playground's grid and explanation, the workbook and the template,
 * and the refusals.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance", "drishti.sources.plugins.demo.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false", "drishti.var.official.on-start=false", "drishti.var.official.publish=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.frtb.parameters-dir=../config/packs/market-risk/capital",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class SaccrApiTest {

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
    void theCasesAnswerAsOnePageWithTheirWaterfalls() throws Exception {
        JsonNode a = ask("/api/v1/saccr/scope", 200, "scope", "EXAMPLE", "nettingSet", "EX-S1");
        assertThat(a.get("sets").size()).isEqualTo(12);
        JsonNode s1 = a.get("selected");
        assertThat(s1.get("nettingSet").asText()).isEqualTo("EX-S1");
        assertThat(s1.get("EAD").asDouble()).isBetween(4_776_789.035, 4_776_789.045);
        JsonNode steps = s1.get("waterfall");
        assertThat(steps.get(steps.size() - 1).get("to").asDouble()).isEqualTo(s1.get("EAD").asDouble());
        assertThat(s1.get("formula").size()).isGreaterThanOrEqualTo(5);
        assertThat(a.get("parameters").get("sha256").asText()).hasSize(64);
        JsonNode s5 = ask("/api/v1/saccr/scope", 200, "scope", "EXAMPLE:S5").get("selected");
        assertThat(s5.get("CAPPED").asBoolean()).isTrue();
        JsonNode off = ask("/api/v1/saccr/scope", 200, "scope", "EXAMPLE:S5", "cap", "false").get("selected");
        assertThat(off.get("CAPPED").asBoolean()).isFalse();
        JsonNode p = ask("/api/v1/saccr/parameters", 200);
        assertThat(p.get("regulation").asText()).isEqualTo("BCBS");
        assertThat(p.get("values").toString()).contains("CRE52.72");
    }

    @Test
    void thePlaygroundAndItsWorkbooks() throws Exception {
        JsonNode p = ask("/api/v1/saccr/playground", 200, "scope", "EXAMPLE");
        assertThat(p.get("grid").size()).isGreaterThan(10);
        assertThat(p.get("explainSteps").size()).isGreaterThan(20);
        for (String path : List.of("/api/v1/saccr/playground.xlsx", "/api/v1/saccr/playground/template")) {
            MvcResult started = mvc.perform(req(get(path), "scope", "EXAMPLE")).andExpect(request().asyncStarted()).andReturn();
            byte[] xlsx = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(xlsx.length).isGreaterThan(10_000);
            assertThat(new String(xlsx, 0, 2, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        }
    }

    @Test
    void refusals() throws Exception {
        assertThat(ask("/api/v1/saccr/scope", 400, "scope", "DESK:DESK-RATES").toString()).contains("DRS-5410");
        assertThat(ask("/api/v1/saccr/scope", 400, "scope", "EXAMPLE:S99").toString()).contains("DRS-5410");
        assertThat(ask("/api/v1/saccr/scope/contributions", 400, "scope", "EXAMPLE", "by", "desk").toString()).contains("DRS-5410");
    }
}
