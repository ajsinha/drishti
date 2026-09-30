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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"drishti.rachana.dirs=../sutras", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.sources.plugins.demo.settings.ticking=false"})
@AutoConfigureMockMvc
class SecurityTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String bearer(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    @Test
    void requestsWithoutAValidTokenAreRefused() throws Exception {
        mvc.perform(get("/api/v1/views/trade/IRS-48213")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DRS-5010"));
        String good = tokens.mint("x", List.of("risk"), 300);
        String[] parts = good.split("\\.");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"x\",\"roles\":[\"author\"],\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + parts[1] + ".";
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", "Bearer " + none)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", "Bearer " + tokens.mint("x", List.of("risk"), -120)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void entitlementsLimitKindsAndDisableLinksWithTheReason() throws Exception {
        String trader = bearer("tina", "trader");
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", trader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.panels[?(@.id=='refs')].data.links[?(@.text=='NS-NORTH-01')].status").value(hasItem("denied")))
                .andExpect(jsonPath("$.panels[?(@.id=='refs')].data.links[?(@.text=='NS-NORTH-01')].badge").value(hasItem("no access")))
                .andExpect(jsonPath("$.keys[?(@.key=='F7')].action").value(hasItem("denied")));
        mvc.perform(get("/api/v1/views/netting-set/NS-NORTH-01").header("Authorization", trader))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        mvc.perform(get("/api/v1/command/suggest").param("q", "NS-N").header("Authorization", trader))
                .andExpect(jsonPath("$[*].kind").value(not(hasItem("netting-set"))));
        mvc.perform(get("/api/v1/views/netting-set/NS-NORTH-01").header("Authorization", bearer("rita", "risk")))
                .andExpect(status().isOk());
    }

    @Test
    void rawJsonIsRedactedForRolesWithoutRaw() throws Exception {
        mvc.perform(get("/api/v1/entities/trade/FXS-20931/raw").header("Authorization", bearer("tina", "trader")))
                .andExpect(jsonPath("$.data.confirmation.trader").value("•••"));
        mvc.perform(get("/api/v1/entities/trade/FXS-20931/raw").header("Authorization", bearer("rita", "risk")))
                .andExpect(jsonPath("$.data.confirmation.trader").value("L. Moreau"));
    }
}
