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

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The heap an open mapped date holds (VAR_GUIDE.md section 27.1), measured: a synthetic date built in the test, the heap after a collection
 * before and after it opens, against {@link VarBook#heapBytes()}. Every node's members are runs (not a bitmap of the date), the aggregates
 * are read from the hot file (none on the heap), and a second date adds about the same.
 *
 * <p>The size is {@code -Dvar.heap.positions} (10,000 by default: the small-test rule; 200000 for the measurement of the guide, one heavy
 * job at a time). Before 2.9.9 the same date also held, per node, a bitmap of the date's trades (trades / 8 bytes, a node's highest
 * ordinal in practice) and every aggregate on the heap (8 bytes a scenario per book, cell and node): the test prints that sum beside.
 */
class VarHeapPerDateTest {

    static final int POSITIONS = Integer.getInteger("var.heap.positions", 10_000);
    static final ParallelSums SUMS = new ParallelSums(2, 4096);

    @AfterAll
    static void close() {
        SUMS.close();
    }

    private static long heap() throws InterruptedException {
        Runtime r = Runtime.getRuntime();
        long used = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(40);
            used = Math.min(used, r.totalMemory() - r.freeMemory());
        }
        return used;
    }

    @Test
    void aMappedDateHoldsItsSkeletonOnlyAndASecondAddsTheSame(@TempDir Path dir) throws Exception {
        SyntheticBank bank = new SyntheticBank(4, 5, 50, POSITIONS, 1);     // 1,000 books, one trade a position
        Path d1 = Files.createDirectories(dir.resolve("d1"));
        Path d2 = Files.createDirectories(dir.resolve("d2"));
        long h0 = heap();
        VarBook one = bank.buildMapped(11, SUMS, Long.MAX_VALUE, d1);
        long h1 = heap();
        VarBook two = bank.buildMapped(12, SUMS, Long.MAX_VALUE, d2);
        long h2 = heap();
        try {
            long first = h1 - h0;
            long second = h2 - h1;
            int nodes = one.nodes().size();
            int width = one.width();
            long runs = one.nodes().values().stream().mapToLong(n -> n.members().heapBytes()).sum();
            long bitmaps = one.nodes().values().stream().mapToLong(n -> {
                long[] w = n.members().words();                            // the 2.9.8 form: a bitmap up to the node's highest ordinal
                return 56L + 8L * w.length;
            }).sum();
            long aggs = (long) one.morningAggregates().size() * (96 + 8L * width);
            System.out.printf("heap per date at %,d positions, %,d nodes, %,d aggregates: measured %,d bytes (second date %,d), heapBytes() %,d;"
                    + " node runs %,d bytes (as bitmaps %,d); aggregates mapped (on the heap they were %,d); before 2.9.9 about %,d bytes%n",
                    POSITIONS, nodes, one.morningAggregates().size(), first, second, one.heapBytes(), runs, bitmaps, aggs,
                    first - runs + bitmaps + aggs);
            assertThat(one.nodes().values()).allSatisfy(n -> assertThat(n.members().isRuns()).isTrue());
            assertThat(runs).as("node members as runs: a few bytes a node").isLessThan(200L * nodes);
            assertThat(one.aggregatesMapped()).isTrue();
            assertThat(one.aggregateBytes()).isZero();
            assertThat(one.heapBytes()).as("the skeleton: columns, ids, indexes, runs, names").isLessThan(2_000L * POSITIONS + 400L * nodes);
            assertThat(first).as("measured against heapBytes()").isLessThan(2 * one.heapBytes() + (8L << 20));
            assertThat(second).as("a second date adds about the same").isLessThan(first + first / 2 + (4L << 20));
            // the answers: every node's vector from the mapping equals the planner's sum of its trades, bit for bit
            for (VarBook.Node n : one.nodes().values()) {
                double[] agg = one.aggregate(n.id());
                OrdinalSet m = n.members();
                double[] again = one.vector(m, SUMS);
                assertThat(Arrays.equals(agg, again)).as(n.id()).isTrue();
            }
        } finally {
            one.close();
            two.close();
        }
    }

    @Test
    void theMappedAggregatesAreTheHeapTiersBitForBit(@TempDir Path dir) {
        SyntheticBank bank = VarHotFileTest.BANK;
        VarBook heap = bank.build(5, SUMS, Long.MAX_VALUE);
        VarBook mapped = bank.buildMapped(5, SUMS, Long.MAX_VALUE, dir);
        try {
            assertThat(heap.aggregatesMapped()).isFalse();
            assertThat(mapped.aggregatesMapped()).isTrue();
            assertThat(mapped.morningAggregates().keySet()).isEqualTo(heap.morningAggregates().keySet());
            for (String k : heap.morningAggregates().keySet()) {
                double[] a = heap.aggregate(k);
                double[] b = mapped.aggregate(k);
                for (int j = 0; j < a.length; j++) {
                    assertThat(Double.doubleToRawLongBits(b[j])).as(k + "[" + j + "]").isEqualTo(Double.doubleToRawLongBits(a[j]));
                }
            }
        } finally {
            mapped.close();
        }
    }
}
