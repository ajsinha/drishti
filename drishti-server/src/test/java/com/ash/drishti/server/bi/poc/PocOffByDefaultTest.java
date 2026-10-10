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
package com.ash.drishti.server.bi.poc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** RUPAKA PHASE 0 PROOF OF CONCEPT: with the default configuration the endpoints do not exist. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class PocOffByDefaultTest {

    @Autowired MockMvc mvc;

    @Test
    void theEndpointsAreNotThereUnlessSwitchedOn() throws Exception {
        mvc.perform(post("/api/v1/bi/poc/query").contentType(MediaType.APPLICATION_JSON).content("{\"groupBy\":[\"desk\"]}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bi/poc/rows")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bi/poc/bench")).andExpect(status().isNotFound());
    }
}
