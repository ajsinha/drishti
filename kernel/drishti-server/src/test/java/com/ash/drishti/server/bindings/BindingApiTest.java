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
package com.ash.drishti.server.bindings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.TestRun;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Admin, Data bindings over HTTP: reload, and the four-eyes proposals (propose, reject, approve, withdraw), each with a malformed or refused
 * request answered 4xx. The bindings file lives beside the test's own connectors folder, never in config/.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.identity.iterations=1000",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class BindingApiTest {

    static final String API = "/api/v1/admin/bindings";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Path root = TestRun.temp("binding-api");
        r.add("drishti.data.dir", () -> root.resolve("data").toString());
        r.add("drishti.sources.connectors-dir", () -> root.resolve("connectors").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private ResultActions send(String path, String who, String role, String body) throws Exception {
        var req = post(path).header("Authorization", "Bearer " + tokens.mint(who, List.of(role), 300));
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    private String propose(String kind, String reason) throws Exception {
        var res = send(API + "/proposals", "ada", "admin", "{\"kind\":\"" + kind + "\",\"reason\":\"" + reason + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("proposed")).andReturn();
        return JSON.readTree(res.getResponse().getContentAsString()).path("id").asText();
    }

    @Test
    void reloadAppliesTheFileAndIsForAdministratorsOnly() throws Exception {
        send(API + "/reload", "ada", "admin", null).andExpect(status().isOk());
        send(API + "/reload", "tia", "trader", null).andExpect(status().isForbidden());
    }

    @Test
    void aProposalIsRejectedByASecondAdministratorOrWithdrawnByItsProposer() throws Exception {
        String id = propose("itemx", "drop it");
        send(API + "/proposals/" + id + "/reject", "ada", "admin", "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-2007"));
        send(API + "/proposals/" + id + "/reject", "bob", "admin", "{\"comment\":\"no\"}").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("rejected"));
        send(API + "/proposals/" + id + "/withdraw", "ada", "admin", null).andExpect(status().isConflict());
        String second = propose("itemx", "again");
        send(API + "/proposals/" + second + "/withdraw", "bob", "admin", null).andExpect(status().is4xxClientError());
        send(API + "/proposals/" + second + "/withdraw", "ada", "admin", null).andExpect(status().isOk());
    }

    @Test
    void aRemovalIsApprovedByASecondAdministrator() throws Exception {
        String id = propose("itemy", "remove the binding");
        send(API + "/proposals/" + id + "/approve", "ada", "admin", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-2007"));
        send(API + "/proposals/" + id + "/approve", "bob", "admin", null).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("approved"));
        send(API + "/proposals/" + id + "/approve", "bob", "admin", null).andExpect(status().isConflict());
    }

    @Test
    void malformedProposalsAndUnknownIdsAreClientErrors() throws Exception {
        send(API + "/proposals", "ada", "admin", "{\"kind\":\"itemz\",\"reason\":\"\"}").andExpect(status().isUnprocessableEntity());
        send(API + "/proposals", "ada", "admin", "{\"kind\":\"itemz\",\"binding\":{\"read\":[\"no-such-connector\"]},\"reason\":\"why\"}")
                .andExpect(status().isUnprocessableEntity());
        send(API + "/proposals", "ada", "admin", "{not json").andExpect(status().is4xxClientError());
        send(API + "/proposals", "tia", "trader", "{\"kind\":\"itemz\",\"reason\":\"why\"}").andExpect(status().isForbidden());
        send(API + "/proposals/nope/approve", "bob", "admin", null).andExpect(status().isNotFound());
        send(API + "/proposals/nope/reject", "bob", "admin", null).andExpect(status().isNotFound());
        send(API + "/proposals/nope/withdraw", "ada", "admin", null).andExpect(status().isNotFound());
    }
}
