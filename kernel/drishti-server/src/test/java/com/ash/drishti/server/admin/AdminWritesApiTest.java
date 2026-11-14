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
package com.ash.drishti.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.TestRun;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The administrators' writes over somebody else's things, with the server's security on: an administrator pauses, resumes and deletes another
 * person's alert rule and removes another person's report; "Retry now" tries the failing areas again and is audited. Each call has its effect
 * checked in the owner's own list, a person who is not an administrator is refused, and a missing thing is a 404 with its code.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.tick-ms=40", "drishti.packs.enabled=finance,logistics",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.identity.iterations=1000",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db", "drishti.reports.tick=1h"})
@AutoConfigureMockMvc
class AdminWritesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String FIELD_ALERT = "{\"title\":\"Deep MTM\",\"source\":{\"type\":\"field\",\"kind\":\"trade\",\"id\":\"IRS-48213\",\"field\":\"mtm\"},"
            + "\"condition\":{\"type\":\"threshold\",\"op\":\"below\",\"value\":0},\"trigger\":{\"mode\":\"schedule\",\"schedule\":\"hourly\"},\"severity\":\"critical\"}";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Path folder = TestRun.temp("admin-writes-reports");
        r.add("drishti.reports.folder", folder::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions a) throws Exception {
        return JSON.readTree(a.andReturn().getResponse().getContentAsString());
    }

    private boolean enabled(String owner, String name) throws Exception {
        JsonNode rules = json(mvc.perform(get("/api/v1/alerts/rules").header("Authorization", as(owner, "admin"))));
        for (JsonNode r : rules) {
            if (name.equals(r.path("name").asText())) {
                return r.path("enabled").asBoolean();
            }
        }
        throw new AssertionError("no rule " + name + " of " + owner + " in " + rules);
    }

    @Test
    void anAdministratorPausesResumesAndDeletesAnotherPersonsAlertRule() throws Exception {
        String olga = as("drishti-dev-admin", "admin");
        String ada = as("ada", "admin");
        String tia = as("tia", "trader");
        var made = mvc.perform(put("/api/v1/alerts/rules/Deep MTM").header("Authorization", olga).contentType(MediaType.APPLICATION_JSON).content(FIELD_ALERT)).andReturn().getResponse();
        assertThat(made.getStatus()).as(made.getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(made.getContentAsString()).path("enabled").asBoolean()).isTrue();
        // not an administrator: refused, nothing changes
        mvc.perform(post("/api/v1/admin/alerts/rules/drishti-dev-admin/Deep MTM/pause").header("Authorization", tia)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-5002"));
        mvc.perform(delete("/api/v1/admin/alerts/rules/drishti-dev-admin/Deep MTM").header("Authorization", tia)).andExpect(status().isForbidden());
        assertThat(enabled("drishti-dev-admin", "Deep MTM")).isTrue();
        // paused and resumed by an administrator who is not the owner; repeated, each is the same state
        mvc.perform(post("/api/v1/admin/alerts/rules/drishti-dev-admin/Deep MTM/pause").header("Authorization", ada)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(enabled("drishti-dev-admin", "Deep MTM")).as("the owner's own list shows it paused").isFalse();
        mvc.perform(post("/api/v1/admin/alerts/rules/drishti-dev-admin/Deep MTM/pause").header("Authorization", ada)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/v1/admin/alerts/rules/drishti-dev-admin/Deep MTM/resume").header("Authorization", ada)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        assertThat(enabled("drishti-dev-admin", "Deep MTM")).isTrue();
        mvc.perform(post("/api/v1/admin/alerts/rules/drishti-dev-admin/Deep MTM/resume").header("Authorization", ada)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        // a rule that is not there is a 404 with its code, for every one of the three
        mvc.perform(post("/api/v1/admin/alerts/rules/drishti-dev-admin/Nope/pause").header("Authorization", ada)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(startsWith("DRS-")));
        mvc.perform(post("/api/v1/admin/alerts/rules/drishti-dev-admin/Nope/resume").header("Authorization", ada)).andExpect(status().isNotFound());
        // deleted: gone from the owner's list, and a second delete says so
        mvc.perform(delete("/api/v1/admin/alerts/rules/drishti-dev-admin/Deep MTM").header("Authorization", ada)).andExpect(status().isNoContent());
        assertThat(json(mvc.perform(get("/api/v1/alerts/rules").header("Authorization", olga))).findValuesAsText("name")).doesNotContain("Deep MTM");
        mvc.perform(delete("/api/v1/admin/alerts/rules/drishti-dev-admin/Deep MTM").header("Authorization", ada)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(startsWith("DRS-")));
    }

    @Test
    void anAdministratorRemovesAnotherPersonsReport() throws Exception {
        String olga = as("drishti-dev-admin", "admin");
        String ada = as("ada", "admin");
        String body = "{\"query\": \"TRD where mtm < 0\", \"schedule\": \"business-days 18:30\", \"deliver\": \"folder\", \"webhook\": \"\"}";
        mvc.perform(put("/api/v1/me/reports/Losers").header("Authorization", olga).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(json(mvc.perform(get("/api/v1/admin/reports").header("Authorization", ada))).findValuesAsText("name")).contains("Losers");
        mvc.perform(delete("/api/v1/admin/reports/drishti-dev-admin/Losers").header("Authorization", as("tia", "trader"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-5002"));
        mvc.perform(get("/api/v1/me/reports/Losers").header("Authorization", olga)).andExpect(status().isOk());               // the refusal changed nothing
        mvc.perform(delete("/api/v1/admin/reports/drishti-dev-admin/Losers").header("Authorization", ada)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/reports/Losers").header("Authorization", olga)).andExpect(status().isNotFound());
        assertThat(json(mvc.perform(get("/api/v1/admin/reports").header("Authorization", ada))).findValuesAsText("name")).doesNotContain("Losers");
        mvc.perform(delete("/api/v1/admin/reports/drishti-dev-admin/Losers").header("Authorization", ada)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(startsWith("DRS-")));
    }

    @Test
    void retryNowTriesTheAreasAgainIsAuditedAndRefusesAnUnknownArea() throws Exception {
        String ada = as("ada", "admin");
        mvc.perform(post("/api/v1/admin/retry/connectors").header("Authorization", as("tia", "trader"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        JsonNode one = json(mvc.perform(post("/api/v1/admin/retry/connectors").header("Authorization", ada)).andExpect(status().isOk()).andExpect(jsonPath("$.connectors").isNumber())
                .andExpect(jsonPath("$.retry").exists()));
        assertThat(one.has("packs")).as("only the area asked").isFalse();
        JsonNode all = json(mvc.perform(post("/api/v1/admin/retry/all").header("Authorization", ada)).andExpect(status().isOk()));
        assertThat(all.has("packs") && all.has("connectors") && all.has("ai") && all.has("mcp")).isTrue();
        mvc.perform(post("/api/v1/admin/retry/everything").header("Authorization", ada)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5001"))
                .andExpect(jsonPath("$.detail").value(containsString("unknown area")));
        String audit = mvc.perform(get("/api/v1/admin/audit").header("Authorization", ada)).andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("connector-retry-now", "ai-retry-now", "mcp-retry-now");
    }
}
