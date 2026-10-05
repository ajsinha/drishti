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
package com.ash.drishti.server.collab.snapshot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The size guard: a picture larger than {@code max-bytes} refuses the share with DRS-7016, and nothing is sent. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/snapshot-limits-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/snapshot-limits-overlay/added.yaml", "drishti.collab.store=file",
        "drishti.collab.dir=target/snapshot-limits-${random.uuid}/collab", "drishti.collab.snapshots.enabled=true",
        "drishti.collab.snapshots.max-bytes=10000"})
@AutoConfigureMockMvc
class SnapshotLimitsTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    @Test
    void aPictureOverTheByteLimitRefusesTheShare() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        for (String u : List.of("ann", "rng")) {
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", u, "displayName", u, "email", u + "@desk.test", "roles", List.of("risk"),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "trade", "id", "IRS-48213", "note", "x", "to", Map.of("users", List.of("rng")), "picture", true))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DRS-7016"));
    }
}
