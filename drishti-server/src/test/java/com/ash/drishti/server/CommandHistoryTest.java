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
import org.springframework.test.web.servlet.ResultActions;

/** History commands (HISTORY_QUERIES.md section 4): the range after an id opens the entity over time, and nothing else changes. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false"})
@AutoConfigureMockMvc
class CommandHistoryTest {

    @Autowired MockMvc mvc;

    private ResultActions command(String text) throws Exception {
        return mvc.perform(post("/api/v1/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}").header("X-Drishti-User", "hist-t"));
    }

    @Test
    void aNumberAfterTheIdIsTheLastDaysWithData() throws Exception {
        command("TRD IRS-48213 30 <GO>").andExpect(status().isOk())
                .andExpect(jsonPath("$.ref.id").value("IRS-48213"))
                .andExpect(jsonPath("$.history.range").value("30"))
                .andExpect(jsonPath("$.history.mode").value("data-days"))
                .andExpect(jsonPath("$.history.days").value(30))
                .andExpect(jsonPath("$.history.diff").value(false))
                .andExpect(jsonPath("$.history.query").value("days=30"));
    }

    @Test
    void calendarRangesBetweenAndDiff() throws Exception {
        command("trd irs-48213 3m diff").andExpect(jsonPath("$.ref.id").value("IRS-48213"))
                .andExpect(jsonPath("$.history.span").value("3M"))
                .andExpect(jsonPath("$.history.diff").value(true))
                .andExpect(jsonPath("$.history.query").value("range=3M&diff=1"));
        command("TRD IRS-48213 FROM 2026-09-01 TO 2026-09-30").andExpect(jsonPath("$.history.mode").value("between"))
                .andExpect(jsonPath("$.history.from").value("2026-09-01"))
                .andExpect(jsonPath("$.history.query").value("from=2026-09-01&to=2026-09-30"));
        command("NSET ns-north-01 YTD").andExpect(jsonPath("$.ref.kind").value("netting-set"))
                .andExpect(jsonPath("$.history.span").value("YTD"));
    }

    @Test
    void withoutARangeNothingChanges() throws Exception {
        command("TRD IRS-48213").andExpect(jsonPath("$.ref.id").value("IRS-48213")).andExpect(jsonPath("$.history").doesNotExist());
        command("TRD 30").andExpect(jsonPath("$.history").doesNotExist());           // a lone id after the mnemonic is an id
        command("TRD YTD").andExpect(jsonPath("$.history").doesNotExist());
    }

    @Test
    void aPrefixKeepsItsRange() throws Exception {
        command("TRD IRS-48 30").andExpect(jsonPath("$.ref.id").value("IRS-48213")).andExpect(jsonPath("$.history.days").value(30));
    }

    @Test
    void aRangeThatCannotHoldIsAnErrorNotAPickList() throws Exception {
        command("TRD IRS-48213 FROM 2026-09-30 TO 2026-09-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRS-4001"));
    }

    @Test
    void theTypeAheadOffersRangesAfterAnId() throws Exception {
        mvc.perform(get("/api/v1/command/suggest").param("q", "TRD IRS-48213 ").header("X-Drishti-User", "hist-t"))
                .andExpect(jsonPath("$[0].type").value("range"))
                .andExpect(jsonPath("$[0].complete").value("TRD IRS-48213 30"))
                .andExpect(jsonPath("$[1].complete").value("TRD IRS-48213 3M"));
        mvc.perform(get("/api/v1/command/suggest").param("q", "TRD IRS-48213 Y").header("X-Drishti-User", "hist-t"))
                .andExpect(jsonPath("$[0].complete").value("TRD IRS-48213 YTD"));
    }
}
