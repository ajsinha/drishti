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
package com.ash.drishti.server.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A fresh install is read-only (WRITE_BACK.md › The design and its reasons, section 12): with write-back off, as shipped, every change is refused with
 * {@code 403 DRS-9301}, the console is told nobody may edit (no Submit is drawn), and no lane runs.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading", "drishti.edit.file=../config/edit/edit.yaml"})
@AutoConfigureMockMvc
class WriteBackOffTest {

    @Autowired MockMvc mvc;
    @Autowired EditRuntime runtime;

    @Test
    void offAsShippedEveryChangeIsRefusedAndNoLaneRuns() throws Exception {
        String body = "{\"kind\":\"trade\",\"entity\":\"T-1\",\"reason\":\"x\",\"items\":[{\"field\":\"NOTIONAL\",\"from\":1,\"to\":2}]}";
        JsonNode e = new ObjectMapper().readTree(mvc.perform(post("/api/v1/changes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(e.path("code").asText()).isEqualTo("DRS-9301");
        JsonNode s = new ObjectMapper().readTree(mvc.perform(get("/api/v1/changes/settings")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(s.path("enabled").asBoolean()).isFalse();
        assertThat(s.path("mayEdit").asBoolean()).isFalse();
        assertThat(runtime.lanes()).as("no lane, no worker").isNull();
        mvc.perform(post("/api/v1/changes/chg-x/approve").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
}
