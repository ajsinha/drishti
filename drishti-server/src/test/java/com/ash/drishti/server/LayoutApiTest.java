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
package com.ash.drishti.server;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Personal layouts: who may keep one (the {@code layout} power, on for every role but viewer), validation against the
 * Sutra's panels, each user's own, the Sutra's own sizes on the view, and promoting a layout to the next version of the
 * Sutra through review.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.rachana.studio-save=true", "drishti.rachana.dirs=${java.io.tmpdir}/drishti-layout-${random.uuid}",
        "drishti.governance.dir=${java.io.tmpdir}/drishti-layout-props-${random.uuid}",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.desk.kinds[0]=trade", "drishti.security.roles.curves.kinds[0]=curve",
        "drishti.identity.database-url=jdbc:sqlite:target/layouts-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class LayoutApiTest {

    static final String LAYOUT = """
            {"panels":[
              {"id":"cashflows","area":"main","span":8,"height":10},
              {"id":"legs","area":"main"},
              {"id":"refs","area":"main","span":4},
              {"id":"built","hidden":true}]}""";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private org.springframework.test.web.servlet.ResultActions save(String who, String role, String sutra, String kind, String body) throws Exception {
        return mvc.perform(put("/api/v1/me/layouts/{s}/{k}", sutra, kind).header("Authorization", as(who, role))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void everyRoleButViewerMayKeepALayout() throws Exception {
        mvc.perform(get("/api/v1/me/layouts").header("Authorization", as("vera", "viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false)).andExpect(jsonPath("$.layouts.length()").value(0));
        save("vera", "viewer", "irs-vanilla", "trade", LAYOUT).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("role with layout")));
        for (String role : new String[] {"desk", "author", "admin", "trader"}) {
            mvc.perform(get("/api/v1/me/layouts").header("Authorization", as("u", role))).andExpect(jsonPath("$.allowed").value(true));
        }
        mvc.perform(get("/api/v1/me/layouts").header("Authorization", as("d", "desk"))).andExpect(jsonPath("$.promote").value(false));
        mvc.perform(get("/api/v1/me/layouts").header("Authorization", as("a", "author"))).andExpect(jsonPath("$.promote").value(true));
        mvc.perform(get("/api/v1/admin/role-definitions").header("Authorization", as("root", "admin")))
                .andExpect(jsonPath("$[?(@.name == 'viewer')].layout").value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.name == 'author')].layout").value(hasItem(true)));
    }

    @Test
    void aLayoutIsTheCallersOwnAndReadAgainstTheSutraAsItIsNow() throws Exception {
        save("dana", "desk", "irs-vanilla", "trade", LAYOUT).andExpect(status().isOk())
                .andExpect(jsonPath("$.panels[*].id").value(contains("cashflows", "legs", "refs", "built", "leg2", "curve", "dv01")))
                .andExpect(jsonPath("$.panels[0].span").value(8)).andExpect(jsonPath("$.panels[0].height").value(10))
                .andExpect(jsonPath("$.panels[2].area").value("main")).andExpect(jsonPath("$.panels[3].hidden").value(true))
                .andExpect(jsonPath("$.panels[4].added").value(true)).andExpect(jsonPath("$.panels[5].area").value("right"));
        mvc.perform(get("/api/v1/me/layouts/irs-vanilla/trade").header("Authorization", as("dana", "desk")))
                .andExpect(jsonPath("$.sutraVersion").value(3)).andExpect(jsonPath("$.panels[1].id").value("legs"));
        mvc.perform(get("/api/v1/me/layouts").header("Authorization", as("dana", "desk")))
                .andExpect(jsonPath("$.layouts[0].sutra").value("irs-vanilla")).andExpect(jsonPath("$.layouts[0].kind").value("trade"));
        mvc.perform(get("/api/v1/me/layouts/irs-vanilla/trade").header("Authorization", as("omar", "desk"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/me/layouts/irs-vanilla/trade").header("Authorization", as("omar", "desk"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/me/layouts/irs-vanilla/trade").header("Authorization", as("dana", "desk"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/layouts/irs-vanilla/trade").header("Authorization", as("dana", "desk"))).andExpect(status().isNotFound());
    }

    @Test
    void onlyThePanelsTheSutraHasInSizesTheGridAllows() throws Exception {
        String[] bad = {
            "{\"panels\":[{\"id\":\"ghost\"}]}",
            "{\"panels\":[]}",
            "{\"panels\":[{\"id\":\"legs\"},{\"id\":\"legs\"}]}",
            "{\"panels\":[{\"id\":\"legs\",\"area\":\"left\"}]}",
            "{\"panels\":[{\"id\":\"legs\",\"span\":0}]}",
            "{\"panels\":[{\"id\":\"legs\",\"span\":13}]}",
            "{\"panels\":[{\"id\":\"legs\",\"height\":25}]}",
            "{\"panels\":[{\"id\":\"legs\",\"span\":\"wide\"}]}",
            "{\"panels\":[{\"id\":\"legs\",\"hidden\":\"yes\"}]}"};
        for (String b : bad) {
            save("dana", "desk", "irs-vanilla", "trade", b).andExpect(status().isBadRequest());
        }
        save("dana", "desk", "irs-vanilla", "curve", LAYOUT).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("lays out trade entities")));
        save("dana", "desk", "no-such-sutra", "trade", LAYOUT).andExpect(status().isNotFound());
        save("cleo", "curves", "irs-vanilla", "trade", LAYOUT).andExpect(status().isForbidden());     // may not open trades
        save("dana", "desk", "irs-vanilla", "trade", "{\"panels\":[{\"id\":\"legs\",\"span\":12}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.panels[0].span").doesNotExist());                               // 12 is the whole column
    }

    @Test
    void theViewCarriesTheSutrasNameAndSizes() throws Exception {
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", as("dana", "desk")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provenance.sutra").value("irs-vanilla"));
    }

    @Test
    void anAuthorPromotesTheirLayoutToTheNextVersionThroughReview() throws Exception {
        save("dana", "desk", "irs-vanilla", "trade", LAYOUT).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/layouts/irs-vanilla/trade/promotion").header("Authorization", as("dana", "desk")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(containsString("role with author")));
        save("ana", "author", "irs-vanilla", "trade", LAYOUT).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/layouts/irs-vanilla/trade/promotion").header("Authorization", as("ana", "author")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fromVersion").value(3)).andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.base").value(containsString("version: 3")))
                .andExpect(jsonPath("$.text").value(containsString("version: 4")))
                .andExpect(jsonPath("$.text").value(containsString("span: 8")))
                .andExpect(jsonPath("$.text").value(containsString("{ id: built, kind: provenance, title: How this view was built }")))
                .andExpect(jsonPath("$.changes").value(hasItem(containsString("'refs' moves to the main column"))));
        mvc.perform(get("/api/v1/me/layouts/irs-vanilla/trade/promotion").param("dropHidden", "true").header("Authorization", as("ana", "author")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.not(containsString("id: built"))));
        String made = mvc.perform(post("/api/v1/me/layouts/irs-vanilla/trade/promotion").header("Authorization", as("ana", "author"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"cashflows first\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.proposal.version").value(4))
                .andExpect(jsonPath("$.proposal.status").value("pending")).andReturn().getResponse().getContentAsString();
        String id = json.readTree(made).path("proposal").path("id").asText();
        mvc.perform(get("/api/v1/sutras/proposals/" + id).header("Authorization", as("rui", "approver")))
                .andExpect(jsonPath("$.note").value("cashflows first")).andExpect(jsonPath("$.newVersion").value(true))
                .andExpect(jsonPath("$.previousText").value(containsString("version: 3")));
        // the Sutra itself is unchanged until an approver says so
        mvc.perform(get("/api/v1/sutras/irs-vanilla/4").header("Authorization", as("ana", "author"))).andExpect(status().isNotFound());
    }
}
