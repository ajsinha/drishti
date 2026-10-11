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
package com.ash.drishti.server.alerts.measure;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The alerts on measures through the real server: a field alert on a Sutra entity of the demo source fires into the person's inbox, is listed, paused, looked at
 * by hand and deleted; a bad alert is {@code DRS-9062}; the board tiles list is for administrators.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"drishti.sources.plugins.demo.settings.tick-ms=40", "drishti.packs.enabled=finance,logistics",
        "drishti.identity.database-url=jdbc:sqlite:target/prefs-measure-alerts-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class MeasureAlertApiTest {

    @Autowired MockMvc mvc;

    static final String FIELD_ALERT = "{\"title\":\"Deep MTM\",\"source\":{\"type\":\"field\",\"kind\":\"trade\",\"id\":\"IRS-48213\",\"field\":\"mtm\"},"
            + "\"condition\":{\"type\":\"threshold\",\"op\":\"below\",\"value\":0},\"trigger\":{\"mode\":\"schedule\",\"schedule\":\"hourly\"},\"severity\":\"critical\"}";

    @Test
    void aFieldAlertFiresIntoTheInboxAndCanBeManaged() throws Exception {
        mvc.perform(put("/api/v1/me/measure-alerts/Deep MTM").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON).content(FIELD_ALERT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.describe").value("below 0")).andExpect(jsonPath("$.link").value("/v/trade/IRS-48213"));
        long deadline = System.currentTimeMillis() + 8000;
        String history = "";
        while (System.currentTimeMillis() < deadline) {
            history = mvc.perform(get("/api/v1/me/measure-alerts/Deep MTM").header("X-Drishti-User", "ash")).andReturn().getResponse().getContentAsString();
            if (history.contains("\"fired\"")) {
                break;
            }
            Thread.sleep(100);
        }
        mvc.perform(get("/api/v1/me/measure-alerts").header("X-Drishti-User", "ash")).andExpect(status().isOk()).andExpect(jsonPath("$[*].name").value(hasItem("Deep MTM")))
                .andExpect(jsonPath("$[0].history[0].action").value("fired")).andExpect(jsonPath("$[0].history[0].delivered.inbox").value("ok"));
        mvc.perform(get("/api/v1/me/inbox").header("X-Drishti-User", "ash")).andExpect(status().isOk()).andExpect(jsonPath("$[0].type").value("alert"))
                .andExpect(jsonPath("$[0].id").value("/v/trade/IRS-48213")).andExpect(jsonPath("$[0].title").value(startsWith("Deep MTM")));
        mvc.perform(post("/api/v1/me/measure-alerts/Deep MTM/check").header("X-Drishti-User", "ash")).andExpect(status().isOk()).andExpect(jsonPath("$.state.ash.active").value(true));
        mvc.perform(post("/api/v1/me/measure-alerts/Deep MTM/pause").header("X-Drishti-User", "ash")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/v1/me/measure-alerts/Deep MTM/resume").header("X-Drishti-User", "ash")).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(delete("/api/v1/me/measure-alerts/Deep MTM").header("X-Drishti-User", "ash")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/me/measure-alerts/Deep MTM").header("X-Drishti-User", "ash")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/measure-alerts/Deep MTM").header("X-Drishti-User", "ash")).andExpect(status().isNotFound());
    }

    @Test
    void aPreviewReadsWithoutSavingAndABadAlertIsDrs9062() throws Exception {
        mvc.perform(post("/api/v1/me/measure-alerts/preview").header("X-Drishti-User", "pia").contentType(MediaType.APPLICATION_JSON).content(FIELD_ALERT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.value").isNumber()).andExpect(jsonPath("$.condition").value("below 0"));
        mvc.perform(get("/api/v1/me/measure-alerts").header("X-Drishti-User", "pia")).andExpect(jsonPath("$").isEmpty());
        mvc.perform(put("/api/v1/me/measure-alerts/Bad").header("X-Drishti-User", "pia").contentType(MediaType.APPLICATION_JSON)
                .content(FIELD_ALERT.replace("\"below\"", "\"under\""))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-9062"));
        mvc.perform(put("/api/v1/me/measure-alerts/Bad2").header("X-Drishti-User", "pia").contentType(MediaType.APPLICATION_JSON)
                .content(FIELD_ALERT.replace("\"field\":\"mtm\"", "\"field\":\"mtm <\""))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void boardTilesAndEveryonesAlertsAreListedForAdministrators() throws Exception {
        mvc.perform(get("/api/v1/admin/measure-alerts").header("X-Drishti-User", "ash")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/measure-alerts/boards").header("X-Drishti-User", "ash")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }
}
