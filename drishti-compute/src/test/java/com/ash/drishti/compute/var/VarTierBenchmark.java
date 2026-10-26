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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The tiered store at bank scale (VAR_GUIDE.md sections 26 and 27.3): {@code T} synthetic trades over {@code D}
 * business dates, each date built through its spill into a float32 hot file and mapped, as the server does. Not run by default; it
 * writes {@code D x T x 509 x 4} bytes of hot files (plus a float64 spill of one date at a time) under {@code drishti.var.tier.dir}
 * and deletes them at the end.
 *
 * <pre>
 *   a workstation (measured this way):  ./mvnw -o test -pl drishti-compute -Dtest=VarTierBenchmark
 *       -Ddrishti.var.tier.trades=100000 -Ddrishti.var.tier.dates=5 -DargLine=-Xmx4g
 *   the full run, on a server with about 110 GB of local disk:  ./mvnw -o test -pl drishti-compute -Dtest=VarTierBenchmark
 *       -Ddrishti.var.tier.trades=1000000 -Ddrishti.var.tier.dates=50 -Ddrishti.var.tier.dir=/local/var-bench -DargLine=-Xmx16g
 *   (optional) -Ddrishti.var.tier.trades-per-position=1 -Ddrishti.var.tier.threads=24 -Ddrishti.var.tier.desks=100
 *       -Ddrishti.var.tier.open=2 (dates kept mapped at once, as drishti.var.hot-dates)
 * </pre>
 *
 * Prints one line per measure: each date's first load (generate, spill, sort, aggregates, hot file), reopening a history date (map
 * only), a node from its aggregate, the bank minus a desk, the whole bank from its rows, a basket of scattered positions, a drill to
 * one position's trades, the heap the open dates hold, and the disk.
 */
@EnabledIfSystemProperty(named = "drishti.var.tier.trades", matches = "\\d+")
class VarTierBenchmark {

    private static double ms(long t0) {
        return (System.nanoTime() - t0) / 1e6;
    }

    private static long heapUsed() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    @Test
    void tieredStoreAtScale() throws IOException {
        int trades = Integer.getInteger("drishti.var.tier.trades");
        int dates = Integer.getInteger("drishti.var.tier.dates", 5);
        int perPosition = Integer.getInteger("drishti.var.tier.trades-per-position", 1);
        int threads = Integer.getInteger("drishti.var.tier.threads", Runtime.getRuntime().availableProcessors());
        int desks = Integer.getInteger("drishti.var.tier.desks", 100);
        int openAtOnce = Integer.getInteger("drishti.var.tier.open", 2);
        Path dir = Path.of(System.getProperty("drishti.var.tier.dir", "target/var-tier-benchmark")).toAbsolutePath();
        Files.createDirectories(dir);
        int positions = Math.max(1, trades / perPosition);
        int les = 4;
        SyntheticBank bank = new SyntheticBank(les, Math.max(1, desks / les), 10, positions, perPosition);
        System.out.printf("%nVaR tiered store benchmark: %,d trades in %,d positions x %d dates, %d threads, %d books; hot files in %s%n",
                positions * perPosition, positions, dates, threads, bank.books(), dir);
        long heap0 = heapUsed();
        List<VarBook> open = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (ParallelSums sums = new ParallelSums(threads, 4096)) {
            double buildTotal = 0;
            for (int d = 0; d < dates; d++) {
                LocalDate day = LocalDate.of(2026, 9, 30).minusDays(d);
                VarBook.HotTarget t = SyntheticBank.target(dir, day + ".vhot", "bench-" + d);
                long t0 = System.nanoTime();
                VarBook.Builder b = new VarBook.Builder(day, "HS-509", SyntheticBank.dates(), Long.MAX_VALUE, t);
                bank.fill(b, 100 + d);
                double fill = ms(t0);
                t0 = System.nanoTime();
                VarBook book = b.build(sums);
                double build = ms(t0);
                buildTotal += fill + build;
                files.add(book.hot().file());
                System.out.printf("  date %s: generate + spill %,.0f ms, sort + aggregates + hot file %,.0f ms; hot file %,.1f MB%n", day, fill, build,
                        Files.size(book.hot().file()) / 1e6);
                open.add(book);
                while (open.size() > openAtOnce) {
                    open.remove(0).close();                              // evicted: unmapped, the file stays
                }
            }
            long heapOpen = heapUsed() - heap0;
            VarBook today = open.get(open.size() - 1).businessDate().isAfter(open.get(0).businessDate()) ? open.get(open.size() - 1) : open.get(0);
            today = open.stream().max(Comparator.comparing(VarBook::businessDate)).orElse(today);

            // reopening a history date: map its file, no lake (the oldest date, evicted above)
            Path oldest = files.get(files.size() - 1);
            double reopen = Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                VarBook h = VarHotFile.open(oldest, 0);
                reopen = Math.min(reopen, ms(t0));
                h.close();
            }
            VarBook history = VarHotFile.open(oldest, 0);
            long t0 = System.nanoTime();
            double[] hv = history.vector(history.node("MGMT~BANK").members(), sums);
            double firstNode = ms(t0);
            assertThat(hv).hasSize(509);

            VarBook book = today;
            VarCalc calc = VarCalc.defaults();
            double node = best(() -> calc.measures(book.vector(book.node("MGMT~BANK").members(), sums)));
            String desk = "MGMT~" + SyntheticBank.desk(0, 0);
            OrdinalSet minusDesk = book.all().andNot(book.node(desk).members());
            double bankMinusDesk = best(() -> calc.measures(book.vector(minusDesk, sums)));
            double[] whole = new double[1];
            double leaves = best(() -> whole[0] = sums.sum(book.store(), book.all())[0]);
            double gb = (double) book.hot().positions().bytes() / 1e9;          // the whole bank reads the position rows only
            ScopeResolver res = new ScopeResolver(book, VarBookTest.defs());
            Random r = new Random(5);
            List<String> ids = new ArrayList<>();
            int basket = Math.min(50_000, positions / 2);
            for (int i = 0; i < basket; i++) {
                ids.add("POS-" + r.nextInt(positions));
            }
            String scope = "POS:" + String.join(",", ids);
            OrdinalSet basketSet = res.resolve(scope).set();
            double basketMs = best(() -> calc.measures(book.vector(basketSet, sums)));
            int p = book.positionIndex("POS-" + (positions / 3));
            double drill = best(() -> {
                for (int o = book.positionFrom(p); o < book.positionTo(p); o++) {
                    calc.measures(book.tradeVector(o));
                }
            });
            long disk = 0;
            for (Path f : files) {
                disk += Files.size(f);
            }
            System.out.printf("  reopen a history date (map its file): %,.1f ms; its bank's first answer %,.2f ms%n", reopen, firstNode);
            System.out.printf("  a node from its aggregate (VaR, ES): %,.3f ms; the bank minus a desk: %,.2f ms%n", node, bankMinusDesk);
            System.out.printf("  the whole bank from its float32 rows: %,.1f ms (%.1f GB/s of float32)%n", leaves, gb / (leaves / 1e3));
            System.out.printf("  a basket of %,d scattered positions (sum and measures): %,.1f ms%n", basket, basketMs);
            System.out.printf("  a drill to one position's %d trade(s) (their vectors and VaR): %,.3f ms%n", book.positionTo(p) - book.positionFrom(p), drill);
            System.out.printf("  heap held by %d open dates: %,.0f MB (aggregates %,.1f MB a date; columns, ids, links the rest); "
                    + "first loads %,.1f s in all%n", open.size(), heapOpen / 1e6, book.aggregateBytes() / 1e6, buildTotal / 1e3);
            System.out.printf("  disk: %,.2f GB of hot files for %d dates (%,.1f MB a date; float64 in the lake: %,.1f MB a date)%n", disk / 1e9, dates,
                    disk / 1e6 / dates, positions * (double) perPosition * 509 * 8 / 1e6);
            history.close();
        } finally {
            open.forEach(VarBook::close);
            try (Stream<Path> s = Files.list(dir)) {
                for (Path f : s.toList()) {
                    Files.deleteIfExists(f);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    private static double best(Runnable r) {
        r.run();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            r.run();
            best = Math.min(best, ms(t0));
        }
        return best;
    }
}
