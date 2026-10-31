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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * {@code max-parts}'s choice (2.9.7): the heap of primitive indexes keeps exactly the parts the old full sort of boxed indexes kept
 * (the largest by gross size, ties to the first, in their order), for every size of list and of {@code max}, with ties, zeros and NaN.
 */
class VarPartsLargestTest {

    /** The 2.9.6 implementation, kept here as the reference. */
    static <T> List<T> reference(List<T> items, int max, java.util.function.ToDoubleFunction<T> size) {
        if (items.size() <= max) {
            return items;
        }
        Integer[] idx = new Integer[items.size()];
        double[] sz = new double[items.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
            sz[i] = size.applyAsDouble(items.get(i));
        }
        java.util.Arrays.sort(idx, (a, c) -> sz[a] != sz[c] ? Double.compare(sz[c], sz[a]) : Integer.compare(a, c));
        Integer[] keep = java.util.Arrays.copyOf(idx, max);
        java.util.Arrays.sort(keep);
        List<T> out = new ArrayList<>(max);
        for (Integer i : keep) {
            out.add(items.get(i));
        }
        return out;
    }

    @Test
    void keepsWhatTheFullSortKept() {
        SplittableRandom r = new SplittableRandom(7);
        for (int round = 0; round < 400; round++) {
            int n = 1 + r.nextInt(300);
            List<double[]> items = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double v = switch (r.nextInt(7)) {
                    case 0 -> 0;                                       // many ties at zero
                    case 6 -> -0.0;                                    // equal to 0.0, as the old == said
                    case 1 -> r.nextInt(5) * 1000.0;                   // ties among a few values
                    case 2 -> Double.NaN;                              // ranked first by Double.compare, as before
                    default -> Math.abs(r.nextGaussian()) * 1e6;
                };
                items.add(new double[] {v, i});
            }
            int max = r.nextInt(n + 2);
            List<double[]> want = reference(items, max, x -> x[0]);
            List<double[]> got = VarViews.largest(items, max, x -> x[0]);
            assertThat(got).as("n=%d max=%d", n, max).containsExactlyElementsOf(want);
        }
    }

    @Test
    void asksEachSizeOnce() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            items.add(i);
        }
        int[] asked = {0};
        List<Integer> got = VarViews.largest(items, 10, i -> {
            asked[0]++;
            return i % 97;
        });
        assertThat(asked[0]).isEqualTo(1000);
        assertThat(got).hasSize(10).allMatch(i -> i % 97 == 96);
    }
}
