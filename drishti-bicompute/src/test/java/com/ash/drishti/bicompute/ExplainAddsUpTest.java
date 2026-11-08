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
import com.ash.drishti.bicompute.explain.Explain;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.whatif.Overlay;
import com.ash.drishti.bicompute.whatif.WhatIf;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Explain the difference (ARCHITECTURE.md section 26.7, item 1; test {@code ExplainAddsUpTest} of section 20): for an additive, a ratio, an
 * average and a holistic measure, and for the price and quantity effects, the steps and the residual add up to the difference; for a sum
 * each step is the member's own change. And what-if (item 3): the overlay's figures equal the same arithmetic done by hand, the original
 * is unchanged beside it.
 */
class ExplainAddsUpTest {

    static PivotView view(String... values) {
        return PivotView.parse(Map.of("source", "trades", "rows", List.of("REGION"), "values", List.of(values)));
    }

    @SuppressWarnings("unchecked")
    static void addsUp(Map<String, Object> e) {
        double diff = ((Number) e.get("difference")).doubleValue();
        double sum = 0;
        for (Map<String, Object> s : (List<Map<String, Object>>) e.get("steps")) {
            sum += ((Number) s.get("step")).doubleValue();
        }
        sum += ((Number) ((Map<String, Object>) e.get("residual")).get("value")).doubleValue();
        assertThat(sum).as(e.get("measure") + " adds up").isCloseTo(diff, offset(1e-9 * Math.max(1, Math.abs(diff))));
        assertThat(((Number) ((Map<String, Object>) e.get("residual")).get("value")).doubleValue()).isCloseTo(0.0,
                offset(1e-6 * Math.max(1, Math.abs(diff))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stepsAddUpForEveryKindOfMeasure() {
        try (PivotFixture fx = new PivotFixture(3_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Viewer v = Viewer.of("rita");
            for (String m : List.of("sum(NOTIONAL)", "avg(PNL)", "sum(PNL) / sum(NOTIONAL)", "var_samp(PNL)")) {
                Map<String, Object> e = Explain.explain(fx.core, v, new Explain.Request(view(m), null, Map.of("REGION", "EMEA"), null,
                        Map.of("REGION", "APAC"), null, "DESK", "member", null, null));
                addsUp(e);
                assertThat(e.get("kind")).isEqualTo("mergeable");
                if (m.equals("sum(NOTIONAL)")) {
                    for (Map<String, Object> s : (List<Map<String, Object>>) e.get("steps")) {
                        double own = (s.get("to") == null ? 0 : ((Number) s.get("to")).doubleValue())
                                - (s.get("from") == null ? 0 : ((Number) s.get("from")).doubleValue());
                        assertThat(((Number) s.get("step")).doubleValue()).as("a sum's step is the member's own change")
                                .isCloseTo(own, offset(1e-6));
                    }
                }
            }
            Map<String, Object> med = Explain.explain(fx.core, v, new Explain.Request(view("median(PNL)"), null, Map.of("REGION", "EMEA"), null,
                    Map.of("REGION", "APAC"), null, "CCY", "size", null, null));
            assertThat(med.get("kind")).isEqualTo("holistic");
            List<Map<String, Object>> steps = (List<Map<String, Object>>) med.get("steps");
            assertThat(steps.get(steps.size() - 1).get("cause")).isEqualTo("residual");
            addsUp(med);
            Map<String, Object> pv = Explain.explain(fx.core, v, new Explain.Request(view("sum(NOTIONAL)"), null, Map.of("REGION", "EMEA"), null,
                    Map.of("REGION", "AMERICAS"), null, "DESK", null, "PNL", "NOTIONAL"));
            assertThat((List<?>) pv.get("steps")).hasSize(5);
            addsUp(pv);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void whatIfEqualsTheArithmeticByHandAndKeepsTheOriginal() {
        try (PivotFixture fx = new PivotFixture(2_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Overlay o = Overlay.parse(Map.of("exclude", Map.of("DESK", List.of("FX")), "changes", List.of(Map.of("field", "NOTIONAL", "scale", 2,
                    "where", Map.of("REGION", List.of("EMEA")))), "add", List.of(Map.of("REGION", "APAC", "DESK", "RATES", "NOTIONAL", 1000))));
            Map<String, Object> w = WhatIf.run(fx.plain, Viewer.of("rita"), view("sum(NOTIONAL)"), o);
            double expected = fx.exec.query("SELECT sum(CASE WHEN REGION = 'EMEA' THEN NOTIONAL * 2 ELSE NOTIONAL END) + 1000 FROM trades "
                    + "WHERE DESK <> 'FX'", List.of(), rs -> {
                        rs.next();
                        return rs.getDouble(1);
                    });
            double original = fx.exec.query("SELECT sum(NOTIONAL) FROM trades", List.of(), rs -> {
                rs.next();
                return rs.getDouble(1);
            });
            Map<String, Map<String, Object>> wc = (Map<String, Map<String, Object>>) ((Map<String, Object>) w.get("whatIf")).get("cells");
            Map<String, Map<String, Object>> oc = (Map<String, Map<String, Object>>) ((Map<String, Object>) w.get("original")).get("cells");
            double got = ((Number) ((Map<String, Object>) wc.get("*|*").get("values")).get("sum(NOTIONAL)")).doubleValue();
            double orig = ((Number) ((Map<String, Object>) oc.get("*|*").get("values")).get("sum(NOTIONAL)")).doubleValue();
            assertThat(got).isCloseTo(expected, offset(1e-6));
            assertThat(orig).isCloseTo(original, offset(1e-6));
            Map<String, Object> d = (Map<String, Object>) ((Map<String, Object>) w.get("diff")).get("*|*");
            assertThat(((Number) ((Map<String, Object>) d.get("delta")).get("sum(NOTIONAL)")).doubleValue()).isCloseTo(expected - original,
                    offset(1e-6));
        }
    }
}
