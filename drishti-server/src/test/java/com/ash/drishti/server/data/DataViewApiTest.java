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
package com.ash.drishti.server.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * /api/v1/data (DATA): the datasets a caller may open, a dataset's columns less the masked ones, a selection's row count with its words and
 * SQL-like line, and the download: the same rows as JSON Lines, gzip-compressed, only the chosen columns, audited. A masked column is left
 * out and may not be filtered on; an unknown column or an unreadable line is a 400.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.trades-only.kinds[0]=trade", "drishti.security.redact[0]=dv01",
        "drishti.database.sqlite.file=${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class DataViewApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired AuditLog audit;

    private MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder r) {
        return r.header("Authorization", "Bearer " + tokens.mint("u-" + role, List.of(role), 300));
    }

    private JsonNode json(String role, MockHttpServletRequestBuilder r) throws Exception {
        return new ObjectMapper().readTree(mvc.perform(as(role, r)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> all(JsonNode list, String field) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    @Test
    void theDatasetsAreTheCallersAndAMaskedColumnIsLeftOut() throws Exception {
        JsonNode admin = json("admin", get("/api/v1/data"));
        assertThat(all(admin.path("datasets"), "kind")).contains("trade");
        assertThat(all(json("trades-only", get("/api/v1/data")).path("datasets"), "kind")).containsExactly("trade");

        JsonNode trade = json("trades-only", get("/api/v1/data/trade"));
        assertThat(trade.path("connector").asText()).isNotBlank();
        assertThat(all(trade.path("columns"), "name")).contains("tradeId", "productType", "counterparty").doesNotContain("dv01");
        assertThat(trade.path("masked").asInt()).isEqualTo(1);
        mvc.perform(as("trades-only", get("/api/v1/data/counterparty"))).andExpect(status().isForbidden());
    }

    @Test
    void theCountAndTheDownloadAgreeAndTheDownloadIsAudited() throws Exception {
        JsonNode c = json("admin", get("/api/v1/data/trade/count").param("columns", "tradeId,productType,dv01")
                .param("where", "productType = IRS").param("where", "tradeId is not empty"));
        long rows = c.path("rows").asLong();
        assertThat(rows).isPositive().isLessThan(c.path("total").asLong());
        assertThat(c.path("complete").asBoolean()).isTrue();
        assertThat(c.path("columns").size()).isBetween(2, 3);
        assertThat(c.path("words").asText()).contains("productType is IRS");
        assertThat(c.path("sql").asText()).contains("WHERE productType = 'IRS' AND tradeId IS NOT NULL");
        assertThat(c.path("gzipBytes").asLong()).isPositive();

        byte[] body = mvc.perform(as("trades-only", get("/api/v1/data/trade/rows").param("columns", "tradeId,productType,dv01")
                .param("where", "productType = IRS").param("where", "tradeId is not empty")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<JsonNode> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(body)), StandardCharsets.UTF_8))) {
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                lines.add(new ObjectMapper().readTree(l));
            }
        }
        assertThat(lines).hasSize((int) rows);
        lines.forEach(l -> {
            assertThat(l.path("productType").asText()).isEqualTo("IRS");
            assertThat(l.has("dv01")).as("masked for this role: left out").isFalse();
            assertThat(l.size()).isEqualTo(2);
        });
        assertThat(audit.recent(20, "trade")).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("data.download");
            assertThat(e.actor()).isEqualTo("u-trades-only");
            assertThat(e.detail()).contains("rows=" + rows).contains("where=productType = IRS AND tradeId is not empty");
        });
    }

    @Test
    void aBadSelectionIsRefusedBeforeAnyRowIsRead() throws Exception {
        mvc.perform(as("admin", get("/api/v1/data/trade/count").param("columns", "NO_SUCH"))).andExpect(status().isBadRequest());
        mvc.perform(as("admin", get("/api/v1/data/trade/count").param("where", "MTM ~ 3"))).andExpect(status().isBadRequest());
        mvc.perform(as("trades-only", get("/api/v1/data/trade/rows").param("where", "dv01 = 3"))).andExpect(status().isForbidden());
    }
}
