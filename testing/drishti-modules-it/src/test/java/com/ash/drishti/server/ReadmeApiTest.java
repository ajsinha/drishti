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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
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

/** The README standard over the API: a pack's README for its users (kinds they may not open left out) and a connector type's for admins. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.trades-only.kinds[0]=trade", "drishti.security.roles.nothing.kinds[0]=curve"})
@AutoConfigureMockMvc
class ReadmeApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
    }

    @Test
    void aPackReadmeIsItsFrontMatterAndMarkdownWithoutTheKindsTheCallerMayNotOpen() throws Exception {
        mvc.perform(get("/api/v1/packs/TRDS/readme").header("Authorization", as("trades-only"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pack").value("trading"))
                .andExpect(jsonPath("$.meta.pack").value("trading"))
                .andExpect(jsonPath("$.meta.kinds").value(hasItem("trade")))
                .andExpect(jsonPath("$.meta.kinds").value(not(hasItem("counterparty-trades"))))
                .andExpect(jsonPath("$.meta.commands.TRD").value("trade"))
                .andExpect(jsonPath("$.markdown").value(containsString("## Data structures")))
                .andExpect(jsonPath("$.markdown").value(containsString("### `trade`")))
                .andExpect(jsonPath("$.markdown").value(containsString("`LEGS[].CASH_FLOWS[]`")))
                .andExpect(jsonPath("$.markdown").value(not(containsString("### `counterparty-trades`"))))
                .andExpect(jsonPath("$.markdown").value(not(containsString("PROPRIETARY AND CONFIDENTIAL"))))   // the licence comment is not content
                .andExpect(jsonPath("$.markdown").value(containsString("## See also")));
        mvc.perform(get("/api/v1/packs/trading/readme").header("Authorization", as("nothing"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value(not(containsString("### `trade`"))));
        mvc.perform(get("/api/v1/packs/world-economy/readme").header("Authorization", as("trades-only"))).andExpect(status().isBadRequest());  // not switched on
    }

    @Test
    void aConnectorTypeReadmeIsForAdministrators() throws Exception {
        mvc.perform(get("/api/v1/admin/connectors/types/cassandra/readme").header("Authorization", as("admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.connector").value("cassandra"))
                .andExpect(jsonPath("$.meta.category").value("plugin"))
                .andExpect(jsonPath("$.meta.settings").value(hasItem("contact-points")))
                .andExpect(jsonPath("$.markdown").value(containsString("## Data structure")))
                .andExpect(jsonPath("$.markdown").value(containsString("CREATE TABLE")));
        mvc.perform(get("/api/v1/admin/connectors/types/derived/readme").header("Authorization", as("admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.category").value("built-in"));
        mvc.perform(get("/api/v1/admin/connectors/types/no-such-type/readme").header("Authorization", as("admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/connectors/types/cassandra/readme").header("Authorization", as("trades-only"))).andExpect(status().isForbidden());
    }
}
