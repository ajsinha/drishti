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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics",
        "drishti.identity.database-url=jdbc:sqlite:target/packaccess-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/packaccess-overlay/added.yaml"})
@AutoConfigureMockMvc
class PackAccessTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    @Test
    void adminsAssignPacksAndUsersChooseAmongThem() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"lena\",\"roles\":[\"ops\",\"risk\"],\"password\":\"logistics-pass-1\",\"packs\":[\"logistics\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.packs[0]").value("logistics"));
        String lena = as("lena", "ops", "risk");
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", lena)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", lena)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/command/suggest").param("q", "T").header("Authorization", lena))
                .andExpect(jsonPath("$[*].mnemonic").value(not(hasItem("TRD"))));
        mvc.perform(get("/api/v1/packs").header("Authorization", lena))
                .andExpect(jsonPath("$[?(@.name=='finance')].assigned").value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.name=='logistics')].active").value(hasItem(true)));
        mvc.perform(put("/api/v1/me/packs").header("Authorization", lena).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":[\"finance\"]}")).andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/admin/users/lena").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"packs\":[\"finance\",\"logistics\"]}")).andExpect(jsonPath("$.packs.length()").value(2));
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", lena)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/me/packs").header("Authorization", lena).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":[\"finance\"]}")).andExpect(jsonPath("$.active[0]").value("finance"));
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", lena)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/users/lena").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"packs\":[\"astrology\"]}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void adminsSwitchPacksOffAndOnForEveryone() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        String user = as("drishti-dev-admin", "admin");
        mvc.perform(get("/api/v1/admin/packs").header("Authorization", admin))
                .andExpect(jsonPath("$[?(@.name=='logistics')].loaded").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.name=='trading')].loaded").value(hasItem(false)));           // on disk, not loaded
        mvc.perform(put("/api/v1/admin/packs/logistics").header("Authorization", as("lena", "ops")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/admin/packs/logistics").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/packs").header("Authorization", user)).andExpect(jsonPath("$[*].name").value(not(hasItem("logistics"))));
        mvc.perform(get("/api/v1/command/suggest").param("q", "SHP").header("Authorization", user))
                .andExpect(jsonPath("$[*].kind").value(not(hasItem("shipment"))));

        mvc.perform(put("/api/v1/admin/packs/logistics").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", user)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/packs/astrology").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/audit").param("subject", "logistics").header("Authorization", admin))
                .andExpect(jsonPath("$[*].action").value(hasItem("pack-disabled")));
    }

    @Test
    void adminsLoadAndUnloadPacksThroughTheOverlay() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/packaccess-overlay/added.yaml"));
        mvc.perform(post("/api/v1/admin/packs/genomics/load").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added[0]").value("genomics"))
                .andExpect(jsonPath("$.restarting").value(false));                    // tests do not restart: next start
        org.assertj.core.api.Assertions.assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("target/packaccess-overlay/added.yaml")))
                .contains("added:", "- genomics");
        mvc.perform(post("/api/v1/admin/packs/finance/load").header("Authorization", admin)).andExpect(status().isBadRequest());     // loaded
        mvc.perform(post("/api/v1/admin/packs/astrology/load").header("Authorization", admin)).andExpect(status().isBadRequest());   // no such pack
        mvc.perform(post("/api/v1/admin/packs/trading/load").header("Authorization", admin))                                   // clashes with finance
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("defined by both")));
        mvc.perform(post("/api/v1/admin/packs/finance/unload").header("Authorization", admin)).andExpect(status().isBadRequest());   // site config
        mvc.perform(post("/api/v1/admin/packs/genomics/load").header("Authorization", as("lena", "ops"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/packs/genomics/unload").header("Authorization", admin)).andExpect(jsonPath("$.added").isEmpty());
        mvc.perform(get("/api/v1/admin/audit").param("subject", "genomics").header("Authorization", admin))
                .andExpect(jsonPath("$[*].action").value(hasItem("pack-loaded")));
    }
}
