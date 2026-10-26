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

import com.ash.drishti.compute.vector.MappedMatrix;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.compute.vector.VectorStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tiered store (VAR.md section 7.3): a date built through the spill into its hot file and mapped answers like the same date on the
 * heap. Node figures are the same to the bit (the aggregates are float64 in both); any other scope is within the float32 bound
 * {@link #bound}: each stored value is rounded once to float32 (relative error at most 2^-24), sums are float64, and VaR and ES are
 * order statistics (moving every scenario by at most E moves them by at most E). The file reopens to the same answers, its checksums
 * hold, a damaged section is refused, and closing it unmaps and deletes it.
 */
class VarHotFileTest {

    static final SyntheticBank BANK = new SyntheticBank(2, 3, 4, 600, 3);       // 24 books, 600 positions, 1,800 trades
    static ParallelSums sums;
    static VarBook heap;
    static VarBook mapped;
    static double bound;
    @TempDir
    static Path dir;

    @BeforeAll
    static void load() {
        sums = new ParallelSums(4, 64);
        heap = BANK.build(42, sums, Long.MAX_VALUE);
        mapped = BANK.buildMapped(42, sums, Long.MAX_VALUE, dir);
        bound = bound(heap);
    }

    @AfterAll
    static void close() {
        mapped.close();
        sums.close();
    }

    /** E = 2^-24 x the largest, over scenarios, of the sum of every trade's absolute P&amp;L: no scope's sum can be off by more. */
    static double bound(VarBook b) {
        double worst = 0;
        double[] row = new double[b.width()];
        double[] gross = new double[b.width()];
        for (int o = 0; o < b.tradeCount(); o++) {
            b.store().copy(o, row);
            for (int j = 0; j < row.length; j++) {
                gross[j] += Math.abs(row[j]);
            }
        }
        for (double g : gross) {
            worst = Math.max(worst, g);
        }
        return worst * Math.pow(2, -24) + 1e-6;
    }

    @Test
    void theMappedDateHasTheSameTradesPositionsNodesAndAggregates() {
        assertThat(mapped.tier()).isEqualTo("mapped");
        assertThat(heap.tier()).isEqualTo("heap");
        assertThat(mapped.tradeCount()).isEqualTo(heap.tradeCount());
        assertThat(mapped.positionCount()).isEqualTo(heap.positionCount());
        for (int o = 0; o < heap.tradeCount(); o++) {
            assertThat(mapped.tradeId(o)).isEqualTo(heap.tradeId(o));
            assertThat(mapped.positionOf(o)).isEqualTo(heap.positionOf(o));
        }
        for (String col : VarBook.POSITION_TEXTS) {
            for (int p = 0; p < heap.positionCount(); p += 37) {
                assertThat(mapped.positions().text(col, p)).as(col).isEqualTo(heap.positions().text(col, p));
            }
        }
        assertThat(mapped.nodes().keySet()).containsExactlyElementsOf(heap.nodes().keySet());
        for (VarBook.Node n : heap.nodes().values()) {
            VarBook.Node m = mapped.node(n.id());
            assertThat(m.members()).isEqualTo(n.members());
            assertThat(m.children()).isEqualTo(n.children());
            assertThat(mapped.aggregate(n.id())).as(n.id()).containsExactly(heap.aggregate(n.id()));   // float64, to the bit
        }
        assertThat(mapped.scenarioDates()).isEqualTo(heap.scenarioDates());
        assertThat(mapped.hot().meta()).isEqualTo("{\"seed\":42}");
        assertThat(mapped.vectorBytes()).isLessThan(heap.vectorBytes());          // float32 positions (+ the trades of fungible ones)
    }

    @Test
    void everyScopeIsWithinTheFloat32BoundAndNodesAreExact() {
        VarCalc calc = VarCalc.defaults();
        ScopeResolver hr = new ScopeResolver(heap, VarBookTest.defs());
        ScopeResolver mr = new ScopeResolver(mapped, VarBookTest.defs());
        double worstVar = 0;
        for (String s : List.of("BANK", "DESK:DESK-0-1", "LE:LE-1", "DESK:DESK-0-1 - TRD:T-3,T-4", "BOOK:BOOK-1-2-3 + POS:POS-5,POS-6",
                "WHERE:CURRENCY == 'EUR' and DIRECTION == 'SHORT'", "TRD:T-7,T-8,T-100", "POS:POS-1", "BANK - DESK:DESK-1-2")) {
            OrdinalSet set = hr.resolve(s).set();
            assertThat(mr.resolve(s).set()).as(s).isEqualTo(set);
            VarCalc.Measures a = calc.measures(heap.vector(set, sums));
            VarCalc.Measures b = calc.measures(mapped.vector(set, sums));
            assertThat(Math.abs(a.var() - b.var())).as("VaR of " + s).isLessThanOrEqualTo(bound);
            assertThat(Math.abs(a.es() - b.es())).as("ES of " + s).isLessThanOrEqualTo(bound);
            worstVar = Math.max(worstVar, Math.abs(a.var() - b.var()));
        }
        for (VarBook.Node n : heap.nodes().values()) {                 // a node is its aggregate: the same as the lake's
            assertThat(calc.measures(mapped.vector(n.members(), sums)).var()).isEqualTo(calc.measures(heap.vector(n.members(), sums)).var());
        }
        Random r = new Random(3);
        for (int k = 0; k < 30; k++) {
            OrdinalSet.Builder b = new OrdinalSet.Builder();
            for (int i = r.nextInt(900); i > 0; i--) {
                b.add(r.nextInt(1800));
            }
            OrdinalSet set = b.build();
            double[] hv = heap.vector(set, sums);
            double[] mv = mapped.vector(set, sums);
            for (int j = 0; j < hv.length; j++) {
                assertThat(Math.abs(hv[j] - mv[j])).isLessThanOrEqualTo(bound);
            }
        }
        System.out.printf("float32 hot copy: bound %.3f, largest VaR difference %.3f on the synthetic bank%n", bound, worstVar);
    }

    @Test
    void aTradeAndAPositionReadTheirOwnRows() {
        for (int p = 0; p < heap.positionCount(); p += 11) {
            double[] h = heap.positionVector(p);
            double[] m = mapped.positionVector(p);
            for (int j = 0; j < h.length; j++) {
                assertThat(m[j]).isEqualTo((double) (float) h[j]);          // the float32 of the float64 sum, nothing more
            }
        }
        for (int o = 0; o < heap.tradeCount(); o += 13) {
            double[] h = heap.tradeVector(o);
            double[] m = mapped.tradeVector(o);
            for (int j = 0; j < h.length; j++) {
                assertThat(m[j]).isEqualTo((double) (float) h[j]);
            }
        }
    }

    @Test
    void theFileReopensToTheSameAnswersAndItsChecksumsHold() throws IOException {
        Path file = mapped.hot().file();
        VarHotFile.verify(file);
        VarHotFile.Header h = VarHotFile.header(file);
        assertThat(h.digest()).isEqualTo("digest-42");
        assertThat(h.batchId()).isEqualTo("B-1");
        assertThat(h.trades()).isEqualTo(1800);
        assertThat(h.positions()).isEqualTo(600);
        assertThat(h.width()).isEqualTo(509);
        VarBook again = VarHotFile.open(file, 0);
        try {
            OrdinalSet set = new ScopeResolver(again, VarBookTest.defs()).resolve("DESK:DESK-0-1 - TRD:T-3,T-4").set();
            assertThat(again.vector(set, sums)).containsExactly(mapped.vector(set, sums));
            assertThat(again.positionVector(5)).containsExactly(mapped.positionVector(5));
        } finally {
            again.close();
        }
        assertThat(Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".part") || p.getFileName().toString().endsWith(".f64")))
                .as("the spill and the part file are gone").isEmpty();
    }

    @Test
    void aDamagedSectionIsRefusedAndClosingUnmapsAndDeletes(@TempDir Path own) throws IOException {
        VarBook b = BANK.buildMapped(7, sums, Long.MAX_VALUE, own);
        Path file = b.hot().file();
        Path copy = own.resolve("copy.vhot");
        Files.copy(file, copy);
        try (FileChannel ch = FileChannel.open(copy, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[] {(byte) 0xff}), 706);      // inside META, the first section (after 256 + 14 x 32)
        }
        assertThatThrownBy(() -> VarHotFile.open(copy, 0)).hasMessageContaining("checksum");
        assertThat(VarHotFile.header(own.resolve("missing.vhot"))).isNull();
        OrdinalSet all = b.all();
        b.closeAndDelete();
        assertThat(b.hot().positions().unmapped()).isTrue();
        assertThat(Files.exists(file)).isFalse();
        assertThat(b.vector(all, sums)).containsExactly(b.aggregate("MGMT~BANK"));     // the aggregates stay on the heap
        assertThatThrownBy(() -> b.positionVector(0)).isInstanceOf(MappedMatrix.Closed.class);
    }

    @Test
    void theBudgetCountsTheFloat32Copy(@TempDir Path own) {
        long rowBytes = 509L * Float.BYTES;
        assertThatThrownBy(() -> BANK.buildMapped(1, sums, rowBytes * 100, own)).isInstanceOf(VectorStore.BudgetExceeded.class)
                .hasMessageContaining("drishti.var.memory-budget-mb");
    }
}
