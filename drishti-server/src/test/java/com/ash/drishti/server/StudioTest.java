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

@SpringBootTest(properties = {"drishti.rachana.dirs=../sutras", "drishti.rachana.hot-reload=false",
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
    void savingIsOffUnlessEnabled() throws Exception {
        mvc.perform(get("/api/v1/studio/settings")).andExpect(jsonPath("$.save").value(false));
        mvc.perform(post("/api/v1/sutras").contentType("text/yaml").content("sutra: x\nversion: 1\nmatch: { kind: trade }\n"))
                .andExpect(status().isForbidden());
        assertThat(Files.exists(Path.of("../sutras/studio"))).isFalse();
    }
}
