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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SA-CCR of one netting set by the Basel Framework, chapter CRE52 (docs/guides/SACCR_GUIDE.md section 5;
 * docs/architecture/COUNTERPARTY_ENGINES.md section 3): per trade the times, the supervisory duration, the adjusted notional, the supervisory
 * delta, both maturity factors and both effective notionals; per hedging set the add-on (IR with its three maturity buckets and their
 * correlations, FX per pair, credit and equity through the systematic and idiosyncratic terms, commodity per hedging set over its
 * commodity types); the replacement cost (unmargined and margined with TH + MTA − NICA), the multiplier, PFE, EAD = α (RC + PFE) and, for a
 * margined set, the cap at the unmargined EAD. Every number comes from the parameter file by key ({@link SaccrParams}); the playground's
 * workbook computes the same figures with formulas over the same cells and checks them against these to the cent.
 */
public final class Saccr {

    public static final String ENGINE = "drishti-saccr 1.0 (2.9.12)";
    public static final List<String> ASSET_CLASSES = List.of("IR", "FX", "CREDIT", "EQUITY", "COMMODITY");
    public static final List<String> TRANSACTION_TYPES = List.of("LINEAR", "OPTION", "CDO_TRANCHE", "BASIS", "VOLATILITY");
    public static final List<String> COMMODITY_SETS = List.of("ENERGY", "METALS", "AGRICULTURAL", "OTHER");
    public static final List<String> CREDIT_QUALITIES = List.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC");
    public static final List<String> INDEX_QUALITIES = List.of("IG", "SG");

    private Saccr() {}

    /**
     * The method choices: the IR offsets across maturity buckets (CRE52.57; off: Σ |D_b|) and the cap of a margined set at its unmargined
     * EAD (CRE52.1). Both on is the regulatory figure.
     */
    public record Choices(boolean irBucketOffset, boolean cap) {
        public static final Choices REGULATORY = new Choices(true, true);

        public boolean regulatory() {
            return irBucketOffset && cap;
        }
    }

    /** A row the method refuses, with its DRS code and reason. */
    public record Refusal(String tradeId, int driver, String reason) {}

    /** One row's terms (Trades tab). */
    public record Terms(SaccrInput.Trade trade, double s, double e, double m, double t, double sd, double d, double d1, double delta, double mfUnmargined,
            double mfMargined, double dUnmargined, double dMargined, int bucket, String subKey, double sf, double rho, double sigma) {}

    /** A sub-key of a hedging set: an IR bucket, a reference entity or index, a commodity type (FX: the pair). */
    public record SubKey(String key, double dUnmargined, double dMargined, double sf, double rho, double addOnUnmargined, double addOnMargined, int rows) {}

    /** A hedging set and its add-on on both bases. */
    public record HedgingSet(String assetClass, String hedgingSet, List<SubKey> subKeys, double enUnmargined, double enMargined, double addOnUnmargined,
            double addOnMargined, int rows) {}

    /** The set's figures on one basis (margined or unmargined). */
    public record Basis(double rc, Map<String, Double> addOns, double addOnAgg, double exponent, double multiplier, double pfe, double ead) {}

    /**
     * The answer of one netting set.
     *
     * @param unmargined the figures with the unmargined RC and maturity factors (every set)
     * @param margined the margined figures (null for an unmargined set)
     * @param ead the EAD after the cap (a margined set) or the unmargined EAD
     * @param capped the unmargined EAD is the smaller (a margined set)
     * @param mporFloor the MPOR floor in business days (0 unmargined)
     * @param mpor the margin period of risk in business days (0 unmargined)
     */
    public record Result(SaccrInput.NettingSet set, SaccrParams params, Choices choices, double v, List<Terms> terms, List<HedgingSet> hedgingSets,
            Basis unmargined, Basis margined, double ead, boolean capped, int mporFloor, int mpor, double mfMargined, List<Refusal> refusals) {

        public double rc() {
            return margined == null ? unmargined.rc : margined.rc;
        }

        public double pfe() {
            return capped || margined == null ? unmargined.pfe : margined.pfe;
        }

        /** The add-on on the basis the EAD was taken from. */
        public double addOnAgg() {
            return capped || margined == null ? unmargined.addOnAgg : margined.addOnAgg;
        }

        public double multiplier() {
            return capped || margined == null ? unmargined.multiplier : margined.multiplier;
        }

        public Basis binding() {
            return capped || margined == null ? unmargined : margined;
        }
    }

    /** The standard normal distribution function (W. J. Cody's / Hart's double-precision rational fit, |error| &lt; 1e-14). */
    public static double phi(double x) {
        double a = Math.abs(x);
        double c;
        if (a > 37) {
            c = 0;
        } else {
            double e = Math.exp(-a * a / 2);
            if (a < 7.07106781186547) {
                double n = 3.52624965998911e-02 * a + 0.700383064443688;
                n = n * a + 6.37396220353165;
                n = n * a + 33.912866078383;
                n = n * a + 112.079291497871;
                n = n * a + 221.213596169931;
                n = n * a + 220.206867912376;
                double d = 8.83883476483184e-02 * a + 1.75566716318264;
                d = d * a + 16.064177579207;
                d = d * a + 86.7807322029461;
                d = d * a + 296.564248779674;
                d = d * a + 637.333633378831;
                d = d * a + 793.826512519948;
                d = d * a + 440.413735824752;
                c = e * n / d;
            } else {
                double b = a + 0.65;
                b = a + 4 / b;
                b = a + 3 / b;
                b = a + 2 / b;
                b = a + 1 / b;
                c = e / b / 2.506628274631;
            }
        }
        return x > 0 ? 1 - c : c;
    }

    /** The supervisory duration {@code (exp(−r S) − exp(−r E)) / r}. */
    public static double supervisoryDuration(double s, double e, double rate) {
        return (Math.exp(-rate * s) - Math.exp(-rate * e)) / rate;
    }

    /** The multiplier {@code min(1, f + (1 − f) exp((V − C) / (2 (1 − f) AddOn)))}; 1 when the add-on is 0. */
    public static double multiplier(double vMinusC, double addOn, double floor) {
        if (addOn <= 0) {
            return 1;
        }
        return Math.min(1, floor + (1 - floor) * Math.exp(vMinusC / (2 * (1 - floor) * addOn)));
    }

    /** The margin period of risk's floor (CRE52.50, CRE52.51). */
    public static int mporFloor(SaccrInput.NettingSet n, int rows, SaccrParams p) {
        int members = n.members() > 0 ? n.members() : rows;
        double f = n.cleared() ? p.get("mpor-floors-days.cleared") : p.get("mpor-floors-days.bilateral");
        if (members > p.get("mpor-floors-days.large-netting-set-trades") || n.illiquidCollateral()) {
            f = p.get("mpor-floors-days.large-or-illiquid");
        }
        if (n.disputes() > p.get("dispute-doubling.disputes-over")) {
            f *= p.get("dispute-doubling.factor");
        }
        return (int) Math.round(f);
    }

    /** {@code (SF, ρ, σ)} of a row before the basis and volatility factors. */
    static double[] supervisory(SaccrInput.Trade t, SaccrParams p) {
        return switch (t.assetClass()) {
            case "IR", "FX" -> new double[] {p.get(t.assetClass() + ".sf"), 1, p.get(t.assetClass() + ".sigma")};
            case "CREDIT" -> {
                String b = t.index() ? "CREDIT.index" : "CREDIT.single";
                yield new double[] {p.get(b + ".sf." + t.creditQuality()), p.get(b + ".rho"), p.get(b + ".sigma")};
            }
            case "EQUITY" -> {
                String b = t.index() ? "EQUITY.index" : "EQUITY.single";
                yield new double[] {p.get(b + ".sf"), p.get(b + ".rho"), p.get(b + ".sigma")};
            }
            default -> {
                String b = "ELECTRICITY".equalsIgnoreCase(t.reference()) ? "COMMODITY.ELECTRICITY" : "COMMODITY.other";
                yield new double[] {p.get(b + ".sf"), p.get(b + ".rho"), p.get(b + ".sigma")};
            }
        };
    }

    /** Why a row is refused (DRS-5410), or null when it is complete. */
    static String check(SaccrInput.Trade t) {
        if (!ASSET_CLASSES.contains(String.valueOf(t.assetClass()))) {
            return "DRS-5410 ASSET_CLASS " + t.assetClass() + " is not one of " + ASSET_CLASSES;
        }
        if (!TRANSACTION_TYPES.contains(t.transactionType())) {
            return "DRS-5410 TRANSACTION_TYPE " + t.transactionType() + " is not one of " + TRANSACTION_TYPES;
        }
        if (t.hedgingSet() == null || t.hedgingSet().isBlank()) {
            return "DRS-5410 HEDGING_SET is missing";
        }
        boolean own = t.transactionType().equals("BASIS") || t.transactionType().equals("VOLATILITY");
        if (t.assetClass().equals("COMMODITY") && !own && !COMMODITY_SETS.contains(t.hedgingSet())) {
            return "DRS-5410 a commodity HEDGING_SET is one of " + COMMODITY_SETS + ", not " + t.hedgingSet();
        }
        if (t.assetClass().equals("FX") && !own && !t.hedgingSet().matches("[A-Z]{6}")) {
            return "DRS-5410 an FX HEDGING_SET is a six-letter pair, not " + t.hedgingSet();
        }
        if (Set.of("CREDIT", "EQUITY", "COMMODITY").contains(t.assetClass()) && t.reference().isBlank()) {
            return "DRS-5410 REFERENCE is missing (credit and equity: the entity or index; commodity: the commodity type)";
        }
        if (t.assetClass().equals("CREDIT") && !(t.index() ? INDEX_QUALITIES : CREDIT_QUALITIES).contains(String.valueOf(t.creditQuality()))) {
            return "DRS-5410 CREDIT_QUALITY " + t.creditQuality() + " is not one of " + (t.index() ? INDEX_QUALITIES : CREDIT_QUALITIES);
        }
        if (!t.transactionType().equals("OPTION") && !t.transactionType().equals("CDO_TRANCHE") && !"LONG".equals(t.direction())
                && !"SHORT".equals(t.direction())) {
            return "DRS-5410 DIRECTION " + t.direction() + " is not LONG or SHORT";
        }
        if (!(t.notional() > 0) || !Double.isFinite(t.notional())) {
            return "DRS-5410 the notional (NOTIONAL_USD, or QUANTITY x UNDERLYING_PRICE) is missing or not positive";
        }
        if (!Double.isFinite(t.m())) {
            return "DRS-5410 MATURITY_DATE is missing";
        }
        if (Double.isFinite(t.s()) && Double.isFinite(t.e()) && t.s() > t.e() + 1e-9 || Double.isFinite(t.e()) && t.e() > t.m() + 1e-9) {
            return "DRS-5410 the dates are not START_DATE <= END_DATE <= MATURITY_DATE";
        }
        if (t.transactionType().equals("OPTION")) {
            double lam = Double.isFinite(t.shift()) ? t.shift() : 0;
            if (!"CALL".equals(t.optionType()) && !"PUT".equals(t.optionType()) || !"BOUGHT".equals(t.boughtSold()) && !"SOLD".equals(t.boughtSold())
                    || !(t.t() > 0) || !Double.isFinite(t.strike()) || !Double.isFinite(t.forward())) {
                return "DRS-5410 an OPTION needs OPTION_TYPE, BOUGHT_SOLD, EXERCISE_DATE after the business date, STRIKE and UNDERLYING_FORWARD";
            }
            if (!(t.forward() + lam > 0) || !(t.strike() + lam > 0)) {
                return "DRS-5410 P + SHIFT and K + SHIFT must be positive (CRE52.41)";
            }
        }
        if (t.transactionType().equals("CDO_TRANCHE") && (!(t.attachment() >= 0) || !(t.attachment() < t.detachment()) || !(t.detachment() <= 1)
                || !"BOUGHT".equals(t.boughtSold()) && !"SOLD".equals(t.boughtSold()))) {
            return "DRS-5410 a CDO_TRANCHE needs 0 <= ATTACHMENT < DETACHMENT <= 1 and BOUGHT_SOLD";
        }
        return null;
    }

    /** One row's terms with the set's margined maturity factor. */
    static Terms terms(SaccrInput.Trade t, double mfMargined, SaccrParams p) {
        double minYears = p.get("time.min-maturity-days") / p.get("time.mf-days-per-year");
        double[] sup = supervisory(t, p);
        double sf = sup[0] * (t.transactionType().equals("BASIS") ? p.get("basis-factor") : t.transactionType().equals("VOLATILITY") ? p.get("volatility-factor") : 1);
        double m = Math.max(t.m(), minYears);
        boolean dur = t.assetClass().equals("IR") || t.assetClass().equals("CREDIT");
        double s = Double.isFinite(t.s()) ? Math.max(t.s(), 0) : 0;
        double e = Math.max(Double.isFinite(t.e()) ? t.e() : t.m(), minYears);
        double sd = dur ? supervisoryDuration(s, e, p.get("supervisory-duration-rate")) : 1;
        double d = t.notional() * sd;
        double d1 = Double.NaN;
        double delta;
        switch (t.transactionType()) {
            case "OPTION" -> {
                double lam = Double.isFinite(t.shift()) ? t.shift() : 0;
                double sig = sup[2];
                d1 = (Math.log((t.forward() + lam) / (t.strike() + lam)) + 0.5 * sig * sig * t.t()) / (sig * Math.sqrt(t.t()));
                boolean bought = t.boughtSold().equals("BOUGHT");
                if (t.optionType().equals("CALL")) {
                    delta = bought ? phi(d1) : -phi(d1);
                } else {
                    delta = bought ? -phi(-d1) : phi(-d1);
                }
            }
            case "CDO_TRANCHE" -> {
                double v = p.get("cdo-delta.numerator") / ((1 + p.get("cdo-delta.slope") * t.attachment()) * (1 + p.get("cdo-delta.slope") * t.detachment()));
                delta = t.boughtSold().equals("SOLD") ? v : -v;
            }
            default -> delta = t.direction().equals("LONG") ? 1 : -1;
        }
        double mfU = Math.sqrt(Math.min(m, 1.0));
        int bucket = 0;
        String key;
        if (t.assetClass().equals("IR")) {
            bucket = e < p.get("ir-buckets.lower-years") ? 1 : e <= p.get("ir-buckets.upper-years") ? 2 : 3;
            key = "b" + bucket;
        } else if (t.assetClass().equals("FX")) {
            key = t.hedgingSet();
        } else {
            key = t.reference();
        }
        return new Terms(t, s, e, m, t.t(), sd, d, d1, delta, mfU, mfMargined, delta * d * mfU, delta * d * mfMargined, bucket, key, sf, sup[1], sup[2]);
    }

    /** SA-CCR of the input with the regulatory choices. */
    public static Result run(SaccrInput in, SaccrParams p) {
        return run(in, p, Choices.REGULATORY);
    }

    /** SA-CCR of the input. */
    public static Result run(SaccrInput in, SaccrParams p, Choices ch) {
        SaccrInput.NettingSet n = in.set();
        List<Refusal> refusals = new ArrayList<>();
        List<SaccrInput.Trade> ok = new ArrayList<>();
        for (SaccrInput.Trade t : in.trades()) {
            String why = check(t);
            if (why == null) {
                ok.add(t);
            } else {
                refusals.add(new Refusal(t.tradeId(), t.driver(), why));
            }
        }
        double v = n.v();
        if (Double.isNaN(v)) {
            v = 0;
            for (SaccrInput.Trade t : ok) {
                if (t.driver() == 0 && Double.isFinite(t.mtm())) {
                    v += t.mtm();
                }
            }
        }
        int floor = 0;
        int mpor = 0;
        double mfM = 0;
        if (n.margined()) {
            floor = mporFloor(n, ok.size(), p);
            mpor = Math.max(floor, n.mporDays()) + Math.max(1, n.marginFrequencyDays()) - 1;
            mfM = p.get("margined-mf-scale") * Math.sqrt(mpor / p.get("time.mf-days-per-year"));
        }
        List<Terms> terms = new ArrayList<>(ok.size());
        for (SaccrInput.Trade t : ok) {
            terms.add(terms(t, mfM, p));
        }
        List<HedgingSet> sets = hedgingSets(terms, p, ch);
        double alpha = p.get("alpha");
        double f = p.get("multiplier-floor");
        Basis u = basis(sets, false, Math.max(v - n.c(), 0), v - n.c(), alpha, f);
        Basis mg = null;
        double ead = u.ead();
        boolean capped = false;
        if (n.margined()) {
            mg = basis(sets, true, Math.max(Math.max(v - n.c(), n.th() + n.mta() - n.nica()), 0), v - n.c(), alpha, f);
            boolean cap = ch.cap() && p.get("cap-at-unmargined") > 0.5;
            capped = cap && u.ead() < mg.ead();
            ead = capped ? u.ead() : mg.ead();
        }
        return new Result(n, p, ch, v, List.copyOf(terms), sets, u, mg, ead, capped, floor, mpor, mfM, List.copyOf(refusals));
    }

    private static Basis basis(List<HedgingSet> sets, boolean margined, double rc, double vMinusC, double alpha, double floor) {
        Map<String, Double> by = new LinkedHashMap<>();
        ASSET_CLASSES.forEach(a -> by.put(a, 0.0));
        double agg = 0;
        for (HedgingSet h : sets) {
            double a = margined ? h.addOnMargined() : h.addOnUnmargined();
            by.merge(h.assetClass(), a, Double::sum);
        }
        for (String a : ASSET_CLASSES) {
            agg += by.get(a);
        }
        double exponent = agg > 0 ? vMinusC / (2 * (1 - floor) * agg) : 0;
        double mult = multiplier(vMinusC, agg, floor);
        double pfe = mult * agg;
        return new Basis(rc, Map.copyOf(by), agg, exponent, mult, pfe, alpha * (rc + pfe));
    }

    /** The hedging sets of the rows, in the order the asset classes and hedging sets first appear. */
    static List<HedgingSet> hedgingSets(List<Terms> terms, SaccrParams p, Choices ch) {
        Map<String, List<Terms>> by = new LinkedHashMap<>();
        for (String a : ASSET_CLASSES) {
            for (Terms x : terms) {
                if (x.trade().assetClass().equals(a)) {
                    by.computeIfAbsent(a + "|" + x.trade().hedgingSet(), k -> new ArrayList<>()).add(x);
                }
            }
        }
        List<HedgingSet> out = new ArrayList<>();
        for (Map.Entry<String, List<Terms>> e : by.entrySet()) {
            List<Terms> rows = e.getValue();
            String ac = rows.get(0).trade().assetClass();
            String hs = rows.get(0).trade().hedgingSet();
            Map<String, double[]> sub = new LinkedHashMap<>();         // key -> dU, dM, sf, rho, rows
            if (ac.equals("IR")) {
                for (String b : List.of("b1", "b2", "b3")) {
                    sub.put(b, new double[] {0, 0, rows.get(0).sf(), 1, 0});
                }
            }
            for (Terms x : rows) {
                double[] s = sub.computeIfAbsent(x.subKey(), k -> new double[] {0, 0, x.sf(), x.rho(), 0});
                s[0] += x.dUnmargined();
                s[1] += x.dMargined();
                s[4]++;
            }
            double sf = rows.get(0).sf();
            double[] en = new double[2];
            double[] addOn = new double[2];
            List<SubKey> subs = new ArrayList<>();
            switch (ac) {
                case "IR" -> {
                    double w12 = p.get("ir-bucket-weights.b1b2");
                    double w23 = p.get("ir-bucket-weights.b2b3");
                    double w13 = p.get("ir-bucket-weights.b1b3");
                    for (int k = 0; k < 2; k++) {
                        double d1 = sub.get("b1")[k];
                        double d2 = sub.get("b2")[k];
                        double d3 = sub.get("b3")[k];
                        en[k] = ch.irBucketOffset() ? Math.sqrt(Math.max(0, d1 * d1 + d2 * d2 + d3 * d3 + w12 * d1 * d2 + w23 * d2 * d3 + w13 * d1 * d3))
                                : Math.abs(d1) + Math.abs(d2) + Math.abs(d3);
                        addOn[k] = sf * en[k];
                    }
                    sub.forEach((k, s) -> subs.add(new SubKey(k, s[0], s[1], s[2], 1, Double.NaN, Double.NaN, (int) s[4])));
                }
                case "FX" -> {
                    double[] s = sub.values().iterator().next();
                    for (int k = 0; k < 2; k++) {
                        en[k] = Math.abs(s[k]);
                        addOn[k] = sf * en[k];
                    }
                    sub.forEach((k, x) -> subs.add(new SubKey(k, x[0], x[1], x[2], 1, x[2] * Math.abs(x[0]), x[2] * Math.abs(x[1]), (int) x[4])));
                }
                default -> {
                    boolean commodity = ac.equals("COMMODITY");
                    for (int k = 0; k < 2; k++) {
                        double sys = 0;
                        double idio = 0;
                        double sum = 0;
                        double sumSq = 0;
                        for (double[] s : sub.values()) {
                            double a = s[2] * s[k];
                            sys += s[3] * a;
                            idio += (1 - s[3] * s[3]) * a * a;
                            sum += a;
                            sumSq += a * a;
                        }
                        double rho = commodity ? sub.values().iterator().next()[3] : 0;
                        addOn[k] = commodity ? Math.sqrt(Math.max(0, rho * sum * rho * sum + (1 - rho * rho) * sumSq)) : Math.sqrt(Math.max(0, sys * sys + idio));
                        en[k] = Double.NaN;
                    }
                    sub.forEach((k, s) -> subs.add(new SubKey(k, s[0], s[1], s[2], s[3], s[2] * s[0], s[2] * s[1], (int) s[4])));
                }
            }
            out.add(new HedgingSet(ac, hs, List.copyOf(subs), en[0], en[1], addOn[0], addOn[1], rows.size()));
        }
        return List.copyOf(out);
    }
}
