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
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** A picked business date flows from the request header through the engine to a Delta Lake connector. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance",
        "drishti.sources.connectors.finance-lake.settings.root=../plugins/drishti-plugin-delta/src/test/resources/lake",
        "drishti.sources.connectors.finance-lake.settings.domain=desk",
        "drishti.sources.connectors.finance-lake.settings.mode.counterparty=effective"})
@AutoConfigureMockMvc
class BusinessDateTest {

    @Autowired MockMvc mvc;

    @Test
    void theBusinessDateEndpointDescribesTheCalendar() throws Exception {
        mvc.perform(get("/api/v1/business-date")).andExpect(status().isOk())
                .andExpect(jsonPath("$.calendar").value("USNY"))
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.current").value(notNullValue()))
                .andExpect(jsonPath("$.holidays").value(hasItem("2026-11-26")));
        // a Saturday rolls back to Friday; a picked date is a static snapshot
        mvc.perform(get("/api/v1/business-date").header("X-Drishti-As-Of", "2026-09-26"))
                .andExpect(jsonPath("$.selected").value("2026-09-25"))
                .andExpect(jsonPath("$.live").value(false));
        mvc.perform(get("/api/v1/business-date").header("X-Drishti-As-Of", "2999-01-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4003"));
        mvc.perform(get("/api/v1/business-date").param("asOf", "yesterday"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4003"));
    }

    @Test
    void aPickedDateReadsThatDaysDataAndIsStatic() throws Exception {
        mvc.perform(get("/api/v1/views/trade/T-1").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance.source").value("finance-lake"))
                .andExpect(jsonPath("$.provenance.businessDate").value("2026-09-29"))
                .andExpect(jsonPath("$.provenance.live").value(false))
                .andExpect(jsonPath("$.strip[*].text").value(hasItem("+110")));
        mvc.perform(get("/api/v1/views/trade/T-1").param("asOf", "2026-09-28"))
                .andExpect(jsonPath("$.strip[*].text").value(hasItem("+100")));
        mvc.perform(get("/api/v1/entities/counterparty/CP-X/raw").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(jsonPath("$.data.rating").value("A"));
    }

    @Test
    void anUndatedSourceStillAnswersAndSaysSo() throws Exception {
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance.businessDate").doesNotExist())
                .andExpect(jsonPath("$.provenance.live").value(false));
        mvc.perform(get("/api/v1/views/trade/IRS-48213"))
                .andExpect(jsonPath("$.provenance.live").value(true));
    }

    @Test
    void historyComparesTwoBusinessDates() throws Exception {
        mvc.perform(get("/api/v1/history/trade/T-1/diff").param("from", "2026-09-28").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from.businessDate").value("2026-09-28"))
                .andExpect(jsonPath("$.to.businessDate").value("2026-09-30"))
                .andExpect(jsonPath("$.changed").value(1))
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.changes[?(@.path=='mtm')].delta").value(hasItem(25.0)))
                .andExpect(jsonPath("$.changes[?(@.path=='mtm')].label").value(hasItem("MTM")))
                .andExpect(jsonPath("$.changes[?(@.path=='restated')].kind").value(hasItem("added")));
        // "to" defaults to the date box, "from" to the business day before it
        mvc.perform(get("/api/v1/history/trade/T-1/diff").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(jsonPath("$.from.businessDate").value("2026-09-28"))
                .andExpect(jsonPath("$.changes[0].before").value(100))
                .andExpect(jsonPath("$.changes[0].after").value(110));
    }

    @Test
    void aFieldsSeriesReadsEachBusinessDay() throws Exception {
        mvc.perform(get("/api/v1/history/trade/T-1/series").param("path", "mtm").param("days", "3").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("$.mtm"))
                .andExpect(jsonPath("$.dated").value(true))
                .andExpect(jsonPath("$.points[*].date").value(org.hamcrest.Matchers.contains("2026-09-28", "2026-09-29", "2026-09-30")))
                .andExpect(jsonPath("$.points[*].value").value(org.hamcrest.Matchers.contains(100, 110, 125)))
                .andExpect(jsonPath("$.points[2].source").value("finance-lake"));
        mvc.perform(get("/api/v1/history/trade/T-1/series").param("path", "mtm +").param("days", "3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSearchComparedBetweenTwoDatesShowsTheChange() throws Exception {
        mvc.perform(get("/api/v1/search/compare").param("q", "TRD where mtm > 0").param("from", "2026-09-28").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-28"))
                .andExpect(jsonPath("$.rows[?(@.ref.id=='T-1')].values['$.mtm'].delta").value(hasItem(25.0)));
    }
}
