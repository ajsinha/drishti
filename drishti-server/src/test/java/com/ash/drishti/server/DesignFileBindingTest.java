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
        "drishti.rachana.dirs=${java.io.tmpdir}/drishti-bind-sutras", "drishti.builder.dev-dir=${java.io.tmpdir}/drishti-bind-dev", "drishti.governance.dir=${java.io.tmpdir}/drishti-bind-props-${random.uuid}",
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
        return Path.of(System.getProperty("java.io.tmpdir"), "drishti-bind-dev", "ana", FILE);
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
        JsonNode synced = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/sync").header("Authorization", who)));
        assertThat(synced.path("changed").asBoolean()).isTrue();
        assertThat(synced.path("sutra").asText()).contains("# edited in the IDE");
        assertThat(ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/sync").header("Authorization", who))).path("changed").asBoolean()).isFalse();

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

    private String newDesign(String who, String name) throws Exception {
        return ok(mvc.perform(post("/api/v1/builder/designs").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"kind\":\"bind-thing\",\"sutra\":" + JSON.writeValueAsString(sutra(name)) + "}"))).path("id").asText();
    }

    private ResultActions bind(String who, String id, String file) throws Exception {
        return mvc.perform(post("/api/v1/builder/designs/" + id + "/bind").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("file", file))));
    }

    /** S2-01: Save to file writes the author's own development folder, which the registry never loads: nothing goes live. */
    @Test
    void savingAFileNeverMakesASutraLiveAndStaysInTheAuthorsOwnFolder() throws Exception {
        String who = as("ana", "author");
        String id = newDesign(who, "tricky-new");
        ok(bind(who, id, "tricky/tricky-new.v1.sutra.yaml"));
        ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/save-file").header("Authorization", who)));
        assertThat(Files.exists(Path.of(System.getProperty("java.io.tmpdir"), "drishti-bind-dev", "ana", "tricky", "tricky-new.v1.sutra.yaml"))).isTrue();
        assertThat(Files.exists(Path.of(System.getProperty("java.io.tmpdir"), "drishti-bind-sutras", "tricky"))).isFalse();
        String live = mvc.perform(get("/api/v1/sutras").header("Authorization", who)).andReturn().getResponse().getContentAsString();
        assertThat(live).doesNotContain("tricky-new");
        // another author's folder is a different place: the same relative name does not reach ana's file
        String bo = as("bo", "author");
        String bid = newDesign(bo, "bo-thing");
        assertThat(ok(bind(bo, bid, "tricky/tricky-new.v1.sutra.yaml")).path("sutra").asText()).contains("bo-thing");
    }

    /** S2-06: no absolute path for a signed-in user who is not an administrator. */
    @Test
    void theBindingInfoShowsNoAbsolutePathToANonAdministrator() throws Exception {
        JsonNode info = ok(mvc.perform(get("/api/v1/builder/designs/binding").header("Authorization", as("ana", "author"))));
        assertThat(info.path("dir").asText()).isEqualTo("drishti-bind-dev/ana");
        assertThat(info.toString()).doesNotContain(System.getProperty("java.io.tmpdir"));
        JsonNode admin = ok(mvc.perform(get("/api/v1/builder/designs/binding").header("Authorization", as("root", "admin"))));
        assertThat(admin.path("absoluteDir").asText()).endsWith("drishti-bind-dev/root");
    }

    /** S2-05 and S2-07: a symbolic link is never followed; a name the file system refuses is a clean 400, a long body a 413. */
    @Test
    void symbolicLinksAreNeverFollowedAndABadNameIsAProblemNotA500() throws Exception {
        String who = as("ana", "author");
        Path mine = Path.of(System.getProperty("java.io.tmpdir"), "drishti-bind-dev", "ana");
        Files.createDirectories(mine);
        Path outside = Files.createTempFile("outside", ".yaml");
        Files.writeString(outside, "OUTSIDE-FILE-MARKER");
        Path link = mine.resolve("link.yaml");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, outside);
        String id = newDesign(who, "linked");
        bind(who, id, "link.yaml").andExpect(status().isBadRequest());
        Path dirLink = mine.resolve("dirlink");
        Files.deleteIfExists(dirLink);
        Files.createSymbolicLink(dirLink, outside.getParent());
        bind(who, id, "dirlink/x.yaml").andExpect(status().isBadRequest());
        // a planted x.yaml.tmp link is not what the write goes through
        Files.writeString(mine.resolve("plain.yaml"), sutra("plain"));
        Path planted = mine.resolve("plain.yaml.tmp");
        Files.deleteIfExists(planted);
        Files.createSymbolicLink(planted, outside);
        ok(bind(who, id, "plain.yaml"));
        ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/save-file").header("Authorization", who)));
        assertThat(Files.readString(outside)).isEqualTo("OUTSIDE-FILE-MARKER");
        bind(who, id, "n".repeat(300) + ".yaml").andExpect(status().isBadRequest());
        bind(who, id, "plain.yaml/inner.yaml").andExpect(status().isBadRequest());
        bind(who, id, "bad\u0001.yaml").andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/builder/designs/" + id + "/bind").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"file\":\"" + "n".repeat(1_100_000) + "\"}")).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/v1/builder/designs/" + id + "/bind").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content("{not json")).andExpect(status().isBadRequest());
    }

    /** S2-13: sync changes the design, so it is a POST only. */
    @Test
    void syncIsNoLongerAGet() throws Exception {
        String who = as("ana", "author");
        String id = newDesign(who, "syncy");
        mvc.perform(get("/api/v1/builder/designs/" + id + "/sync").header("Authorization", who)).andExpect(status().is4xxClientError());
    }
}
