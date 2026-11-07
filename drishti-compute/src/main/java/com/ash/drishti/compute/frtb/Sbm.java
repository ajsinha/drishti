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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sensitivities-based method's aggregation (FRTB_SA_GUIDE.md sections 4.4 to 4.7; MAR21.4 to MAR21.7): within each bucket, across the
 * buckets of each class, for delta, vega and curvature, in the three correlation scenarios; the SBM of a scenario the sum over the
 * classes, the capital the largest. With the gradient of the reported scenario's charge in every weighted sensitivity and every CVR, so
 * that each position's Euler contribution adds up to the charge (every step is homogeneous of degree one).
 */
public final class Sbm {

    public static final String[] SCENARIOS = {"low", "medium", "high"};
    /** The ρ matrices kept for the explanation when a bucket has at most this many factors. */
    static final int KEEP_RHO = 300;

    /** One bucket of one class and measure. */
    public static final class Bucket {
        public final String cls;
        public final String measure;
        public final String bucket;
        /** The factors: indices into {@link SbmModel#risks} (delta, vega) or {@link SbmModel#curvs} (curvature). */
        public final int[] members;
        public final boolean absSum;
        /** K_b per scenario. */
        public final double[] k = new double[3];
        /** S_b as used across buckets per scenario (capped under the alternative specification). */
        public final double[] s = new double[3];
        /** Σ WS (delta, vega) of the bucket. */
        public double net;
        /** Curvature: K_b+ and K_b− per scenario, the side chosen ({@code up} or {@code down}), ΣCVR+ and ΣCVR−. */
        public final double[] kUp = new double[3];
        public final double[] kDown = new double[3];
        public final String[] side = new String[3];
        public double sumUp;
        public double sumDown;
        /** The medium ρ (curvature: ρ², before the scenario) between the members, when kept. */
        public double[][] rho;
        /** ∂K_b/∂x per member (the reported scenario; curvature: of the chosen side). */
        double[] gradK;

        Bucket(String cls, String measure, String bucket, int[] members, boolean absSum) {
            this.cls = cls;
            this.measure = measure;
            this.bucket = bucket;
            this.members = members;
            this.absSum = absSum;
        }
    }

    /** One class and measure: its buckets, γ (medium), the charge per scenario and whether the alternative specification applied. */
    public static final class ClassCharge {
        public final String cls;
        public final String measure;
        public final List<Bucket> buckets;
        public final double[][] gamma;
        public final double[] charge = new double[3];
        public final boolean[] alternative = new boolean[3];

        ClassCharge(String cls, String measure, List<Bucket> buckets, double[][] gamma) {
            this.cls = cls;
            this.measure = measure;
            this.buckets = buckets;
            this.gamma = gamma;
        }
    }

    /** The SBM of a scope. */
    public static final class Result {
        public final SbmModel model;
        public final List<ClassCharge> charges;
        public final double[] total = new double[3];
        public final int binding;
        public final int reported;
        /** ∂SBM(reported)/∂WS_k per risk and ∂/∂CVR_k (chosen side) per curvature factor. */
        public final double[] riskGrad;
        public final double[] curvGrad;

        Result(SbmModel model, List<ClassCharge> charges, int binding, int reported, double[] riskGrad, double[] curvGrad) {
            this.model = model;
            this.charges = charges;
            this.binding = binding;
            this.reported = reported;
            this.riskGrad = riskGrad;
            this.curvGrad = curvGrad;
        }

        /** The reported SBM. */
        public double sbm() {
            return total[reported];
        }

        /** The charge of a class and measure in a scenario (0 when the scope has none). */
        public double charge(String cls, String measure, int scenario) {
            for (ClassCharge c : charges) {
                if (c.cls.equals(cls) && c.measure.equals(measure)) {
                    return c.charge[scenario];
                }
            }
            return 0;
        }

        /**
         * A risk class's own total in a scenario: its delta, vega and curvature charges added (MAR21.6: the SBM adds the measures within a
         * class, then the classes), so the classes' totals add up to {@link #total}. 0 when the scope has none of the class.
         */
        public double classTotal(String cls, int scenario) {
            double t = 0;
            for (ClassCharge c : charges) {
                if (c.cls.equals(cls)) {
                    t += c.charge[scenario];
                }
            }
            return t;
        }

        /** The risk classes the charges name, in their order. */
        public List<String> classes() {
            return charges.stream().map(c -> c.cls).distinct().toList();
        }

        public ClassCharge classCharge(String cls, String measure) {
            return charges.stream().filter(c -> c.cls.equals(cls) && c.measure.equals(measure)).findFirst().orElse(null);
        }

        /** Each position's Euler contribution to the reported SBM, by position index (positions with no sensitivity: 0). */
        public double[] contributions(int positions) {
            double[] out = new double[positions];
            for (int k = 0; k < model.risks.size(); k++) {
                SbmModel.Risk r = model.risks.get(k);
                double g = riskGrad[k];
                if (g != 0) {
                    for (Map.Entry<Integer, Double> e : r.parts.entrySet()) {
                        out[e.getKey()] += r.rw * e.getValue() * g;
                    }
                }
            }
            for (ClassCharge c : charges) {
                if (!c.measure.equals(SbmModel.CURVATURE)) {
                    continue;
                }
                for (Bucket b : c.buckets) {
                    boolean upSide = "up".equals(b.side[reported]);
                    for (int m : b.members) {
                        double g = curvGrad[m];
                        if (g != 0) {
                            for (Map.Entry<Integer, double[]> e : model.curvs.get(m).parts.entrySet()) {
                                out[e.getKey()] += e.getValue()[upSide ? 0 : 1] * g;
                            }
                        }
                    }
                }
            }
            return out;
        }
    }

    private Sbm() {}

    /** The SBM of the model's factors. */
    public static Result compute(SbmModel m) {
        SbmCorrelations corr = new SbmCorrelations(m.params, m.choices);
        FrtbParams.Scenarios sc = m.params.scenarios;
        List<ClassCharge> charges = new ArrayList<>();
        double[] riskGrad = new double[m.risks.size()];
        double[] curvGrad = new double[m.curvs.size()];
        // the reported scenario is known only after the totals: keep every scenario's gradients, pick one at the end
        double[][] rg = new double[3][m.risks.size()];
        double[][] cg = new double[3][m.curvs.size()];
        for (String cls : SbmModel.CLASSES) {
            for (String measure : List.of(SbmModel.DELTA, SbmModel.VEGA)) {
                Map<String, List<Integer>> byBucket = new LinkedHashMap<>();
                for (int i = 0; i < m.risks.size(); i++) {
                    SbmModel.Risk r = m.risks.get(i);
                    if (r.cls.equals(cls) && r.measure.equals(measure)) {
                        byBucket.computeIfAbsent(r.bucket, x -> new ArrayList<>()).add(i);
                    }
                }
                if (!byBucket.isEmpty()) {
                    charges.add(linear(m, corr, sc, cls, measure, byBucket, rg));
                }
            }
            Map<String, List<Integer>> byBucket = new LinkedHashMap<>();
            for (int i = 0; i < m.curvs.size(); i++) {
                if (m.curvs.get(i).cls.equals(cls)) {
                    byBucket.computeIfAbsent(m.curvs.get(i).bucket, x -> new ArrayList<>()).add(i);
                }
            }
            if (!byBucket.isEmpty()) {
                charges.add(curvature(m, corr, sc, cls, byBucket, cg));
            }
        }
        double[] total = new double[3];
        for (ClassCharge c : charges) {
            for (int s = 0; s < 3; s++) {
                total[s] += c.charge[s];
            }
        }
        int binding = 1;
        for (int s : new int[] {0, 2}) {
            if (total[s] > total[binding]) {
                binding = s;
            }
        }
        int reported = switch (m.choices.scenario()) {
            case "low" -> 0;
            case "medium" -> 1;
            case "high" -> 2;
            default -> binding;
        };
        System.arraycopy(rg[reported], 0, riskGrad, 0, riskGrad.length);
        System.arraycopy(cg[reported], 0, curvGrad, 0, curvGrad.length);
        Result r = new Result(m, charges, binding, reported, riskGrad, curvGrad);
        System.arraycopy(total, 0, r.total, 0, 3);
        return r;
    }

    private static List<String> sorted(Map<String, List<Integer>> byBucket) {
        List<String> keys = new ArrayList<>(byBucket.keySet());
        keys.sort(Comparator.comparingInt((String b) -> SbmModel.bucketNo(b) < 0 ? Integer.MAX_VALUE : SbmModel.bucketNo(b)).thenComparing(b -> b));
        return keys;
    }

    private static ClassCharge linear(SbmModel m, SbmCorrelations corr, FrtbParams.Scenarios sc, String cls, String measure,
            Map<String, List<Integer>> byBucket, double[][] rg) {
        List<String> keys = sorted(byBucket);
        List<Bucket> buckets = new ArrayList<>();
        int nb = keys.size();
        double[][] gamma = new double[nb][nb];
        for (int x = 0; x < nb; x++) {
            for (int y = 0; y < nb; y++) {
                gamma[x][y] = x == y ? 0 : corr.gamma(cls, keys.get(x), keys.get(y));
            }
        }
        ClassCharge cc = new ClassCharge(cls, measure, buckets, gamma);
        double[][][] gradK = new double[3][nb][];
        for (int bi = 0; bi < nb; bi++) {
            List<Integer> idx = byBucket.get(keys.get(bi));
            int n = idx.size();
            Bucket b = new Bucket(cls, measure, keys.get(bi), idx.stream().mapToInt(Integer::intValue).toArray(), corr.otherSector(cls, keys.get(bi)));
            double[] ws = new double[n];
            for (int i = 0; i < n; i++) {
                ws[i] = m.risks.get(idx.get(i)).ws();
                b.net += ws[i];
            }
            double[][] rho = null;
            if (!b.absSum) {
                rho = new double[n][n];
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        rho[i][j] = i == j ? 1 : corr.rho(m.risks.get(idx.get(i)), m.risks.get(idx.get(j)));
                    }
                }
                if (n <= KEEP_RHO) {
                    b.rho = rho;
                }
            }
            for (int s = 0; s < 3; s++) {
                double[] g = new double[n];
                b.k[s] = b.absSum ? absSum(ws, g) : within(ws, rho, sc, s, g);
                gradK[s][bi] = g;
            }
            buckets.add(b);
        }
        for (int s = 0; s < 3; s++) {
            double[] k = new double[nb];
            double[] net = new double[nb];
            for (int bi = 0; bi < nb; bi++) {
                k[bi] = buckets.get(bi).k[s];
                net[bi] = buckets.get(bi).net;
            }
            double[] used = net.clone();
            double rad = radicand(k, used, gamma, sc, s, false);
            boolean alt = false;
            if (rad < 0) {
                alt = true;
                for (int bi = 0; bi < nb; bi++) {
                    used[bi] = Math.max(Math.min(net[bi], k[bi]), -k[bi]);
                }
                rad = radicand(k, used, gamma, sc, s, false);
            }
            double charge = Math.sqrt(Math.max(0, rad));
            cc.charge[s] = charge;
            cc.alternative[s] = alt;
            for (int bi = 0; bi < nb; bi++) {
                buckets.get(bi).s[s] = used[bi];
            }
            if (charge > 0) {
                for (int bi = 0; bi < nb; bi++) {
                    double cross = 0;
                    for (int ci = 0; ci < nb; ci++) {
                        if (ci != bi) {
                            cross += sc.apply(s, gamma[bi][ci]) * used[ci];
                        }
                    }
                    Bucket b = buckets.get(bi);
                    boolean capped = alt && (net[bi] > k[bi] || net[bi] < -k[bi]);
                    double sign = net[bi] > 0 ? 1 : -1;
                    double[] gk = gradK[s][bi];
                    for (int i = 0; i < b.members.length; i++) {
                        double dS = capped ? sign * gk[i] : 1;
                        rg[s][b.members[i]] = (k[bi] * gk[i] + cross * dS) / charge;
                    }
                }
            }
        }
        return cc;
    }

    /** Σ_b K_b² + Σ_{b≠c} γ_bc(s)·S_b·S_c (curvature: γ² and ψ when {@code psi}). */
    static double radicand(double[] k, double[] used, double[][] gamma, FrtbParams.Scenarios sc, int s, boolean psi) {
        double rad = 0;
        int nb = k.length;
        for (int bi = 0; bi < nb; bi++) {
            rad += k[bi] * k[bi];
            for (int ci = 0; ci < nb; ci++) {
                if (ci != bi && !(psi && used[bi] < 0 && used[ci] < 0)) {
                    rad += sc.apply(s, gamma[bi][ci]) * used[bi] * used[ci];
                }
            }
        }
        return rad;
    }

    /** K_b = √max(0, Σ_k Σ_l ρ_kl(s)·WS_k·WS_l), its gradient into {@code g}. */
    static double within(double[] ws, double[][] rho, FrtbParams.Scenarios sc, int s, double[] g) {
        int n = ws.length;
        double[] row = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double r = 0;
            for (int j = 0; j < n; j++) {
                r += (i == j ? 1 : sc.apply(s, rho[i][j])) * ws[j];
            }
            row[i] = r;
            sum += r * ws[i];
        }
        double k = Math.sqrt(Math.max(0, sum));
        for (int i = 0; i < n; i++) {
            g[i] = k > 0 ? row[i] / k : 0;
        }
        return k;
    }

    /** The other-sector bucket: K_b = Σ|WS_k| (MAR21.56, MAR21.79). */
    static double absSum(double[] ws, double[] g) {
        double k = 0;
        for (int i = 0; i < ws.length; i++) {
            k += Math.abs(ws[i]);
            g[i] = Math.signum(ws[i]);
        }
        return k;
    }

    private static ClassCharge curvature(SbmModel m, SbmCorrelations corr, FrtbParams.Scenarios sc, String cls, Map<String, List<Integer>> byBucket,
            double[][] cg) {
        List<String> keys = sorted(byBucket);
        int nb = keys.size();
        List<Bucket> buckets = new ArrayList<>();
        double[][] gamma2 = new double[nb][nb];
        for (int x = 0; x < nb; x++) {
            for (int y = 0; y < nb; y++) {
                double g = corr.gamma(cls, keys.get(x), keys.get(y));
                gamma2[x][y] = x == y ? 0 : g * g;
            }
        }
        ClassCharge cc = new ClassCharge(cls, SbmModel.CURVATURE, buckets, gamma2);
        double[][][] gradK = new double[3][nb][];
        for (int bi = 0; bi < nb; bi++) {
            List<Integer> idx = byBucket.get(keys.get(bi));
            int n = idx.size();
            Bucket b = new Bucket(cls, SbmModel.CURVATURE, keys.get(bi), idx.stream().mapToInt(Integer::intValue).toArray(),
                    corr.otherSector(cls, keys.get(bi)));
            double[] up = new double[n];
            double[] dn = new double[n];
            for (int i = 0; i < n; i++) {
                SbmModel.Curv c = m.curvs.get(idx.get(i));
                up[i] = c.cvrUp;
                dn[i] = c.cvrDown;
                b.sumUp += up[i];
                b.sumDown += dn[i];
            }
            double[][] rho2 = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double r = corr.curvatureRho(m.curvs.get(idx.get(i)), m.curvs.get(idx.get(j)));
                    rho2[i][j] = i == j ? 1 : r * r;
                }
            }
            if (n <= KEEP_RHO) {
                b.rho = rho2;
            }
            for (int s = 0; s < 3; s++) {
                double[] gu = new double[n];
                double[] gd = new double[n];
                double ku = b.absSum ? positiveSum(up, gu) : curvatureK(up, rho2, sc, s, gu);
                double kd = b.absSum ? positiveSum(dn, gd) : curvatureK(dn, rho2, sc, s, gd);
                b.kUp[s] = ku;
                b.kDown[s] = kd;
                boolean upSide = ku > kd || ku == kd && b.sumUp > b.sumDown;
                b.side[s] = upSide ? "up" : "down";
                b.k[s] = Math.max(ku, kd);
                b.s[s] = upSide ? b.sumUp : b.sumDown;
                gradK[s][bi] = upSide ? gu : gd;
            }
            buckets.add(b);
        }
        for (int s = 0; s < 3; s++) {
            double[] k = new double[nb];
            double[] used = new double[nb];
            for (int bi = 0; bi < nb; bi++) {
                k[bi] = buckets.get(bi).k[s];
                used[bi] = buckets.get(bi).s[s];
            }
            double charge = Math.sqrt(Math.max(0, radicand(k, used, gamma2, sc, s, true)));
            cc.charge[s] = charge;
            if (charge > 0) {
                for (int bi = 0; bi < nb; bi++) {
                    double cross = 0;
                    for (int ci = 0; ci < nb; ci++) {
                        if (ci != bi && !(used[bi] < 0 && used[ci] < 0)) {
                            cross += sc.apply(s, gamma2[bi][ci]) * used[ci];
                        }
                    }
                    Bucket b = buckets.get(bi);
                    double[] gk = gradK[s][bi];
                    for (int i = 0; i < b.members.length; i++) {
                        cg[s][b.members[i]] = (k[bi] * gk[i] + cross) / charge;
                    }
                }
            }
        }
        return cc;
    }

    /** K_b± = √max(0, Σ_k max(CVR_k, 0)² + Σ_{k≠l} ρ²_kl(s)·CVR_k·CVR_l·ψ_kl) (MAR21.5(3)), its gradient into {@code g}. */
    static double curvatureK(double[] cvr, double[][] rho2, FrtbParams.Scenarios sc, int s, double[] g) {
        int n = cvr.length;
        double[] row = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double pos = Math.max(cvr[i], 0);
            double r = pos;
            for (int j = 0; j < n; j++) {
                if (j != i && !(cvr[i] < 0 && cvr[j] < 0)) {
                    r += sc.apply(s, rho2[i][j]) * cvr[j];
                }
            }
            row[i] = r;
            sum += pos * pos;
            for (int j = 0; j < n; j++) {
                if (j != i && !(cvr[i] < 0 && cvr[j] < 0)) {
                    sum += sc.apply(s, rho2[i][j]) * cvr[i] * cvr[j];
                }
            }
        }
        double k = Math.sqrt(Math.max(0, sum));
        for (int i = 0; i < n; i++) {
            g[i] = k > 0 ? row[i] / k : 0;
        }
        return k;
    }

    /** The other-sector bucket's curvature side: Σ_k max(CVR_k, 0). */
    static double positiveSum(double[] cvr, double[] g) {
        double k = 0;
        for (int i = 0; i < cvr.length; i++) {
            if (cvr[i] > 0) {
                k += cvr[i];
                g[i] = 1;
            }
        }
        return k;
    }
}
