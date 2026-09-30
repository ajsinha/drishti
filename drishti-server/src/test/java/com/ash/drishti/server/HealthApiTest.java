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

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The health page's data: connectors with their traffic, packs with their Sutras and connectors, live figures; admins only. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class HealthApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 60);
    }

    @Test
    void reportsConnectorsPacksAndLiveForAdminsOnly() throws Exception {
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", as("admin"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/views/trade/NOPE-1").header("Authorization", as("admin")));
        mvc.perform(get("/api/v1/admin/health").header("Authorization", as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.sources[?(@.name=='demo')].status").value(hasItem("UP")))
                .andExpect(jsonPath("$.sources[?(@.name=='demo')].reads.found").value(hasItem(greaterThan(0))))
                .andExpect(jsonPath("$.sources[?(@.name=='demo')].reads.notHeld").value(hasItem(greaterThan(0))))
                .andExpect(jsonPath("$.sources[?(@.name=='demo')].reads.p99Ms").exists())
                .andExpect(jsonPath("$.packs[?(@.name=='finance')].sutras").value(hasItem(greaterThan(0))))
                .andExpect(jsonPath("$.packs[?(@.name=='finance')].connectors[*]").value(hasItem("finance-lake")))
                // the test machine has no finance lake: its connector is down, so the pack and the whole are degraded
                .andExpect(jsonPath("$.packs[?(@.name=='finance')].status").value(hasItem("DEGRADED")))
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.server.heapMaxMb").value(greaterThan(0)))
                .andExpect(jsonPath("$.live.streams").exists());
        mvc.perform(get("/api/v1/admin/health").header("Authorization", as("trader"))).andExpect(status().isForbidden());
    }
}
