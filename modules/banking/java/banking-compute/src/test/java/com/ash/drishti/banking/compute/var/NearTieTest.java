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
package com.ash.drishti.banking.compute.var;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The near-tie rule (HIGH_PERFORMANCE_NATIVE.md section 6.2, VAR_GUIDE.md section 18): when the Java and the BLAS sums of a scope differ
 * in their last bits and two scenarios at the VaR rank swap places, both paths still pick the same scenario (the older date) and give VaR
 * and ES within a relative 1e-12; exact ties keep the older scenario first, as before.
 */
class NearTieTest {

    static final int N = 509;

    /** A "Java" vector with a near tie at the 99% rank, and its "BLAS" twin: every value moved by a few last bits, the pair swapped. */
    static double[][] pair(long seed) {
        Random r = new Random(seed);
        double[] java = new double[N];
        for (int i = 0; i < N; i++) {
            java[i] = r.nextGaussian() * 1e7;
        }
        int k = VarMath.rank(N, 0.99);
        int[] o = VarMath.exactOrder(java);
        int a = Math.min(o[k - 1], o[k]);                                // the k-th and (k+1)-th worst become a near tie
        int b = Math.max(o[k - 1], o[k]);
        double x = java[o[k - 1]];
        java[a] = x * (1 + 3e-15);                                        // the older scenario a little worse...
        java[b] = x;
        double[] blas = java.clone();
        for (int i = 0; i < N; i++) {
            blas[i] = r.nextBoolean() ? Math.nextUp(blas[i]) : Math.nextDown(blas[i]);
        }
        blas[a] = x;                                                      // ...and on the other path a little better: the exact order flips
        blas[b] = x * (1 + 3e-15);
        return new double[][] {java, blas, {a, b, k}};
    }

    @Test
    void bothPathsPickTheOlderScenarioAndAgreeWithinTheBound() {
        for (long seed = 1; seed <= 20; seed++) {
            double[][] p = pair(seed);
            double[] java = p[0];
            double[] blas = p[1];
            int a = (int) p[2][0];
            int k = (int) p[2][2];
            assertThat(VarMath.exactOrder(java)[k - 1]).isNotEqualTo(VarMath.exactOrder(blas)[k - 1]);   // without the rule they would differ
            int[] oj = VarMath.order(java);
            int[] ob = VarMath.order(blas);
            assertThat(oj[k - 1]).as("the VaR scenario, seed " + seed).isEqualTo(ob[k - 1]).isEqualTo(a);
            double scale = 1e-12 * Math.abs(java[a]);
            assertThat(Math.abs(VarMath.var(java, 0.99) - VarMath.var(blas, 0.99))).isLessThanOrEqualTo(scale);
            assertThat(VarMath.var(java, 0.99)).isEqualTo(VarMath.var(java, oj, 0.99, VarMath.RankRule.KTH_WORST));
            assertThat(Math.abs(VarMath.es(java, 0.975) - VarMath.es(blas, 0.975))).isLessThanOrEqualTo(scale);
            int[] tj = VarMath.tail(oj, 0.99, 5);
            int[] tb = VarMath.tail(ob, 0.99, 5);
            assertThat(tj).containsExactly(tb);                           // component VaR averages over the same scenarios
        }
    }

    @Test
    void exactTiesAndClearOrdersAreAsBefore() {
        double[] v = {5, -3, -3, 7, -10, 0.0, -0.0, 2};
        assertThat(VarMath.order(v)).containsExactly(VarMath.exactOrder(v));
        assertThat(VarMath.order(v)).containsExactly(4, 1, 2, 5, 6, 7, 0, 3);
        Random r = new Random(9);
        double[] w = new double[N];
        for (int i = 0; i < N; i++) {
            w[i] = r.nextGaussian() * 1e6;
        }
        assertThat(VarMath.order(w)).containsExactly(VarMath.exactOrder(w));  // no near ties: the same order as before
        assertThat(VarMath.order(new double[N])).containsExactly(VarMath.exactOrder(new double[N]));
    }
}
