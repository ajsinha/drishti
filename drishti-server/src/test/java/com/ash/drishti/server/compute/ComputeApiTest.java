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
package com.ash.drishti.server.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.server.TestRun;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Step C3 through the server (docs/architecture/PYTHON_COMPUTE.md section 16): a connector whose file has {@code requires:} stays off and
 * says why until an administrator approves; the person who asked cannot approve it; approval starts it, revocation stops it again; each
 * decision is audited; Admin → Compute's data says compute is off as shipped.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false"})
@AutoConfigureMockMvc
class ComputeApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired ConnectorManager manager;
    @Autowired ConnectorFiles files;
    @Autowired AuditLog audit;

    @AfterEach
    void clean() {
        for (String n : new String[] {"compute-gate", "compute-mine"}) {
            if (files.exists(n)) {
                files.delete(n);
            }
        }
        manager.scan();
    }

    private void connector(String name, String requestedBy) throws Exception {
        Path root = TestRun.temp(name);
        files.write(name, """
                plugin: file
                kinds: [item]
                settings:
                  root: %s
                requires:
                  plugins: [ { name: drishti-plugin-file } ]
                  requested-by: %s
                """.formatted(JSON.writeValueAsString(root.toString()), requestedBy));
        manager.scan();
    }

    private JsonNode overview() throws Exception {
        return JSON.readTree(mvc.perform(get("/api/v1/admin/compute")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String pendingFor(String subject) throws Exception {
        for (JsonNode r : overview().get("approvals")) {
            if (r.get("subject").asText().equals(subject) && r.get("state").asText().equals("pending")) {
                return r.get("id").asText();
            }
        }
        throw new AssertionError("no pending request for " + subject + " in " + overview().get("approvals"));
    }

    @Test
    void computeIsOffAsShippedAndSaysSo() throws Exception {
        JsonNode o = overview();
        assertThat(o.get("enabled").asBoolean()).isFalse();
        assertThat(o.get("pools").isArray()).isTrue();
    }

    @Test
    void aConnectorWithUnapprovedRequirementsStaysOffUntilApprovedAndStopsWhenRevoked() throws Exception {
        connector("compute-gate", "dev1");
        mvc.perform(get("/api/v1/admin/connectors/compute-gate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_APPROVED"))
                .andExpect(jsonPath("$.health", containsString("waiting for approval")));
        String id = pendingFor("connector:compute-gate");
        mvc.perform(post("/api/v1/admin/compute/requests/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-9202"));
        mvc.perform(post("/api/v1/admin/compute/requests/" + id + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"the file plugin ships with Drishti\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("approved"));
        awaitState("compute-gate", false);
        mvc.perform(post("/api/v1/admin/compute/requests/" + id + "/revoke").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"withdrawn for review\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("revoked"));
        awaitState("compute-gate", true);
        assertThat(audit.recent(50, "connector:compute-gate")).extracting(AuditLog.Event::action)
                .contains("compute-request", "compute-approve", "compute-revoke");
    }

    @Test
    void thePersonWhoAskedCannotApprove() throws Exception {
        String me = overview().get("me").asText();
        connector("compute-mine", me);
        String id = pendingFor("connector:compute-mine");
        mvc.perform(post("/api/v1/admin/compute/requests/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"mine\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("four eyes")));
        mvc.perform(post("/api/v1/admin/compute/requests/nope/approve").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    private void awaitState(String name, boolean held) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        String state = "";
        while (System.currentTimeMillis() < until) {
            state = JSON.readTree(mvc.perform(get("/api/v1/admin/connectors/" + name)).andReturn().getResponse().getContentAsString()).get("state").asText();
            if (held == "NOT_APPROVED".equals(state)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(name + " is " + state);
    }
}
