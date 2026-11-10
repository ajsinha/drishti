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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ISDA SIMM for one netting set (or scope) and one direction (docs/guides/SIMM_GUIDE.md section 5; docs/guides/FRTB_SA_GUIDE.md section 8,
 * checked against SIMM 2.8+2512 in section 18): the elements netted per factor within each product class; delta, vega, curvature and base
 * correlation per risk class with the concentration factors at the grain the rules set (per currency for rates, per issuer and seniority
 * for credit, per factor otherwise), f_kl within a bucket and g_bc across currencies, every bucket's net capped at its K, the residual
 * buckets outside the root; the risk classes within a product class with ψ; the product classes added with no correlation; the CSA's
 * add-ons on top. Post is the same run with every sensitivity negated. Every number is read from the parameter file by key and the key is
 * kept, so the playground's workbook points each formula at the same cell.
 */
public final class Simm {

    public static final String ENGINE = "drishti-simm 1.0 (2.9.11)";
    public static final String DELTA = "DELTA";
    public static final String VEGA = "VEGA";
    public static final String CURVATURE = "CURVATURE";
    public static final String BASE_CORR = "BASE_CORR";
    public static final List<String> MEASURES = List.of(DELTA, VEGA, CURVATURE, BASE_CORR);
    /** √(365 / 14) / Φ⁻¹(99 %): the vega σ of equity, FX and commodity is the delta risk weight times this (paragraph 10(b)). */
    public static final double SIGMA_FACTOR = Math.sqrt(365.0 / 14.0) / SimmParams.PHI99;

    private Simm() {}

    /** A correlation as the product of one or two parameter keys, squared for curvature (ρ² and γ², paragraph 11). */
    public record Corr(String a, String b, boolean squared) {
        public double value(SimmParams p) {
            double v = p.v(a) * (b == null ? 1 : p.v(b));
            return squared ? v * v : v;
        }
    }

    /** One netted factor of a product class, risk class and measure. */
    public static final class Risk {
        public final String productClass;
        public final String riskClass;
        public final String measure;
        public final String key;
        public final String bucket;
        public final String type;
        public final String qualifier;
        public final String label1;
        public final String label2;
        /** The concentration grain (currency, issuer and seniority, factor); null: no concentration (basis, curvature, base correlation). */
        public final String group;
        public final String rwKey;
        public final String thresholdKey;
        public final String hvrKey;
        public double s;
        public double rw = 1;
        public double hvr = 1;
        public double cr = 1;
        public double ws;
        public final Map<Integer, Double> parts = new TreeMap<>();

        Risk(String productClass, String riskClass, String measure, String key, String bucket, String type, String qualifier, String label1, String label2,
                String group, String rwKey, String thresholdKey, String hvrKey) {
            this.productClass = productClass;
            this.riskClass = riskClass;
            this.measure = measure;
            this.key = key;
            this.bucket = bucket;
            this.type = type;
            this.qualifier = qualifier;
            this.label1 = label1;
            this.label2 = label2;
            this.group = group;
            this.rwKey = rwKey;
            this.thresholdKey = thresholdKey;
            this.hvrKey = hvrKey;
        }

        /** The text that names the risk in the workbook's Data tab (unique within a run). */
        public String id() {
            return productClass + "|" + riskClass + "|" + measure + "|" + key;
        }
    }

    /** One concentration grain: the sum of its sensitivities, its threshold and its factor. */
    public static final class Group {
        public final String productClass;
        public final String riskClass;
        public final String measure;
        public final String group;
        public final String thresholdKey;
        public final List<Integer> members = new ArrayList<>();
        public double sum;
        public double threshold;
        public double cr = 1;

        Group(String productClass, String riskClass, String measure, String group, String thresholdKey) {
            this.productClass = productClass;
            this.riskClass = riskClass;
            this.measure = measure;
            this.group = group;
            this.thresholdKey = thresholdKey;
        }
    }

    /** One bucket of a charge (for rates: a currency). */
    public static final class Bucket {
        public final String label;
        public final boolean residual;
        public final int[] members;
        public final Corr[][] rho;
        public final double[][] rhoValue;
        public final double[][] f;
        public double k;
        public double net;
        public double s;
        /** Rates: the currency's concentration factor (delta) or vega concentration factor (vega), for g_bc. */
        public double cr = 1;
        public double sumCvr;
        public double sumAbsCvr;

        Bucket(String label, boolean residual, int[] members) {
            this.label = label;
            this.residual = residual;
            this.members = members;
            int n = members.length;
            this.rho = new Corr[n][n];
            this.rhoValue = new double[n][n];
            this.f = new double[n][n];
        }
    }

    /** The margin of one product class, risk class and measure. */
    public static final class Charge {
        public final String productClass;
        public final String riskClass;
        public final String measure;
        public final List<Bucket> buckets = new ArrayList<>();
        /** Between the non-residual buckets, in their order (null on the diagonal). */
        public Corr[][] gamma;
        public double[][] gammaValue;
        public double[][] g;
        public double root;
        public double residual;
        public double margin;
        // curvature
        public double theta;
        public double lambda;
        public double sumCvr;
        public double sumAbsCvr;
        public double nonResidual;
        public double thetaResidual;
        public double lambdaResidual;
        public double scale = 1;

        Charge(String productClass, String riskClass, String measure) {
            this.productClass = productClass;
            this.riskClass = riskClass;
            this.measure = measure;
        }

        /** The non-residual buckets in order. */
        public List<Bucket> core() {
            return buckets.stream().filter(b -> !b.residual).toList();
        }

        /** The residual bucket, or null. */
        public Bucket residualBucket() {
            return buckets.stream().filter(b -> b.residual).findFirst().orElse(null);
        }
    }

    /** The margin of one risk class within a product class: {@code IM_r = Delta + Vega + Curvature + BaseCorr}. */
    public record ClassIm(String productClass, String riskClass, double delta, double vega, double curvature, double baseCorr) {
        public double im() {
            return delta + vega + curvature + baseCorr;
        }
    }

    /** A product class's margin: the risk classes' IM (in {@link SimmParams#RISK_CLASSES}' order) and SIMM_p. */
    public record ProductIm(String productClass, double[] im, double simm) {}

    /** An element left out, and why (a DRS code first). */
    public record Refusal(int element, String factor, String measure, String reason) {}

    /** The add-ons as computed. */
    public record AddOn(double fixed, double notional, double multipliers, Map<String, Double> byProduct) {
        public double total() {
            return fixed + notional + multipliers;
        }
    }

    /**
     * One run.
     *
     * @param elementRisk per element, the index in {@code risks} of the delta, vega or base-correlation risk it feeds (−1: none)
     * @param elementConv per element, the conversion to SIMM's quantity (1; 100 · σ for rates and credit vega; RW · √(365/14) / Φ⁻¹(99 %)
     *     for equity, FX and commodity vega)
     * @param elementCurv per element, the index of the curvature risk it feeds (−1: none)
     * @param elementSf per element, its curvature scaling SF(t)
     */
    public record Result(SimmInput input, SimmParams params, SimmChoices choices, boolean post, List<Risk> risks, List<Group> groups, List<Charge> charges,
            List<ClassIm> classes, List<ProductIm> products, double simm, AddOn addOn, double total, List<Refusal> refusals, int[] elementRisk,
            double[] elementConv, int[] elementCurv, double[] elementSf, List<String> notes) {

        /** True when the figures are the regulatory ones (default switches, no override). */
        public boolean regulatory() {
            return choices.regulatory() && params.overrides.isEmpty();
        }

        /** The charge of a product class, risk class and measure, or null. */
        public Charge charge(String pc, String rc, String measure) {
            return charges.stream().filter(c -> c.productClass.equals(pc) && c.riskClass.equals(rc) && c.measure.equals(measure)).findFirst().orElse(null);
        }

        /** The product class's margin, or null. */
        public ProductIm product(String pc) {
            return products.stream().filter(x -> x.productClass.equals(pc)).findFirst().orElse(null);
        }
    }

    // ---- the run ---------------------------------------------------------------------------------------------------------------------

    /** SIMM of {@code in} for the bank's side ({@code post} false: collect) or the counterparty's ({@code post} true: every sensitivity negated). */
    public static Result run(SimmInput in, SimmParams p, SimmChoices ch, boolean post) {
        double sign = post ? -1 : 1;
        String calc = in.calculationCurrency();
        List<Refusal> refusals = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Risk> byId = new LinkedHashMap<>();
        int n = in.elements().size();
        String[] riskOf = new String[n];
        String[] curvOf = new String[n];
        double[] conv = new double[n];
        double[] sf = new double[n];
        int[] skippedCurvature = {0};
        int skippedCalc = 0;
        for (int i = 0; i < n; i++) {
            SimmInput.Element e = in.elements().get(i);
            SimmInput.Factor f = e.factor();
            String m = e.measure() == null ? "" : e.measure().toUpperCase(Locale.ROOT);
            if (m.equals("CURVATURE_UP") || m.equals("CURVATURE_DOWN") || m.equals("GAMMA")) {
                skippedCurvature[0]++;
                continue;
            }
            if (f == null || f.riskClass() == null || !SimmParams.RISK_CLASSES.contains(f.riskClass())) {
                refusals.add(new Refusal(i, f == null ? "?" : f.name(), m, "DRS-5331 " + (f == null ? "a factor not in the dictionary" : f.name()) + " has no SIMM bucket; left out of SIMM"));
                continue;
            }
            String pc = in.trades().get(e.trade()).productClass();
            if (!SimmParams.PRODUCT_CLASSES.contains(pc)) {
                refusals.add(new Refusal(i, f.name(), m, "DRS-5331 the trade " + in.trades().get(e.trade()).id() + " has no SIMM product class (" + pc + ")"));
                continue;
            }
            String rc = f.riskClass();
            String type = f.type() == null ? "" : f.type().toUpperCase(Locale.ROOT);
            String why;
            if (m.equals("VEGA")) {
                why = vega(i, e, f, pc, rc, type, p, calc, sign, byId, riskOf, curvOf, conv, sf);
            } else if (m.equals("BASE_CORR")) {
                why = rc.equals("CreditQ") ? null : "DRS-5331 a base-correlation sensitivity outside CreditQ";
                if (why == null) {
                    Risk r = risk(byId, new Risk(pc, rc, BASE_CORR, f.qualifier(), "BaseCorr", type, f.qualifier(), null, null, null,
                            "CreditQ.base-correlation-risk-weight", null, null));
                    add(r, e, 1, sign, riskOf, i, conv);
                }
            } else {
                why = delta(i, e, f, pc, rc, type, p, calc, sign, byId, riskOf, conv);
            }
            if ("CALC".equals(why)) {
                skippedCalc++;
            } else if (why != null) {
                refusals.add(new Refusal(i, f.name(), m, why));
            }
        }
        if (skippedCurvature[0] > 0) {
            notes.add(skippedCurvature[0] + " curvature revaluations or gammas not read: SIMM's curvature comes from the vegas");
        }
        if (skippedCalc > 0) {
            notes.add(skippedCalc + " FX sensitivities to the calculation currency " + calc + " left out (paragraph 8)");
        }
        // a deterministic order: product class, risk class, measure, bucket, key
        List<Risk> risks = new ArrayList<>(byId.values());
        risks.sort(Comparator.comparingInt((Risk r) -> SimmParams.PRODUCT_CLASSES.indexOf(r.productClass))
                .thenComparingInt(r -> SimmParams.RISK_CLASSES.indexOf(r.riskClass)).thenComparingInt(r -> MEASURES.indexOf(r.measure))
                .thenComparing(r -> r.bucket, Simm::compareBuckets).thenComparing(r -> r.key));
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int k = 0; k < risks.size(); k++) {
            index.put(risks.get(k).id(), k);
        }
        int[] elementRisk = new int[n];
        int[] elementCurv = new int[n];
        for (int i = 0; i < n; i++) {
            elementRisk[i] = riskOf[i] == null ? -1 : index.get(riskOf[i]);
            elementCurv[i] = curvOf[i] == null ? -1 : index.get(curvOf[i]);
        }
        // risk weights, HVR, concentration, WS
        Map<String, Group> groups = new LinkedHashMap<>();
        for (int k = 0; k < risks.size(); k++) {
            Risk r = risks.get(k);
            r.rw = r.rwKey == null ? 1 : p.v(r.rwKey);
            r.hvr = r.hvrKey == null || !ch.hvrOn() ? 1 : p.v(r.hvrKey);
            if (r.group != null) {
                Group g = groups.computeIfAbsent(r.productClass + "|" + r.riskClass + "|" + r.measure + "|" + r.group,
                        x -> new Group(r.productClass, r.riskClass, r.measure, r.group, r.thresholdKey));
                g.members.add(k);
                g.sum += r.hvr * r.s;
            }
        }
        for (Group g : groups.values()) {
            g.threshold = p.v(g.thresholdKey) * in.thresholdFx();
            g.cr = ch.concentrationOn() ? SimmMath.concentration(g.sum, g.threshold) : 1;
            for (int k : g.members) {
                risks.get(k).cr = g.cr;
            }
        }
        for (Risk r : risks) {
            r.ws = r.rw * r.hvr * r.s * r.cr;
        }
        // the charges
        List<Charge> charges = new ArrayList<>();
        Map<String, List<Integer>> byCharge = new LinkedHashMap<>();
        for (int k = 0; k < risks.size(); k++) {
            Risk r = risks.get(k);
            byCharge.computeIfAbsent(r.productClass + "|" + r.riskClass + "|" + r.measure, x -> new ArrayList<>()).add(k);
        }
        for (Map.Entry<String, List<Integer>> e : byCharge.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            charges.add(charge(parts[0], parts[1], parts[2], e.getValue(), risks, groups, p, ch, calc));
        }
        // risk classes, product classes, the total
        Map<String, double[]> im = new LinkedHashMap<>();
        for (Charge c : charges) {
            double[] v = im.computeIfAbsent(c.productClass + "|" + c.riskClass, x -> new double[4]);
            v[MEASURES.indexOf(c.measure)] += c.margin;
        }
        List<ClassIm> classes = new ArrayList<>();
        im.forEach((k, v) -> {
            String[] pr = k.split("\\|");
            classes.add(new ClassIm(pr[0], pr[1], v[0], v[1], v[2], v[3]));
        });
        double[][] psi = psi(p);
        List<ProductIm> products = new ArrayList<>();
        double simm = 0;
        for (String pc : SimmParams.PRODUCT_CLASSES) {
            double[] v = new double[SimmParams.RISK_CLASSES.size()];
            boolean any = false;
            for (ClassIm c : classes) {
                if (c.productClass().equals(pc)) {
                    v[SimmParams.RISK_CLASSES.indexOf(c.riskClass())] = c.im();
                    any = true;
                }
            }
            if (any) {
                double sp = SimmMath.productClass(v, psi);
                products.add(new ProductIm(pc, v, sp));
                simm += sp;
            }
        }
        AddOn addOn = addOn(in, products);
        return new Result(in, p, ch, post, List.copyOf(risks), List.copyOf(groups.values()), List.copyOf(charges), List.copyOf(classes), List.copyOf(products),
                simm, addOn, simm + addOn.total(), List.copyOf(refusals), elementRisk, conv, elementCurv, sf, List.copyOf(notes));
    }

    /** ψ from the parameter file, in the order of the risk classes. */
    public static double[][] psi(SimmParams p) {
        int r = SimmParams.RISK_CLASSES.size();
        double[][] psi = new double[r][r];
        for (int a = 0; a < r; a++) {
            for (int b = 0; b < r; b++) {
                psi[a][b] = p.v(psiKey(a, b));
            }
        }
        return psi;
    }

    /** The parameter key of ψ between two risk classes. */
    public static String psiKey(int a, int b) {
        return "risk-class-correlation." + SimmParams.RISK_CLASSES.get(a) + "." + SimmParams.RISK_CLASSES.get(b);
    }

    private static Risk risk(Map<String, Risk> byId, Risk fresh) {
        return byId.computeIfAbsent(fresh.id(), x -> fresh);
    }

    private static void add(Risk r, SimmInput.Element e, double c, double sign, String[] riskOf, int i, double[] conv) {
        double v = e.value() * c * sign;
        r.s += v;
        r.parts.merge(e.trade(), v, Double::sum);
        riskOf[i] = r.id();
        conv[i] = c;
    }

    /** A delta element into its risk; null when taken, "CALC" for FX to the calculation currency, else the refusal's words. */
    private static String delta(int i, SimmInput.Element e, SimmInput.Factor f, String pc, String rc, String type, SimmParams p, String calc, double sign,
            Map<String, Risk> byId, String[] riskOf, double[] conv) {
        Risk r;
        switch (rc) {
            case "InterestRate" -> {
                String ccy = f.qualifier();
                String rb = f.bucket();
                if (!p.irBuckets.contains(rb)) {
                    return "DRS-5331 " + f.name() + " is in rates bucket " + rb + ", not one of " + p.irBuckets;
                }
                String tk = "InterestRate.concentration-thresholds.delta." + p.group("InterestRate", ccy);
                switch (type) {
                    case "CURVE" -> {
                        String t = label(f.label1());
                        if (!p.irTenors.contains(t)) {
                            return "DRS-5331 " + f.name() + ": " + f.label1() + " is not a SIMM rates tenor (send it on SIMM's vertices)";
                        }
                        String sub = f.label2() == null ? "" : f.label2();
                        r = new Risk(pc, rc, DELTA, ccy + "|" + t + "|" + sub, ccy, type, ccy, t, sub, ccy, "InterestRate.delta-risk-weights." + rb + "." + t, tk, null);
                    }
                    case "INFLATION" -> r = new Risk(pc, rc, DELTA, ccy + "|Inf", ccy, type, ccy, "Inf", null, ccy, "InterestRate.inflation-risk-weight", tk, null);
                    case "BASIS" -> r = new Risk(pc, rc, DELTA, ccy + "|XCcy", ccy, type, ccy, null, "XCcy", null, "InterestRate.cross-currency-basis-risk-weight", null, null);
                    default -> {
                        return "DRS-5331 " + f.name() + ": a rates factor of type " + type + " has no SIMM delta";
                    }
                }
            }
            case "CreditQ", "CreditNonQ" -> {
                if (!type.equals("SPREAD")) {
                    return "DRS-5331 " + f.name() + ": a credit factor of type " + type + " has no SIMM delta";
                }
                String b = creditBucket(p, rc, f.bucket());
                if (b == null) {
                    return "DRS-5331 " + f.name() + " is in " + rc + " bucket " + f.bucket() + ", not in the version";
                }
                String t = label(f.label1());
                if (!p.creditQTenors.contains(t)) {
                    return "DRS-5331 " + f.name() + ": " + f.label1() + " is not a SIMM credit tenor";
                }
                String iss = f.issuer();
                r = new Risk(pc, rc, DELTA, iss + "|" + t, b, type, iss, t, null, iss, rc + ".delta-risk-weights." + b, rc + ".concentration-thresholds.delta." + b, null);
            }
            case "Equity", "Commodity" -> {
                if (type.equals("REPO")) {
                    return "DRS-5331 " + f.name() + ": equity repo is left out of SIMM (the version's CRIF rule is an open question, SIMM.md section 16)";
                }
                if (!(rc.equals("Equity") ? type.equals("SPOT") : type.equals("PRICE"))) {
                    return "DRS-5331 " + f.name() + ": an " + rc + " factor of type " + type + " has no SIMM delta";
                }
                String b = namedBucket(p, rc, f.bucket());
                if (b == null) {
                    return "DRS-5331 " + f.name() + " is in " + rc + " bucket " + f.bucket() + ", not in the version";
                }
                String q = f.qualifier();
                r = new Risk(pc, rc, DELTA, q, b, type, q, null, null, q, rc + ".delta-risk-weights." + b, rc + ".concentration-thresholds.delta." + b, null);
            }
            case "FX" -> {
                if (!type.equals("SPOT")) {
                    return "DRS-5331 " + f.name() + ": an FX factor of type " + type + " has no SIMM delta";
                }
                String ccy = f.qualifier() == null ? "" : f.qualifier().toUpperCase(Locale.ROOT);
                if (ccy.equals(calc)) {
                    return "CALC";
                }
                r = new Risk(pc, rc, DELTA, ccy, "FX", type, ccy, null, null, ccy, fxRiskWeightKey(p, ccy, calc),
                        "FX.concentration-thresholds.delta." + p.group("FX", ccy), null);
            }
            default -> {
                return "DRS-5331 " + f.name() + ": no SIMM risk class";
            }
        }
        add(risk(byId, r), e, 1, sign, riskOf, i, conv);
        return null;
    }

    /** A vega element into its vega risk and its curvature risk. */
    private static String vega(int i, SimmInput.Element e, SimmInput.Factor f, String pc, String rc, String type, SimmParams p, String calc, double sign,
            Map<String, Risk> byId, String[] riskOf, String[] curvOf, double[] conv, double[] sf) {
        if (!type.equals("VOL")) {
            return "DRS-5331 " + f.name() + ": a VEGA on a factor that is not a volatility";
        }
        double years = f.years();
        if (!(years > 0)) {
            return "DRS-5331 " + f.name() + ": the volatility has no expiry (SIMM_LABEL1)";
        }
        String t = label(f.label1());
        Risk v;
        Risk c;
        double cv;
        switch (rc) {
            case "InterestRate" -> {
                if (!p.irTenors.contains(t)) {
                    return "DRS-5331 " + f.name() + ": the expiry " + f.label1() + " is not a SIMM rates tenor";
                }
                if (!Double.isFinite(e.impliedVol())) {
                    return "DRS-5336 " + f.name() + ": a rates vega without IMPLIED_VOL";
                }
                String ccy = f.qualifier();
                cv = 100 * e.impliedVol();
                v = new Risk(pc, rc, VEGA, ccy + "|" + t, ccy, type, ccy, t, null, ccy, "InterestRate.vega-risk-weight",
                        "InterestRate.concentration-thresholds.vega." + p.group("InterestRate", ccy), null);
                c = new Risk(pc, rc, CURVATURE, ccy + "|" + t, ccy, type, ccy, t, null, null, null, null, null);
            }
            case "CreditQ", "CreditNonQ" -> {
                String b = creditBucket(p, rc, f.bucket());
                if (b == null) {
                    return "DRS-5331 " + f.name() + " is in " + rc + " bucket " + f.bucket() + ", not in the version";
                }
                if (!Double.isFinite(e.impliedVol())) {
                    return "DRS-5336 " + f.name() + ": a credit vega without IMPLIED_VOL";
                }
                String iss = f.issuer();
                cv = 100 * e.impliedVol();
                v = new Risk(pc, rc, VEGA, iss + "|" + t, b, type, iss, t, null, iss, rc + ".vega-risk-weight", rc + ".concentration-thresholds.vega." + b, null);
                c = new Risk(pc, rc, CURVATURE, iss + "|" + t, b, type, iss, t, null, null, null, null, null);
            }
            case "Equity", "Commodity" -> {
                String b = namedBucket(p, rc, f.bucket());
                if (b == null) {
                    return "DRS-5331 " + f.name() + " is in " + rc + " bucket " + f.bucket() + ", not in the version";
                }
                String q = f.qualifier();
                cv = p.v(rc + ".delta-risk-weights." + b) * SIGMA_FACTOR;
                v = new Risk(pc, rc, VEGA, q, b, type, q, null, null, q, p.vegaRiskWeightKey(rc, b), rc + ".concentration-thresholds.vega." + b,
                        rc + ".historical-volatility-ratio");
                c = rc.equals("Equity") && p.zeroCurvatureEquity.contains(b) ? null : new Risk(pc, rc, CURVATURE, q, b, type, q, null, null, null, null, null, null);
            }
            case "FX" -> {
                String ccy = f.qualifier() == null ? "" : f.qualifier().toUpperCase(Locale.ROOT);
                if (ccy.equals(calc)) {
                    return "CALC";
                }
                cv = p.v(fxRiskWeightKey(p, ccy, calc)) * SIGMA_FACTOR;
                v = new Risk(pc, rc, VEGA, ccy, "FX", type, ccy, null, null, ccy, "FX.vega-risk-weight", "FX.concentration-thresholds.vega." + p.group("FX", ccy),
                        "FX.historical-volatility-ratio");
                c = new Risk(pc, rc, CURVATURE, ccy, "FX", type, ccy, null, null, null, null, null, null);
            }
            default -> {
                return "DRS-5331 " + f.name() + ": no SIMM risk class";
            }
        }
        add(risk(byId, v), e, cv, sign, riskOf, i, conv);
        if (c != null) {
            Risk cr = risk(byId, c);
            double scale = SimmMath.scaling(years);
            double x = e.value() * cv * scale * sign;
            cr.s += x;
            cr.parts.merge(e.trade(), x, Double::sum);
            curvOf[i] = cr.id();
            sf[i] = scale;
        }
        return null;
    }

    /** The FX delta risk weight's key of a currency against the calculation currency. */
    public static String fxRiskWeightKey(SimmParams p, String ccy, String calc) {
        return "FX.delta-risk-weights." + p.fxGroup(ccy) + "-" + p.fxGroup(calc);
    }

    private static String label(String l) {
        return l == null ? "" : l.strip().toLowerCase(Locale.ROOT);
    }

    private static String creditBucket(SimmParams p, String rc, String b) {
        if (b == null) {
            return null;
        }
        if (b.equalsIgnoreCase("Residual")) {
            return "Residual";
        }
        return (rc.equals("CreditQ") ? p.creditQBuckets : p.creditNonQBuckets).contains(b.strip()) ? b.strip() : null;
    }

    private static String namedBucket(SimmParams p, String rc, String b) {
        if (b == null) {
            return null;
        }
        if (b.equalsIgnoreCase("Residual")) {
            return rc.equals("Equity") ? "Residual" : null;
        }
        return (rc.equals("Equity") ? p.equityBuckets : p.commodityBuckets).contains(b.strip()) ? b.strip() : null;
    }

    /** Numbers in number order, then text, Residual last. */
    static int compareBuckets(String a, String b) {
        boolean ra = a.equals("Residual");
        boolean rb = b.equals("Residual");
        if (ra || rb) {
            return ra == rb ? 0 : ra ? 1 : -1;
        }
        boolean na = a.matches("\\d+");
        boolean nb = b.matches("\\d+");
        if (na && nb) {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        }
        return na == nb ? a.compareTo(b) : na ? -1 : 1;
    }

    // ---- within and across buckets ---------------------------------------------------------------------------------------------------

    /** ρ between two risks of one bucket (the keys; curvature squared). */
    static Corr rho(Risk a, Risk b, SimmParams p, String calc) {
        boolean sq = a.measure.equals(CURVATURE);
        String rc = a.riskClass;
        if (a.measure.equals(BASE_CORR)) {
            return new Corr("CreditQ.base-correlation-correlation", null, false);
        }
        switch (rc) {
            case "InterestRate" -> {
                if (a.measure.equals(DELTA)) {
                    if (a.type.equals("BASIS") || b.type.equals("BASIS")) {
                        return new Corr("InterestRate.cross-currency-basis-correlation", null, false);
                    }
                    if (a.type.equals("INFLATION") || b.type.equals("INFLATION")) {
                        return new Corr("InterestRate.inflation-correlation", null, false);
                    }
                    String t = "InterestRate.tenor-correlation." + a.label1 + "." + b.label1;
                    return new Corr(t, a.label2.equals(b.label2) ? null : "InterestRate.sub-curve-correlation", false);
                }
                return new Corr("InterestRate.tenor-correlation." + a.label1 + "." + b.label1, null, sq);
            }
            case "CreditQ", "CreditNonQ" -> {
                boolean same = a.qualifier.equals(b.qualifier);
                boolean res = a.bucket.equals("Residual");
                return new Corr(rc + ".correlation." + (res ? "residual-" : "") + (same ? "same-issuer" : "different-issuer"), null, sq);
            }
            case "Equity", "Commodity" -> {
                return new Corr(a.qualifier.equals(b.qualifier) ? SimmParams.ONE : rc + ".correlation." + a.bucket, null, sq);
            }
            default -> {
                if (!a.measure.equals(DELTA)) {
                    return new Corr("FX.vega-correlation", null, sq);
                }
                String ga = p.fxGroup(a.qualifier);
                String gb = p.fxGroup(b.qualifier);
                String pair = ga.equals(gb) ? ga + "-" + gb : "regular-high";
                return new Corr("FX.correlation." + p.fxGroup(calc) + "." + pair, null, false);
            }
        }
    }

    /** γ between two non-residual buckets of a class (the keys; curvature squared); null when the class has one group only. */
    static Corr gamma(String rc, String measure, String b, String c) {
        boolean sq = measure.equals(CURVATURE);
        return switch (rc) {
            case "InterestRate" -> new Corr("InterestRate.across-currencies", null, sq);
            case "CreditQ", "Equity", "Commodity" -> new Corr(rc + ".across-buckets." + b + "." + c, null, sq);
            case "CreditNonQ" -> new Corr("CreditNonQ.across-buckets", null, sq);
            default -> null;
        };
    }

    private static Charge charge(String pc, String rc, String measure, List<Integer> members, List<Risk> risks, Map<String, Group> groups, SimmParams p,
            SimmChoices ch, String calc) {
        Charge c = new Charge(pc, rc, measure);
        Map<String, List<Integer>> byBucket = new LinkedHashMap<>();
        for (int k : members) {
            byBucket.computeIfAbsent(risks.get(k).bucket, x -> new ArrayList<>()).add(k);
        }
        boolean concentrationTerms = (measure.equals(DELTA) || measure.equals(VEGA)) && !rc.equals("InterestRate");
        for (Map.Entry<String, List<Integer>> e : byBucket.entrySet()) {
            int[] m = e.getValue().stream().mapToInt(Integer::intValue).toArray();
            boolean residual = e.getKey().equals("Residual");
            Bucket b = new Bucket(e.getKey(), residual, m);
            double sum = 0;
            double net = 0;
            for (int i = 0; i < m.length; i++) {
                Risk a = risks.get(m[i]);
                net += a.ws;
                b.sumCvr += a.ws;
                b.sumAbsCvr += Math.abs(a.ws);
                for (int j = 0; j < m.length; j++) {
                    if (i == j) {
                        b.rhoValue[i][j] = 1;
                        b.f[i][j] = 1;
                        sum += a.ws * a.ws;
                        continue;
                    }
                    Risk o = risks.get(m[j]);
                    b.rho[i][j] = rho(a, o, p, calc);
                    b.rhoValue[i][j] = b.rho[i][j].value(p);
                    b.f[i][j] = concentrationTerms ? SimmMath.ratio(a.cr, o.cr) : 1;
                    sum += b.rhoValue[i][j] * b.f[i][j] * a.ws * o.ws;
                }
            }
            b.k = Math.sqrt(Math.max(0, sum));
            b.net = net;
            b.s = SimmMath.capped(net, b.k);
            if (rc.equals("InterestRate") && (measure.equals(DELTA) || measure.equals(VEGA))) {
                Group g = groups.get(pc + "|" + rc + "|" + measure + "|" + e.getKey());
                b.cr = g == null ? 1 : g.cr;
            }
            c.buckets.add(b);
        }
        c.buckets.sort((x, y) -> compareBuckets(x.label, y.label));
        List<Bucket> core = c.core();
        int nb = core.size();
        c.gamma = new Corr[nb][nb];
        c.gammaValue = new double[nb][nb];
        c.g = new double[nb][nb];
        double[] k = new double[nb];
        double[] s = new double[nb];
        for (int i = 0; i < nb; i++) {
            k[i] = core.get(i).k;
            s[i] = core.get(i).s;
            for (int j = 0; j < nb; j++) {
                c.g[i][j] = 1;
                if (i != j) {
                    c.gamma[i][j] = gamma(rc, measure, core.get(i).label, core.get(j).label);
                    c.gammaValue[i][j] = c.gamma[i][j] == null ? 0 : c.gamma[i][j].value(p);
                    if (rc.equals("InterestRate") && !measure.equals(CURVATURE)) {
                        c.g[i][j] = SimmMath.ratio(core.get(i).cr, core.get(j).cr);
                    }
                }
            }
        }
        c.root = SimmMath.acrossBuckets(k, s, c.gammaValue, c.g);
        Bucket res = c.residualBucket();
        if (!measure.equals(CURVATURE)) {
            c.residual = res == null ? 0 : res.k;
            c.margin = c.root + c.residual;
            return c;
        }
        for (Bucket b : core) {
            c.sumCvr += b.sumCvr;
            c.sumAbsCvr += b.sumAbsCvr;
        }
        c.theta = SimmMath.theta(c.sumCvr, c.sumAbsCvr);
        c.lambda = SimmMath.lambda(c.theta);
        c.nonResidual = nb == 0 ? 0 : SimmMath.curvature(c.sumCvr, c.lambda, c.root);
        if (res != null) {
            c.thetaResidual = SimmMath.theta(res.sumCvr, res.sumAbsCvr);
            c.lambdaResidual = SimmMath.lambda(c.thetaResidual);
            c.residual = SimmMath.curvature(res.sumCvr, c.lambdaResidual, res.k);
        }
        if (rc.equals("InterestRate") && ch.hvrOn()) {
            double h = p.v("InterestRate.historical-volatility-ratio");
            c.scale = 1 / (h * h);
        }
        c.margin = (c.nonResidual + c.residual) * c.scale;
        return c;
    }

    /** The CSA's add-ons on the product classes' margins. */
    static AddOn addOn(SimmInput in, List<ProductIm> products) {
        SimmInput.AddOns a = in.addOns();
        double notional = 0;
        Map<String, Double> by = new LinkedHashMap<>();
        for (SimmInput.Line l : a.lines()) {
            Double f = a.factors().get(l.product());
            if (f != null) {
                double v = f * Math.abs(l.notional());
                notional += v;
                by.merge("notional: " + l.product(), v, Double::sum);
            }
        }
        double mult = 0;
        for (ProductIm pm : products) {
            Double ms = a.multipliers().get(pm.productClass());
            if (ms != null) {
                if (!(ms >= 1)) {
                    throw new IllegalArgumentException("the CSA's multiplier of " + pm.productClass() + " is " + ms + ": a product-class multiplier is at least 1");
                }
                double v = (ms - 1) * pm.simm();
                mult += v;
                by.merge("multiplier: " + pm.productClass(), v, Double::sum);
            }
        }
        if (a.fixed() != 0) {
            by.put("fixed amount", a.fixed());
        }
        return new AddOn(a.fixed(), notional, mult, by);
    }
}
