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
package com.ash.drishti.compute.saccr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The hand-built SA-CCR cases (docs/architecture/COUNTERPARTY_ENGINES.md section 9, cases S1 to S7; docs/contracts/SACCR.md section 15):
 * every published EAD to the whole USD it is published in, and the engine to the cent against the case's arithmetic written out here with
 * Math alone (an independent second computation). The BCBS 279 Annex 4 netting sets are skipped while their inputs are unverified.
 */
class SaccrHandBuiltCasesTest {

    private static final SaccrParams P = SaccrParams.bcbs();
    private static final double CENT = 0.005;

    private static Saccr.Result run(SaccrInput in) {
        return Saccr.run(in, P);
    }

    private static double sd(double s, double e) {
        return (Math.exp(-0.05 * s) - Math.exp(-0.05 * e)) / 0.05;
    }

    private static double mult(double vc, double addOn) {
        return Math.min(1, 0.05 + 0.95 * Math.exp(vc / (1.9 * addOn)));
    }

    @Test
    void everyDesignCaseEqualsItsPublishedEad() {
        for (SaccrExamples.Case c : SaccrExamples.design()) {
            Saccr.Result r = run(c.input());
            assertThat(r.refusals()).as(c.name()).isEmpty();
            assertThat(Math.round(r.ead())).as(c.name() + " (" + c.source() + ")").isEqualTo(Math.round(c.publishedEad()));
        }
    }

    @Test
    void s1ToTheCent() {
        double addOn = 0.005 * 100_000_000 * sd(0, 5);
        Saccr.Result r = run(SaccrExamples.s1());
        assertThat(r.terms().get(0).sd()).isCloseTo(4.423984, within(5e-7));
        assertThat(r.unmargined().addOnAgg()).isCloseTo(addOn, within(CENT));
        assertThat(r.ead()).isCloseTo(1.4 * (1_200_000 + addOn), within(CENT));
        Saccr.Result neg = run(new SaccrInput(SaccrExamples.s1().set().withV(-1_200_000), SaccrExamples.s1().trades()));
        assertThat(neg.unmargined().multiplier()).isCloseTo(0.764039, within(5e-7));
        assertThat(neg.ead()).isCloseTo(1.4 * mult(-1_200_000, addOn) * addOn, within(CENT));
    }

    @Test
    void s2BucketOffsetsToTheCent() {
        double d1 = 200_000_000 * sd(0, 0.5) * Math.sqrt(0.5);
        double d2 = -100_000_000 * sd(0, 3);
        double d3 = 50_000_000 * sd(0, 10);
        double en = Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3 + 1.4 * d1 * d2 + 1.4 * d2 * d3 + 0.6 * d1 * d3);
        Saccr.Result r = run(SaccrExamples.s2());
        Saccr.HedgingSet usd = r.hedgingSets().get(0);
        assertThat(usd.enUnmargined()).isCloseTo(en, within(CENT));
        assertThat(Math.round(en)).isEqualTo(270_359_047L);
        assertThat(r.unmargined().multiplier()).isCloseTo(0.486056, within(5e-7));
        assertThat(r.ead()).isCloseTo(1.4 * mult(-2_000_000, 0.005 * en) * 0.005 * en, within(CENT));
        Saccr.Result off = Saccr.run(SaccrExamples.s2(), P, new Saccr.Choices(false, true));
        assertThat(Math.round(off.unmargined().addOnAgg())).isEqualTo(3_709_438L);
    }

    @Test
    void s3FxPairsToTheCent() {
        double eur = 0.04 * Math.abs(55_000_000 * Math.sqrt(0.75) - 22_000_000);
        double jpy = 0.04 * 30_000_000;
        Saccr.Result r = run(SaccrExamples.s3());
        assertThat(r.hedgingSets()).hasSize(2);
        assertThat(r.unmargined().addOns().get("FX")).isCloseTo(eur + jpy, within(CENT));
        assertThat(r.ead()).isCloseTo(1.4 * (800_000 + eur + jpy), within(CENT));
    }

    @Test
    void s4OptionDeltaAndEquityCorrelationToTheCent() {
        Saccr.Result r = run(SaccrExamples.s4());
        Saccr.Terms call = r.terms().get(0);
        double d1 = (Math.log(5_000.0 / 5_200) + 0.5 * 0.75 * 0.75 * 0.5) / (0.75 * Math.sqrt(0.5));
        assertThat(call.d1()).isCloseTo(d1, within(1e-12));
        assertThat(call.delta()).isCloseTo(-0.575819, within(5e-7));
        double a1 = 0.20 * call.delta() * 50_000_000 * Math.sqrt(0.5);
        double a2 = 0.32 * 30_000_000;
        double addOn = Math.sqrt(Math.pow(0.8 * a1 + 0.5 * a2, 2) + 0.36 * a1 * a1 + 0.75 * a2 * a2);
        assertThat(r.unmargined().addOns().get("EQUITY")).isCloseTo(addOn, within(CENT));
        assertThat(r.ead()).isCloseTo(1.4 * mult(-1_200_000, addOn) * addOn, within(CENT));
        SaccrInput.Trade put = SaccrExamples.s4().trades().get(0).option("PUT", "SOLD", 0.5, 5_200, 5_000, Double.NaN);
        assertThat(Saccr.terms(put, 0, P).delta()).isCloseTo(0.424181, within(5e-7));
    }

    @Test
    void s5MarginedRcAndTheCap() {
        Saccr.Result r = run(SaccrExamples.s5(0));
        assertThat(r.mpor()).isEqualTo(10);
        assertThat(r.mfMargined()).isCloseTo(0.3, within(1e-12));
        assertThat(r.margined().rc()).isEqualTo(4_500_000);
        assertThat(r.unmargined().rc()).isEqualTo(2_000_000);
        double ir = 0.005 * 100_000_000 * sd(0, 0.75);
        double addOnM = ir * 0.3 + 0.04 * 40_000_000 * 0.3;
        double addOnU = ir * Math.sqrt(0.75) + 0.04 * 40_000_000 * 0.5;
        assertThat(r.margined().ead()).isCloseTo(1.4 * (4_500_000 + addOnM), within(CENT));
        assertThat(r.unmargined().ead()).isCloseTo(1.4 * (2_000_000 + addOnU), within(CENT));
        assertThat(r.capped()).isTrue();
        assertThat(r.ead()).isEqualTo(r.unmargined().ead());
        Saccr.Result disputes = run(SaccrExamples.s5(3));
        assertThat(disputes.mporFloor()).isEqualTo(20);
        assertThat(Math.round(disputes.margined().ead())).isEqualTo(7_468_966L);
        Saccr.Result noCap = Saccr.run(SaccrExamples.s5(0), P, new Saccr.Choices(true, false));
        assertThat(noCap.capped()).isFalse();
        assertThat(Math.round(noCap.ead())).isEqualTo(7_126_583L);
    }

    @Test
    void s6CreditIndexAgainstASingleName() {
        double a1 = 0.0038 * -100_000_000 * sd(0, 5);
        double a2 = 0.0054 * 40_000_000 * sd(0, 3);
        double addOn = Math.sqrt(Math.pow(0.8 * a1 + 0.5 * a2, 2) + 0.36 * a1 * a1 + 0.75 * a2 * a2);
        Saccr.Result r = run(SaccrExamples.s6());
        assertThat(r.unmargined().addOns().get("CREDIT")).isCloseTo(addOn, within(CENT));
        assertThat(r.ead()).isCloseTo(1.4 * (500_000 + addOn), within(CENT));
    }

    @Test
    void s7ShiftedSwaptionDelta() {
        Saccr.Result r = run(SaccrExamples.s7());
        assertThat(r.terms().get(0).delta()).isCloseTo(0.476754, within(5e-7));
        assertThat(r.terms().get(0).bucket()).isEqualTo(3);
        SaccrInput.Trade noShift = SaccrExamples.s7().trades().get(0).option("CALL", "BOUGHT", 1, 0.025, 0.02, Double.NaN);
        assertThat(Saccr.terms(noShift, 0, P).delta()).isCloseTo(0.422193, within(5e-7));
    }

    @Test
    void cdoTrancheDeltaAndCommodityTypes() {
        SaccrInput.Trade tranche = new SaccrInput.Trade("TR", 0, "CREDIT", "CREDIT", "CDO_TRANCHE", "IDX-CDX-IG", true, "IG", null, 10_000_000, 0, 5, 5,
                Double.NaN, null, "SOLD", Double.NaN, Double.NaN, Double.NaN, 0.03, 0.07, Double.NaN, null);
        assertThat(Saccr.check(tranche)).isNull();
        assertThat(Saccr.terms(tranche, 0, P).delta()).isCloseTo(15 / (1.42 * 1.98), within(1e-12));
        // the explanation's energy example: crude oil +1.8m and natural gas -0.9m of add-on
        SaccrInput.Trade oil = SaccrInput.Trade.linear("OIL", "COMMODITY", "ENERGY", "LONG", 10_000_000, Double.NaN, Double.NaN, 1).named("CRUDE_OIL", false, null);
        SaccrInput.Trade gas = SaccrInput.Trade.linear("GAS", "COMMODITY", "ENERGY", "SHORT", 5_000_000, Double.NaN, Double.NaN, 1).named("NATURAL_GAS", false, null);
        Saccr.Result r = run(new SaccrInput(SaccrInput.NettingSet.unmargined("EX-COM", 0, 0), List.of(oil, gas)));
        assertThat(Math.round(r.unmargined().addOns().get("COMMODITY"))).isEqualTo(1_879_255L);
    }

    @Test
    void edgesOfTheMethod() {
        // E = 1 and E = 5 fall in bucket 2
        assertThat(Saccr.terms(SaccrInput.Trade.linear("A", "IR", "USD", "LONG", 1, 0, 1, 1), 0, P).bucket()).isEqualTo(2);
        assertThat(Saccr.terms(SaccrInput.Trade.linear("B", "IR", "USD", "LONG", 1, 0, 5, 5), 0, P).bucket()).isEqualTo(2);
        // M floored at 10 business days
        assertThat(Saccr.terms(SaccrInput.Trade.linear("C", "FX", "EURUSD", "LONG", 1, Double.NaN, Double.NaN, 0.001), 0, P).mfUnmargined())
                .isCloseTo(Math.sqrt(10 / 250.0), within(1e-12));
        // the multiplier at V - C = 0 is exactly 1; no add-on: 1 and EAD = alpha RC
        assertThat(Saccr.multiplier(0, 1_000, 0.05)).isEqualTo(1.0);
        Saccr.Result empty = run(new SaccrInput(SaccrInput.NettingSet.unmargined("EMPTY", 1_000, 0), List.of()));
        assertThat(empty.unmargined().multiplier()).isEqualTo(1.0);
        assertThat(empty.ead()).isCloseTo(1_400, within(1e-9));
        // far below zero the multiplier tends to the floor
        assertThat(Saccr.multiplier(-1e12, 1, 0.05)).isCloseTo(0.05, within(1e-12));
        // a refused row is counted, never charged
        SaccrInput.Trade bad = SaccrInput.Trade.linear("BAD", "IR", "USD", "SIDEWAYS", 1_000, 0, 1, 1);
        Saccr.Result r = run(new SaccrInput(SaccrInput.NettingSet.unmargined("R", 0, 0), List.of(bad)));
        assertThat(r.refusals()).singleElement().satisfies(x -> assertThat(x.reason()).startsWith("DRS-5410"));
        // a cleared set's floor is 5 days; more than 5,000 trades 20
        SaccrInput.NettingSet cleared = new SaccrInput.NettingSet("CL", null, null, 0, 0, 0, 0, 0, true, true, 0, 1, 0, false, 10);
        assertThat(Saccr.mporFloor(cleared, 10, P)).isEqualTo(5);
        SaccrInput.NettingSet large = new SaccrInput.NettingSet("LG", null, null, 0, 0, 0, 0, 0, true, false, 0, 1, 0, false, 5_001);
        assertThat(Saccr.mporFloor(large, 10, P)).isEqualTo(20);
    }

    @Test
    void phiIsDoublePrecision() {
        assertThat(Saccr.phi(0)).isEqualTo(0.5);
        assertThat(Saccr.phi(1.959963984540054)).isCloseTo(0.975, within(1e-14));
        assertThat(Saccr.phi(-3)).isCloseTo(0.0013498980316301, within(1e-15));
        assertThat(Saccr.phi(0.191210) + Saccr.phi(-0.191210)).isCloseTo(1, within(1e-15));
    }

    @Test
    void theParameterFileIsCompleteAndARefusalListsEveryProblem() {
        assertThat(P.regulation).isEqualTo("BCBS");
        assertThat(P.get("alpha")).isEqualTo(1.4);
        assertThat(P.ref("CREDIT.single.sf.BBB")).isEqualTo("CRE52.72");
        assertThat(P.ref("mpor-floors-days.bilateral")).isEqualTo("CRE52.50");
        assertThat(P.hash).hasSize(64);
        String broken = new String(SaccrParams.builtInBytes(), StandardCharsets.UTF_8).replace("alpha:                     { value: 1.4", "alpha:                     { value: -1")
                .replace("IG: 0.0038, ", "");
        assertThatThrownBy(() -> SaccrParams.parse(broken.getBytes(StandardCharsets.UTF_8))).hasMessageContaining("DRS-5413").hasMessageContaining("alpha")
                .hasMessageContaining("CREDIT.index.sf.IG is missing");
    }

    @Test
    void bcbs279AnnexExamples() {
        for (SaccrExamples.Case c : SaccrExamples.bcbs279()) {
            Assumptions.assumeTrue(c.verified(), c.name() + ": the inputs are unverified against the published BCBS 279 text");
            assertThat(Math.round(run(c.input()).ead())).as(c.source()).isEqualTo(Math.round(c.publishedEad()));
        }
    }
}
