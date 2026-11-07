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

import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.TokenVerifier;
import com.ash.drishti.server.tables.TablesService;
import com.ash.drishti.tables.read.TableReads;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Virtual tables as pivot sources (TABLES_GUIDE.md section 7a): two tables over the demo trades listed as {@code table:} sources; a pivot
 * of a table gives the same counts as the pivot of its kind; a column masked at the source for the reader is no field; two tables over one
 * kind read in one request read the kind once (V7); a stored kind's name is refused as a table's (V12).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.trades-only.kinds[0]=trade", "drishti.security.redact[0]=dv01",
        "drishti.database.sqlite.file=${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class PivotTablesApiTest {

    static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("drishti-tables-test");
            Files.createDirectories(ROOT.resolve("trading"));
            Files.writeString(ROOT.resolve("trading/trade_summary.table.yaml"), """
                    rachana: 3
                    table: trading.trade_summary
                    from: { kind: trade }
                    columns:
                      PRODUCT: { path: $.productType, type: string }
                      MTM:     { path: $.mtm, type: decimal }
                      DV01:    { path: $.dv01, type: decimal }
                    """);
            Files.writeString(ROOT.resolve("trading/trade_products.table.yaml"), """
                    rachana: 3
                    table: trading.trade_products
                    from: { kind: trade }
                    filter: "$.productType != null"
                    columns:
                      PRODUCT: { path: $.productType, type: string }
                    """);
            Files.createDirectories(ROOT.resolve("core"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void clean() throws java.io.IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(ROOT)) {
            for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }

    @DynamicPropertySource
    static void tables(DynamicPropertyRegistry r) {
        r.add("drishti.tables.root", ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired TablesService tables;
    @Autowired com.ash.drishti.server.security.Entitlements entitlements;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode ok(String role, MockHttpServletRequestBuilder r, Object body) throws Exception {
        r = r.header("Authorization", "Bearer " + tokens.mint("u-" + role, List.of(role), 300));
        if (body != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult res = mvc.perform(r).andReturn();
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void aTableIsAPivotSourceWithTheKindsFiguresAndItsMasks() throws Exception {
        JsonNode sources = ok("trades-only", get("/api/v1/pivot/sources"), null);
        assertThat(sources.path("sources").toString()).contains("table:trading.trade_summary").contains("table:trading.trade_products");
        JsonNode cat = ok("trades-only", get("/api/v1/pivot/catalogue").param("source", "table:trading.trade_summary"), null);
        assertThat(cat.path("fields").toString()).contains("PRODUCT").contains("MTM").doesNotContain("DV01");
        assertThat(cat.path("masked").asInt()).isEqualTo(1);
        JsonNode byTable = ok("trades-only", post("/api/v1/pivot/query"), Map.of("source", "table:trading.trade_summary", "rows", List.of("PRODUCT"),
                "values", List.of(Map.of("measure", "count()", "as", "N"))));
        JsonNode byKind = ok("trades-only", post("/api/v1/pivot/query"), Map.of("source", "trade", "rows", List.of("productType"),
                "values", List.of(Map.of("measure", "count()", "as", "N"))));
        int checked = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = byKind.path("cells").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getKey().startsWith("productType=")) {
                continue;
            }
            String key = "PRODUCT=" + e.getKey().substring("productType=".length());
            assertThat(byTable.path("cells").path(key).path("values").path("N").asDouble()).as(key)
                    .isEqualTo(e.getValue().path("values").path("N").asDouble());
            checked++;
        }
        assertThat(checked).isPositive();
    }

    @Test
    void twoTablesOverOneKindInOneRequestReadItOnce() {
        com.ash.drishti.engine.ViewerContext p = entitlements.viewer(new Principal("u-risk", List.of("trades-only")));
        TableReads reads = tables.reads(p);
        TableReads.Read a = tables.read(reads, p, "trading.trade_summary", null, null, null);
        TableReads.Read b = tables.read(reads, p, "trading.trade_products", null, null, "PRODUCT != null");
        assertThat(a.rows()).isNotEmpty();
        assertThat(b.rows()).isNotEmpty().hasSizeLessThanOrEqualTo(a.rows().size());
        assertThat(reads.sourceReads()).isEqualTo(1);
    }

    @Autowired TableSources tableSources;

    @Test
    void aViewOpenedAgainOnUnchangedDataReadsNothingWhenTheSourceHasADataVersion() throws Exception {
        com.ash.drishti.engine.ViewerContext p = entitlements.viewer(new Principal("u-trades-only", List.of("trades-only")));
        String version = tables.version(p, "trading.trade_summary", null);
        Map<String, Object> view = Map.of("source", "table:trading.trade_summary", "rows", List.of("PRODUCT"),
                "values", List.of(Map.of("measure", "count()", "as", "N")));
        JsonNode first = ok("trades-only", post("/api/v1/pivot/query"), view);
        long before = tableSources.hits();
        JsonNode again = ok("trades-only", post("/api/v1/pivot/query"), view);
        assertThat(again.path("cells")).isEqualTo(first.path("cells"));
        // a query opens its source more than once (catalogue, plan, answer): with a data version every open of the second query is kept,
        // without one (a source that cannot say) every open reads and nothing is kept
        if (version == null) {
            assertThat(tableSources.hits()).isEqualTo(before);
        } else {
            assertThat(tableSources.hits() - before).isPositive();
        }
        assertThat(tables.version(p, "trading.no_such_table", null)).isNull();
    }

    @Test
    void aTableNamedLikeAStoredTableIsNotServed() throws Exception {
        assertThat(tables.storedNames()).contains("trade");
        assertThat(tables.catalogue().clashes(List.of("trading.trade_products"))).hasSize(1);
        assertThat(tables.problems()).noneMatch(com.ash.drishti.tables.Problem::isError);
    }
}
