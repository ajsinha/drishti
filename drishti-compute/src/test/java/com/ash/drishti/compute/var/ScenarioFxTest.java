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

import com.ash.drishti.compute.vector.ParallelSums;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The FX of scenario PVs and reporting currencies on hand-made numbers: the USD P&amp;L of a trade priced in its own currency, every
 * cross through USD (a GBP trade in EUR is the same number by the two routes), a USD vector converted with its value today, scenario
 * rates derived from a history (today's rate moved by the day's 1-day move) and supplied rates winning over derived ones, a currency
 * neither covers left out with its reason, and a whole book converted (same trades, same order, PV and limits at today's rate).
 */
class ScenarioFxTest {

    private static final List<LocalDate> DAYS = List.of(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 28));
    /** USD per EUR today 1.20; under the three scenarios 1.10, 1.25, 1.20. */
    private static final ScenarioFx.Rates EUR = new ScenarioFx.Rates("EUR", 1.20, new double[] {1.10, 1.25, 1.20}, "supplied");
    /** USD per JPY: 1/150 today; 1/160, 1/140, 1/150. */
    private static final ScenarioFx.Rates JPY = new ScenarioFx.Rates("jpy", 1 / 150.0, new double[] {1 / 160.0, 1 / 140.0, 1 / 150.0}, "supplied");
    private static final ScenarioFx.Rates GBP = new ScenarioFx.Rates("GBP", 1.30, new double[] {1.25, 1.35, 1.40}, "supplied");

    @Test
    void theUsdPnlOfATradePricedInItsOwnCurrency() {
        double[] out = new double[3];
        // a EUR trade worth 1,000 EUR today, 990 / 1,010 / 1,000 under the scenarios
        ScenarioFx.pnlFromPv(1000, new double[] {990, 1010, 1000}, 3, EUR, out);
        assertThat(out[0]).isCloseTo(990 * 1.10 - 1200, within(1e-9));      // -111: the price fell and the euro too
        assertThat(out[1]).isCloseTo(1010 * 1.25 - 1200, within(1e-9));     // 62.5
        assertThat(out[2]).isCloseTo(0, within(1e-9));                      // nothing moved
    }

    @Test
    void crossRatesGoThroughUsd() {
        ScenarioFx fx = new ScenarioFx(3, List.of(EUR, JPY, GBP));
        assertThat(fx.currencies()).containsExactly("EUR", "GBP", "JPY", "USD");
        assertThat(fx.cross("EUR", "JPY", -1)).isCloseTo(1.20 * 150, within(1e-9));       // 180 JPY per EUR today
        assertThat(fx.cross("EUR", "JPY", 0)).isCloseTo(1.10 * 160, within(1e-9));        // 176 under scenario 0
        assertThat(fx.cross("USD", "EUR", 1)).isCloseTo(1 / 1.25, within(1e-12));
        assertThat(fx.cross("EUR", "CHF", 0)).isNaN();                                    // no CHF: no cross
        // a GBP trade reported in EUR: through USD (the engine's route) is the direct cross
        double basePv = 2000;
        double[] pv = {1950, 2100, 2000};
        double[] usd = new double[3];
        ScenarioFx.pnlFromPv(basePv, pv, 3, GBP, usd);
        double[] eur = new double[3];
        ScenarioFx.convert(basePv * GBP.base(), usd, 3, EUR, eur);
        for (int s = 0; s < 3; s++) {
            double direct = pv[s] * fx.cross("GBP", "EUR", s) - basePv * fx.cross("GBP", "EUR", -1);
            assertThat(eur[s]).isCloseTo(direct, within(1e-9));
        }
    }

    @Test
    void aUsdVectorConvertsWithItsValueToday() {
        double[] usd = {-100, 50, 0};
        double[] eur = new double[3];
        ScenarioFx.convert(1200, usd, 3, EUR, eur);
        assertThat(eur[0]).isCloseTo(1100 / 1.10 - 1000, within(1e-9));     // 0: the USD loss is the euro's fall, nothing in EUR
        assertThat(eur[1]).isCloseTo(1250 / 1.25 - 1000, within(1e-9));     // 0 again
        assertThat(eur[2]).isCloseTo(0, within(1e-9));
        // with no value today only the P&L converts, at each scenario's rate
        ScenarioFx.convert(0, usd, 3, EUR, eur);
        assertThat(eur[0]).isCloseTo(-100 / 1.10, within(1e-9));
        // in USD nothing changes
        double[] same = new double[3];
        ScenarioFx.convert(1200, usd, 3, new ScenarioFx(3, List.of()).rates("usd").orElseThrow(), same);
        assertThat(same).containsExactly(usd);
    }

    @Test
    void scenarioRatesAreDerivedFromTheHistoryAndSuppliedOnesWin() {
        TreeMap<LocalDate, Double> h = new TreeMap<>(Map.of(LocalDate.of(2026, 9, 23), 1.00, LocalDate.of(2026, 9, 24), 1.02,
                LocalDate.of(2026, 9, 25), 0.99, LocalDate.of(2026, 9, 28), 0.99));
        double[] d = ScenarioFx.derive(1.20, DAYS, h);
        assertThat(d[0]).isCloseTo(1.20 * 1.02 / 1.00, within(1e-12));     // today's rate moved by the day's 1-day move
        assertThat(d[1]).isCloseTo(1.20 * 0.99 / 1.02, within(1e-12));
        assertThat(d[2]).isCloseTo(1.20, within(1e-12));                    // the 28th after a weekend: the day before is the 25th
        TreeMap<LocalDate, Double> shortH = new TreeMap<>(Map.of(LocalDate.of(2026, 9, 25), 0.99, LocalDate.of(2026, 9, 28), 0.99));
        assertThat(ScenarioFx.uncovered(DAYS, ScenarioFx.derive(1.2, DAYS, shortH))).containsExactly(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25));

        ScenarioFx fx = ScenarioFx.assemble(DAYS, List.of(JPY), Map.of("EUR", 1.20, "JPY", 1 / 151.0, "CAD", 0.72, "CHF", 1.25),
                Map.of("EUR", h, "JPY", h, "CHF", shortH));
        assertThat(fx.rates("EUR").orElseThrow().source()).isEqualTo("derived");
        assertThat(fx.rates("EUR").orElseThrow().scenarios()).containsExactly(d);
        assertThat(fx.rates("JPY").orElseThrow().source()).isEqualTo("supplied");           // the batch's rates win over the history
        assertThat(fx.rates("JPY").orElseThrow().base()).isEqualTo(1 / 150.0);
        assertThat(fx.rates("CAD")).isEmpty();                                              // no history
        assertThat(fx.rates("CHF")).isEmpty();                                              // a history too short
        assertThat(fx.notes()).anyMatch(n -> n.startsWith("CAD: no scenario rates supplied and no FX history"))
                .anyMatch(n -> n.startsWith("CHF:") && n.contains("does not cover 2 of the 3 scenario days (first: 2026-09-24)"));
    }

    @Test
    void badRatesAreLeftOutWithTheirReason() {
        ScenarioFx fx = new ScenarioFx(3, List.of(new ScenarioFx.Rates("CAD", 0.73, new double[] {0.72, Double.NaN, 0.74}, "supplied"),
                new ScenarioFx.Rates("CHF", 1.25, new double[] {1.2, 1.3}, "supplied")));
        assertThat(fx.rates("CAD")).isEmpty();
        assertThat(fx.rates("CHF")).isEmpty();
        assertThat(fx.notes()).containsExactly("CAD: no rate under 1 scenario (first: scenario 1)", "CHF: 2 scenario rates; the scenario set has 3");
    }

    @Test
    void aWholeBookConvertsWithTheSameTradesInTheSameOrder() {
        try (ParallelSums sums = new ParallelSums(2, 16)) {
            VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-3", DAYS, 1024, Long.MAX_VALUE);
            b.trade("T2", new double[] {-100, 50, 0}, 3, texts("BOOK-2", "POS-BOOK-2~T2"), new double[] {1, 1200});
            b.trade("T1", new double[] {10, -20, 30}, 3, texts("BOOK-1", "POS-BOOK-1~T1"), new double[] {1, Double.NaN});
            b.trade("T3", null, -1, texts("BOOK-1", "POS-BOOK-1~T3"), new double[] {1, 0});
            b.node("MGMT~BANK", "MGMT", "bank", "Bank", null, List.of("BOOK-1", "BOOK-2"), 600);
            VarBook usd = b.build(sums);
            CurrencyBook.Converted c = CurrencyBook.convert(usd, EUR,
                    new VarBook.Builder(usd.businessDate(), usd.scenarioSet(), usd.scenarioDates(), 1024, Long.MAX_VALUE), sums);
            VarBook eur = c.book();
            assertThat(c.withoutPv()).isEqualTo(1);
            assertThat(eur.tradeCount()).isEqualTo(2);
            assertThat(eur.failed()).isEqualTo(1);
            for (int o = 0; o < 2; o++) {
                assertThat(eur.tradeId(o)).isEqualTo(usd.tradeId(o));
            }
            int t2 = eur.tradeOrdinal("T2");
            assertThat(eur.tradeVector(t2)).usingComparatorWithPrecision(1e-9).containsExactly(0, 0, 0);
            assertThat(eur.trades().number("PV", t2)).isCloseTo(1000, within(1e-9));
            int t1 = eur.tradeOrdinal("T1");
            assertThat(eur.tradeVector(t1)).usingComparatorWithPrecision(1e-9).containsExactly(10 / 1.10, -20 / 1.25, 30 / 1.20);
            assertThat(eur.node("MGMT~BANK").limit()).isCloseTo(500, within(1e-9));
            assertThat(eur.aggregate("MGMT~BANK")).usingComparatorWithPrecision(1e-9).containsExactly(10 / 1.10, -20 / 1.25, 30 / 1.20);
        }
    }

    private static String[] texts(String book, String position) {
        String[] t = new String[VarBook.TRADE_TEXTS.size()];
        t[VarBook.TRADE_TEXTS.indexOf("LEGAL_ENTITY")] = "LE-A";
        t[VarBook.TRADE_TEXTS.indexOf("DESK")] = "DESK-X";
        t[VarBook.TRADE_TEXTS.indexOf("TT_BOOK")] = book;
        t[VarBook.TRADE_TEXTS.indexOf("POSITION_ID")] = position;
        return t;
    }
}
