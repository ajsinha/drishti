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
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The intraday layer (VAR_GUIDE.md section 8.4): a morning book with batches applied (amended, new, cancelled, moved to another book, a
 * trade updated twice, a new trade joining an existing position) equals, to the cent, the book built from the final data in one run:
 * every node's P&amp;L and VaR in both hierarchies, any scope, every position, the per-trade-currency totals in every currency; on the
 * heap and on the tiered memory (float32 rows, the morning rows given exactly as the lake holds them). Applying is incremental: a batch
 * leaves the morning book and the previous snapshot unchanged, and the work does not grow with the batches applied before.
 */
class VarIntradayTest {

    static final int W = SyntheticBank.WIDTH;
    static final int BOOKS = 6;
    static ParallelSums sums;
    static ScenarioFx fx;
    @TempDir
    static Path dir;

    /** A trade's state: its book, position, currency (null: a P&amp;L vector), USD value today, USD P&amp;L. */
    record T(String id, String book, String pos, String ccy, double pv, double[] pnl, boolean live) {}

    static final Map<String, T> MORNING = new LinkedHashMap<>();

    @BeforeAll
    static void data() {
        sums = new ParallelSums(2, 64);
        SplittableRandom r = new SplittableRandom(11);
        List<ScenarioFx.Rates> rates = new ArrayList<>();
        for (String[] c : new String[][] {{"EUR", "1.08"}, {"CAD", "0.73"}, {"GBP", "1.27"}}) {
            double base = Double.parseDouble(c[1]);
            double[] s = new double[W];
            for (int j = 0; j < W; j++) {
                s[j] = base * (1 + 0.006 * r.nextGaussian());
            }
            rates.add(new ScenarioFx.Rates(c[0], base, s, "supplied"));
        }
        fx = new ScenarioFx(W, rates);
        for (int t = 0; t < 240; t++) {
            String book = "BOOK-" + (t % BOOKS);
            String pos = t % 7 == 0 ? "POS-" + book + "~SEC-" + (t % 3) : null;          // some fungible positions
            String ccy = t % 4 == 0 ? null : List.of("USD", "EUR", "CAD").get(t % 3);
            MORNING.put("T-" + t, trade("T-" + t, book, pos, ccy, r));
        }
    }

    static T trade(String id, String book, String pos, String ccy, SplittableRandom r) {
        double[] v = new double[W];
        for (int j = 0; j < W; j++) {
            v[j] = Math.rint(3e4 * r.nextGaussian()) + 0.37 * r.nextGaussian();
        }
        return new T(id, book, pos, ccy, Math.rint(1e6 * r.nextDouble()) + 0.11, v, true);
    }

    @AfterAll
    static void close() {
        sums.close();
    }

    static String[] texts(T t) {
        String[] x = new String[VarBook.TRADE_TEXTS.size()];
        x[VarBook.TRADE_TEXTS.indexOf("POSITION_ID")] = t.pos() == null ? "POS-" + t.book() + "~" + t.id() : t.pos();
        x[VarBook.TRADE_TEXTS.indexOf("LEGAL_ENTITY")] = "LE-" + (t.book().endsWith("5") ? 2 : 1);
        x[VarBook.TRADE_TEXTS.indexOf("DESK")] = "DESK-" + ("012".contains(t.book().substring(5)) ? 0 : 1);
        x[VarBook.TRADE_TEXTS.indexOf("TT_BOOK")] = t.book();
        x[VarBook.TRADE_TEXTS.indexOf("CURRENCY")] = t.ccy() == null ? "USD" : t.ccy();
        x[VarBook.TRADE_TEXTS.indexOf(CurrencyTotals.COLUMN)] = t.ccy();
        x[VarBook.TRADE_TEXTS.indexOf("PRODUCT_TYPE")] = "PT";
        return x;
    }

    static double[] numbers(T t) {
        return new double[] {1, t.pv()};
    }

    /** A book of these trades: on the heap, or (with a folder) mapped. */
    static VarBook build(Map<String, T> trades, Path mapped) {
        VarBook.Builder b = mapped == null ? new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), 65_536, Long.MAX_VALUE)
                : new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), Long.MAX_VALUE,
                        SyntheticBank.target(mapped, "i-" + System.nanoTime() + ".vhot", "d-" + System.nanoTime()));
        b.currencyRates(c -> fx.rates(c).orElse(null));
        for (T t : trades.values()) {
            if (t.live()) {
                b.trade(t.id(), t.pnl(), W, texts(t), numbers(t));
            }
        }
        List<String> all = new ArrayList<>();
        for (int k = 0; k < BOOKS; k++) {
            all.add("BOOK-" + k);
        }
        b.node("MGMT~BANK", "MGMT", "bank", "Bank", null, all, Double.NaN);
        b.node("MGMT~DESK-0", "MGMT", "desk", "Desk 0", "MGMT~BANK", all.subList(0, 3), Double.NaN);
        b.node("MGMT~DESK-1", "MGMT", "desk", "Desk 1", "MGMT~BANK", all.subList(3, 6), Double.NaN);
        for (int k = 0; k < BOOKS; k++) {
            b.node("MGMT~BOOK-" + k, "MGMT", "book", "Book " + k, "MGMT~DESK-" + (k / 3), List.of("BOOK-" + k), Double.NaN);
        }
        b.node("LEGAL~BANK", "LEGAL", "bank", "Bank", null, all, Double.NaN);
        b.node("LEGAL~LE-1", "LEGAL", "legal-entity", "LE 1", "LEGAL~BANK", all.subList(0, 5), Double.NaN);
        b.node("LEGAL~LE-2", "LEGAL", "legal-entity", "LE 2", "LEGAL~BANK", all.subList(5, 6), Double.NaN);
        return b.build(sums);
    }

    static VarIntraday.Update update(T t) {
        return new VarIntraday.Update(t.id(), t.live() ? t.pnl().clone() : null, t.pv(), texts(t), numbers(t), t.pv(), t.live() ? t.pnl().clone() : null);
    }

    /** The day's batches: amended, new, cancelled, moved, updated twice, a new trade in an existing position, a cancel then a revival. */
    static List<List<T>> batches() {
        SplittableRandom r = new SplittableRandom(99);
        List<List<T>> out = new ArrayList<>();
        List<T> b1 = new ArrayList<>();
        T a = MORNING.get("T-5");
        b1.add(new T(a.id(), a.book(), a.pos(), a.ccy(), a.pv() * 1.1, trade(a.id(), a.book(), a.pos(), a.ccy(), r).pnl(), true));     // amended
        b1.add(trade("N-1", "BOOK-2", null, "EUR", r));                                                         // new, own position
        T c = MORNING.get("T-9");
        b1.add(new T(c.id(), c.book(), c.pos(), c.ccy(), c.pv(), c.pnl(), false));                              // cancelled
        T f = MORNING.get("T-14");                                                                               // a fungible position's id
        b1.add(trade("N-2", f.book(), f.pos(), "CAD", r));                                                     // new, joins it
        out.add(b1);
        List<T> b2 = new ArrayList<>();
        b2.add(new T(a.id(), a.book(), a.pos(), a.ccy(), a.pv() * 0.9, trade(a.id(), a.book(), a.pos(), a.ccy(), r).pnl(), true));      // again
        T m = MORNING.get("T-22");
        b2.add(new T(m.id(), "BOOK-5", null, m.ccy(), m.pv(), m.pnl(), true));                                  // moved to another book
        b2.add(trade("N-3", "BOOK-0", null, null, r));                                                          // new P&L vector
        T cad = MORNING.get("T-2");                                                                              // scenario PVs in CAD,
        b2.add(new T(cad.id(), cad.book(), cad.pos(), null, cad.pv(), trade(cad.id(), cad.book(), cad.pos(), null, r).pnl(), true)); // now a vector
        out.add(b2);
        List<T> b3 = new ArrayList<>();
        b3.add(new T(c.id(), c.book(), c.pos(), c.ccy(), c.pv() * 2, trade(c.id(), c.book(), c.pos(), c.ccy(), r).pnl(), true)); // revived
        T n1 = b1.get(1);
        b3.add(new T(n1.id(), n1.book(), n1.pos(), n1.ccy(), n1.pv(), n1.pnl(), false));                         // a new one cancelled
        T x = MORNING.get("T-31");
        b3.add(new T(x.id(), x.book(), x.pos(), x.ccy(), x.pv(), x.pnl(), false));
        out.add(b3);
        return out;
    }

    static Map<String, T> finalState() {
        Map<String, T> out = new LinkedHashMap<>(MORNING);
        for (List<T> b : batches()) {
            for (T t : b) {
                out.put(t.id(), t);
            }
        }
        return out;
    }

    static VarBook applied(VarBook morning) {
        VarBook b = morning;
        VarIntraday.Morning exact = o -> MORNING.get(morning.tradeId(o)).pnl().clone();   // the morning rows as the lake holds them
        int k = 0;
        for (List<T> batch : batches()) {
            k++;
            VarBook before = b;
            b = VarIntraday.apply(b, batch.stream().map(VarIntradayTest::update).toList(), exact, c -> fx.rates(c).orElse(null), List.of("B-" + k),
                    "2026-09-30T1" + k + ":00:00Z");
            assertThat(b).isNotSameAs(before);
            assertThat(b.morning()).isSameAs(morning);
        }
        return b;
    }

    static void sameToTheCent(VarBook got, VarBook want, String what) {
        VarCalc calc = VarCalc.defaults();
        for (String node : want.nodes().keySet()) {
            double[] a = got.aggregate(node);
            double[] e = want.aggregate(node);
            for (int j = 0; j < W; j++) {
                assertThat(a[j]).as(what + " " + node + " " + j).isCloseTo(e[j], within(0.005));
            }
            double[] planned = got.vector(got.node(node).members(), sums);
            for (int j = 0; j < W; j++) {
                assertThat(planned[j]).as(what + " planned " + node + " " + j).isCloseTo(e[j], within(want.tier().equals("heap") ? 0.005 : 0.6));
            }
            assertThat(calc.measures(a).var()).isCloseTo(calc.measures(e).var(), within(0.005));
            assertThat(calc.measures(a).es()).isCloseTo(calc.measures(e).es(), within(0.005));
            assertThat(got.node(node).members().size()).as(node).isEqualTo(want.node(node).members().size());
            for (String ccy : List.of("USD", "EUR", "CAD", "GBP")) {
                double[] ta = got.currencyTotals().get(node).in(fx, ccy);
                double[] te = want.currencyTotals().get(node).in(fx, ccy);
                for (int j = 0; j < W; j++) {
                    assertThat(ta[j]).as(what + " totals " + node + " " + ccy + " " + j).isCloseTo(te[j], within(0.005));
                }
            }
        }
        // every trade by id, every position by id: the same members and P&L
        for (int o = 0; o < want.tradeCount(); o++) {
            int g = got.tradeOrdinal(want.tradeId(o));
            assertThat(g).as(want.tradeId(o)).isGreaterThanOrEqualTo(0);
            double[] a = got.tradeVector(g);
            double[] e = want.tradeVector(o);
            for (int j = 0; j < W; j += 50) {
                assertThat(a[j]).isCloseTo(e[j], within(want.tier().equals("heap") ? 0.005 : 0.05));
            }
        }
        assertThat(got.all().size()).isEqualTo(want.tradeCount());
        for (int p = 0; p < want.positionCount(); p++) {
            int gp = got.positionIndex(want.positionId(p));
            assertThat(gp).as(want.positionId(p)).isGreaterThanOrEqualTo(0);
            assertThat(got.positionMembers(gp).size()).as(want.positionId(p)).isEqualTo(want.positionMembers(p).size());
            assertThat(got.positions().number("QUANTITY", gp)).isEqualTo(want.positions().number("QUANTITY", p));
            double[] a = got.positionVector(gp);
            double[] e = want.positionVector(p);
            assertThat(a[7]).isCloseTo(e[7], within(want.tier().equals("heap") ? 0.005 : 0.6));
        }
        // an ad-hoc scope: two books' trades and a few others, with an added one
        OrdinalSet set = got.node("MGMT~BOOK-2").members().or(got.node("MGMT~BOOK-4").members());
        OrdinalSet wset = want.node("MGMT~BOOK-2").members().or(want.node("MGMT~BOOK-4").members());
        double[] a = got.vector(set, sums);
        double[] e = want.vector(wset, sums);
        for (int j = 0; j < W; j++) {
            assertThat(a[j]).isCloseTo(e[j], within(want.tier().equals("heap") ? 0.005 : 0.6));
        }
    }

    @Test
    void onTheHeapTheIncrementalResultEqualsAFullReloadToTheCent() {
        VarBook morning = build(MORNING, null);
        VarBook got = applied(morning);
        VarBook want = build(finalState(), null);
        sameToTheCent(got, want, "heap");
        VarIntraday.Info i = got.intraday().info();
        assertThat(i.batches()).containsExactly("B-1", "B-2", "B-3");
        assertThat(i.lastAt()).isEqualTo("2026-09-30T13:00:00Z");
        assertThat(i.updates()).isEqualTo(11);
        assertThat(got.tradeOrdinal("T-9")).isGreaterThanOrEqualTo(0);                 // revived
        assertThat(got.tradeOrdinal("T-31")).isEqualTo(-1);                            // cancelled: in no scope
        assertThat(got.tradeOrdinal("N-1")).isEqualTo(-1);
        assertThat(got.trades().text("TT_BOOK", got.tradeOrdinal("T-22"))).isEqualTo("BOOK-5");
        // the morning book is untouched
        assertThat(morning.intraday()).isNull();
        assertThat(morning.tradeOrdinal("T-31")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void onTheTieredMemoryTheNodesEqualAFullReloadToTheCent() {
        VarBook morning = build(MORNING, dir);
        try {
            VarBook got = applied(morning);
            VarBook want = build(finalState(), dir);
            try {
                sameToTheCent(got, want, "mapped");
            } finally {
                want.close();
            }
        } finally {
            morning.close();
        }
    }

    @Test
    void aTradeCancelledIsInNoScopeAndItsPnlIsRemoved() {
        VarBook morning = build(MORNING, null);
        T c = MORNING.get("T-31");
        VarBook b = VarIntraday.apply(morning, List.of(update(new T(c.id(), c.book(), c.pos(), c.ccy(), c.pv(), c.pnl(), false))),
                o -> MORNING.get(morning.tradeId(o)).pnl().clone(), x -> fx.rates(x).orElse(null), List.of("B-X"), "2026-09-30T10:00:00Z");
        double[] before = morning.aggregate("MGMT~" + c.book());
        double[] after = b.aggregate("MGMT~" + c.book());
        for (int j = 0; j < W; j++) {
            assertThat(after[j]).isCloseTo(before[j] - c.pnl()[j], within(1e-6));
        }
        assertThat(b.cancelled(morning.tradeOrdinal("T-31"))).isTrue();
        assertThat(b.all().size()).isEqualTo(morning.tradeCount() - 1);
        assertThat(b.intraday().info().cancelled()).isEqualTo(1);
    }

    @Test
    void theBatchesAreListedByTheirNewestComputedAtThenIdWhateverOrderTheyWereAppliedIn() {
        VarBook morning = build(MORNING, null);
        T a = MORNING.get("T-31");
        T b = MORNING.get("T-32");
        VarIntraday.Morning exact = o -> MORNING.get(morning.tradeId(o)).pnl().clone();
        java.util.function.Function<String, ScenarioFx.Rates> rates = x -> fx.rates(x).orElse(null);
        List<VarIntraday.Update> ua = List.of(update(new T(a.id(), a.book(), a.pos(), a.ccy(), a.pv(), a.pnl(), false)));
        List<VarIntraday.Update> ub = List.of(update(new T(b.id(), b.book(), b.pos(), b.ccy(), b.pv(), b.pnl(), false)));
        // B-2 computed at 10:00, B-1 at 11:00, B-0 also at 11:00: listed B-2, B-0, B-1 however they come
        VarBook one = VarIntraday.apply(VarIntraday.apply(morning, ub, exact, rates, Map.of("B-1", "2026-09-30T11:00:00Z", "B-0",
                "2026-09-30T11:00:00Z"), "2026-09-30T11:00:00Z"), ua, exact, rates, Map.of("B-2", "2026-09-30T10:00:00Z"), "2026-09-30T10:00:00Z");
        VarBook two = VarIntraday.apply(VarIntraday.apply(morning, ua, exact, rates, Map.of("B-2", "2026-09-30T10:00:00Z"), "2026-09-30T10:00:00Z"),
                ub, exact, rates, Map.of("B-0", "2026-09-30T11:00:00Z", "B-1", "2026-09-30T11:00:00Z"), "2026-09-30T11:00:00Z");
        Map<String, String> all = new java.util.LinkedHashMap<>();
        all.put("B-1", "2026-09-30T11:00:00Z");
        all.put("B-2", "2026-09-30T10:00:00Z");
        all.put("B-0", "2026-09-30T11:00:00Z");
        List<VarIntraday.Update> both = new java.util.ArrayList<>(ub);
        both.addAll(ua);
        VarBook three = VarIntraday.apply(morning, both, exact, rates, all, "2026-09-30T11:00:00Z");
        for (VarBook x : List.of(one, two, three)) {
            assertThat(x.intraday().info().batches()).containsExactly("B-2", "B-0", "B-1");
            assertThat(x.intraday().info().batchCount()).isEqualTo(3);
            assertThat(x.intraday().info().lastAt()).isEqualTo("2026-09-30T11:00:00Z");
        }
    }
}
