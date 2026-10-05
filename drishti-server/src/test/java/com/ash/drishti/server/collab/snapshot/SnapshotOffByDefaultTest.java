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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.ash.drishti.server.security.TokenVerifier;

/**
 * Snapshots are off unless an administrator turns them on: a share asking for a picture is refused with DRS-7015, the dialog is told
 * there is no option, and a share without one is unaffected. A tiny byte limit shows the size guard (DRS-7016) when they are on.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/snapshot-off-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/snapshot-off-overlay/added.yaml", "drishti.collab.store=file",
        "drishti.collab.dir=target/snapshot-off-${random.uuid}/collab"})
@AutoConfigureMockMvc
class SnapshotOffByDefaultTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    @Test
    void offByDefaultADialogHasNoOptionAndAPictureIsRefused() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        for (String u : List.of("ann", "rng")) {
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", u, "displayName", u, "email", u + "@desk.test", "roles", List.of("risk"),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
        mvc.perform(get("/api/v1/collab").param("kind", "trade").header("Authorization", as("ann", "risk"))).andExpect(jsonPath("$.snapshots").value(false));
        Map<String, Object> body = Map.of("kind", "trade", "id", "IRS-48213", "note", "x", "to", Map.of("users", List.of("rng")), "picture", true);
        mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-7015"));
        mvc.perform(post("/api/v1/shares/preview-picture").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "trade", "id", "IRS-48213", "note", "x", "to", Map.of("users", List.of("rng"))))))
                .andExpect(status().isCreated());
    }
}
