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
        "drishti.identity.database-url=jdbc:sqlite:target/packaccess-${random.uuid}/identity.db"})
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
}
