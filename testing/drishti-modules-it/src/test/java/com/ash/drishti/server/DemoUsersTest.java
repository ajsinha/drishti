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
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * The demo profile (docs/guides/ADMIN_GUIDE.md, "Three demo users"): the development administrator and three demo users are made on the
 * first start with their roles and packs, each user opens only its packs' kinds, and the default-password warning names them all.
 */
@SpringBootTest(properties = {"drishti.profile=demo", "drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.identity.iterations=1000",
        "drishti.packs.enabled=default",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/demousers-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class DemoUsersTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    @Test
    void theDemoProfileSeedsTheAdministratorAndThreeUsersWithTheirRolesAndPacks() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        mvc.perform(get("/api/v1/admin/users").header("Authorization", admin))
                .andExpect(jsonPath("$[*].username").value(hasItems("drishti-dev-admin", "drishti-dev-risk", "drishti-dev-genomics", "drishti-dev-market")));
        mvc.perform(get("/api/v1/admin/users/drishti-dev-risk").header("Authorization", admin))
                .andExpect(jsonPath("$.roles").value(hasItem("risk-trading")))
                .andExpect(jsonPath("$.packs").value(hasItems("core", "trading", "market-data", "market-risk", "counterparty-risk")));
        mvc.perform(get("/api/v1/admin/users/drishti-dev-genomics").header("Authorization", admin))
                .andExpect(jsonPath("$.roles").value(hasItem("genomics-reader")))
                .andExpect(jsonPath("$.packs").value(hasItems("genomics-showcase", "protein-engineering")));
        mvc.perform(get("/api/v1/admin/users/drishti-dev-market").header("Authorization", admin))
                .andExpect(jsonPath("$.roles").value(hasItem("market-data-reader")))
                .andExpect(jsonPath("$.packs.length()").value(1));
        mvc.perform(get("/api/v1/admin/role-definitions/market-data-reader").header("Authorization", admin))
                .andExpect(jsonPath("$.kinds").value(hasItem("fx-spot"))).andExpect(jsonPath("$.kinds").value(not(hasItem("trade"))));
        mvc.perform(get("/api/v1/admin/role-definitions/risk-trading").header("Authorization", admin))
                .andExpect(jsonPath("$.kinds").value(hasItems("trade", "fx-spot")));
        mvc.perform(get("/api/v1/admin/status").header("Authorization", admin))
                .andExpect(jsonPath("$.defaultAdminPasswordInUse").value(true))
                .andExpect(jsonPath("$.defaultPasswordUsers").value(hasItems("drishti-dev-admin", "drishti-dev-risk", "drishti-dev-genomics", "drishti-dev-market")));
    }

    @Test
    void eachDemoUserOpensOnlyItsKinds() throws Exception {
        String market = as("drishti-dev-market", "market-data-reader");
        mvc.perform(get("/api/v1/views/trade/T-1").header("Authorization", market)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/views/gene/HLXK7").header("Authorization", market)).andExpect(status().isForbidden());
        String genomics = as("drishti-dev-genomics", "genomics-reader");
        mvc.perform(get("/api/v1/views/trade/T-1").header("Authorization", genomics)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/views/fx-spot/EURUSD").header("Authorization", genomics)).andExpect(status().isForbidden());
        String risk = as("drishti-dev-risk", "risk-trading");
        mvc.perform(get("/api/v1/views/gene/HLXK7").header("Authorization", risk)).andExpect(status().isForbidden());
        MvcResult trade = mvc.perform(get("/api/v1/views/trade/T-1").header("Authorization", risk)).andReturn();
        org.junit.jupiter.api.Assertions.assertNotEquals(403, trade.getResponse().getStatus(), "drishti-dev-risk opens trades");
    }
}
