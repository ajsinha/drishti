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
package com.ash.drishti.banking.compute.simm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The SIMM engine against the hand-built cases (docs/guides/FRTB_SA_GUIDE.md section 13.1 cases 6 and 11, docs/contracts/SIMM.md section
 * 15, the explanation's swaption) to the cent, with the TOY parameter file src/test/resources/simm/simm-toy.yaml: round illustrative
 * numbers, not ISDA's. The shipped structure (docs/architecture/capital-parameters/simm.yaml, every number empty) is refused with DRS-5333.
 */
class SimmHandBuiltCasesTest {

    static final double CENT = 0.005;
    static byte[] toy;
    static SimmParams params;

    @BeforeAll
    static void load() throws IOException {
        toy = Files.readAllBytes(Path.of("src/test/resources/simm/simm-toy.yaml"));
        params = SimmParams.parse(toy, Map.of());
    }

    static Simm.Result run(SimmInput in) {
        return Simm.run(in, params, SimmChoices.defaults(), false);
    }

    @Test
    void theToyFileIsCompleteAndTheShippedStructureIsRefused() throws IOException {
        assertThat(params.complete()).isTrue();
        assertThat(params.version).isEqualTo("TOY-1");
        assertThat(params.hash).hasSize(64);
        byte[] empty = Files.readAllBytes(Path.of("../../../docs/architecture/capital-parameters/simm.yaml"));
        assertThatThrownBy(() -> SimmParams.parse(empty, Map.of())).hasMessageStartingWith("DRS-5333 SIMM (no version) is refused")
                .hasMessageContaining("risk-class-correlation.InterestRate.CreditQ is missing");
        SimmParams lenient = SimmParams.lenient(empty);
        assertThat(lenient.complete()).isFalse();
        assertThat(params.values().keySet()).containsAll(lenient.values().keySet());   // the same structure (the groups are the licence's)
        assertThat(lenient.v("InterestRate.across-currencies")).isNaN();
    }

    @Test
    void case6TheBucketAndTheProductClass() {
        Simm.Result b = run(SimmExamples.case6Bucket());
        Simm.Charge ir = b.charge("RatesFX", "InterestRate", Simm.DELTA);
        assertThat(ir.buckets).hasSize(1);
        assertThat(ir.buckets.get(0).k).isCloseTo(34_000, offset(CENT));
        assertThat(ir.margin).isCloseTo(34_000, offset(CENT));
        assertThat(b.simm()).isCloseTo(34_000, offset(CENT));
        Simm.Result p = run(SimmExamples.case6Product());
        assertThat(p.charge("RatesFX", "InterestRate", Simm.DELTA).margin).isCloseTo(50_000, offset(CENT));
        assertThat(p.charge("RatesFX", "CreditQ", Simm.DELTA).margin).isCloseTo(30_000, offset(CENT));
        assertThat(p.product("RatesFX").simm()).isCloseTo(65_574.39, offset(CENT));
        assertThat(SimmMath.productClass(new double[] {50_000, 30_000}, new double[][] {{1, 0.3}, {0.3, 1}})).isCloseTo(65_574.39, offset(CENT));
    }

    @Test
    void case11ConcentrationPerIssuerNotPerBucket() {
        Simm.Result r = run(SimmExamples.case11());
        Simm.Charge c = r.charge("Credit", "CreditQ", Simm.DELTA);
        assertThat(r.groups()).hasSize(2);
        assertThat(r.groups().stream().filter(g -> g.group.equals("ISS-X/SENIOR")).findFirst().orElseThrow().cr).isEqualTo(2.0);
        assertThat(r.groups().stream().filter(g -> g.group.equals("ISS-Y/SENIOR")).findFirst().orElseThrow().cr).isEqualTo(1.0);
        assertThat(c.buckets.get(0).f[0][1]).isEqualTo(0.5);
        assertThat(c.buckets.get(0).k).isCloseTo(786_129.76, offset(CENT));
        assertThat(c.margin).isCloseTo(786_129.76, offset(CENT));
        // a bucket-level factor (|−3,000| against 1,000 for both, f = 1) gives another figure
        double crBucket = SimmMath.concentration(-3_000, 1_000);
        double other = SimmMath.bucketK(new double[] {-400_000 * crBucket, 100_000 * crBucket}, new double[] {crBucket, crBucket},
                new double[][] {{1, 0.4}, {0.4, 1}}, false);
        assertThat(other).isNotCloseTo(786_129.76, offset(1.0));
    }

    @Test
    void theWorkedNettingSetOfSimmMdSection15() {
        Simm.Result r = run(SimmExamples.worked());
        Simm.Charge ir = r.charge("RatesFX", "InterestRate", Simm.DELTA);
        assertThat(ir.buckets.get(0).label).isEqualTo("EUR");
        assertThat(ir.buckets.get(1).k).isCloseTo(236_634.74, offset(CENT));
        assertThat(ir.buckets.get(1).s).isCloseTo(194_000, offset(CENT));
        assertThat(ir.margin).isCloseTo(357_483.43, offset(CENT));
        assertThat(r.charge("RatesFX", "FX", Simm.DELTA).margin).isCloseTo(160_000, offset(CENT));
        assertThat(r.simm()).isCloseTo(426_606.46, offset(CENT));
        Simm.Result post = Simm.run(SimmExamples.worked(), params, SimmChoices.defaults(), true);
        assertThat(post.total()).isCloseTo(r.total(), offset(1e-6));                 // delta only: collect = post
        // a USD threshold of 3,000 per bp: CR_USD = √(4,000 / 3,000), g(USD, EUR) = 1 / CR_USD
        SimmParams usd = SimmParams.parse(toy, Map.of("InterestRate.concentration-thresholds.delta.1", "3000"));
        Simm.Result c = Simm.run(SimmExamples.worked(), usd, SimmChoices.defaults(), false);
        Simm.Charge irc = c.charge("RatesFX", "InterestRate", Simm.DELTA);
        assertThat(irc.buckets.get(1).cr).isCloseTo(1.154701, offset(1e-6));
        assertThat(irc.buckets.get(1).k).isCloseTo(273_242.26, offset(CENT));
        assertThat(irc.buckets.get(1).s).isCloseTo(224_011.90, offset(CENT));
        assertThat(irc.g[0][1]).isCloseTo(0.866025, offset(1e-6));
        assertThat(irc.margin).isCloseTo(382_700.58, offset(CENT));
        assertThat(c.simm()).isCloseTo(450_195.27, offset(CENT));
        assertThat(c.regulatory()).isFalse();
        // the concentration switch off gives back the figure without concentration
        Simm.Result off = Simm.run(SimmExamples.worked(), usd, new SimmChoices("off", null, Map.of()), false);
        assertThat(off.simm()).isCloseTo(426_606.46, offset(CENT));
    }

    @Test
    void theSwaptionsVegaAndCurvatureBothWays() {
        Simm.Result r = run(SimmExamples.swaption());
        Simm.Charge v = r.charge("RatesFX", "InterestRate", Simm.VEGA);
        assertThat(r.risks().get(v.buckets.get(0).members[0]).s).isCloseTo(40_000, offset(1e-6));
        assertThat(v.margin).isCloseTo(12_000, offset(CENT));                        // toy vega RW 0.3 × 40,000
        Simm.Charge c = r.charge("RatesFX", "InterestRate", Simm.CURVATURE);
        double cvr = 0.5 * 14 / 365 * 40_000;
        assertThat(c.sumCvr).isCloseTo(cvr, offset(1e-9));
        assertThat(c.theta).isZero();
        assertThat(c.lambda).isCloseTo(5.634897, offset(1e-6));
        assertThat(c.nonResidual).isCloseTo(5_089.78, offset(CENT));
        assertThat(c.scale).isCloseTo(1 / 0.64, offset(1e-12));                   // toy HVR_IR 0.8: × HVR⁻²
        assertThat(c.margin).isCloseTo(5_089.78 / 0.64, offset(0.01));
        Simm.Result post = Simm.run(SimmExamples.swaption(), params, SimmChoices.defaults(), true);
        Simm.Charge pc = post.charge("RatesFX", "InterestRate", Simm.CURVATURE);
        assertThat(pc.theta).isEqualTo(-1.0);
        assertThat(pc.lambda).isCloseTo(1.0, offset(1e-12));
        assertThat(pc.margin).isCloseTo(0, offset(1e-9));
        assertThat(post.total()).isLessThan(r.total());                              // curvature falls on one side only
        Simm.Result noHvr = Simm.run(SimmExamples.swaption(), params, new SimmChoices(null, "off", Map.of()), false);
        assertThat(noHvr.charge("RatesFX", "InterestRate", Simm.CURVATURE).margin).isCloseTo(5_089.78, offset(CENT));
    }

    @Test
    void productClassesAddWithoutCorrelationAndTheAddOnsComeOnTop() {
        SimmInput in = SimmExamples.together();
        Simm.Result r = run(in);
        assertThat(r.products().stream().map(Simm.ProductIm::productClass).toList()).containsExactly("RatesFX", "Credit");
        assertThat(r.simm()).isCloseTo(r.product("RatesFX").simm() + r.product("Credit").simm(), offset(1e-9));
        SimmInput withAddOns = new SimmInput(in.trades(), in.elements(), "USD", new SimmInput.AddOns(1_000_000, Map.of("Credit", 1.5),
                Map.of("Variance swap", 0.01), List.of(new SimmInput.Line("C11-1", "Variance swap", 25_000_000))));
        Simm.Result a = run(withAddOns);
        assertThat(a.addOn().total()).isCloseTo(1_000_000 + 250_000 + 0.5 * r.product("Credit").simm(), offset(CENT));
        assertThat(a.total()).isCloseTo(a.simm() + a.addOn().total(), offset(1e-9));
        assertThatThrownBy(() -> run(new SimmInput(in.trades(), in.elements(), "USD", new SimmInput.AddOns(0, Map.of("Credit", 0.9), Map.of(), List.of()))))
                .hasMessageContaining("at least 1");
    }

    @Test
    void anOverrideMustNameAParameterAndRefusalsAreCounted() {
        assertThatThrownBy(() -> SimmParams.parse(toy, Map.of("InterestRate.no-such", "1"))).hasMessageContaining("no such SIMM parameter");
        SimmInput in = new SimmInput(List.of(new SimmInput.Trade("T", "RatesFX", Map.of())), List.of(
                new SimmInput.Element(0, new SimmInput.Factor("EQ/REPO/X", "Equity", "REPO", "1", "X", null, null, null), "DELTA", 10, Double.NaN),
                new SimmInput.Element(0, new SimmInput.Factor("IR/CURVE/USD/OIS/7Y", "InterestRate", "CURVE", "1", "USD", "7y", "OIS", null), "DV01", 10, Double.NaN),
                new SimmInput.Element(0, null, "DV01", 10, Double.NaN),
                new SimmInput.Element(0, SimmExamples.fxSpot("USD"), "DELTA", 10, Double.NaN),
                new SimmInput.Element(0, SimmExamples.irCurve("USD", "5y", "1"), "CURVATURE_UP", 10, Double.NaN)), "USD", SimmInput.AddOns.NONE);
        Simm.Result r = run(in);
        assertThat(r.refusals()).hasSize(3);
        assertThat(r.refusals()).allMatch(x -> x.reason().startsWith("DRS-5331"));
        assertThat(r.notes()).hasSize(2);
        assertThat(r.total()).isZero();
    }
}
