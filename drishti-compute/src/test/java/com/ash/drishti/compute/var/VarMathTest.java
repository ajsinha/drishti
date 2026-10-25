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
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The measures on small hand-made vectors whose answers are known exactly: VaR is the k-th worst (k = ceil(N (1 - a))), ES the mean of
 * the k worst, component VaR adds up to VaR and component ES to ES, ties break by scenario index, and two offsetting parts (a long and
 * a short) net in the sum.
 */
class VarMathTest {

    /** 100 scenarios: -1, -2, ... -100 shuffled by a fixed permutation; VaR 99% is the 1st worst (k = 1), 95% the 5th worst. */
    private static double[] hundred() {
        double[] v = new double[100];
        for (int i = 0; i < 100; i++) {
            v[(i * 37) % 100] = -(i + 1);
        }
        return v;
    }

    @Test
    void ranksFollowTheCeilingRule() {
        assertThat(VarMath.rank(509, 0.99)).isEqualTo(6);        // ceil(5.09)
        assertThat(VarMath.rank(509, 0.975)).isEqualTo(13);      // ceil(12.725)
        assertThat(VarMath.rank(100, 0.99)).isEqualTo(1);        // exactly 1: no floating-point 2
        assertThat(VarMath.rank(250, 0.99)).isEqualTo(3);        // ceil(2.5)
        assertThat(VarMath.rank(10, 0.999)).isEqualTo(1);
    }

    @Test
    void varAndEsAreReadFromTheSortedVector() {
        double[] v = hundred();
        assertThat(VarMath.var(v, 0.99)).isEqualTo(100);
        assertThat(VarMath.var(v, 0.95)).isEqualTo(96);
        assertThat(VarMath.es(v, 0.95)).isEqualTo((100 + 99 + 98 + 97 + 96) / 5.0);
        assertThat(VarMath.es(v, 0.975)).isEqualTo((100 + 99 + 98) / 3.0);   // ceil(2.5) = 3 worst
        // interpolated: N (1 - a) = 5 exactly at 95%: the 5th worst
        assertThat(VarMath.var(v, 0.95, VarMath.RankRule.INTERPOLATED)).isEqualTo(96);
        assertThat(VarMath.var(v, 0.955, VarMath.RankRule.INTERPOLATED)).isCloseTo(-(0.5 * -97 + 0.5 * -96), within(1e-9));
    }

    @Test
    void the509ScenarioRuleIsTheSixthWorstAndTheMeanOfThirteen() {
        double[] v = new double[509];
        for (int j = 0; j < 509; j++) {
            v[j] = 1000 - j;                                       // worst are the last scenarios: -8, -7 ... no: 1000-508 = 492
        }
        for (int k = 0; k < 20; k++) {
            v[400 + k] = -1000 * (k + 1);                          // twenty losses -1000 .. -20000
        }
        assertThat(VarMath.var(v, 0.99)).isEqualTo(15000);       // worst: -20000, -19000, -18000, -17000, -16000, -15000
        double sum13 = 0;
        for (int k = 8; k <= 20; k++) {
            sum13 += 1000 * k;
        }
        assertThat(VarMath.es(v, 0.975)).isEqualTo(sum13 / 13);
    }

    @Test
    void tiesBreakByTheOlderScenario() {
        double[] v = {-5, -5, 3, -5, 1};
        assertThat(VarMath.order(v)).containsExactly(0, 1, 3, 4, 2);
    }

    @Test
    void componentsAddUpToVarAndComponentEsToEs() {
        Random r = new Random(11);
        int n = 509;
        double[][] parts = new double[5][n];
        double[] total = new double[n];
        for (int p = 0; p < 5; p++) {
            for (int j = 0; j < n; j++) {
                parts[p][j] = Math.round(r.nextGaussian() * (p + 1) * 10_000);
                total[j] += parts[p][j];
            }
        }
        int[] order = VarMath.order(total);
        double var = VarMath.var(total, order, 0.99, VarMath.RankRule.KTH_WORST);
        int[] tail = VarMath.tail(order, 0.99, 5);
        assertThat(tail).hasSize(5);
        assertThat(tail[2]).isEqualTo(order[5]);                    // centred on the VaR scenario (rank 6)
        double smooth = VarMath.smoothedVar(total, tail);
        double scale = VarMath.componentScale(var, smooth);
        double sumComp = 0;
        double sumRaw = 0;
        double sumEs = 0;
        for (double[] p : parts) {
            sumComp += VarMath.component(p, tail, scale);
            sumRaw += VarMath.component(p, tail, 1.0);
            sumEs += VarMath.componentEs(p, order, 0.975);
        }
        assertThat(sumComp).isCloseTo(var, within(1e-6));
        assertThat(sumRaw).isCloseTo(smooth, within(1e-6));
        assertThat(sumEs).isCloseTo(VarMath.es(total, 0.975), within(1e-6));
    }

    @Test
    void aLongAndAShortNetInTheSum() {
        double[] unit = new double[509];
        Random r = new Random(3);
        for (int j = 0; j < unit.length; j++) {
            unit[j] = r.nextGaussian() * 1000;
        }
        double[] longTrade = new double[509];
        double[] shortTrade = new double[509];
        for (int j = 0; j < unit.length; j++) {
            longTrade[j] = 100 * unit[j];                        // long 100
            shortTrade[j] = -40 * unit[j];                       // short 40
        }
        double[] position = longTrade.clone();
        VarMath.addInto(position, shortTrade);
        double[] net = new double[509];
        for (int j = 0; j < unit.length; j++) {
            net[j] = 60 * unit[j];                               // the net holding: long 60
        }
        assertThat(VarMath.var(position, 0.99)).isCloseTo(VarMath.var(net, 0.99), within(1e-6));
        assertThat(VarMath.var(position, 0.99)).isCloseTo(0.6 * VarMath.var(longTrade, 0.99), within(1e-6));
        // trade VaRs do not add: 100 + 40 is not 60
        assertThat(VarMath.var(longTrade, 0.99) + VarMath.var(shortTrade, 0.99)).isGreaterThan(VarMath.var(position, 0.99));
    }
}
