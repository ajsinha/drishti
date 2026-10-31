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
package com.ash.drishti.server.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Reactive connectors through the server (DATA_ARCHITECTURE.md R1 to R4): a connector file of plugin reactive starts from its file; a
 * computation asked directly and through a view is computed for the caller (their user and view signature); a parameter out of bounds is
 * refused in words; a request's events go to its owner only; sandboxes are private; events reach the triggers once (administrators).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false"})
@AutoConfigureMockMvc
class ReactiveApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER = "X-Drishti-User";
    @Autowired MockMvc mvc;
    @Autowired ConnectorManager manager;
    @Autowired ConnectorFiles files;

    @BeforeEach
    void connector() {
        files.write("reactive-echo", """
                plugin: reactive
                mode: reactive
                kinds:
                  echo:
                    params:
                      x: { type: int, default: 1, min: 0, max: 10 }
                      whatif: { type: sandbox-ref }
                    result: { required: [SUM] }
                engine: { type: java, class: com.ash.drishti.server.compute.EchoTestEngine }
                triggers:
                  - { on: write-back, kinds: [trade], affects: "{entity}" }
                limits: { wait: 5s }
                """);
        manager.scan();
    }

    @AfterEach
    void clean() {
        if (files.exists("reactive-echo")) {
            files.delete("reactive-echo");
        }
        manager.scan();
    }

    private JsonNode ask(String user, String body) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/v1/compute/reactive-echo/echo").header(USER, user).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aComputationIsAnsweredForTheCallerDirectlyAndThroughAView() throws Exception {
        JsonNode a = ask("asha", "{\"id\": \"A\", \"params\": {\"x\": 3}, \"wait\": 5000}");
        assertThat(a.get("state").asText()).isEqualTo("ready");
        assertThat(a.get("id").asText()).isEqualTo("A~x=3");
        assertThat(a.at("/result/SUM").asLong()).isEqualTo(6);
        assertThat(a.at("/result/USER").asText()).isEqualTo("asha");
        assertThat(a.at("/result/STAMP/signature").asText()).startsWith("vs-");
        JsonNode v = JSON.readTree(mvc.perform(get("/api/v1/views/echo/A~x=4").header(USER, "ben")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(v.toString()).contains("\"text\":\"8\"").contains("ben");          // computed through the normal routing, for ben
        JsonNode bad = ask("asha", "{\"id\": \"A\", \"params\": {\"x\": 11}}");
        assertThat(bad.get("state").asText()).isEqualTo("refused");
        assertThat(bad.get("message").asText()).isEqualTo("x for echo is 11: it must be at least 0 and at most 10");
    }

    @Test
    void aRequestsEventsGoToItsOwnerOnly() throws Exception {
        String id = ask("asha", "{\"id\": \"B\", \"wait\": 5000}").get("requestId").asText();
        mvc.perform(get("/api/v1/compute/requests/" + id + "/stream").header(USER, "ben")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRS-9206"));
        mvc.perform(delete("/api/v1/compute/requests/" + id).header(USER, "ben")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/compute/requests/" + id).header(USER, "asha")).andExpect(status().isOk());
    }

    @Test
    void sandboxesArePrivateAndComputeForTheirOwner() throws Exception {
        String sid = JSON.readTree(mvc.perform(post("/api/v1/compute/sandboxes").header(USER, "asha").contentType(MediaType.APPLICATION_JSON)
                .content("{\"connector\": \"reactive-echo\", \"title\": \"what if\", \"items\": [{\"TRADE\": \"T-1\"}]}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        assertThat(ask("asha", "{\"id\": \"A\", \"params\": {\"whatif\": \"" + sid + "\"}, \"wait\": 5000}").at("/result/INPUTS").asInt()).isEqualTo(1);
        assertThat(ask("ben", "{\"id\": \"A\", \"params\": {\"whatif\": \"" + sid + "\"}}").get("message").asText()).isEqualTo("there is no sandbox " + sid);
        mvc.perform(get("/api/v1/compute/sandboxes").header(USER, "ben")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put("/api/v1/compute/sandboxes/" + sid).header(USER, "ben").contentType(MediaType.APPLICATION_JSON).content("{\"items\": []}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/compute/sandboxes/" + sid).header(USER, "asha")).andExpect(status().isOk());
    }

    @Test
    void anEventReachesTheTriggersOnce() throws Exception {
        String body = "{\"id\": \"chg-1\", \"type\": \"write-back\", \"kind\": \"trade\", \"entity\": \"A\", \"watermark\": {\"L1\": 7}}";
        JsonNode first = JSON.readTree(mvc.perform(post("/api/v1/compute/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.at("/connectors/reactive-echo/affected").isNumber()).isTrue();
        JsonNode again = JSON.readTree(mvc.perform(post("/api/v1/compute/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(again.at("/connectors/reactive-echo/ignored").asText()).contains("chg-1");
        mvc.perform(post("/api/v1/compute/events").contentType(MediaType.APPLICATION_JSON).content("{\"id\": \"x\", \"type\": \"nope\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/compute/reactive")).andExpect(status().isOk()).andExpect(jsonPath("$.connectors[?(@.name == 'reactive-echo')]").exists());
    }
}
