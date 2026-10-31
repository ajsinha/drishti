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
 * A server started with {@code drishti.edition=world} (Drishti World, config/editions/world.yaml; docs/admin/EDITIONS.md): the five
 * public-data packs alone (allow-only: another is refused with DRS-1017), the packs' own commands in FUNC and the type-ahead (VaR and
 * exposure words off), the BI datasets of the world-economy pack only, and the About answers and branding.
 */
@SpringBootTest(properties = {"drishti.edition=world", "drishti.packs.enabled=default", "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false",
        "drishti.bi.dir=../config/bi", "drishti.bi.query.store-dir=${drishti.test.dir}/world-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/world-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class EditionWorldServerTest {

    static final Set<String> WORLD_PACKS = Set.of("world-economy", "news-and-markets", "us-equities", "gleif", "funds");
    static final Set<String> WORLD_DATASETS = Set.of("world-economics-economies", "region-economics-regions");

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
        assertThat(about.path("edition").path("name").asText()).isEqualTo("world");
        assertThat(about.path("edition").path("title").asText()).isEqualTo("Drishti World");
        assertThat(about.path("product").asText()).isEqualTo("Drishti World");
        assertThat(about.path("tagline").asText()).isEqualTo("Economies, markets, companies and funds from public data");
        JsonNode pub = getJson("/public/about");
        assertThat(pub.path("edition").path("name").asText()).isEqualTo("world");
        assertThat(pub.path("lenses")).isEmpty();
    }

    @Test
    void onlyThePublicDataPacksAreLoadedAndOthersAreRefused() throws Exception {
        List<String> names = new ArrayList<>();
        getJson("/api/v1/packs").forEach(p -> names.add(p.path("name").asText()));
        assertThat(names).containsExactlyInAnyOrderElementsOf(WORLD_PACKS);
        MvcResult r = mvc.perform(post("/api/v1/admin/packs/market-risk/load")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("DRS-1017").contains("allow-only");
    }

    @Test
    void thePacksOwnCommandsAreOfferedAndNoOthers() throws Exception {
        List<String> codes = new ArrayList<>();
        getJson("/api/v1/functions").path("packs").forEach(p -> p.path("commands").forEach(c -> codes.add(c.path("code").asText())));
        assertThat(codes).contains("ECON", "MACRO", "FCST", "ECG", "REGE", "ELEC", "SOCI", "TOPIC", "NEWS", "CO", "QUOTE", "USEQ", "IDX", "SIC",
                "EDGAR", "FILING", "FACT", "PXH", "LEI", "LEIG", "LEIR", "LEIC", "LEIM", "FUND", "HELD", "TRACK")
                .doesNotContain("VRES", "TRD", "CPTY", "EXP", "FRTB");
        List<String> suggested = new ArrayList<>();
        getJson("/api/v1/command/suggest?q=ECO").forEach(s -> suggested.add(s.path("mnemonic").asText()));
        assertThat(suggested).contains("ECON");
        List<String> heads = new ArrayList<>();
        getJson("/api/v1/packs").forEach(p -> p.path("console").path("examples").forEach(e -> heads.add(
                (e.isArray() ? e.get(0).asText() : e.asText()).split("\\s+")[0])));
        assertThat(heads).doesNotContain("VAR", "VRES", "TRD", "EXP");
    }

    @Test
    void theWorldCommandsResolveAndARiskCommandIsNotOffered() throws Exception {
        MvcResult typed = mvc.perform(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"ECON CAN\"}")).andReturn();
        assertThat(typed.getResponse().getContentAsString()).doesNotContain("DRS-4023");   // shown (the test has no lake: no entity needed)
        MvcResult risk = mvc.perform(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"VRES DESK:RATES\"}")).andReturn();
        assertThat(risk.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void biOffersTheWorldEconomyDatasetsOnly() throws Exception {
        List<String> ids = new ArrayList<>();
        getJson("/api/v1/bi/datasets").path("datasets").forEach(d -> ids.add(d.path("id").asText()));
        assertThat(ids).isNotEmpty();
        assertThat(ids).allSatisfy(id -> assertThat(WORLD_DATASETS).contains(id.substring(id.lastIndexOf(':') + 1)));
        assertThat(ids).contains("file:dataset:world-economics-economies");
        assertThat(mvc.perform(get("/api/v1/bi/items/file:dataset:market-risk-var-latest")).andReturn().getResponse().getStatus()).isEqualTo(404);
        List<String> items = new ArrayList<>();
        getJson("/api/v1/bi/catalogue").path("items").forEach(i -> items.add(i.path("id").asText()));
        assertThat(items).contains("file:report:world-economics").doesNotContain("file:report:market-risk-var", "file:report:trades-overview");
    }
}
