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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
 * QA round 2, wave 1 (governance and safety): a Design never rewrites a version (M-1), notices that its base moved and rebases
 * (M-2), the synthetic sampler is bounded (S2-02), notes and Sutra are capped and counted (S2-03), scratch designs have a cap of
 * their own and a bulk delete (S2-04, UX-05), and the evidence and share payload hide what the viewer may not open (S2-10).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.rachana.studio-save=true", "drishti.governance.dir=${java.io.tmpdir}/drishti-w1-props-${random.uuid}",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.narrowauthor.kinds[0]=trade", "drishti.security.roles.narrowauthor.author=true",
        "drishti.identity.database-url=jdbc:sqlite:target/w1-api-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/w1-api-files-${random.uuid}", "drishti.builder.designs.max-notes-kb=1",
        "drishti.builder.designs.max-scratch=3", "drishti.builder.sample-max-items=10", "drishti.builder.sample-max-string=50"})
@AutoConfigureMockMvc
class DesignGovernanceWaveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PACK = Path.of(System.getProperty("java.io.tmpdir"), "drishti-w1-pack-" + System.nanoTime());
    private static final Path STUDIO = Path.of(System.getProperty("java.io.tmpdir"), "drishti-w1-studio-" + System.nanoTime());

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry r) {
        try {
            Files.createDirectories(PACK);
            Files.writeString(PACK.resolve("pk.v1.sutra.yaml"), sutra("pk", 1, "terms"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        r.add("drishti.rachana.dirs", () -> STUDIO + "," + PACK);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private static String sutra(String name, int version, String panel) {
        return "rachana: 1\nsutra: " + name + "\nversion: " + version + "\nmatch: { kind: w1-thing, priority: 100 }\ntitle: { id: $.thingId }\n"
                + "panels:\n  - id: " + panel + "\n    kind: kv\n    columns:\n      - { label: Label, bind: $.label }\n";
    }

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String who, String body) throws Exception {
        b = b.header("Authorization", who);
        return body == null ? mvc.perform(b) : mvc.perform(b.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return JSON.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private String text(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String create(String who, String json) throws Exception {
        return ok(send(post("/api/v1/builder/designs"), who, json)).path("id").asText();
    }

    private void approveLatest(String pid) throws Exception {
        ok(send(post("/api/v1/sutras/proposals/" + pid + "/approve"), as("rui", "approver"), "{}"));
    }

    private String proposalOf(String id, String who) throws Exception {
        return ok(send(post("/api/v1/builder/designs/" + id + "/propose"), who, "{}")).path("proposal").path("id").asText();
    }

    /** A live {@code name@1}, made the normal way: a design, a proposal, an approval by someone else. */
    private void publish(String name, String panel) throws Exception {
        String ana = as("ana", "author");
        String id = create(ana, "{\"name\":\"" + name + "\",\"kind\":\"w1-thing\",\"sutra\":" + JSON.writeValueAsString(sutra(name, 1, panel)) + "}");
        approveLatest(proposalOf(id, ana));
    }

    private JsonNode op(String who, String id, int rev, String ops) throws Exception {
        return ok(send(post("/api/v1/builder/designs/" + id + "/ops"), who, "{\"baseRev\":" + rev + ",\"ops\":" + ops + "}"));
    }

    /** M-1 and M-2 share one fixture: book@1 is live, two designs are made from it, one of them goes live as book@2. */
    @Test
    void aDesignPublishesTheNextVersionNeverRewritesOneAndRebasesWhenItsBaseMoves() throws Exception {
        String ana = as("ana", "author");
        publish("book", "terms");
        String original = text(send(get("/api/v1/sutras/book/1/source"), ana, null));

        // two designs made from book@1; D keeps a step that cannot survive the move, D2 one that can
        String d = create(ana, "{\"name\":\"d\",\"kind\":\"w1-thing\",\"base\":\"book@1\"}");
        int rev = ok(send(get("/api/v1/builder/designs/" + d), ana, null)).path("rev").asInt();
        op(ana, d, rev, "[{\"op\":\"setOption\",\"panel\":\"terms\",\"option\":\"title\",\"value\":\"Kept\"}]");
        String d2 = create(ana, "{\"name\":\"d2\",\"kind\":\"w1-thing\",\"base\":\"book@1\"}");
        int rev2 = ok(send(get("/api/v1/builder/designs/" + d2), ana, null)).path("rev").asInt();
        op(ana, d2, rev2, "[{\"op\":\"setTitle\",\"title\":{\"pill\":\"BK\",\"id\":\"$.thingId\"}}]");
        assertThat(ok(send(get("/api/v1/builder/designs/" + d), ana, null)).has("baseMoved")).isFalse();

        // E: a third design from book@1 replaces the panel and ships: it is book@2, and book@1 is untouched (M-1)
        String e = create(ana, "{\"name\":\"e\",\"kind\":\"w1-thing\",\"base\":\"book@1\"}");
        int rev3 = ok(send(get("/api/v1/builder/designs/" + e), ana, null)).path("rev").asInt();
        op(ana, e, rev3, "[{\"op\":\"addPanel\",\"id\":\"other\",\"kind\":\"kv\",\"options\":{\"columns\":[{\"label\":\"L\",\"bind\":\"$.label\"}]}},{\"op\":\"remove\",\"panel\":\"terms\"}]");
        JsonNode proposed = ok(send(post("/api/v1/builder/designs/" + e + "/propose"), ana, "{}"));
        assertThat(proposed.path("proposal").path("version").asInt()).isEqualTo(2);
        approveLatest(proposed.path("proposal").path("id").asText());
        assertThat(ok(send(get("/api/v1/builder/designs/" + e), ana, null)).path("status").asText()).isEqualTo("live(v2)");
        assertThat(text(send(get("/api/v1/sutras/book/1/source"), ana, null))).isEqualTo(original);
        // approving its own design must not flag it: the live version is the one this design published
        assertThat(ok(send(get("/api/v1/builder/designs/" + e), ana, null)).has("baseMoved")).isFalse();

        // M-2: D and D2 now say their base moved, on open and on check; rebase replays what can be replayed and reports the rest
        JsonNode moved = ok(send(get("/api/v1/builder/designs/" + d), ana, null));
        assertThat(moved.path("baseMoved").path("latest").asText()).isEqualTo("book@2");
        assertThat(moved.path("baseMoved").path("from").asInt()).isEqualTo(1);
        JsonNode conflict = ok(send(post("/api/v1/builder/designs/" + d + "/rebase"), ana, "{\"baseRev\":" + moved.path("rev").asInt() + "}"));
        assertThat(conflict.path("base").asText()).isEqualTo("book@2");
        assertThat(conflict.path("replayed").asInt()).isZero();
        assertThat(conflict.path("problems")).hasSize(1);
        assertThat(conflict.path("problems").get(0).path("message").asText()).contains("step 1").contains("book@2");
        assertThat(conflict.has("baseMoved")).isFalse();
        send(post("/api/v1/builder/designs/" + d + "/rebase"), ana, "{\"baseRev\":" + conflict.path("rev").asInt() + "}").andExpect(status().isBadRequest());

        JsonNode m2 = ok(send(get("/api/v1/builder/designs/" + d2), ana, null));
        assertThat(m2.path("baseMoved").path("to").asInt()).isEqualTo(2);
        send(post("/api/v1/builder/designs/" + d2 + "/rebase"), ana, "{\"baseRev\":" + (m2.path("rev").asInt() + 5) + "}").andExpect(status().isConflict());
        JsonNode replayed = ok(send(post("/api/v1/builder/designs/" + d2 + "/rebase"), ana, "{\"baseRev\":" + m2.path("rev").asInt() + "}"));
        assertThat(replayed.path("replayed").asInt()).isEqualTo(1);
        assertThat(replayed.path("problems")).isEmpty();
        assertThat(replayed.path("sutra").asText()).contains("pill: BK").contains("id: other");
        // proposing the rebased design publishes book@3, again without touching an existing version
        JsonNode p3 = ok(send(post("/api/v1/builder/designs/" + d2 + "/propose"), ana, "{}"));
        assertThat(p3.path("proposal").path("version").asInt()).isEqualTo(3);
        assertThat(text(send(get("/api/v1/sutras/book/2/source"), ana, null))).contains("other");
    }

    /** M-1: a design that is not based on an existing name@version is refused at propose time, naming a pack when a pack owns it. */
    @Test
    void aDesignOverAnExistingVersionIsRefusedAtProposeTime() throws Exception {
        String ana = as("ana", "author");
        publish("taken", "terms");
        String id = create(ana, "{\"name\":\"fresh\",\"kind\":\"w1-thing\",\"sutra\":" + JSON.writeValueAsString(sutra("taken", 1, "different")) + "}");
        String body = send(post("/api/v1/builder/designs/" + id + "/propose"), ana, "{}").andExpect(status().isUnprocessableEntity()).andReturn().getResponse()
                .getContentAsString();
        assertThat(body).contains("DRS-2028").contains("taken@1").contains("already live");
        String pk = create(ana, "{\"name\":\"pk\",\"kind\":\"w1-thing\",\"sutra\":" + JSON.writeValueAsString(sutra("pk", 1, "changed")) + "}");
        assertThat(send(post("/api/v1/builder/designs/" + pk + "/propose"), ana, "{}").andExpect(status().isUnprocessableEntity()).andReturn().getResponse()
                .getContentAsString()).contains("DRS-2028").contains("a pack owns it");
        assertThat(ok(send(get("/api/v1/builder/designs/" + pk), ana, null)).path("status").asText()).isEqualTo("draft");
    }

    /** S2-02: a schema asking for billions of items or characters is clamped with a problem and never allocated. */
    @Test
    void aHugeSchemaIsClampedWithAProblem() throws Exception {
        String ana = as("ana", "author");
        String id = create(ana, "{\"name\":\"synth\",\"kind\":\"w1-thing\"}");
        String schema = "{\"type\":\"object\",\"properties\":{\"rows\":{\"type\":\"array\",\"minItems\":2000000000,\"items\":{\"type\":\"string\"}},"
                + "\"name\":{\"type\":\"string\",\"minLength\":2000000000}}}";
        JsonNode out = ok(send(post("/api/v1/builder/designs/" + id + "/samples"), ana, "{\"schema\":" + schema + ",\"count\":2}"));
        assertThat(out.path("samples")).hasSize(2);
        assertThat(out.path("problems")).hasSize(2);
        assertThat(out.path("problems").toString()).contains("sample-max-items").contains("sample-max-string");
        JsonNode doc = JSON.readTree(mvc.perform(get("/api/v1/builder/designs/" + id + "/samples/document").param("name", "synthetic-1").header("Authorization", ana))
                .andReturn().getResponse().getContentAsString());
        assertThat(doc.path("rows")).hasSize(10);
        assertThat(doc.path("name").asText()).hasSize(50);
    }

    /** S2-03 and S2-08: notes and Sutra are capped (DRS-5005), counted in the design's bytes, and a long text says so, not "nested too deep". */
    @Test
    void notesAndSutraAreCappedAndCounted() throws Exception {
        String ana = as("ana", "author");
        String id = create(ana, "{\"name\":\"big\",\"kind\":\"w1-thing\"}");
        JsonNode ok = ok(send(patch("/api/v1/builder/designs/" + id), ana, "{\"notes\":\"" + "n".repeat(900) + "\"}"));
        assertThat(ok.path("bytes").asLong()).isEqualTo(900);
        String refused = send(patch("/api/v1/builder/designs/" + id), ana, "{\"notes\":\"" + "n".repeat(2000) + "\"}").andExpect(status().isPayloadTooLarge())
                .andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("DRS-5005").contains("max-notes-kb");
        String sutra = send(patch("/api/v1/builder/designs/" + id), ana, "{\"sutra\":\"#" + "x".repeat(1_100_000) + "\"}").andExpect(status().isPayloadTooLarge())
                .andReturn().getResponse().getContentAsString();
        assertThat(sutra).contains("max-sutra-kb").doesNotContain("nested");
        String huge = send(patch("/api/v1/builder/designs/" + id), ana, "{\"sutra\":\"#" + "x".repeat(21_000_000) + "\"}").andExpect(status().isPayloadTooLarge())
                .andReturn().getResponse().getContentAsString();
        assertThat(huge).contains("too long").doesNotContain("nested");
    }

    /** S2-04 and UX-05: scratch designs are capped separately (the oldest goes first), do not fill the named quota, and can be deleted at once. */
    @Test
    void scratchDesignsHaveTheirOwnCapAndABulkDelete() throws Exception {
        String zed = as("zed", "author");
        String first = create(zed, "{\"kind\":\"w1-thing\"}");
        for (int i = 0; i < 5; i++) {
            create(zed, "{\"kind\":\"w1-thing\"}");
        }
        JsonNode list = ok(send(get("/api/v1/builder/designs"), zed, null));
        assertThat(list.path("designs")).hasSize(3);
        assertThat(list.path("designs").toString()).doesNotContain(first);
        assertThat(list.path("limits").path("maxScratch").asInt()).isEqualTo(3);
        create(zed, "{\"name\":\"named\",\"kind\":\"w1-thing\"}");
        send(delete("/api/v1/builder/designs"), zed, null).andExpect(status().isBadRequest());
        assertThat(ok(send(delete("/api/v1/builder/designs?scratch=true"), zed, null)).path("deleted").asInt()).isEqualTo(3);
        assertThat(ok(send(get("/api/v1/builder/designs"), zed, null)).path("designs")).hasSize(1);
    }

    /** S2-10: a reference sample's name (it carries an entity id) and the notes reach only those who may see them. */
    @Test
    void referenceNamesAndNotesAreHiddenFromWhoMayNotSeeThem() throws Exception {
        String ana = as("ana", "author");
        String id = create(ana, "{\"name\":\"refs\",\"kind\":\"w1-thing\",\"notes\":\"private reviewer note\",\"sutra\":"
                + JSON.writeValueAsString(sutra("refs", 1, "terms")) + "}");
        ok(send(post("/api/v1/builder/designs/" + id + "/samples"), ana, "{\"samples\":[{\"name\":\"doc.json\",\"document\":{\"thingId\":\"T\",\"label\":\"x\"}}]}"));
        ok(send(post("/api/v1/builder/designs/" + id + "/samples"), ana, "{\"refs\":{\"kind\":\"w1-private\",\"ids\":[\"MX-20000001\"]}}"));
        String pid = proposalOf(id, ana);

        String author = ok(send(get("/api/v1/sutras/proposals/" + pid), ana, null)).toString();
        assertThat(author).contains("w1-private MX-20000001").contains("private reviewer note");
        String approver = ok(send(get("/api/v1/sutras/proposals/" + pid), as("rui", "approver"), null)).toString();
        assertThat(approver).contains("private reviewer note");
        String other = ok(send(get("/api/v1/sutras/proposals/" + pid), as("nia", "narrowauthor"), null)).toString();
        assertThat(other).doesNotContain("MX-20000001").doesNotContain("private reviewer note").contains("w1-private (reference)").contains("doc.json")
                .doesNotContain("refSamples");

        String token = ok(send(post("/api/v1/builder/designs/" + id + "/share"), ana, null)).path("token").asText();
        String shared = ok(mvc.perform(get("/api/v1/builder/designs/shared/" + id).param("token", token).header("Authorization", as("nia", "narrowauthor")))).toString();
        assertThat(shared).doesNotContain("MX-20000001").contains("w1-private (reference)");
        String owner = ok(mvc.perform(get("/api/v1/builder/designs/shared/" + id).param("token", token).header("Authorization", as("rui", "approver")))).toString();
        assertThat(owner).contains("MX-20000001");
    }

    /** S2-14: four-eyes compares user names the way sign-in canonicalises them. */
    @Test
    void fourEyesComparesCanonicalUserNames() throws Exception {
        String ana = as("ana", "author");
        String id = create(ana, "{\"name\":\"eyes\",\"kind\":\"w1-thing\",\"sutra\":" + JSON.writeValueAsString(sutra("eyes", 1, "terms")) + "}");
        String pid = proposalOf(id, ana);
        send(post("/api/v1/sutras/proposals/" + pid + "/approve"), as("ANA", "approver"), "{}").andExpect(status().isForbidden());
        approveLatest(pid);
    }
}
