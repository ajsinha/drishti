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
 * A server started with {@code drishti.edition=risk} (docs/admin/EDITIONS.md): every server-side surface follows the edition file
 * (config/editions/risk.yaml): the packs (five, allow-only), the commands people may type and see (the command line, FUNC, the type-ahead,
 * the entity page and search, the packs' examples), the BI datasets, the About answers and the branding. DRISHTI_PACKS naming another pack
 * is not loaded, and loading one from Admin -> Packs is refused (DRS-1017).
 */
@SpringBootTest(properties = {"drishti.edition=risk", "drishti.packs.enabled=default", "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false",
        "drishti.var.official.enabled=false", "drishti.var.official.on-start=false", "drishti.ccr.exposure.official.enabled=false",
        "drishti.bi.dir=../config/bi", "drishti.bi.query.store-dir=${drishti.test.dir}/edition-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/edition-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class EditionRiskServerTest {

    static final Set<String> RISK_PACKS = Set.of("core", "market-data", "trading", "counterparty-risk", "market-risk");
    static final Set<String> RISK_DATASETS = Set.of("market-risk-var-latest", "market-risk-var-official", "counterparty-exposure-counterparties",
            "counterparty-exposure-netting-sets", "counterparty-exposure-official", "counterparty-exposure-official-counterparties",
            "counterparty-exposure-official-netting-sets", "live-exposure", "live-exposure-netting", "trading-risk-books", "trading-risk-counterparties",
            "trading-risk-desks", "trading-risk-netting-sets", "trading-risk-trades");

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? json.createObjectNode() : json.readTree(s);
    }

    JsonNode getJson(String path) throws Exception {
        MvcResult r = mvc.perform(get(java.net.URI.create(path))).andReturn();
        assertThat(r.getResponse().getStatus()).as(path + ": " + r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r);
    }

    @Test
    void aboutNamesTheEditionAndItsBranding() throws Exception {
        JsonNode about = getJson("/api/v1/about");
        assertThat(about.path("edition").path("name").asText()).isEqualTo("risk");
        assertThat(about.path("edition").path("title").asText()).isEqualTo("Drishti Risk");
        assertThat(about.path("product").asText()).isEqualTo("Drishti Risk");
        assertThat(about.path("tagline").asText()).isEqualTo("VaR and exposure from your own pricing data");
        JsonNode pub = getJson("/public/about");
        assertThat(pub.path("edition").path("name").asText()).isEqualTo("risk");
        assertThat(pub.path("product").asText()).isEqualTo("Drishti Risk");
        assertThat(pub.path("lenses")).isEmpty();                                      // an instance with an edition offers no lens
    }

    @Test
    void onlyTheEditionsPacksAreLoadedAndOthersAreRefused() throws Exception {
        List<String> names = new ArrayList<>();
        getJson("/api/v1/packs").forEach(p -> names.add(p.path("name").asText()));
        assertThat(names).containsExactlyInAnyOrderElementsOf(RISK_PACKS);
        MvcResult r = mvc.perform(post("/api/v1/admin/packs/gleif/load")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("DRS-1017").contains("allow-only");
    }

    @Test
    void hiddenCommandsAreLeftOutOfFuncAndTheTypeAhead() throws Exception {
        List<String> codes = new ArrayList<>();
        getJson("/api/v1/functions").path("packs").forEach(p -> p.path("commands").forEach(c -> codes.add(c.path("code").asText())));
        assertThat(codes).contains("VRES", "VWIF", "VOFF", "PNLV", "HIER", "EXP", "NSET", "XWIF", "TRD", "CPTY", "FX")
                .doesNotContain("FRTB", "STR", "PNL", "SCN", "TVAL", "CVA", "SACCR", "SIMM", "CRV", "FXV", "EQ");
        List<String> suggested = new ArrayList<>();
        getJson("/api/v1/command/suggest?q=FRT").forEach(s -> suggested.add(s.path("mnemonic").asText()));
        assertThat(suggested).doesNotContain("FRTB");
        List<String> vr = new ArrayList<>();
        getJson("/api/v1/command/suggest?q=VRE").forEach(s -> vr.add(s.path("mnemonic").asText()));
        assertThat(vr).contains("VRES");
    }

    @Test
    void aHiddenCommandTypedOrOpenedAnswersDrs4023() throws Exception {
        MvcResult typed = mvc.perform(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"FRTB FRTB-RATES-1\"}")).andReturn();
        assertThat(typed.getResponse().getStatus()).as(typed.getResponse().getContentAsString()).isEqualTo(404);
        assertThat(typed.getResponse().getContentAsString()).contains("DRS-4023");
        MvcResult page = mvc.perform(get("/api/v1/views/frtb-sensitivity/FRTB-RATES-1")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(404);
        assertThat(page.getResponse().getContentAsString()).contains("DRS-4023");
    }

    @Test
    void thePacksExamplesFollowTheEdition() throws Exception {
        List<String> heads = new ArrayList<>();
        getJson("/api/v1/packs").forEach(p -> p.path("console").path("examples").forEach(e -> heads.add(
                (e.isArray() ? e.get(0).asText() : e.asText()).split("\\s+")[0])));
        assertThat(heads).isNotEmpty().doesNotContain("FRTB", "STR", "PNL", "CVA", "SACCR", "CRV", "FXV");
    }

    @Test
    void biOffersTheEditionsDatasetsOnly() throws Exception {
        List<String> ids = new ArrayList<>();
        getJson("/api/v1/bi/datasets").path("datasets").forEach(d -> ids.add(d.path("id").asText()));
        assertThat(ids).isNotEmpty();
        assertThat(ids).allSatisfy(id -> assertThat(RISK_DATASETS).contains(id.substring(id.lastIndexOf(':') + 1)));
        assertThat(ids).contains("file:dataset:market-risk-var-latest");
        MvcResult hidden = mvc.perform(get("/api/v1/bi/items/file:dataset:trades-basic-trade")).andReturn();
        assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
        List<String> reports = new ArrayList<>();
        getJson("/api/v1/bi/catalogue").path("items").forEach(i -> reports.add(i.path("id").asText()));
        assertThat(reports).contains("file:report:market-risk-var").doesNotContain("file:report:trades-overview", "file:report:world-economics");
    }
}
