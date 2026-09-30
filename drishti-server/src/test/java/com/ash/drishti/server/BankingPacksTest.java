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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The banking packs: enabling two risk packs brings the packs they require, their Sutras and their data-domain connectors. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=market-risk,counterparty-risk"})
@AutoConfigureMockMvc
class BankingPacksTest {

    @Autowired MockMvc mvc;

    @Test
    void requiredPacksConnectorsAndSutrasAreLoaded() throws Exception {
        mvc.perform(get("/api/v1/packs")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(containsInAnyOrder("banking-core", "market-data", "trading", "market-risk", "counterparty-risk")));
        mvc.perform(get("/api/v1/sources"))
                .andExpect(jsonPath("$.sources[*].name").value(hasItems("reference-store", "market-store", "trading-store", "risk-store",
                        "credit-store", "collateral-store")));
        mvc.perform(get("/api/v1/sutras")).andExpect(jsonPath("$.length()").value(170))
                .andExpect(jsonPath("$[*].name").value(hasItems("irs-fixfloat", "netting-set", "var", "ir-curve", "counterparty")));
        mvc.perform(get("/api/v1/command/suggest").param("q", "NSE"))
                .andExpect(jsonPath("$[*].mnemonic").value(hasItem("NSET")));
    }
}
