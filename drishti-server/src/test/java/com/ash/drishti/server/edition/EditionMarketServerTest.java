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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A server started with {@code drishti.edition=market} (Drishti Market, config/editions/market.yaml; docs/architecture/EDITIONS.md section
 * 13): the market-data pack and the core it extends alone (allow-only: another is refused with DRS-1017), the pack's 22 commands in FUNC
 * and the type-ahead (no trades, no VaR, no world economy), no VaR or exposure BI item, and the About answers and branding.
 */
@SpringBootTest(properties = {"drishti.edition=market", "drishti.packs.enabled=default", "drishti.rachana.hot-reload=false",
        "drishti.sources.plugins.demo.enabled=false", "drishti.bi.dir=../config/bi",
        "drishti.bi.query.store-dir=${drishti.test.dir}/market-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/market-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class EditionMarketServerTest {

    static final List<String> MARKET_COMMANDS = List.of("CRV", "REPO", "FX", "FXF", "FXV", "IRV", "CPV", "EQ", "EQX", "DIV", "EQV", "CDS", "INF",
            "INFC", "CMD", "CMDC", "CMDV", "FIX", "BND", "CORR", "CORRC", "EQB");

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    JsonNode getJson(String path) throws Exception {
        MvcResult r = mvc.perform(get(java.net.URI.create(path))).andReturn();
        assertThat(r.getResponse().getStatus()).as(path + ": " + r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void aboutNamesTheEditionAndItsBranding() throws Exception {
        JsonNode about = getJson("/api/v1/about");
        assertThat(about.path("edition").path("name").asText()).isEqualTo("market");
        assertThat(about.path("edition").path("title").asText()).isEqualTo("Drishti Market");
        assertThat(about.path("product").asText()).isEqualTo("Drishti Market");
        assertThat(about.path("tagline").asText()).contains("market data").contains("risk and valuations");
        JsonNode pub = getJson("/public/about");
        assertThat(pub.path("edition").path("name").asText()).isEqualTo("market");
        assertThat(pub.path("lenses")).isEmpty();
    }

    @Test
    void onlyTheMarketDataPackAndItsCoreAreLoadedAndOthersAreRefused() throws Exception {
        List<String> names = new ArrayList<>();
        getJson("/api/v1/packs").forEach(p -> names.add(p.path("name").asText()));
        assertThat(names).containsExactlyInAnyOrderElementsOf(Set.of("core", "market-data"));
        for (String other : List.of("trading", "market-risk", "world-economy")) {
            MvcResult r = mvc.perform(post("/api/v1/admin/packs/" + other + "/load")).andReturn();
            assertThat(r.getResponse().getStatus()).as(other + ": " + r.getResponse().getContentAsString()).isEqualTo(403);
            assertThat(r.getResponse().getContentAsString()).contains("DRS-1017").contains("allow-only");
        }
    }

    @Test
    void thePacksOwnCommandsAreOfferedAndNoOthers() throws Exception {
        List<String> codes = new ArrayList<>();
        getJson("/api/v1/functions").path("packs").forEach(p -> p.path("commands").forEach(c -> codes.add(c.path("code").asText())));
        assertThat(codes).containsAll(MARKET_COMMANDS).doesNotContain("VRES", "TRD", "CPTY", "EXP", "FRTB", "ECON", "BOOK", "CAL", "ISS");
        List<String> suggested = new ArrayList<>();
        getJson("/api/v1/command/suggest?q=CR").forEach(s -> suggested.add(s.path("mnemonic").asText()));
        assertThat(suggested).contains("CRV").doesNotContain("CPTY");
        List<String> heads = new ArrayList<>();
        getJson("/api/v1/packs").forEach(p -> p.path("console").path("examples").forEach(e -> heads.add(
                (e.isArray() ? e.get(0).asText() : e.asText()).split("\\s+")[0])));
        assertThat(heads).isNotEmpty().allSatisfy(h -> assertThat(MARKET_COMMANDS).contains(h));
    }

    @Test
    void theMarketCommandsResolveAndRiskOrWorldCommandsAreNotOffered() throws Exception {
        MvcResult typed = mvc.perform(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"CRV CRV-USD-OIS\"}")).andReturn();
        assertThat(typed.getResponse().getContentAsString()).doesNotContain("DRS-4023");   // shown (the test has no lake: no entity needed)
        for (String hidden : List.of("VRES DESK:RATES", "CPTY CP-ALDERSHOT", "BOOK BK-RATES")) {
            MvcResult r = mvc.perform(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + hidden + "\"}")).andReturn();
            assertThat(r.getResponse().getStatus()).as(hidden).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void biOffersNoVarExposureOrWorldItems() throws Exception {
        List<String> ids = new ArrayList<>();
        getJson("/api/v1/bi/datasets").path("datasets").forEach(d -> ids.add(d.path("id").asText()));
        assertThat(ids).allSatisfy(id -> assertThat(id.substring(id.lastIndexOf(':') + 1)).startsWith("market-data-"));
        assertThat(mvc.perform(get("/api/v1/bi/items/file:dataset:market-risk-var-latest")).andReturn().getResponse().getStatus()).isEqualTo(404);
        List<String> items = new ArrayList<>();
        getJson("/api/v1/bi/catalogue").path("items").forEach(i -> items.add(i.path("id").asText()));
        assertThat(items).doesNotContain("file:report:market-risk-var", "file:report:trades-overview", "file:report:world-economics");
    }
}
