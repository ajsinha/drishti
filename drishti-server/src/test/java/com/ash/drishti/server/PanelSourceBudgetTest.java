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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A panel's own {@code source:} is that panel's data, read as the page's record is (within the sources' fetch timeout), not within the
 * link budget a linked entity's label waits for. With the link budget at zero every read held to it times out; the panel still draws
 * its source (before, it drew its empty text, as if the source held nothing: the funds pack's "Held by funds" tab under load).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=news-and-markets", "drishti.graph.link-budget=0ms"})
@AutoConfigureMockMvc
class PanelSourceBudgetTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void site(DynamicPropertyRegistry r) throws Exception {
        Path site = PackSutraNextEquivalence.site(r, "panel-source-budget");
        Files.writeString(site.resolve("budget-sources.v1.sutra.yaml"), """
                rachana: 1
                sutra: budget-sources
                version: 1
                match: { kind: news-topic, priority: 50 }
                title: { id: $.TOPIC_ID }
                panels:
                  - id: quote
                    kind: kv
                    title: Quote
                    source: { kind: quote, id: "'QUOTE-SYNQ'" }
                    columns: [ { label: Price, bind: $src.PRICE } ]
                """, StandardCharsets.UTF_8);
        PackSutraNextEquivalence.lakeNamed(r, "news-and-markets", "panel-source-budget-lake", "quote");
    }

    @Test
    void aPanelsOwnSourceIsNotHeldToTheLinkBudget() throws Exception {
        JsonNode view = json.readTree(mvc.perform(get("/api/v1/views/news-topic/TOPIC-FED")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(view.path("provenance").path("layout").asText()).contains("budget-sources");
        JsonNode quote = null;
        for (JsonNode p : view.path("panels")) {
            if ("quote".equals(p.path("id").asText())) {
                quote = p;
            }
        }
        assertThat(quote).as("the quote panel in " + view.path("panels")).isNotNull();
        assertThat(quote.path("empty").asBoolean()).as("drew its source: " + quote).isFalse();
        assertThat(quote.has("compute")).as("read, not failed: " + quote).isFalse();
        assertThat(quote.path("provenance").path("source").asText()).isEqualTo("panel-source-budget-lake");
    }
}
