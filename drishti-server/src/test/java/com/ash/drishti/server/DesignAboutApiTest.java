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

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Build workbench About tab (CONTEXT_HELP.md step 7): the About text is a part of a Design, saved as a step of its log (undo, redo,
 * revisions, a stale revision is 409), explained over a sample with the drawer's own code (page text, panel text, glossary), linted
 * ({@code DRS-2040} to {@code DRS-2047}), exported as {@code config/about.yaml} with the pack fragment and imported back.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.rachana.studio-save=true", "drishti.rachana.dirs=${java.io.tmpdir}/drishti-about-${random.uuid}",
        "drishti.governance.dir=${java.io.tmpdir}/drishti-about-props-${random.uuid}",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/about-api-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/about-api-files-${random.uuid}"})
@AutoConfigureMockMvc
class DesignAboutApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUTRA = "rachana: 1\nsutra: about-demo\nversion: 1\ndescription: A demo thing.\n"
            + "match: { kind: about-thing, priority: 100 }\ntitle: { id: $.thingId }\n"
            + "panels:\n  - id: terms\n    kind: kv\n    columns:\n      - { label: Label, bind: $.label }\n";
    private static final String ABOUT = "about: 1\nkinds:\n  about-thing:\n    title: About thing\n"
            + "    about: \"Thing ${$.thingId} is called ${$.label}.\"\n"
            + "    panels:\n      terms:\n        about: \"The terms of ${$.thingId}.\"\n"
            + "    glossary:\n      label:\n        term: Label\n        means: What the thing is called.\n";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user) {
        return "Bearer " + tokens.mint(user, List.of("author"), 300);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return JSON.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private String design(String user, String name) throws Exception {
        JsonNode made = ok(mvc.perform(post("/api/v1/builder/designs").header("Authorization", as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"kind\":\"about-thing\",\"sutra\":" + JSON.writeValueAsString(SUTRA.replace("about-demo", name)) + "}")));
        String id = made.path("id").asText();
        ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/samples").header("Authorization", as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"samples\":[{\"name\":\"one.json\",\"document\":{\"thingId\":\"T-1\",\"label\":\"Widget\"}}]}")));
        return id;
    }

    private JsonNode save(String user, String id, int rev, String text) throws Exception {
        return ok(mvc.perform(put("/api/v1/builder/designs/" + id + "/about").header("Authorization", as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("baseRev", rev, "text", text)))));
    }

    private JsonNode rev(String user, String id) throws Exception {
        return ok(mvc.perform(get("/api/v1/builder/designs/" + id).header("Authorization", as(user))));
    }

    @Test
    void theCardShowsTheTextPanelTextAndGlossaryOfTheSampleAndLintsWhatIsMissing() throws Exception {
        String id = design("ana", "about-a");
        JsonNode none = ok(mvc.perform(get("/api/v1/builder/designs/" + id + "/about").header("Authorization", as("ana"))));
        assertThat(none.path("card").path("about").path("text").isMissingNode()).isTrue();
        assertThat(none.path("card").path("about").path("sutraDescription").asText()).isEqualTo("A demo thing.");
        assertThat(none.path("lint")).extracting(x -> x.path("code").asText()).contains("DRS-2047");

        JsonNode saved = save("ana", id, rev("ana", id).path("rev").asInt(), ABOUT);
        JsonNode card = saved.path("card");
        assertThat(card.path("about").path("text").asText()).isEqualTo("Thing T-1 is called Widget.");
        assertThat(card.path("about").path("panels").get(0).path("description").asText()).isEqualTo("The terms of T-1.");
        assertThat(card.path("glossary").get(0).path("means").asText()).isEqualTo("What the thing is called.");
        assertThat(saved.path("problems")).isEmpty();
        assertThat(saved.path("lint")).isEmpty();
        assertThat(saved.path("coverage").path("covered").asInt()).isEqualTo(1);
        assertThat(rev("ana", id).path("about").asText()).isEqualTo(ABOUT);
    }

    @Test
    void thePreviewOfUnsavedTextSavesNothingAndReportsLocatedProblemsAndAnUnknownPanel() throws Exception {
        String id = design("ana", "about-b");
        String bad = ABOUT.replace("      terms:", "      nosuch:").replace("${$.label}", "${$.label");
        JsonNode r = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/about/preview").header("Authorization", as("ana"))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("text", bad)))));
        assertThat(r.path("problems")).extracting(x -> x.path("code").asText()).contains("DRS-2042");
        assertThat(r.path("problems").get(0).path("line").asInt()).isPositive();
        JsonNode ok = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/about/preview").header("Authorization", as("ana"))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("text", ABOUT.replace("      terms:", "      nosuch:"))))));
        assertThat(ok.path("lint")).extracting(x -> x.path("code").asText()).contains("DRS-2046");
        assertThat(rev("ana", id).path("about").asText()).isEmpty();
    }

    @Test
    void anAboutEditIsAStepOfTheLogSoUndoRedoAndAStaleRevisionWork() throws Exception {
        String id = design("ana", "about-c");
        int r0 = rev("ana", id).path("rev").asInt();
        JsonNode one = save("ana", id, r0, ABOUT);
        assertThat(one.path("rev").asInt()).isEqualTo(r0 + 1);
        mvc.perform(put("/api/v1/builder/designs/" + id + "/about").header("Authorization", as("ana")).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("baseRev", r0, "text", "about: 1\n")))).andExpect(status().isConflict());
        JsonNode two = save("ana", id, r0 + 1, ABOUT.replace("Thing ", "Item "));
        assertThat(two.path("opsCount").asInt()).isEqualTo(2);

        JsonNode undone = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/undo").header("Authorization", as("ana"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"baseRev\":" + (r0 + 2) + "}")));
        assertThat(undone.path("about").asText()).isEqualTo(ABOUT);
        assertThat(undone.path("yaml").asText()).contains("sutra: about-c");        // the Sutra is untouched by an About step
        JsonNode redone = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/redo").header("Authorization", as("ana"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"baseRev\":" + (r0 + 3) + "}")));
        assertThat(redone.path("about").asText()).contains("Item ");
        JsonNode versions = ok(mvc.perform(get("/api/v1/builder/designs/" + id + "/versions").header("Authorization", as("ana"))));
        assertThat(versions.path("versions")).hasSize(3);
    }

    @Test
    void theAboutTextExportsAsConfigAboutYamlWithTheFragmentAndImportsBack() throws Exception {
        String id = design("ana", "about-d");
        save("ana", id, rev("ana", id).path("rev").asInt(), ABOUT);
        byte[] zip = mvc.perform(get("/api/v1/builder/designs/" + id + "/export").header("Authorization", as("ana")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                files.put(e.getName(), new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertThat(files.get("about-d/config/about.yaml")).isEqualTo(ABOUT);
        assertThat(files.get("about-d/pack.yaml")).contains("about: config/about.yaml");
        assertThat(files.get("about-d/README.md")).contains("config/about.yaml");

        JsonNode imported = ok(mvc.perform(post("/api/v1/builder/designs/import").header("Authorization", as("bea")).contentType("application/zip").content(zip)));
        String copy = imported.path("designs").get(0).path("id").asText();
        assertThat(rev("bea", copy).path("about").asText()).isEqualTo(ABOUT);

        String plain = design("ana", "about-e");
        byte[] zip2 = mvc.perform(get("/api/v1/builder/designs/" + plain + "/export").header("Authorization", as("ana"))).andReturn().getResponse().getContentAsByteArray();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip2))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                assertThat(e.getName()).doesNotContain("about.yaml");
            }
        }
    }

    @Test
    void aDesignBelongsToItsOwnerAndADuplicateKeepsTheAboutText() throws Exception {
        String id = design("ana", "about-f");
        save("ana", id, rev("ana", id).path("rev").asInt(), ABOUT);
        mvc.perform(get("/api/v1/builder/designs/" + id + "/about").header("Authorization", as("bea"))).andExpect(status().isNotFound());
        JsonNode copy = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/duplicate").header("Authorization", as("ana"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"about-f2\"}")));
        assertThat(rev("ana", copy.path("id").asText()).path("about").asText()).isEqualTo(ABOUT);
    }
}
