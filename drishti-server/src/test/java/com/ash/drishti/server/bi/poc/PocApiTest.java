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
package com.ash.drishti.server.bi.poc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * RUPAKA PHASE 0 PROOF OF CONCEPT: the query endpoint answers Arrow that decodes, applies the field masks column by column
 * for a user without raw (and cannot be probed through a filter), is for administrators, and compares the three layouts.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=trading",
        "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.masked-admin.kinds[0]=*", "drishti.security.roles.masked-admin.admin=true",
        "drishti.bi.poc.enabled=true", "drishti.bi.poc.rows=2000", "drishti.bi.poc.days=2",
        "drishti.bi.poc.lake-dir=target/poc-lake-api",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class PocApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private ArrowTestReader.Table query(String role, String body) throws Exception {
        byte[] bytes = mvc.perform(post("/api/v1/bi/poc/query").header("Authorization", as("u", role)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/vnd.apache.arrow.stream"))
                .andReturn().getResponse().getContentAsByteArray();
        return ArrowTestReader.read(bytes);
    }

    @Test
    void theAggregationComesBackAsArrowThatDecodes() throws Exception {
        var t = query("admin", "{\"groupBy\":[\"desk\",\"productType\"]}");
        assertThat(t.columns().keySet()).containsExactly("desk", "productType", "notional", "mtm", "trades");
        assertThat(t.rows()).isEqualTo(60);                                           // 6 desks x 10 products
        long trades = t.columns().get("trades").stream().mapToLong(v -> (Long) v).sum();
        assertThat(trades).isEqualTo(4000);                                            // 2,000 a day for 2 days
        assertThat(t.columns().get("notional")).doesNotContainNull();
    }

    @Test
    void allThreeLayoutsAnswerTheSame() throws Exception {
        var typed = query("admin", "{\"groupBy\":[\"currency\"],\"layout\":\"typed\"}");
        var json = query("admin", "{\"groupBy\":[\"currency\"],\"layout\":\"json\"}");
        var rollup = query("admin", "{\"groupBy\":[\"currency\"],\"layout\":\"rollup\"}");
        assertThat(json.columns().get("trades")).isEqualTo(typed.columns().get("trades"));
        assertThat(rollup.columns().get("trades")).isEqualTo(typed.columns().get("trades"));
        assertThat(json.columns().get("mtm")).isEqualTo(typed.columns().get("mtm"));
        assertThat(rollup.columns().get("notional")).isEqualTo(typed.columns().get("notional"));
    }

    @Test
    void aMaskedColumnComesBackAsTheMaskForAUserWithoutRaw() throws Exception {
        var seen = query("admin", "{\"groupBy\":[\"trader\"]}");
        assertThat(seen.rows()).isEqualTo(12);
        var masked = query("masked-admin", "{\"groupBy\":[\"trader\",\"desk\"]}");
        assertThat(masked.columns().get("trader")).containsOnly("•••");     // never grouped on: no value can be told apart
        assertThat(masked.rows()).isEqualTo(6);                                              // the 6 desks, the trader a constant
        var preview = query("admin", "{\"groupBy\":[\"trader\"],\"preview\":\"masked\"}");   // an administrator checking what others see
        assertThat(preview.columns().get("trader")).containsExactly("•••");
        assertThat(preview.columns().get("trades").get(0)).isEqualTo(4000L);
        mvc.perform(post("/api/v1/bi/poc/query").header("Authorization", as("u", "masked-admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupBy\":[\"desk\"],\"filter\":{\"column\":\"trader\",\"value\":\"TRDR-ASHAH\"}}"))
                .andExpect(status().isForbidden());                                          // a masked field cannot be probed with a filter
    }

    @Test
    void onlyAdministratorsAndOnlyKnownColumns() throws Exception {
        mvc.perform(post("/api/v1/bi/poc/query").header("Authorization", as("v", "viewer")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupBy\":[\"desk\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bi/poc/query").header("Authorization", as("a", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupBy\":[\"doc; drop table x\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/bi/poc/query").header("Authorization", as("a", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupBy\":[]}")).andExpect(status().isBadRequest());
        var filtered = query("admin", "{\"groupBy\":[\"desk\"],\"filter\":{\"column\":\"currency\",\"value\":\"USD' OR '1'='1\"}}");
        assertThat(filtered.rows()).isZero();                                                // a value is a bound parameter, never SQL
    }

    @Test
    void theLayoutComparisonReportsLatencyAndBytes() throws Exception {
        mvc.perform(get("/api/v1/bi/poc/bench").param("runs", "5").header("Authorization", as("a", "admin")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("\"rollup\"")))
                .andExpect(content().string(containsString("\"bytesRead\"")));
        mvc.perform(get("/api/v1/bi/poc/bench").header("Authorization", as("v", "viewer"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bi/poc/query").header("Authorization", as("a", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupBy\":[\"desk\"]}")).andExpect(header().string("X-Poc-Layout", "typed"));
    }
}
