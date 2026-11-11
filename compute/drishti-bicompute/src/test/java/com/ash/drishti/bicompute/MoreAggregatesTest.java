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

import static com.ash.drishti.bicompute.StatisticsFunctionsTest.N;
import static com.ash.drishti.bicompute.StatisticsFunctionsTest.SKEW;
import static com.ash.drishti.bicompute.StatisticsFunctionsTest.TOLERANCE;
import static com.ash.drishti.bicompute.StatisticsFunctionsTest.fn;
import static com.ash.drishti.bicompute.StatisticsFunctionsTest.num;
import static com.ash.drishti.bicompute.StatisticsFunctionsTest.rows;
import static com.ash.drishti.bicompute.StatisticsFunctionsTest.run;
import static com.ash.drishti.bicompute.StatisticsFunctionsTest.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.functions.MoreAggregates;
import com.ash.drishti.bicompute.harness.FunctionHarness;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The aggregates that complete Excel's and SAS's everyday set (RACHANA_REFERENCE.md section F4): Excel's documented examples (SUMPRODUCT,
 * SUMSQ, PRODUCT, DEVSQ, SKEW.P, COVARIANCE.P, STEYX, AVEDEV, PERCENTILE.EXC, QUARTILE.EXC, RANK.AVG, PERCENTRANK.INC, MODE.MULT); nulls,
 * nothing and wrong arguments; every mergeable one through the split-and-merge harness (2, 3 and 16 parts, five seeds, states serialised)
 * within 1e-12, and the holistic ones merging their values exactly.
 */
class MoreAggregatesTest {

    @Test
    void excelsDocumentedExamples() {
        assertThat((Double) run(fn("sumproduct"), new double[] {3, 4, 8, 6, 1, 9}, new double[] {2, 7, 6, 7, 5, 3})).isEqualTo(156.0);
        assertThat(num("sumsq", 3, 4)).isEqualTo(25.0);                                                   // SUMSQ
        assertThat(num("product", 5, 15, 30)).isEqualTo(2250.0);                                          // PRODUCT
        assertThat(num("devsq", 4, 5, 8, 7, 11, 4, 3)).isCloseTo(48, within(1e-12));                       // DEVSQ
        assertThat(num("skewness_pop", SKEW)).isCloseTo(0.303193339, within(1e-9));                       // SKEW.P
        assertThat((Double) run(fn("covar_pop"), new double[] {3, 2, 4, 5, 6}, new double[] {9, 7, 12, 15, 17}))
                .isCloseTo(5.2, within(1e-12));                                                           // COVARIANCE.P
        assertThat((Double) run(fn("steyx"), new double[] {2, 3, 9, 1, 8, 7, 5}, new double[] {6, 5, 11, 7, 5, 4, 4}))
                .isCloseTo(3.305718950, within(1e-9));                                                    // STEYX(known_y, known_x)
        assertThat(num("avedev", 4, 5, 6, 7, 5, 4, 3)).isCloseTo(1.020408163, within(1e-9));              // AVEDEV
        assertThat((Double) run(fn("percentile_exc", 0.25), new double[] {1, 2, 3, 6, 6, 6, 7, 8, 9})).isEqualTo(2.5);   // PERCENTILE.EXC
        double[] q = {6, 7, 15, 36, 39, 40, 41, 42, 43, 47, 49};
        assertThat((Double) run(fn("percentile_exc", 0.25), q)).isEqualTo(15.0);                          // QUARTILE.EXC(.., 1)
        assertThat((Double) run(fn("percentile_exc", 0.75), q)).isEqualTo(43.0);                          // QUARTILE.EXC(.., 3)
        assertThat((Double) run(fn("rank_avg", 94), new double[] {89, 88, 92, 101, 94, 97, 95})).isEqualTo(4.0);   // RANK.AVG
        assertThat((Double) run(fn("rank_avg", 5, 1), new double[] {5, 3, 5, 1})).isEqualTo(3.5);          // ties averaged, ascending
        double[] pr = {13, 12, 11, 8, 4, 3, 2, 1, 1, 1};
        assertThat((Double) run(fn("percent_rank_of", 2), pr)).isEqualTo(0.333);                           // PERCENTRANK.INC
        assertThat((Double) run(fn("percent_rank_of", 4), pr)).isEqualTo(0.555);
        assertThat((Double) run(fn("percent_rank_of", 8), pr)).isEqualTo(0.666);
        assertThat((Double) run(fn("percent_rank_of", 5), pr)).isEqualTo(0.583);
        assertThat((Double) run(fn("percent_rank_of", 5, 5), pr)).isEqualTo(0.58333);
        assertThat(text(new MoreAggregates.ModeMulti(), "1", "2", "3", "4", "3", "2", "1", "2", "3", "5", "6", "1")).isEqualTo("1, 2, 3");   // MODE.MULT
        assertThat(text(new MoreAggregates.ModeMulti(), "EUR", "USD", "EUR", "USD", "GBP")).isEqualTo("EUR, USD");
    }

    @Test
    void nullsNothingAndWrongArguments() {
        assertThat((Double) run(fn("sumproduct"), new double[] {3, N, 2}, new double[] {2, 5, N})).isEqualTo(6.0);   // a pair with a null: out
        assertThat(num("sumsq")).isEqualTo(0.0);                                                          // a sum of nothing is 0
        assertThat(num("product", 2, N, 3)).isEqualTo(6.0);
        assertThat(num("product")).isNull();
        assertThat(num("product", 2, 0, -3)).isEqualTo(0.0);
        assertThat(num("product", -2, 3)).isEqualTo(-6.0);
        assertThat(num("product", 1e200, 1e200, 1e-250)).isCloseTo(1e150, within(1e138));                 // never overflows on the way
        assertThat(num("product", 1e300, 1e300)).isNull();                                                // past a number's range: said
        assertThat(fn("product").emptyNote()).contains("range");
        assertThat(num("devsq")).isNull();
        assertThat(num("skewness_pop", 5, 5, 5)).isNull();
        assertThat(run(fn("steyx"), new double[] {1, 2}, new double[] {3, 4})).isNull();                   // under three pairs
        assertThat((Double) run(fn("percentile_exc", 0.01), new double[] {1, 2, 3, 6, 6, 6, 7, 8, 9})).isNull();   // #NUM!
        assertThat(fn("percentile_exc", 0.01).emptyNote()).contains("#NUM!");
        assertThat((Double) run(fn("rank_avg", 7), new double[] {1, 2})).isNull();                         // not among them: #N/A
        assertThat((Double) run(fn("percent_rank_of", 20), new double[] {1, 2})).isNull();
        assertThat(text(new MoreAggregates.ModeMulti(), "1", "2", "3")).isNull();                           // nothing repeats
        assertThatThrownBy(() -> fn("percentile_exc", 1.0)).hasMessageContaining("exclusive");
        assertThatThrownBy(() -> fn("rank_avg")).hasMessageContaining("the value to rank");
        assertThatThrownBy(() -> fn("percent_rank_of", 1, 0)).hasMessageContaining("significant digits");
        assertThat(fn("sumproduct").plainSum()).isTrue();
        assertThat(fn("product").plainSum()).isFalse();
    }

    @Test
    void everyNewMergeableOnePassesTheSplitAndMergeHarness() {
        for (String name : List.of("sumproduct", "sumsq", "devsq", "skewness_pop", "covar_pop", "steyx")) {
            AggFunction<Object> f = fn(name);
            assertThat(f.kind()).as(name).isEqualTo(Kind.MERGEABLE);
            // steyx on rows where x explains y only in part: where r² is near 1, 1 − r² cancels and no order of merges keeps 1e-12
            Batch b = rows(10_000, 13, "steyx".equals(name));
            Batch in = f.inputs() == 2 ? b : new Batch(b.rows(), new Object[] {b.columns()[0]});
            FunctionHarness.Report rep = FunctionHarness.run(f, in, 5, TOLERANCE);
            assertThat(rep.passed()).as(name + ": worst " + rep.worst()).isTrue();
        }
        // a product of 2,000 returns near 1 (a compounded growth factor), and one past the double range on the way
        Random r = new Random(5);
        double[] g = new double[2_000];
        double[] big = new double[2_000];
        for (int i = 0; i < g.length; i++) {
            g[i] = i % 97 == 0 ? Double.NaN : 1 + r.nextGaussian() * 0.01;
            big[i] = (i < 1_000 ? 1e100 : 1e-100) * (1 + r.nextDouble() * 0.001);    // 1e100000 on the way, about 2.7 at the end
        }
        for (double[] x : List.of(g, big)) {
            FunctionHarness.Report rep = FunctionHarness.run(fn("product"), new Batch(x.length, new Object[] {x}), 5, TOLERANCE);
            assertThat(rep.passed()).as("product: worst " + rep.worst()).isTrue();
            assertThat(rep.onePass()).isNotNull();
        }
    }

    @Test
    void theNewHolisticOnesMergeTheirValuesExactly() {
        Batch b = rows(5_000, 9, false);
        Batch x = new Batch(b.rows(), new Object[] {b.columns()[0]});
        double some = ((double[]) b.columns()[0])[1];
        for (AggFunction<Object> f : List.of(fn("avedev"), fn("percentile_exc", 0.3), fn("rank_avg", some), fn("percent_rank_of", 0))) {
            assertThat(f.kind()).isEqualTo(Kind.HOLISTIC);
            FunctionHarness.Report rep = FunctionHarness.run(f, x, 2, 0);
            assertThat(rep.passed()).as(f.name() + ": worst " + rep.worst()).isTrue();
        }
        String[] t = new String[3_000];
        Random r = new Random(4);
        for (int i = 0; i < t.length; i++) {
            t[i] = i % 40 == 0 ? null : "CP-" + r.nextInt(12);
        }
        Batch tb = new Batch(t.length, new Object[] {t});
        AggFunction<Object> f = Functions.builtIn("mode_multi", List.of("x"), List.of()).orElseThrow();
        Object one = f.result(f.add(f.start(), tb));
        Object merged = f.start();
        for (int i = 0; i < t.length; i += 250) {
            merged = f.merge(f.decode(f.encode(merged)), f.decode(f.encode(f.add(f.start(), tb.slice(i, Math.min(t.length, i + 250))))));
        }
        assertThat(f.result(merged)).isEqualTo(one).isNotNull();
    }
}
