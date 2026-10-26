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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.SwitchLedger;
import com.ash.drishti.server.security.PackAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Admin → Commands (docs/admin/COMMANDS.md) on one server: a command turned off answers "not active" ({@code 403 DRS-1016}) by its page,
 * the typed command, search and the API, leaves the type-ahead and the pack catalogue, and shows as "not active" where another page links
 * to it; turned on again, all of it is back. Packs switch through the same ledger, with the reason kept.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class CommandSwitchesApiTest {

    @Autowired MockMvc mvc;
    @Autowired SwitchLedger ledger;
    @Autowired PackAccess packs;

    private ResultActions as(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("X-Drishti-User", "cmd-admin"));
    }

    private ResultActions change(String scope, String name, boolean on, String reason) throws Exception {
        return as(post("/api/v1/admin/commands").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\",\"changes\":[{\"scope\":\"" + scope + "\",\"name\":\"" + name + "\",\"on\":" + on + "}]}"));
    }

    @AfterEach
    void back() throws Exception {
        if (!ledger.on(SwitchLedger.COMMAND, "TRD")) {
            change("command", "TRD", true, "");
        }
    }

    @Test
    void aCommandTurnedOffIsNotActiveEverywhereAndComesBackWhenTurnedOn() throws Exception {
        as(get("/api/v1/views/trade/IRS-48213")).andExpect(status().isOk());
        String list = as(get("/api/v1/admin/commands")).andExpect(status().isOk())
                .andExpect(jsonPath("$.packs[?(@.commands[?(@.code == 'TRD')])]").isNotEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("\"code\":\"TRD\"", "\"kind\":\"trade\"", "\"nodes\"");

        change("command", "TRD", false, "audit asked").andExpect(status().isOk()).andExpect(jsonPath("$.off").value(1));

        as(get("/api/v1/views/trade/IRS-48213")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-1016"))
                .andExpect(jsonPath("$.command").value("TRD")).andExpect(jsonPath("$.detail").value("DRS-1016 TRD is not active on this server"));
        as(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"TRD IRS-48213\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-1016"));
        as(get("/api/v1/search").param("q", "TRD")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-1016"));
        String suggest = as(get("/api/v1/command/suggest").param("q", "IRS-48")).andReturn().getResponse().getContentAsString();
        assertThat(suggest).doesNotContain("\"kind\":\"trade\"");
        String catalogue = as(get("/api/v1/packs")).andReturn().getResponse().getContentAsString();
        assertThat(catalogue).doesNotContain("[\"TRD ");
        String nettingSet = as(get("/api/v1/views/netting-set/NS-NORTH-01")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(nettingSet).as("its member trades are not links any more, and say so").contains("inactive")
                .doesNotContainPattern("\"link\":\\{\"kind\":\"trade\"");
        as(get("/api/v1/packs")).andExpect(jsonPath("$[*].kinds[?(@ == 'trade')]").isEmpty());
        assertThat(packs.kindAllowed("anyone", "trade")).isFalse();
        assertThat(as(get("/api/v1/admin/commands/history").param("scope", "command").param("name", "TRD")).andReturn().getResponse()
                .getContentAsString()).contains("audit asked", "cmd-admin");
        as(get("/api/v1/admin/commands/TRD/impact")).andExpect(status().isOk()).andExpect(jsonPath("$.page").value("/v/trade/…"));

        change("command", "TRD", true, "").andExpect(jsonPath("$.off").value(0));
        as(get("/api/v1/views/trade/IRS-48213")).andExpect(status().isOk());
    }

    @Test
    void anUnknownCommandOrScopeIsRefusedAndNothingChanges() throws Exception {
        change("command", "NOPE", false, "").andExpect(status().isBadRequest());
        change("thing", "TRD", false, "").andExpect(status().isBadRequest());
        as(post("/api/v1/admin/commands").contentType(MediaType.APPLICATION_JSON).content("{\"changes\":[]}")).andExpect(status().isBadRequest());
        assertThat(ledger.on(SwitchLedger.COMMAND, "TRD")).isTrue();
    }

    @Test
    void aPackSwitchGoesThroughTheSameLedgerWithItsReason() throws Exception {
        String pack = packs.ownerOf("trade");
        org.junit.jupiter.api.Assumptions.assumeTrue(pack != null && packs.requiredBy(pack).isEmpty(), "trade's pack can be switched off alone");
        change("pack", pack, false, "maintenance").andExpect(status().isOk());
        assertThat(packs.isEnabled(pack)).isFalse();
        assertThat(ledger.find(SwitchLedger.PACK, pack)).get().extracting(SwitchLedger.Entry::reason).isEqualTo("maintenance");
        change("pack", pack, true, "").andExpect(status().isOk());
        assertThat(packs.isEnabled(pack)).isTrue();
    }
}
