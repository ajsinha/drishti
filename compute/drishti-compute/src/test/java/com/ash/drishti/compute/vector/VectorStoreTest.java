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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The vector store against brute force: rows across chunk borders, ranges, the parallel sum equal to the serial one to the bit (whole
 * numbers), set algebra against java.util sets, a permutation into hierarchy order, and the memory budget.
 */
class VectorStoreTest {

    static final int W = 509;

    private static VectorStore store(int rows, int perChunk, Random r, double[][] keep) {
        VectorStore.Builder b = new VectorStore.Builder(W, perChunk, Long.MAX_VALUE);
        double[] v = new double[W];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < W; j++) {
                v[j] = Math.round(r.nextGaussian() * 10_000);
            }
            keep[i] = v.clone();
            assertThat(b.add(v, W)).isEqualTo(i);
        }
        return b.build();
    }

    @Test
    void sumsEqualBruteForceAcrossChunksSeriallyAndInParallel() {
        Random r = new Random(5);
        int rows = 1_000;
        double[][] keep = new double[rows][];
        VectorStore s = store(rows, 64, r, keep);                       // 16 chunks, the last one short
        assertThat(s.chunkCount()).isEqualTo(16);
        OrdinalSet.Builder ob = new OrdinalSet.Builder();
        BitSet brute = new BitSet();
        for (int i = 0; i < rows; i++) {
            if (r.nextInt(3) == 0) {
                ob.add(i);
                brute.set(i);
            }
        }
        ob.addRange(100, 300);
        brute.set(100, 300);
        OrdinalSet set = ob.build();
        double[] want = new double[W];
        for (int i = brute.nextSetBit(0); i >= 0; i = brute.nextSetBit(i + 1)) {
            for (int j = 0; j < W; j++) {
                want[j] += keep[i][j];
            }
        }
        try (ParallelSums serial = new ParallelSums(1, 1); ParallelSums par = new ParallelSums(4, 10)) {
            assertThat(serial.sum(s, set)).containsExactly(want);
            assertThat(par.sum(s, set)).containsExactly(want);
            double[] range = new double[W];
            for (int i = 37; i < 777; i++) {
                for (int j = 0; j < W; j++) {
                    range[j] += keep[i][j];
                }
            }
            assertThat(par.sumRange(s, 37, 777)).containsExactly(range);
            double[][] parts = par.sums(s, List.of(OrdinalSet.range(0, 10), OrdinalSet.of(999), OrdinalSet.empty()));
            assertThat(parts[1]).containsExactly(keep[999]);
            assertThat(parts[2]).containsOnly(0.0);
        }
        assertThat(s.value(65, 3)).isEqualTo(keep[65][3]);
        assertThat(s.sumColumn(10, 20, 7)).isEqualTo(sumColumn(keep, 10, 20, 7));
    }

    private static double sumColumn(double[][] keep, int from, int to, int j) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += keep[i][j];
        }
        return s;
    }

    @Test
    void setAlgebraIsExactAndOverlapsCountOnce() {
        OrdinalSet desk = OrdinalSet.range(100, 200);
        OrdinalSet basket = OrdinalSet.of(150, 151, 500, 501);
        OrdinalSet union = desk.or(basket);
        assertThat(union.size()).isEqualTo(100 + 2);                       // 150 and 151 once
        assertThat(desk.andNot(OrdinalSet.of(150, 10)).size()).isEqualTo(99);
        assertThat(desk.and(basket).size()).isEqualTo(2);
        assertThat(union.containsAll(basket)).isTrue();
        assertThat(desk.coversRange(100, 200)).isTrue();
        assertThat(desk.andNot(OrdinalSet.of(150)).coversRange(100, 200)).isFalse();
        List<int[]> runs = new ArrayList<>();
        union.forEachRun((f, t) -> runs.add(new int[] {f, t}));
        assertThat(runs).hasSize(2);
        assertThat(runs.get(1)).containsExactly(500, 502);
        assertThat(union.countIn(140, 160)).isEqualTo(20);
        assertThat(union.digest()).isEqualTo(basket.or(desk).digest());
    }

    @Test
    void aPermutationPutsRowsInHierarchyOrder() {
        Random r = new Random(9);
        double[][] keep = new double[10][];
        VectorStore.Builder b = new VectorStore.Builder(W, 4, Long.MAX_VALUE);
        double[] v = new double[W];
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < W; j++) {
                v[j] = r.nextInt(1000);
            }
            keep[i] = v.clone();
            b.add(v, W);
        }
        int[] order = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
        VectorStore s = b.permuted(order);
        double[] row = new double[W];
        s.copy(0, row);
        assertThat(row).containsExactly(keep[9]);
        s.copy(9, row);
        assertThat(row).containsExactly(keep[0]);
    }

    @Test
    void theMemoryBudgetIsKept() {
        VectorStore.Builder b = new VectorStore.Builder(W, 16, 3L * W * Double.BYTES);
        double[] v = new double[W];
        b.add(v, W);
        b.add(v, W);
        b.add(v, W);
        assertThatThrownBy(() -> b.add(v, W)).isInstanceOf(VectorStore.BudgetExceeded.class).hasMessageContaining("memory-budget-mb");
        VectorStore s = new VectorStore.Builder(W, 16, Long.MAX_VALUE).build();
        assertThat(s.rows()).isZero();
        VectorStore.Builder shortRow = new VectorStore.Builder(W, 16, Long.MAX_VALUE);
        shortRow.add(new double[] {1, 2}, 2);                              // a short vector is padded with NaN, never zero
        assertThat(Double.isNaN(shortRow.build().value(0, 5))).isTrue();
    }
}
