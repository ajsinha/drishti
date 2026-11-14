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
package com.ash.drishti.server.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.TestRun;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Admin, Configuration's proposals over HTTP: propose, approve, reject, withdraw and "applied", each with its effect (the list, the state, the
 * audit trail), four eyes, a refusal for a secret or a bad key, and a non-administrator refused at every step. Console-area keys only, so an
 * approval writes no {@code application.local.yaml} into the working directory.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.identity.iterations=1000",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class SettingProposalsApiTest {

    static final String API = "/api/v1/admin/settings/proposals";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Path data = TestRun.temp("setting-proposals");
        r.add("drishti.data.dir", data::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private ResultActions send(String path, String who, String role, String body) throws Exception {
        var req = post(path).header("Authorization", as(who, role));
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions a) throws Exception {
        return JSON.readTree(a.andReturn().getResponse().getContentAsString());
    }

    private JsonNode listed(String id) throws Exception {
        JsonNode all = JSON.readTree(mvc.perform(get(API).header("Authorization", as("ada", "admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode p : all.path("proposals")) {
            if (id.equals(p.path("id").asText())) {
                return p;
            }
        }
        throw new AssertionError(id + " is not listed");
    }

    private String propose(String key, String reason) throws Exception {
        return body(send(API, "ada", "admin", "{\"key\":\"" + key + "\",\"area\":\"console\",\"value\":\"42\",\"was\":\"40\",\"reason\":\"" + reason + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("proposed"))).path("id").asText();
    }

    @Test
    void aProposalIsFiledApprovedByASecondAdministratorAndMarkedAppliedByTheConsole() throws Exception {
        String id = propose("console.sample.limit", "the desk asked");
        assertThat(listed(id).path("proposedBy").asText()).isEqualTo("ada");
        // a person who is not an administrator decides nothing
        send(API + "/" + id + "/approve", "tia", "trader", "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        assertThat(listed(id).path("state").asText()).as("a refusal changed nothing").isEqualTo("proposed");
        // four eyes: not the proposer
        send(API + "/" + id + "/approve", "ada", "admin", "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-2007"));
        // not applied before it is approved
        send(API + "/" + id + "/applied", "bob", "admin", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-2006"));
        send(API + "/" + id + "/approve", "bob", "admin", "{\"comment\":\"checked\"}").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("approved"))
                .andExpect(jsonPath("$.decidedBy").value("bob")).andExpect(jsonPath("$.comment").value("checked")).andExpect(jsonPath("$.applied").value(false));
        // a console key is written by the console, which then says so
        send(API + "/" + id + "/applied", "bob", "admin", null).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        JsonNode now = listed(id);
        assertThat(now.path("state").asText()).isEqualTo("approved");
        assertThat(now.path("applied").asBoolean()).isTrue();
        // decided already: a second decision is a conflict, and the proposer can no longer withdraw it
        send(API + "/" + id + "/reject", "bob", "admin", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-2006"));
        send(API + "/" + id + "/withdraw", "ada", "admin", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-2006"));
        String audit = mvc.perform(get("/api/v1/admin/audit").header("Authorization", as("ada", "admin"))).andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("setting-proposed", "setting-approved", "setting-applied", "console.sample.limit");
    }

    @Test
    void aProposalIsRejectedWithItsCommentOrWithdrawnByItsProposerOnly() throws Exception {
        String rejected = propose("console.sample.other", "try it");
        send(API + "/" + rejected + "/reject", "tia", "trader", "{}").andExpect(status().isForbidden());
        send(API + "/" + rejected + "/reject", "ada", "admin", "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-2007"));
        send(API + "/" + rejected + "/reject", "bob", "admin", "{\"comment\":\"not now\"}").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("rejected"))
                .andExpect(jsonPath("$.comment").value("not now"));
        assertThat(listed(rejected).path("state").asText()).isEqualTo("rejected");
        send(API + "/" + rejected + "/approve", "bob", "admin", "{}").andExpect(status().isConflict());

        String withdrawn = propose("console.sample.third", "changed my mind");
        send(API + "/" + withdrawn + "/withdraw", "tia", "trader", null).andExpect(status().isForbidden());
        send(API + "/" + withdrawn + "/withdraw", "bob", "admin", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-2006"));   // not its proposer
        send(API + "/" + withdrawn + "/withdraw", "ada", "admin", null).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("withdrawn"));
        assertThat(listed(withdrawn).path("state").asText()).isEqualTo("withdrawn");
        send(API + "/" + withdrawn + "/withdraw", "ada", "admin", null).andExpect(status().isConflict());                                              // no second withdrawal
        // a withdrawn key can be proposed again
        propose("console.sample.third", "again");
        send(API + "/no-such-proposal/approve", "bob", "admin", "{}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-2005"));
    }

    @Test
    void aBadProposalIsRefusedInWordsAndAnOpenKeyIsNotProposedTwice() throws Exception {
        send(API, "tia", "trader", "{\"key\":\"console.x\",\"area\":\"console\",\"value\":\"1\",\"reason\":\"r\"}").andExpect(status().isForbidden());
        send(API, "ada", "admin", "{\"key\":\"Not A Key\",\"area\":\"console\",\"value\":\"1\",\"reason\":\"r\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(startsWith("DRS-")));
        send(API, "ada", "admin", "{\"key\":\"console.x\",\"area\":\"elsewhere\",\"value\":\"1\",\"reason\":\"r\"}").andExpect(status().isBadRequest());
        send(API, "ada", "admin", "{\"key\":\"console.api-key\",\"area\":\"console\",\"value\":\"1\",\"reason\":\"r\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("secret")));
        send(API, "ada", "admin", "{\"key\":\"console.x\",\"area\":\"console\",\"value\":\"1\",\"reason\":\" \"}").andExpect(status().isBadRequest());
        send(API, "ada", "admin", "{\"key\":\"console.x\",\"area\":\"console\",\"value\":\"a\\nb\",\"reason\":\"r\"}").andExpect(status().isBadRequest());
        propose("console.sample.once", "first");
        send(API, "bob", "admin", "{\"key\":\"console.sample.once\",\"area\":\"console\",\"value\":\"7\",\"reason\":\"second\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRS-2006"));
        mvc.perform(get(API).header("Authorization", as("tia", "trader"))).andExpect(status().isForbidden());
    }
}
