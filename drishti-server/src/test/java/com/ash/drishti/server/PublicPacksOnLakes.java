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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A server as the user's cluster runs: the demo connector OFF, every page read from the demo lakes, here built in-test from the
 * committed test data ({@link PublicPackLakes}) for the us-equities and gleif packs and the packs they link to (world-economy and
 * news-and-markets, static reference data). Nothing is served from a pack's samples. The subclasses share one context.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false",
        "drishti.packs.enabled=core,world-economy,news-and-markets,us-equities,gleif",
        "drishti.sources.plugins.file.enabled=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
abstract class PublicPacksOnLakes {

    /** The connector files written from the packs' templates at start: one folder per test fork (the forks run in parallel). */
    static final String CONNECTORS = TestRun.root().resolve("public-pack-connectors").toString();

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) {
        Path lake = PublicPackLakes.lake("world-economy", "news-and-markets", "us-equities", "gleif");
        try {                                                      // the connector files are written from the packs' templates at start
            Path dir = Path.of(CONNECTORS);
            if (Files.isDirectory(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        r.add("DRISHTI_DELTA_ROOT", lake::toString);
        r.add("drishti.sources.connectors-dir", () -> CONNECTORS);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    String auth() {
        return "Bearer " + tokens.mint("u-analyst", List.of("full"), 300);
    }

    JsonNode getJson(String path, String... params) throws Exception {
        var req = get(path).header("Authorization", auth());
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    JsonNode view(String kind, String id) throws Exception {
        return getJson("/api/v1/views/" + kind + "/" + id);
    }

    JsonNode click(String kind, String id, String panel, int row) throws Exception {
        String body = json.writeValueAsString(Map.of("panel", panel, "event", "open", "row", row, "selections", List.of()));
        return json.readTree(mvc.perform(post("/api/v1/views/" + kind + "/" + id + "/events").header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    /** The ids the type-ahead offers for what was typed. */
    List<String> suggested(String typed) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        getJson("/api/v1/command/suggest", "q", typed).forEach(s -> out.add(s.path("id").asText(s.path("text").asText())));
        return out;
    }

    /** A search form's criteria compiled and run: the ids it found. */
    List<String> formSearch(String pack, String kind, String criteria) throws Exception {
        var response = mvc.perform(post("/api/v1/forms/" + pack + "/" + kind + "/compile").header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"criteria\":[" + criteria + "]}")).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        if (response.getStatus() != 200) {
            throw new AssertionError("FORM " + pack + " " + kind + " " + criteria + ": " + response.getStatus() + " " + body);
        }
        JsonNode compiled = json.readTree(body);
        List<String> ids = new java.util.ArrayList<>();
        getJson("/api/v1/search", "q", compiled.path("command").asText()).path("rows").forEach(r -> ids.add(r.path("ref").path("id").asText()));
        return ids;
    }

    static JsonNode panel(JsonNode panels, String id) {
        for (JsonNode p : panels) {
            if (id.equals(p.path("id").asText())) {
                return p;
            }
        }
        throw new AssertionError("no panel " + id + " in " + panels);
    }
}
