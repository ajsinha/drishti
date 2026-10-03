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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Build workbench step 8, development file binding: saving writes the file, an edit made outside comes back, a clash is a 409. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.rachana.studio-save=true", "drishti.builder.file-binding=true",
        "drishti.rachana.dirs=${java.io.tmpdir}/drishti-bind-sutras", "drishti.governance.dir=${java.io.tmpdir}/drishti-bind-props-${random.uuid}",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/bind-api-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/bind-api-files-${random.uuid}"})
@AutoConfigureMockMvc
class DesignFileBindingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE = "bound/bind-thing.v1.sutra.yaml";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private static String sutra(String title) {
        return "rachana: 1\nsutra: bind-thing\nversion: 1\nmatch: { kind: bind-thing, priority: 100 }\ntitle: { id: $.thingId }\n# " + title + "\n"
                + "panels:\n  - id: terms\n    kind: kv\n    columns:\n      - { label: Label, bind: $.label }\n";
    }

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return JSON.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private static Path file() {
        return Path.of(System.getProperty("java.io.tmpdir"), "drishti-bind-sutras", FILE);
    }

    @Test
    void saveWritesTheFileAndAnOutsideEditComesBackIntoTheDesign() throws Exception {
        String who = as("ana", "author");
        JsonNode made = ok(mvc.perform(post("/api/v1/builder/designs").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bound\",\"kind\":\"bind-thing\",\"sutra\":" + JSON.writeValueAsString(sutra("first")) + "}")));
        String id = made.path("id").asText();
        Files.deleteIfExists(file());
        assertThat(ok(mvc.perform(get("/api/v1/builder/designs/binding").header("Authorization", who))).path("enabled").asBoolean()).isTrue();

        JsonNode bound = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/bind").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"file\":\"" + FILE + "\"}")));
        assertThat(bound.path("boundFile").asText()).isEqualTo(FILE);
        ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/save-file").header("Authorization", who)));
        assertThat(Files.readString(file())).isEqualTo(sutra("first"));

        // an IDE edit: the next sync brings it into the design as a step (undo-able)
        Files.writeString(file(), sutra("edited in the IDE"));
        JsonNode synced = ok(mvc.perform(get("/api/v1/builder/designs/" + id + "/sync").header("Authorization", who)));
        assertThat(synced.path("changed").asBoolean()).isTrue();
        assertThat(synced.path("sutra").asText()).contains("# edited in the IDE");
        assertThat(ok(mvc.perform(get("/api/v1/builder/designs/" + id + "/sync").header("Authorization", who))).path("changed").asBoolean()).isFalse();

        // both sides changed: the design edit does not silently overwrite the IDE's file
        ok(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/builder/designs/" + id).header("Authorization", who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"sutra\":" + JSON.writeValueAsString(sutra("design edit")) + "}")));
        Files.writeString(file(), sutra("IDE again"));
        mvc.perform(post("/api/v1/builder/designs/" + id + "/save-file").header("Authorization", who)).andExpect(status().isConflict());
        assertThat(Files.readString(file())).isEqualTo(sutra("IDE again"));
    }

    @Test
    void aPathOutsideTheSutraDirectoryIsRefused() throws Exception {
        String who = as("ana", "author");
        String id = ok(mvc.perform(post("/api/v1/builder/designs").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"escape\",\"kind\":\"bind-thing\",\"sutra\":" + JSON.writeValueAsString(sutra("x")) + "}"))).path("id").asText();
        for (String bad : new String[] {"../escape.sutra.yaml", "/etc/x.yaml", "a/../../b.yaml", "plain.txt"}) {
            mvc.perform(post("/api/v1/builder/designs/" + id + "/bind").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"file\":\"" + bad + "\"}")).andExpect(status().isBadRequest());
        }
    }
}
