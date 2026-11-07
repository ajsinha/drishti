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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link VarBook#vectors}: many scopes at once (a cube's cells, ad-hoc sets, whole books and desks mixed) give, scope by scope, the very
 * bits {@link VarBook#vector} gives one at a time, and hence the same VaR, ES and VaR scenario. The process's BLAS binding is Java here
 * unless the machine loads the bundled OpenBLAS; on OpenBLAS the batched sets are within a relative 1e-12 (and the scenario the same).
 */
class ManyScopeVectorsTest {

    static final int W = 509;
    static final int TRADES = 12_000;

    @Test
    void manyScopesGiveTheVectorsOfOneAtATime() {
        Random r = new Random(9);
        try (ParallelSums sums = new ParallelSums(4, 256)) {
            VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), 65_536, Long.MAX_VALUE);
            b.columnHierarchies(List.of(), Map.of());
            List<String> cols = b.textColumns();
            for (int t = 0; t < TRADES; t++) {
                double[] v = new double[W];
                for (int j = 0; j < W; j++) {
                    v[j] = r.nextGaussian() * Math.pow(10, 3 + r.nextInt(4));
                }
                String[] x = new String[cols.size()];
                int book = t % 60;
                x[cols.indexOf("TT_BOOK")] = "BOOK-" + book;
                x[cols.indexOf("DESK")] = "DESK-" + book / 10;
                x[cols.indexOf("LEGAL_ENTITY")] = "LE-1";
                x[cols.indexOf("POSITION_ID")] = "POS-" + t;
                b.trade("T-" + t, v, W, x, new double[] {1, 1});
            }
            List<String> books = new ArrayList<>();
            for (int k = 0; k < 60; k++) {
                books.add("BOOK-" + k);
            }
            b.node("MGMT~BANK", "MGMT", "bank", "Bank", null, books, Double.NaN);
            for (int d = 0; d < 6; d++) {
                b.node("MGMT~DESK-" + d, "MGMT", "desk", "D" + d, "MGMT~BANK", books.subList(d * 10, d * 10 + 10), Double.NaN);
            }
            VarBook book = b.build(sums);
            List<OrdinalSet> sets = new ArrayList<>();
            OrdinalSet all = book.all();
            sets.add(all);                                                   // whole: the aggregate
            for (int c = 0; c < 40; c++) {                                   // cube cells: scattered, no whole book
                OrdinalSet.Builder cell = new OrdinalSet.Builder();
                for (int t = 0; t < TRADES; t++) {
                    if (r.nextInt(40) == c) {
                        cell.add(t);
                    }
                }
                sets.add(cell.build());
            }
            for (int a = 0; a < 6; a++) {                                    // ad-hoc baskets, dense and overlapping
                OrdinalSet.Builder s = new OrdinalSet.Builder();
                for (int t = 0; t < TRADES; t++) {
                    if (r.nextInt(3) == 0) {
                        s.add(t);
                    }
                }
                sets.add(s.build());
            }
            sets.add(OrdinalSet.empty());
            sets.add(OrdinalSet.of(5, 77, 9_000));
            List<double[]> many = book.vectors(sets, sums);
            boolean exact = !com.ash.drishti.compute.linalg.Blas.get().isNative();
            for (int i = 0; i < sets.size(); i++) {
                double[] one = book.vector(sets.get(i), sums);
                if (exact) {
                    assertThat(many.get(i)).as("set " + i).containsExactly(one);
                } else {
                    double scale = 0;
                    for (double v : one) {
                        scale = Math.max(scale, Math.abs(v));
                    }
                    for (int j = 0; j < W; j++) {
                        assertThat(Math.abs(many.get(i)[j] - one[j])).isLessThanOrEqualTo(1e-12 * scale);
                    }
                }
                assertThat(VarMath.order(many.get(i))[VarMath.rank(W, 0.99) - 1]).as("the VaR scenario of set " + i)
                        .isEqualTo(VarMath.order(one)[VarMath.rank(W, 0.99) - 1]);
                assertThat(VarMath.var(many.get(i), 0.99)).isCloseTo(VarMath.var(one, 0.99), org.assertj.core.data.Offset.offset(1e-6));
            }
        }
    }
}
