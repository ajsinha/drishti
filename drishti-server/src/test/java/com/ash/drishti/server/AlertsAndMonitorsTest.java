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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "drishti.sources.plugins.demo.settings.tick-ms=40", "drishti.live.frame=20ms", "drishti.packs.enabled=finance,logistics",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/prefs-alerts-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class AlertsAndMonitorsTest {

    @Autowired MockMvc mvc;
    @LocalServerPort int port;

    private JsonNode json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return new ObjectMapper().readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** A rule on one entity (a Rachana-EL predicate over a field source) in the one alert engine: raised once, then acknowledged, snoozed, resolved. */
    @Test
    void aRuleOnAnEntityIsRaisedOnceThenAcknowledgedSnoozedAndResolved() throws Exception {
        String rule = "{\"title\":\"Deep MTM\",\"severity\":\"warn\",\"source\":{\"type\":\"field\",\"kind\":\"trade\",\"id\":\"IRS-48213\","
                + "\"field\":\"$.mtm < 0\"},\"condition\":{\"type\":\"rule\"},\"trigger\":{\"mode\":\"after-load\",\"kind\":\"trade\"}}";
        mvc.perform(put("/api/v1/alerts/rules/Deep MTM").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON).content(rule))
                .andExpect(status().isOk()).andExpect(jsonPath("$.describe").value("holds: $.mtm < 0"));
        mvc.perform(post("/api/v1/alerts/rules/Deep MTM/check").header("X-Drishti-User", "ash")).andExpect(status().isOk());
        JsonNode raised = json(mvc.perform(get("/api/v1/alerts").header("X-Drishti-User", "ash")).andExpect(status().isOk()));
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).path("stage").asText()).isEqualTo("raised");
        assertThat(raised.get(0).path("message").asText()).startsWith("Deep MTM").contains("holds");
        String id = raised.get(0).path("id").asText();
        mvc.perform(post("/api/v1/alerts/rules/Deep MTM/check").header("X-Drishti-User", "ash")).andExpect(status().isOk());
        assertThat(json(mvc.perform(get("/api/v1/alerts").header("X-Drishti-User", "ash")))).hasSize(1);      // still true: raised once
        mvc.perform(post("/api/v1/alerts/acknowledge").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"" + id + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("acknowledged")).andExpect(jsonPath("$.assignee").value("ash"));
        mvc.perform(post("/api/v1/alerts/snooze").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\",\"for\":\"1h\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.snoozedUntil").exists());
        mvc.perform(post("/api/v1/alerts/acknowledge").header("X-Drishti-User", "bo").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"" + id + "\"}"))
                .andExpect(status().isNotFound());                                                              // another person never reaches it
        assertThat(json(mvc.perform(get("/api/v1/alerts").header("X-Drishti-User", "bo")))).isEmpty();
        mvc.perform(post("/api/v1/alerts/resolve").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\",\"note\":\"booked back\"}")).andExpect(jsonPath("$.stage").value("resolved"));
        assertThat(json(mvc.perform(get("/api/v1/alerts").header("X-Drishti-User", "ash")))).isEmpty();          // open only
        assertThat(json(mvc.perform(get("/api/v1/alerts?stage=all").header("X-Drishti-User", "ash")))).hasSize(1);
        mvc.perform(put("/api/v1/alerts/rules/Bad").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON)
                .content(rule.replace("$.mtm < 0", "$.mtm <"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/alerts/rules/Bad").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON)
                .content(rule.replace("\"warn\"", "\"panic\""))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/alerts/suggestions/netting-set")).andExpect(jsonPath("$[*].name").value(hasItem("PFE near limit")));
        mvc.perform(get("/api/v1/alerts/suggestions/shipment")).andExpect(jsonPath("$[*].name").value(hasItem("Delayed more than 12 h")));
    }

    @Test
    void monitorsListRowsAndMultiplexLiveChanges() throws Exception {
        mvc.perform(put("/api/v1/me/monitors/Watch").header("X-Drishti-User", "mo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"entities\":[{\"kind\":\"trade\",\"id\":\"IRS-48213\"},{\"kind\":\"shipment\",\"id\":\"SHP-10042\"},"
                        + "{\"kind\":\"trade\",\"id\":\"NOPE\"}]}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/monitors/Watch").header("X-Drishti-User", "mo"))
                .andExpect(jsonPath("$[0].strip[0].text").value("50,000,000"))
                .andExpect(jsonPath("$[1].title.id").value("SHP-10042"))
                .andExpect(jsonPath("$[2].error").exists());
        HttpResponse<java.io.InputStream> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/me/monitors/Watch/stream")).header("X-Drishti-User", "mo").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int rows = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            String line;
            long deadline = System.currentTimeMillis() + 8000;
            while ((line = in.readLine()) != null && System.currentTimeMillis() < deadline && rows < 3) {
                if (line.startsWith("event:row")) {
                    rows++;
                }
            }
        }
        assertThat(rows).isGreaterThanOrEqualTo(3);
    }
}
