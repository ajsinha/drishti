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

/** A derived kind declared in a pack (finance: book-pnl from trade) is served like any other kind. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance",
        "drishti.sources.connectors.finance-lake.enabled=false"})
@AutoConfigureMockMvc
class DerivedKindTest {

    @Autowired MockMvc mvc;

    @Test
    void aBooksPnlIsSummedFromItsTrades() throws Exception {
        mvc.perform(get("/api/v1/views/book-pnl/RATES-NY-3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance.source").value("book-totals"))
                .andExpect(jsonPath("$.title.id").value("RATES-NY-3"))
                .andExpect(jsonPath("$.title.pill").value("Book P&L"))
                .andExpect(jsonPath("$.panels[?(@.id=='trades')].data.rows.length()").value(hasItem(14)))
                .andExpect(jsonPath("$.panels[?(@.id=='refs')].data.links[*].link.id").value(hasItem("IRS-47102")));
        mvc.perform(get("/api/v1/entities/book-pnl/RATES-NY-3/raw")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeCount").value(14))
                .andExpect(jsonPath("$.data.mtm").value(-1259900))
                .andExpect(jsonPath("$.data.tradeIds").value(hasItem("IRS-47102")))
                .andExpect(jsonPath("$.data.book").value("RATES-NY-3"));
        mvc.perform(get("/api/v1/search").param("q", "BPNL where tradeCount > 1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].ref.id").value(hasItem("RATES-NY-3")));
        mvc.perform(get("/api/v1/command/suggest").param("q", "BPNL RATES")).andExpect(status().isOk())
                .andExpect(jsonPath("$..id").value(hasItem("RATES-NY-3")));
    }
}
