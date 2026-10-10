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
                .andExpect(jsonPath("$.form.fields.length()").value(greaterThan(3)))
                .andExpect(jsonPath("$.form.fields[?(@.path=='mtm')].type").value(hasItem("amount")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='notional')].indexed").exists());
    }

    @Test
    void theFormOfAPackYouCannotOpenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/forms/trading").header("Authorization", as("nothing"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/forms/no-such-pack").header("Authorization", as("searcher"))).andExpect(status().isBadRequest());
    }

    @Test
    void criteriaBecomeASearchTheServerRuns() throws Exception {
        compile("searcher", "{\"criteria\":[{\"path\":\"currency\",\"op\":\"in\",\"values\":[\"EUR\",\"USD\"]},"
                + "{\"path\":\"notional\",\"op\":\">=\",\"value\":\"100m\"}],\"orderBy\":\"mtm\",\"descending\":true,\"limit\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.command").value("TRD where (currency = 'EUR' or currency = 'USD') and notional >= 100m order by mtm desc limit 5"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where (currency = 'EUR' or currency = 'USD') and notional >= 100m order by mtm desc limit 5")
                .header("Authorization", as("searcher"))).andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(greaterThan(5)));
        compile("searcher", "{\"criteria\":[{\"path\":\"notional\",\"op\":\">\",\"value\":\"lots\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4004")).andExpect(jsonPath("$.detail").value(containsString("not a number")));
        compile("nothing", "{\"criteria\":[]}").andExpect(status().isForbidden());
    }

    @Test
    void regexSearchesRunAndUnsafeOnesAreRefusedWithACode() throws Exception {
        compile("searcher", "{\"criteria\":[{\"path\":\"productType\",\"op\":\"regex\",\"value\":\"^REVOL\"}]}")
                .andExpect(jsonPath("$.command").value("TRD where matches(productType, '^REVOL')"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(productType, '^REVOL')").header("Authorization", as("searcher")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(6));
        mvc.perform(get("/api/v1/search").param("q", "TRD productType matches '^revol.*r$'").header("Authorization", as("searcher")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(6));
        mvc.perform(get("/api/v1/search").param("q", "TRD where matchesCase(productType, 'revol')").header("Authorization", as("searcher")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(0));
        compile("searcher", "{\"criteria\":[{\"path\":\"productType\",\"op\":\"regex\",\"value\":\"(a)\\\\1\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4010"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(productType, '(?=x)y')").header("Authorization", as("searcher")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4010"));
        long t = System.nanoTime();                                          // a catastrophic pattern finishes in no time
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(productType, '(a+)+$')").header("Authorization", as("searcher")))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(5_000);
    }

    @Test
    void aMaskedFieldCannotBeProbedByARegexEither() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(trader, '^TRDR-')").header("Authorization", as("searcher")))
                .andExpect(jsonPath("$.matched").value(greaterThan(0)));
        mvc.perform(get("/api/v1/search").param("q", "TRD where matches(trader, '^TRDR-')").header("Authorization", as("masked")))
                .andExpect(jsonPath("$.matched").value(0));
    }
}
