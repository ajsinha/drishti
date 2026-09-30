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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"drishti.rachana.hot-reload=false"})
@AutoConfigureMockMvc
class ApiTest {

    @Autowired MockMvc mvc;

    @Test
    void viewEndpointReturnsTheViewModel() throws Exception {
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("X-Drishti-User", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mnemonic").value("TRD"))
                .andExpect(jsonPath("$.strip[0].text").value("50,000,000"))
                .andExpect(jsonPath("$.panels[?(@.id=='curve')].data.mark").value(hasItem("5Y")))
                .andExpect(jsonPath("$.provenance.layout").value("Sutra irs-vanilla v3 + inference"));
        mvc.perform(get("/api/v1/command/suggest").param("q", "").header("X-Drishti-User", "t1"))
                .andExpect(jsonPath("$[0].id").value("IRS-48213"));
    }

    @Test
    void commandsAndSuggestions() throws Exception {
        mvc.perform(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"nset ns-north-01 <GO>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref.kind").value("netting-set"))
                .andExpect(jsonPath("$.mnemonic").value("NSET"));
        mvc.perform(get("/api/v1/command/suggest").param("q", "TRD IRS-48"))
                .andExpect(jsonPath("$[0].complete").value("TRD IRS-48213"));
    }

    @Test
    void errorsAreProblemJsonWithCodes() throws Exception {
        mvc.perform(get("/api/v1/views/trade/NOPE-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRS-1001"));
        mvc.perform(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hello world\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRS-4001"));
        mvc.perform(get("/api/v1/views/nothing/X"))
                .andExpect(jsonPath("$.code").value("DRS-1001"));
    }

    @Test
    void rawSourcesAndSutras() throws Exception {
        mvc.perform(get("/api/v1/entities/trade/IRS-48213/raw"))
                .andExpect(jsonPath("$.provenance.source").value("aero-risk"))
                .andExpect(jsonPath("$.data.legs[0].cashflows[0].amount").value(-1962430.56));
        mvc.perform(get("/api/v1/sources")).andExpect(jsonPath("$.sources[*].name").value(hasItem("demo")));
        mvc.perform(get("/api/v1/sutras")).andExpect(jsonPath("$[*].name").value(hasItem("irs-vanilla")));
        mvc.perform(get("/api/v1/sutras/irs-vanilla/3")).andExpect(jsonPath("$.panels[0].id").value("legs"));
        mvc.perform(get("/api/docs")).andExpect(status().isOk()).andExpect(jsonPath("$.info.title").value(containsString("OpenAPI")));
    }
}
