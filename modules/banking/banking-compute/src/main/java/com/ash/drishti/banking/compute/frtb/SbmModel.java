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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The scope's sensitivities turned into the SBM's risk factors (FRTB_SA_GUIDE.md sections 4.1 to 4.3; MAR21.8 to MAR21.25): each delivered
 * element converted to the regulatory sensitivity, placed on its delta, vega or curvature factor and netted across positions (the
 * per-position parts kept for the contributions), with the risk weight of the parameter file. An element the method cannot use (a tenor
 * that is not a vertex, a vega without its implied volatility, a measure the class does not have) is refused with its reason and counted.
 */
public final class SbmModel {

    public static final List<String> CLASSES = List.of("GIRR", "CSR_NS", "EQ", "COMM", "FX");
    public static final String DELTA = "DELTA";
    public static final String VEGA = "VEGA";
    public static final String CURVATURE = "CURVATURE";

    /** A delta or vega factor of the scope, netted. */
    public static final class Risk {
        public final String cls;
        public final String measure;
        public final String bucket;
        public final String key;
        /** {@code CURVE}, {@code INFLATION}, {@code BASIS}, {@code SPREAD}, {@code SPOT}, {@code REPO}, {@code PRICE}, {@code FX} or {@code VOL}. */
        public final String type;
        /** The issuer, underlying, commodity or currency. */
        public final String name;
        /** The curve (GIRR), the bond or CDS curve (CSR), the location (commodity); else empty. */
        public final String curve;
        /** The tenor or option maturity in years (NaN when none). */
        public final double t1;
        /** The GIRR volatility's underlying maturity in years (NaN when none). */
        public final double t2;
        public final double rw;
        public final String rwSource;
        public double s;
        /** The position's part of s: position index → s. */
        public final Map<Integer, Double> parts = new TreeMap<>();

        Risk(String cls, String measure, String bucket, String key, String type, String name, String curve, double t1, double t2, double rw,
                String rwSource) {
            this.cls = cls;
            this.measure = measure;
            this.bucket = bucket;
            this.key = key;
            this.type = type;
            this.name = name;
            this.curve = curve;
            this.t1 = t1;
            this.t2 = t2;
            this.rw = rw;
            this.rwSource = rwSource;
        }

        public double ws() {
            return rw * s;
        }
    }

    /** A curvature factor of the scope (a currency, an issuer, an equity, a commodity, a currency pair leg), netted. */
    public static final class Curv {
        public final String cls;
        public final String bucket;
        public final String key;
        public final String name;
        /** The curvature risk weight (the shock) as a fraction. */
        public final double rw;
        /** The shock in the delivered unit of the deltas: basis points for curves (GIRR, CSR), per cent for spots and prices. */
        public final double h;
        public double up;
        public double down;
        public double gamma;
        public double deltaPart;
        public double cvrUp;
        public double cvrDown;
        public boolean estimated;
        public boolean revalued;
        public boolean divided;
        /** Per position: {CVR+, CVR−}. */
        public final Map<Integer, double[]> parts = new TreeMap<>();

        Curv(String cls, String bucket, String key, String name, double rw, double h) {
            this.cls = cls;
            this.bucket = bucket;
            this.key = key;
            this.name = name;
            this.rw = rw;
            this.h = h;
        }
    }

    /** An element the method refused, with the reason. */
    public record Refusal(int position, String factor, String measure, String reason) {}

    /** A warning that changes nothing in the figure (a shock that differs from the parameter file's). */
    public record Warning(String factor, String text) {}

    public final List<Risk> risks = new ArrayList<>();
    public final List<Curv> curvs = new ArrayList<>();
    public final List<Refusal> refusals = new ArrayList<>();
    public final List<Warning> warnings = new ArrayList<>();
    public final FrtbParams params;
    public final FrtbChoices choices;
    public final String reporting;
    /** Per input element (in the input's order): the delta or vega factor's key it was netted into, or null. */
    public final String[] elementRisk;
    /** Per input element: the curvature factor's key it feeds (a curvature input, or a delta of the factor's curve), or null. */
    public final String[] elementCurv;
    /** Per input element: the conversion to the regulatory sensitivity (10,000 per bp, 100 per 1%, 100·σ for vega; NaN: none). */
    public final double[] elementConversion;
    private int current;

    private final Map<String, Risk> riskByKey = new LinkedHashMap<>();
    private final Map<String, Curv> curvByKey = new LinkedHashMap<>();

    /** Per-position raw curvature inputs before CVR: key → position → {up, down, gamma, deltaS, hasUp, hasDown, hasGamma}. */
    private final Map<String, Map<Integer, double[]>> curvRaw = new LinkedHashMap<>();

    public SbmModel(FrtbInput in, FrtbParams params, FrtbChoices choices) {
        this.params = params;
        this.choices = choices;
        this.reporting = in.reporting();
        Map<Integer, Set<String>> fxCurvature = new HashMap<>();
        elementRisk = new String[in.elements().size()];
        elementCurv = new String[in.elements().size()];
        elementConversion = new double[in.elements().size()];
        java.util.Arrays.fill(elementConversion, Double.NaN);
        for (FrtbInput.Element e : in.elements()) {
            String why = place(e);
            current++;
            if (why != null) {
                refusals.add(new Refusal(e.position(), e.factor() == null ? "" : e.factor().name(), e.measure(), why));
            } else if (e.factor().riskClass().equals("FX") && e.measure().startsWith("CURVATURE")) {
                fxCurvature.computeIfAbsent(e.position(), x -> new HashSet<>()).add(e.factor().qualifier());
            }
        }
        curvatures(fxCurvature);
        risks.addAll(riskByKey.values());
        curvs.addAll(curvByKey.values());
    }

    private static String up(String s) {
        return s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
    }

    /** Places one element; the reason it is refused, or null. */
    private String place(FrtbInput.Element e) {
        FactorDictionary.Factor f = e.factor();
        if (f == null) {
            return "the factor is not in the dictionary";
        }
        if (!Double.isFinite(e.value())) {
            return "the value is not a number";
        }
        String cls = up(f.riskClass());
        String m = up(e.measure());
        if (!CLASSES.contains(cls)) {
            return "the risk class " + f.riskClass() + " is not one of FRTB-SA's";
        }
        if (m.equals("CURVATURE_UP") || m.equals("CURVATURE_DOWN") || m.equals("GAMMA")) {
            return curvatureInput(e, cls, m);
        }
        String type = up(f.type());
        if (m.equals("VEGA")) {
            if (!type.equals("VOL")) {
                return "a VEGA on a factor that is not a volatility";
            }
            if (!(e.impliedVol() > 0)) {
                return "a VEGA without its IMPLIED_VOL";
            }
            return vega(e, cls, f);
        }
        if (type.equals("VOL")) {
            return "a " + m + " on a volatility factor";
        }
        return delta(e, cls, type, m, f);
    }

    private String delta(FrtbInput.Element e, String cls, String type, String m, FactorDictionary.Factor f) {
        String b = f.frtbBucket();
        double perBp = 10_000;
        double perPct = 100;
        switch (cls) {
            case "GIRR" -> {
                if (!(m.equals("DV01") || m.equals("IE01"))) {
                    return "GIRR delta is a DV01 or IE01, not " + m;
                }
                String ccy = up(f.qualifier());
                if (type.equals("INFLATION")) {
                    add(new Risk(cls, DELTA, ccy, "GIRR|" + ccy + "|INFLATION", "INFLATION", ccy, "", Double.NaN, Double.NaN, params.girrInflationRw,
                            "GIRR.delta.inflation-risk-weight"), e, perBp);
                    return null;
                }
                if (type.equals("BASIS")) {
                    add(new Risk(cls, DELTA, ccy, "GIRR|" + ccy + "|BASIS", "BASIS", ccy, "", Double.NaN, Double.NaN, params.girrBasisRw,
                            "GIRR.delta.cross-currency-basis-risk-weight"), e, perBp);
                    return null;
                }
                double t = FactorDictionary.years(f.tenor());
                int i = params.girrTenorIndex(t);
                if (i < 0) {
                    return "the tenor " + f.tenor() + " is not a GIRR vertex (MAR21.8)";
                }
                boolean div = specified(ccy);
                double rw = params.girrRw[i] / (div ? params.girrDivisor : 1);
                String curve = up(f.curve());
                add(new Risk(cls, DELTA, ccy, "GIRR|" + ccy + "|CURVE|" + curve + "|" + f.tenor(), "CURVE", ccy, curve, t, Double.NaN, rw,
                        "GIRR.delta.risk-weights[" + i + "]" + (div ? " / specified-divisor" : "")), e, perBp);
                return null;
            }
            case "CSR_NS" -> {
                if (!m.equals("CS01")) {
                    return "CSR delta is a CS01, not " + m;
                }
                int bucket = bucketNo(b);
                Double rw = params.csrRw.get(bucket);
                if (rw == null) {
                    return "the CSR bucket " + b + " has no risk weight";
                }
                double t = FactorDictionary.years(f.tenor());
                if (params.csrTenorIndex(t) < 0) {
                    return "the tenor " + f.tenor() + " is not a CSR vertex (MAR21.8)";
                }
                String curve = up(f.curve()).isEmpty() ? "BOND" : up(f.curve());
                add(new Risk(cls, DELTA, String.valueOf(bucket), "CSR|" + bucket + "|" + f.qualifier() + "|" + curve + "|" + f.tenor(), "SPREAD", f.qualifier(),
                        curve, t, Double.NaN, rw, "CSR_NS.delta.risk-weights." + bucket), e, perBp);
                return null;
            }
            case "EQ" -> {
                if (!m.equals("DELTA")) {
                    return "equity delta is a DELTA, not " + m;
                }
                int bucket = bucketNo(b);
                boolean repo = type.equals("REPO");
                Double rw = (repo ? params.eqRepoRw : params.eqSpotRw).get(bucket);
                if (rw == null) {
                    return "the equity bucket " + b + " has no risk weight";
                }
                add(new Risk(cls, DELTA, String.valueOf(bucket), "EQ|" + bucket + "|" + f.qualifier() + "|" + (repo ? "REPO" : "SPOT"), repo ? "REPO" : "SPOT",
                        f.qualifier(), "", Double.NaN, Double.NaN, rw, "EQ.delta." + (repo ? "repo" : "spot") + "-risk-weights." + bucket), e,
                        repo ? perBp : perPct);
                return null;
            }
            case "COMM" -> {
                if (!m.equals("DELTA")) {
                    return "commodity delta is a DELTA, not " + m;
                }
                int bucket = bucketNo(b);
                Double rw = params.cmRw.get(bucket);
                if (rw == null) {
                    return "the commodity bucket " + b + " has no risk weight";
                }
                double t = FactorDictionary.years(f.tenor());
                if (FrtbParams.index(new double[] {0, 0.25, 0.5, 1, 2, 3, 5, 10, 15, 20, 30}, t) < 0) {
                    return "the tenor " + f.tenor() + " is not a commodity vertex (MAR21.8)";
                }
                String loc = up(f.curve());
                add(new Risk(cls, DELTA, String.valueOf(bucket), "COMM|" + bucket + "|" + f.qualifier() + "|" + loc + "|" + f.tenor(), "PRICE", f.qualifier(), loc, t,
                        Double.NaN, rw, "COMM.delta.risk-weights." + bucket), e, perPct);
                return null;
            }
            default -> {
                if (!m.equals("DELTA")) {
                    return "FX delta is a DELTA, not " + m;
                }
                String ccy = up(f.qualifier());
                if (ccy.equals(reporting)) {
                    return "an FX delta to the reporting currency itself";
                }
                boolean div = params.fxSpecifiedPairs.contains(ccy + reporting) || params.fxSpecifiedPairs.contains(reporting + ccy);
                add(new Risk(cls, DELTA, ccy, "FX|" + ccy, "FX", ccy, "", Double.NaN, Double.NaN, params.fxRw / (div ? params.fxDivisor : 1),
                        "FX.delta.risk-weight" + (div ? " / specified-divisor" : "")), e, perPct);
                return null;
            }
        }
    }

    private boolean specified(String ccy) {
        return params.girrUseDivisor && (params.girrSpecified.contains(ccy) || ccy.equals(reporting));
    }

    private String vega(FrtbInput.Element e, String cls, FactorDictionary.Factor f) {
        double t = FactorDictionary.years(f.tenor());
        if (!(t > 0)) {
            return "the volatility " + f.name() + " has no option maturity";
        }
        double conv = 100 * e.impliedVol();
        switch (cls) {
            case "GIRR" -> {
                String ccy = up(f.qualifier());
                double u = FactorDictionary.years(f.underlyingTenor());
                if (!(u > 0)) {
                    return "the GIRR volatility " + f.name() + " has no underlying maturity";
                }
                add(new Risk(cls, VEGA, ccy, "GIRR|" + ccy + "|VOL|" + f.tenor() + "|" + f.underlyingTenor(), "VOL", ccy, "", t, u, params.girrVegaRw,
                        "GIRR.vega.risk-weight"), e, conv);
            }
            case "CSR_NS" -> {
                int b = bucketNo(f.frtbBucket());
                add(new Risk(cls, VEGA, String.valueOf(b), "CSR|" + b + "|" + f.qualifier() + "|VOL|" + f.tenor(), "VOL", f.qualifier(), "", t, Double.NaN,
                        params.csrVegaRw, "CSR_NS.vega.risk-weight"), e, conv);
            }
            case "EQ" -> {
                int b = bucketNo(f.frtbBucket());
                Double rw = params.eqVegaRw.get(b);
                if (rw == null) {
                    return "the equity bucket " + f.frtbBucket() + " has no vega risk weight";
                }
                add(new Risk(cls, VEGA, String.valueOf(b), "EQ|" + b + "|" + f.qualifier() + "|VOL|" + f.tenor(), "VOL", f.qualifier(), "", t, Double.NaN, rw,
                        "EQ.vega.risk-weights." + b), e, conv);
            }
            case "COMM" -> {
                int b = bucketNo(f.frtbBucket());
                add(new Risk(cls, VEGA, String.valueOf(b), "COMM|" + b + "|" + f.qualifier() + "|VOL|" + f.tenor(), "VOL", f.qualifier(), "", t, Double.NaN,
                        params.cmVegaRw, "COMM.vega.risk-weight"), e, conv);
            }
            default -> {
                String ccy = up(f.qualifier());
                add(new Risk(cls, VEGA, ccy, "FX|" + ccy + "|VOL|" + f.tenor(), "VOL", ccy, "", t, Double.NaN, params.fxVegaRw, "FX.vega.risk-weight"), e, conv);
            }
        }
        return null;
    }

    private void add(Risk proto, FrtbInput.Element e, double conv) {
        double s = e.value() * conv;
        Risk r = riskByKey.computeIfAbsent(proto.key, k -> proto);
        elementRisk[current] = r.key;
        elementConversion[current] = conv;
        if (r.measure.equals(DELTA)) {
            elementCurv[current] = curvKeyOfDelta(r);
        }
        r.s += s;
        r.parts.merge(e.position(), s, Double::sum);
        if (r.measure.equals(DELTA)) {
            String ck = curvKeyOfDelta(r);
            if (ck != null) {
                curvRaw.computeIfAbsent(ck, k -> new TreeMap<>()).computeIfAbsent(e.position(), p -> new double[7])[3] += s;
            }
        }
    }

    /** The curvature factor a delta factor belongs to (null: none, as inflation, basis and equity repo). */
    private static String curvKeyOfDelta(Risk r) {
        return switch (r.cls) {
            case "GIRR" -> r.type.equals("CURVE") ? "GIRR|" + r.bucket : null;
            case "CSR_NS" -> "CSR|" + r.bucket + "|" + r.name;
            case "EQ" -> r.type.equals("SPOT") ? "EQ|" + r.bucket + "|" + r.name : null;
            case "COMM" -> "COMM|" + r.bucket + "|" + r.name;
            default -> "FX|" + r.bucket;
        };
    }

    private String curvatureInput(FrtbInput.Element e, String cls, String m) {
        FactorDictionary.Factor f = e.factor();
        String type = up(f.type());
        if (type.equals("VOL") || type.equals("REPO") || type.equals("INFLATION") || type.equals("BASIS")) {
            return "no curvature for a " + type.toLowerCase(Locale.ROOT) + " factor (MAR21.8 to MAR21.14)";
        }
        String key = switch (cls) {
            case "GIRR" -> "GIRR|" + up(f.qualifier());
            case "CSR_NS" -> "CSR|" + bucketNo(f.frtbBucket()) + "|" + f.qualifier();
            case "EQ" -> "EQ|" + bucketNo(f.frtbBucket()) + "|" + f.qualifier();
            case "COMM" -> "COMM|" + bucketNo(f.frtbBucket()) + "|" + f.qualifier();
            default -> "FX|" + up(f.qualifier());
        };
        if (cls.equals("FX") && up(f.qualifier()).equals(reporting)) {
            return "FX curvature to the reporting currency itself";
        }
        if (!m.equals("GAMMA") && !f.whole()) {
            return "curvature is delivered on the whole curve or spot, not on " + f.name();
        }
        double[] raw = curvRaw.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(e.position(), p -> new double[7]);
        elementCurv[current] = key;
        switch (m) {
            case "CURVATURE_UP" -> {
                raw[0] += e.value();
                raw[4] = 1;
            }
            case "CURVATURE_DOWN" -> {
                raw[1] += e.value();
                raw[5] = 1;
            }
            default -> {
                raw[2] += e.value();
                raw[6] = 1;
            }
        }
        if (!m.equals("GAMMA") && Double.isFinite(e.shockSize())) {
            double expected = shock(cls, f.frtbBucket(), up(f.qualifier()));
            double delivered = cls.equals("GIRR") || cls.equals("CSR_NS") ? e.shockSize() / 10_000 : e.shockSize();
            if (Math.abs(delivered - expected) > 1e-9) {
                warnings.add(new Warning(f.name(), "SHOCK_SIZE " + e.shockSize() + " differs from the parameter file's shock " + expected
                        + "; the revaluations are used as delivered"));
            }
        }
        return null;
    }

    /** The curvature risk weight of a factor (MAR21.98, MAR21.99), with the GIRR shock switch. */
    double shock(String cls, String bucket, String name) {
        return switch (cls) {
            case "GIRR" -> params.girrCurvatureShock / (choices.girrCurvatureShock().equals("divided") && specified(name) ? params.girrDivisor : 1);
            case "CSR_NS" -> params.csrRw.getOrDefault(bucketNo(bucket), Double.NaN);
            case "EQ" -> params.eqSpotRw.getOrDefault(bucketNo(bucket), Double.NaN);
            case "COMM" -> params.cmRw.getOrDefault(bucketNo(bucket), Double.NaN);
            default -> params.fxRw / (params.fxSpecifiedPairs.contains(name + reporting) || params.fxSpecifiedPairs.contains(reporting + name)
                    ? params.fxDivisor : 1);
        };
    }

    /** CVR+ and CVR− of every curvature factor, per position then netted (MAR21.5(2)); the source and FX switches applied. */
    private void curvatures(Map<Integer, Set<String>> fxCurvature) {
        for (Map.Entry<String, Map<Integer, double[]>> en : curvRaw.entrySet()) {
            String key = en.getKey();
            boolean any = en.getValue().values().stream().anyMatch(r -> r[4] > 0 || r[5] > 0 || r[6] > 0);
            if (!any) {
                continue;                                             // deltas only: a linear factor has no curvature
            }
            String[] p = key.split("\\|", 3);
            String cls = p[0].equals("CSR") ? "CSR_NS" : p[0];
            String bucket = p[1];
            String name = p.length > 2 ? p[2] : p[1];
            double rw = shock(cls, bucket, name);
            boolean curve = cls.equals("GIRR") || cls.equals("CSR_NS");
            double h = rw * (curve ? 10_000 : 100);
            Curv c = new Curv(cls, bucket, key, name, rw, h);
            for (Map.Entry<Integer, double[]> pe : en.getValue().entrySet()) {
                double[] r = pe.getValue();
                boolean hasReval = r[4] > 0 && r[5] > 0;
                boolean hasGamma = r[6] > 0;
                if (!hasReval && !hasGamma) {
                    continue;                                         // this position's delta only: linear in the factor
                }
                double rws = rw * r[3];
                boolean useGamma = hasGamma && (!hasReval || choices.curvatureSource().equals("gamma"));
                double cu;
                double cd;
                if (useGamma) {
                    cu = -0.5 * r[2] * h * h;
                    cd = cu;
                    c.estimated = true;
                } else {
                    cu = -(r[0] - rws);
                    cd = -(r[1] + rws);
                    c.revalued = true;
                }
                double div = 1;
                if (cls.equals("FX")) {
                    String fx = choices.fxCurvatureDivisor();
                    boolean nonRef = fxCurvature.getOrDefault(pe.getKey(), Set.of()).size() >= 2;
                    if (fx.equals("all") || fx.equals("non-reporting-options") && nonRef) {
                        div = params.fxCurvatureDivisor;
                        c.divided = true;
                    }
                }
                cu /= div;
                cd /= div;
                c.up += r[0];
                c.down += r[1];
                c.gamma += r[2];
                c.deltaPart += rws;
                c.cvrUp += cu;
                c.cvrDown += cd;
                c.parts.put(pe.getKey(), new double[] {cu, cd});
            }
            if (!c.parts.isEmpty()) {
                curvByKey.put(key, c);
            }
        }
    }

    /** A bucket text as a number (−1 when it is not one). */
    static int bucketNo(String b) {
        try {
            return Integer.parseInt(b == null ? "" : b.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
