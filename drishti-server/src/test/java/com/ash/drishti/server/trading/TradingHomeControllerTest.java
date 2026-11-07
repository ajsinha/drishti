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
package com.ash.drishti.server.trading;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The trader home's endpoints in the running server: the state of the watched kinds (their data versions) and the stream that ticks. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.trading.home.frame=50ms"})
@AutoConfigureMockMvc
class TradingHomeControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void theStateNamesTheWatchedKindsAndTheStreamOpens() throws Exception {
        mvc.perform(get("/api/v1/trading/home/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kinds", hasItem("trade")))
                .andExpect(jsonPath("$.kinds", hasItem("trade-valuation")))
                .andExpect(jsonPath("$.generation").isString())
                .andExpect(jsonPath("$.versions.trade").isString());
        mvc.perform(get("/api/v1/trading/home/live")).andExpect(request().asyncStarted());
    }
}
