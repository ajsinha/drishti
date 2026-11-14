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
 * {@code drishti.lenses=risk,world,market,trading} on a full instance, as the product owner's stack runs it (LENSES.md): the server offers
 * the four lenses; a person's lens and their chosen landing are kept by the real settings API ({@code /api/v1/me/settings}) and read back, as
 * the console needs them to arrange every page. A lens is a workspace: it never hides anything, so the lens the console sends
 * (X-Drishti-Lens) changes none of the commands, answers or BI datasets the server gives that person.
 */
@SpringBootTest(properties = {"drishti.lenses=risk,world,market,trading", "drishti.packs.enabled=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds",
        "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false", "drishti.var.official.enabled=false",
        "drishti.var.official.on-start=false", "drishti.ccr.exposure.official.enabled=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.bi.dir=../../config/bi", "drishti.tables.root=../../config/tables",
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
    void theLensAndTheLandingAreKeptByTheSettingsApiAndHideNothing() throws Exception {
        String ana = "ana-" + System.nanoTime();
        assertThat(settings(ana, "{\"lens\":\"world\",\"homeLanding\":\"explorer\"}").path("lens").asText()).isEqualTo("world");
        JsonNode back = ok(get("/api/v1/me/settings").header("Authorization", bearer(ana)));                 // read back, as the console does
        assertThat(back.path("lens").asText()).isEqualTo("world");
        assertThat(back.path("homeLanding").asText()).isEqualTo("explorer");
        List<String> everything = functions(ana, null);
        assertThat(everything).contains("VRES", "EXP", "TRD", "ECON", "USEQ", "FUND", "CRV");
        List<String> datasets = datasets(ana, null);
        for (String lens : List.of("world", "risk", "market", "trading")) {
            settings(ana, "{\"lens\":\"" + lens + "\"}");                                                   // switching is one patch
            assertThat(ok(get("/api/v1/me/settings").header("Authorization", bearer(ana))).path("lens").asText()).isEqualTo(lens);
            assertThat(functions(ana, lens)).as(lens).containsExactlyElementsOf(everything);
            assertThat(datasets(ana, lens)).as(lens).containsExactlyElementsOf(datasets);
            for (String typed : List.of("VRES DESK:RATES", "ECON CAN", "CRV CRV-USD-OIS", "SCN SCN-2008")) {
                assertThat(answer(ana, lens, typed)).as(lens + ": " + typed).doesNotContain("DRS-4023");
            }
        }
        assertThat(settings(ana, "{\"lens\":null}").path("lens").isNull()).isTrue();
    }

    List<String> datasets(String who, String lens) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/v1/bi/datasets").header("Authorization", bearer(who));
        if (lens != null) {
            req = req.header(EditionContext.HEADER, lens);
        }
        List<String> out = new ArrayList<>();
        ok(req).path("datasets").forEach(d -> out.add(d.path("id").asText()));
        return out;
    }

    String answer(String who, String lens, String text) throws Exception {
        return mvc.perform(post("/api/v1/command").header("Authorization", bearer(who)).header(EditionContext.HEADER, lens)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}")).andReturn().getResponse().getContentAsString();
    }
}
