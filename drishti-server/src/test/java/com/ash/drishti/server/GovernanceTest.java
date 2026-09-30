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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** W19: a Studio save is a proposal; four eyes approve it; stale and self approvals are refused; all is audited. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.rachana.studio-save=true", "drishti.rachana.dirs=${java.io.tmpdir}/drishti-gov-${random.uuid}",
        "drishti.governance.dir=${java.io.tmpdir}/drishti-gov-props-${random.uuid}",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class GovernanceTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private static String sutra(String name, String title) {
        return "# " + title + "\n\n```sutra\nsutra: " + name + "\nversion: 1\nmatch: { kind: trade }\ntitle: { id: $.tradeId }\n```\n";
    }

    private String propose(String text, String by) throws Exception {
        String body = mvc.perform(post("/api/v1/sutras").param("note", "clearer title").contentType("text/markdown").content(text)
                        .header("Authorization", as(by, "author")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.proposal.status").value("pending"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("proposal").path("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions decide(String id, String action, String by, String role, String comment) throws Exception {
        return mvc.perform(post("/api/v1/sutras/proposals/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON)
                .content(comment == null ? "{}" : "{\"comment\":\"" + comment + "\"}").header("Authorization", as(by, role)));
    }

    @Test
    void aProposalGoesLiveOnlyWhenSomeoneElseApprovesIt() throws Exception {
        String id = propose(sutra("gov-a", "First"), "ana");
        mvc.perform(get("/api/v1/sutras/gov-a/1").header("Authorization", as("ana", "author"))).andExpect(status().isNotFound());   // not live yet
        mvc.perform(get("/api/v1/sutras/proposals/" + id).header("Authorization", as("rui", "approver")))
                .andExpect(jsonPath("$.mayApprove").value(true)).andExpect(jsonPath("$.newVersion").value(true))
                .andExpect(jsonPath("$.text").value(containsString("# First")));
        decide(id, "approve", "ana", "approver", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-2007"));
        decide(id, "approve", "ana", "author", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        decide(id, "approve", "rui", "approver", "looks right").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.reviewer").value("rui"));
        mvc.perform(get("/api/v1/sutras/gov-a/1").header("Authorization", as("ana", "author"))).andExpect(status().isOk());
        decide(id, "approve", "rui", "approver", null).andExpect(status().isConflict());               // decided once
        mvc.perform(get("/api/v1/admin/audit").param("subject", "gov-a@1").header("Authorization", as("root", "admin")))
                .andExpect(jsonPath("$[*].action", org.hamcrest.Matchers.hasItems("sutra-proposed", "sutra-approved")));
    }

    @Test
    void aStaleProposalIsRefusedAndRejectingNeedsAReason() throws Exception {
        String one = propose(sutra("gov-b", "Stale one"), "ana");
        String two = propose(sutra("gov-b", "Stale two"), "bea");
        decide(two, "approve", "rui", "approver", null).andExpect(status().isOk());
        // one was proposed against the old live text: approving it would silently undo two
        decide(one, "approve", "rui", "approver", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-2006"));
        mvc.perform(get("/api/v1/sutras/proposals/" + one).header("Authorization", as("rui", "approver"))).andExpect(jsonPath("$.stale").value(true));
        decide(one, "reject", "rui", "approver", null).andExpect(status().isBadRequest());
        decide(one, "reject", "rui", "approver", "superseded by " + two).andExpect(jsonPath("$.status").value("rejected"));
        String three = propose(sutra("gov-b", "Withdrawn"), "ana");
        decide(three, "withdraw", "bea", "author", null).andExpect(status().isForbidden());
        decide(three, "withdraw", "ana", "author", null).andExpect(jsonPath("$.status").value("withdrawn"));
        mvc.perform(get("/api/v1/sutras/gov-b/history").header("Authorization", as("ana", "author")))
                .andExpect(jsonPath("$[*].status", org.hamcrest.Matchers.hasItems("approved", "rejected", "withdrawn")));
        mvc.perform(get("/api/v1/sutras/proposals").header("Authorization", as("vic", "viewer"))).andExpect(status().isForbidden());
    }
}
