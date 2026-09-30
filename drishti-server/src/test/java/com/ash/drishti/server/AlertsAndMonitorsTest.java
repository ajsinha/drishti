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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.alerts.AlertEngine;
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
        "drishti.identity.preferences-dir=target/prefs-alerts-${random.uuid}"})
@AutoConfigureMockMvc
class AlertsAndMonitorsTest {

    @Autowired MockMvc mvc;
    @Autowired AlertEngine engine;
    @LocalServerPort int port;

    @Test
    void aRuleThatIsAlreadyTrueFiresOnceWhenArmedAndBadExpressionsAreRefused() throws Exception {
        mvc.perform(put("/api/v1/me/alerts/rules/Deep MTM").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"trade\",\"id\":\"IRS-48213\",\"when\":\"$.mtm < 0\",\"severity\":\"warn\","
                                + "\"message\":\"${$.tradeId}: MTM ${fmt($.mtm, 'signed0')}\"}"))
                .andExpect(status().isOk());
        long deadline = System.currentTimeMillis() + 5000;
        while (engine.events("ash", 10).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(400);
        assertThat(engine.events("ash", 10)).hasSize(1).first().satisfies(e -> {
            assertThat(e.message()).startsWith("IRS-48213: MTM −");
            assertThat(e.severity()).isEqualTo("warn");
        });
        mvc.perform(get("/api/v1/me/alerts").header("X-Drishti-User", "ash")).andExpect(jsonPath("$[0].rule").value("Deep MTM"));
        mvc.perform(get("/api/v1/me/alerts/rules").header("X-Drishti-User", "ash")).andExpect(jsonPath("$[0].when").value("$.mtm < 0"));
        mvc.perform(put("/api/v1/me/alerts/rules/Bad").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"trade\",\"id\":\"IRS-48213\",\"when\":\"$.mtm <\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/me/alerts/rules/Bad").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"trade\",\"id\":\"IRS-48213\",\"when\":\"true\",\"severity\":\"panic\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/alerts/suggestions/netting-set")).andExpect(jsonPath("$[*].name").value(hasItem("PFE near limit")));
        mvc.perform(get("/api/v1/me/alerts/suggestions/shipment")).andExpect(jsonPath("$[*].name").value(hasItem("Delayed more than 12 h")));
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
