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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularProvider;
import com.ash.drishti.bicompute.sql.TabularSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The direct lake route (ARCHITECTURE.md section 23.6.2): a table's Parquet files read by DuckDB's {@code read_parquet}, a masked column never
 * named in the relation, the filters repeated on the raw column so the scan can skip row groups by their statistics; the figures equal the
 * same pivot over the staged table.
 */
class ParquetRouteTest {

    @TempDir
    Path dir;

    @Test
    @SuppressWarnings("unchecked")
    void aPivotOverTheParquetFilesEqualsTheSameOverTheStagedTable() {
        try (PivotFixture fx = new PivotFixture(3_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Path a = dir.resolve("part-0.parquet");
            Path b = dir.resolve("part-1.parquet");
            fx.exec.execute("COPY (SELECT * FROM trades WHERE REGION <> 'EMEA') TO '" + a + "' (FORMAT parquet, ROW_GROUP_SIZE 256)");
            fx.exec.execute("COPY (SELECT * FROM trades WHERE REGION = 'EMEA') TO '" + b + "' (FORMAT parquet, ROW_GROUP_SIZE 256)");
            List<String> statements = new ArrayList<>();
            TabularSource.Resolver lake = new TabularSource.Resolver() {
                public Map<String, String> sources(Viewer v) {
                    return Map.of("trades", "Trades");
                }

                public boolean serves(String s) {
                    return "trades".equals(s);
                }

                public TabularSource open(Viewer v, String s, String date) {
                    return new TabularSource() {
                        public String id() { return "trades"; }
                        public String title() { return "Trades (Parquet)"; }
                        public List<PivotProvider.Field> fields() {
                            return List.of(new PivotProvider.Field("REGION", "string", "", ""), new PivotProvider.Field("DESK", "string", "", ""),
                                    new PivotProvider.Field("NOTIONAL", "number", "", ""), new PivotProvider.Field("PNL", "number", "", ""));
                        }
                        public int masked() { return 1; }
                        public String date() { return "2026-10-30"; }
                        public List<String> dates() { return List.of("2026-10-30"); }
                        public String version() { return "lake-v1"; }
                        public long rows() { return 3_000; }
                        public long bytes() { return -1; }
                        public Map<String, Object> notes() { return Map.of("route", "lake"); }
                        public Relation relation(DuckDbExecutor e) {
                            return new Relation("(SELECT \"REGION\" AS \"REGION\", \"DESK\" AS \"DESK\", \"NOTIONAL\" AS \"NOTIONAL\", \"PNL\" AS \"PNL\" "
                                    + "FROM read_parquet(['" + a + "', '" + b + "'], union_by_name = true))", List.of(), null, List.of());
                        }
                    };
                }
            };
            TabularProvider p = new TabularProvider(fx.exec, lake);
            PivotCore onLake = new PivotCore(List.of(new PivotFixture.Counting(p, statements)), StateCache.none(), PivotCore.Settings.defaults());
            Map<String, Object> view = Map.of("source", "trades", "rows", List.of("REGION", "DESK"), "expandTo", Map.of("rows", 2),
                    "filters", Map.of("DESK", Map.of("in", List.of("RATES", "FX"))),
                    "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "median(PNL)", "as", "M")));
            Map<String, Object> x = onLake.query(Viewer.of("rita"), PivotView.parse(view));
            Map<String, Object> y = fx.core.query(Viewer.of("rita"), PivotView.parse(view));
            Map<String, Map<String, Object>> cx = (Map<String, Map<String, Object>>) x.get("cells");
            Map<String, Map<String, Object>> cy = (Map<String, Map<String, Object>>) y.get("cells");
            assertThat(cx.keySet()).isEqualTo(cy.keySet());
            for (String k : cy.keySet()) {
                Map<String, Object> vx = (Map<String, Object>) cx.get(k).get("values");
                Map<String, Object> vy = (Map<String, Object>) cy.get(k).get("values");
                assertThat(((Number) vx.get("N")).doubleValue()).as(k).isCloseTo(((Number) vy.get("N")).doubleValue(), offset(1e-6));
                assertThat(((Number) vx.get("M")).doubleValue()).as(k).isCloseTo(((Number) vy.get("M")).doubleValue(), offset(1e-9));
            }
            assertThat(statements).allMatch(s -> s.contains("read_parquet") && s.contains("\"DESK\" IN (?, ?)") && !s.contains("SALARY"));
        }
    }
}
