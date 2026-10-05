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
package com.ash.drishti.server.collab;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** {@code drishti.collab.enabled=false}: every collaboration endpoint answers 403 DRS-7004, and the console can ask {@code /collab}. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/collabdisabled-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/collabdisabled-overlay/added.yaml", "drishti.collab.enabled=false"})
@AutoConfigureMockMvc
class CollabDisabledTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String admin() {
        return "Bearer " + tokens.mint("drishti-dev-admin", List.of("admin"), 300);
    }

    @Test
    void everyEndpointSaysSharingIsOff() throws Exception {
        mvc.perform(post("/api/v1/shares").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"trade\",\"id\":\"IRS-48213\",\"note\":\"x\",\"to\":{\"users\":[\"a\"]}}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-7004"));
        mvc.perform(get("/api/v1/directory").param("q", "ab").header("Authorization", admin())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-7004"));
        mvc.perform(get("/api/v1/me/inbox").header("Authorization", admin())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-7004"));
        mvc.perform(get("/api/v1/shares/sh_01ARZ3NDEKTSV4RRFFQ69G5FAV").header("Authorization", admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/threads/trade/IRS-48213").header("Authorization", admin())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-7004"));
        mvc.perform(post("/api/v1/threads/trade/IRS-48213").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"anchor\":\"entity\",\"body\":\"x\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-7004"));
        mvc.perform(get("/api/v1/me/mentions").header("Authorization", admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/collab").header("Authorization", admin())).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.collaborate").value(false));
    }
}
