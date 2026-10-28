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
package com.ash.drishti.server.switches;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.SwitchLedger;
import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Functions (FUNC, docs/admin/USER_MANAGEMENT.md): every signed-in user reads the commands they can use (only those that are on, of packs active
 * for them, of kinds their roles open), with nothing of the switches; only an administrator may read or change the switches.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.trades-only.kinds[0]=trade",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class FunctionsApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired SwitchLedger ledger;

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
    }

    private String functions(String role) throws Exception {
        return mvc.perform(get("/api/v1/functions").header("Authorization", as(role))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    @AfterEach
    void back() {
        if (!ledger.on(SwitchLedger.COMMAND, "TRD")) {
            ledger.set(List.of(new SwitchLedger.Change(SwitchLedger.COMMAND, "TRD", true)), "", "test");
        }
    }

    @Test
    void aUserSeesOnlyTheCommandsTheyCanUseAndNothingOfTheSwitches() throws Exception {
        String mine = functions("trades-only");
        assertThat(mine).contains("\"code\":\"TRD\"", "\"kind\":\"trade\"", "\"example\":").doesNotContain("\"kind\":\"netting-set\"")
                .doesNotContain("\"on\":", "\"reason\":", "\"nodes\":", "\"actor\":");
        assertThat(functions("admin")).contains("\"kind\":\"trade\"", "\"kind\":\"netting-set\"");

        ledger.set(List.of(new SwitchLedger.Change(SwitchLedger.COMMAND, "TRD", false)), "audit asked", "test");
        assertThat(functions("trades-only")).doesNotContain("\"code\":\"TRD\"");
        assertThat(functions("admin")).as("an administrator sees off commands on the admin listing, not here").doesNotContain("\"code\":\"TRD\"");
    }

    @Test
    void onlyAnAdministratorReadsOrChangesTheSwitches() throws Exception {
        mvc.perform(get("/api/v1/admin/commands").header("Authorization", as("trades-only"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/commands").header("Authorization", as("trades-only")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\",\"changes\":[{\"scope\":\"command\",\"name\":\"TRD\",\"on\":false}]}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("not an administrator")));
        assertThat(ledger.on(SwitchLedger.COMMAND, "TRD")).isTrue();
        mvc.perform(get("/api/v1/admin/commands").header("Authorization", as("admin"))).andExpect(status().isOk());
    }
}
