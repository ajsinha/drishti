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
package com.ash.drishti.server.pivot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * /api/v1/pivot on the demo trades (PIVOT_GUIDE.md): the sources a person may pivot, a catalogue without masked fields, a view whose every
 * cell equals the same figure computed from the Data view's own download of the rows, a masked field refused as a dimension or an input
 * (masks before aggregation), the Excel export with its outline and SUM formulas only where a measure adds up, CSV, saved views and a cell
 * by reference, every run and export audited without values.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.trades-only.kinds[0]=trade", "drishti.security.redact[0]=dv01",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class PivotApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired AuditLog audit;
    final ObjectMapper json = new ObjectMapper();

    private MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder r) {
        return r.header("Authorization", "Bearer " + tokens.mint("u-" + role, List.of(role), 300));
    }

    private MvcResult call(String role, MockHttpServletRequestBuilder r, Object body, int expect) throws Exception {
        if (body != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult res = mvc.perform(as(role, r)).andReturn();
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(expect);
        return res;
    }

    private JsonNode ok(String role, MockHttpServletRequestBuilder r, Object body) throws Exception {
        return json.readTree(call(role, r, body, 200).getResponse().getContentAsString());
    }

    /** The demo trades as the Data view downloads them for the role. */
    private List<JsonNode> download(String role, String columns) throws Exception {
        byte[] body = call(role, get("/api/v1/data/trade/rows").param("columns", columns), null, 200).getResponse().getContentAsByteArray();
        List<JsonNode> out = new java.util.ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(body)), StandardCharsets.UTF_8))) {
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                out.add(json.readTree(l));
            }
        }
        return out;
    }

    private String numberField(JsonNode catalogue) {
        for (JsonNode f : catalogue.path("fields")) {
            if ("number".equals(f.path("type").asText()) && !"dv01".equals(f.path("name").asText())) {
                return f.path("name").asText();
            }
        }
        throw new AssertionError("no number field in " + catalogue);
    }

    @Test
    void everyCellEqualsTheSameFigureFromTheDataViewsOwnRowsAndMasksApplyBeforeAggregation() throws Exception {
        JsonNode sources = ok("trades-only", get("/api/v1/pivot/sources"), null);
        assertThat(sources.path("sources").toString()).contains("\"trade\"");
        JsonNode cat = ok("trades-only", get("/api/v1/pivot/catalogue").param("source", "trade"), null);
        assertThat(cat.path("fields").toString()).contains("productType").doesNotContain("\"dv01\"");
        assertThat(cat.path("masked").asInt()).isEqualTo(1);
        String num = numberField(cat);
        Map<String, Object> view = Map.of("source", "trade", "rows", List.of("productType"), "values",
                List.of(Map.of("measure", "sum(" + num + ")", "as", "S"), Map.of("measure", "count()", "as", "N")));
        JsonNode a = ok("trades-only", post("/api/v1/pivot/query"), view);
        Map<String, double[]> naive = new HashMap<>();
        for (JsonNode row : download("trades-only", "productType," + num)) {
            double[] acc = naive.computeIfAbsent(row.path("productType").isMissingNode() || row.path("productType").isNull() ? "(blank)"
                    : row.path("productType").asText(), k -> new double[2]);
            if (row.path(num).isNumber()) {
                acc[0] += row.path(num).asDouble();
            }
            acc[1]++;
        }
        assertThat(naive).isNotEmpty();
        double total = 0;
        double count = 0;
        for (Map.Entry<String, double[]> e : naive.entrySet()) {
            JsonNode cell = a.path("cells").path("productType=" + e.getKey() + "|*");
            assertThat(cell.isMissingNode()).as(e.getKey()).isFalse();
            assertThat(cell.path("values").path("S").asDouble()).as(e.getKey()).isCloseTo(e.getValue()[0],
                    org.assertj.core.data.Offset.offset(Math.max(1e-6, Math.abs(e.getValue()[0]) * 1e-9)));
            assertThat(cell.path("values").path("N").asDouble()).isEqualTo(e.getValue()[1]);
            total += e.getValue()[0];
            count += e.getValue()[1];
        }
        assertThat(a.path("cells").path("*|*").path("values").path("N").asDouble()).isEqualTo(count);
        assertThat(a.path("cells").path("*|*").path("values").path("S").asDouble()).isCloseTo(total,
                org.assertj.core.data.Offset.offset(Math.max(1e-6, Math.abs(total) * 1e-9)));
        // a masked field: refused as a dimension and as an input, before anything is read
        call("trades-only", post("/api/v1/pivot/query"), Map.of("source", "trade", "rows", List.of("dv01"), "values", List.of("count()")), 403);
        call("trades-only", post("/api/v1/pivot/query"), Map.of("source", "trade", "rows", List.of("productType"), "values",
                List.of("sum(dv01)")), 403);
        // the provenance of a cell, and the run audited without its values
        JsonNode prov = ok("trades-only", get("/api/v1/pivot/provenance/" + a.path("provenance").asText()).param("cell", "*|*"), null);
        assertThat(prov.path("cell").path("members").asLong()).isEqualTo((long) count);
        assertThat(audit.recent(50, "trade")).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("pivot.query");
            assertThat(e.actor()).isEqualTo("u-trades-only");
        });
    }

    @Test
    void exportsSavedViewsAndACellByReference() throws Exception {
        JsonNode cat = ok("admin", get("/api/v1/pivot/catalogue").param("source", "trade"), null);
        String num = numberField(cat);
        Map<String, Object> view = Map.of("source", "trade", "rows", List.of("productType", "counterparty"), "expanded",
                List.of(Map.of("axis", "rows", "path", List.of(List.of("productType", "IRS")))), "values",
                List.of(Map.of("measure", "count()", "as", "S"), Map.of("measure", "median(" + num + ")", "as", "Med")));
        byte[] xlsx = call("admin", post("/api/v1/pivot/export"), Map.of("view", view, "format", "xlsx"), 200).getResponse().getContentAsByteArray();
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                parts.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("<f>SUM(B").contains("outlineLevel=\"2\"").doesNotContainPattern("r=\"C[0-9]+\"[^>]*><f>");
        assertThat(parts.get("xl/workbook.xml")).contains("Provenance");
        String csv = call("admin", post("/api/v1/pivot/export"), Map.of("view", view, "format", "csv"), 200).getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("ROW,COLUMN,productType,counterparty,MEASURE,VALUE,EXACT,MEMBERS,KIND");
        assertThat(audit.recent(50, "trade")).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("pivot.export");
            assertThat(e.detail()).contains("format=xlsx");
        });
        JsonNode saved = ok("admin", post("/api/v1/pivot/views"), Map.of("name", "irs-book", "view", view, "sharedWith", List.of("role:trades-only")));
        assertThat(saved.path("id").asText()).isEqualTo("u-admin/irs-book");
        JsonNode reread = ok("trades-only", post("/api/v1/pivot/query"), Map.of("view", "u-admin/irs-book"));
        assertThat(reread.path("cells").path("productType=IRS|*").path("values").path("S").isNumber()).as(reread.path("cells").toString().substring(0, Math.min(3000, reread.path("cells").toString().length()))).isTrue();
        JsonNode cell = ok("admin", get("/api/v1/pivot/cells").param("ref", "pivot://u-admin/irs-book@1/S[productType=IRS]"), null);
        assertThat(cell.path("value").asDouble()).isEqualTo(reread.path("cells").path("productType=IRS|*").path("values").path("S").asDouble());
        JsonNode gone = ok("admin", get("/api/v1/pivot/cells").param("ref", "pivot://u-admin/irs-book@1/S[productType=NO-SUCH]"), null);
        assertThat(gone.path("found").asBoolean()).isFalse();
        assertThat(gone.path("note").asText()).startsWith("not in the view");
    }
}
