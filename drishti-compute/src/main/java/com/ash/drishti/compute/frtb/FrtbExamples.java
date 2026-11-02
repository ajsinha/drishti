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
package com.ash.drishti.compute.frtb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The design's hand-built cases (SENSITIVITIES_AND_FRTB_SA.md section 13.1, FRTB_SA_GUIDE.md section 10) as engine inputs: small enough
 * to check with a calculator, used by the engine's unit tests, the workbook's recalculation tests and the playground's {@code example}
 * scope. Their factors form a small dictionary of their own.
 */
public final class FrtbExamples {

    private FrtbExamples() {}

    private static FactorDictionary.Factor f(String name, String cls, String type, String q, String curve, String tenor, String und, String bucket) {
        return new FactorDictionary.Factor(name, cls, type, q, curve, q, tenor, und, bucket, null, List.of());
    }

    /** The dictionary of the cases' factors. */
    public static FactorDictionary dictionary() {
        List<FactorDictionary.Factor> fs = new ArrayList<>(List.of(
                f("IR/CURVE/USD/SOFR/5Y", "GIRR", "CURVE", "USD", "SOFR", "5Y", null, "USD"),
                f("IR/CURVE/USD/SOFR/10Y", "GIRR", "CURVE", "USD", "SOFR", "10Y", null, "USD"),
                f("IR/CURVE/EUR/ESTR/2Y", "GIRR", "CURVE", "EUR", "ESTR", "2Y", null, "EUR"),
                f("IR/BASIS/USD", "GIRR", "BASIS", "USD", null, null, null, "USD"),
                f("INF/USCPI/5Y", "GIRR", "INFLATION", "USD", "USCPI", "5Y", null, "USD"),
                f("IR/BASIS/EUR", "GIRR", "BASIS", "EUR", null, null, null, "EUR"),
                f("INF/HICPXT/5Y", "GIRR", "INFLATION", "EUR", "HICPXT", "5Y", null, "EUR"),
                f("IR/CURVE/EUR/ESTR/5Y", "GIRR", "CURVE", "EUR", "ESTR", "5Y", null, "EUR"),
                f("EQ/SPOT/SPX", "EQ", "SPOT", "SPX", null, null, null, "12"),
                f("EQ/SPOT/EQ-A", "EQ", "SPOT", "EQ-A", null, null, null, "5"),
                f("EQ/SPOT/EQ-B", "EQ", "SPOT", "EQ-B", null, null, null, "5")));
        return new FactorDictionary(fs, "examples");
    }

    private static FrtbInput.Element el(FactorDictionary d, int pos, String factor, String measure, double v) {
        return new FrtbInput.Element(pos, d.resolve(factor), measure, v, Double.NaN, Double.NaN);
    }

    private static List<FrtbInput.Position> positions(int n) {
        List<FrtbInput.Position> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new FrtbInput.Position("CASE-POS-" + (i + 1), "EXAMPLE", Map.of("DESK", i % 2 == 0 ? "DESK-A" : "DESK-B")));
        }
        return out;
    }

    /** Case 1: USD DV01 5Y −1,000 and 10Y −500. */
    public static FrtbInput case1() {
        FactorDictionary d = dictionary();
        return new FrtbInput(positions(2), List.of(el(d, 0, "IR/CURVE/USD/SOFR/5Y", "DV01", -1000), el(d, 1, "IR/CURVE/USD/SOFR/10Y", "DV01", -500)),
                "USD", null, null);
    }

    /** Case 2: case 1 plus EUR DV01 2Y +800. */
    public static FrtbInput case2() {
        FactorDictionary d = dictionary();
        return new FrtbInput(positions(3), List.of(el(d, 0, "IR/CURVE/USD/SOFR/5Y", "DV01", -1000), el(d, 1, "IR/CURVE/USD/SOFR/10Y", "DV01", -500),
                el(d, 2, "IR/CURVE/EUR/ESTR/2Y", "DV01", 800)), "USD", null, null);
    }

    /** Case 3: an equity index (bucket 12), DELTA 10,000 per 1%, CURVATURE_UP 120,000 and CURVATURE_DOWN −180,000. */
    public static FrtbInput case3() {
        FactorDictionary d = dictionary();
        return new FrtbInput(positions(1), List.of(el(d, 0, "EQ/SPOT/SPX", "DELTA", 10_000), el(d, 0, "EQ/SPOT/SPX", "CURVATURE_UP", 120_000),
                el(d, 0, "EQ/SPOT/SPX", "CURVATURE_DOWN", -180_000)), "USD", null, null);
    }

    /** Case 4: the DRC positions (a BBB bond and a bought CDS on one obligor, a BB obligor net short). */
    public static List<FrtbInput.DrcPosition> case4() {
        return List.of(new FrtbInput.DrcPosition("BOND-1", 0, "OBLIGOR-X", "CORPORATE", "SENIOR", "BBB", 10_000_000, 9_800_000, 2, Double.NaN, false),
                new FrtbInput.DrcPosition("CDS-1", 1, "OBLIGOR-X", "CORPORATE", "SENIOR", "BBB", -4_000_000, -4_000_000 + 50_000, 0.5, Double.NaN, false),
                new FrtbInput.DrcPosition("BOND-2", 2, "OBLIGOR-Y", "CORPORATE", "SENIOR", "BB", -2_000_000 / 0.75, -2_000_000 / 0.75, 1, Double.NaN,
                        false));
    }

    /** Case 5: an FX barrier option (other residual risk) and a weather derivative (exotic). */
    public static List<FrtbInput.RraoItem> case5() {
        return List.of(new FrtbInput.RraoItem("FXB-1", 0, "FX_BARRIER", null, 50_000_000, false, false),
                new FrtbInput.RraoItem("WTH-1", 1, "WEATHER_HDD", null, 10_000_000, false, false));
    }

    /** Cases 1 to 5 together: case 2's GIRR, case 3's equity curvature, case 4's DRC and case 5's RRAO. */
    public static FrtbInput together() {
        FactorDictionary d = dictionary();
        return new FrtbInput(positions(3), List.of(el(d, 0, "IR/CURVE/USD/SOFR/5Y", "DV01", -1000), el(d, 1, "IR/CURVE/USD/SOFR/10Y", "DV01", -500),
                el(d, 2, "IR/CURVE/EUR/ESTR/2Y", "DV01", 800), el(d, 0, "EQ/SPOT/SPX", "DELTA", 10_000), el(d, 0, "EQ/SPOT/SPX", "CURVATURE_UP", 120_000),
                el(d, 0, "EQ/SPOT/SPX", "CURVATURE_DOWN", -180_000)), "USD", case4(), case5());
    }

    /**
     * Case 7: two GIRR buckets whose nets oppose more than their charges. In each currency a 5-year rate, the cross-currency basis and
     * inflation, every weighted sensitivity 100,000 (USD) or −100,000 (EUR); ρ 0.4 between the curve and inflation and 0 with the basis,
     * so K_b = 100,000·√3.8 below |S_b| = 300,000 and the radicand is negative in every scenario.
     */
    public static FrtbInput case7(FrtbParams p) {
        FactorDictionary d = dictionary();
        double curve = 100_000 / (p.girrRw[p.girrTenorIndex(5)] / p.girrDivisor) / 10_000;
        double other = 100_000 / p.girrInflationRw / 10_000;
        double basis = 100_000 / p.girrBasisRw / 10_000;
        return new FrtbInput(positions(2), List.of(el(d, 0, "IR/CURVE/USD/SOFR/5Y", "DV01", curve), el(d, 0, "IR/BASIS/USD", "DV01", basis),
                el(d, 0, "INF/USCPI/5Y", "IE01", other), el(d, 1, "IR/CURVE/EUR/ESTR/5Y", "DV01", -curve), el(d, 1, "IR/BASIS/EUR", "DV01", -basis),
                el(d, 1, "INF/HICPXT/5Y", "IE01", -other)), "USD", null, null);
    }

    /** Case 9: two names in equity bucket 5 with no delta, so CVR+ = −UP and CVR− = −DOWN: A 40,000 and −10,000; B −25,000 and 30,000. */
    public static FrtbInput case9() {
        FactorDictionary d = dictionary();
        return new FrtbInput(positions(2), List.of(el(d, 0, "EQ/SPOT/EQ-A", "CURVATURE_UP", -40_000), el(d, 0, "EQ/SPOT/EQ-A", "CURVATURE_DOWN", 10_000),
                el(d, 1, "EQ/SPOT/EQ-B", "CURVATURE_UP", 25_000), el(d, 1, "EQ/SPOT/EQ-B", "CURVATURE_DOWN", -30_000)), "USD", null, null);
    }

    /** Case 10: case 3's index sent with GAMMA −200 per (1%)² instead of the revaluations. */
    public static FrtbInput case10() {
        FactorDictionary d = dictionary();
        return new FrtbInput(positions(1), List.of(el(d, 0, "EQ/SPOT/SPX", "DELTA", 10_000), el(d, 0, "EQ/SPOT/SPX", "GAMMA", -200)), "USD", null, null);
    }

    /** Case 10 beside case 3: the same position with both the revaluations and the gamma (the curvature-source switch decides). */
    public static FrtbInput case3and10() {
        FactorDictionary d = dictionary();
        return new FrtbInput(positions(1), List.of(el(d, 0, "EQ/SPOT/SPX", "DELTA", 10_000), el(d, 0, "EQ/SPOT/SPX", "CURVATURE_UP", 120_000),
                el(d, 0, "EQ/SPOT/SPX", "CURVATURE_DOWN", -180_000), el(d, 0, "EQ/SPOT/SPX", "GAMMA", -200)), "USD", null, null);
    }
}
