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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Query by form: the form of a kind, its criteria compiled, and the compiled command run by the ordinary search (regular expressions included). */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.searcher.kinds[0]=trade", "drishti.security.roles.searcher.raw=true",
        "drishti.security.roles.masked.kinds[0]=trade", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.nothing.kinds[0]=curve"})
@AutoConfigureMockMvc
class FormApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
    }

    private org.springframework.test.web.servlet.ResultActions compile(String role, String body) throws Exception {
        return mvc.perform(post("/api/v1/forms/trading/trade/compile").header("Authorization", as(role)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aKindHasAFormWithTypedFields() throws Exception {
        mvc.perform(get("/api/v1/forms/trading").header("Authorization", as("searcher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("trade")).andExpect(jsonPath("$.mnemonic").value("TRD"))
                .andExpect(jsonPath("$.kinds[*].kind", hasItem("trade")))
                .andExpect(jsonPath("$.kinds[0].declared").value(true))                      // the trading pack declares its form: groups, order, help
                .andExpect(jsonPath("$.form.inferred").value(false))
                .andExpect(jsonPath("$.form.fields[0].path").value("TRADE_ID"))
                .andExpect(jsonPath("$.form.fields[?(@.path=='QUANTITY')].group").value(hasItem("Money")))
                .andExpect(jsonPath("$.form.fields.length()").value(greaterThan(3)))
                .andExpect(jsonPath("$.form.fields[?(@.path=='QUANTITY')].type").value(hasItem("amount")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='QUANTITY')].indexed").exists())
                .andExpect(jsonPath("$.form.fields[?(@.path=='TRADE_DATE')].type").value(hasItem("date")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='MATURITY_DATE')].type").value(hasItem("date")));
    }

    @Test
    void aDropdownListsTheValuesOfTheWholeKindNotOnlyItsFirstRows() throws Exception {
        // the demo trades come grouped by product (rates first): the first 200 rows hold no FX product, but the dropdown must offer it;
        // the drop-downs offer the values of every source system (ENDUR's own product types too)
        mvc.perform(get("/api/v1/forms/trading").header("Authorization", as("searcher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.fields[?(@.path=='PRODUCT_TYPE')].values[*]").value(hasItem("IRS_FIXFLOAT")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='PRODUCT_TYPE')].values[*]").value(hasItem("FX_BARRIER")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='PRODUCT_TYPE')].values[*]").value(hasItem("ComSwap")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='SOURCE_SYSTEM')].values[*]").value(org.hamcrest.Matchers.hasItems("ENDUR", "CALYPSO", "MUREX")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='COUNTERPARTY')].values[*]").value(hasItem("CP-MERIDIAN-RE")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='PRODUCT_SUB_TYPE')].depends").value(hasItem("PRODUCT_TYPE")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='PRODUCT_SUB_TYPE')].parents['ENGY-SWAP'][*]").value(hasItem("ComSwap")));
    }

    @Test
    void theFormOfAPackYouCannotOpenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/forms/trading").header("Authorization", as("nothing"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/forms/no-such-pack").header("Authorization", as("searcher"))).andExpect(status().isBadRequest());
    }

    @Test
    void criteriaBecomeASearchTheServerRuns() throws Exception {
        compile("searcher", "{\"criteria\":[{\"path\":\"CURRENCY\",\"op\":\"in\",\"values\":[\"EUR\",\"USD\"]},"
                + "{\"path\":\"QUANTITY\",\"op\":\">=\",\"value\":\"100m\"}],\"orderBy\":\"QUANTITY\",\"descending\":true,\"limit\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.command").value("TRD where (CURRENCY = 'EUR' or CURRENCY = 'USD') and QUANTITY >= 100m order by QUANTITY desc limit 5"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where (CURRENCY = 'EUR' or CURRENCY = 'USD') and QUANTITY >= 100m order by QUANTITY desc limit 5")
                .header("Authorization", as("searcher"))).andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(greaterThan(5)));
        compile("searcher", "{\"criteria\":[{\"path\":\"SOURCE_SYSTEM\",\"op\":\"in\",\"values\":[\"ENDUR\"]},"
                + "{\"path\":\"PRODUCT_TYPE\",\"op\":\"in\",\"values\":[\"ComSwap\"]}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.command").value("TRD where SOURCE_SYSTEM = 'ENDUR' and PRODUCT_TYPE = 'ComSwap'"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where SOURCE_SYSTEM = 'ENDUR' and PRODUCT_TYPE = 'ComSwap'").header("Authorization", as("searcher")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(3));
        compile("searcher", "{\"criteria\":[{\"path\":\"QUANTITY\",\"op\":\">=\",\"value\":\"lots\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4004")).andExpect(jsonPath("$.detail").value(containsString("not a number")));
        compile("nothing", "{\"criteria\":[]}").andExpect(status().isForbidden());
    }

    @Test
    void regexSearchesRunAndUnsafeOnesAreRefusedWithACode() throws Exception {
        compile("searcher", "{\"criteria\":[{\"path\":\"PRODUCT_SUB_TYPE\",\"op\":\"in\",\"values\":[\"Revolving credit facility\"]}]}")
                .andExpect(jsonPath("$.command").value("TRD where PRODUCT_SUB_TYPE = 'Revolving credit facility'"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(PRODUCT_TYPE, '^REVOL')").header("Authorization", as("searcher")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(6));
        mvc.perform(get("/api/v1/search").param("q", "TRD PRODUCT_TYPE matches '^revol.*r$'").header("Authorization", as("searcher")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(6));
        mvc.perform(get("/api/v1/search").param("q", "TRD where matchesCase(PRODUCT_TYPE, 'revol')").header("Authorization", as("searcher")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(0));
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(PRODUCT_TYPE, '(a)\\1')").header("Authorization", as("searcher")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4010"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(PRODUCT_TYPE, '(?=x)y')").header("Authorization", as("searcher")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4010"));
        long t = System.nanoTime();                                          // a catastrophic pattern finishes in no time
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(PRODUCT_TYPE, '(a+)+$')").header("Authorization", as("searcher")))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(5_000);
    }

    @Test
    void aMaskedFieldCannotBeProbedByARegexEither() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(TRADER_ID, '^TRDR-')").header("Authorization", as("searcher")))
                .andExpect(jsonPath("$.matched").value(greaterThan(0)));
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(TRADER_ID, '^TRDR-')").header("Authorization", as("masked")))
                .andExpect(jsonPath("$.matched").value(0));
    }
}
