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
package com.ash.drishti.compute.simm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The inputs of the hand-built SIMM cases (SIMM_GUIDE.md section 10, cases 6 and 11, docs/contracts/SIMM.md section 15):
 * sensitivities only. The figures in the documents come from a toy parameter set (not ISDA's), so the cases prove the formulas, never the
 * licensed numbers; with a bank's licensed file installed the same inputs give that version's margin. The {@code EXAMPLE} scope of the
 * SIMM API and playground runs {@link #worked()} with the version asked for.
 */
public final class SimmExamples {

    private SimmExamples() {}

    public static SimmInput.Factor irCurve(String ccy, String tenor, String bucket) {
        return new SimmInput.Factor("IR/CURVE/" + ccy + "/OIS/" + tenor.toUpperCase(java.util.Locale.ROOT), "InterestRate", "CURVE", bucket, ccy, tenor, "OIS", null);
    }

    public static SimmInput.Factor irVol(String ccy, String expiry) {
        return new SimmInput.Factor("VOL/IR/" + ccy + "/OIS/" + expiry.toUpperCase(java.util.Locale.ROOT) + "/10Y", "InterestRate", "VOL", "1", ccy, expiry, null, null);
    }

    public static SimmInput.Factor fxSpot(String ccy) {
        return new SimmInput.Factor("FX/SPOT/" + ccy, "FX", "SPOT", "1", ccy, null, null, null);
    }

    public static SimmInput.Factor credit(String issuer, String bucket, String tenor) {
        return new SimmInput.Factor("CR/SPREAD/" + issuer + "/CDS/" + tenor.toUpperCase(java.util.Locale.ROOT), "CreditQ", "SPREAD", bucket, issuer, tenor, "CDS",
                "SENIOR");
    }

    private static SimmInput.Trade trade(String id, String pc) {
        return new SimmInput.Trade(id, pc, Map.of("PRODUCT_GROUP", pc));
    }

    /**
     * SIMM.md section 15: one netting set, product class RatesFX: USD 5y OIS +10,000 and 10y −6,000 per bp, EUR 2y +4,000 per bp, FX EUR
     * +20,000 per 1 %. With the toy parameters SIMM_RatesFX = 426,606.46; with a USD threshold of 3,000 per bp, 450,195.27.
     */
    public static SimmInput worked() {
        List<SimmInput.Trade> t = List.of(trade("EX-SWAP-USD", "RatesFX"), trade("EX-SWAP-EUR", "RatesFX"), trade("EX-FXFWD-EUR", "RatesFX"));
        List<SimmInput.Element> e = List.of(new SimmInput.Element(0, irCurve("USD", "5y", "1"), "DV01", 10_000, Double.NaN),
                new SimmInput.Element(0, irCurve("USD", "10y", "1"), "DV01", -6_000, Double.NaN),
                new SimmInput.Element(1, irCurve("EUR", "2y", "1"), "DV01", 4_000, Double.NaN),
                new SimmInput.Element(2, fxSpot("EUR"), "DELTA", 20_000, Double.NaN));
        return new SimmInput(t, e, "USD", SimmInput.AddOns.NONE);
    }

    /** Case 6, the bucket: USD 5y DV01 −1,000 (toy RW 50) and 1y +400 (toy RW 60), ρ 0.8, CR 1: K = 34,000. */
    public static SimmInput case6Bucket() {
        return new SimmInput(List.of(trade("C6-A", "RatesFX")), List.of(new SimmInput.Element(0, irCurve("USD", "5y", "1"), "DV01", -1_000, Double.NaN),
                new SimmInput.Element(0, irCurve("USD", "1y", "1"), "DV01", 400, Double.NaN)), "USD", SimmInput.AddOns.NONE);
    }

    /**
     * Case 6, the product class: rates IM 50,000 (USD 5y DV01 1,000, toy RW 50) and CreditQ IM 30,000 (CS01 375, toy RW 80, bucket 2) in
     * one product class, toy ψ 0.3: SIMM_p = 65,574.39.
     */
    public static SimmInput case6Product() {
        return new SimmInput(List.of(trade("C6-B", "RatesFX")), List.of(new SimmInput.Element(0, irCurve("USD", "5y", "1"), "DV01", 1_000, Double.NaN),
                new SimmInput.Element(0, credit("ISS-B", "2", "5y"), "CS01", 375, Double.NaN)), "USD", SimmInput.AddOns.NONE);
    }

    /**
     * Case 11: one credit bucket (toy T 1,000 per bp, RW 100, ρ 0.4 between issuers). Issuer X nets CS01 −4,000 (two trades at 5y), CR 2;
     * issuer Y +1,000, CR 1; f 0.5: K = 786,129.76.
     */
    public static SimmInput case11() {
        List<SimmInput.Trade> t = List.of(trade("C11-1", "Credit"), trade("C11-2", "Credit"), trade("C11-3", "Credit"));
        return new SimmInput(t, List.of(new SimmInput.Element(0, credit("ISS-X", "1", "5y"), "CS01", -3_000, Double.NaN),
                new SimmInput.Element(1, credit("ISS-X", "1", "5y"), "CS01", -1_000, Double.NaN),
                new SimmInput.Element(2, credit("ISS-Y", "1", "5y"), "CS01", 1_000, Double.NaN)), "USD", SimmInput.AddOns.NONE);
    }

    /** The explanation's swaption (playground/simm/template/explain.txt steps 7 to 9): VEGA 2,000 per point at 20 %, one year to expiry. */
    public static SimmInput swaption() {
        return new SimmInput(List.of(trade("EX-SWPN", "RatesFX")), List.of(new SimmInput.Element(0, irVol("USD", "1y"), "VEGA", 2_000, 0.20)), "USD",
                SimmInput.AddOns.NONE);
    }

    /** Every example in one netting set (the {@code EXAMPLE} scope): the worked netting set, the swaption, case 11's credit. */
    public static SimmInput together() {
        List<SimmInput.Trade> t = new ArrayList<>();
        List<SimmInput.Element> e = new ArrayList<>();
        for (SimmInput in : List.of(worked(), swaption(), case11())) {
            int base = t.size();
            t.addAll(in.trades());
            in.elements().forEach(x -> e.add(new SimmInput.Element(base + x.trade(), x.factor(), x.measure(), x.value(), x.impliedVol())));
        }
        return new SimmInput(t, e, "USD", SimmInput.AddOns.NONE);
    }
}
