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

/** History commands (DATA_ARCHITECTURE.md › History queries: an entity over time, section 4): the range after an id opens the entity over time, and nothing else changes. */
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
        // QA1-12: the same code as the endpoint (DRS-4022), not "command unknown"
        command("TRD IRS-48213 FROM 2026-09-30 TO 2026-09-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRS-4022"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("is after TO")));
    }

    @Test
    void aBadRangeAfterAnEntityIsNamedNeverTodaysViewNorASearch() throws Exception {
        // QA1-03: every row of the finding's table answers its DRS code and what to type
        String[][] cases = {{"TRD IRS-48213 0", "DRS-4022", "0 days is empty"}, {"TRD IRS-48213 -5", "DRS-4022", "'-5' is not a range"},
            {"TRD IRS-48213 99999", "DRS-4021", "business days at most (asked 99999)"}, {"TRD IRS-48213 1.5M", "DRS-4022", "'1.5M' is not a range"},
            {"TRD IRS-48213 3X", "DRS-4022", "'3X' is not a range"}, {"TRD IRS-48213 DIFF DIFF", "DRS-4022", "DIFF is said once"},
            {"TRD IRS-48213 30 DIFF DIFF", "DRS-4022", "TRD IRS-48213 30 DIFF"},
            {"TRD IRS-48213 FROM 2026-02-30", "DRS-4022", "'2026-02-30' is not a date"},
            {"TRD IRS-48213 FROM yesterday", "DRS-4022", "'yesterday' is not a date"}, {"TRD IRS-48213 DIFF", "DRS-4022", "DIFF follows a range"},
            {"TRD IRS-48213 30 30", "DRS-4022", "two ranges"}, {"TRD IRS-48213 99999 DIFF", "DRS-4021", "asked 99999"}};
        for (String[] c : cases) {
            command(c[0]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(c[1]))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(c[2])));
        }
        // not after one entity: as before (a lone id after the mnemonic, a prefix that is no entity)
        command("TRD 0").andExpect(jsonPath("$.code").doesNotExist());
        command("TRD NO-SUCH-TRADE-X 0").andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void theFormsThatStillEscapedAreNamedToo() throws Exception {
        // QA1-203: a trailing point, an exponent, TO without its date or its FROM, DIFF first, digits of another script
        String[][] cases = {{"TRD IRS-48213 30.", "'30.' is not a range"}, {"TRD IRS-48213 1e3", "'1e3' is not a range"},
            {"TRD IRS-48213 FROM 2026-10-01 TO", "TO needs a date"}, {"TRD IRS-48213 TO 2026-10-01", "TO follows FROM"},
            {"TRD IRS-48213 DIFF 30", "DIFF follows a range: TRD IRS-48213 30 DIFF"}, {"TRD IRS-48213 \uFF10", "0 days is empty"}};
        for (String[] c : cases) {
            command(c[0]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4022"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(c[1])));
        }
        command("TRD IRS-48213 \uFF130").andExpect(status().isOk()).andExpect(jsonPath("$.history.query").value("days=30"));
        // a bare id in plain words with a range that is none: DRS-4022 too
        mvc.perform(get("/api/v1/phrase").param("text", "IRS-48213 -5").header("X-Drishti-User", "hist-t"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4022"));
        mvc.perform(get("/api/v1/phrase").param("text", "IRS-48213 since yesterday").header("X-Drishti-User", "hist-t"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("'yesterday' is not a date")));
    }

    @Test
    void plainWordsReadABareIdAndAnIdInAnyCase() throws Exception {
        // QA1-08: a bare id that names one entity, then a range; an id typed in lower case after the kind word
        mvc.perform(get("/api/v1/phrase").param("text", "IRS-48213 30").header("X-Drishti-User", "hist-t"))
                .andExpect(jsonPath("$.command").value("TRD IRS-48213 30"));
        mvc.perform(get("/api/v1/phrase").param("text", "trade irs-48213 last 3 months").header("X-Drishti-User", "hist-t"))
                .andExpect(jsonPath("$.command").value("TRD IRS-48213 3M"));
        mvc.perform(get("/api/v1/phrase").param("text", "NO-SUCH-ID-77 30").header("X-Drishti-User", "hist-t"))
                .andExpect(jsonPath("$.command").doesNotExist());
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
