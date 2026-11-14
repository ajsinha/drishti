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
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading", "drishti.live-sets.modules-dir=../../modules",
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

    @Test
    void aFormKeptForOneCallerIsNotServedToACallerWithMasks() throws Exception {
        // the form is kept per data version; a caller who sees TRADER_ID masked must not get the dropdown built for one who does not
        mvc.perform(get("/api/v1/forms/trading").header("Authorization", as("searcher")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='TRADER_ID')].values[*]", hasItem(org.hamcrest.Matchers.startsWith("TRDR-"))));
        mvc.perform(get("/api/v1/forms/trading").header("Authorization", as("masked")))
                .andExpect(jsonPath("$.form.fields[?(@.path=='TRADER_ID')].values[*]", org.hamcrest.Matchers.not(hasItem(org.hamcrest.Matchers.startsWith("TRDR-")))));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** A FORM's result is a live result set: the same rows, columns and order as the search it compiled to, with the search's summary. */
    @Test
    void aFormsResultIsALiveResultSetWithTheSearchsRowsColumnsAndOrder() throws Exception {
        String command = json(compile("searcher",
                "{\"criteria\":[{\"path\":\"CURRENCY\",\"op\":\"in\",\"values\":[\"EUR\",\"USD\"]}],\"orderBy\":\"QUANTITY\",\"descending\":true,\"limit\":25}"))
                .path("command").asText();
        var searched = json(mvc.perform(get("/api/v1/search").param("q", command).header("Authorization", as("searcher"))));
        var live = json(mvc.perform(get("/api/v1/live/query").param("q", command).header("Authorization", as("searcher"))));
        org.assertj.core.api.Assertions.assertThat(live.path("search").path("columns")).isEqualTo(searched.path("columns"));
        org.assertj.core.api.Assertions.assertThat(live.path("search").path("labels")).isEqualTo(searched.path("labels"));
        org.assertj.core.api.Assertions.assertThat(live.path("search").path("matched")).isEqualTo(searched.path("matched"));
        org.assertj.core.api.Assertions.assertThat(live.path("total").asInt()).isEqualTo(searched.path("rows").size()).isPositive();
        java.util.List<String> want = new java.util.ArrayList<>();
        searched.path("rows").forEach(r -> want.add(r.path("ref").path("id").asText()));
        java.util.List<String> got = new java.util.ArrayList<>();
        live.path("rows").forEach(r -> got.add(r.path("id").asText()));
        org.assertj.core.api.Assertions.assertThat(got).containsExactlyElementsOf(want);
        // each row's document holds the search's values at the columns' fields
        var first = searched.path("rows").get(0).path("values");
        first.fieldNames().forEachRemaining(path -> org.assertj.core.api.Assertions
                .assertThat(live.path("rows").get(0).path("doc").path(path.substring(2))).as(path).isEqualTo(first.path(path)));
        String id = live.path("set").path("id").asText();
        org.assertj.core.api.Assertions.assertThat(id).startsWith("q-");
        org.assertj.core.api.Assertions.assertThat(live.path("set").path("editable").asBoolean()).isFalse();
        // a page of it, sorted another way; its declaration is not listed among the blotters
        mvc.perform(get("/api/v1/live/" + id + "/rows").param("limit", "5").param("sort", "TRADE_ID").header("Authorization", as("searcher")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(5));
        mvc.perform(get("/api/v1/live").header("Authorization", as("searcher"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sets[?(@.id=='" + id + "')]").isEmpty());
        // the trading module's trade blotter passed the checks at load (formats, the trade contract, the write path): it is listed
        mvc.perform(get("/api/v1/live").header("Authorization", as("searcher"))).andExpect(jsonPath("$.sets[?(@.id=='trade')]").isNotEmpty());
        // a masked person (another audience) gets their own set, and may not read the other one by its id
        var masked = json(mvc.perform(get("/api/v1/live/query").param("q", command).header("Authorization", as("masked"))));
        org.assertj.core.api.Assertions.assertThat(masked.path("set").path("id").asText()).startsWith("q-").isNotEqualTo(id);
        mvc.perform(get("/api/v1/live/" + id + "/rows").header("Authorization", as("masked"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/live/query").param("q", command).header("Authorization", as("nothing"))).andExpect(status().isForbidden());
    }
}
