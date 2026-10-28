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

import com.ash.drishti.compute.vector.ParallelSums;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The cost of a hierarchy of limit letters (2.9.5, plan task B5), not run in the build (run it by name): 10,000 trades in 100 books of
 * 10 desks, 700 letters dealt over the trades (so every book is split between many letters: about 7,000 cells, the worst case), against
 * the same book without the letters. Measures the date's build and an official-capture-like pass (VaR and ES of every node).
 *
 * <pre>./mvnw -q -o -pl drishti-compute -am test -Dtest=ColumnHierarchyBenchmark -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
class ColumnHierarchyBenchmark {

    static final int W = SyntheticBank.WIDTH;

    @Test
    void sevenHundredLettersOverTenDesksAtTenThousandTrades() {
        SplittableRandom r = new SplittableRandom(3);
        int n = 10_000;
        double[][] v = new double[n][W];
        for (int t = 0; t < n; t++) {
            for (int j = 0; j < W; j++) {
                v[t][j] = Math.rint(1e4 * r.nextGaussian());
            }
        }
        HierarchySpec ll = new HierarchySpec("LL", "Limit letters", "columns", List.of("LIMIT_LETTER", "DESK"), null, null, null, false, "LL", null);
        try (ParallelSums sums = new ParallelSums(4, 4096)) {
            for (int round = 0; round < 3; round++) {
                long[] plain = run(v, List.of(), sums);
                long[] letters = run(v, List.of(ll), sums);
                System.out.printf("round %d: without letters build %d ms, nodes %d ms (%d nodes); with 700 letters build %d ms, nodes %d ms (%d nodes, %d cells)%n",
                        round, plain[0], plain[1], plain[2], letters[0], letters[1], letters[2], letters[3]);
            }
        }
    }

    private static long[] run(double[][] v, List<HierarchySpec> specs, ParallelSums sums) {
        long t0 = System.nanoTime();
        VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), 65_536, Long.MAX_VALUE);
        b.columnHierarchies(specs, Map.of());
        List<String> cols = b.textColumns();
        for (int t = 0; t < v.length; t++) {
            String[] x = new String[cols.size()];
            int book = t % 100;
            x[cols.indexOf("TT_BOOK")] = "BOOK-" + book;
            x[cols.indexOf("DESK")] = "DESK-" + book / 10;
            x[cols.indexOf("LEGAL_ENTITY")] = "LE-1";
            x[cols.indexOf("POSITION_ID")] = "POS-" + t;
            if (cols.contains("LIMIT_LETTER")) {
                x[cols.indexOf("LIMIT_LETTER")] = "LL-" + (t * 7919 % 700);
            }
            b.trade("T-" + t, v[t], W, x, new double[] {1, 1});
        }
        List<String> books = new ArrayList<>();
        for (int k = 0; k < 100; k++) {
            books.add("BOOK-" + k);
        }
        b.node("MGMT~BANK", "MGMT", "bank", "Bank", null, books, Double.NaN);
        for (int d = 0; d < 10; d++) {
            b.node("MGMT~DESK-" + d, "MGMT", "desk", "D" + d, "MGMT~BANK", books.subList(d * 10, d * 10 + 10), Double.NaN);
        }
        VarBook book = b.build(sums);
        long t1 = System.nanoTime();
        VarCalc calc = VarCalc.defaults();
        double s = 0;
        for (VarBook.Node node : book.nodes().values()) {
            s += calc.measures(book.aggregate(node.id())).var();
        }
        long t2 = System.nanoTime();
        long cells = book.nodes().values().stream().filter(x -> x.hierarchy().equals("LL")).mapToLong(x -> x.books().size()).max().orElse(0);
        return new long[] {(t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, book.nodes().size(), cells + (long) (s * 0)};
    }
}
