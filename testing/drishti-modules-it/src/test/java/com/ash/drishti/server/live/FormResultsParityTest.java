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
package com.ash.drishti.server.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.engine.search.SearchFilter;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The search grammar compiled to the Data Protocol ({@link SearchFilter}) answers what the search answers, on the demo lake's trades: for
 * every form of search a FORM writes (equality ignoring case, {@code in} as {@code or}, amounts, ranges, {@code startswith},
 * {@code contains}, {@code not}, a regular expression, an id pattern, orders and limits), the live table's rows are the search's matches,
 * counted the same and in the same order. As of a past business date the table is a static snapshot of that day.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.searcher.kinds[0]=trade", "drishti.security.roles.searcher.raw=true"})
@AutoConfigureMockMvc
class FormResultsParityTest {

    static final List<String> SEARCHES = List.of(
            "TRD where CURRENCY = 'EUR'",
            "TRD where currency = usd",
            "TRD where (CURRENCY = 'EUR' or CURRENCY = 'USD') and QUANTITY >= 100m order by QUANTITY desc limit 25",
            "TRD where SOURCE_SYSTEM = 'ENDUR' and PRODUCT_TYPE = 'ComSwap'",
            "TRD where PRODUCT_TYPE startswith 'irs' order by TRADE_ID limit 50",
            "TRD where COUNTERPARTY contains 'meridian'",
            "TRD where not TRADE_STATUS = 'CANCELLED' and QUANTITY < 5m order by QUANTITY limit 40",
            "TRD where matches(PRODUCT_TYPE, '^REVOL')",
            "TRD where PRICE > 0 and PRICE <= 100 order by PRICE desc limit 30",
            "TRD order by QUANTITY desc limit 10");

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired StructuredSearch search;
    final ObjectMapper json = new ObjectMapper();

    private String as() {
        return "Bearer " + tokens.mint("u-searcher", List.of("searcher"), 300);
    }

    private JsonNode read(String path, String q) throws Exception {
        var r = mvc.perform(get(path).param("q", q).header("Authorization", as())).andReturn().getResponse();
        assertThat(r.getStatus()).as(path + " " + q + ": " + r.getContentAsString()).isEqualTo(200);
        return json.readTree(r.getContentAsString());
    }

    static List<String> ids(JsonNode page) {
        int at = 0;
        List<String> cols = new ArrayList<>();
        page.path("columns").forEach(c -> cols.add(c.isTextual() ? c.asText() : c.path("name").asText()));
        at = cols.indexOf(SearchFilter.KEY);
        List<String> out = new ArrayList<>();
        int col = at;
        page.path("values").forEach(v -> out.add(v.get(col).asText()));
        return out;
    }

    @Test
    void everyFormOfSearchAnswersTheSearchsRowsCountAndOrder() throws Exception {
        for (String q : SEARCHES) {
            JsonNode searched = read("/api/v1/search", q);
            JsonNode live = read("/api/v1/rdp/searches", q);
            List<String> want = new ArrayList<>();
            searched.path("rows").forEach(r -> want.add(r.path("ref").path("id").asText()));
            assertThat(live.path("page").path("total").asLong()).as("matched: " + q).isEqualTo(searched.path("matched").asLong());
            assertThat(ids(live.path("page"))).as("rows: " + q).containsExactlyElementsOf(want);
            assertThat(live.path("search").path("columns")).as("columns: " + q).isEqualTo(searched.path("columns"));
        }
    }

    @Test
    void aConditionInTheTreesSubsetTravelsAsAFilterAndOneOutsideStaysInTheTable() throws Exception {
        SearchFilter eur = SearchFilter.compile(search, "TRD where currency = eur and QUANTITY >= 1.5m");
        assertThat(eur.pushed()).isTrue();
        assertThat(json.writeValueAsString(eur.tree())).contains("\"col\":\"CURRENCY\"").contains("\"lit\":\"eur\"").contains("1500000");
        assertThat(eur.columns()).contains("id", "CURRENCY", "QUANTITY");
        SearchFilter regex = SearchFilter.compile(search, "TRD where matches(PRODUCT_TYPE, '^REVOL')");
        assertThat(regex.pushed()).isFalse();
        assertThat(regex.condition()).contains("@.PRODUCT_TYPE");
        SearchFilter ids = SearchFilter.compile(search, "TRD MX-2*");
        assertThat(ids.condition()).contains("starts_with(@.id, 'mx-2')");
        assertThat(ids.pushed()).isTrue();
        // the table answers further pages through the protocol with the same filter
        JsonNode live = read("/api/v1/rdp/searches", "TRD where currency = eur limit 5");
        var r = mvc.perform(post("/api/v1/rdp/tables/" + live.path("table").asText() + "/query").contentType(MediaType.APPLICATION_JSON)
                .content("{\"filter\":" + live.path("query").path("filter") + ",\"sort\":[{\"col\":\"id\"}],\"offset\":5,\"limit\":5}")
                .header("Authorization", as())).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(ids(json.readTree(r.getContentAsString()))).hasSize(5).doesNotContainAnyElementsOf(ids(live.path("page")));
    }

    @Test
    void aPastBusinessDateIsAStaticSnapshotOfThatDay() throws Exception {
        JsonNode dates = json.readTree(mvc.perform(get("/api/v1/business-date").header("Authorization", as())).andReturn().getResponse().getContentAsString());
        String q = "TRD where CURRENCY = 'EUR'";
        JsonNode live = read("/api/v1/rdp/searches", q);
        assertThat(live.path("live").asBoolean()).isTrue();
        String day = java.time.LocalDate.parse(dates.path("current").asText()).minusDays(3).toString();   // a past day: static
        var r = mvc.perform(get("/api/v1/rdp/searches").param("q", q).param("asOf", day).header("Authorization", as())).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode past = json.readTree(r.getContentAsString());
        assertThat(past.path("live").asBoolean()).isFalse();
        assertThat(past.path("table").asText()).isNotEqualTo(live.path("table").asText());
        JsonNode searched = json.readTree(mvc.perform(get("/api/v1/search").param("q", q).param("asOf", day).header("Authorization", as()))
                .andReturn().getResponse().getContentAsString());
        assertThat(past.path("page").path("total").asLong()).isEqualTo(searched.path("matched").asLong());
        assertThat(json.readTree(mvc.perform(get("/api/v1/rdp/tables/" + past.path("table").asText()).header("Authorization", as()))
                .andReturn().getResponse().getContentAsString()).path("live").toString()).doesNotContain("\"stream\":true");
    }
}
