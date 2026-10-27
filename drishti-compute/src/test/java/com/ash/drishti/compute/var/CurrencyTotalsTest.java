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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-trade-currency totals of every node (VAR_GUIDE.md section 15.11): a node's P&amp;L in any reporting currency from its PVs summed
 * per trade currency equals, <b>to the cent</b>, the sum of its trades each converted on its own from the PVs as received, under every
 * scenario, and so its VaR and ES; it equals the book converted trade by trade ({@link CurrencyBook}) too. The native sums are the received
 * PVs' sums; the totals survive the hot file; any other scope's totals ({@link CurrencyTotals#of}) agree with a node's stored ones.
 */
class CurrencyTotalsTest {

    static final List<String> CCYS = List.of("USD", "EUR", "CAD", "JPY");
    static final int BOOKS = 6;
    static final int TRADES = 360;
    static ParallelSums sums;
    static ScenarioFx fx;
    static VarBook heap;
    static VarBook mapped;
    /** Per trade id: its currency (null: a P&amp;L vector in USD), its base PV, its scenario PVs (native), or its USD vector and PV. */
    static final Map<String, Object[]> TRADE = new HashMap<>();
    @TempDir
    static Path dir;

    @BeforeAll
    static void build() {
        sums = new ParallelSums(2, 64);
        int w = SyntheticBank.WIDTH;
        SplittableRandom r = new SplittableRandom(7);
        List<ScenarioFx.Rates> rates = new ArrayList<>();
        for (String[] c : new String[][] {{"EUR", "1.08"}, {"CAD", "0.73"}, {"JPY", "0.0067"}, {"GBP", "1.27"}}) {
            double base = Double.parseDouble(c[1]);
            double[] s = new double[w];
            for (int j = 0; j < w; j++) {
                s[j] = base * (1 + 0.006 * r.nextGaussian());
            }
            rates.add(new ScenarioFx.Rates(c[0], base, s, "supplied"));
        }
        fx = new ScenarioFx(w, rates);
        for (int t = 0; t < TRADES; t++) {
            String ccy = t % 5 == 4 ? null : CCYS.get(t % 4);
            double base = (t % 2 == 0 ? 1 : -1) * Math.rint(1e6 * (1 + r.nextDouble())) / (ccy != null && ccy.equals("JPY") ? 0.0067 : 1);
            double[] v = new double[w];
            for (int j = 0; j < w; j++) {
                v[j] = ccy == null ? Math.rint(2e4 * r.nextGaussian()) : base * (1 + 0.02 * r.nextGaussian());
            }
            double pvUsd = t % 15 == 4 ? Double.NaN : base;            // some P&L vectors without a PV: their P&L alone converts
            TRADE.put("T-" + t, new Object[] {ccy, ccy == null ? pvUsd : base, v});
        }
        heap = fill(new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), 65_536, Long.MAX_VALUE)).build(sums);
        VarBook.HotTarget target = SyntheticBank.target(dir, "ctot.vhot", "digest-ctot");
        mapped = fill(new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), Long.MAX_VALUE, target)).build(sums);
    }

    @AfterAll
    static void close() {
        mapped.close();
        sums.close();
    }

    /** The trades as the server's intake gives them: a scenario-PV trade's USD P&amp;L and USD PV; a P&amp;L vector as sent. */
    static VarBook.Builder fill(VarBook.Builder b) {
        b.currencyRates(c -> fx.rates(c).orElse(null));
        int w = SyntheticBank.WIDTH;
        double[] usd = new double[w];
        for (int t = 0; t < TRADES; t++) {
            Object[] x = TRADE.get("T-" + t);
            String ccy = (String) x[0];
            double base = (double) x[1];
            double[] v = (double[]) x[2];
            String[] texts = new String[VarBook.TRADE_TEXTS.size()];
            double[] nums = new double[VarBook.TRADE_NUMBERS.size()];
            String book = "BOOK-" + (t % BOOKS);
            texts[VarBook.TRADE_TEXTS.indexOf("LEGAL_ENTITY")] = "LE-1";
            texts[VarBook.TRADE_TEXTS.indexOf("DESK")] = "DESK-" + (t % BOOKS / 3);
            texts[VarBook.TRADE_TEXTS.indexOf("TT_BOOK")] = book;
            texts[VarBook.TRADE_TEXTS.indexOf("CURRENCY")] = ccy == null ? "GBP" : ccy;   // a P&L vector's trade may be in any currency
            texts[VarBook.TRADE_TEXTS.indexOf(CurrencyTotals.COLUMN)] = ccy;
            nums[0] = 1;
            if (ccy == null) {
                nums[1] = base;
                b.trade("T-" + t, v, w, texts, nums);
            } else {
                ScenarioFx.Rates rc = fx.rates(ccy).orElseThrow();
                ScenarioFx.pnlFromPv(base, v, w, rc, usd);
                nums[1] = base * rc.base();
                b.trade("T-" + t, usd, w, texts, nums);
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
        return b;
    }

    /** The node's P&amp;L in {@code x}, each of its trades converted on its own from what was received (the trade-level result). */
    static double[] tradeLevel(VarBook b, String node, String x) {
        int w = b.width();
        double[] out = new double[w];
        ScenarioFx.Rates rx = fx.rates(x).orElseThrow();
        b.node(node).members().forEach(o -> {
            Object[] t = TRADE.get(b.tradeId(o));
            String ccy = (String) t[0];
            double base = (double) t[1];
            double[] v = (double[]) t[2];
            if (ccy == null) {
                double p = Double.isNaN(base) ? 0 : base;
                for (int s = 0; s < w; s++) {
                    out[s] += (p + v[s]) / rx.scenarios()[s] - p / rx.base();
                }
            } else {
                ScenarioFx.Rates rc = fx.rates(ccy).orElseThrow();
                for (int s = 0; s < w; s++) {
                    out[s] += v[s] * rc.scenarios()[s] / rx.scenarios()[s] - base * rc.base() / rx.base();
                }
            }
        });
        return out;
    }

    @Test
    void everyNodesTotalsEqualTheTradeLevelResultToTheCentInEveryCurrency() {
        VarCalc calc = VarCalc.defaults();
        for (VarBook b : List.of(heap, mapped)) {
            CurrencyTotals t = b.currencyTotals();
            assertThat(t).as(b.tier()).isNotNull();
            for (String node : b.nodes().keySet()) {
                for (String x : List.of("USD", "EUR", "CAD", "JPY", "GBP")) {
                    double[] fromTotals = t.get(node).in(fx, x);
                    double[] expected = tradeLevel(b, node, x);
                    for (int s = 0; s < expected.length; s++) {
                        assertThat(fromTotals[s]).as(b.tier() + " " + node + " " + x + " scenario " + s).isCloseTo(expected[s], within(0.005));
                    }
                    VarCalc.Measures a = calc.measures(fromTotals);
                    VarCalc.Measures e = calc.measures(expected);
                    assertThat(a.var()).as(node + " " + x + " VaR").isCloseTo(e.var(), within(0.005));
                    assertThat(a.es()).as(node + " " + x + " ES").isCloseTo(e.es(), within(0.005));
                }
            }
        }
    }

    @Test
    void theTotalsAgreeWithTheBookConvertedTradeByTrade() {
        for (String x : List.of("EUR", "CAD", "JPY")) {
            VarBook conv = CurrencyBook.convert(heap, fx.rates(x).orElseThrow(), new VarBook.Builder(heap.businessDate(), heap.scenarioSet(),
                    heap.scenarioDates(), 65_536, Long.MAX_VALUE), sums).book();
            assertThat(conv.currencyTotals()).isNull();                  // a book in another currency carries none: the USD book's are used
            for (String node : heap.nodes().keySet()) {
                double[] a = heap.currencyTotals().get(node).in(fx, x);
                double[] b = conv.aggregate(node);
                for (int s = 0; s < a.length; s++) {
                    assertThat(a[s]).as(node + " " + x + " " + s).isCloseTo(b[s], within(0.005));
                }
            }
        }
    }

    @Test
    void theNativeSumsAreTheReceivedPvsAndSurviveTheHotFile() throws Exception {
        CurrencyTotals.Sums bank = heap.currencyTotals().get("MGMT~BANK");
        assertThat(bank.currencies()).containsExactly("CAD", "EUR", "JPY", "USD");
        Map<String, Double> expected = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();
        TRADE.values().forEach(t -> {
            String c = t[0] == null ? "USD" : (String) t[0];
            double base = (double) t[1];
            expected.merge(c, Double.isNaN(base) ? 0 : base, Double::sum);
            count.merge(c, 1, Integer::sum);
        });
        for (int i = 0; i < bank.currencies().length; i++) {
            String c = bank.currencies()[i];
            assertThat(bank.base()[i]).as(c).isCloseTo(expected.get(c), within(1e-4));
            assertThat(bank.trades()[i]).as(c).isEqualTo(count.get(c));
        }
        // the hot file keeps them (CTOT): reopened, every node's sums are the same to the bit
        VarBook again = VarHotFile.open(mapped.hot().file(), 0);
        try {
            for (String node : mapped.nodes().keySet()) {
                CurrencyTotals.Sums a = mapped.currencyTotals().get(node);
                CurrencyTotals.Sums b = again.currencyTotals().get(node);
                assertThat(b.currencies()).containsExactly(a.currencies());
                assertThat(b.base()).containsExactly(a.base());
                for (int i = 0; i < a.pv().length; i++) {
                    assertThat(b.pv()[i]).containsExactly(a.pv()[i]);
                }
            }
        } finally {
            again.close();
        }
    }

    @Test
    void anyScopesTotalsAgreeWithTheNodesStoredOnes() {
        for (String node : List.of("MGMT~BANK", "MGMT~DESK-1", "MGMT~BOOK-2")) {
            OrdinalSet m = heap.node(node).members();
            CurrencyTotals.Sums of = CurrencyTotals.of(heap, m, fx, sums);
            double[] a = of.in(fx, "EUR");
            double[] b = heap.currencyTotals().get(node).in(fx, "EUR");
            for (int s = 0; s < a.length; s++) {
                assertThat(a[s]).as(node + " " + s).isCloseTo(b[s], within(0.005));
            }
        }
        // an ad-hoc scope: two books' worth of one desk and a few trades, against the trade-level result
        OrdinalSet set = heap.node("MGMT~BOOK-0").members().or(OrdinalSet.of(heap.tradeOrdinal("T-5"), heap.tradeOrdinal("T-11")));
        double[] got = CurrencyTotals.of(heap, set, fx, sums).in(fx, "CAD");
        double[] want = new double[heap.width()];
        ScenarioFx.Rates rx = fx.rates("CAD").orElseThrow();
        set.forEach(o -> {
            double[] row = heap.tradeVector(o);
            double pv = heap.trades().number("PV", o);
            double p = Double.isNaN(pv) ? 0 : pv;
            for (int s = 0; s < want.length; s++) {
                want[s] += (p + row[s]) / rx.scenarios()[s] - p / rx.base();
            }
        });
        for (int s = 0; s < want.length; s++) {
            assertThat(got[s]).isCloseTo(want[s], within(0.005));
        }
        // a currency the date has no rates for: none (the server answers DRS-5207)
        assertThat(heap.currencyTotals().get("MGMT~BANK").in(fx, "CHF")).isNull();
    }
}
