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
package com.ash.drishti.compute.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The fixed reduction order (2.9.7, RESOURCES_AND_SCALE.md section 6.7, PO-7): any scope's sum is equal to the bit whatever the threads
 * (1, 2, 3, 8, 24), for dense ranges, scattered baskets, a single row and a set spanning two blocks; the earlier order (slices by thread
 * count) was not. The micro-benchmark against the earlier order is {@link #main} (100,000 rows of 509, never run in the build).
 */
class FixedOrderSumTest {

    static VectorStore store(int rows, int width, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        VectorStore.Builder b = new VectorStore.Builder(width, 16_384, Long.MAX_VALUE);
        double[] v = new double[width];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < width; j++) {
                v[j] = r.nextGaussian() * 1e5 + (j % 7) * 0.1;
            }
            b.add(v, width);
        }
        return b.build();
    }

    static List<OrdinalSet> scopes(int rows, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        List<OrdinalSet> out = new ArrayList<>();
        out.add(OrdinalSet.range(0, rows));                            // the bank
        out.add(OrdinalSet.range(rows / 3, rows / 3 + rows / 4));      // a desk
        out.add(OrdinalSet.of(17));                                     // one trade
        out.add(OrdinalSet.of(4_095, 4_096));                           // across a block's edge
        OrdinalSet.Builder basket = new OrdinalSet.Builder();
        for (int i = 0; i < rows / 4; i++) {
            basket.add(r.nextInt(rows));                                // a scattered basket
        }
        out.add(basket.build());
        OrdinalSet.Builder minus = new OrdinalSet.Builder();
        minus.addRange(0, rows);
        out.add(OrdinalSet.range(0, rows).andNot(OrdinalSet.range(rows / 2, rows / 2 + 1_000)));   // the bank minus a book
        return out;
    }

    private static boolean same(double[] a, double[] b) {
        for (int j = 0; j < a.length; j++) {
            if (Double.doubleToRawLongBits(a[j]) != Double.doubleToRawLongBits(b[j])) {
                return false;
            }
        }
        return true;
    }

    @Test
    void everyScopeIsEqualToTheBitWhateverTheThreads() {
        int rows = 40_000;
        VectorStore s = store(rows, 32, 7);
        List<OrdinalSet> sets = scopes(rows, 11);
        List<double[]> one = new ArrayList<>();
        try (ParallelSums p = new ParallelSums(1, 4_096)) {
            sets.forEach(x -> one.add(p.sumFixed(s, x)));
        }
        boolean slicedDiffered = false;
        for (int threads : new int[] {2, 3, 8, 24}) {
            try (ParallelSums p = new ParallelSums(threads, 4_096); ParallelSums p1 = new ParallelSums(1, 4_096)) {
                for (int i = 0; i < sets.size(); i++) {
                    assertThat(same(p.sumFixed(s, sets.get(i)), one.get(i))).as(threads + " threads, scope " + i).isTrue();
                    slicedDiffered |= !same(p.sumSliced(s, sets.get(i)), p1.sumSliced(s, sets.get(i)));
                }
            }
        }
        assertThat(slicedDiffered).as("the earlier order depended on the threads").isTrue();
        double[] plain = new double[32];
        sets.get(1).forEachRun((f, t) -> s.addRangeInto(plain, f, t));
        for (int j = 0; j < 32; j++) {
            assertThat(one.get(1)[j]).isCloseTo(plain[j], org.assertj.core.data.Offset.offset(1e-3));   // the same sum, to rounding
        }
        try (ParallelSums p = new ParallelSums(4, 4_096)) {
            assertThat(p.sum(s, OrdinalSet.empty())).containsOnly(0.0);
        }
    }

    /** The micro-benchmark (PO-7's condition: no more than 5% slower): {@code main} with the test classpath; never part of the build. */
    public static void main(String[] args) {
        int rows = Integer.parseInt(args.length > 0 ? args[0] : "100000");
        int width = 509;
        VectorStore s = store(rows, width, 3);
        List<OrdinalSet> sets = scopes(rows, 5);
        String[] names = {"bank", "desk", "one trade", "block edge", "basket 25%", "bank minus a book"};
        for (int threads : new int[] {1, 8, Runtime.getRuntime().availableProcessors()}) {
            try (ParallelSums p = new ParallelSums(threads, Integer.getInteger("rows.block", 4_096))) {
                for (int i = 0; i < sets.size(); i++) {
                    OrdinalSet x = sets.get(i);
                    for (int warm = 0; warm < 30; warm++) {
                        p.sumSliced(s, x);
                        p.sumFixed(s, x);
                    }
                    int reps = 21;
                    long[][] t = new long[2][reps];
                    for (int rep = 0; rep < reps; rep++) {                  // interleaved, each order alternately first: the median of each
                        for (int o = 0; o < 2; o++) {
                            int which = (rep + o) % 2;
                            long t0 = System.nanoTime();
                            for (int k = 0; k < 10; k++) {
                                if (which == 0) {
                                    p.sumSliced(s, x);
                                } else {
                                    p.sumFixed(s, x);
                                }
                            }
                            t[which][rep] = (System.nanoTime() - t0) / 10;
                        }
                    }
                    java.util.Arrays.sort(t[0]);
                    java.util.Arrays.sort(t[1]);
                    long[] best = {t[0][reps / 2], t[1][reps / 2]};
                    System.out.printf("threads %2d  %-18s sliced %9.3f ms  fixed %9.3f ms  %+6.1f%%%n", threads, names[i], best[0] / 1e6,
                            best[1] / 1e6, 100.0 * (best[1] - best[0]) / best[0]);
                }
            }
        }
    }
}
