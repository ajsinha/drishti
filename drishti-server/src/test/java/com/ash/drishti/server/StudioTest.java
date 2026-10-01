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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.rachana.parse.SutraParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"drishti.rachana.hot-reload=false",
        "drishti.sources.plugins.demo.settings.ticking=false"})
@AutoConfigureMockMvc
class StudioTest {

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void previewAnEditedSutraAndSeeProblemsWithLines() throws Exception {
        String src = mvc.perform(get("/api/v1/sutras/irs-vanilla/3/source")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String edited = src.replace("title: Legs", "title: Swap legs (edited)");
        mvc.perform(post("/api/v1/studio/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("yaml", edited, "kind", "trade", "id", "IRS-48213"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.panels[0].title").value("Swap legs (edited)"));
        String broken = src.replace("bind: $.notional", "bind: \"$.notional +\"");
        mvc.perform(post("/api/v1/studio/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("yaml", broken, "kind", "trade", "id", "IRS-48213"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems[*].code").value(hasItem("DRS-2101")));
    }

    @Test
    void startFromInferenceGivesAValidSutra() throws Exception {
        String yaml = mvc.perform(get("/api/v1/studio/inferred/trade/IRS-47102").param("name", "irs-plain"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var s = new SutraParser().parse(yaml, "x.yaml", "rates");
        assertThat(s.name()).isEqualTo("irs-plain");
        assertThat(s.strip()).isNotEmpty();
        mvc.perform(post("/api/v1/studio/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("yaml", yaml, "kind", "trade", "id", "IRS-47102"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance.layout").value("Sutra irs-plain v1 + inference"));
    }

    @Test
    void previewAndInferAgainstPastedJson() throws Exception {
        String yaml = "rachana: 1\nsutra: pasted\nversion: 1\nmatch: { kind: widget }\ntitle: { pill: Widget, id: $.code }\n"
                + "strip:\n  - { label: Price, bind: $.price, fmt: amount2 }\npanels:\n  - { id: refs, kind: links, title: Links }\n";
        String doc = "{\"code\":\"W-1\",\"price\":1234.5,\"parts\":[{\"name\":\"a\",\"qty\":2},{\"name\":\"b\",\"qty\":3}]}";
        mvc.perform(post("/api/v1/studio/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yaml\":" + json.writeValueAsString(yaml) + ",\"kind\":\"widget\",\"id\":\"W-1\",\"document\":" + doc + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strip[0].text").value("1,234.50"))
                .andExpect(jsonPath("$.provenance.source").value("studio sample JSON"));
        String inferred = mvc.perform(post("/api/v1/studio/inferred").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"widget\",\"id\":\"W-1\",\"name\":\"widget\",\"document\":" + doc + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(inferred).contains("rachana: 1\nsutra: widget").contains("$.parts");
        mvc.perform(post("/api/v1/studio/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"yaml\":" + json.writeValueAsString(yaml) + ",\"kind\":\"widget\",\"document\":[1,2]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void savingIsOffUnlessEnabled() throws Exception {
        mvc.perform(get("/api/v1/studio/settings")).andExpect(jsonPath("$.save").value(false));
        mvc.perform(post("/api/v1/sutras").contentType("text/yaml").content("rachana: 1\nsutra: x\nversion: 1\nmatch: { kind: trade }\n"))
                .andExpect(status().isForbidden());
        assertThat(Files.exists(Path.of("../sutras/studio"))).isFalse();
    }

    @Test
    void theRachanaSchemaComesFromTheGrammarAndThisServersKindsAndFormats() throws Exception {
        String body = mvc.perform(get("/api/v1/rachana/schema")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        org.assertj.core.api.Assertions.assertThat(schema.path("required").toString()).contains("rachana", "sutra", "version", "match");
        org.assertj.core.api.Assertions.assertThat(schema.at("/properties/rachana/const").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(schema.at("/properties/match/properties/kind/enum").toString()).contains("trade");
        org.assertj.core.api.Assertions.assertThat(schema.at("/$defs/panel/properties/kind/enum")).hasSize(20);
        org.assertj.core.api.Assertions.assertThat(schema.at("/$defs/column/properties/fmt/enum").toString()).contains("signed0", "date");
        org.assertj.core.api.Assertions.assertThat(schema.at("/$defs/panel/properties/colors/enum").toString()).isEqualTo("[\"gain-loss\",\"theme\"]");
        org.assertj.core.api.Assertions.assertThat(schema.path("x-rachana-functions").has("link")).isTrue();
    }
}
