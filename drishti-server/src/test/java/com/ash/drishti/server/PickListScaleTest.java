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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A book far larger than the scan limit (here 750 trades against a limit of 3, as 500,000 against 20,000): a pick
 * list still finds what it names, because the sources' indexes narrow by it before the limit applies.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading",
        "drishti.sources.connectors.trading-store.enabled=false", "drishti.search.max-scan=3"})
@AutoConfigureMockMvc
class PickListScaleTest {

    @Autowired MockMvc mvc;

    @Test
    void aPickListFindsWhatItNamesBeyondTheScanLimit() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "TRD T-1070")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].ref.id").value(hasItem("T-10700")))
                .andExpect(jsonPath("$.partial").value(true));                  // T-1070 … names 11 trades; 3 are read
        mvc.perform(get("/api/v1/search").param("q", "TRD T-10700")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].ref.id").value("T-10700")).andExpect(jsonPath("$.partial").value(false));
    }
}
