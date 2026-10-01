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

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** W17: structured search over the trading pack's trades, honouring entitlements and redaction. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.searcher.kinds[0]=trade", "drishti.security.roles.searcher.raw=true",
        "drishti.security.roles.masked.kinds[0]=trade", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.nothing.kinds[0]=curve"})
@AutoConfigureMockMvc
class StructuredSearchTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
    }

    @Test
    void findsSortsAndLimitsByFieldValues() throws Exception {
        String body = mvc.perform(get("/api/v1/search").param("q", "TRD where assetClass = 'Rates' and notional >= 100m order by mtm desc limit 5")
                        .header("Authorization", as("searcher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("trade"))
                .andExpect(jsonPath("$.rows", hasSize(5)))
                .andExpect(jsonPath("$.matched").value(greaterThan(5)))
                .andExpect(jsonPath("$.rows[*].values['$.notional']", everyItem(greaterThan(99_999_999.0))))
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = new ObjectMapper().readTree(body).get("rows");
        for (int i = 1; i < rows.size(); i++) {
            Assertions.assertThat(rows.get(i - 1).get("values").get("$.mtm").asDouble()).isGreaterThanOrEqualTo(rows.get(i).get("values").get("$.mtm").asDouble());
        }
        Assertions.assertThat(new ObjectMapper().readTree(body).get("columns").toString()).contains("$.assetClass", "$.notional", "$.mtm");
    }

    @Test
    void aRedactedFieldCannotBeProbedAndAKindYouCannotOpenIsRefused() throws Exception {
        String q = "TRD where trader startswith 'TRDR-'";
        mvc.perform(get("/api/v1/search").param("q", q).header("Authorization", as("searcher")))
                .andExpect(jsonPath("$.matched").value(greaterThan(0)));
        mvc.perform(get("/api/v1/search").param("q", q).header("Authorization", as("masked")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(0));
        mvc.perform(get("/api/v1/search").param("q", "TRD where mtm > 0").header("Authorization", as("nothing")))
                .andExpect(status().isForbidden());
    }

    @Test
    void badQueriesSayWhy() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "TRD where mtm >").header("Authorization", as("searcher")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4004"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where name = 'open").header("Authorization", as("searcher")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not closed")));
    }

    private org.springframework.test.web.servlet.ResultActions command(String text) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/command")
                .header("Authorization", as("searcher")).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isOk());
    }

    @Test
    void oneMatchOpensAndSeveralGiveAPickList() throws Exception {
        command("TRD T-10001 <GO>").andExpect(jsonPath("$.ref.id").value("T-10001"));                    // exists: opened
        command("trd t-10001").andExpect(jsonPath("$.ref.id").value("T-10001"));                          // case never matters
        command("TRD T-100").andExpect(jsonPath("$.ref").doesNotExist())                                 // T-10001 … T-10099: pick one
                .andExpect(jsonPath("$.list").value("TRD T-100")).andExpect(jsonPath("$.matched").value(greaterThan(1)));
        command("TRD productType=revolver").andExpect(jsonPath("$.ref").doesNotExist()).andExpect(jsonPath("$.matched").value(6));
        command("TRD productType=revolver and direction=nobody").andExpect(jsonPath("$.matched").value(0));

        mvc.perform(get("/api/v1/search").param("q", "TRD productType=Revolver").header("Authorization", as("searcher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(6)))
                .andExpect(jsonPath("$.rows[*].values['$.productType']", everyItem(org.hamcrest.Matchers.is("REVOLVER"))))
                .andExpect(jsonPath("$.columns", org.hamcrest.Matchers.hasItems("$.productType", "$.notional", "$.mtm", "$.book")));  // the pack's key fields
        mvc.perform(get("/api/v1/search").param("q", "TRD T-1000").header("Authorization", as("searcher")))
                .andExpect(jsonPath("$.rows[*].ref.id", everyItem(org.hamcrest.Matchers.startsWith("T-1000"))));
        mvc.perform(get("/api/v1/search").param("q", "TRD T-10001").header("Authorization", as("nothing"))).andExpect(status().isForbidden());
    }

    @Test
    void aPacksCodeOpensItsOverview() throws Exception {
        command("TRDS").andExpect(jsonPath("$.pack").value("trading")).andExpect(jsonPath("$.ref").doesNotExist());
        command("trading").andExpect(jsonPath("$.pack").value("trading"));
        mvc.perform(get("/api/v1/packs/TRDS/overview").header("Authorization", as("searcher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TRDS"))
                .andExpect(jsonPath("$.kinds[0].kind").value("trade"))
                .andExpect(jsonPath("$.kinds[0].mnemonic").value("TRD"))
                .andExpect(jsonPath("$.kinds[0].count").value(greaterThan(100)))
                .andExpect(jsonPath("$.kinds[0].columns[0]").value("productType"));
        mvc.perform(get("/api/v1/packs/trading/overview").header("Authorization", as("nothing")))
                .andExpect(jsonPath("$.kinds").isEmpty());                                     // kinds you may not open are left out
    }

    @Test
    void aliasesExpandAndHistoryRemembersWhatWasRead() throws Exception {
        String me = as("searcher");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/command/aliases").header("Authorization", me)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"revs\":\"TRD productType=Revolver\",\"first\":\"TRD T-10001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.REVS").value("TRD productType=Revolver"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/command/aliases").header("Authorization", me)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"TRD\":\"TRD T-1\"}"))
                .andExpect(status().isBadRequest());                                                 // a mnemonic is not free
        command("first <GO>").andExpect(jsonPath("$.ref.id").value("T-10001"));
        command("revs").andExpect(jsonPath("$.matched").value(6));
        java.util.concurrent.TimeUnit.MILLISECONDS.sleep(300);                                    // history is written in the background
        mvc.perform(get("/api/v1/command/history").header("Authorization", me))
                .andExpect(jsonPath("$[0]").value("revs")).andExpect(jsonPath("$[1]").value("first"));
    }

    @Test
    void authorsKeepTestEntitiesPerSutra() throws Exception {
        var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/me/studio-tests/irs-fixfloat")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("[{\"kind\":\"trade\",\"id\":\"T-10001\"},{\"kind\":\"trade\",\"id\":\"T-10001\"},{\"kind\":\"trade\",\"id\":\"T-10044\"}]");
        mvc.perform(put.header("Authorization", as("admin"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));   // duplicates once
        mvc.perform(get("/api/v1/me/studio-tests/irs-fixfloat").header("Authorization", as("admin"))).andExpect(jsonPath("$[1].id").value("T-10044"));
        mvc.perform(get("/api/v1/me/studio-tests/irs-fixfloat").header("Authorization", as("searcher"))).andExpect(status().isForbidden());
    }

    @Test
    void searchesComeAsCsvForSpreadsheets() throws Exception {
        String csv = mvc.perform(get("/api/v1/search/csv").param("q", "TRD productType=Revolver").header("Authorization", as("searcher")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String[] lines = csv.split("\r\n");
        org.assertj.core.api.Assertions.assertThat(lines[0]).startsWith("kind,id,title,");
        org.assertj.core.api.Assertions.assertThat(lines).hasSize(7);                              // a header and the 6 revolvers
        org.assertj.core.api.Assertions.assertThat(lines[1]).startsWith("trade,T-");
    }
}
