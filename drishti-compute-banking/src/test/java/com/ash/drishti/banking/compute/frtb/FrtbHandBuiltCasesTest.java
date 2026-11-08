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
package com.ash.drishti.banking.compute.frtb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.banking.compute.simm.SimmMath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The hand-built cases of SENSITIVITIES_AND_FRTB_SA.md section 13.1 against the engine, to the cent, with the market-risk pack's own
 * parameter files. Cases 1 and 2 are written with MAR21.46's exact formula (the {@code girr-correlation} switch); the default, the
 * published table, is asserted beside them. Cases 6 and 11 are SIMM's aggregation formulas with toy parameters.
 */
class FrtbHandBuiltCasesTest {

    static final double CENT = 0.005;
    static byte[] sbm;
    static byte[] drc;
    static FrtbParams params;

    static Path capital() {
        Path p = Path.of("..", "config", "packs", "market-risk", "capital");
        return Files.isDirectory(p) ? p : Path.of("config", "packs", "market-risk", "capital");
    }

    @BeforeAll
    static void read() throws Exception {
        sbm = Files.readAllBytes(capital().resolve("frtb-sa-sbm.yaml"));
        drc = Files.readAllBytes(capital().resolve("frtb-sa-drc-rrao.yaml"));
        params = FrtbParams.parse(sbm, drc, Map.of());
    }

    static FrtbSa.Result run(FrtbInput in, FrtbChoices c) {
        return FrtbSa.run(in, FrtbParams.parse(sbm, drc, c.overrides()), c);
    }

    static FrtbChoices exact() {
        return FrtbChoices.defaults().with("girr-correlation", "exact");
    }

    @Test
    void case1GirrDeltaOneBucketTheThreeScenarios() {
        FrtbSa.Result r = run(FrtbExamples.case1(), exact());
        SbmModel.Risk r5 = r.sbm().model.risks.get(0);
        assertThat(r5.s).isEqualTo(-10_000_000.0);
        assertThat(r5.rw).isCloseTo(0.00777817, offset(1e-8));
        assertThat(r5.ws()).isCloseTo(-77_781.75, offset(CENT));
        assertThat(r.sbm().model.risks.get(1).ws()).isCloseTo(-38_890.87, offset(CENT));
        Sbm.Bucket usd = r.sbm().classCharge("GIRR", "DELTA").buckets.get(0);
        assertThat(usd.rho[0][1]).isCloseTo(0.970446, offset(1e-6));
        assertThat(usd.k[0]).isCloseTo(115_129.89, offset(CENT));
        assertThat(usd.k[1]).isCloseTo(115_903.82, offset(CENT));
        assertThat(usd.k[2]).isCloseTo(116_672.62, offset(CENT));
        // the default: the published table's 0.970
        FrtbSa.Result t = run(FrtbExamples.case1(), FrtbChoices.defaults());
        Sbm.Bucket tb = t.sbm().classCharge("GIRR", "DELTA").buckets.get(0);
        assertThat(tb.rho[0][1]).isEqualTo(0.970);
        assertThat(tb.k[0]).isCloseTo(115_106.47, offset(CENT));
        assertThat(tb.k[1]).isCloseTo(115_892.19, offset(CENT));
        assertThat(tb.k[2]).isCloseTo(116_672.62, offset(CENT));
    }

    @Test
    void case2TwoBucketsAHedgeTheLowScenarioBinds() {
        FrtbSa.Result r = run(FrtbExamples.case2(), exact());
        Sbm.ClassCharge g = r.sbm().classCharge("GIRR", "DELTA");
        assertThat(g.buckets.get(0).bucket).isEqualTo("EUR");
        assertThat(g.buckets.get(0).k[1]).isCloseTo(73_539.11, offset(CENT));
        assertThat(g.buckets.get(1).net).isCloseTo(-116_672.62, offset(CENT));
        assertThat(g.charge[0]).isCloseTo(110_579.79, offset(CENT));
        assertThat(g.charge[1]).isCloseTo(101_300.03, offset(CENT));
        assertThat(g.charge[2]).isCloseTo(91_079.64, offset(CENT));
        assertThat(r.sbm().binding).isZero();
        assertThat(r.sbm().sbm()).isCloseTo(110_579.79, offset(CENT));
        FrtbSa.Result t = run(FrtbExamples.case2(), FrtbChoices.defaults());
        assertThat(t.sbm().total[0]).isCloseTo(110_555.42, offset(CENT));
        assertThat(t.sbm().total[1]).isCloseTo(101_286.72, offset(CENT));
        assertThat(t.sbm().total[2]).isCloseTo(91_079.64, offset(CENT));
    }

    @Test
    void case3CurvatureTheTieGoesDown() {
        FrtbSa.Result r = run(FrtbExamples.case3(), FrtbChoices.defaults());
        SbmModel.Curv c = r.sbm().model.curvs.get(0);
        assertThat(c.deltaPart).isCloseTo(150_000, offset(CENT));
        assertThat(c.cvrUp).isCloseTo(30_000, offset(CENT));
        assertThat(c.cvrDown).isCloseTo(30_000, offset(CENT));
        Sbm.ClassCharge cc = r.sbm().classCharge("EQ", "CURVATURE");
        for (int s = 0; s < 3; s++) {
            assertThat(cc.buckets.get(0).side[s]).isEqualTo("down");
            assertThat(cc.charge[s]).isCloseTo(30_000, offset(CENT));
        }
        assertThat(c.estimated).isFalse();
    }

    @Test
    void case4DrcNettingAndTheHedgeBenefitRatio() {
        Drc.Result d = Drc.compute(FrtbExamples.case4(), params);
        assertThat(d.lines().get(0).scaled()).isCloseTo(7_300_000, offset(CENT));
        assertThat(d.lines().get(1).scaled()).isCloseTo(-1_475_000, offset(CENT));
        assertThat(d.obligors().get(0).netLong()).isCloseTo(5_825_000, offset(CENT));
        assertThat(d.obligors().get(1).netShort()).isCloseTo(-2_000_000, offset(CENT));
        assertThat(d.buckets().get(0).hbr()).isCloseTo(0.744409, offset(1e-6));
        assertThat(d.total()).isCloseTo(126_177.32, offset(CENT));
        double sum = d.contributionsByLine().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(d.total(), offset(1e-6));
    }

    @Test
    void drcNettingFollowsSeniorityAndMissingInputIsNeverZero() {
        // a senior short does not offset a junior long; a junior short offsets a senior long
        List<FrtbInput.DrcPosition> p = List.of(
                new FrtbInput.DrcPosition("L-JUNIOR", -1, "O", "CORPORATE", "NON_SENIOR", "A", 1_000_000, 1_000_000, 1, Double.NaN, false),
                new FrtbInput.DrcPosition("S-SENIOR", -1, "O", "CORPORATE", "SENIOR", "A", -1_000_000, -1_000_000, 1, Double.NaN, false));
        Drc.Result d = Drc.compute(p, params);
        assertThat(d.obligors().get(0).netLong()).isCloseTo(1_000_000, offset(CENT));
        assertThat(d.obligors().get(0).netShort()).isCloseTo(-750_000, offset(CENT));
        List<FrtbInput.DrcPosition> q = List.of(
                new FrtbInput.DrcPosition("L-SENIOR", -1, "O", "CORPORATE", "SENIOR", "A", 1_000_000, 1_000_000, 1, Double.NaN, false),
                new FrtbInput.DrcPosition("S-JUNIOR", -1, "O", "CORPORATE", "NON_SENIOR", "A", -500_000, -500_000, 1, Double.NaN, false));
        Drc.Result e = Drc.compute(q, params);
        assertThat(e.obligors().get(0).netLong()).isCloseTo(250_000, offset(CENT));
        assertThat(e.obligors().get(0).netShort()).isCloseTo(0, offset(CENT));
        FrtbSa.Result none = run(FrtbExamples.case1(), FrtbChoices.defaults());
        assertThat(none.drc().delivered()).isFalse();
        assertThat(none.drc().total()).isNaN();
        assertThat(none.complete()).isFalse();
    }

    @Test
    void case5Rrao() {
        Rrao.Result r = Rrao.compute(FrtbExamples.case5(), params);
        assertThat(r.other()).isCloseTo(50_000, offset(CENT));
        assertThat(r.exotic()).isCloseTo(100_000, offset(CENT));
        assertThat(r.total()).isCloseTo(150_000, offset(CENT));
        Rrao.Result x = Rrao.compute(List.of(new FrtbInput.RraoItem("A", -1, "FX_BARRIER", null, 1e6, false, true),
                new FrtbInput.RraoItem("B", -1, "WEATHER_HDD", null, 1e6, false, true), new FrtbInput.RraoItem("C", -1, "WEATHER_HDD", null, 1e6, true, false)),
                params);
        assertThat(x.total()).isCloseTo(10_000, offset(CENT));      // listed: out of the other part only; back-to-back: out entirely
    }

    @Test
    void casesOneToFiveTogether() {
        FrtbSa.Result r = run(FrtbExamples.together(), exact());
        // GIRR delta (case 2) + the index's own delta charge (WS 0.15 × 1,000,000 = 150,000) + its curvature (case 3)
        assertThat(r.sbm().charge("EQ", "DELTA", 0)).isCloseTo(150_000, offset(CENT));
        assertThat(r.sbm().charge("EQ", "CURVATURE", 0)).isCloseTo(30_000, offset(CENT));
        assertThat(r.sbm().total[0]).isCloseTo(290_579.79, offset(CENT));
        assertThat(r.sbm().total[1]).isCloseTo(281_300.03, offset(CENT));
        assertThat(r.sbm().total[2]).isCloseTo(271_079.64, offset(CENT));
        assertThat(Sbm.SCENARIOS[r.sbm().binding]).isEqualTo("low");
        // a class's total is its measures added (the screen's Total column comes from here), and the classes' totals add up to SBM
        assertThat(r.sbm().classTotal("EQ", 0)).isCloseTo(180_000, offset(CENT));
        assertThat(r.sbm().classes()).contains("GIRR", "EQ");
        for (int sc = 0; sc < 3; sc++) {
            final int s = sc;
            assertThat(r.sbm().classes().stream().mapToDouble(c -> r.sbm().classTotal(c, s)).sum()).isCloseTo(r.sbm().total[s], offset(CENT));
        }
        assertThat(r.sbm().classTotal("FX", 0)).isZero();
        assertThat(r.total()).isCloseTo(566_757.11, offset(CENT));
        double sum = Arrays.stream(r.contributions()).sum();
        assertThat(sum).isCloseTo(r.total(), offset(CENT));
        Map<String, Double> byDesk = r.contributionsBy("DESK");
        assertThat(byDesk.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(r.total(), offset(CENT));
    }

    @Test
    void casesOneToFiveTogetherWithTheDefaultPublishedTable() {
        // decision 10: MAR21.46's published table (0.970) by default; the exact formula (0.970446) is the switch, asserted above
        FrtbSa.Result r = run(FrtbExamples.together(), FrtbChoices.defaults());
        assertThat(r.sbm().charge("GIRR", "DELTA", 0)).isCloseTo(110_555.42, offset(CENT));
        assertThat(r.sbm().charge("EQ", "DELTA", 0)).isCloseTo(150_000, offset(CENT));      // case 3's index delta, kept explicit
        assertThat(r.sbm().charge("EQ", "CURVATURE", 0)).isCloseTo(30_000, offset(CENT));
        assertThat(r.sbm().total[0]).isCloseTo(290_555.42, offset(CENT));
        assertThat(r.sbm().total[1]).isCloseTo(281_286.72, offset(CENT));
        assertThat(r.sbm().total[2]).isCloseTo(271_079.64, offset(CENT));
        assertThat(r.total()).isCloseTo(566_732.73, offset(CENT));            // the parts rounded first add to .74; the engine adds them unrounded
        // without the index's own delta charge (the design's curvature-only sum of cases 2 and 3): 140,555.42 and 416,732.73
        assertThat(r.sbm().total[0] - r.sbm().charge("EQ", "DELTA", 0)).isCloseTo(140_555.42, offset(CENT));
        assertThat(r.total() - r.sbm().charge("EQ", "DELTA", 0)).isCloseTo(416_732.73, offset(CENT));
    }

    @Test
    void case6SimmAggregationWithToyParameters() {
        double k = SimmMath.bucketK(new double[] {50 * -1000, 60 * 400}, new double[] {1, 1}, new double[][] {{1, 0.8}, {0.8, 1}}, true);
        assertThat(k).isCloseTo(34_000, offset(CENT));
        assertThat(SimmMath.productClass(new double[] {50_000, 30_000}, new double[][] {{1, 0.3}, {0.3, 1}})).isCloseTo(65_574.39, offset(CENT));
    }

    @Test
    void case7TheAlternativeSpecification() {
        FrtbSa.Result r = run(FrtbExamples.case7(params), FrtbChoices.defaults());
        Sbm.ClassCharge g = r.sbm().classCharge("GIRR", "DELTA");
        // low: ρ 0.4 becomes 0.3 and γ 0.375, K_b/|S_b| = √3.6/3 = 0.632 above √0.375 = 0.612: the plain formula holds
        assertThat(g.alternative[0]).isFalse();
        for (int s = 1; s < 3; s++) {
            assertThat(g.alternative[s]).as("scenario " + s).isTrue();
            double kEur = g.buckets.get(0).k[s];
            double kUsd = g.buckets.get(1).k[s];
            assertThat(g.buckets.get(1).net).isCloseTo(300_000, offset(CENT));
            assertThat(g.buckets.get(0).s[s]).isCloseTo(-kEur, offset(1e-9));
            assertThat(g.buckets.get(1).s[s]).isCloseTo(kUsd, offset(1e-9));
            double gamma = params.scenarios.apply(s, 0.5);
            double plain = kEur * kEur + kUsd * kUsd + 2 * gamma * 300_000 * -300_000;
            assertThat(plain).isNegative();
            assertThat(g.charge[s]).isCloseTo(Math.sqrt(kEur * kEur + kUsd * kUsd - 2 * gamma * kEur * kUsd), offset(CENT));
        }
        assertThat(g.buckets.get(1).k[1]).isCloseTo(100_000 * Math.sqrt(3.8), offset(CENT));
        assertThat(g.charge[1]).isCloseTo(100_000 * Math.sqrt(3.8), offset(CENT));   // 2K² − K² = K²
        assertThat(Arrays.stream(r.contributions()).sum()).isCloseTo(r.total(), offset(CENT));
    }

    @Test
    void case9CurvatureTheTwoSidesAndTheOrderOfSquaring() {
        FrtbSa.Result r = run(FrtbExamples.case9(), FrtbChoices.defaults());
        Sbm.Bucket b = r.sbm().classCharge("EQ", "CURVATURE").buckets.get(0);
        assertThat(b.rho[0][1]).isCloseTo(0.0625, offset(1e-12));
        double[] kUp = {38_810.44, 38_405.73, 37_996.71};
        double[] kDown = {29_527.53, 29_368.35, 29_208.30};
        for (int s = 0; s < 3; s++) {
            assertThat(b.kUp[s]).isCloseTo(kUp[s], offset(CENT));
            assertThat(b.kDown[s]).isCloseTo(kDown[s], offset(CENT));
            assertThat(b.side[s]).isEqualTo("up");
            assertThat(b.k[s]).isCloseTo(kUp[s], offset(CENT));
            assertThat(b.s[s]).isCloseTo(15_000, offset(CENT));
        }
        // scaling ρ before squaring would give 39,111.22 (low) and 37,479.16 (high): not what the engine does
        assertThat(b.kUp[0]).isNotCloseTo(39_111.22, offset(1.0));
        assertThat(b.kUp[2]).isNotCloseTo(37_479.16, offset(1.0));
        assertThat(Arrays.stream(r.contributions()).sum()).isCloseTo(r.sbm().sbm(), offset(CENT));
    }

    @Test
    void case10CurvatureEstimatedFromGamma() {
        FrtbSa.Result r = run(FrtbExamples.case10(), FrtbChoices.defaults());
        SbmModel.Curv c = r.sbm().model.curvs.get(0);
        assertThat(c.h).isCloseTo(15, offset(1e-12));
        assertThat(c.cvrUp).isCloseTo(22_500, offset(CENT));
        assertThat(c.cvrDown).isCloseTo(22_500, offset(CENT));
        assertThat(c.estimated).isTrue();
        // both delivered: revaluation by default (30,000), the estimate with the switch (22,500); the comparison says where
        FrtbSa.Result reval = run(FrtbExamples.case3and10(), FrtbChoices.defaults());
        FrtbSa.Result gamma = run(FrtbExamples.case3and10(), FrtbChoices.defaults().with("curvature-source", "gamma"));
        assertThat(reval.sbm().charge("EQ", "CURVATURE", 1)).isCloseTo(30_000, offset(CENT));
        assertThat(gamma.sbm().charge("EQ", "CURVATURE", 1)).isCloseTo(22_500, offset(CENT));
        FrtbCompare.Diff d = FrtbCompare.compare(reval, gamma, "curvature-source: revaluation → gamma");
        assertThat(d.difference()).isCloseTo(-7_500, offset(CENT));
        assertThat(d.words()).contains("lowers the total by 7,500.00", "curvature risk");
    }

    @Test
    void case11SimmConcentrationPerIssuer() {
        double crX = SimmMath.concentration(-4_000, 1_000);
        double crY = SimmMath.concentration(1_000, 1_000);
        assertThat(crX).isEqualTo(2);
        assertThat(crY).isEqualTo(1);
        double k = SimmMath.bucketK(new double[] {100 * -4_000 * crX, 100 * 1_000 * crY}, new double[] {crX, crY}, new double[][] {{1, 0.4}, {0.4, 1}},
                false);
        assertThat(k).isCloseTo(786_129.76, offset(CENT));
    }

    @Test
    void theGirrCorrelationSwitchIsExplainedByStepAndPair() {
        // case 2 binds on the low scenario, where the two correlations differ (case 1 alone binds on high, where both are 1)
        FrtbSa.Result base = run(FrtbExamples.case2(), FrtbChoices.defaults());
        FrtbSa.Result alt = run(FrtbExamples.case2(), exact());
        assertThat(alt.total() - base.total()).isCloseTo(110_579.79 - 110_555.42, offset(0.01));
        FrtbCompare.Diff d = FrtbCompare.compare(base, alt, "girr-correlation: table → exact");
        assertThat(d.lines()).hasSize(1);
        assertThat(d.lines().get(0).bucket()).isEqualTo("USD");
        assertThat(d.lines().get(0).step()).contains("correlations within the bucket");
        assertThat(d.lines().get(0).detail()).contains("5Y", "10Y");
        assertThat(d.words()).contains("raises the total", "GIRR delta");
    }

    @Test
    void anOverrideChangesTheFigureAndABadFileIsRefusedWithItsList() {
        FrtbChoices o = new FrtbChoices(null, null, null, null, null, Map.of("GIRR.delta.across-buckets", "0.6"));
        assertThat(o.regulatory()).isFalse();
        FrtbSa.Result r = run(FrtbExamples.case2(), o);
        assertThat(r.params().girrGamma).isEqualTo(0.6);
        assertThat(r.params().hash).isEqualTo(params.hash);                     // the file is the same; the override is recorded beside it
        assertThat(r.params().overrides).containsEntry("GIRR.delta.across-buckets", "0.6");
        assertThatThrownBy(() -> FrtbParams.parse(sbm, drc, Map.of("GIRR.delta.across-buckets", "1.7"))).hasMessageContaining("across-buckets");
        assertThatThrownBy(() -> FrtbParams.parse(sbm, drc, Map.of("GIRR.nothing", "1"))).hasMessageContaining("no such");
        String broken = new String(sbm, java.nio.charset.StandardCharsets.UTF_8).replace("theta: 0.03", "theta: -1").replace("floor: 0.40", "floor: x");
        assertThatThrownBy(() -> FrtbParams.parse(broken.getBytes(java.nio.charset.StandardCharsets.UTF_8), drc, Map.of()))
                .hasMessageContaining("theta").hasMessageContaining("floor");
        assertThat(params.hash).hasSize(64);
    }

    @Test
    void fxCurvatureDivisorAndUnknownFactorsAreRefusedAndCounted() {
        FactorDictionary d = new FactorDictionary(List.of(
                new FactorDictionary.Factor("FX/SPOT/EUR", "FX", "SPOT", "EUR", null, "EURUSD", null, null, "EUR", null, List.of("MX:FX:EUR")),
                new FactorDictionary.Factor("FX/SPOT/JPY", "FX", "SPOT", "JPY", null, "JPYUSD", null, null, "JPY", null, List.of())), "t");
        assertThat(d.resolve("MX:FX:EUR").name()).isEqualTo("FX/SPOT/EUR");
        List<FrtbInput.Element> els = List.of(new FrtbInput.Element(0, d.resolve("FX/SPOT/EUR"), "CURVATURE_UP", -3_000, 0.15, Double.NaN),
                new FrtbInput.Element(0, d.resolve("FX/SPOT/EUR"), "CURVATURE_DOWN", -3_000, 0.15, Double.NaN),
                new FrtbInput.Element(0, d.resolve("FX/SPOT/JPY"), "CURVATURE_UP", -1_500, 0.15, Double.NaN),
                new FrtbInput.Element(0, d.resolve("FX/SPOT/JPY"), "CURVATURE_DOWN", -1_500, 0.15, Double.NaN),
                new FrtbInput.Element(1, d.resolve("FX/SPOT/EUR"), "CURVATURE_UP", -600, 0.15, Double.NaN),
                new FrtbInput.Element(1, d.resolve("FX/SPOT/EUR"), "CURVATURE_DOWN", -600, 0.15, Double.NaN),
                new FrtbInput.Element(1, d.resolve("NOT/A/FACTOR"), "DELTA", 1, Double.NaN, Double.NaN));
        FrtbInput in = new FrtbInput(List.of(new FrtbInput.Position("EURJPY-OPT", "FX_OPTION", Map.of()), new FrtbInput.Position("EURUSD-OPT", "FX_OPTION",
                Map.of())), els, "USD", null, null);
        FrtbSa.Result def = run(in, FrtbChoices.defaults());
        assertThat(def.sbm().model.refusals).hasSize(1);
        assertThat(def.sbm().model.refusals.get(0).reason()).contains("not in the dictionary");
        SbmModel.Curv eur = def.sbm().model.curvs.stream().filter(c -> c.name.equals("EUR")).findFirst().orElseThrow();
        assertThat(eur.cvrUp).isCloseTo(3_000 / 1.5 + 600, offset(CENT));      // the cross divided, the option on the reporting currency not
        FrtbSa.Result off = run(in, FrtbChoices.defaults().with("fx-curvature-divisor", "off"));
        assertThat(off.sbm().model.curvs.stream().filter(c -> c.name.equals("EUR")).findFirst().orElseThrow().cvrUp).isCloseTo(3_600, offset(CENT));
        FrtbSa.Result all = run(in, FrtbChoices.defaults().with("fx-curvature-divisor", "all"));
        assertThat(all.sbm().model.curvs.stream().filter(c -> c.name.equals("EUR")).findFirst().orElseThrow().cvrUp).isCloseTo(2_400, offset(CENT));
        assertThatThrownBy(() -> new FactorDictionary(List.of(
                new FactorDictionary.Factor("A", "FX", "SPOT", "EUR", null, null, null, null, "EUR", null, List.of("X")),
                new FactorDictionary.Factor("B", "FX", "SPOT", "JPY", null, null, null, null, "JPY", null, List.of("X"))), "t"))
                .hasMessageContaining("the source name X maps to A and B");
    }

    @Test
    void everyAlternativeOfTheDefaultsIsOneSwitchAway() {
        List<FrtbChoices> alts = FrtbChoices.defaults().alternatives();
        assertThat(alts).hasSize(1 + 1 + 2 + 1 + 3);
        assertThat(alts).allSatisfy(c -> assertThat(c.changed()).hasSize(1));
        assertThatThrownBy(() -> FrtbChoices.defaults().with("scenario", "worst")).hasMessageContaining("scenario");
    }
}
