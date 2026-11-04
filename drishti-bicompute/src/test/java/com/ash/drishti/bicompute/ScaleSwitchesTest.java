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
import com.ash.drishti.bicompute.functions.SpaceSaving;
import com.ash.drishti.bicompute.rollup.AggregateAwareness;
import com.ash.drishti.bicompute.rollup.RollupDef;
import com.ash.drishti.bicompute.rollup.Rollups;
import com.ash.drishti.bicompute.rollup.TabularRollupSource;
import com.ash.drishti.bicompute.scale.ScalePolicy;
import com.ash.drishti.bicompute.spi.Viewer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The screen's switches as the engine answers them (PIVOT_GUIDE.md, the screen's switches; PIVOT_ENGINE.md sections 5.3, 9.5, 9.6): exact,
 * auto and ≈ each give the answer they name and say so in {@code computedAs}; "use rollups" off reads the table and says so, on answers
 * from the rollup with the same figures; a level's top N by a count is chosen by a Space-Saving summary only when ≈ is asked, its figures
 * exact and its members within the summary's stated bound; the Space-Saving summary keeps its bounds through a merge.
 */
class ScaleSwitchesTest {

    final Viewer rita = Viewer.of("rita");

    @TempDir
    Path dir;

    static Map<String, Object> distinctView() {
        return Map.of("source", "trades", "rows", List.of("REGION"), "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"),
                Map.of("measure", "distinct(CPTY)", "as", "D")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactAutoAndApproximateEachAnswerAsTheyNameAndSaySo() {
        try (PivotFixture fx = new PivotFixture(4_000, StateCache.lru(8L << 20), PivotCore.Settings.defaults())) {
            fx.core.hooks(new ScalePolicy(1));                  // auto: ≈ above one row, so auto answers ≈ here
            PivotView v = PivotView.parse(distinctView());
            Map<String, Object> exact = fx.core.query(rita, v, ScaleOptions.parse(Map.of("exact", true)));
            Map<String, Object> auto = fx.core.query(rita, v, ScaleOptions.parse(Map.of("exact", "auto")));
            Map<String, Object> approx = fx.core.query(rita, v, ScaleOptions.parse(Map.of("exact", false)));
            Map<String, Object> ce = (Map<String, Object>) exact.get("computedAs");
            assertThat(ce.get("words")).isEqualTo("exact");
            assertThat(ce.get("exact")).isEqualTo(true);
            assertThat(((Map<String, Object>) ce.get("asked")).get("exact")).isEqualTo("exact");
            for (Map<String, Object> a : List.of(auto, approx)) {
                Map<String, Object> c = (Map<String, Object>) a.get("computedAs");
                assertThat(c.get("exact")).isEqualTo(false);
                assertThat(String.valueOf(c.get("words"))).contains("≈ from a sketch (D)");
                assertThat((List<Map<String, Object>>) c.get("sketches")).singleElement().satisfies(s -> assertThat(String.valueOf(s.get("error")))
                        .contains("HyperLogLog"));
            }
            assertThat(((Map<String, Object>) ((Map<String, Object>) approx.get("computedAs")).get("asked")).get("exact")).isEqualTo("approximate");
            // the sums are exact either way; only the distinct count is ≈, within its stated error
            Map<String, Map<String, Object>> e = PivotCoreTest.cells(exact);
            Map<String, Map<String, Object>> x = PivotCoreTest.cells(approx);
            for (String k : e.keySet()) {
                assertThat(PivotCoreTest.val(x.get(k), "N")).isEqualTo(PivotCoreTest.val(e.get(k), "N"));
                double d = PivotCoreTest.val(e.get(k), "D");
                assertThat(PivotCoreTest.val(x.get(k), "D")).isBetween(d * 0.9, d * 1.1);
            }
        }
    }

    @Test
    void theOptionsReadBackAsTheyWereGivenAndALiveRecomputeIsNeverAnEstimateUnlessAsked() {
        ScaleOptions o = ScaleOptions.parse(Map.of("exact", "≈", "progressive", false, "rollups", "false"));
        assertThat(o.approximate()).isTrue();
        assertThat(o.progressive()).isFalse();
        assertThat(o.rollups()).isFalse();
        assertThat(ScaleOptions.parse(o.toMap())).isEqualTo(o);
        assertThat(ScaleOptions.AUTO.live().exactness()).isEqualTo(ScaleOptions.Exactness.EXACT);
        assertThat(ScaleOptions.AUTO.sample(0.02).live().sampled()).isFalse();
        assertThat(o.live().exactness()).isEqualTo(ScaleOptions.Exactness.APPROXIMATE);
        assertThat(o.live().rollups()).isFalse();
        assertThat(ScaleOptions.parse(Map.of()).rollups()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void useRollupsOffReadsTheTableAndOnAnswersFromTheRollupWithTheSameFigures() {
        try (PivotFixture fx = new PivotFixture(3_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Rollups r = new Rollups(Map.of("tabular", new TabularRollupSource(fx.provider, Viewer.of("system"))), dir);
            fx.core.hooks(new AggregateAwareness(r, 10));
            r.define(new RollupDef("desk", "trades", List.of("REGION", "DESK"), List.of("sum(NOTIONAL)", "count()"), "latest", null));
            r.refresh("desk", "2026-10-30");
            PivotView v = PivotView.parse(Map.of("source", "trades", "rows", List.of("REGION", "DESK"), "values",
                    List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "count()", "as", "C")), "expandTo", Map.of("rows", 2)));
            int before = fx.statements.size();
            Map<String, Object> on = fx.core.query(rita, v, ScaleOptions.AUTO);
            assertThat(fx.statements).as("the rollup answered: no SQL").hasSize(before);
            Map<String, Object> con = (Map<String, Object>) on.get("computedAs");
            assertThat(String.valueOf(con.get("words"))).contains("from rollup desk");
            Map<String, Object> off = fx.core.query(rita, v, ScaleOptions.parse(Map.of("rollups", false)));
            assertThat(fx.statements.size()).as("rollups off: the table is read").isGreaterThan(before);
            Map<String, Object> coff = (Map<String, Object>) off.get("computedAs");
            assertThat(String.valueOf(coff.get("words"))).contains("rollups off").doesNotContain("from rollup");
            assertThat((List<String>) coff.get("routes")).isEmpty();
            Map<String, Map<String, Object>> a = PivotCoreTest.cells(on);
            Map<String, Map<String, Object>> b = PivotCoreTest.cells(off);
            assertThat(a.keySet()).isEqualTo(b.keySet());
            for (String k : a.keySet()) {
                assertThat(PivotCoreTest.val(a.get(k), "C")).isEqualTo(PivotCoreTest.val(b.get(k), "C"));
                assertThat(PivotCoreTest.val(a.get(k), "N")).isCloseTo(PivotCoreTest.val(b.get(k), "N"),
                        org.assertj.core.data.Offset.offset(1e-6));
            }
        }
    }

    /** A view of counterparties (150 of them) with the top 5 by trades. */
    static Map<String, Object> topView() {
        return Map.of("source", "trades", "rows", List.of("CPTY"), "values", List.of(Map.of("measure", "count()", "as", "C"),
                Map.of("measure", "sum(NOTIONAL)", "as", "N")), "top", Map.of("CPTY", Map.of("n", 5, "by", "C")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLevelsTopNIsChosenByASpaceSavingSummaryOnlyWhenApproximateIsAskedWithExactFiguresWithinItsBound() {
        try (PivotFixture fx = new PivotFixture(20_000, StateCache.none(), PivotCore.Settings.defaults())) {
            PivotView v = PivotView.parse(topView());
            Map<String, Object> exact = fx.core.query(rita, v);
            Map<String, Object> approx = fx.core.query(rita, v, ScaleOptions.parse(Map.of("exact", false)));
            assertThat((List<String>) ((Map<String, Object>) exact.get("computedAs")).get("topChosen")).isEmpty();
            Map<String, Object> ca = (Map<String, Object>) approx.get("computedAs");
            assertThat((List<String>) ca.get("topChosen")).singleElement().asString().contains("Space-Saving summary of 50 counters")
                    .contains("the figures shown are exact");
            assertThat(String.valueOf(ca.get("words"))).contains("top N ≈ by a summary");
            // the true counts, by a naive query
            Map<String, Double> truth = new HashMap<>();
            fx.exec.query("SELECT CPTY, count(*) FROM trades GROUP BY CPTY", List.of(), rs -> {
                while (rs.next()) {
                    truth.put(rs.getString(1), rs.getDouble(2));
                }
                return null;
            });
            List<Double> sorted = new ArrayList<>(truth.values());
            sorted.sort(java.util.Comparator.reverseOrder());
            double fifth = sorted.get(4);
            List<Map<String, Object>> rows = PivotCoreTest.rows(approx);
            List<Map<String, Object>> members = rows.stream().filter(r -> "node".equals(r.get("kind"))).toList();
            assertThat(members).hasSize(5);
            long floor = Long.parseLong(((List<String>) ca.get("topChosen")).get(0).replaceAll(".*over by at most ([0-9,]+);.*", "$1").replace(",", ""));
            Map<String, Map<String, Object>> cells = PivotCoreTest.cells(approx);
            double shown = 0;
            for (Map<String, Object> m : members) {
                String cp = String.valueOf(m.get("value"));
                Map<String, Object> cell = cells.values().stream().filter(c -> cp.equals(((Map<String, Object>) c.get("coords")).get("CPTY")))
                        .findFirst().orElseThrow();
                assertThat(PivotCoreTest.val(cell, "C")).as(cp + ": the figure is exact").isEqualTo(truth.get(cp));
                assertThat(truth.get(cp)).as(cp + ": within the bound of the 5th").isGreaterThanOrEqualTo(fifth - floor);
                shown += truth.get(cp);
            }
            Map<String, Object> others = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("approximateTop"))).findFirst().orElseThrow();
            assertThat(String.valueOf(others.get("label"))).startsWith("Others (≈");
            Map<String, Object> oc = cells.values().stream().filter(c -> com.ash.drishti.bicompute.core.CellKey.OTHERS.equals(
                    ((Map<String, Object>) c.get("coords")).get("CPTY"))).findFirst().orElseThrow();
            assertThat(PivotCoreTest.val(oc, "C")).as("Others: its own members, exactly").isEqualTo(20_000 - shown);
            // ranked by a sum: never by the summary, said in words
            Map<String, Object> bySum = new HashMap<>(topView());
            bySum.put("top", Map.of("CPTY", Map.of("n", 5, "by", "N")));
            Map<String, Object> s = fx.core.query(rita, PivotView.parse(bySum), ScaleOptions.parse(Map.of("exact", false)));
            assertThat(String.valueOf(s.get("notes"))).contains("top 5 ranked exactly").contains("N is not one");
        }
    }

    @Test
    void theSpaceSavingSummaryKeepsItsBoundsAloneAndThroughAMerge() {
        Random r = new Random(7);
        int m = 40;
        long total = 50_000;
        Map<String, Long> truth = new HashMap<>();
        SpaceSaving a = new SpaceSaving(m);
        SpaceSaving b = new SpaceSaving(m);
        for (long i = 0; i < total; i++) {
            int z = (int) Math.floor(Math.pow(1000, r.nextDouble()));   // heavy-tailed: a few values hold most rows
            String v = "v" + z;
            truth.merge(v, 1L, Long::sum);
            (i % 2 == 0 ? a : b).add(v, 1);
        }
        SpaceSaving merged = a.merge(b);
        for (SpaceSaving s : List.of(merged)) {
            assertThat(s.n()).isEqualTo(total);
            assertThat(s.floor()).as("the floor is at most N/m").isLessThanOrEqualTo(2 * total / m);
            for (SpaceSaving.Counted c : s.top(m)) {
                long t = truth.get(c.value());
                assertThat(c.count()).as(c.value()).isGreaterThanOrEqualTo(t).isLessThanOrEqualTo(t + c.error());
                assertThat(c.error()).isLessThanOrEqualTo(s.floor());
            }
            List<String> listed = s.top(m).stream().map(SpaceSaving.Counted::value).toList();
            truth.forEach((v, t) -> {
                if (t > s.floor()) {
                    assertThat(listed).as(v + " counts " + t + ", more than the floor " + s.floor()).contains(v);
                }
            });
        }
        SpaceSaving exact = new SpaceSaving(0);
        truth.forEach(exact::add);
        assertThat(exact.floor()).isZero();
        assertThat(exact.top(3)).allSatisfy(c -> assertThat(c.error()).isZero());
        assertThat(SpaceSaving.decode(merged.encode()).top(10)).isEqualTo(merged.top(10));
    }
}
