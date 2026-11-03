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

import com.ash.drishti.compute.saccr.SaccrInput.NettingSet;
import com.ash.drishti.compute.saccr.SaccrInput.Trade;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hand-built SA-CCR cases with their published figures (docs/architecture/COUNTERPARTY_ENGINES.md section 9, cases S1 to S7; the
 * worked example of docs/contracts/SACCR.md section 15), each small enough to check with a calculator; times are given in years directly.
 * The {@code EXAMPLE} scope of the SA-CCR API and playground runs them. The Basel Committee's Annex 4 netting sets (BCBS 279) are listed
 * apart ({@link #bcbs279}) and marked unverified until their inputs are checked against the published text.
 */
public final class SaccrExamples {

    private SaccrExamples() {}

    /**
     * One case: its input, the published EAD (whole units) and where it is published.
     *
     * @param verified false: the inputs are not yet checked against the publication (the tests skip it)
     */
    public record Case(String name, String source, SaccrInput input, double publishedEad, boolean verified) {}

    private static Trade ir(String id, String dir, double n, double s, double e, String ccy) {
        return Trade.linear(id, "IR", ccy, dir, n, s, e, e);
    }

    private static Trade fx(String id, String dir, double n, double m, String pair) {
        return Trade.linear(id, "FX", pair, dir, n, Double.NaN, Double.NaN, m);
    }

    private static Trade credit(String id, String dir, double n, double e, String ref, boolean index, String q) {
        return Trade.linear(id, "CREDIT", "CREDIT", dir, n, 0, e, e).named(ref, index, q);
    }

    private static Trade commodity(String id, String dir, double n, double m, String set, String type) {
        return Trade.linear(id, "COMMODITY", set, dir, n, Double.NaN, Double.NaN, m).named(type, false, null);
    }

    private static SaccrInput in(NettingSet ns, Trade... trades) {
        return new SaccrInput(ns, List.of(trades));
    }

    /** Case S1: one USD payer swap, 100m, five years, V +1.2m: EAD 4,776,789. */
    public static SaccrInput s1() {
        return in(NettingSet.unmargined("EX-S1", 1_200_000, 0), ir("S1-SWAP", "LONG", 100_000_000, 0, 5, "USD"));
    }

    /** Case S2: three USD swaps across the three buckets, V −2m: EAD 919,867. */
    public static SaccrInput s2() {
        return in(NettingSet.unmargined("EX-S2", -2_000_000, 0), ir("S2-T1", "LONG", 200_000_000, 0, 0.5, "USD"), ir("S2-T2", "SHORT", 100_000_000, 0, 3, "USD"),
                ir("S2-T3", "LONG", 50_000_000, 0, 10, "USD"));
    }

    /** Case S3: FX forwards in two pairs, V +0.8m: EAD 4,235,358. */
    public static SaccrInput s3() {
        return in(NettingSet.unmargined("EX-S3", 800_000, 0), fx("S3-F1", "LONG", 55_000_000, 0.75, "EURUSD"), fx("S3-F2", "SHORT", 22_000_000, 2, "EURUSD"),
                fx("S3-F3", "SHORT", 30_000_000, 1.5, "JPYUSD"));
    }

    /** Case S4: a sold equity index call and a single-name total return swap, V −1.2m: EAD 11,511,662. */
    public static SaccrInput s4() {
        Trade call = Trade.linear("S4-E1", "EQUITY", "EQUITY", "SHORT", 50_000_000, Double.NaN, Double.NaN, 0.5).named("IDX-SPX", true, null)
                .option("CALL", "SOLD", 0.5, 5_200, 5_000, Double.NaN);
        Trade trs = Trade.linear("S4-E2", "EQUITY", "EQUITY", "LONG", 30_000_000, Double.NaN, Double.NaN, 1).named("EQ-ACME", false, null);
        return in(NettingSet.unmargined("EX-S4", -1_200_000, 0), call, trs);
    }

    /** Case S5: a margined set where TH + MTA − NICA binds and the cap binds: EAD 4,366,244 (CAPPED); {@code disputes} 3 doubles the floor. */
    public static SaccrInput s5(int disputes) {
        return in(NettingSet.margined("EX-S5", 3_000_000, 1_000_000, 1_000_000, 5_000_000, 500_000, 10, 1, disputes), ir("S5-M1", "LONG", 100_000_000, 0, 0.75, "USD"),
                fx("S5-M2", "LONG", 40_000_000, 0.25, "EURUSD"));
    }

    /** Case S6: bought protection on an IG index against sold protection on a BBB name, V +0.5m: EAD 2,859,343. */
    public static SaccrInput s6() {
        return in(NettingSet.unmargined("EX-S6", 500_000, 0), credit("S6-C1", "SHORT", 100_000_000, 5, "IDX-CDX-IG", true, "IG"),
                credit("S6-C2", "LONG", 40_000_000, 3, "LEI-NAME-BBB", false, "BBB"));
    }

    /** Case S7: a bought payer swaption with a shifted delta (λ 1 %), V +0.9m: EAD 2,664,402. */
    public static SaccrInput s7() {
        Trade sw = Trade.linear("S7-SWPN", "IR", "USD", "LONG", 100_000_000, 1, 6, 6).option("CALL", "BOUGHT", 1, 0.025, 0.02, 0.01);
        return in(NettingSet.unmargined("EX-S7", 900_000, 0), sw);
    }

    /** SACCR.md section 15: three IR swaps in USD and EUR; {@code v} 1.5m (EAD 4,184,144) or −1.5m (1,269,235); margined: 1,349,541. */
    public static SaccrInput worked(double v, boolean margined) {
        NettingSet ns = margined ? NettingSet.margined("EX-W", v, 1_200_000, 0, 0, 500_000, 10, 1, 0) : NettingSet.unmargined("EX-W", v, 0);
        return in(ns, ir("W-T1", "LONG", 100_000_000, 0, 4, "USD"), ir("W-T2", "SHORT", 50_000_000, 0, 8, "USD"), ir("W-T3", "LONG", 80_000_000, 0, 0.5, "EUR"));
    }

    /** The design's own cases with their published EAD (whole USD). */
    public static List<Case> design() {
        String d = "COUNTERPARTY_ENGINES.md section 9, case ";
        String w = "SACCR.md section 15";
        return List.of(new Case("S1", d + "S1", s1(), 4_776_789, true),
                new Case("S1-V-NEGATIVE", d + "S1 (variant V = -1,200,000)", new SaccrInput(s1().set().withV(-1_200_000), s1().trades()), 2_366_066, true),
                new Case("S2", d + "S2", s2(), 919_867, true), new Case("S3", d + "S3", s3(), 4_235_358, true), new Case("S4", d + "S4", s4(), 11_511_662, true),
                new Case("S5", d + "S5", s5(0), 4_366_244, true), new Case("S5-DISPUTES", d + "S5 (variant: three disputes)", s5(3), 4_366_244, true),
                new Case("S6", d + "S6", s6(), 2_859_343, true), new Case("S7", d + "S7", s7(), 2_664_402, true),
                new Case("W", w, worked(1_500_000, false), 4_184_144, true), new Case("W-V-NEGATIVE", w + " (V = -1.5m)", worked(-1_500_000, false), 1_269_235, true),
                new Case("W-MARGINED", w + " (margined)", worked(1_500_000, true), 1_349_541, true));
    }

    /**
     * The Basel Committee's worked netting sets, BCBS 279 (March 2014, revised April 2014), Annex 4, examples 1 to 4 (amounts in
     * thousands). UNVERIFIED: the inputs were typed from memory because the publication could not be fetched where they were written;
     * the published EAD of each (569, 381, 5,406, 6,342) equals what this engine computes from them, which is evidence, not proof. Check
     * every input against the PDF, then set {@code verified} true.
     */
    public static List<Case> bcbs279() {
        String src = "BCBS 279 (2014), Annex 4, example ";
        Trade t1 = ir("BCBS-T1", "LONG", 10_000, 0, 10, "USD");
        Trade t2 = ir("BCBS-T2", "SHORT", 10_000, 0, 4, "USD");
        Trade t3 = Trade.linear("BCBS-T3", "IR", "EUR", "SHORT", 5_000, 1, 11, 11).option("PUT", "BOUGHT", 1, 0.05, 0.06, Double.NaN);
        Trade t4 = credit("BCBS-T4", "SHORT", 10_000, 3, "FIRM-A", false, "AA");
        Trade t5 = credit("BCBS-T5", "LONG", 10_000, 6, "FIRM-B", false, "BBB");
        Trade t6 = credit("BCBS-T6", "SHORT", 10_000, 5, "CDX-IG-5Y", true, "IG");
        Trade t7 = commodity("BCBS-T7", "LONG", 10_000, 0.75, "ENERGY", "CRUDE_OIL");
        Trade t8 = commodity("BCBS-T8", "SHORT", 20_000, 2, "ENERGY", "CRUDE_OIL");
        Trade t9 = commodity("BCBS-T9", "LONG", 10_000, 5, "METALS", "SILVER");
        return List.of(new Case("BCBS-1", src + "1 (interest rate)", in(NettingSet.unmargined("BCBS-NS1", 60, 0), t1, t2, t3), 569, false),
                new Case("BCBS-2", src + "2 (credit)", in(NettingSet.unmargined("BCBS-NS2", -20, 0), t4, t5, t6), 381, false),
                new Case("BCBS-3", src + "3 (commodity)", in(NettingSet.unmargined("BCBS-NS3", 20, 0), t7, t8, t9), 5_406, false),
                new Case("BCBS-4", src + "4 (the three in one netting set)", in(NettingSet.unmargined("BCBS-NS4", 60, 0), t1, t2, t3, t4, t5, t6, t7, t8, t9),
                        6_342, false));
    }

    /** Every case of {@link #design()} by name. */
    public static Map<String, SaccrInput> byName() {
        Map<String, SaccrInput> m = new LinkedHashMap<>();
        for (Case c : design()) {
            m.put(c.name(), c.input());
        }
        return m;
    }

    /** The trades of every design case with each case's set as a separate netting set (the EXAMPLE scope lists them side by side). */
    public static List<Case> together() {
        return new ArrayList<>(design());
    }
}
