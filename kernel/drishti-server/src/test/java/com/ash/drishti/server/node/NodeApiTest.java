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
package com.ash.drishti.server.node;

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

/**
 * The node's readiness and drain over HTTP (CD1, CD2): readiness is its own group (the server's health and liveness stay UP while the node
 * is drained), the probes need no sign-in, the drain needs an administrator, and an undrained node is ready again.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.identity.iterations=1000",
        "drishti.var.enabled=false", "drishti.ccr.exposure.enabled=false",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class NodeApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    @Test
    void theDrainTakesTheNodeOutOfServiceForReadinessOnly() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/v1/admin/node").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.drained").value(false)).andExpect(jsonPath("$.readiness.status").value("UP"));
        mvc.perform(post("/api/v1/admin/node/drain")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/node/drain").header("Authorization", as("tina", "trader"))).andExpect(status().isForbidden());
        try {
            mvc.perform(post("/api/v1/admin/node/drain").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"on\":true}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.drained").value(true))
                    .andExpect(jsonPath("$.safeToStop").value(true))                 // no cluster, no shards: nothing to hand over
                    .andExpect(jsonPath("$.readiness.status").value("OUT_OF_SERVICE"));
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
            mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        } finally {
            mvc.perform(post("/api/v1/admin/node/drain").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"on\":false}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.drained").value(false));
        }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
