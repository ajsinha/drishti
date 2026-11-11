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
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.functions.Holistics;
import com.ash.drishti.bicompute.harness.FunctionHarness;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The descriptive, two-variable and holistic statistics of FUNCTION_CATALOGUE.md section 4 (2.9.11.e front-loaded): each on its normal
 * case, with nulls, on nothing, and refused on a wrong argument; Excel's documented examples ({@code SKEW}, {@code KURT}, {@code CORREL},
 * {@code COVARIANCE.S}, {@code SLOPE}, {@code INTERCEPT}, {@code RSQ}, {@code TRIMMEAN}, {@code GEOMEAN}, {@code HARMEAN},
 * {@code MODE.SNGL}, {@code LARGE}, {@code SMALL}, {@code QUARTILE.INC}) to their printed digits; and every mergeable one through the
 * split-and-merge harness (splits into 2, 3 and 16 parts, five seeds, every state serialised and read back) within the configured relative
 * tolerance (config/engines/pivot.yaml, {@code harness.tolerance}: 1e-12).
 */
class StatisticsFunctionsTest {

    static final double TOLERANCE = 1e-12;
    static final double N = Double.NaN;

    static AggFunction<Object> fn(String name, double... numbers) {
        List<Double> nums = new ArrayList<>();
        for (double d : numbers) {
            nums.add(d);
        }
        return Functions.builtIn(name, List.of("x"), nums).orElseThrow();
    }

    static Object run(AggFunction<Object> f, double[]... cols) {
        Object[] c = new Object[cols.length];
        System.arraycopy(cols, 0, c, 0, cols.length);
        return f.result(f.add(f.start(), new Batch(cols[0].length, c)));
    }

    static Double num(String name, double... x) {
        return (Double) run(fn(name), x);
    }

    static Object text(AggFunction<Object> f, String... x) {
        return f.result(f.add(f.start(), new Batch(x.length, new Object[] {x})));
    }

    static final double[] SKEW = {3, 4, 5, 2, 3, 4, 5, 6, 4, 7};

    @Test
    void excelsDocumentedExamples() {
        assertThat(num("skewness", SKEW)).isCloseTo(0.359543071, within(1e-9));                              // SKEW
        assertThat(num("kurtosis", SKEW)).isCloseTo(-0.151799637, within(1e-9));                             // KURT
        assertThat((Double) run(fn("corr"), new double[] {3, 2, 4, 5, 6}, new double[] {9, 7, 12, 15, 17}))
                .isCloseTo(0.997054486, within(1e-9));                                                       // CORREL
        assertThat((Double) run(fn("covar"), new double[] {2, 4, 8}, new double[] {5, 11, 12})).isCloseTo(9.666666667, within(1e-9));
        double[] y = {2, 3, 9, 1, 8, 7, 5};
        double[] x = {6, 5, 11, 7, 5, 4, 4};
        assertThat((Double) run(fn("slope"), y, x)).isCloseTo(0.305555556, within(1e-9));                     // SLOPE(known_y, known_x)
        assertThat((Double) run(fn("rsq"), y, x)).isCloseTo(0.057950192, within(1e-9));                       // RSQ
        assertThat((Double) run(fn("intercept"), new double[] {2, 3, 9, 1, 8}, new double[] {6, 5, 11, 7, 5}))
                .isCloseTo(0.048387097, within(1e-9));                                                       // INTERCEPT
        assertThat((Double) run(fn("trimmean", 0.2), new double[] {4, 5, 6, 7, 2, 3, 4, 5, 1, 2, 3}))
                .isCloseTo(3.777777778, within(1e-9));                                                       // TRIMMEAN
        assertThat(num("geomean", 4, 5, 8, 7, 11, 4, 3)).isCloseTo(5.476986970, within(1e-9));               // GEOMEAN
        assertThat(num("harmean", 4, 5, 8, 7, 11, 4, 3)).isCloseTo(5.028375962, within(1e-9));               // HARMEAN
        assertThat(num("mode", 5.6, 4, 4, 3, 2, 4)).isEqualTo(4.0);                                          // MODE.SNGL
        assertThat((Double) run(fn("kth_largest", 3), new double[] {3, 5, 3, 5, 4, 4, 2, 4, 6, 7})).isEqualTo(5.0);   // LARGE(.., 3)
        assertThat((Double) run(fn("kth_largest", 7), new double[] {3, 5, 3, 5, 4, 4, 2, 4, 6, 7})).isEqualTo(4.0);   // LARGE(.., 7)
        assertThat((Double) run(fn("kth_smallest", 4), SKEW)).isEqualTo(4.0);                                          // SMALL(.., 4)
        assertThat((Double) run(fn("kth_smallest", 2), new double[] {1, 4, 8, 3, 7, 12, 54, 8, 23})).isEqualTo(3.0);   // SMALL(.., 2)
        assertThat(num("iqr", 1, 2, 4, 7, 8, 9, 10, 12)).isEqualTo(9.25 - 3.5);                              // QUARTILE.INC 3 − 1
        assertThat(num("stderr", SKEW)).isCloseTo(0.472581563, within(1e-9));                                // STDEV.S / SQRT(n)
        assertThat(num("cv", SKEW)).isCloseTo(0.347542818, within(1e-9));                                    // STDEV.S / AVERAGE
        assertThat(num("range", SKEW)).isEqualTo(5.0);
        assertThat(num("nmiss", 1, N, 3, N)).isEqualTo(2.0);
    }

    @Test
    void nullsAreSkippedExceptByNmiss() {
        assertThat(num("skewness", 3, N, 4, 5, 2, 3, N, 4, 5, 6, 4, 7)).isCloseTo(0.359543071, within(1e-9));
        assertThat((Double) run(fn("corr"), new double[] {3, 2, N, 4, 5, 6, 1}, new double[] {9, 7, 5, 12, 15, 17, N}))
                .isCloseTo(0.997054486, within(1e-9));                                                       // a pair with a null is left out
        assertThat(num("range", N, 2, N, 9)).isEqualTo(7.0);
        assertThat(num("geomean", N, 4, 5, 8, 7, 11, 4, 3)).isCloseTo(5.476986970, within(1e-9));
        assertThat(num("iqr", N, 1, 2, 4, 7, 8, 9, 10, 12, N)).isEqualTo(5.75);
        assertThat(text(new Holistics.StringAgg(", ", false), "EUR", null, "USD", "EUR")).isEqualTo("EUR, EUR, USD");
        assertThat(num("nmiss", N, N)).isEqualTo(2.0);
    }

    @Test
    void anEmptyCellHasNoFigureAndSaysWhy() {
        for (String name : List.of("range", "stderr", "cv", "skewness", "kurtosis", "geomean", "harmean", "iqr", "mode")) {
            assertThat(num(name)).as(name).isNull();
            assertThat(fn(name).emptyNote()).as(name).isNotBlank();
        }
        assertThat(num("nmiss")).isEqualTo(0.0);
        assertThat(run(fn("corr"), new double[0], new double[0])).isNull();
        assertThat(run(fn("trimmean", 0.1), new double[0])).isNull();
        assertThat(run(fn("kth_largest", 1), new double[0])).isNull();
        assertThat(text(new Holistics.StringAgg(", ", true))).isNull();
        // too few values or no spread: no figure, as Excel's #DIV/0!, #NUM!, #N/A
        assertThat(num("skewness", 1, 2)).isNull();
        assertThat(num("kurtosis", 1, 2, 3)).isNull();
        assertThat(num("skewness", 5, 5, 5, 5)).isNull();
        assertThat(num("cv", -1, 1)).isNull();
        assertThat(num("geomean", 4, 0, 2)).isNull();
        assertThat(num("harmean", 4, -1, 2)).isNull();
        assertThat(fn("geomean").emptyNote()).contains("≤ 0");
        assertThat(num("mode", 1, 2, 3)).isNull();                                                           // no value repeats: #N/A
        assertThat(run(fn("kth_largest", 4), new double[] {1, 2, 3})).isNull();
        assertThat(fn("kth_largest", 4).emptyNote()).contains("fewer than 4");
        assertThat(run(fn("slope"), new double[] {1, 2, 3}, new double[] {4, 4, 4})).isNull();               // x does not vary
    }

    @Test
    void aWrongArgumentIsRefusedInWords() {
        assertThatThrownBy(() -> fn("trimmean")).hasMessageContaining("trimmean leaves out a fraction");
        assertThatThrownBy(() -> fn("trimmean", 1.0)).hasMessageContaining("not 1.0");
        assertThatThrownBy(() -> fn("kth_largest", 1.5)).hasMessageContaining("a whole k");
        assertThatThrownBy(() -> fn("kth_smallest", 0)).hasMessageContaining("a whole k");
        assertThatThrownBy(() -> Functions.builtIn("sum", List.of("x"), List.of(), List.of(", "))).hasMessageContaining("no text argument");
        assertThat(fn("corr").inputs()).isEqualTo(2);
        assertThat(fn("skewness").inputs()).isEqualTo(1);
        // text where a number is meant is not a number: skipped, never read as 0
        AggFunction<Object> sk = fn("range");
        assertThat(sk.finish(sk.add(sk.start(), new Batch(3, new Object[] {new String[] {"x", "7", "2"}})))).isEqualTo(5.0);
    }

    @Test
    void modeAndStringAggReadNamesAndGiveText() {
        assertThat(text(new Holistics.Mode(), "USD", "EUR", "USD", "GBP", "EUR")).isEqualTo("EUR");          // a tie goes to the smallest
        assertThat(text(new Holistics.Mode(), "4", "4.0", "5")).isEqualTo(4.0);                             // all numbers: a number
        assertThat(new Holistics.Mode().finish(new Holistics.Mode().add(new Holistics.Mode().start(),
                new Batch(2, new Object[] {new String[] {"USD", "USD"}})))).isNull();                            // no number to finish
        assertThat(text(new Holistics.StringAgg(" | ", true), "USD", "EUR", "USD", "AUD")).isEqualTo("AUD | EUR | USD");
        assertThat(text(new Holistics.StringAgg(", ", false), "242000000.0", "1.6E7")).isEqualTo("16000000, 242000000");
        String[] many = new String[Holistics.STRING_AGG_MAX + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = "CP-" + i;
        }
        assertThat(text(new Holistics.StringAgg(", ", true), many)).isNull();                               // bounded: refused, never cut
        assertThat(new Holistics.StringAgg(", ", true).emptyNote()).contains("at most 1000");
        AggFunction<Object> s = Functions.builtIn("string_agg", List.of("x"), List.of(1.0), List.of("; ")).orElseThrow();
        assertThat(text(s, "B", "A", "B")).isEqualTo("A; B");
    }

    static Batch rows(int n, long seed, boolean positive) {
        Random r = new Random(seed);
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double g = r.nextGaussian();
            x[i] = i % 113 == 0 ? Double.NaN : positive ? Math.exp(g) * 1e4 : g * 1e4 + (i % 7 == 0 ? 1e6 : 0);
            // an intercept of about 5e4: the harness's tolerance is relative to the figure, and an intercept near 0 beside a mean of y
            // of 4e4 is ill-conditioned (ȳ − b·x̄ cancels), whatever the order of the merges
            y[i] = i % 89 == 0 ? Double.NaN : 5e4 + 0.3 * (Double.isNaN(x[i]) ? 0 : x[i]) + r.nextGaussian() * 5e3;
        }
        return new Batch(n, new Object[] {x, y});
    }

    @Test
    void everyMergeableStatisticPassesTheSplitAndMergeHarness() {
        for (String name : List.of("nmiss", "range", "stderr", "cv", "skewness", "kurtosis", "geomean", "harmean", "corr", "covar", "slope",
                "intercept", "rsq")) {
            AggFunction<Object> f = fn(name);
            assertThat(f.kind()).as(name).isEqualTo(Kind.MERGEABLE);
            Batch b = rows(10_000, 11, name.endsWith("mean"));
            Batch in = f.inputs() == 2 ? b : new Batch(b.rows(), new Object[] {b.columns()[0]});
            FunctionHarness.Report rep = FunctionHarness.run(f, in, 5, TOLERANCE);
            assertThat(rep.passed()).as(name + ": worst " + rep.worst()).isTrue();
            assertThat(rep.onePass()).as(name).isNotNull();
            assertThat(rep.runs().stream().map(FunctionHarness.Run::how)).anyMatch(h -> h.startsWith("2 parts"))
                    .anyMatch(h -> h.startsWith("3 parts")).anyMatch(h -> h.startsWith("16 parts"));
        }
    }

    @Test
    void theHolisticOnesMergeTheirValuesExactly() {
        Batch b = rows(5_000, 5, false);
        Batch x = new Batch(b.rows(), new Object[] {b.columns()[0]});
        for (AggFunction<Object> f : List.of(fn("iqr"), fn("trimmean", 0.1), fn("kth_largest", 7), fn("kth_smallest", 3))) {
            assertThat(f.kind()).isEqualTo(Kind.HOLISTIC);
            FunctionHarness.Report rep = FunctionHarness.run(f, x, 2, 0);
            assertThat(rep.passed()).as(f.name() + ": worst " + rep.worst()).isTrue();
        }
        String[] t = new String[4_000];
        Random r = new Random(3);
        for (int i = 0; i < t.length; i++) {
            t[i] = i % 50 == 0 ? null : "CCY-" + r.nextInt(30);
        }
        Batch tb = new Batch(t.length, new Object[] {t});
        AggFunction<Object> mode = new Holistics.Mode();
        AggFunction<Object> agg = new Holistics.StringAgg(",", true);
        for (AggFunction<Object> f : List.of(mode, agg)) {
            Object one = f.result(f.add(f.start(), tb));
            Object merged = f.start();
            for (int i = 0; i < t.length; i += 333) {
                merged = f.merge(f.decode(f.encode(merged)), f.decode(f.encode(f.add(f.start(), tb.slice(i, Math.min(t.length, i + 333))))));
            }
            assertThat(f.result(merged)).as(f.name()).isEqualTo(one).isNotNull();
        }
    }

    @Test
    void theMomentsStayRightFarFromZero() {
        // values around 1e9 with a spread of 1: the two-pass batches and Pébay's merge keep the shape (a naive power sum loses it)
        double[] x = new double[SKEW.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = 1e9 + SKEW[i];
        }
        assertThat(num("skewness", x)).isCloseTo(0.359543071, within(1e-6));
        assertThat(num("kurtosis", x)).isCloseTo(-0.151799637, within(1e-6));
    }
}
