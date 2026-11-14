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
 * Drishti Trading as a lens on a full instance (config/lenses/banking/trading.yaml; docs/guides/ADMIN_GUIDE.md › The design), as the product
 * owner's stack runs it ({@code drishti.lenses=risk,world,market,trading}): the lens and the person's View as choice ({@code tradingView})
 * are kept and read back by the real settings API. The lens arranges the person's Home and menus and hides nothing
 * (EditionTwoLensesServerTest).
 */
@SpringBootTest(properties = {"drishti.lenses=risk,world,market,trading",
        "drishti.packs.enabled=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds",
        "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false", "drishti.var.official.enabled=false",
        "drishti.var.official.on-start=false", "drishti.ccr.exposure.official.enabled=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.bi.dir=../../../config/bi", "drishti.tables.root=../../../config/tables",
        "drishti.bi.query.store-dir=${drishti.test.dir}/trading-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/trading-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class EditionTradingServerTest {

    static final List<String> TRADING_COMMANDS = List.of("TRD", "CTRD", "PTRD", "LTRD", "ITRD", "BOOK", "DESK", "TRDR", "POS", "VLIM",
            "VRES", "TVAL", "PNLV", "PORT", "DPNL", "PNL", "SENS", "CPTY", "GRP", "NSET", "NSMTM", "CPMTM", "LIM", "EXP", "CRV",
            "REPO", "FIX", "IRV", "CPV", "FX", "FXF", "FXV", "EQ", "EQX", "CMD", "CMDC", "BND", "CDS");

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
}
