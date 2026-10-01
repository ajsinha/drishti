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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Calc's server side (PYTHON_CALC.md): who may use it (the {@code calc} power of a role), each user's saved snippets, and
 * whole columns of a business day ({@code drishti.columns()}) from a laid-out Delta table, checked and redacted as a
 * search is. The packs' snippets reach the console with {@code /packs}.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.layout-lake.plugin=delta",
        "drishti.sources.connectors.layout-lake.kinds[0]=trade",
        "drishti.sources.connectors.layout-lake.settings.root=../plugins/drishti-plugin-delta/src/test/resources/lake-layout",
        "drishti.sources.connectors.layout-lake.settings.domain=desk",
        "drishti.sources.connectors.layout-lake.settings.layout.trade.columns=mtm,book,nettingSet,counterparty.id",
        "drishti.sources.routes.trade=layout-lake",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact[0]=book",
        "drishti.security.roles.quant.kinds[0]=*", "drishti.security.roles.quant.calc=true",
        "drishti.security.roles.curves-quant.kinds[0]=curve", "drishti.security.roles.curves-quant.calc=true",
        "drishti.calc.max-column-rows=25", "drishti.calc.max-snippet-chars=200",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class CalcApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    @Test
    void theCalcPowerDecidesWhoMayUseIt() throws Exception {
        mvc.perform(get("/api/v1/calc/settings").header("Authorization", as("vera", "viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false)).andExpect(jsonPath("$.enabled").value(true));
        for (String role : new String[] {"quant", "author", "approver", "admin", "risk", "trader"}) {    // config, the finance pack, a site role
            mvc.perform(get("/api/v1/calc/settings").header("Authorization", as("u", role))).andExpect(jsonPath("$.allowed").value(true));
        }
        mvc.perform(get("/api/v1/calc/settings").header("Authorization", as("q", "quant")))
                .andExpect(jsonPath("$.maxColumnRows").value(25)).andExpect(jsonPath("$.maxSnippetChars").value(200));
        mvc.perform(get("/api/v1/me/calc-snippets").header("Authorization", as("vera", "viewer")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"))
                .andExpect(jsonPath("$.detail").value(containsString("role with calc")));
        mvc.perform(get("/api/v1/search/columns/TRD").param("paths", "mtm").header("Authorization", as("vera", "viewer")))
                .andExpect(status().isForbidden());
    }

    @Test
    void snippetsAreEachUsersOwn() throws Exception {
        String quinn = as("quinn", "quant");
        mvc.perform(put("/api/v1/me/calc-snippets/{n}", "Shift by 10bp").header("Authorization", quinn).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"print(view.id)\",\"kind\":\"trade\",\"description\":\"mine\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Shift by 10bp")).andExpect(jsonPath("$.kind").value("trade"));
        mvc.perform(get("/api/v1/me/calc-snippets").header("Authorization", quinn))
                .andExpect(jsonPath("$[*].name").value(contains("Shift by 10bp"))).andExpect(jsonPath("$[0].code").value("print(view.id)"));
        mvc.perform(get("/api/v1/me/calc-snippets").header("Authorization", as("ravi", "quant")))
                .andExpect(jsonPath("$.length()").value(0));                                             // another user sees none of them
        mvc.perform(put("/api/v1/me/calc-snippets/x").header("Authorization", quinn).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + "x".repeat(201) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/calc-snippets/x").header("Authorization", quinn).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/calc-snippets/-bad").header("Authorization", quinn).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"1\"}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/me/calc-snippets/{n}", "Shift by 10bp").header("Authorization", as("ravi", "quant")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/me/calc-snippets/{n}", "Shift by 10bp").header("Authorization", quinn)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/calc-snippets").header("Authorization", quinn)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void columnsAreTheWholeDayAsTheCallerMaySeeIt() throws Exception {
        mvc.perform(get("/api/v1/search/columns/TRD").header("Authorization", as("q", "quant")))       // which fields are kept as columns
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("trade"))
                .andExpect(jsonPath("$.available").value(contains("book", "counterparty.id", "mtm", "nettingSet")));
        mvc.perform(get("/api/v1/search/columns/trade").param("paths", "$.mtm,book").header("Authorization", as("q", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.businessDate").value("2026-09-30"))
                .andExpect(jsonPath("$.total").value(40)).andExpect(jsonPath("$.rows").value(25)).andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.paths").value(contains("mtm", "book"))).andExpect(jsonPath("$.ids[0]").value("T-001"))
                .andExpect(jsonPath("$.values.mtm[0]").value(-18900)).andExpect(jsonPath("$.values.book[0]").value("BOOK-A"))
                .andExpect(jsonPath("$.masked.length()").value(0));
        mvc.perform(get("/api/v1/search/columns/TRD").param("paths", "mtm,book").param("limit", "3").header("Authorization", as("q", "quant")))
                .andExpect(jsonPath("$.rows").value(3))
                .andExpect(jsonPath("$.values.book").value(everyItem(is("•••"))))                        // quant has no raw: book is masked
                .andExpect(jsonPath("$.masked").value(contains("book")));
        mvc.perform(get("/api/v1/search/columns/TRD").param("paths", "mtm").header("Authorization", as("q", "quant")).header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(jsonPath("$.businessDate").value("2026-09-29"));
    }

    @Test
    void columnsAreRefusedForWhatTheCallerMayNotOpenOrTheSourceDoesNotKeep() throws Exception {
        mvc.perform(get("/api/v1/search/columns/TRD").param("paths", "mtm").header("Authorization", as("c", "curves-quant")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(containsString("may not open trade")));
        mvc.perform(get("/api/v1/search/columns/TRD").param("paths", "mtm,notional").header("Authorization", as("q", "quant")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("not kept as columns for trade: [notional]")));
        mvc.perform(get("/api/v1/search/columns/TRD").param("paths", "mtm;drop").header("Authorization", as("q", "quant")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search/columns/NOPE").param("paths", "mtm").header("Authorization", as("q", "quant")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theFinancePackOffersCalcWithItsSnippets() throws Exception {
        mvc.perform(get("/api/v1/packs").header("Authorization", as("q", "quant"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'finance')].python.enabled").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.name == 'finance')].python.snippets[*].title").value(hasItem("MTM under parallel rate moves")))
                .andExpect(jsonPath("$[?(@.name == 'finance')].python.snippets[*].kinds[*]").value(hasItem("netting-set")));
    }
}
