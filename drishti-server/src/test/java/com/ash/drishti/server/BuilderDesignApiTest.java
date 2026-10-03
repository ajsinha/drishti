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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /api/v1/builder/design} and {@code /suggest}: the ten documented examples draw a Sutra that previews with no
 * panel errors, with reasons and alternatives; panels the samples cannot fill are pruned; open to every signed-in user; limits enforced.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/builder-design-${random.uuid}/identity.db",
        "drishti.builder.max-samples=6", "drishti.builder.max-file-mb=1", "drishti.builder.max-total-mb=4"})
@AutoConfigureMockMvc
class BuilderDesignApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIR = Path.of("..", "docs", "guides", "examples");

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String author() {
        return "Bearer " + tokens.mint("ann", List.of("author"), 300);
    }

    private static JsonNode example(String name) throws Exception {
        return JSON.readTree(Files.readString(DIR.resolve(name + ".json")));
    }

    private static ObjectNode body(String kind, JsonNode... docs) {
        ObjectNode b = JSON.createObjectNode();
        b.put("kind", kind);
        ArrayNode samples = b.putArray("samples");
        for (int i = 0; i < docs.length; i++) {
            samples.addObject().put("name", "s" + i + ".json").set("document", docs[i]);
        }
        return b;
    }

    private JsonNode design(ObjectNode body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/builder/design").header("Authorization", author()).contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isOk()).andReturn();
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private static List<String> names() throws Exception {
        try (var files = Files.list(DIR)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.replace(".json", "")).sorted().toList();
        }
    }

    @Test
    void everyExampleDraftsASutraThatPreviewsWithNoPanelErrors() throws Exception {
        assertThat(names()).hasSizeGreaterThanOrEqualTo(10);
        for (String name : names()) {
            JsonNode d = design(body("trade", example(name)));
            assertThat(d.path("yaml").asText()).as(name).contains("rachana: 1", "panels:");
            assertThat(d.path("preview").path("panels").size()).as(name).isGreaterThanOrEqualTo(2);
            for (JsonNode p : d.path("preview").path("panels")) {
                assertThat(p.path("error").asText("")).as(name + " panel " + p.path("id").asText()).isEmpty();
                if (!"links".equals(p.path("kind").asText())) {
                    assertThat(p.path("empty").asBoolean(false)).as(name + " panel " + p.path("id").asText() + " is empty").isFalse();
                }
            }
            assertThat(d.path("reasons").has("title")).as(name).isTrue();
            for (JsonNode p : d.path("pruned")) {
                assertThat(p.path("of").asInt()).as(name + " pruned by preview: " + p).isZero();
            }
        }
    }

    @Test
    void theShowcaseDrawsTheExpectedKindsAndKeepsAlternatives() throws Exception {
        JsonNode d = design(body("trade", example("all-panels-showcase")));
        List<String> kinds = new java.util.ArrayList<>();
        d.path("preview").path("panels").forEach(p -> kinds.add(p.path("kind").asText()));
        assertThat(kinds).contains("line", "candlestick", "waterfall", "histogram", "graph", "timeline", "pivot", "area", "hbar", "status");
        JsonNode alt = d.path("alternatives").path("pnlhistory");
        assertThat(alt.size()).isGreaterThanOrEqualTo(1);
        assertThat(alt.get(0).path("kind").asText()).isEqualTo("area");
        assertThat(alt.get(0).path("reason").asText()).isNotBlank();
        assertThat(alt.get(0).path("options").path("rows").asText()).isEqualTo("$.pnlHistory");
        assertThat(alt.get(0).path("area").asText()).isEqualTo("main");
        assertThat(d.path("reasons").path("pnlhistory").asText()).contains("20 points");
    }

    @Test
    void theShowcaseWithVariedCopiesStillDraftsCleanly() throws Exception {
        JsonNode base = example("all-panels-showcase");
        JsonNode[] docs = new JsonNode[4];
        for (int i = 0; i < docs.length; i++) {
            ObjectNode c = base.deepCopy();
            c.put("tradeId", "DEMO-BOND-" + (i + 1));
            c.put("mtm", 1209000 * (i + 1));
            c.put("pnl1d", -41000 + 9000 * i * i);
            docs[i] = c;
        }
        JsonNode d = design(body("trade", docs));
        assertThat(d.path("samples").asInt()).isEqualTo(4);
        assertThat(d.path("yaml").asText()).contains("emphasis: true");
        assertThat(d.path("reasons").path("strip.MTM").asText()).contains("varies");
        for (JsonNode p : d.path("preview").path("panels")) {
            assertThat(p.path("error").asText("")).isEmpty();
        }
    }

    @Test
    void aPanelOnAFieldMissingFromMostSamplesIsPrunedWithAReason() throws Exception {
        JsonNode base = example("all-panels-showcase");
        JsonNode[] docs = new JsonNode[5];
        for (int i = 0; i < docs.length; i++) {
            ObjectNode c = base.deepCopy();
            c.put("tradeId", "DEMO-BOND-" + (i + 1));
            if (i > 0) {
                c.remove("pnlHistory");
                c.remove("ohlc");
            }
            docs[i] = c;
        }
        JsonNode d = design(body("trade", docs));
        List<String> pruned = d.path("pruned").findValuesAsText("panel");
        assertThat(pruned).contains("pnlhistory", "ohlc");
        JsonNode p = d.path("pruned").get(pruned.indexOf("pnlhistory"));
        assertThat(p.path("action").asText()).isIn("dropped", "demoted");
        assertThat(p.path("bad").asInt()).isEqualTo(4);
        assertThat(p.path("of").asInt()).isEqualTo(5);
        assertThat(p.path("reason").asText()).contains("empty in 4 of 5 samples");
        assertThat(d.path("yaml").asText()).doesNotContain("kind: candlestick");
        for (JsonNode panel : d.path("preview").path("panels")) {
            assertThat(panel.path("error").asText("")).isEmpty();
        }
    }

    @Test
    void aShapeAloneDraftsWithoutAPreview() throws Exception {
        MvcResult shape = mvc.perform(post("/api/v1/builder/shape").header("Authorization", author()).contentType(MediaType.APPLICATION_JSON)
                .content(body("trade", example("pnl-explain")).toString())).andExpect(status().isOk()).andReturn();
        ObjectNode req = JSON.createObjectNode();
        req.put("kind", "trade");
        req.set("shape", JSON.readTree(shape.getResponse().getContentAsString()));
        JsonNode d = design(req);
        assertThat(d.path("yaml").asText()).contains("kind: waterfall");
        assertThat(d.path("preview").isNull()).isTrue();
        assertThat(d.path("samples").asInt()).isZero();
    }

    private JsonNode suggest(ObjectNode req) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/builder/suggest").header("Authorization", author()).contentType(MediaType.APPLICATION_JSON)
                .content(req.toString())).andExpect(status().isOk()).andReturn();
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void suggestRanksPanelKindsForAFieldAndForAPair() throws Exception {
        ObjectNode req = body("trade", example("all-panels-showcase"));
        req.put("path", "$.ohlc");
        JsonNode s = suggest(req);
        assertThat(s.path("suggestions").get(0).path("kind").asText()).isEqualTo("candlestick");
        assertThat(s.path("suggestions").get(0).path("reason").asText()).isNotBlank();
        assertThat(s.path("suggestions").get(0).path("options").path("rows").asText()).isEqualTo("$.ohlc");
        req.put("path", "$.positions[].family");
        req.put("at", "$.positions[].mtm");
        s = suggest(req);
        assertThat(s.path("suggestions").get(0).path("kind").asText()).isEqualTo("pivot");
        assertThat(s.path("suggestions").get(0).path("options").has("by")).isTrue();
        assertThat(s.path("at").asText()).isEqualTo("$.positions[].mtm");
    }

    @Test
    void suggestTakesAShapeAndRefusesAnUnknownPath() throws Exception {
        MvcResult shape = mvc.perform(post("/api/v1/builder/shape").header("Authorization", author()).contentType(MediaType.APPLICATION_JSON)
                .content(body("trade", example("exposure-profile")).toString())).andExpect(status().isOk()).andReturn();
        ObjectNode req = JSON.createObjectNode();
        req.set("shape", JSON.readTree(shape.getResponse().getContentAsString()));
        req.put("path", "$.profile");
        assertThat(suggest(req).path("suggestions").get(0).path("kind").asText()).isEqualTo("area");
        req.put("path", "$.nope");
        mvc.perform(post("/api/v1/builder/suggest").header("Authorization", author()).contentType(MediaType.APPLICATION_JSON).content(req.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("no such path")));
    }

    @Test
    void everySignedInUserMayDesignOrSuggest() throws Exception {
        String viewer = "Bearer " + tokens.mint("vic", List.of("viewer"), 300);
        ObjectNode req = body("trade", example("pnl-explain"));
        req.put("path", "$.pnlExplain");
        for (String url : new String[] {"/api/v1/builder/design", "/api/v1/builder/suggest"}) {
            mvc.perform(post(url).header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON).content(req.toString()))
                    .andExpect(status().isOk());
            mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(req.toString())).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void limitsAreEnforcedLikeTheShapeEndpoint() throws Exception {
        JsonNode doc = example("pnl-explain");
        mvc.perform(post("/api/v1/builder/design").header("Authorization", author()).contentType(MediaType.APPLICATION_JSON)
                .content(body("trade", doc, doc, doc, doc, doc, doc, doc).toString()))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("DRS-5005"))
                .andExpect(jsonPath("$.detail").value(containsString("max-samples")));
        String big = "{\"samples\":[{\"name\":\"a\",\"document\":{\"blob\":\"" + "x".repeat(4_300_000) + "\"}}]}";
        mvc.perform(post("/api/v1/builder/design").header("Authorization", author()).contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.detail").value(containsString("max-total-mb")));
        String huge = "{\"samples\":[{\"name\":\"huge.json\",\"document\":{\"blob\":\"" + "x".repeat(1_100_000) + "\"}}]}";
        mvc.perform(post("/api/v1/builder/suggest").header("Authorization", author()).contentType(MediaType.APPLICATION_JSON)
                .content(huge.replace("}}]}", "}}],\"path\":\"$.blob\"}")))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.detail").value(containsString("max-file-mb")));
    }

    @Test
    void badBodiesAreCleanProblems() throws Exception {
        for (String url : new String[] {"/api/v1/builder/design", "/api/v1/builder/suggest"}) {
            for (String b : new String[] {"{not json", "[1]", "{}", "{\"samples\":[]}", "{\"shape\":1}", "{\"samples\":[{\"name\":\"a\"}]}",
                    "{\"kind\":\"trade\"}"}) {
                mvc.perform(post(url).header("Authorization", author()).contentType(MediaType.APPLICATION_JSON).content(b))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5001"));
            }
        }
        mvc.perform(post("/api/v1/builder/suggest").header("Authorization", author()).contentType(MediaType.APPLICATION_JSON)
                .content(body("trade", example("pnl-explain")).toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("'path' is required")));
    }
}
