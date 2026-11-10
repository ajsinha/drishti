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
package com.ash.drishti.server.edition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A server started from the Drishti Finance distribution file alone (docs/architecture/DISTRIBUTIONS.md): its brand, its packs (market
 * data among them), no VaR, exposure or trading command in FUNC or the type-ahead (what the AI reads), a VaR command answering "not in
 * Drishti Finance", the VaR and exposure engines off, and Health's distribution card.
 */
@SpringBootTest(properties = {"drishti.distribution=finance", "drishti.distributions-dir=../config/distributions", "drishti.packs.dir=../config/packs",
        "drishti.packs.enabled=default", "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false",
        "drishti.bi.dir=../config/bi", "drishti.tables.root=../config/tables", "drishti.bi.query.store-dir=${drishti.test.dir}/finance-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/finance-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class DistributionFinanceServerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    JsonNode getJson(String path) throws Exception {
        MvcResult r = mvc.perform(get(java.net.URI.create(path))).andReturn();
        assertThat(r.getResponse().getStatus()).as(path + ": " + r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void itBootsFromItsFileWithItsBrand() throws Exception {
        JsonNode about = getJson("/api/v1/about");
        assertThat(about.path("product").asText()).isEqualTo("Drishti Finance");
        assertThat(about.path("edition").path("name").asText()).isEqualTo("drishti.finance");
        assertThat(getJson("/public/about").path("lenses").toString()).contains("world");
        List<String> packs = new ArrayList<>();
        getJson("/api/v1/packs").forEach(p -> packs.add(p.path("name").asText()));
        assertThat(packs).contains("market-data", "world-economy").doesNotContain("market-risk", "counterparty-risk", "trading");
    }

    @Test
    void noVarCommandExistsAndOneTypedIsNotInDrishtiFinance() throws Exception {
        List<String> codes = new ArrayList<>();
        getJson("/api/v1/functions").path("packs").forEach(p -> p.path("commands").forEach(c -> codes.add(c.path("code").asText())));
        assertThat(codes).contains("ECON", "USEQ", "CRV", "FX").doesNotContain("VRES", "PNLV", "EXP", "TRD", "FRTB");
        List<String> suggested = new ArrayList<>();
        getJson("/api/v1/command/suggest?q=V").forEach(s -> suggested.add(s.path("mnemonic").asText()));
        assertThat(suggested).doesNotContain("VRES", "VOFF", "VLIM");
        MvcResult risk = mvc.perform(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"VRES DESK:RATES\"}")).andReturn();
        assertThat(risk.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }

    @Autowired com.ash.drishti.server.engines.EngineRegistry engines;

    @Test
    void theEnginesOfTheModulesItLeavesOutAreOffAndHealthSaysSo() throws Exception {
        assertThat(engines.state("var")).isEqualTo(com.ash.drishti.server.engines.EngineRegistry.State.OFF);
        assertThat(engines.state("exposure")).isEqualTo(com.ash.drishti.server.engines.EngineRegistry.State.OFF);
        JsonNode d = getJson("/api/v1/health").path("distribution");
        assertThat(d.path("id").asText()).isEqualTo("drishti.finance");
        assertThat(d.path("modules").path("economy").asText()).isEqualTo("loaded");
        assertThat(d.path("asked").toString()).contains("capital-markets", "economy", "market-data");
    }
}
