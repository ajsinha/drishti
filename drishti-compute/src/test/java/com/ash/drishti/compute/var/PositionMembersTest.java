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
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The position walks (2.9.7, VAR_GUIDE.md section 27a): {@code forEachPositionMember}, {@code forEachPositionRun},
 * {@code forEachPositionMemberIn}, {@code positionWithin} and the counts give, member for member and in the same order, what the old
 * path read from {@code positionMembers(p)} (a set as wide as the date) and its {@code and}, {@code containsAll} and {@code size}; on a
 * morning book and on one with an intraday layer (amended, added to an existing position, new, cancelled). And their cost does not grow
 * with the date's width: walking every position of a wide date allocates nothing like positions x trades / 8 bytes.
 */
class PositionMembersTest {

    static final int W = 16;
    static ParallelSums sums;

    @BeforeAll
    static void open() {
        sums = new ParallelSums(2, 64);
    }

    @AfterAll
    static void close() {
        sums.close();
    }

    static String[] texts(String pos, String book) {
        String[] x = new String[VarBook.TRADE_TEXTS.size()];
        x[VarBook.TRADE_TEXTS.indexOf("POSITION_ID")] = pos;
        x[VarBook.TRADE_TEXTS.indexOf("LEGAL_ENTITY")] = "LE-1";
        x[VarBook.TRADE_TEXTS.indexOf("DESK")] = "DESK-0";
        x[VarBook.TRADE_TEXTS.indexOf("TT_BOOK")] = book;
        x[VarBook.TRADE_TEXTS.indexOf("CURRENCY")] = "USD";
        x[VarBook.TRADE_TEXTS.indexOf("PRODUCT_TYPE")] = "PT";
        return x;
    }

    static double[] pnl(SplittableRandom r) {
        double[] v = new double[W];
        for (int j = 0; j < W; j++) {
            v[j] = Math.rint(1e4 * r.nextGaussian());
        }
        return v;
    }

    static List<LocalDate> dates() {
        List<LocalDate> out = new ArrayList<>();
        for (int j = 0; j < W; j++) {
            out.add(LocalDate.of(2026, 1, 1).plusDays(j));
        }
        return out;
    }

    /** {@code trades} trades in 4 books, every {@code fungible}-th position holding up to 5 trades. */
    static VarBook book(int trades, int fungible, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-16", dates(), 65_536, Long.MAX_VALUE);
        for (int t = 0; t < trades; t++) {
            String book = "BOOK-" + (t % 4);
            String pos = t % fungible == 0 ? "POS-" + book + "~SEC-" + (t % 5) : "POS-" + book + "~T-" + t;
            b.trade("T-" + t, pnl(r), W, texts(pos, book), new double[] {1 + t % 3, 1000 + t});
        }
        List<String> all = List.of("BOOK-0", "BOOK-1", "BOOK-2", "BOOK-3");
        b.node("MGMT~BANK", "MGMT", "bank", "Bank", null, all, Double.NaN);
        for (String k : all) {
            b.node("MGMT~" + k, "MGMT", "book", k, "MGMT~BANK", List.of(k), Double.NaN);
        }
        return b.build(sums);
    }

    static VarBook withLayer(VarBook morning) {
        SplittableRandom r = new SplittableRandom(5);
        List<VarIntraday.Update> u = new ArrayList<>();
        u.add(new VarIntraday.Update("T-3", pnl(r), 7, texts(morning.trades().text("POSITION_ID", morning.tradeOrdinal("T-3")), "BOOK-3"),
                new double[] {2, 7}, 7, null));                                                      // amended
        u.add(new VarIntraday.Update("T-9", null, 0, texts("POS-BOOK-1~T-9", "BOOK-1"), new double[] {0, 0}, 0, null)); // cancelled
        String fungible = morning.trades().text("POSITION_ID", morning.tradeOrdinal("T-0"));
        u.add(new VarIntraday.Update("N-1", pnl(r), 11, texts(fungible, "BOOK-0"), new double[] {3, 11}, 11, null));   // joins a position
        u.add(new VarIntraday.Update("N-2", pnl(r), 13, texts("POS-BOOK-2~N-2", "BOOK-2"), new double[] {1, 13}, 13, null)); // new
        u.add(new VarIntraday.Update("T-10", null, 0, texts(fungible, "BOOK-0"), new double[] {0, 0}, 0, null));  // a fungible member out
        return VarIntraday.apply(morning, u, o -> morning.tradeVector(o).clone(), c -> null, List.of("B-1"), "2026-09-30T10:00:00Z");
    }

    /** Scopes to ask the walks against: every book, the bank, a sparse random set, a set without whole positions, the empty set. */
    static List<OrdinalSet> scopes(VarBook b) {
        List<OrdinalSet> out = new ArrayList<>();
        b.nodes().values().forEach(n -> out.add(n.members()));
        SplittableRandom r = new SplittableRandom(3);
        OrdinalSet.Builder sparse = new OrdinalSet.Builder();
        OrdinalSet.Builder halves = new OrdinalSet.Builder();
        int n = b.tradeCount() + (b.intraday() == null ? 0 : b.intraday().added());
        for (int o = 0; o < n; o++) {
            if (r.nextInt(3) == 0) {
                sparse.add(o);
            }
            if (o % 2 == 0) {
                halves.add(o);
            }
        }
        out.add(sparse.build());
        out.add(halves.build());
        out.add(OrdinalSet.empty());
        return out;
    }

    static void sameAsTheWidePath(VarBook b) {
        for (int p = 0; p < b.positionCount(); p++) {
            OrdinalSet wide = b.positionMembers(p);
            List<Integer> want = new ArrayList<>();
            wide.forEach(want::add);
            List<Integer> got = new ArrayList<>();
            b.forEachPositionMember(p, got::add);
            assertThat(got).as("members of " + b.positionId(p)).isEqualTo(want);
            assertThat(b.positionMemberCount(p)).isEqualTo(wide.size());
            List<int[]> wantRuns = new ArrayList<>();
            wide.forEachRun((f, t) -> wantRuns.add(new int[] {f, t}));
            List<int[]> gotRuns = new ArrayList<>();
            b.forEachPositionRun(p, (f, t) -> gotRuns.add(new int[] {f, t}));
            assertThat(gotRuns).as("runs of " + b.positionId(p)).usingRecursiveComparison().isEqualTo(wantRuns);
            for (OrdinalSet s : scopes(b)) {
                List<Integer> wantIn = new ArrayList<>();
                wide.and(s).forEach(wantIn::add);
                List<Integer> gotIn = new ArrayList<>();
                b.forEachPositionMemberIn(p, s, gotIn::add);
                assertThat(gotIn).isEqualTo(wantIn);
                assertThat(b.positionMemberCountIn(p, s)).isEqualTo(wide.and(s).size());
                assertThat(b.positionWithin(p, s)).as(b.positionId(p)).isEqualTo(s.containsAll(wide));
            }
        }
    }

    @Test
    void theWalksGiveWhatTheWideSetGaveOnAMorningBook() {
        sameAsTheWidePath(book(400, 7, 1));
    }

    @Test
    void theWalksGiveWhatTheWideSetGaveWithAnIntradayLayer() {
        VarBook b = withLayer(book(400, 7, 1));
        assertThat(b.intraday()).isNotNull();
        assertThat(b.intraday().added()).isEqualTo(2);
        sameAsTheWidePath(b);
        // a scope by position ids: the same set as the union of the wide sets
        ScopeResolver res = new ScopeResolver(b, null);
        OrdinalSet want = OrdinalSet.empty();
        List<String> ids = new ArrayList<>();
        for (int p = 0; p < b.positionCount(); p += 3) {
            ids.add(b.positionId(p));
            want = want.or(b.positionMembers(p));
        }
        assertThat(res.resolve("POS:" + String.join(",", ids)).set()).isEqualTo(want);
    }

    /** Bytes the current thread allocates while {@code r} runs. */
    static long allocated(Runnable r) {
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long before = t.getThreadAllocatedBytes(id);
        r.run();
        return t.getThreadAllocatedBytes(id) - before;
    }

    @Test
    void sizingEveryPositionOfAWideDateNoLongerCostsPositionsTimesTheDatesWidth() {
        VarBook b = book(40_000, 1_000_000, 2);                       // 40,000 positions of one trade each
        OrdinalSet bank = b.node("MGMT~BANK").members();
        int n = b.positionCount();
        double[] sum = {0, 0};
        for (int k = 0; k < 3; k++) {                                 // warm both paths
            oldPath(b, bank, sum);
            newPath(b, bank, sum);
        }
        long old = allocated(() -> oldPath(b, bank, sum));
        long now = allocated(() -> newPath(b, bank, sum));
        System.out.printf("sizing %d positions: through positionMembers %,d bytes, walking the runs %,d bytes%n", n, old, now);
        assertThat(sum[1]).isEqualTo(sum[0]);
        // the old path: a set of the date's width per position (about n x n / 8 bytes, 200 MB here); the new one: a few bytes a position
        assertThat(old).isGreaterThan((long) n * n / 16);
        assertThat(now).as("bytes allocated sizing %d positions", n).isLessThan(256L * n);
    }

    private static void oldPath(VarBook b, OrdinalSet set, double[] sum) {
        for (int p = 0; p < b.positionCount(); p++) {
            OrdinalSet m = b.positionMembers(p);
            if (set.containsAll(m)) {
                double[] s = {0};
                m.and(set).forEach(o -> s[0] += Math.abs(b.trades().number("PV", o)));
                sum[0] += s[0];
            }
        }
    }

    private static void newPath(VarBook b, OrdinalSet set, double[] sum) {
        for (int p = 0; p < b.positionCount(); p++) {
            if (b.positionWithin(p, set)) {
                double[] s = {0};
                b.forEachPositionMemberIn(p, set, o -> s[0] += Math.abs(b.trades().number("PV", o)));
                sum[1] += s[0];
            }
        }
    }
}
