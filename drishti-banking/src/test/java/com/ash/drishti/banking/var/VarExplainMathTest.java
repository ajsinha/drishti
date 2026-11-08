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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.var.VarExplainMath.Op;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The states and steps of "why VaR moved" (VAR_GUIDE.md section 3.11; docs/lenses/README.md section 4.1's tests) on crafted vectors where
 * each cause alone moves VaR by a known amount: one trade out, one in, one restated vector, one book moved, a rolled window. Each step
 * equals it and the others are zero; on random inputs the steps telescope to the change and the last state is the later date's scope.
 */
class VarExplainMathTest {

    static final VarCalc CALC = new VarCalc(0.9, 0.9, com.ash.drishti.banking.compute.var.VarMath.RankRule.KTH_WORST, 3, true);
    static final int N = 20;
    static final List<Op> DEFAULT = VarExplainMath.plan(VarExplainMath.causes(VarExplainProperties.DEFAULT_ORDER), false, false);

    static List<LocalDate> days(int from, int count) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1).plusDays(from);
        for (int i = 0; i < count; i++) {
            out.add(d.plusDays(i));
        }
        return out;
    }

    static double[] random(Random r, int n, double scale) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = Math.round(r.nextGaussian() * scale * 100) / 100.0;
        }
        return v;
    }

    static double[] plus(double[] a, double[] b) {
        double[] o = a.clone();
        for (int i = 0; i < o.length; i++) {
            o[i] += b[i];
        }
        return o;
    }

    static VarExplainMath.Inputs same(double[] captured, double[] fromTotal, double[] toTotal, double[] movedIn, double[] out, double[] in) {
        List<LocalDate> d = days(0, N);
        return new VarExplainMath.Inputs(d, d, captured, fromTotal, toTotal, movedIn, null, out, null, in, null, movedIn, null);
    }

    static Map<Op, Double> effects(VarExplainMath.Result r) {
        Map<Op, Double> m = new java.util.EnumMap<>(Op.class);
        r.steps().forEach(s -> m.merge(s.op(), s.effect(), Double::sum));
        return m;
    }

    static void onlyOne(VarExplainMath.Result r, Op op, double expected) {
        Map<Op, Double> e = effects(r);
        for (Map.Entry<Op, Double> x : e.entrySet()) {
            assertThat(x.getValue()).as(x.getKey().label).isCloseTo(x.getKey() == op ? expected : 0.0, within(1e-9));
        }
        assertThat(r.sumOfSteps()).isCloseTo(r.change(), within(1e-9));
    }

    final Random rnd = new Random(11);
    final double[] book = random(rnd, N, 1000);
    final double[] trade = random(rnd, N, 400);

    @Test
    void aTradeOutIsTheTradesOutStepAlone() {
        VarExplainMath.Result r = VarExplainMath.run(same(null, plus(book, trade), book, null, trade, null), DEFAULT, CALC);
        onlyOne(r, Op.OUT, CALC.var(book) - CALC.var(plus(book, trade)));
    }

    @Test
    void aTradeInIsTheTradesInStepAlone() {
        VarExplainMath.Result r = VarExplainMath.run(same(null, book, plus(book, trade), null, null, trade), DEFAULT, CALC);
        onlyOne(r, Op.IN, CALC.var(plus(book, trade)) - CALC.var(book));
    }

    @Test
    void aRestatedVectorIsTheRestatementStepAlone() {
        double[] restated = plus(book, trade);
        VarExplainMath.Result r = VarExplainMath.run(same(book, restated, restated, null, null, null), DEFAULT, CALC);
        onlyOne(r, Op.RESTATE, CALC.var(restated) - CALC.var(book));
        assertThat(r.first().var()).isCloseTo(CALC.var(book), within(1e-9));
    }

    @Test
    void aBookMovedInIsTheHierarchyStepAlone() {
        VarExplainMath.Result r = VarExplainMath.run(same(null, book, plus(book, trade), trade, null, null), DEFAULT, CALC);
        onlyOne(r, Op.HIER, CALC.var(plus(book, trade)) - CALC.var(book));
    }

    @Test
    void aRolledWindowIsTheTwoWindowStepsAlone() {
        List<LocalDate> d0 = days(0, N);
        List<LocalDate> d1 = days(1, N);
        double[] to = new double[N];
        System.arraycopy(book, 1, to, 0, N - 1);                       // the same P&L on the common days: no market move
        to[N - 1] = -5000;                                             // the new day: a heavy loss, in the tail
        VarExplainMath.Inputs in = new VarExplainMath.Inputs(d0, d1, null, book, to, null, null, null, null, null, null, null, null);
        VarExplainMath.Result r = VarExplainMath.run(in, DEFAULT, CALC);
        double[] common = java.util.Arrays.copyOfRange(book, 1, N);
        Map<Op, Double> e = effects(r);
        assertThat(e.get(Op.DROP)).isCloseTo(CALC.var(common) - CALC.var(book), within(1e-9));
        assertThat(e.get(Op.MARKET)).isCloseTo(0.0, within(1e-9));
        assertThat(e.get(Op.ADD)).isCloseTo(CALC.var(to) - CALC.var(common), within(1e-9));
        assertThat(e.get(Op.ADD)).isGreaterThanOrEqualTo(0.0);           // k is 2 in both: a day entering can only raise VaR or leave it
        assertThat(e.get(Op.DROP)).isLessThanOrEqualTo(1e-9);            // a day leaving can only lower it or leave it
        assertThat(r.sumOfSteps()).isCloseTo(r.change(), within(1e-9));
        assertThat(r.days().union()).hasSize(N + 1);
        assertThat(r.last().rank(N)).isEqualTo(1);                       // the new day is the worst of the later set
    }

    @Test
    void everythingAtOnceTelescopesAndEndsAtTheLaterScope() {
        Random r = new Random(5);
        for (int run = 0; run < 50; run++) {
            List<LocalDate> d0 = days(0, N);
            List<LocalDate> d1 = days(1 + run % 3, N + run % 2);
            int n1 = d1.size();
            VarExplainMath.Inputs in = new VarExplainMath.Inputs(d0, d1, random(r, N, 900), random(r, N, 900), random(r, n1, 900),
                    random(r, N, 100), random(r, N, 100), random(r, N, 200), random(r, N, 50), random(r, n1, 200), random(r, n1, 50),
                    random(r, n1, 100), random(r, n1, 100));
            for (List<String> order : List.of(VarExplainProperties.DEFAULT_ORDER,
                    List.of("trades-out", "restatement", "window", "market", "hierarchy", "trades-in"),
                    List.of("hierarchy", "restatement", "trades-out", "market", "window", "trades-in"))) {
                for (boolean amend : List.of(false, true)) {
                    List<Op> ops = VarExplainMath.plan(VarExplainMath.causes(order), amend, false);
                    VarExplainMath.Result res = VarExplainMath.run(in, ops, CALC);
                    assertThat(res.states()).hasSize(ops.size() + 1);
                    assertThat(res.sumOfSteps()).isCloseTo(res.change(), within(1e-6));
                    assertThat(res.first().var()).isCloseTo(CALC.var(in.captured()), within(1e-9));
                    assertThat(res.last().var()).isCloseTo(CALC.var(in.toTotal()), within(1e-9));
                    double[] last = res.last().compact();
                    for (int i = 0; i < n1; i++) {
                        assertThat(last[i]).isCloseTo(in.toTotal()[i], within(1e-6));
                    }
                }
            }
        }
    }

    @Test
    void aReplacedScenarioSetIsOneStep() {
        List<Op> ops = VarExplainMath.plan(VarExplainMath.causes(VarExplainProperties.DEFAULT_ORDER), false, true);
        assertThat(ops).containsExactly(Op.RESTATE, Op.HIER, Op.OUT, Op.SET_CHANGE, Op.IN);
        VarExplainMath.Inputs in = new VarExplainMath.Inputs(days(0, N), days(100, N), null, book, trade, null, null, null, null, null, null,
                null, null);
        VarExplainMath.Result r = VarExplainMath.run(in, ops, CALC);
        assertThat(effects(r).get(Op.SET_CHANGE)).isCloseTo(CALC.var(trade) - CALC.var(book), within(1e-9));
    }

    @Test
    void componentsOfAStateAddUpToItsVar() {
        VarExplainMath.Result r = VarExplainMath.run(same(null, plus(book, trade), plus(book, trade), null, null, null), DEFAULT, CALC);
        VarExplainMath.State s = r.last();
        double a = VarExplainMath.component(s, r.days().liftTo(book));
        double b = VarExplainMath.component(s, r.days().liftTo(trade));
        assertThat(a + b).isCloseTo(s.var(), within(1e-9));
        assertThat(s.var() - VarExplainMath.varWithout(s, r.days().liftTo(trade), CALC)).isCloseTo(s.var() - CALC.var(book), within(1e-9));
    }

    @Test
    void aBadOrderIsRefusedInWords() {
        assertThatThrownBy(() -> VarExplainMath.plan(VarExplainMath.causes(List.of("market", "restatement", "hierarchy", "trades-out", "window",
                "trades-in")), false, false)).hasMessageContaining("restatement and trades-out come before market");
        assertThatThrownBy(() -> VarExplainMath.plan(VarExplainMath.causes(List.of("restatement", "hierarchy", "trades-out", "trades-in", "window",
                "market")), false, false)).hasMessageContaining("trades-in comes after market");
        assertThatThrownBy(() -> VarExplainMath.plan(VarExplainMath.causes(List.of("restatement", "window", "hierarchy", "trades-out", "market",
                "trades-in")), false, false)).hasMessageContaining("window is next to market");
        assertThatThrownBy(() -> VarExplainMath.causes(List.of("weather"))).hasMessageContaining("not a cause");
        assertThatThrownBy(() -> new VarExplainProperties(null, List.of("market"), null, null, null, null, null, null, null))
                .hasMessageContaining("each cause once");
        assertThatThrownBy(() -> new VarExplainProperties(null, null, "three", null, null, null, null, null, null)).hasMessageContaining("two or one");
        assertThat(VarExplainProperties.defaults().order()).isEqualTo(VarExplainProperties.DEFAULT_ORDER);
    }
}
