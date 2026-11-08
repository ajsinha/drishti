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

import com.ash.drishti.compute.vector.ParallelSums;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Compute-first on the committed demo data (VAR_GUIDE.md, "Input, computed, cache and the official record"; implementation plan row B5):
 * scenario PVs are the preferred input and the engine derives the P&amp;L from them, so the demo can run on scenario PVs alone.
 *
 * <ul>
 *   <li>Every one of the demo's 8,233 P&amp;L vectors, sent instead as the scenario PVs a pricing system would send for it (the trade's
 *       value today and under each scenario, in USD), gives back through the engine's own conversion ({@link ScenarioFx#pnlFromPv}) its
 *       stored vector <b>exactly</b>; the date built that way (those PVs and the demo's 1,339 scenario-PV trades: PVs only) has every node's
 *       vector equal to the bit to the date built from the stored vectors, and the bank's VaR 112,041,646.</li>
 *   <li>The same vectors sent as PVs in each trade's own currency at the batch's scenario FX give back the stored vector to within
 *       floating-point rounding (1e-9 of the trade's value), and every node's VaR to the cent.</li>
 * </ul>
 * The stress scenario set (section 18.8) is computed the same way: the bank's stressed VaR and ES on the demo's synthetic stress set.
 */
class VarComputeFirstTest {

    static ParallelSums sums;
    static PositionRules rules;
    static Path data;
    static List<LocalDate> scen;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        Path repo = VarTestDataPrecisionTest.root();
        data = repo.resolve("data/testdata/market-risk");
        Map<String, Object> pack = new ObjectMapper(new YAMLFactory()).readValue(repo.resolve("config/packs/banking/market-risk/pack.yaml").toFile(), Map.class);
        rules = PositionRules.of((Map<?, ?>) pack.get("positions"));
        scen = dates(false);
        sums = new ParallelSums(4, 4096);
    }

    @AfterAll
    static void close() {
        sums.close();
    }

    static List<LocalDate> dates(boolean stress) throws IOException {
        List<LocalDate> out = new ArrayList<>();
        VarTestDataPrecisionTest.lines(data.resolve("scenario-set"), stress, n -> n.path("SCENARIO_DATES").forEach(d -> out.add(LocalDate.parse(d.asText()))));
        return out;
    }

    /** How the stored vectors go into a book: as sent ({@code null}), or turned into scenario PVs in USD or in the trade's currency first. */
    enum Form { VECTORS, PV_USD, PV_OWN }

    /** The demo date built from its committed files, its P&amp;L vectors in {@code form}; the largest difference to a stored vector found. */
    static double[] build(VarBook.Builder b, Form form) throws IOException {
        ScenarioFx fx = VarTestDataPrecisionTest.fx(data, b.width());
        b.currencyRates(c -> fx.rates(c).orElse(null));
        double[] worst = {0, 0};                                              // the largest absolute and relative differences
        VarTestDataPrecisionTest.lines(data.resolve("scenario-pv"), n -> {   // the Endur trades: scenario PVs in their own currency
            if (!rules.live(VarTestDataPrecisionTest.text(n, "TRADE_STATUS"))) {
                return;
            }
            double[] p = values(n.get("SCENARIO_PV"));
            ScenarioFx.Rates r = fx.rates(VarTestDataPrecisionTest.text(n, "CURRENCY")).orElseThrow();
            double[] v = new double[p.length];
            ScenarioFx.pnlFromPv(n.path("BASE_PV").asDouble(), p, p.length, r, v);
            add(b, n, v, n.path("BASE_PV").asDouble() * r.base(), VarTestDataPrecisionTest.text(n, "CURRENCY"));
        });
        VarTestDataPrecisionTest.lines(data.resolve("pnl-vector"), n -> {
            if (!rules.live(VarTestDataPrecisionTest.text(n, "TRADE_STATUS"))) {
                return;
            }
            double[] stored = values(n.get("PNL"));
            double pv = n.path("PV").asDouble(0);
            double[] v = stored;
            if (form != Form.VECTORS) {
                // the scenario PVs a pricing system sends for this trade: its value today and under each scenario, in a currency c
                String c = form == Form.PV_USD ? "USD" : own(n, fx);
                ScenarioFx.Rates r = fx.rates(c).orElseThrow();
                double base = pv / r.base();
                double[] pvs = new double[stored.length];
                for (int j = 0; j < pvs.length; j++) {
                    pvs[j] = (pv + stored[j]) / r.scenarios()[j];
                }
                v = new double[stored.length];
                ScenarioFx.pnlFromPv(base, pvs, pvs.length, r, v);           // the engine's conversion (VarIntake)
                for (int j = 0; j < v.length; j++) {
                    double d = Math.abs(v[j] - stored[j]);
                    worst[0] = Math.max(worst[0], d);
                    worst[1] = Math.max(worst[1], d / Math.max(1, Math.abs(pv) + Math.abs(stored[j])));
                }
            }
            add(b, n, v, pv, null);
        });
        VarTestDataPrecisionTest.lines(data.resolve("risk-hierarchy"), n -> {
            List<String> books = new ArrayList<>();
            n.path("BOOKS").forEach(x -> books.add(x.asText()));
            b.node(VarTestDataPrecisionTest.text(n, "NODE_ID"), VarTestDataPrecisionTest.text(n, "HIERARCHY"), VarTestDataPrecisionTest.text(n, "LEVEL"),
                    VarTestDataPrecisionTest.text(n, "NAME"), VarTestDataPrecisionTest.text(n, "PARENT_ID"), books, Double.NaN);
        });
        return worst;
    }

    /** The trade's own currency when the batch has its scenario FX, else USD. */
    static String own(JsonNode n, ScenarioFx fx) {
        String c = VarTestDataPrecisionTest.text(n, "TRADE_CURRENCY");
        return c != null && fx.rates(c).isPresent() ? c : "USD";
    }

    static double[] values(JsonNode a) {
        double[] v = new double[a.size()];
        for (int j = 0; j < v.length; j++) {
            v[j] = a.get(j).asDouble();
        }
        return v;
    }

    private static void add(VarBook.Builder b, JsonNode n, double[] v, double pvUsd, String pvCurrency) {
        String[] texts = new String[VarBook.TRADE_TEXTS.size()];
        for (int c = 0; c < texts.length; c++) {
            texts[c] = VarTestDataPrecisionTest.text(n, VarBook.TRADE_TEXTS.get(c));
        }
        String trade = VarTestDataPrecisionTest.text(n, "TRADE_ID");
        PositionRules.Derived d = rules.derive(trade, VarTestDataPrecisionTest.text(n, "TT_BOOK"), VarTestDataPrecisionTest.text(n, "PRODUCT_GROUP"),
                VarTestDataPrecisionTest.text(n, "PRODUCT_TYPE"), k -> VarTestDataPrecisionTest.text(n, k));
        texts[VarBook.TRADE_TEXTS.indexOf("POSITION_ID")] = d.positionId();
        texts[VarBook.TRADE_TEXTS.indexOf("POSITION_RULE")] = d.rule();
        texts[VarBook.TRADE_TEXTS.indexOf("INSTRUMENT_ID")] = d.instrumentId();
        texts[VarBook.TRADE_TEXTS.indexOf("SECURITY_ID")] = d.securityId();
        texts[VarBook.TRADE_TEXTS.indexOf(CurrencyTotals.COLUMN)] = pvCurrency;
        b.trade(trade, v, v.length, texts, new double[] {n.path("QUANTITY").asDouble(Double.NaN), pvUsd});
    }

    static VarBook book(Form form, double[] worst) throws IOException {
        VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", scen, 65_536, Long.MAX_VALUE);
        double[] w = build(b, form);
        worst[0] = w[0];
        worst[1] = w[1];
        return b.build(sums);
    }

    @Test
    void theDemoRunOnScenarioPvsAloneGivesTheStoredVectorsToTheBit() throws IOException {
        double[] none = new double[2];
        double[] usd = new double[2];
        VarBook vectors = book(Form.VECTORS, none);
        VarBook pvs = book(Form.PV_USD, usd);
        assertThat(usd[0]).as("the largest difference of a derived P&L to its stored vector").isZero();
        assertThat(pvs.tradeCount()).isEqualTo(9572);
        assertThat(pvs.nodes()).hasSize(88);
        for (VarBook.Node n : vectors.nodes().values()) {
            assertThat(pvs.aggregate(n.id())).as(n.id()).containsExactly(vectors.aggregate(n.id()));
        }
        VarCalc calc = VarCalc.defaults();
        assertThat(Math.round(calc.measures(pvs.aggregate("MGMT~BANK")).var())).isEqualTo(112_041_646L);
        assertThat(Math.round(calc.measures(pvs.aggregate("MGMT~BANK")).es())).isEqualTo(160_143_809L);
    }

    @Test
    void scenarioPvsInEachTradesOwnCurrencyGiveTheStoredVectorsToRounding() throws IOException {
        double[] none = new double[2];
        double[] own = new double[2];
        VarBook vectors = book(Form.VECTORS, none);
        VarBook pvs = book(Form.PV_OWN, own);
        System.out.printf("compute-first: scenario PVs in the trades' own currencies give the stored P&L to %.3e USD (%.2e of the trade's "
                + "value)%n", own[0], own[1]);
        assertThat(own[1]).isLessThan(1e-9);
        VarCalc calc = VarCalc.defaults();
        for (VarBook.Node n : vectors.nodes().values()) {
            assertThat(calc.measures(pvs.aggregate(n.id())).var()).as(n.id()).isCloseTo(calc.measures(vectors.aggregate(n.id())).var(),
                    org.assertj.core.api.Assertions.within(0.01));
        }
    }

    /**
     * The bank on the demo's synthetic stress set (VAR_GUIDE.md section 18.8: HS-STRESS-2008, 250 days of 2008-07-01 to 2009-06-30): the
     * figures the guides quote, computed from the stress set's own files as the engine reads them (P&amp;L vectors as sent, scenario PVs
     * converted with the stress set's scenario FX).
     */
    @Test
    void theBanksStressedVarOnTheDemoStressSet() throws IOException {
        List<LocalDate> days = dates(true);
        assertThat(days).hasSize(250);
        assertThat(days.get(0)).isEqualTo(LocalDate.of(2008, 7, 1));
        assertThat(days.get(249)).isEqualTo(LocalDate.of(2009, 6, 30));
        VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-STRESS-2008", days, 65_536, Long.MAX_VALUE);
        VarTestDataPrecisionTest.fill(b, data, rules, 1, true);
        VarBook stressed = b.build(sums);
        assertThat(stressed.tradeCount()).isEqualTo(9572);
        VarCalc calc = VarCalc.defaults();
        double[] bank = stressed.aggregate("MGMT~BANK");
        VarCalc.Measures one = calc.measures(bank);
        VarLens sqrt = new VarLens(10, VarLens.Method.SQRT, 0.99, 0.975, true);
        VarLens sum = new VarLens(10, VarLens.Method.SUM, 0.99, 0.975, true);
        VarCalc.Measures ms = sum.measures(calc, bank, sum.apply(bank));
        VarCalc.Measures m95 = VarLens.oneDay(0.95, 0.975).measures(calc, bank, bank);
        System.out.printf(java.util.Locale.ROOT, "stressed, the bank: 1-day 99%% %,d (k=%d of %d) ES %,d (%d); 10-day √10 %,d; 10-day sum %,d (k=%d of %d) "
                + "ES %,d; 95%% %,d (k=%d)%n", Math.round(one.var()), one.k(), bank.length, Math.round(one.es()), one.esCount(),
                Math.round(sqrt.measures(calc, bank, sqrt.apply(bank)).var()), Math.round(ms.var()), ms.k(), sum.apply(bank).length,
                Math.round(ms.es()), Math.round(m95.var()), m95.k());
        assertThat(one.k()).isEqualTo(3);                                            // ceil(250 x 0.01)
        assertThat(one.esCount()).isEqualTo(7);                                      // ceil(250 x 0.025)
        assertThat(Math.round(one.var())).isEqualTo(213_637_794L);
        assertThat(Math.round(one.es())).isEqualTo(222_810_303L);
        assertThat(sum.apply(bank)).hasSize(241);
        assertThat(sqrt.measures(calc, bank, sqrt.apply(bank)).var()).isEqualTo(one.var() * VarLens.SQRT10);
        assertThat(Math.round(sqrt.measures(calc, bank, sqrt.apply(bank)).es())).isEqualTo(704_588_045L);
        assertThat(Math.round(ms.var())).isEqualTo(293_280_315L);
        assertThat(Math.round(m95.var())).isEqualTo(147_807_462L);
    }
}
