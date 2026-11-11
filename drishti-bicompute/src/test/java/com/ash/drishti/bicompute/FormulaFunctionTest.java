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
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.bicompute.formula.FormulaFunction;
import com.ash.drishti.bicompute.formula.FormulaLowering;
import com.ash.drishti.bicompute.harness.FunctionCheck;
import com.ash.drishti.bicompute.harness.Playground;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.drishti.kernel.api.pivot.Viewer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Formulas of built-ins (HOW_IT_FITS.md section A26.5): Rachana, refused in words where it is not a measure; the kind of the parts; the
 * Java path (the harness, the playground) and the SQL path (the tabular provider's GROUPING SETS) giving the same figures, totals made from
 * states, never from the figures shown.
 */
class FormulaFunctionTest {

    @Test
    void aFormulaIsMadeOfAggregates() {
        assertThat(FormulaLowering.isFormula("sum(PNL) / sum(abs(PNL))")).isTrue();
        assertThat(FormulaLowering.isFormula("sum(NOTIONAL, where=DESK == 'RATES') / sum(NOTIONAL)")).isTrue();
        assertThat(FormulaLowering.isFormula("count(where=PNL < 0) / count()")).isTrue();
        assertThatThrownBy(() -> FormulaLowering.lower("PNL * 2")).hasMessageContaining("row value");
        assertThatThrownBy(() -> FormulaLowering.lower("sum(sum(PNL))")).hasMessageContaining("inside another aggregate");
        assertThat(FormulaLowering.isFormula("count() > 10 ? sum(PNL) : 0")).as("Rachana's spelling").isTrue();
        assertThat(FormulaLowering.isFormula("sum(PNL, where = DESK in ['FX', 'RATES'] && !(PNL == null))")).isTrue();
        assertThatThrownBy(() -> FormulaLowering.lower("sum(PNL) if count() > 10 else 0")).as("the old syntax is the migration's").isInstanceOf(FormulaLowering.FormulaException.class);
        assertThatThrownBy(() -> FormulaLowering.lower("sum(PNL, where=PNL != None)")).hasMessageContaining("old syntax");
        assertThatThrownBy(() -> FormulaLowering.lower("count(where=0 < PNL < 10)")).hasMessageContaining("a < b && b < c");
        assertThatThrownBy(() -> FormulaLowering.lower("percentile(PNL)")).as("Rachana's signature: percentile(x, q)").hasMessageContaining("percentile takes 2-3 argument");
        assertThat(FormulaFunction.of("sum(PNL) / sum(NOTIONAL)").kind()).isEqualTo(Kind.MERGEABLE);
        assertThat(FormulaFunction.of("median(PNL) - avg(PNL)").kind()).isEqualTo(Kind.HOLISTIC);
        assertThat(FormulaFunction.of("sum(PNL) - sum(NOTIONAL)").plainSum()).isTrue();
        assertThat(FormulaFunction.of("sum(PNL) / count()").plainSum()).isFalse();
    }

    @Test
    void theJavaPathEvaluatesRowExpressionsConditionsAndArithmetic() {
        FormulaFunction f = FormulaFunction.of("sum(X, where=T == 'A') / sum(abs(X))");
        assertThat(f.fields()).containsExactly("X", "T");
        Batch b = new Batch(4, new Object[] {new String[] {"1", "-2", "3", "x"}, new String[] {"A", "B", "A", "A"}});
        assertThat(f.finish(f.add(f.start(), b))).isCloseTo(4.0 / 6.0, offset(1e-15));
        FormulaFunction zero = FormulaFunction.of("sum(X) / sum(X, where=X > 100)");
        assertThat(zero.finish(zero.add(zero.start(), b))).as("a division by zero is nothing, never infinity").isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePlaygroundShowsEachStateAsItsSumsNeverAsAnObjectName() {
        // tutorial 7, step 12: sum(r * w) / sum(w) on four rows; each state is [Σrw, Σw], shown with its values, the figure unrounded
        FormulaFunction f = FormulaFunction.of("wret", "sum(r * w) / sum(w)", List.of("r", "w"));
        Batch sample = new Batch(4, new Object[] {new double[] {0.012, -0.004, 0.020, 0.001}, new double[] {3, 1, 2, 5}});
        Map<String, Object> out = Playground.run(f, sample, 2, null, 0, 1);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) out.get("steps");
        assertThat(steps).isNotEmpty();
        for (Map<String, Object> s : steps) {
            assertThat(String.valueOf(s.get("state"))).doesNotContain("@").doesNotContain("$").contains("Σ=").contains("n=");
        }
        Map<String, Object> last = steps.get(steps.size() - 1);
        assertThat(String.valueOf(last.get("state"))).contains("Σ=11.0 n=4");
        assertThat((Double) out.get("final")).isCloseTo(0.077 / 11, offset(1e-12));
    }

    @Test
    void formulasPassTheHarness() {
        Batch rows = FunctionsTest.rows(10_000, 11);
        Batch xw = new Batch(rows.rows(), new Object[] {rows.columns()[0], rows.columns()[1]});
        for (String text : List.of("sum(x) / sum(abs(x))", "sum(x * w) / sum(w)", "count(where=x < 0) / count()", "stddev(x) / avg(w)",
                "sum(x, where=w > 50) - sum(x, where=w <= 50)")) {
            FormulaFunction f = FormulaFunction.of(null, text, List.of("x", "w"));
            FunctionCheck.Verdict v = FunctionCheck.run(f, xw, FunctionCheck.Options.defaults());
            assertThat(v.passed()).as(text + ": " + v.firstFailure()).isTrue();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSqlPathEqualsTheJavaPathOnTheTabularProvider() {
        try (PivotFixture fx = new PivotFixture(2_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Map<String, Object> view = Map.of("source", "trades", "rows", List.of("REGION"), "values", List.of(
                    Map.of("measure", "sum(PNL) / sum(abs(PNL))", "as", "R"),
                    Map.of("measure", "sum(NOTIONAL, where=DESK == 'RATES') / sum(NOTIONAL)", "as", "S"),
                    Map.of("measure", "count(where=PNL < 0) / count()", "as", "N")));
            Map<String, Object> a = fx.core.query(Viewer.of("rita"), PivotView.parse(view));
            Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
            double[] tot = fx.exec.query("SELECT sum(PNL) / sum(abs(PNL)), sum(CASE WHEN DESK = 'RATES' THEN NOTIONAL END) / sum(NOTIONAL), "
                    + "count(CASE WHEN PNL < 0 THEN 1 END)::DOUBLE / count(*) FROM trades", List.of(), rs -> {
                        rs.next();
                        return new double[] {rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)};
                    });
            Map<String, Object> grand = (Map<String, Object>) cells.get("*|*").get("values");
            assertThat(((Number) grand.get("R")).doubleValue()).isCloseTo(tot[0], offset(1e-12));
            assertThat(((Number) grand.get("S")).doubleValue()).isCloseTo(tot[1], offset(1e-12));
            assertThat(((Number) grand.get("N")).doubleValue()).isCloseTo(tot[2], offset(1e-12));
            for (String region : PivotFixture.REGIONS) {
                double r = fx.exec.query("SELECT sum(PNL) / sum(abs(PNL)) FROM trades WHERE REGION = ?", List.of(region), rs -> {
                    rs.next();
                    return rs.getDouble(1);
                });
                Map<String, Object> v = (Map<String, Object>) cells.get("REGION=" + region + "|*").get("values");
                assertThat(((Number) v.get("R")).doubleValue()).as(region).isCloseTo(r, offset(1e-12));
            }
        }
    }
}
