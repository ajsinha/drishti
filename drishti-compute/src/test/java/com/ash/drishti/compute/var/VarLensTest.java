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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.compute.vector.VectorStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * The horizons and confidences of a VaR answer (2.9.7, VAR_GUIDE.md section 18.6): √10 is the 1-day VaR and ES times √10 exactly; the
 * 10-day sum's windows by hand on a small vector, 509 scenarios giving 500 windows and 250 giving 241; the ranks of each confidence; ES;
 * components adding up; and the 10-day vector of any scope equal to the bit whatever the threads that summed its 1-day vector.
 */
class VarLensTest {

    private static final VarCalc BASE = VarCalc.defaults();

    private static double[] gaussian(int n, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double[] v = new double[n];
        for (int j = 0; j < n; j++) {
            v[j] = r.nextGaussian() * 1e6 + 1_234.5;
        }
        return v;
    }

    @Test
    void sqrtTenIsTheOneDayVarAndEsTimesSqrtTenExactly() {
        for (long seed = 1; seed <= 20; seed++) {
            double[] v = gaussian(509, seed);
            for (double q : new double[] {0.95, 0.975, 0.99, 0.995}) {
                VarLens one = VarLens.oneDay(q, 0.975);
                VarLens ten = new VarLens(10, VarLens.Method.SQRT, q, 0.975);
                VarCalc.Measures m1 = one.measures(BASE, v, one.apply(v));
                VarCalc.Measures m10 = ten.measures(BASE, v, ten.apply(v));
                assertThat(m10.var()).isEqualTo(m1.var() * VarLens.SQRT10);
                assertThat(m10.es()).isEqualTo(m1.es() * VarLens.SQRT10);
                assertThat(m10.k()).isEqualTo(m1.k());
                // the scaled vector read directly gives the same VaR (the order of the scenarios does not change)
                assertThat(ten.calc(BASE).measures(ten.apply(v)).var()).isCloseTo(m10.var(), Offset.offset(1e-6));
                assertThat(ten.apply(v)).hasSize(509);
            }
        }
        assertThat(VarLens.SQRT10).isEqualTo(Math.sqrt(10));
    }

    @Test
    void theTenDaySumsByHand() {
        double[] v = new double[14];
        for (int j = 0; j < v.length; j++) {
            v[j] = j + 1;                                                // 1, 2, ... 14
        }
        double[] w = VarLens.tenDaySum(v);
        assertThat(w).containsExactly(55, 65, 75, 85, 95);                // 1..10, 2..11, ... 5..14
        assertThat(VarLens.tenDaySum(new double[9])).isEmpty();
        assertThat(VarLens.tenDaySum(new double[10])).hasSize(1);
        VarLens sum = new VarLens(10, VarLens.Method.SUM, 0.99, 0.975);
        assertThat(sum.apply(new double[509])).hasSize(500);
        assertThat(sum.apply(new double[250])).hasSize(241);
        assertThat(sum.width(509)).isEqualTo(500);
        assertThat(sum.width(250)).isEqualTo(241);
        // a loss of 10 on one day sits in the 10 windows that hold it
        double[] spike = new double[30];
        spike[15] = -10;
        double[] ws = VarLens.tenDaySum(spike);
        int hit = 0;
        for (int i = 0; i < ws.length; i++) {
            if (ws[i] == -10) {
                hit++;
                assertThat(i).isBetween(6, 15);
            }
        }
        assertThat(hit).isEqualTo(10);
        // the window dates: each window is labelled with its last scenario day; its first is the scenario at its index
        List<LocalDate> days = new ArrayList<>();
        for (int j = 0; j < 14; j++) {
            days.add(LocalDate.of(2026, 9, 1).plusDays(j));
        }
        assertThat(sum.dates(days)).hasSize(5).first().isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(sum.windowStart(days, 0)).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void theTenDaySumReadsItsVarAndEsOnTheWindows() {
        double[] v = gaussian(509, 7);
        VarLens sum = new VarLens(10, VarLens.Method.SUM, 0.99, 0.975);
        double[] w = sum.apply(v);
        VarCalc.Measures m = sum.measures(BASE, v, w);
        assertThat(m.k()).isEqualTo(5);                                   // ceil(500 × 0.01)
        assertThat(m.esCount()).isEqualTo(13);                            // ceil(500 × 0.025)
        double[] sorted = w.clone();
        java.util.Arrays.sort(sorted);
        assertThat(m.var()).isEqualTo(-sorted[4]);
        double es = 0;
        for (int i = 0; i < 13; i++) {
            es += sorted[i];
        }
        assertThat(m.es()).isCloseTo(-es / 13, Offset.offset(1e-6));
    }

    @Test
    void eachConfidenceReadsItsRankOfTheSameSortedVector() {
        double[] v = gaussian(509, 3);
        double[] sorted = v.clone();
        java.util.Arrays.sort(sorted);
        int[][] ranks = {{95, 26}, {97, 13}, {99, 6}, {100, 3}};          // 95%: 26th, 97.5%: 13th, 99%: 6th, 99.5%: 3rd worst of 509
        double[] qs = {0.95, 0.975, 0.99, 0.995};
        for (int i = 0; i < qs.length; i++) {
            VarLens l = VarLens.oneDay(qs[i], 0.975);
            VarCalc.Measures m = l.measures(BASE, v, v);
            assertThat(m.k()).as(qs[i] + "").isEqualTo(ranks[i][1]);
            assertThat(m.var()).isEqualTo(-sorted[ranks[i][1] - 1]);
        }
        // ES at 95%: the mean of the 26 worst
        VarCalc.Measures m = VarLens.oneDay(0.99, 0.95).measures(BASE, v, v);
        double s = 0;
        for (int i = 0; i < 26; i++) {
            s += sorted[i];
        }
        assertThat(m.esCount()).isEqualTo(26);
        assertThat(m.es()).isCloseTo(-s / 26, Offset.offset(1e-6));
        // the interpolated rank rule follows the confidence too
        VarCalc interp = new VarCalc(0.99, 0.975, VarMath.RankRule.INTERPOLATED, 5, true);
        assertThat(VarLens.oneDay(0.95, 0.975).measures(interp, v, v).var()).isEqualTo(VarMath.var(v, 0.95, VarMath.RankRule.INTERPOLATED));
    }

    @Test
    void componentsOfEitherTenDayMethodAddUpToItsVar() {
        double[] a = gaussian(509, 11);
        double[] b = gaussian(509, 12);
        double[] total = new double[509];
        for (int j = 0; j < 509; j++) {
            total[j] = a[j] + b[j];
        }
        for (VarLens.Method method : new VarLens.Method[] {VarLens.Method.SQRT, VarLens.Method.SUM}) {
            VarLens l = new VarLens(10, method, 0.99, 0.975);
            double[] t = l.apply(total);
            VarCalc.Measures m = l.measures(BASE, total, t);
            VarCalc c = l.calc(BASE);
            List<VarCalc.Contribution> cs = c.contributions(t, m, List.of(new VarCalc.Part("a", "a", "x", l.apply(a), Double.NaN),
                    new VarCalc.Part("b", "b", "x", l.apply(b), Double.NaN)));
            assertThat(cs.get(0).componentVar() + cs.get(1).componentVar()).as(method + "").isCloseTo(m.var(), Offset.offset(1e-3));
            assertThat(cs.get(0).componentEs() + cs.get(1).componentEs()).as(method + "").isCloseTo(m.es(), Offset.offset(1e-3));
        }
    }

    @Test
    void theTenDayVectorOfAnyScopeIsEqualToTheBitWhateverTheThreads() {
        int rows = 20_000;
        SplittableRandom r = new SplittableRandom(5);
        VectorStore.Builder sb = new VectorStore.Builder(509, 16_384, Long.MAX_VALUE);
        double[] row = new double[509];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < 509; j++) {
                row[j] = r.nextGaussian() * 1e5;
            }
            sb.add(row, 509);
        }
        VectorStore store = sb.build();
        List<OrdinalSet> scopes = new ArrayList<>(List.of(OrdinalSet.range(0, rows), OrdinalSet.range(5_000, 9_100), OrdinalSet.of(4_095, 4_096)));
        OrdinalSet.Builder basket = new OrdinalSet.Builder();
        for (int i = 0; i < 3_000; i++) {
            basket.add(r.nextInt(rows));
        }
        scopes.add(basket.build());
        VarLens sum = new VarLens(10, VarLens.Method.SUM, 0.99, 0.975);
        List<double[]> one = new ArrayList<>();
        try (ParallelSums p = new ParallelSums(1, 4_096)) {
            scopes.forEach(s -> one.add(sum.apply(p.sum(store, s))));
        }
        for (int threads : new int[] {2, 3, 8, 24}) {
            try (ParallelSums p = new ParallelSums(threads, 4_096)) {
                for (int i = 0; i < scopes.size(); i++) {
                    double[] w = sum.apply(p.sum(store, scopes.get(i)));
                    assertThat(w).hasSize(500);
                    for (int j = 0; j < w.length; j++) {
                        assertThat(Double.doubleToRawLongBits(w[j])).as(threads + " threads, scope " + i + ", window " + j)
                                .isEqualTo(Double.doubleToRawLongBits(one.get(i)[j]));
                    }
                    assertThat(sum.measures(BASE, null, w).var()).isEqualTo(sum.measures(BASE, null, one.get(i)).var());
                }
            }
        }
    }

    @Test
    void wordsAndLabels() {
        assertThat(VarLens.parseHorizon("10D")).isEqualTo(10);
        assertThat(VarLens.parseHorizon(null)).isEqualTo(1);
        assertThatThrownBy(() -> VarLens.parseHorizon("5d")).hasMessageContaining("1d or 10d");
        assertThat(VarLens.Method.parse("SQRT")).isEqualTo(VarLens.Method.SQRT);
        assertThat(VarLens.Method.parse("sum")).isEqualTo(VarLens.Method.SUM);
        assertThatThrownBy(() -> VarLens.Method.parse("full")).hasMessageContaining("sqrt10");
        assertThat(new VarLens(10, VarLens.Method.SUM, 0.99, 0.975).label()).isEqualTo("10-day (sum)");
        assertThat(new VarLens(10, null, 0.99, 0.975).method()).isEqualTo(VarLens.Method.SQRT);
        assertThat(new VarLens(1, VarLens.Method.SUM, 0.99, 0.975).method()).isEqualTo(VarLens.Method.NONE);
        assertThat(VarLens.oneDay(0.99, 0.975).methodName()).isNull();
        assertThat(new VarLens(10, VarLens.Method.SQRT, 0.99, 0.975).methodName()).isEqualTo("SQRT10");
        assertThat(VarLens.pct(0.975)).isEqualTo("97.5%");
        assertThat(VarLens.pct(0.99)).isEqualTo("99%");
        assertThatThrownBy(() -> new VarLens(5, null, 0.99, 0.975)).hasMessageContaining("1d and 10d");
    }
}
