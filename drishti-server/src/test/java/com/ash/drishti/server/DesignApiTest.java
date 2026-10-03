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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * {@code /api/v1/builder/designs}: persisted Designs with owner-only access, samples (documents, store references, synthetic
 * ones), quotas answering DRS-5005, shape, preview and auto-design; every documented example opens as a Design copy.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.designer.kinds[0]=trade", "drishti.security.roles.designer.kinds[1]=netting-set",
        "drishti.identity.database-url=jdbc:sqlite:target/design-api-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/design-api-files-${random.uuid}",
        "drishti.builder.designs.max-per-user=3", "drishti.builder.designs.max-samples=4", "drishti.builder.designs.max-mb=1",
        "drishti.builder.max-file-mb=1"})
@AutoConfigureMockMvc
class DesignApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EXAMPLES = Path.of("..", "docs", "guides", "examples");
    private static final Path DIR = sutraDir();
    private static final String SOURCED = """
            rachana: 1
            sutra: ns-sourced
            version: 1
            match: { kind: netting-set, priority: 100 }
            title: { pill: NS, id: $.nettingSetId }
            panels:
              - id: srcline
                kind: line
                title: Curve line
                source: "link('USD-SOFR', 'curve')"
                rows: points
                x: tenor
                y: rate
            """;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry r) {
        r.add("drishti.rachana.dirs", DIR::toString);
    }

    private static Path sutraDir() {
        try {
            Path d = Files.createTempDirectory("drishti-design-api-");
            Files.writeString(d.resolve("ns-sourced.v1.sutra.yaml"), SOURCED);
            return d;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private ResultActions call(String method, String url, String who, String body) throws Exception {
        java.net.URI uri = java.net.URI.create(url);
        var b = switch (method) {
            case "GET" -> get(uri);
            case "POST" -> post(uri);
            case "PATCH" -> patch(uri);
            default -> delete(uri);
        };
        b.header("Authorization", who);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(b);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return JSON.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private String create(String who, String body) throws Exception {
        return ok(call("POST", "/api/v1/builder/designs", who, body)).path("id").asText();
    }

    private static String samples(String... nameAndJson) {
        ObjectNode b = JSON.createObjectNode();
        var arr = b.putArray("samples");
        for (int i = 0; i < nameAndJson.length; i += 2) {
            try {
                arr.addObject().put("name", nameAndJson[i]).set("document", JSON.readTree(nameAndJson[i + 1]));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return b.toString();
    }

    private static String example(String name, String ext) throws IOException {
        return Files.readString(EXAMPLES.resolve(name + ext));
    }

    @Test
    void aDesignIsCreatedReadRenamedAndDeletedAndOnlyItsOwnerSeesIt() throws Exception {
        String ann = as("ann", "author"), bob = as("bob", "author");
        String id = create(ann, "{\"name\":\"Rates\",\"kind\":\"trade\",\"notes\":\"n\"}");
        JsonNode d = ok(call("GET", "/api/v1/builder/designs/" + id, ann, null));
        assertThat(d.path("name").asText()).isEqualTo("Rates");
        assertThat(d.path("owner").isMissingNode()).isTrue();
        assertThat(d.path("status").asText()).isEqualTo("draft");
        assertThat(d.path("scratch").asBoolean()).isFalse();
        ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", ann, samples("t1.json", "{\"tradeId\":\"T1\"}")));
        assertThat(ok(call("PATCH", "/api/v1/builder/designs/" + id, ann, "{\"name\":\"Rates v2\",\"sutra\":\"rachana: 1\\n\"}")).path("rev").asInt())
                .isEqualTo(1);
        // everyone else gets 404 DRS-5006, as for a design that does not exist, on every verb
        String base = "/api/v1/builder/designs/" + id;
        for (String[] c : new String[][] {{"GET", base}, {"PATCH", base}, {"DELETE", base}, {"POST", base + "/duplicate"},
                {"POST", base + "/samples"}, {"DELETE", base + "/samples?name=t1.json"}, {"GET", base + "/samples/document?name=t1.json"},
                {"POST", base + "/shape"}, {"GET", base + "/preview"}, {"POST", base + "/autodesign"}}) {
            call(c[0], c[1], bob, "{\"name\":\"x\",\"samples\":[]}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-5006"));
        }
        assertThat(ok(call("GET", "/api/v1/builder/designs", bob, null)).path("designs")).isEmpty();
        JsonNode mine = ok(call("GET", "/api/v1/builder/designs", ann, null));
        assertThat(mine.path("designs")).hasSize(1);
        assertThat(mine.path("designs").get(0).path("name").asText()).isEqualTo("Rates v2");
        assertThat(mine.path("limits").path("maxPerUser").asInt()).isEqualTo(3);
        assertThat(ok(call("GET", base + "/samples/document?name=t1.json", ann, null)).path("tradeId").asText()).isEqualTo("T1");
        call("DELETE", base, ann, null).andExpect(status().isNoContent());
        call("GET", base, ann, null).andExpect(status().isNotFound());
    }

    @Test
    void designingIsOpenToARoleWithoutAnyRights() throws Exception {
        String dee = as("dee", "designer");
        String id = create(dee, "{}");
        assertThat(ok(call("GET", "/api/v1/builder/designs/" + id, dee, null)).path("scratch").asBoolean()).isTrue();
        call("GET", "/api/v1/builder/designs", "", null).andExpect(status().isUnauthorized());
    }

    @Test
    void limitsAnswerWithDrs5005() throws Exception {
        String lee = as("lee", "author");
        String id = create(lee, "{\"name\":\"A\"}");
        create(lee, "{\"name\":\"B\"}");
        create(lee, "{\"name\":\"C\"}");
        call("POST", "/api/v1/builder/designs", lee, "{\"name\":\"D\"}").andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("DRS-5005"));
        call("POST", "/api/v1/builder/designs/" + id + "/samples", lee, samples("1", "{}", "2", "{}", "3", "{}", "4", "{}", "5", "{}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("DRS-5005"));
        assertThat(ok(call("GET", "/api/v1/builder/designs/" + id, lee, null)).path("samples")).isEmpty();          // nothing of it kept
        String big = "\"" + "x".repeat(600_000) + "\"";
        ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", lee, samples("big1", big)));
        call("POST", "/api/v1/builder/designs/" + id + "/samples", lee, samples("big2", big)).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("max-mb")));
        call("POST", "/api/v1/builder/designs/" + id + "/samples", lee, "{\"samples\":[{\"name\":\"nodoc\"}]}").andExpect(status().isBadRequest());
        call("DELETE", "/api/v1/builder/designs/" + id + "/samples?name=big1", lee, null).andExpect(status().isOk());
        assertThat(ok(call("GET", "/api/v1/builder/designs/" + id, lee, null)).path("samples")).isEmpty();
    }

    @Test
    void aSchemaGivesLabelledSyntheticSamplesAndStoredEntitiesAreKeptAsReferences() throws Exception {
        String ann = as("ann2", "author");
        String id = create(ann, "{\"name\":\"From schema\",\"kind\":\"trade\"}");
        JsonNode d = ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", ann,
                "{\"schema\":{\"type\":\"object\",\"properties\":{\"tradeId\":{\"type\":\"string\"},\"qty\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":9}}},\"count\":3}"));
        assertThat(d.path("samples")).hasSize(3);
        for (JsonNode s : d.path("samples")) {
            assertThat(s.path("type").asText()).isEqualTo("synthetic");
            assertThat(s.path("synthetic").asBoolean()).isTrue();
        }
        JsonNode doc = ok(call("GET", "/api/v1/builder/designs/" + id + "/samples/document?name=synthetic-2", ann, null));
        assertThat(doc.path("qty").asInt()).isBetween(1, 9);
        JsonNode shape = ok(call("POST", "/api/v1/builder/designs/" + id + "/shape", ann, null));
        assertThat(shape.path("samples").asInt()).isEqualTo(3);
        assertThat(shape.path("report").path("paths").size()).isGreaterThanOrEqualTo(2);
        JsonNode refd = ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", ann, "{\"refs\":{\"kind\":\"trade\",\"ids\":[\"IRS-48213\"]}}"));
        JsonNode ref = refd.path("samples").get(3);
        assertThat(ref.path("type").asText()).isEqualTo("ref");
        assertThat(ref.path("ref").path("id").asText()).isEqualTo("IRS-48213");
        call("GET", "/api/v1/builder/designs/" + id + "/samples/document?name=" + ref.path("name").asText().replace(" ", "%20"), ann, null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void aReferenceToAKindTheUserMayNoLongerOpenPreviewsAsNoAccess() throws Exception {
        String rita = as("rita", "risk");
        String id = create(rita, "{\"name\":\"Curves\",\"kind\":\"netting-set\",\"sutra\":" + JSON.writeValueAsString(SOURCED) + "}");
        ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", rita, "{\"refs\":{\"kind\":\"curve\",\"ids\":[\"USD-SOFR\"]}}"));
        assertThat(ok(call("GET", "/api/v1/builder/designs/" + id + "/preview?sample=curve%20USD-SOFR", rita, null)).toString()).contains("3.93");
        // the same person with a role that may not open curves any more
        String lowered = as("rita", "designer");
        call("GET", "/api/v1/builder/designs/" + id + "/preview?sample=curve%20USD-SOFR", lowered, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-5002")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no access")));
        // and a reference of such a kind cannot be added by someone who may not open it
        call("POST", "/api/v1/builder/designs/" + id + "/samples", lowered, "{\"refs\":{\"kind\":\"curve\",\"ids\":[\"USD-SOFR\"]}}")
                .andExpect(status().isForbidden());
        // shape and auto-design skip it and say why, rather than showing its values
        ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", lowered, samples("a.json", "{\"nettingSetId\":\"N1\",\"trades\":2}")));
        JsonNode shape = ok(call("POST", "/api/v1/builder/designs/" + id + "/shape", lowered, null));
        assertThat(shape.path("skipped").get(0).path("reason").asText()).isEqualTo("no access");
        assertThat(shape.toString()).doesNotContain("3.93");
    }

    @Test
    void autodesignWritesTheSutraAndARevisionAndEverySamplePreviews() throws Exception {
        String ann = as("ann3", "author");
        String id = create(ann, "{\"name\":\"Showcase\",\"kind\":\"trade\"}");
        call("POST", "/api/v1/builder/designs/" + id + "/autodesign", ann, null).andExpect(status().isBadRequest());     // no samples yet
        ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", ann, samples("s.json", example("all-panels-showcase", ".json"))));
        JsonNode draft = ok(call("POST", "/api/v1/builder/designs/" + id + "/autodesign", ann, null));
        assertThat(draft.path("yaml").asText()).contains("rachana: 1", "panels:");
        assertThat(draft.path("rev").asInt()).isEqualTo(1);
        JsonNode d = ok(call("GET", "/api/v1/builder/designs/" + id, ann, null));
        assertThat(d.path("sutra").asText()).isEqualTo(draft.path("yaml").asText());
        JsonNode view = ok(call("GET", "/api/v1/builder/designs/" + id + "/preview", ann, null));
        assertThat(view.path("panels").size()).isGreaterThanOrEqualTo(2);
        assertThat(ok(call("POST", "/api/v1/builder/designs/" + id + "/autodesign", ann, null)).path("rev").asInt()).isEqualTo(1);   // same text, same revision
    }

    @Test
    void everyExampleOpensAsADesignCopyAndPreviewsWithNoPanelErrorsAndTheExampleFilesAreUntouched() throws Exception {
        List<String> names = new ArrayList<>();
        try (var files = Files.list(EXAMPLES)) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sutra.yaml")).forEach(n -> names.add(n.replace(".sutra.yaml", "")));
        }
        assertThat(names).hasSizeGreaterThanOrEqualTo(10);
        String who = as("exa", "author");
        Pattern kind = Pattern.compile("^match:\\s*\\{[^}]*?\\bkind:\\s*([A-Za-z0-9_-]+)", Pattern.MULTILINE);
        for (String name : names) {
            String before = example(name, ".sutra.yaml") + example(name, ".json") + example(name, ".md");
            Matcher m = kind.matcher(example(name, ".sutra.yaml"));
            ObjectNode b = JSON.createObjectNode().put("name", name + " (copy)").put("sutra", example(name, ".sutra.yaml"))
                    .put("notes", example(name, ".md")).put("kind", m.find() ? m.group(1) : "trade");
            String id = create(who, b.toString());
            ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", who, samples(name + ".json", example(name, ".json"))));
            JsonNode d = ok(call("GET", "/api/v1/builder/designs/" + id, who, null));
            assertThat(d.path("sutra").asText()).as(name).isEqualTo(example(name, ".sutra.yaml"));
            assertThat(d.path("notes").asText()).as(name).isEqualTo(example(name, ".md"));
            JsonNode view = ok(call("GET", "/api/v1/builder/designs/" + id + "/preview", who, null));
            assertThat(view.path("panels").size()).as(name).isGreaterThanOrEqualTo(1);
            for (JsonNode p : view.path("panels")) {
                String error = p.path("error").asText("");
                if ("linked-sources".equals(name) && error.startsWith("waiting for")) {
                    continue;       // that example reads other entities of the live demo packs, which a pasted document does not fetch
                }
                assertThat(error).as(name + " panel " + p.path("id").asText()).isEmpty();
            }
            if ("all-panels-showcase".equals(name)) {
                assertThat(view.path("panels")).hasSize(21);
            }
            call("DELETE", "/api/v1/builder/designs/" + id, who, null).andExpect(status().isNoContent());
            assertThat(example(name, ".sutra.yaml") + example(name, ".json") + example(name, ".md")).as(name + " is unchanged").isEqualTo(before);
        }
    }

    @Test
    void anExistingSutraOfTheRegistryOpensAsABaseCopy() throws Exception {
        String ann = as("ann4", "author");
        String id = create(ann, "{\"name\":\"From registry\",\"base\":\"ns-sourced@1\"}");
        JsonNode d = ok(call("GET", "/api/v1/builder/designs/" + id, ann, null));
        assertThat(d.path("base").asText()).isEqualTo("ns-sourced@1");
        assertThat(d.path("sutra").asText()).contains("sutra: ns-sourced");
        call("POST", "/api/v1/builder/designs", ann, "{\"base\":\"nope@9\"}").andExpect(status().isNotFound());
    }
}
