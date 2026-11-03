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
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.bicompute.scale.Progressive;
import com.ash.drishti.bicompute.scale.ScanRates;
import com.ash.drishti.bicompute.sql.SampledResolver;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Progressive answers and estimates (PIVOT_ENGINE.md sections 9.7, 9.8, decision 7): the first answer from a sample marks every figure ≈,
 * scales sums and counts with an interval that holds the exact figure, says "computing" for what a sample cannot state honestly (min, a
 * distinct count), is never cached (the exact answer after it is the table's); a view with a holistic measure is never sampled; an estimate
 * over {@code after-ms} gets the pair, the exact one last; the estimate lists the options and, once a scan is measured, the measured rate.
 */
class ProgressiveTest {

    final Viewer rita = Viewer.of("rita");

    static final Map<String, Object> VIEW = Map.of("source", "trades", "rows", List.of("REGION"), "values", List.of(
            Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "count()", "as", "C"), Map.of("measure", "avg(NOTIONAL)", "as", "A"),
            Map.of("measure", "min(PNL)", "as", "Mn"), Map.of("measure", "approx_count_distinct(CPTY)", "as", "D")));

    @Test
    @SuppressWarnings("unchecked")
    void theSampledAnswerIsApproximateWithIntervalsThatHoldTheExactFiguresAndIsNeverCached() {
        try (PivotFixture fx = new PivotFixture(20_000, StateCache.lru(16L << 20), PivotCore.Settings.defaults())) {
            fx.core.hooks(SampledResolver.hook());
            Progressive pr = new Progressive(fx.core, new Progressive.Settings(true, 1, 0.25));
            Map<String, Object> approx = pr.approximate(rita, PivotView.parse(VIEW), ScaleOptions.AUTO);
            Map<String, Object> exact = fx.core.query(rita, PivotView.parse(VIEW));
            assertThat(approx.get("exactness")).isEqualTo("approximate");
            assertThat(((List<Map<String, Object>>) approx.get("measures"))).hasSize(5).allSatisfy(m -> assertThat(m.get("exact")).isEqualTo(false));
            Map<String, Map<String, Object>> a = PivotCoreTest.cells(approx);
            Map<String, Map<String, Object>> e = PivotCoreTest.cells(exact);
            assertThat(a.keySet()).isEqualTo(e.keySet());
            int inside = 0;
            int checked = 0;
            for (String k : e.keySet()) {
                Map<String, Object> cell = a.get(k);
                assertThat(cell.get("approximate")).isEqualTo(true);
                assertThat(((Map<String, Object>) cell.get("values"))).doesNotContainKeys("≈n0", "≈v0");
                Map<String, Object> iv = (Map<String, Object>) cell.get("intervals");
                for (String m : List.of("N", "C", "A")) {
                    List<Number> lohi = (List<Number>) iv.get(m);
                    double truth = PivotCoreTest.val(e.get(k), m);
                    checked++;
                    if (truth >= lohi.get(0).doubleValue() && truth <= lohi.get(1).doubleValue()) {
                        inside++;
                    }
                }
                assertThat(PivotCoreTest.val(cell, "Mn")).isNull();
                assertThat(PivotCoreTest.val(cell, "D")).isNull();
                assertThat(String.valueOf(cell.get("notes"))).contains("computing");
            }
            assertThat((double) inside / checked).as("95% intervals hold the exact figure").isGreaterThanOrEqualTo(0.75);
            Map<String, Object> exactAgain = fx.core.query(rita, PivotView.parse(VIEW));
            assertThat(PivotCoreTest.cells(exactAgain)).isEqualTo(e);
        }
    }

    @Test
    void aHolisticMeasureIsNeverSampledAndAViewOverAfterMsGetsThePairExactLast() {
        try (PivotFixture fx = new PivotFixture(5_000, StateCache.none(), PivotCore.Settings.defaults())) {
            fx.core.hooks(SampledResolver.hook());
            Progressive pr = new Progressive(fx.core, new Progressive.Settings(true, 1, 0.5));
            Map<String, Object> holistic = Map.of("source", "trades", "rows", List.of("REGION"), "values",
                    List.of(Map.of("measure", "median(PNL)", "as", "Med")));
            assertThat(pr.refusal(rita, PivotView.parse(holistic), ScaleOptions.AUTO)).contains("holistic");
            List<Map<String, Object>> seen = new ArrayList<>();
            Map<String, Object> slowEnough = Map.of("source", "trades", "rows", List.of("REGION"), "values",
                    List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N")));
            // the fixture's estimate is a fraction of a millisecond: after-ms of 0 is read as the default (2 s), so ask the pair directly
            String no = pr.refusal(rita, PivotView.parse(slowEnough), ScaleOptions.AUTO);
            assertThat(no).contains("after-ms");
            pr.run(rita, PivotView.parse(slowEnough), ScaleOptions.AUTO, seen::add);
            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).get("exactness")).isEqualTo("exact");
            seen.clear();
            seen.add(pr.approximate(rita, PivotView.parse(slowEnough), ScaleOptions.AUTO));
            seen.add(fx.core.query(rita, PivotView.parse(slowEnough)));
            assertThat(seen.get(0).get("exactness")).isEqualTo("approximate");
            assertThat(seen.get(1).get("exactness")).isEqualTo("exact");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theEstimateListsTheOptionsAndUsesTheMeasuredRate() {
        try (PivotFixture fx = new PivotFixture(2_000, StateCache.none(), PivotCore.Settings.defaults())) {
            ScanRates rates = new ScanRates(() -> 3);
            fx.core.hooks(rates);
            Map<String, Object> e = fx.core.estimate(rita, PivotView.parse(VIEW));
            List<Map<String, Object>> options = (List<Map<String, Object>>) e.get("options");
            assertThat(options.stream().map(o -> o.get("id")).toList()).containsExactly("run", "narrow", "background", "cancel");
            assertThat(e.get("nodes")).isEqualTo(3);
            rates.record("tabular", 1_000);                  // a measured 1,000 rows a second
            Map<String, Object> m = fx.core.estimate(rita, PivotView.parse(VIEW));
            assertThat(((Number) m.get("seconds")).doubleValue()).isCloseTo(2_000.0 / 1_000 / 3, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(String.valueOf(m.get("how"))).contains("measured rate").contains("shared by 3 nodes");
            assertThat((List<Number>) m.get("range")).hasSize(2);
        }
    }
}
