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

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.Viewer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Show values as (HOW_IT_FITS.md section A26.11) on a view of two row dimensions and a column dimension: a cell's key holds "|" inside
 * its row key and between row and column, and each share must be of its own row's, column's or parent's total.
 */
class ShowAsTest {

    @Test
    @SuppressWarnings("unchecked")
    void sharesOfRowColumnAndParentUseTheirOwnTotalsWithSeveralDimensions() throws Exception {
        try (PivotFixture fx = new PivotFixture(1_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Map<String, Object> base = Map.of("source", "trades", "rows", List.of("REGION", "DESK"), "columns", List.of("CCY"),
                    "expandTo", Map.of("rows", 2));
            Map<String, Object> raw = fx.core.query(Viewer.of("rita"), PivotView.parse(with(base, null)));
            Map<String, Map<String, Object>> rawCells = (Map<String, Map<String, Object>>) raw.get("cells");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) raw.get("rows");
            List<Map<String, Object>> cols = (List<Map<String, Object>>) raw.get("columns");
            String grandRow = grand(rows);
            String grandCol = grand(cols);
            for (String how : List.of("pct-row", "pct-column", "pct-grand")) {
                Map<String, Object> a = fx.core.query(Viewer.of("rita"), PivotView.parse(with(base, how)));
                Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
                int checked = 0;
                for (Map<String, Object> r : rows) {
                    for (Map<String, Object> c : cols) {
                        String rk = String.valueOf(r.get("key"));
                        String ck = String.valueOf(c.get("key"));
                        Map<String, Object> cell = cells.get(rk + "|" + ck);
                        if (cell == null) {
                            continue;
                        }
                        double mine = value(rawCells.get(rk + "|" + ck));
                        double total = switch (how) {
                            case "pct-row" -> value(rawCells.get(rk + "|" + grandCol));
                            case "pct-column" -> value(rawCells.get(grandRow + "|" + ck));
                            default -> value(rawCells.get(grandRow + "|" + grandCol));
                        };
                        Object shown = ((Map<String, Object>) cell.get("values")).get("Notional");
                        assertThat(((Number) shown).doubleValue()).as(how + " " + rk + " | " + ck).isCloseTo(mine / total,
                                org.assertj.core.data.Offset.offset(1e-12));
                        checked++;
                    }
                }
                assertThat(checked).as(how).isGreaterThan(20);
            }
        }
    }

    /** The total's key: the member whose path is empty. */
    private static String grand(List<Map<String, Object>> members) {
        return members.stream().filter(m -> ((List<?>) m.get("path")).isEmpty()).map(m -> String.valueOf(m.get("key"))).findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> with(Map<String, Object> base, String showAs) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(base);
        Map<String, Object> v = new java.util.LinkedHashMap<>(Map.of("measure", "sum(NOTIONAL)", "as", "Notional"));
        if (showAs != null) {
            v.put("showAs", showAs);
        }
        m.put("values", List.of(v));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static double value(Map<String, Object> cell) {
        return ((Number) ((Map<String, Object>) cell.get("values")).get("Notional")).doubleValue();
    }
}
