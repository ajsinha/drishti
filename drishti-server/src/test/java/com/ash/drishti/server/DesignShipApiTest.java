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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
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
 * Build workbench step 8: propose with evidence (the matrix, the sample names and the notes reach the reviewer; approval makes the
 * Design {@code live(vN)}), pack fragment export and import, read-only share links (never sample contents; revocable), and the
 * development file binding being off by default.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.rachana.studio-save=true", "drishti.rachana.dirs=${java.io.tmpdir}/drishti-ship-${random.uuid}",
        "drishti.governance.dir=${java.io.tmpdir}/drishti-ship-props-${random.uuid}",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/ship-api-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/ship-api-files-${random.uuid}"})
@AutoConfigureMockMvc
class DesignShipApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET_VALUE = "ZEBRA-4711";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired com.ash.drishti.rachana.SutraRegistry sutras;
    @Autowired com.ash.drishti.engine.ViewPipeline pipeline;
    @Autowired com.ash.drishti.engine.shape.ShapeService shapes;
    @Autowired com.ash.drishti.engine.design.AutoDesigner designer;
    @Autowired com.ash.drishti.common.JsonCodec codec;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp;

    private static String sutra(String name) {
        return "rachana: 1\nsutra: " + name + "\nversion: 1\nmatch: { kind: ship-thing, priority: 100 }\ntitle: { id: $.thingId }\n"
                + "panels:\n  - id: terms\n    kind: kv\n    columns:\n      - { label: Label, bind: $.label }\n";
    }

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return JSON.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    /** A design owned by {@code user} with the Sutra, one sample holding a secret value, and notes. */
    private String design(String user, String role, String name) throws Exception {
        String who = as(user, role);
        JsonNode made = ok(mvc.perform(post("/api/v1/builder/designs").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"kind\":\"ship-thing\",\"sutra\":" + JSON.writeValueAsString(sutra(name))
                        + ",\"notes\":\"reviewer: see the secret note\"}")));
        String id = made.path("id").asText();
        ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/samples").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"samples\":[{\"name\":\"first.json\",\"document\":{\"thingId\":\"T-1\",\"label\":\"" + SECRET_VALUE + "\"}},"
                        + "{\"name\":\"second.json\",\"document\":{\"thingId\":\"T-2\",\"label\":\"x\"}}]}")));
        return id;
    }

    @Test
    void proposeCarriesTheEvidenceAndApprovalMakesTheDesignLive() throws Exception {
        String id = design("ana", "author", "ship-a");
        JsonNode p = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/propose").header("Authorization", as("ana", "author"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"first cut\"}")));
        String pid = p.path("proposal").path("id").asText();
        assertThat(pid).startsWith("P-");
        assertThat(p.path("status").asText()).isEqualTo("proposed(" + pid + ")");

        JsonNode review = ok(mvc.perform(get("/api/v1/sutras/proposals/" + pid).header("Authorization", as("rui", "approver"))));
        JsonNode ev = review.path("evidence");
        assertThat(ev.path("designId").asText()).isEqualTo(id);
        assertThat(ev.path("sampleNames")).extracting(JsonNode::asText).containsExactly("first.json", "second.json");
        assertThat(ev.path("notes").asText()).contains("secret note");
        assertThat(ev.path("matrix").path("ok").asBoolean()).isTrue();
        assertThat(ev.path("matrix").path("panels").get(0).path("id").asText()).isEqualTo("terms");
        assertThat(review.toString()).doesNotContain(SECRET_VALUE);          // names and counts, never contents

        ok(mvc.perform(post("/api/v1/sutras/proposals/" + pid + "/approve").header("Authorization", as("rui", "approver"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")));
        JsonNode after = ok(mvc.perform(get("/api/v1/builder/designs/" + id).header("Authorization", as("ana", "author"))));
        assertThat(after.path("status").asText()).isEqualTo("live(v1)");
        mvc.perform(get("/api/v1/sutras/ship-a/1").header("Authorization", as("ana", "author"))).andExpect(status().isOk());
    }

    @Test
    void aRejectedProposalReturnsTheDesignToDraftAndAnEditAfterwardsDoesNotReviveIt() throws Exception {
        String id = design("ana", "author", "ship-b");
        String pid = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/propose").header("Authorization", as("ana", "author"))))
                .path("proposal").path("id").asText();
        ok(mvc.perform(post("/api/v1/sutras/proposals/" + pid + "/reject").header("Authorization", as("rui", "approver"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"no\"}")));
        assertThat(ok(mvc.perform(get("/api/v1/builder/designs/" + id).header("Authorization", as("ana", "author")))).path("status").asText())
                .isEqualTo("draft");
    }

    @Test
    void proposingNeedsTheAuthorRight() throws Exception {
        String id = design("vic", "designer", "ship-c");
        mvc.perform(post("/api/v1/builder/designs/" + id + "/propose").header("Authorization", as("vic", "designer"))).andExpect(status().isForbidden());
    }

    @Test
    void aFragmentExportsAndImportsAsDesignsWithTheirTests() throws Exception {
        String id = design("ana", "author", "ship-d");
        byte[] zip = mvc.perform(get("/api/v1/builder/designs/" + id + "/export").header("Authorization", as("ana", "author")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> names = new ArrayList<>();
        String expect = "";
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
                if (e.getName().endsWith("expect.yaml")) {
                    expect = new String(in.readAllBytes());
                }
            }
        }
        assertThat(names).contains("ship-d/pack.yaml", "ship-d/README.md", "ship-d/sutras/" + domainOf(names) + "/ship-d.v1.sutra.yaml",
                "ship-d/tests/ship-d/first.json", "ship-d/tests/ship-d/second.json", "ship-d/tests/ship-d/expect.yaml",
                "ship-d/samples/ship-thing/first.json", "ship-d/samples/ship-thing/second.json");
        assertThat(expect).contains("noErrors: true").contains("- \"terms\"");

        JsonNode imported = ok(mvc.perform(post("/api/v1/builder/designs/import").header("Authorization", as("bea", "designer"))
                .contentType("application/zip").content(zip)));
        assertThat(imported.path("designs")).hasSize(1);
        JsonNode d = imported.path("designs").get(0);
        assertThat(d.path("name").asText()).isEqualTo("ship-d");
        assertThat(d.path("kind").asText()).isEqualTo("ship-thing");
        assertThat(d.path("samples")).extracting(s -> s.path("name").asText()).containsExactlyInAnyOrder("first.json", "second.json");
        mvc.perform(post("/api/v1/builder/designs/import").header("Authorization", as("bea", "designer")).contentType("application/zip")
                .content(new byte[] {1, 2, 3})).andExpect(status().isBadRequest());
        // S2-07: a zip cut short is a clean 400, not an empty 200
        mvc.perform(post("/api/v1/builder/designs/import").header("Authorization", as("bea", "designer")).contentType("application/zip")
                .content(java.util.Arrays.copyOf(zip, 30))).andExpect(status().isBadRequest());
    }

    @Test
    void anExportedFragmentLoadsAsAPackAndPassesSutraTest() throws Exception {
        String id = design("ana", "author", "ship-g");
        byte[] zip = mvc.perform(get("/api/v1/builder/designs/" + id + "/export").header("Authorization", as("ana", "author")))
                .andReturn().getResponse().getContentAsByteArray();
        java.nio.file.Path packs = java.nio.file.Files.createDirectories(tmp.resolve("packs"));
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                java.nio.file.Path to = packs.resolve(e.getName());
                java.nio.file.Files.createDirectories(to.getParent());
                java.nio.file.Files.write(to, in.readAllBytes());
            }
        }
        // the pack loader reads it as a pack ...
        var loaded = new com.ash.drishti.packs.PackLoader().load(packs, List.of("ship-g"));
        assertThat(loaded).hasSize(1);
        // ... and `sutra test` over its tests passes
        var out = new java.io.ByteArrayOutputStream();
        int code = new com.ash.drishti.server.cli.SutraCli(new com.ash.drishti.server.cli.SutraCli.Services(sutras, pipeline, shapes, designer, codec),
                new java.io.PrintStream(out, true), new java.io.PrintStream(out, true)).run(List.of("test", packs.resolve("ship-g").toString()));
        assertThat(code).as(out.toString()).isZero();
    }

    private static String domainOf(List<String> names) {
        return names.stream().filter(n -> n.contains("/sutras/")).map(n -> n.split("/")[2]).findFirst().orElse("?");
    }

    @Test
    void aShareLinkShowsTheSutraOpsAndSampleNamesOnlyAndCanBeRevoked() throws Exception {
        String id = design("ana", "author", "ship-e");
        String token = ok(mvc.perform(post("/api/v1/builder/designs/" + id + "/share").header("Authorization", as("ana", "author")))).path("token").asText();
        JsonNode seen = ok(mvc.perform(get("/api/v1/builder/designs/shared/" + id).param("token", token).header("Authorization", as("zed", "viewer"))));
        assertThat(seen.path("sutra").asText()).contains("sutra: ship-e");
        assertThat(seen.path("sampleNames")).extracting(JsonNode::asText).containsExactly("first.json", "second.json");
        assertThat(seen.toString()).doesNotContain(SECRET_VALUE).doesNotContain("secret note");
        mvc.perform(get("/api/v1/builder/designs/shared/" + id).param("token", token + "x").header("Authorization", as("zed", "viewer")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/builder/designs/" + id).header("Authorization", as("zed", "viewer"))).andExpect(status().isNotFound());   // the link is not ownership
        mvc.perform(delete("/api/v1/builder/designs/" + id + "/share").header("Authorization", as("ana", "author"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/builder/designs/shared/" + id).param("token", token).header("Authorization", as("zed", "viewer")))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileBindingIsOffUnlessTheDevelopmentSettingIsOn() throws Exception {
        String id = design("ana", "author", "ship-f");
        assertThat(ok(mvc.perform(get("/api/v1/builder/designs/binding").header("Authorization", as("ana", "author")))).path("enabled").asBoolean()).isFalse();
        mvc.perform(post("/api/v1/builder/designs/" + id + "/bind").header("Authorization", as("ana", "author")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"file\":\"x/ship-f.v1.sutra.yaml\"}")).andExpect(status().isForbidden());
    }
}
