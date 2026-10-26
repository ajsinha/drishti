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
package com.ash.drishti.compute.reactive.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.compute.EngineException;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.Viewer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** CORR computed from price history: the estimators, the window, the alignment of dates and the refusals. */
class CorrelationEngineTest {

    private static TreeMap<String, Double> series(double... closes) {
        TreeMap<String, Double> out = new TreeMap<>();
        for (int i = 0; i < closes.length; i++) {
            out.put(String.format("2026-09-%02d", i + 1), closes[i]);
        }
        return out;
    }

    @Test
    void identicalMovesCorrelateOneAndOppositeMovesMinusOne() throws Exception {
        TreeMap<String, Double> a = series(100, 101, 99, 102, 103, 101);
        TreeMap<String, Double> b = new TreeMap<>();
        TreeMap<String, Double> c = new TreeMap<>();
        a.forEach((d, v) -> {
            b.put(d, v * 2);                            // the same returns
            c.put(d, 10000 / v);                        // the opposite log returns
        });
        Map<String, Object> row = CorrelationEngine.correlate("CORR-T", "Test", List.of("EQ-A", "EQ-B", "EQ-C"), List.of(a, b, c), 0, "pearson");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) row.get("PAIRS");
        assertThat((double) pairs.get(0).get("RHO")).isCloseTo(1.0, offset(1e-4));
        assertThat((double) pairs.get(1).get("RHO")).isCloseTo(-1.0, offset(1e-4));
        assertThat(row.get("WINDOW")).isEqualTo(5);
        assertThat(row.get("FROM")).isEqualTo("2026-09-01");
        assertThat(row.get("TO")).isEqualTo("2026-09-06");
    }

    @Test
    void theWindowTakesTheLatestReturnsOnTheDatesEveryFactorHas() throws Exception {
        TreeMap<String, Double> a = series(100, 101, 99, 102, 103, 101, 104);
        TreeMap<String, Double> b = series(50, 51, 52, 50, 49, 51, 52);
        b.remove("2026-09-04");                         // a date one factor lacks is left out for all
        Map<String, Object> row = CorrelationEngine.correlate("CORR-T", "Test", List.of("EQ-A", "EQ-B"), List.of(a, b), 3, "pearson");
        assertThat(row.get("AVAILABLE")).isEqualTo(5);
        assertThat(row.get("WINDOW")).isEqualTo(3);
        assertThat(row.get("FROM")).isEqualTo("2026-09-03");                 // the last four common closes: 03, 05, 06, 07
    }

    @Test
    void ledoitWolfShrinksTowardNoCorrelation() throws Exception {
        TreeMap<String, Double> a = series(100, 101, 99, 102, 103, 101, 104, 103, 105, 104);
        TreeMap<String, Double> b = series(50, 50.6, 49.9, 51.2, 51.0, 50.4, 52.0, 51.1, 52.6, 52.5);
        Map<String, Object> p = CorrelationEngine.correlate("CORR-T", "Test", List.of("EQ-A", "EQ-B"), List.of(a, b), 0, "pearson");
        Map<String, Object> lw = CorrelationEngine.correlate("CORR-T", "Test", List.of("EQ-A", "EQ-B"), List.of(a, b), 0, "ledoit-wolf");
        double shrink = (double) lw.get("SHRINKAGE");
        assertThat(shrink).isBetween(0.0, 1.0);
        assertThat(Math.abs((double) lw.get("AVERAGE"))).isLessThanOrEqualTo(Math.abs((double) p.get("AVERAGE")));
        assertThat((double) lw.get("AVERAGE")).isCloseTo((1 - shrink) * (double) p.get("AVERAGE"), offset(2e-4));
    }

    @Test
    void theEngineReadsTheSetAndItsFactorsHistories() throws Exception {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("MATRIX_ID", "CORR-T");
        set.put("NAME", "Test");
        set.put("EQUITY_IDS", List.of("EQ-A"));
        set.put("FX_IDS", List.of("FX-EURUSD"));
        Map<String, List<DataNode>> in = new LinkedHashMap<>();
        in.put("set", List.of(DataNode.of(set)));
        in.put("equities", List.of(DataNode.of(Map.of("TICKER", "EQ-A", "HISTORY", hist("CLOSE", 100, 101, 99, 102)))));
        in.put("fx", List.of(DataNode.of(Map.of("PAIR", "FX-EURUSD", "HISTORY", hist("MID", 1.10, 1.11, 1.09, 1.12)))));
        EngineRequest req = new EngineRequest("r1", "correlation-engine", "correlation-result", "CORR-T", Map.of(), null, null, new Viewer("tester", List.of(), "sig"), null, 0, null);
        var out = new CorrelationEngine().compute(req, new EngineInputs(in), p -> {});
        assertThat(out.rows().get(0).get("FACTORS")).isEqualTo(List.of("EQ-A", "FX-EURUSD"));
        assertThat((double) out.rows().get(0).get("AVERAGE")).isCloseTo(1.0, offset(0.05));
        EngineRequest bad = new EngineRequest("r2", "correlation-engine", "correlation-result", "CORR-T", Map.of("estimator", "kendall"), null, null,
                new Viewer("tester", List.of(), "sig"), null, 0, null);
        assertThatThrownBy(() -> new CorrelationEngine().compute(bad, new EngineInputs(in), p -> {})).isInstanceOf(EngineException.class)
                .hasMessageContaining("pearson or ledoit-wolf");
        in.put("set", List.of());
        assertThatThrownBy(() -> new CorrelationEngine().compute(req, new EngineInputs(in), p -> {})).hasMessageContaining("no correlation set");
    }

    private static List<Map<String, Object>> hist(String field, double... v) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < v.length; i++) {
            out.add(Map.of("DATE", String.format("2026-09-%02d", i + 1), field, v[i]));
        }
        return out;
    }
}
