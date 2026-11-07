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
package com.ash.drishti.server.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The one Approvals API over the real kinds (here Sutra proposals, with security on): two people each see their own queue, the maker's item is
 * in her Mine sent and the approver's inbox, the item opens with its diff, effect and rules, a question is asked and answered, the old
 * per-kind decision endpoints are gone, and the decision is made here.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.rachana.studio-save=true", "drishti.rachana.dirs=${java.io.tmpdir}/drishti-appr-${random.uuid}",
        "drishti.governance.dir=${java.io.tmpdir}/drishti-appr-props-${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/approvals-${random.uuid}/identity.db",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class ApprovalApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String propose(String name, String by) throws Exception {
        String text = "# Before\nrachana: 1\nsutra: " + name + "\nversion: 1\nmatch: { kind: trade }\ntitle: { id: $.tradeId }\n";
        String body = mvc.perform(post("/api/v1/sutras").param("note", "clearer title").contentType("text/yaml").content(text)
                .header("Authorization", as(by, "author"))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("proposal").path("id").asText();
    }

    private static List<String> ids(JsonNode listing, String kind) {
        List<String> out = new ArrayList<>();
        listing.path("items").forEach(e -> {
            if (kind.equals(e.path("item").path("kind").asText())) {
                out.add(e.path("item").path("id").asText());
            }
        });
        return out;
    }

    @Test
    void twoPeopleSeeTheirOwnQueuesAndAnItemIsAskedAboutAndDecidedHere() throws Exception {
        String id = propose("appr-a", "ana");
        // the approver's inbox has it; the maker's inbox does not, her Mine sent does
        assertThat(ids(ok(mvc.perform(get("/api/v1/approvals").header("Authorization", as("rui", "approver")))), "sutra")).contains(id);
        assertThat(ids(ok(mvc.perform(get("/api/v1/approvals").header("Authorization", as("ana", "author")))), "sutra")).doesNotContain(id);
        assertThat(ids(ok(mvc.perform(get("/api/v1/approvals").param("box", "sent").header("Authorization", as("ana", "author")))), "sutra")).contains(id);
        assertThat(ok(mvc.perform(get("/api/v1/approvals/count").header("Authorization", as("rui", "approver")))).path("inbox").asInt()).isPositive();
        JsonNode kinds = ok(mvc.perform(get("/api/v1/approvals").header("Authorization", as("rui", "approver")))).path("kinds");
        assertThat(kinds.findValuesAsText("kind")).contains("sutra", "change", "bi", "compute", "function", "stress-window", "var-report");

        // opened: what changes, its effect, the rules, the actions this person has
        JsonNode opened = ok(mvc.perform(get("/api/v1/approvals/sutra/" + id).header("Authorization", as("rui", "approver"))));
        assertThat(opened.at("/detail/diff/format").asText()).isEqualTo("lines");
        assertThat(opened.at("/detail/effect").size()).isPositive();
        assertThat(opened.at("/detail/rules").toString()).contains("four eyes");
        assertThat(opened.path("actions").toString()).contains("approve").contains("reject").contains("ask");
        assertThat(ok(mvc.perform(get("/api/v1/approvals/sutra/" + id).header("Authorization", as("ana", "author")))).path("actions").toString())
                .contains("withdraw").doesNotContain("approve");

        // a question, and its answer: a thread on the item
        ok(mvc.perform(post("/api/v1/approvals/sutra/" + id + "/questions").header("Authorization", as("rui", "approver"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Why the new title?\"}")));
        JsonNode thread = ok(mvc.perform(post("/api/v1/approvals/sutra/" + id + "/questions").header("Authorization", as("ana", "author"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"The desk asked.\"}")));
        assertThat(thread.findValuesAsText("by")).containsExactly("rui", "ana");
        assertThat(ok(mvc.perform(get("/api/v1/me/inbox?limit=20").header("Authorization", as("ana", "author")))).toString())
                .contains("/approvals/sutra/" + id).doesNotContain("Why the new title");

        // the maker never approves her own; the old endpoint is gone; the approver decides here
        mvc.perform(post("/api/v1/approvals/sutra/" + id + "/approve").header("Authorization", as("ana", "approver"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sutras/proposals/" + id + "/approve").header("Authorization", as("rui", "approver"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/approvals/sutra/" + id + "/approve").header("Authorization", as("rui", "approver"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"fine\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved")).andExpect(jsonPath("$.decidedBy").value("rui"));
        assertThat(ids(ok(mvc.perform(get("/api/v1/approvals").header("Authorization", as("rui", "approver")))), "sutra")).doesNotContain(id);
        assertThat(ids(ok(mvc.perform(get("/api/v1/approvals").param("box", "done").header("Authorization", as("ana", "author")))), "sutra")).contains(id);
        mvc.perform(get("/api/v1/approvals").param("box", "nope").header("Authorization", as("rui", "approver"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/approvals/nope/" + id).header("Authorization", as("rui", "approver"))).andExpect(status().isNotFound());
    }
}
