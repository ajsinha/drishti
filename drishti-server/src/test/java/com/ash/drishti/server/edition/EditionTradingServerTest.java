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
 * Drishti Trading as a lens on a full instance (config/editions/trading.yaml; docs/admin/EDITIONS.md › The design as built, section 14), as the product
 * owner's stack runs it ({@code drishti.lenses=risk,world,market,trading}): the lens and the person's View as choice ({@code tradingView})
 * are kept and read back by the real settings API; through the lens the person's FUNC and type-ahead offer the trading, risk-on-my-book,
 * counterparty and market-data commands of the file, and ECON, SCN, COLL and FRTB answer DRS-4023, typed or opened; BI offers the
 * trading and VaR datasets only.
 */
@SpringBootTest(properties = {"drishti.lenses=risk,world,market,trading",
        "drishti.packs.enabled=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds",
        "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false", "drishti.var.official.enabled=false",
        "drishti.var.official.on-start=false", "drishti.ccr.exposure.official.enabled=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.bi.dir=../config/bi",
        "drishti.bi.query.store-dir=${drishti.test.dir}/trading-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/trading-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class EditionTradingServerTest {

    static final List<String> TRADING_COMMANDS = List.of("TRD", "CTRD", "PTRD", "LTRD", "ITRD", "BOOK", "DESK", "TRDR", "POS", "VLIM", "VWIF",
            "VRES", "TVAL", "PNLV", "HIER", "PORT", "CPTY", "GRP", "NSET", "NSMTM", "CPMTM", "LIM", "XWIF", "EXP", "CRV", "FX", "FXV", "EQ", "EQX",
            "CMD", "CMDC", "BND", "CDS");

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

    MvcResult typed(String who, String text) throws Exception {
        return mvc.perform(post("/api/v1/command").header("Authorization", bearer(who)).header(EditionContext.HEADER, "trading")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}")).andReturn();
    }

    @Test
    void theTradingLensIsOfferedAndKeptWithTheViewAsChoice() throws Exception {
        List<String> lenses = new ArrayList<>();
        ok(get("/public/about")).path("lenses").forEach(l -> lenses.add(l.asText()));
        assertThat(lenses).contains("trading");
        String dee = "dee-" + System.nanoTime();
        settings(dee, "{\"lens\":\"trading\",\"homeLanding\":\"desk\",\"tradingView\":\"desk:DESK-COMM\"}");
        JsonNode back = ok(get("/api/v1/me/settings").header("Authorization", bearer(dee)));                // read back, as the console does
        assertThat(back.path("lens").asText()).isEqualTo("trading");
        assertThat(back.path("homeLanding").asText()).isEqualTo("desk");
        assertThat(back.path("tradingView").asText()).isEqualTo("desk:DESK-COMM");
        MvcResult bad = mvc.perform(patch("/api/v1/me/settings").header("Authorization", bearer(dee)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tradingView\":\"book:BOOK-1' or 1=1\"}")).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(settings(dee, "{\"tradingView\":null}").path("tradingView").isNull()).isTrue();
    }

    @Test
    void theLensShowsItsCommandsAndHidesTheRestWithDrs4023() throws Exception {
        String eve = "eve-" + System.nanoTime();
        String lens = settings(eve, "{\"lens\":\"trading\"}").path("lens").asText();
        assertThat(lens).isEqualTo("trading");
        List<String> codes = new ArrayList<>();
        ok(get("/api/v1/functions").header("Authorization", bearer(eve)).header(EditionContext.HEADER, lens)).path("packs")
                .forEach(p -> p.path("commands").forEach(c -> codes.add(c.path("code").asText())));
        assertThat(codes).containsAll(TRADING_COMMANDS)
                .doesNotContain("ECON", "SCN", "STR", "SSPNL", "COLL", "MC", "FRTB", "SACCR", "CVA", "SIMM", "XRUN", "USEQ", "FUND", "IRV");
        List<String> suggested = new ArrayList<>();
        ok(get("/api/v1/command/suggest?q=CO").header("Authorization", bearer(eve)).header(EditionContext.HEADER, lens))
                .forEach(s -> suggested.add(s.path("mnemonic").asText()));
        assertThat(suggested).doesNotContain("COLL");
        for (String hidden : List.of("ECON CAN", "SCN SCN-2008", "COLL COLL-1", "FRTB FRTB-RATES-1")) {
            MvcResult r = typed(eve, hidden);
            assertThat(r.getResponse().getStatus()).as(hidden).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).as(hidden).contains("DRS-4023");
        }
        for (String shown : List.of("TRD MX-20000001", "BOOK BOOK-RATES-1", "CPTY CP-ALDERSHOT", "CRV CRV-USD-OIS")) {
            assertThat(typed(eve, shown).getResponse().getContentAsString()).as(shown).doesNotContain("DRS-4023");   // shown (no lake here)
        }
        MvcResult page = mvc.perform(get("/api/v1/views/frtb-sensitivity/FRTB-RATES-1").header("Authorization", bearer(eve))
                .header(EditionContext.HEADER, lens)).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(404);
        assertThat(page.getResponse().getContentAsString()).contains("DRS-4023");
    }

    @Test
    void biThroughTheLensOffersTheTradingAndVarDatasetsOnly() throws Exception {
        String fay = "fay-" + System.nanoTime();
        settings(fay, "{\"lens\":\"trading\"}");
        List<String> ids = new ArrayList<>();
        ok(get("/api/v1/bi/datasets").header("Authorization", bearer(fay)).header(EditionContext.HEADER, "trading")).path("datasets")
                .forEach(d -> ids.add(d.path("id").asText()));
        assertThat(ids).contains("file:dataset:market-risk-var-latest", "file:dataset:trading-risk-trades")
                .doesNotContain("file:dataset:counterparty-exposure-counterparties", "file:dataset:live-exposure",
                        "file:dataset:world-economics-economies");
        assertThat(ids).allSatisfy(id -> assertThat(id.substring(id.lastIndexOf(':') + 1)).matches(
                "trading-.*|trades-basic-trade|live-trading.*|market-risk-var-.*"));
    }
}
