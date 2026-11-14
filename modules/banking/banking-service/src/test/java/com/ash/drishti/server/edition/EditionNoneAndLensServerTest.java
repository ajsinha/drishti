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

import com.ash.drishti.packs.PackPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * No edition (drishti.edition blank, the default): the full Drishti exactly as before editions existed: the default pack list (the one in
 * config/packs/default-packs.yaml), every command, every BI dataset, no edition in About, no edition property layer. With
 * {@code drishti.lenses=risk} the same instance offers the risk lens: a request that names it (the console's X-Drishti-Lens header for a
 * person who opened it) sees the edition's commands and BI datasets; a request that does not sees everything. Packs stay loaded.
 */
@SpringBootTest(properties = {"drishti.lenses=risk", "drishti.packs.enabled=default", "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false",
        "drishti.var.official.enabled=false", "drishti.var.official.on-start=false", "drishti.ccr.exposure.official.enabled=false",
        "drishti.bi.dir=../../../config/bi", "drishti.tables.root=../../../config/tables", "drishti.bi.query.store-dir=${drishti.test.dir}/lens-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/lens-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class EditionNoneAndLensServerTest {

    @Autowired MockMvc mvc;
    @Autowired Environment env;
    final ObjectMapper json = new ObjectMapper();

    JsonNode getJson(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    List<String> functions(MockHttpServletRequestBuilder req) throws Exception {
        List<String> codes = new ArrayList<>();
        getJson(req).path("packs").forEach(p -> p.path("commands").forEach(c -> codes.add(c.path("code").asText())));
        return codes;
    }

    List<String> datasets(MockHttpServletRequestBuilder req) throws Exception {
        List<String> ids = new ArrayList<>();
        getJson(req).path("datasets").forEach(d -> ids.add(d.path("id").asText()));
        return ids;
    }

    @Test
    void withoutAnEditionTheServerIsTheFullDrishti() throws Exception {
        assertThat(env.getProperty("drishti.edition")).isBlank();
        assertThat(env.getProperty("drishti.edition-spec.title")).isNull();     // Drishti All: the distribution facts only, no filter
        JsonNode about = getJson(get("/api/v1/about"));
        assertThat(about.path("edition").isNull()).isTrue();
        assertThat(about.path("product").asText()).isEqualTo("Drishti");
        List<String> packs = new ArrayList<>();
        getJson(get("/api/v1/packs")).forEach(p -> packs.add(p.path("name").asText()));
        assertThat(packs).containsExactlyInAnyOrderElementsOf(Arrays.asList(PackPaths.DEFAULT_ENABLED.split(",")));
        assertThat(PackPaths.DEFAULT_ENABLED).isEqualTo("core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets");
        assertThat(functions(get("/api/v1/functions"))).contains("FRTB", "STR", "CVA", "VRES", "EXP");
        assertThat(datasets(get("/api/v1/bi/datasets"))).contains("file:table:banking.trades_basic_trade", "file:table:banking.market_risk_var_latest");
    }

    @Test
    void aLensHidesNothing() throws Exception {
        assertThat(getJson(get("/public/about")).path("lenses").get(0).asText()).isEqualTo("risk");
        List<String> everything = functions(get("/api/v1/functions"));
        assertThat(everything).contains("VRES", "EXP", "FRTB", "STR", "CVA");
        assertThat(functions(get("/api/v1/functions").header(EditionContext.HEADER, "risk"))).containsExactlyElementsOf(everything);
        assertThat(datasets(get("/api/v1/bi/datasets").header(EditionContext.HEADER, "risk")))
                .containsExactlyElementsOf(datasets(get("/api/v1/bi/datasets")));
        MvcResult shown = mvc.perform(get("/api/v1/views/frtb-sensitivity/FRTB-RATES-1").header(EditionContext.HEADER, "risk")).andReturn();
        assertThat(shown.getResponse().getContentAsString()).doesNotContain("DRS-4023");   // a workspace arranges; what exists is the distribution's
        List<String> packs = new ArrayList<>();
        getJson(get("/api/v1/packs").header(EditionContext.HEADER, "risk")).forEach(p -> packs.add(p.path("name").asText()));
        assertThat(packs).contains("world-economy", "news-and-markets");
    }
}
