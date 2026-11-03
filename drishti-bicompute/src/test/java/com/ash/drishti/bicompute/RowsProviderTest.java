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
import com.ash.drishti.bicompute.rows.RowsProvider;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rows held in memory (a Sutra's pivot panel, a chart of a table's shown rows) pivot to the same figures as the same rows in DuckDB: the
 * in-memory provider's states are made by the built-ins' own {@code add}, the tabular provider's in SQL; every cell, subtotal, Others and
 * the grand total agree, for every built-in, with dates grouped by month.
 */
class RowsProviderTest {

    @Test
    @SuppressWarnings("unchecked")
    void inMemoryRowsPivotToTheSameFiguresAsTheTabularProvider() {
        try (PivotFixture fx = new PivotFixture(1_500, StateCache.none(), PivotCore.Settings.defaults())) {
            List<Object[]> rows = fx.exec.query("SELECT REGION, DESK, CCY, TRADE_DATE, NOTIONAL, PNL, CPTY FROM trades", List.of(), rs -> {
                List<Object[]> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5), rs.getObject(6),
                        rs.getString(7)});
                }
                return out;
            });
            List<PivotProvider.Field> fields = List.of(new PivotProvider.Field("REGION", "string", "", ""), new PivotProvider.Field("DESK", "string", "", ""),
                    new PivotProvider.Field("CCY", "string", "", ""), new PivotProvider.Field("TRADE_DATE", "date", "", ""),
                    new PivotProvider.Field("NOTIONAL", "number", "", ""), new PivotProvider.Field("PNL", "number", "", ""),
                    new PivotProvider.Field("CPTY", "string", "", ""));
            PivotCore mem = new PivotCore(List.of(new RowsProvider("trades", "Trades", fields, rows)), StateCache.none(), PivotCore.Settings.defaults());
            Map<String, Object> view = Map.of("source", "trades", "rows", List.of("REGION", "DESK"), "columns", List.of("TRADE_DATE:quarter"),
                    "expandTo", Map.of("rows", 2), "top", Map.of("DESK", Map.of("n", 3)),
                    "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "S"), Map.of("measure", "count()", "as", "C"),
                            Map.of("measure", "avg(PNL)", "as", "A"), Map.of("measure", "min(PNL)", "as", "Mn"), Map.of("measure", "stddev_samp(PNL)", "as", "Sd"),
                            Map.of("measure", "median(PNL)", "as", "Md"), Map.of("measure", "count_distinct(CPTY)", "as", "Dc")));
            Map<String, Object> a = mem.query(Viewer.of("rita"), PivotView.parse(view));
            Map<String, Object> b = fx.core.query(Viewer.of("rita"), PivotView.parse(view));
            Map<String, Map<String, Object>> ca = (Map<String, Map<String, Object>>) a.get("cells");
            Map<String, Map<String, Object>> cb = (Map<String, Map<String, Object>>) b.get("cells");
            assertThat(ca.keySet()).isEqualTo(cb.keySet());
            for (String k : cb.keySet()) {
                Map<String, Object> va = (Map<String, Object>) ca.get(k).get("values");
                Map<String, Object> vb = (Map<String, Object>) cb.get(k).get("values");
                for (String m : vb.keySet()) {
                    if (vb.get(m) == null) {
                        assertThat(va.get(m)).as(k + " " + m).isNull();
                        continue;
                    }
                    double x = ((Number) vb.get(m)).doubleValue();
                    assertThat(((Number) va.get(m)).doubleValue()).as(k + " " + m).isCloseTo(x, offset(Math.max(1e-6, Math.abs(x) * 1e-9)));
                }
            }
        }
    }
}
