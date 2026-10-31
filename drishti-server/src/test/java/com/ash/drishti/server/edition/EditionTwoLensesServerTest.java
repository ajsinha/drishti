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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.security.TokenVerifier;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code drishti.lenses=risk,world,market,trading} on a full instance, as the product owner's stack runs it (EDITIONS.md › The design as built, section 8): the server
 * offers the four lenses; a person's lens and their chosen landing are kept by the real settings API ({@code /api/v1/me/settings}) and read back, as
 * the console needs them to show the lens on every page (the 2.9.6 fix); the lens the console then sends (X-Drishti-Lens) focuses that
 * person's commands and BI on Drishti Risk, Drishti World, Drishti Market or Drishti Trading, a command of another answers DRS-4023, and
 * switching lenses is one patch (EditionTradingServerTest: the Trading lens in full).
 */
@SpringBootTest(properties = {"drishti.lenses=risk,world,market,trading", "drishti.packs.enabled=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds",
        "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false", "drishti.var.official.enabled=false",
        "drishti.var.official.on-start=false", "drishti.ccr.exposure.official.enabled=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.bi.dir=../config/bi",
        "drishti.bi.query.store-dir=${drishti.test.dir}/lenses-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/lenses-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class EditionTwoLensesServerTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    String bearer(String user) {
        return "Bearer " + tokens.mint(user, List.of("viewer"), 60);
    }

    JsonNode ok(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    JsonNode settings(String who, String body) throws Exception {
        return ok(patch("/api/v1/me/settings").header("Authorization", bearer(who)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    List<String> functions(String who, String lens) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/v1/functions").header("Authorization", bearer(who));
        if (lens != null) {
            req = req.header(EditionContext.HEADER, lens);
        }
        List<String> codes = new ArrayList<>();
        ok(req).path("packs").forEach(p -> p.path("commands").forEach(c -> codes.add(c.path("code").asText())));
        return codes;
    }

    @Test
    void theFourLensesAreOffered() throws Exception {
        List<String> lenses = new ArrayList<>();
        ok(get("/public/about")).path("lenses").forEach(l -> lenses.add(l.asText()));
        assertThat(lenses).containsExactly("market", "risk", "trading", "world");
    }

    @Test
    void theLensAndTheLandingAreKeptByTheSettingsApiAndFocusThatPersonOnly() throws Exception {
        String ana = "ana-" + System.nanoTime();
        assertThat(settings(ana, "{\"lens\":\"world\",\"homeLanding\":\"explorer\"}").path("lens").asText()).isEqualTo("world");
        JsonNode back = ok(get("/api/v1/me/settings").header("Authorization", bearer(ana)));                 // read back, as the console does
        assertThat(back.path("lens").asText()).isEqualTo("world");
        assertThat(back.path("homeLanding").asText()).isEqualTo("explorer");
        String lens = back.path("lens").asText();
        assertThat(functions(ana, lens)).contains("ECON", "USEQ", "LEI", "FUND", "QUOTE").doesNotContain("VRES", "EXP", "TRD", "FRTB");
        MvcResult hidden = mvc.perform(post("/api/v1/command").header("Authorization", bearer(ana)).header(EditionContext.HEADER, lens)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"VRES DESK:RATES\"}")).andReturn();
        assertThat(hidden.getResponse().getContentAsString()).contains("DRS-4023");
        List<String> datasets = new ArrayList<>();
        ok(get("/api/v1/bi/datasets").header("Authorization", bearer(ana)).header(EditionContext.HEADER, lens)).path("datasets")
                .forEach(d -> datasets.add(d.path("id").asText()));
        assertThat(datasets).contains("file:dataset:world-economics-economies").doesNotContain("file:dataset:market-risk-var-latest");

        // switching to Drishti Risk is one patch; the landing chosen stays until the person picks another
        settings(ana, "{\"lens\":\"risk\"}");
        back = ok(get("/api/v1/me/settings").header("Authorization", bearer(ana)));
        assertThat(back.path("lens").asText()).isEqualTo("risk");
        assertThat(back.path("homeLanding").asText()).isEqualTo("explorer");
        assertThat(functions(ana, "risk")).contains("VRES", "EXP").doesNotContain("ECON", "USEQ", "FUND");
        MvcResult econ = mvc.perform(post("/api/v1/command").header("Authorization", bearer(ana)).header(EditionContext.HEADER, "risk")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"ECON CAN\"}")).andReturn();
        assertThat(econ.getResponse().getContentAsString()).contains("DRS-4023");

        // someone else, at the same time, sees the full product; leaving clears it
        assertThat(ok(get("/api/v1/me/settings").header("Authorization", bearer("bo-" + System.nanoTime()))).path("lens").isNull()).isTrue();
        assertThat(functions("bo", null)).contains("VRES", "ECON", "FRTB");
        assertThat(settings(ana, "{\"lens\":null}").path("lens").isNull()).isTrue();
    }

    int status(String who, String lens, String text) throws Exception {
        return mvc.perform(post("/api/v1/command").header("Authorization", bearer(who)).header(EditionContext.HEADER, lens)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}")).andReturn().getResponse().getStatus();
    }

    String answer(String who, String lens, String text) throws Exception {
        return mvc.perform(post("/api/v1/command").header("Authorization", bearer(who)).header(EditionContext.HEADER, lens)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}")).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theMarketLensShowsTheMarketDataCommandsOnlyAndSwitchesWithTheOthers() throws Exception {
        String cy = "cy-" + System.nanoTime();
        assertThat(settings(cy, "{\"lens\":\"market\",\"homeLanding\":\"quality\"}").path("lens").asText()).isEqualTo("market");
        JsonNode back = ok(get("/api/v1/me/settings").header("Authorization", bearer(cy)));                  // read back, as the console does
        assertThat(back.path("lens").asText()).isEqualTo("market");
        assertThat(back.path("homeLanding").asText()).isEqualTo("quality");
        assertThat(functions(cy, "market")).contains("CRV", "REPO", "FX", "FXF", "FXV", "IRV", "CPV", "EQ", "EQX", "DIV", "EQV", "CDS", "INF",
                "INFC", "CMD", "CMDC", "CMDV", "FIX", "BND", "CORR", "CORRC", "EQB")
                .doesNotContain("VRES", "EXP", "TRD", "CPTY", "ECON", "USEQ", "FUND", "FRTB");
        for (String hidden : List.of("VRES DESK:RATES", "ECON CAN", "TRD MX-20000001", "FUND SPY")) {
            assertThat(answer(cy, "market", hidden)).as(hidden).contains("DRS-4023");
            assertThat(status(cy, "market", hidden)).as(hidden).isEqualTo(404);
        }
        assertThat(answer(cy, "market", "CRV CRV-USD-OIS")).doesNotContain("DRS-4023");                    // shown (no lake here)
        List<String> datasets = new ArrayList<>();
        ok(get("/api/v1/bi/datasets").header("Authorization", bearer(cy)).header(EditionContext.HEADER, "market")).path("datasets")
                .forEach(d -> datasets.add(d.path("id").asText()));
        assertThat(datasets).doesNotContain("file:dataset:market-risk-var-latest", "file:dataset:world-economics-economies");

        // switching: to Drishti World, then Drishti Risk, each one patch; the market data is hidden in both
        settings(cy, "{\"lens\":\"world\"}");
        assertThat(ok(get("/api/v1/me/settings").header("Authorization", bearer(cy))).path("lens").asText()).isEqualTo("world");
        assertThat(functions(cy, "world")).contains("ECON").doesNotContain("CRV", "FXV", "IRV");
        assertThat(answer(cy, "world", "CRV CRV-USD-OIS")).contains("DRS-4023");
        settings(cy, "{\"lens\":\"risk\"}");
        assertThat(functions(cy, "risk")).contains("VRES", "FX").doesNotContain("CRV", "IRV", "ECON");
        settings(cy, "{\"lens\":\"trading\"}");                                                              // the fourth lens
        assertThat(ok(get("/api/v1/me/settings").header("Authorization", bearer(cy))).path("lens").asText()).isEqualTo("trading");
        assertThat(functions(cy, "trading")).contains("TRD", "VWIF", "XWIF", "CRV", "TVAL").doesNotContain("ECON", "SCN", "COLL", "FRTB", "IRV");
        assertThat(answer(cy, "trading", "SCN SCN-2008")).contains("DRS-4023");
        settings(cy, "{\"lens\":\"market\"}");
        assertThat(ok(get("/api/v1/me/settings").header("Authorization", bearer(cy))).path("lens").asText()).isEqualTo("market");
        assertThat(settings(cy, "{\"lens\":null}").path("lens").isNull()).isTrue();
    }
}
