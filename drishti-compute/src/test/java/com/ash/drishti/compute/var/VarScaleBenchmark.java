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

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The engine at bank scale, in memory only (VAR_GUIDE.md sections 27.2 and 27.4): a synthetic bank of a size you
 * choose, never written anywhere, and the operations the design sets targets for. Not run by default: it needs memory (rows x 509 x 8
 * bytes, twice while the rows are put in hierarchy order).
 *
 * <pre>
 *   ./mvnw -o test -pl drishti-compute -Dtest=VarScaleBenchmark -Ddrishti.var.scale.positions=400000 -DargLine=-Xmx8g
 *   (optional) -Ddrishti.var.scale.trades-per-position=1 -Ddrishti.var.scale.threads=8 -Ddrishti.var.scale.desks=100
 * </pre>
 *
 * Prints one line per operation: the build (sort into hierarchy order, permute, sum every book and node), the whole bank from leaves
 * (memory bandwidth) and from the cached aggregate, an ad-hoc basket of 50,000 scattered positions (resolve, sum, measures), and the
 * component VaR of a desk over its positions.
 */
@EnabledIfSystemProperty(named = "drishti.var.scale.positions", matches = "\\d+")
class VarScaleBenchmark {

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Test
    void bankScale() {
        int positions = Integer.getInteger("drishti.var.scale.positions");
        int perPosition = Integer.getInteger("drishti.var.scale.trades-per-position", 1);
        int threads = Integer.getInteger("drishti.var.scale.threads", Runtime.getRuntime().availableProcessors());
        int desks = Integer.getInteger("drishti.var.scale.desks", 100);
        int les = 4;
        SyntheticBank bank = new SyntheticBank(les, Math.max(1, desks / les), 10, positions, perPosition);
        int rows = positions * perPosition;
        double gb = rows * 509.0 * 8 / 1e9;
        System.out.printf("%nVaR scale benchmark: %,d positions, %,d trades (rows), %.2f GB of vectors, %d threads, %d desks, %d books%n",
                positions, rows, gb, threads, les * Math.max(1, desks / les), bank.books());
        try (ParallelSums sums = new ParallelSums(threads, 4096)) {
            long t0 = System.nanoTime();
            VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), 65_536, Long.MAX_VALUE);
            bank.fill(b, 1);
            long fill = ms(t0);
            t0 = System.nanoTime();
            VarBook book = b.build(sums);
            long build = ms(t0);
            System.out.printf("  load: generate %,d ms (synthetic, not the lake), sort + permute + aggregates %,d ms%n", fill, build);

            double[] warm = sums.sum(book.store(), book.all());
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                t0 = System.nanoTime();
                warm = sums.sum(book.store(), book.all());
                best = Math.min(best, System.nanoTime() - t0);
            }
            System.out.printf("  whole bank from leaves: %,.1f ms (%.1f GB/s); from the cached aggregate: %.3f ms%n", best / 1e6, gb / (best / 1e9),
                    timeNs(() -> book.vector(book.node("MGMT~BANK").members(), sums)) / 1e6);
            assertThat(warm).hasSize(509);

            VarCalc calc = VarCalc.defaults();
            ScopeResolver res = new ScopeResolver(book, VarBookTest.defs());
            Random r = new Random(5);
            List<String> ids = new ArrayList<>();
            int basket = Math.min(50_000, positions / 2);
            for (int i = 0; i < basket; i++) {
                ids.add("POS-" + r.nextInt(positions));
            }
            String scope = "POS:" + String.join(",", ids);
            res.resolve(scope);                                                // warm the parser and the maps
            long adhoc = Long.MAX_VALUE;
            long resolveBest = Long.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                t0 = System.nanoTime();
                ScopeResolver.Resolution rr = res.resolve(scope);
                long tr = System.nanoTime() - t0;
                double[] v = book.vector(rr.set(), sums);
                calc.measures(v);
                adhoc = Math.min(adhoc, System.nanoTime() - t0);
                resolveBest = Math.min(resolveBest, tr);
            }
            System.out.printf("  ad-hoc basket of %,d scattered positions: %,.1f ms (resolve %,.1f ms, then sum and measures)%n", basket, adhoc / 1e6,
                    resolveBest / 1e6);

            String desk = "MGMT~" + SyntheticBank.desk(0, 0);
            double[] dv = book.aggregate(desk);
            VarCalc.Measures m = calc.measures(dv);
            List<VarCalc.Part> parts = new ArrayList<>();
            int[] last = {-1};
            book.node(desk).members().forEachRun((f, t) -> {
                for (int o = f; o < t; o++) {
                    int p = book.positionOf(o);
                    if (p != last[0]) {
                        last[0] = p;
                        parts.add(new VarCalc.Part(book.positionId(p), "", "position", book.positionVector(p), Double.NaN));
                    }
                }
            });
            t0 = System.nanoTime();
            List<VarCalc.Contribution> cs = calc.contributions(dv, m, parts);
            long comp = ms(t0);
            double total = cs.stream().mapToDouble(VarCalc.Contribution::componentVar).sum();
            System.out.printf("  component VaR of a desk over its %,d positions: %,d ms (components add up to %.0f, VaR %.0f)%n", parts.size(), comp,
                    total, m.var());
            OrdinalSet desksBank = book.all().andNot(book.node(desk).members());
            t0 = System.nanoTime();
            book.vector(desksBank, sums);
            System.out.printf("  bank minus one desk (planner: aggregates): %,.2f ms%n", (System.nanoTime() - t0) / 1e6);
        }
    }

    private static long timeNs(Runnable r) {
        r.run();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            r.run();
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best;
    }
}
