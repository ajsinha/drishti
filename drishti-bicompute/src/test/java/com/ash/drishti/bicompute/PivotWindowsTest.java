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
import static org.assertj.core.api.Assertions.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The statistics of FUNCTION_CATALOGUE.md section 4 on the pivot (every cell against a naive DuckDB query of its own rows: their SQL
 * forms and the rows fed to the ones without) and the shares, ranks and windows of section 5 over an answer's cells:
 * {@code pct_of_total}, {@code pct_of_row}, {@code pct_of_column}, {@code pct_of_parent}, {@code ntile}, {@code diff},
 * {@code pct_change}, each against the raw figures it is made from, and each wrong form refused in words.
 */
class PivotWindowsTest {

    final PivotFixture fx = new PivotFixture(2_000, StateCache.lru(16L << 20), PivotCore.Settings.defaults());
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

    static final Map<String, String> STATS = Map.ofEntries(Map.entry("nmiss(PNL)", "count(*) - count(PNL)"),
            Map.entry("range(PNL)", "max(PNL) - min(PNL)"), Map.entry("stderr(PNL)", "stddev_samp(PNL) / sqrt(count(PNL))"),
            Map.entry("cv(PNL)", "stddev_samp(PNL) / avg(PNL)"), Map.entry("skewness(PNL)", "skewness(PNL)"),
            Map.entry("kurtosis(PNL)", "kurtosis(PNL)"), Map.entry("geomean(NOTIONAL)", "exp(avg(ln(NOTIONAL)))"),
            Map.entry("harmean(NOTIONAL)", "count(NOTIONAL) / sum(1.0 / NOTIONAL)"), Map.entry("corr(PNL, NOTIONAL)", "corr(PNL, NOTIONAL)"),
            Map.entry("covar(PNL, NOTIONAL)", "covar_samp(PNL, NOTIONAL)"), Map.entry("slope(PNL, NOTIONAL)", "regr_slope(PNL, NOTIONAL)"),
            Map.entry("intercept(PNL, NOTIONAL)", "regr_intercept(PNL, NOTIONAL)"), Map.entry("rsq(PNL, NOTIONAL)", "regr_r2(PNL, NOTIONAL)"),
            Map.entry("iqr(PNL)", "quantile_cont(PNL, 0.75) - quantile_cont(PNL, 0.25)"),
            Map.entry("kth_largest(PNL, 3)", "list_sort(list(PNL), 'DESC')[3]"), Map.entry("kth_smallest(PNL, 2)", "list_sort(list(PNL))[2]"),
            // 2.9.11.e: the new aggregates with a SQL state, computed in the GROUPING SETS statement, against a plain query
            Map.entry("sumproduct(PNL, NOTIONAL)", "sum(PNL * NOTIONAL)"), Map.entry("sumsq(PNL)", "sum(PNL * PNL)"),
            Map.entry("devsq(PNL)", "var_pop(PNL) * count(PNL)"), Map.entry("covar_pop(PNL, NOTIONAL)", "covar_pop(PNL, NOTIONAL)"),
            Map.entry("steyx(PNL, NOTIONAL)", "sqrt((regr_syy(PNL, NOTIONAL) - regr_sxy(PNL, NOTIONAL) * regr_sxy(PNL, NOTIONAL) "
                    + "/ regr_sxx(PNL, NOTIONAL)) / (regr_count(PNL, NOTIONAL) - 2))"));

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> cells(Map<String, Object> a) {
        return (Map<String, Map<String, Object>>) a.get("cells");
    }

    @SuppressWarnings("unchecked")
    static Object val(Map<String, Object> cell, String m) {
        return ((Map<String, Object>) cell.get("values")).get(m);
    }

    @SuppressWarnings("unchecked")
    static Double raw(Map<String, Object> cell, String m) {
        Object v = ((Map<String, Object>) cell.get("raw")).get(m);
        return v == null ? null : ((Number) v).doubleValue();
    }

    static Double num(Map<String, Object> cell, String m) {
        Object v = val(cell, m);
        return v == null ? null : ((Number) v).doubleValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyStatisticCellEqualsANaiveQueryOfItsOwnRows() {
        List<Object> values = new ArrayList<>(STATS.keySet());
        values.add(Map.of("measure", "string_agg(CCY, ' / ', true)", "as", "Ccys"));
        values.add(Map.of("measure", "mode(CCY)", "as", "Mode"));
        values.add(Map.of("measure", "trimmean(PNL, 0.2)", "as", "Trim"));
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("REGION", "DESK"), "values", values,
                "expandTo", Map.of("rows", 2))));
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
            for (Map.Entry<String, String> m : STATS.entrySet()) {
                Double naive = fx.scalar("SELECT " + m.getValue() + " FROM trades" + w, params.toArray());
                Double got = num(cell, m.getKey());
                assertThat(got).as(m.getKey() + " " + coords).isNotNull();
                assertThat(got).as(m.getKey() + " " + coords).isCloseTo(naive, offset(Math.max(1e-9, Math.abs(naive) * 1e-9)));
                checked++;
            }
            String ccys = fx.exec.query("SELECT string_agg(DISTINCT CCY, ' / ' ORDER BY CCY) FROM trades" + w, params,
                    rs -> rs.next() ? rs.getString(1) : null);
            assertThat(val(cell, "Ccys")).as("string_agg " + coords).isEqualTo(ccys);
            assertThat(val(cell, "Mode")).as("mode " + coords).isInstanceOf(String.class);
            assertThat(num(cell, "Trim")).as("trimmean " + coords).isNotNull();
        }
        assertThat(checked).isGreaterThan(16 * 10);
        Map<String, Object> m = ((List<Map<String, Object>>) a.get("measures")).stream().filter(x -> "corr(PNL, NOTIONAL)".equals(x.get("label")))
                .findFirst().orElseThrow();
        assertThat(m.get("kind")).isEqualTo("mergeable");
    }

    @Test
    void sharesOfTheGrandTheRowAndTheColumnTotal() {
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("REGION"), "columns", List.of("CCY"), "values",
                List.of("sum(NOTIONAL)", "pct_of_total(sum(NOTIONAL))", "pct_of_row(sum(NOTIONAL))", "pct_of_column(sum(NOTIONAL))"))));
        Map<String, Map<String, Object>> cells = cells(a);
        double grand = num(cells.get("*|*"), "sum(NOTIONAL)");
        int checked = 0;
        for (Map.Entry<String, Map<String, Object>> e : cells.entrySet()) {
            String[] rc = e.getKey().split("\\|", 2);
            double mine = num(e.getValue(), "sum(NOTIONAL)");
            assertThat(raw(e.getValue(), "pct_of_total(sum(NOTIONAL))")).isEqualTo(mine);
            assertThat(num(e.getValue(), "pct_of_total(sum(NOTIONAL))")).isCloseTo(mine / grand, offset(1e-12));
            assertThat(num(e.getValue(), "pct_of_row(sum(NOTIONAL))")).isCloseTo(mine / num(cells.get(rc[0] + "|*"), "sum(NOTIONAL)"),
                    offset(1e-12));
            assertThat(num(e.getValue(), "pct_of_column(sum(NOTIONAL))")).isCloseTo(mine / num(cells.get("*|" + rc[1]), "sum(NOTIONAL)"),
                    offset(1e-12));
            checked++;
        }
        assertThat(checked).isEqualTo(4 * 5);
        assertThat(num(cells.get("*|*"), "pct_of_total(sum(NOTIONAL))")).isEqualTo(1.0);
        // the view keeps the text as written for the header, and the aggregate with its show-as
        PivotView.Value v = PivotView.parse(view("values", List.of("pct_of_total(sum(NOTIONAL))"))).values().get(0);
        assertThat(v.measure()).isEqualTo("sum(NOTIONAL)");
        assertThat(v.showAs()).isEqualTo("pct-grand");
        assertThat(v.label()).isEqualTo("pct_of_total(sum(NOTIONAL))");
        assertThat(PivotView.parse(PivotView.parse(view("values", List.of("pct_of_total(sum(NOTIONAL))"))).toMap()).values().get(0)).isEqualTo(v);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ofTheParentAndNtileAmongSiblings() {
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("REGION", "DESK"), "values",
                List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "pct_of_parent(sum(NOTIONAL))", "as", "P"),
                        Map.of("measure", "ntile(sum(NOTIONAL), 2)", "as", "Half")), "expandTo", Map.of("rows", 2))));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) a.get("rows");
        int checked = 0;
        for (Map<String, Object> r : rows) {
            List<List<String>> path = (List<List<String>>) r.get("path");
            if (path.size() != 2 || !"node".equals(r.get("kind"))) {
                continue;
            }
            Map<String, Object> cell = cells(a).get(r.get("key") + "|*");
            String parent = rows.stream().filter(x -> ((List<List<String>>) x.get("path")).equals(path.subList(0, 1))).findFirst()
                    .orElseThrow().get("key") + "|*";
            assertThat(num(cell, "P")).isCloseTo(num(cell, "N") / num(cells(a).get(parent), "N"), offset(1e-12));
            // ntile 2: SAS's floor(rank · 2 / (count + 1)) + 1 among the desks of the region
            List<Double> sib = new ArrayList<>();
            for (Map<String, Object> o : rows) {
                List<List<String>> op = (List<List<String>>) o.get("path");
                if (op.size() == 2 && op.get(0).equals(path.get(0)) && "node".equals(o.get("kind"))) {
                    sib.add(num(cells(a).get(o.get("key") + "|*"), "N"));
                }
            }
            double mine = num(cell, "N");
            double rank = sib.stream().filter(x -> x < mine).count() + (sib.stream().filter(x -> x == mine).count() + 1) / 2.0;
            assertThat(num(cell, "Half")).isEqualTo(Math.floor(rank * 2 / (sib.size() + 1)) + 1);
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(10);
        assertThat(num(cells(a).get("*|*"), "Half")).isNull();                                              // a grand total has no bucket
    }

    @Test
    void diffAndPctChangeAlongAnOrderDimension() {
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("DESK"), "columns", List.of("REGION"), "values",
                List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "diff(sum(NOTIONAL), DESK)", "as", "D"),
                        Map.of("measure", "pct_change(sum(NOTIONAL), 2, desk)", "as", "C"),
                        Map.of("measure", "diff(sum(NOTIONAL), 1, REGION)", "as", "DR")))));
        Map<String, Map<String, Object>> cells = cells(a);
        List<String> desks = List.of("COMMOD", "CREDIT", "EQUITY", "FX", "RATES");                         // their natural (text) order
        for (String region : List.of("AMERICAS", "APAC", "EMEA", "*")) {
            for (int i = 0; i < desks.size(); i++) {
                Map<String, Object> cell = cell(cells, "DESK", desks.get(i), "REGION", region);
                double now = num(cell, "N");
                if (i == 0) {
                    assertThat(num(cell, "D")).isNull();
                } else {
                    assertThat(num(cell, "D")).isCloseTo(now - num(cell(cells, "DESK", desks.get(i - 1), "REGION", region), "N"), offset(1e-6));
                }
                if (i < 2) {
                    assertThat(num(cell, "C")).isNull();
                } else {
                    double before = num(cell(cells, "DESK", desks.get(i - 2), "REGION", region), "N");
                    assertThat(num(cell, "C")).isCloseTo((now - before) / before, offset(1e-12));
                }
            }
        }
        Map<String, Object> apac = cell(cells, "DESK", "FX", "REGION", "APAC");
        assertThat(num(apac, "DR")).isCloseTo(num(apac, "N") - num(cell(cells, "DESK", "FX", "REGION", "AMERICAS"), "N"), offset(1e-6));
        assertThat(num(cell(cells, "DESK", "FX", "REGION", "*"), "D")).isNotNull();                        // along the rows, in the total column too
        assertThat(num(cell(cells, "DESK", "FX", "REGION", "*"), "DR")).isNull();                          // the total column is not a region
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cell(Map<String, Map<String, Object>> cells, String rowDim, String row, String colDim, String col) {
        for (Map<String, Object> c : cells.values()) {
            Map<String, String> k = (Map<String, String>) c.get("coords");
            if (row.equals(k.get(rowDim)) && ("*".equals(col) ? !k.containsKey(colDim) : col.equals(k.get(colDim)))) {
                return c;
            }
        }
        throw new AssertionError("no cell " + row + " / " + col);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ntileGivesOthersNoBucketAndTopNStillRanksByTheRawFigure() {
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(view("rows", List.of("DESK"), "values",
                List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "ntile(sum(NOTIONAL), 2)", "as", "Q")),
                "top", Map.of("DESK", Map.of("n", 2, "by", "Q")))));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) a.get("rows");
        Map<String, Object> others = rows.stream().filter(r -> "others".equals(r.get("kind"))).findFirst().orElseThrow();
        assertThat(num(cells(a).get(others.get("key") + "|*"), "Q")).isNull();
        List<Map<String, Object>> shown = rows.stream().filter(r -> "node".equals(r.get("kind"))).toList();
        assertThat(shown).hasSize(2);
        double small = Math.min(num(cells(a).get(shown.get(0).get("key") + "|*"), "N"), num(cells(a).get(shown.get(1).get("key") + "|*"), "N"));
        for (String d : PivotFixture.DESKS) {
            if (shown.stream().noneMatch(r -> d.equals(r.get("value")))) {
                assertThat(fx.scalar("SELECT sum(NOTIONAL) FROM trades WHERE DESK = ?", d)).isLessThanOrEqualTo(small);
            }
        }
        // the two shown desks, Others left out: one in each half
        assertThat(shown.stream().map(r -> num(cells(a).get(r.get("key") + "|*"), "Q"))).containsExactlyInAnyOrder(1.0, 2.0);
    }

    @Test
    void aWrongFormIsRefusedInWords() {
        assertThatThrownBy(() -> PivotView.parse(view("values", List.of("pct_of_parent(sum(NOTIONAL), 'MGMT')"))))
                .hasMessageContaining("2.9.11.e");
        assertThatThrownBy(() -> PivotView.parse(view("values", List.of("ntile(sum(NOTIONAL), 0)")))).hasMessageContaining("whole number");
        assertThatThrownBy(() -> PivotView.parse(view("values", List.of("ntile(sum(NOTIONAL))")))).hasMessageContaining("whole number");
        assertThatThrownBy(() -> PivotView.parse(view("values", List.of("diff(sum(NOTIONAL))")))).hasMessageContaining("order dimension");
        assertThatThrownBy(() -> PivotView.parse(view("values", List.of("pct_of_total(pct_of_row(sum(NOTIONAL)))"))))
                .hasMessageContaining("not one around another");
        assertThatThrownBy(() -> PivotView.parse(view("values", List.of(Map.of("measure", "pct_of_total(sum(NOTIONAL))", "showAs", "rank")))))
                .hasMessageContaining("leave its show-as out");
        assertThatThrownBy(() -> PivotView.parse(view("values", List.of(Map.of("measure", "sum(NOTIONAL)", "showAs", "pct-nonsense")))))
                .hasMessageContaining("ntile:<n>");
        assertThatThrownBy(() -> fx.core.query(rita, PivotView.parse(view("rows", List.of("DESK"), "values",
                List.of("diff(sum(NOTIONAL), 1, CCY)"))))).hasMessageContaining("CCY is on neither the rows nor the columns (DESK)");
        assertThatThrownBy(() -> fx.core.query(rita, PivotView.parse(view("rows", List.of("DESK"), "values",
                List.of("kth_largest(NOTIONAL, 0)"))))).hasMessageContaining("a whole k");
        assertThatThrownBy(() -> fx.core.query(rita, PivotView.parse(view("rows", List.of("DESK"), "values",
                List.of("corr(NOTIONAL)"))))).hasMessageContaining("takes 2 fields");
    }
}
