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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.design.FormDefinitions;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Build workbench Form tab: the search form of a Design's kind is a part of the Design (a step of its log: undo, redo, a stale revision
 * is 409), previewed with the field palette of the samples, read back as problems when wrong, and a change to it travels with the
 * proposal as evidence (before and after) and is in force once approved.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.rachana.studio-save=true", "drishti.rachana.dirs=${java.io.tmpdir}/drishti-form-${random.uuid}",
        "drishti.governance.dir=${java.io.tmpdir}/drishti-form-props-${random.uuid}",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/form-api-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/form-api-files-${random.uuid}"})
@AutoConfigureMockMvc
class DesignFormApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUTRA = "rachana: 1\nsutra: form-demo\nversion: 1\ndescription: A demo thing.\n"
            + "match: { kind: form-thing, priority: 100 }\ntitle: { id: $.thingId }\n"
            + "panels:\n  - id: terms\n    kind: kv\n    columns:\n      - { label: Label, bind: $.label }\n";
    private static final String FORM = "- {path: thingId, label: Thing id, type: id}\n- {path: amount, type: amount, operators: ['>', between]}\n"
            + "- {path: colour, type: enum, values: [red, blue]}\n";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired FormDefinitions definitions;

    private String as(String user) {
        return "Bearer " + tokens.mint(user, List.of("author"), 300);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return JSON.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private String design(String user, String name) throws Exception {
        JsonNode made = ok(mvc.perform(post("/api/v1/builder/designs").header("Authorization", as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"kind\":\"form-thing\",\"sutra\":" + JSON.writeValueAsString(SUTRA.replace("form-demo", name)) + "}")));
        String id = made.path("id").asText();
        ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/samples").header("Authorization", as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"samples\":[{\"name\":\"one.json\",\"document\":{\"thingId\":\"T-1\",\"label\":\"Widget\",\"amount\":1250000.5,\"opened\":\"2026-03-01\","
                        + "\"live\":true,\"owner\":{\"name\":\"Ana\"}}}]}")));
        return id;
    }

    private JsonNode rev(String user, String id) throws Exception {
        return ok(mvc.perform(get("/api/v1/builder/designs/" + id).header("Authorization", as(user))));
    }

    private JsonNode save(String user, String id, int rev, String text) throws Exception {
        return ok(mvc.perform(put("/api/v1/builder/designs/" + id + "/form").header("Authorization", as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("baseRev", rev, "text", text)))));
    }

    @Test
    void thePaletteIsTheSamplesFieldsTypedFromTheirValues() throws Exception {
        String id = design("fay", "form-a");
        JsonNode a = ok(mvc.perform(get("/api/v1/builder/designs/" + id + "/form").header("Authorization", as("fay"))));
        assertThat(a.path("kind").asText()).isEqualTo("form-thing");
        assertThat(a.path("form").asText()).isEmpty();
        Map<String, String> types = new java.util.LinkedHashMap<>();
        a.path("palette").forEach(p -> types.put(p.path("path").asText(), p.path("type").asText()));
        assertThat(types).containsEntry("thingId", "id").containsEntry("amount", "amount").containsEntry("opened", "date").containsEntry("live", "bool")
                .containsEntry("owner.name", "text").containsEntry("label", "text");
        assertThat(a.path("starter")).hasSizeGreaterThan(3);
    }

    @Test
    void theFormIsKeptAsAStepOfTheLogAndUndoneWithIt() throws Exception {
        String id = design("fay", "form-b");
        JsonNode saved = save("fay", id, rev("fay", id).path("rev").asInt(), FORM);
        assertThat(saved.path("problems")).isEmpty();
        assertThat(saved.path("spec").path("fields")).hasSize(3);
        assertThat(saved.path("spec").path("fields").get(1).path("operators")).extracting(JsonNode::asText).containsExactly(">", "between");
        assertThat(rev("fay", id).path("form").asText()).isEqualTo(FORM);
        JsonNode undone = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/undo").header("Authorization", as("fay")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseRev\":" + saved.path("rev").asInt() + "}")));
        assertThat(undone.path("form").asText()).isEmpty();
        mvc.perform(put("/api/v1/builder/designs/" + id + "/form").header("Authorization", as("fay")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseRev\":1,\"text\":\"- {path: a}\"}")).andExpect(status().isConflict());                 // stale
    }

    @Test
    void whatIsWrongIsListedAndNothingIsRefusedWhileTyping() throws Exception {
        String id = design("fay", "form-c");
        JsonNode p = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/form/preview").header("Authorization", as("fay")).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("text", "- {path: a b}\n- {path: x, type: money}\n- {path: y, type: text, operators: [between]}\n")))));
        assertThat(p.path("problems")).extracting(x -> x.path("message").asText()).anyMatch(m -> m.contains("not a field path"))
                .anyMatch(m -> m.contains("type 'money'")).anyMatch(m -> m.contains("operator 'between' does not suit type text"));
        assertThat(p.path("problems").get(0).path("code").asText()).isEqualTo("DRS-2060");
        JsonNode bad = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/form/preview").header("Authorization", as("fay")).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("text", "- {path: a\n")))));
        assertThat(bad.path("problems").get(0).path("message").asText()).contains("not valid YAML");
        assertThat(rev("fay", id).path("form").asText()).isEmpty();                                                      // a preview saves nothing
    }

    @Test
    void anotherAuthorCannotReadOrChangeIt() throws Exception {
        String id = design("fay", "form-d");
        mvc.perform(get("/api/v1/builder/designs/" + id + "/form").header("Authorization", as("gus"))).andExpect(status().isNotFound());
    }

    @Test
    void aProposedFormTravelsWithTheProposalAndIsInForceOnceApproved() throws Exception {
        String id = design("fay", "form-e");
        save("fay", id, rev("fay", id).path("rev").asInt(), FORM);
        assertThat(definitions.yamlOf("form-thing")).isEmpty();
        JsonNode proposed = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/propose").header("Authorization", as("fay")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"a search form for things\"}")));
        String pid = proposed.path("proposal").path("id").asText();
        assertThat(pid).startsWith("P-");
        JsonNode review = ok(mvc.perform(get("/api/v1/sutras/proposals/" + pid).header("Authorization", "Bearer " + tokens.mint("rui", List.of("approver"), 300))));
        assertThat(review.path("evidence").path("form").path("kind").asText()).isEqualTo("form-thing");              // reviewers see before and after
        assertThat(review.path("evidence").path("form").path("before").asText()).isEmpty();
        assertThat(review.path("evidence").path("form").path("after").asText()).isEqualTo(FORM);
        assertThat(definitions.yamlOf("form-thing")).isEmpty();                                                      // not in force while it waits
        ok(mvc.perform(post("/api/v1/sutras/proposals/" + pid + "/approve").header("Authorization", "Bearer " + tokens.mint("rui", List.of("approver"), 300))
                .contentType(MediaType.APPLICATION_JSON).content("{}")));
        assertThat(definitions.yamlOf("form-thing")).isEqualTo(FORM);
        definitions.revert("form-thing");
        assertThat(definitions.yamlOf("form-thing")).isEmpty();
    }
}
