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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Freshness per panel source (RACHANA_REFERENCE.md 9): a panel with its own {@code source:} (a mapping, Sutra Next, or a linked entity as
 * text) carries the provenance of what it read in the view model (and so in the panels the page asks for again): the connector that
 * answered and the kind, when the document was fetched, that connector's last update and stale-after, and whether it is stale; a
 * panel that reads the page's record carries none (the page's provenance stands for it). The quotes come from a synthetic file
 * whose connector has a stale-after of one minute and whose file is a year old, so their panels are stale while the page is not.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=news-and-markets", "drishti.sources.connectors.panel-provenance-lake.settings.stale-after=PT1M"})
@AutoConfigureMockMvc
class PanelProvenanceTest {

    static final String LAKE = "panel-provenance-lake";

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void site(DynamicPropertyRegistry r) throws Exception {
        Path site = PackSutraNextEquivalence.site(r, "panel-provenance");
        Files.writeString(site.resolve("own-sources.v1.sutra.yaml"), """
                rachana: 1
                sutra: own-sources
                version: 1
                match: { kind: news-topic, priority: 50 }
                title: { id: $.TOPIC_ID }
                panels:
                  - id: topic
                    kind: kv
                    title: Topic
                    columns: [ { label: Name, bind: $.NAME } ]
                  - id: quote
                    kind: kv
                    title: Quote
                    source: { kind: quote, id: "'QUOTE-SYNQ'" }
                    columns: [ { label: Price, bind: $src.PRICE } ]
                  - id: history
                    kind: line
                    title: Closing price
                    source: "link('QUOTE-SYNQ', 'quote')"
                    rows: $.HISTORY
                    x: DATE
                    y: CLOSE
                """, StandardCharsets.UTF_8);
        Path lake = PackSutraNextEquivalence.lakeNamed(r, "news-and-markets", LAKE, "quote");
        Files.setLastModifiedTime(lake.resolve("quote.jsonl"), FileTime.from(Instant.parse("2025-10-01T00:00:00Z")));
    }

    @Test
    void aPanelWithItsOwnSourceCarriesThatSourcesFreshnessAndAPanelOfThePageNone() throws Exception {
        JsonNode view = json.readTree(mvc.perform(get("/api/v1/views/news-topic/TOPIC-FED")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(view.path("provenance").path("layout").asText()).contains("own-sources");
        assertThat(view.path("provenance").path("stale").asBoolean()).isFalse();
        JsonNode topic = panel(view, "topic");
        assertThat(topic.has("provenance")).as("a panel of the page's record").isFalse();
        for (String id : new String[] {"quote", "history"}) {
            JsonNode p = panel(view, id);
            assertThat(p.path("empty").asBoolean()).as(id + " drew its source").isFalse();
            JsonNode prov = p.path("provenance");
            assertThat(prov.path("source").asText()).as(id).isEqualTo(LAKE);
            assertThat(prov.path("kind").asText()).as(id).isEqualTo("quote");
            assertThat(prov.path("fetchedAt").asText()).as(id).isNotBlank();
            assertThat(prov.path("updatedAt").asText()).as(id).startsWith("2025-10-01");
            assertThat(prov.path("staleAfter").asText()).as(id).isEqualTo("PT1M");
            assertThat(prov.path("stale").asBoolean()).as(id + " is stale: a year-old file, a minute's threshold").isTrue();
        }
        // the panels the page asks for again (a refresh) carry it too
        JsonNode again = json.readTree(mvc.perform(get("/api/v1/views/news-topic/TOPIC-FED").param("panels", "quote"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(panel(again, "quote").path("provenance").path("source").asText()).isEqualTo(LAKE);
    }

    private static JsonNode panel(JsonNode view, String id) {
        for (JsonNode p : view.path("panels")) {
            if (id.equals(p.path("id").asText())) {
                return p;
            }
        }
        throw new AssertionError("no panel " + id + " in " + view.path("panels"));
    }
}
