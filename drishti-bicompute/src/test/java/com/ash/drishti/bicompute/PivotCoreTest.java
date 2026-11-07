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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The core on the tabular provider (PIVOT_ENGINE.md section 20): every visible cell of a GROUPING SETS answer equals a naive query of its own
 * rows, for every built-in; the first level, its subtotals and the grand total come from one statement; opening a node computes only its
 * children; Others is its own members (not total minus the top N); a masked field cannot be used and row rules apply before aggregation; the
 * cell budget refuses with the count; a cell reference names coordinates; compare differences add up.
 */
class PivotCoreTest {

    final PivotFixture fx = new PivotFixture(2_000, StateCache.lru(64L << 20), PivotCore.Settings.defaults());
    final Viewer rita = Viewer.of("rita");

    @AfterEach
    void close() {
        fx.close();
    }

    static Map<String, Object> view(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "trades");
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static final List<Object> ALL = List.of(Map.of("measure", "sum(NOTIONAL)", "as", "Notional"), Map.of("measure", "count()", "as", "Trades"),
            Map.of("measure", "avg(PNL)", "as", "Avg"), Map.of("measure", "min(PNL)", "as", "Min"), Map.of("measure", "max(PNL)", "as", "Max"),
            Map.of("measure", "wavg(PNL, NOTIONAL)", "as", "WAvg"), Map.of("measure", "var_samp(PNL)", "as", "Var"),
            Map.of("measure", "stddev_pop(PNL)", "as", "Sd"), Map.of("measure", "median(PNL)", "as", "Median"),
            Map.of("measure", "percentile(PNL, 0.9)", "as", "P90"), Map.of("measure", "count_distinct(CPTY)", "as", "Cptys"));

    static final Map<String, String> NAIVE = Map.ofEntries(Map.entry("Notional", "sum(NOTIONAL)"), Map.entry("Trades", "count(*)"),
            Map.entry("Avg", "avg(PNL)"), Map.entry("Min", "min(PNL)"), Map.entry("Max", "max(PNL)"),
            Map.entry("WAvg", "sum(PNL * NOTIONAL) / sum(CASE WHEN PNL IS NOT NULL THEN NOTIONAL END)"), Map.entry("Var", "var_samp(PNL)"),
            Map.entry("Sd", "stddev_pop(PNL)"), Map.entry("Median", "quantile_cont(PNL, 0.5)"), Map.entry("P90", "quantile_cont(PNL, 0.9)"),
            Map.entry("Cptys", "count(DISTINCT CPTY)"));

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> cells(Map<String, Object> a) {
        return (Map<String, Map<String, Object>>) a.get("cells");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> a) {
        return (List<Map<String, Object>>) a.get("rows");
    }

    @SuppressWarnings("unchecked")
    static Double val(Map<String, Object> cell, String m) {
        Object v = ((Map<String, Object>) cell.get("values")).get(m);
        return v == null ? null : ((Number) v).doubleValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCellOfTheGroupingSetsAnswerEqualsANaiveQueryOfItsOwnRows() {
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("REGION", "DESK"), "columns", List.of("CCY"),
                "values", ALL, "expandTo", Map.of("rows", 2))));
        assertThat(cells(a)).hasSizeGreaterThan(60);
        int checked = 0;
        for (Map<String, Object> cell : cells(a).values()) {
            Map<String, String> coords = (Map<String, String>) cell.get("coords");
            List<String> where = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            coords.forEach((k, v) -> {
                where.add(k + " = ?");
                params.add(v);
            });
            String w = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
            for (Map.Entry<String, String> m : NAIVE.entrySet()) {
                Double naive = fx.scalar("SELECT " + m.getValue() + " FROM trades" + w, params.toArray());
                Double got = val(cell, m.getKey());
                if (naive == null) {
                    assertThat(got).as(m.getKey() + " " + coords).isNull();
                } else {
                    assertThat(got).as(m.getKey() + " " + coords).isCloseTo(naive, offset(Math.max(1e-6, Math.abs(naive) * 1e-9)));
                }
                checked++;
            }
            assertThat(((Number) cell.get("members")).longValue()).isEqualTo(fx.scalar("SELECT count(*) FROM trades" + w, params.toArray()).longValue());
        }
        assertThat(checked).isGreaterThan(600);
    }

    @Test
    void theFirstLevelItsTotalsAndTheGrandTotalAreOneStatementWithGroupingSets() {
        fx.statements.clear();
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("REGION", "DESK"), "columns", List.of("CCY"),
                "values", List.of("sum(NOTIONAL)"))));
        assertThat(fx.statements).hasSize(1);
        assertThat(fx.statements.get(0)).contains("GROUPING SETS ((), (\"d0\"), (\"d1\"), (\"d0\", \"d1\"))").doesNotContain("'EMEA'");
        assertThat(rows(a)).extracting(r -> r.get("label")).containsExactly("AMERICAS", "APAC", "EMEA", "Grand total");
        assertThat(rows(a)).filteredOn(r -> "node".equals(r.get("kind"))).allMatch(r -> !Boolean.TRUE.equals(r.get("expanded")) && Boolean.TRUE.equals(r.get("expandable")));
    }

    @Test
    void aMemberOnTheLastRowLevelIsALeafWithNoPlusThoughExpandByStillOpensIt() {
        PivotView v = PivotView.parse(view("rows", List.of("REGION"), "columns", List.of("CCY"), "values", List.of("sum(NOTIONAL)")));
        Map<String, Object> a = fx.core.query(rita, v);
        assertThat(rows(a)).filteredOn(r -> "node".equals(r.get("kind"))).isNotEmpty().allMatch(r -> Boolean.FALSE.equals(r.get("expandable")));
        Map<String, Object> b = fx.core.expand(rita, v, "rows", List.of(List.of("REGION", "EMEA")), "DESK");
        assertThat(rows(b)).anyMatch(r -> List.of(List.of("REGION", "EMEA"), List.of("DESK", "FX")).equals(r.get("path"))
                && Boolean.FALSE.equals(r.get("expandable")));
    }

    @Test
    void openingANodeComputesOnlyItsChildrenTheRestComesFromTheCache() {
        PivotView v = PivotView.parse(view("rows", List.of("REGION", "DESK", "CCY"), "values", List.of("sum(NOTIONAL)", "median(PNL)")));
        fx.core.query(rita, v);
        fx.statements.clear();
        Map<String, Object> a = fx.core.expand(rita, v, "rows", List.of(List.of("REGION", "EMEA")), null);
        assertThat(fx.statements).hasSize(1);
        assertThat(fx.statements.get(0)).contains("\"d0\" = ?");
        List<Map<String, Object>> rs = rows(a);
        assertThat(rs).anyMatch(r -> "header".equals(r.get("kind")) && "EMEA".equals(r.get("label")));
        assertThat(rs).anyMatch(r -> "EMEA total".equals(r.get("label")));
        assertThat(rs.stream().filter(r -> ((Number) r.get("depth")).intValue() == 2).count()).isEqualTo(5);
        assertThat(rs).noneMatch(r -> ((List<?>) r.get("path")).size() == 2 && ((List<?>) ((List<?>) r.get("path")).get(0)).get(1).equals("APAC"));
        Map<String, Object> comp = cast(a.get("computed"));
        assertThat(((Number) comp.get("fromCache")).intValue()).isGreaterThanOrEqualTo(2);
        // expand by another dimension, per member: APAC by CCY while EMEA stays by DESK
        fx.statements.clear();
        PivotView opened = PivotView.parse(cast(a.get("view")));
        Map<String, Object> b = fx.core.expand(rita, opened, "rows", List.of(List.of("REGION", "APAC")), "CCY");
        assertThat(rows(b)).anyMatch(r -> List.of(List.of("REGION", "APAC"), List.of("CCY", "USD")).equals(r.get("path")));
        assertThat(rows(b)).anyMatch(r -> List.of(List.of("REGION", "EMEA"), List.of("DESK", "FX")).equals(r.get("path")));
        Double apacUsd = val(cells(b).get("CCY=USD|REGION=APAC|*"), "sum(NOTIONAL)");
        assertThat(apacUsd).isCloseTo(fx.scalar("SELECT sum(NOTIONAL) FROM trades WHERE REGION = 'APAC' AND CCY = 'USD'"), offset(1e-6));
    }

    @Test
    void othersIsItsOwnMembersNeverTotalMinusTheTopN() {
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("DESK"), "values",
                List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "median(PNL)", "as", "Med")),
                "top", Map.of("DESK", Map.of("n", 2, "by", "N")))));
        List<Map<String, Object>> rs = rows(a);
        assertThat(rs).hasSize(4);
        Map<String, Object> others = rs.get(2);
        assertThat(others.get("kind")).isEqualTo("others");
        assertThat(String.valueOf(others.get("label"))).isEqualTo("Others (3 of 5)");
        List<String> kept = List.of(String.valueOf(rs.get(0).get("value")), String.valueOf(rs.get(1).get("value")));
        Map<String, Object> cell = cells(a).get(others.get("key") + "|*");
        assertThat(val(cell, "N")).isCloseTo(fx.scalar("SELECT sum(NOTIONAL) FROM trades WHERE DESK NOT IN (?, ?)", kept.get(0), kept.get(1)),
                offset(1e-6));
        double naiveMedian = fx.scalar("SELECT quantile_cont(PNL, 0.5) FROM trades WHERE DESK NOT IN (?, ?)", kept.get(0), kept.get(1));
        assertThat(val(cell, "Med")).isCloseTo(naiveMedian, offset(1e-9));
        double grandMinusTop = val(cells(a).get("*|*"), "Med") - val(cells(a).get(rs.get(0).get("key") + "|*"), "Med")
                - val(cells(a).get(rs.get(1).get("key") + "|*"), "Med");
        assertThat(val(cell, "Med")).isNotCloseTo(grandMinusTop, offset(1.0));
        // the top two are the two largest
        double topMin = Math.min(val(cells(a).get(rs.get(0).get("key") + "|*"), "N"), val(cells(a).get(rs.get(1).get("key") + "|*"), "N"));
        for (String d : PivotFixture.DESKS) {
            if (!kept.contains(d)) {
                assertThat(fx.scalar("SELECT sum(NOTIONAL) FROM trades WHERE DESK = ?", d)).isLessThanOrEqualTo(topMin);
            }
        }
    }

    @Test
    void masksAndRowRulesApplyBeforeAggregation() {
        Viewer limited = Viewer.of("limited-lee");
        assertThatThrownBy(() -> fx.core.query(limited, PivotView.parse(view("rows", List.of("SALARY:width=100"), "values", List.of("count()")))))
                .isInstanceOf(DrishtiException.class).extracting(e -> ((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.PIVOT_MASKED);
        assertThatThrownBy(() -> fx.core.query(limited, PivotView.parse(view("rows", List.of("DESK"), "values", List.of("sum(SALARY)")))))
                .isInstanceOf(DrishtiException.class);
        Map<String, Object> a = fx.core.query(limited, PivotView.parse(view("rows", List.of("REGION"), "values", List.of("sum(NOTIONAL)"))));
        assertThat(rows(a)).extracting(r -> r.get("label")).containsExactly("EMEA", "Grand total");
        assertThat(val(cells(a).get("*|*"), "sum(NOTIONAL)")).isCloseTo(fx.scalar("SELECT sum(NOTIONAL) FROM trades WHERE REGION = 'EMEA'"),
                offset(1e-6));
        // the same view for someone who sees every region is another cache entry, never the limited one's
        Map<String, Object> b = fx.core.query(rita, PivotView.parse(view("rows", List.of("REGION"), "values", List.of("sum(NOTIONAL)"))));
        assertThat(rows(b)).hasSize(4);
    }

    @Test
    void theCellBudgetRefusesWithTheCountAndNeverCutsAViewShort() {
        PivotFixture small = new PivotFixture(500, StateCache.none(), new PivotCore.Settings(20, 5, 10, 1, 50, 1, 60, 1_000_000));
        try {
            assertThatThrownBy(() -> small.core.query(rita, PivotView.parse(view("rows", List.of("DESK"), "columns", List.of("CCY"),
                    "values", List.of("sum(NOTIONAL)"))))).isInstanceOf(DrishtiException.class).hasMessageContaining("30 cells")
                    .hasMessageContaining("never cut short");
            assertThat(small.core.query(rita, PivotView.parse(view("rows", List.of("REGION"), "values", List.of("sum(NOTIONAL)")))))
                    .containsKey("cells");
        } finally {
            small.close();
        }
    }

    @Test
    void aCellByItsCoordinatesAndOneThatIsGoneSaysSo() {
        PivotView v = PivotView.parse(view("rows", List.of("REGION"), "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"))));
        Map<String, Object> c = fx.core.cell(rita, v, Map.of("REGION", "APAC", "DESK", "FX"));
        assertThat(c.get("found")).isEqualTo(true);
        assertThat(((Number) cast(c.get("values")).get("N")).doubleValue())
                .isCloseTo(fx.scalar("SELECT sum(NOTIONAL) FROM trades WHERE REGION = 'APAC' AND DESK = 'FX'"), offset(1e-6));
        Map<String, Object> gone = fx.core.cell(rita, v, Map.of("REGION", "MARS"));
        assertThat(gone.get("found")).isEqualTo(false);
        assertThat(String.valueOf(gone.get("note"))).startsWith("not in the view");
    }

    @Test
    void datesGroupByMonthAndSortsPerLevelKeepChildrenUnderTheirParents() {
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("REGION", "DESK"), "columns", List.of("TRADE_DATE:month"),
                "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N")), "expandTo", Map.of("rows", 2),
                "sort", Map.of("DESK", Map.of("by", "N", "desc", true), "REGION", Map.of("by", "label", "desc", true)))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) a.get("columns");
        assertThat(cols.get(0).get("label")).isEqualTo("2026-01");
        assertThat(cols).hasSize(11);
        List<Map<String, Object>> rs = rows(a);
        assertThat(rs.get(0).get("label")).isEqualTo("EMEA");
        List<Double> emeaDesks = new ArrayList<>();
        for (Map<String, Object> r : rs) {
            if (((Number) r.get("depth")).intValue() == 2 && ((List<?>) ((List<?>) r.get("path")).get(0)).get(1).equals("EMEA")) {
                emeaDesks.add(val(cells(a).get(r.get("key") + "|*"), "N"));
            }
        }
        assertThat(emeaDesks).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void theProvenanceOfACellAndTheEstimate() {
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("REGION"), "values", List.of("sum(NOTIONAL)"))));
        Map<String, Object> p = fx.core.provenance(String.valueOf(a.get("provenance")), "REGION=EMEA|*");
        assertThat(p.get("source")).isEqualTo("trades");
        assertThat(cast(p.get("cell")).get("members")).isNotNull();
        Map<String, Object> e = fx.core.estimate(rita, PivotView.parse(view("rows", List.of("REGION"), "values", List.of("sum(NOTIONAL)"))));
        assertThat(((Number) e.get("rows")).longValue()).isEqualTo(2_000);
        assertThat(String.valueOf(e.get("words"))).startsWith("This pivot reads ≈2,000 rows");
        assertThat(CellKey.of(Map.of("A", "1")).id()).startsWith("c:");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compareTwoDatesCellByCell() {
        PivotView v = PivotView.parse(view("rows", List.of("REGION"), "values", List.of("count()")));
        Map<String, Object> c = fx.core.compare(rita, v, "2026-10-30");
        Map<String, Object> diff = cast(c.get("diff"));
        assertThat(diff).containsKey("REGION=EMEA|*");
        assertThat(((Number) cast(cast(diff.get("REGION=EMEA|*")).get("delta")).get("count()")).doubleValue()).isZero();
    }

    @Test
    void slicersAndFormatsTravelWithTheViewAndASlicerMustBeAField() {
        PivotView v = PivotView.parse(view("rows", List.of("REGION"), "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N")),
                "slicers", List.of("CCY"), "formats", Map.of("N", Map.of("style", "bars"))));
        PivotView back = PivotView.parse(v.toMap());
        assertThat(back.slicers()).containsExactly("CCY");
        assertThat(back.formats().get("N").style()).isEqualTo("bars");
        Map<String, Object> a = fx.core.query(rita, back);
        assertThat(cast(cast(a.get("view")).get("formats")).get("N")).isEqualTo(Map.of("style", "bars"));
        assertThatThrownBy(() -> fx.core.query(rita, PivotView.parse(view("values", List.of("count()"), "slicers", List.of("NOPE")))))
                .isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> PivotView.parse(view("values", List.of("count()"), "formats", Map.of("x", "rainbow"))))
                .isInstanceOf(DrishtiException.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }
}
