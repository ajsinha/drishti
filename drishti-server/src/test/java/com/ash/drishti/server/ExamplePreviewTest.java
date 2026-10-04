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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every documented example previews without a panel error, and a pasted document previews its {@code source:} panels with the
 * linked entities' data, with the banking packs that hold those entities enabled.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=market-risk,counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/example-preview-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/example-preview-files-${random.uuid}"})
@AutoConfigureMockMvc
class ExamplePreviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EXAMPLES = Path.of("..", "docs", "guides", "examples");

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
                throw new java.io.UncheckedIOException(e);
            }
        }
        return b.toString();
    }

    private static String example(String name, String ext) throws IOException {
        return Files.readString(EXAMPLES.resolve(name + ext));
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
                assertThat(error).as(name + " panel " + p.path("id").asText()).isEmpty();
            }
            if ("all-panels-showcase".equals(name)) {
                assertThat(view.path("panels")).hasSize(22);   // twenty-one kinds, the table twice
            }
            call("DELETE", "/api/v1/builder/designs/" + id, who, null).andExpect(status().isNoContent());
            assertThat(example(name, ".sutra.yaml") + example(name, ".json") + example(name, ".md")).as(name + " is unchanged").isEqualTo(before);
        }
    }

    @Test
    void aPastedDocumentInStudioFetchesTheEntitiesItsSourcePanelsLinkTo() throws Exception {
        String who = as("stu", "author");
        ObjectNode body = JSON.createObjectNode().put("yaml", example("linked-sources", ".sutra.yaml")).put("kind", "trade");
        body.set("document", JSON.readTree(example("linked-sources", ".json")));
        JsonNode view = ok(call("POST", "/api/v1/studio/preview", who, body.toString()));
        for (JsonNode p : view.path("panels")) {
            assertThat(p.path("error").asText("")).as(p.path("id").asText()).isEmpty();
            assertThat(p.path("empty").asBoolean(false)).as(p.path("id").asText() + " has the linked entity's data").isFalse();
        }
    }
}
