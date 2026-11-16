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
package com.ash.drishti.banking.compute.var;

import com.ash.drishti.compute.linalg.LinearAlgebra;
import java.util.List;

/**
 * Parametric (variance–covariance) VaR and ES from a scope's own scenario P&amp;L vector (docs/guides/VAR_GUIDE.md, VAR_GUIDE.md
 * section 18.15): the normal fitted to the vector (μ, σ), VaR = z·σ − μ, ES = σ·φ(z_ES)/(1 − c_ES) − μ, the shape, the fat-tail gap's
 * inputs, Euler components that add up to the VaR exactly (the −μᵢ term included), the normal curve over the histogram and the parts'
 * covariance matrix. A <b>cross-check, never the official VaR</b>. Pure functions on {@code double[]}, two passes in float64 in scenario
 * order: the same bits on any number of threads or shards (the input vector is).
 */
public final class ParametricVar {

    /** {@code drishti.var.parametric.weighting}. */
    public enum Weighting {
        EQUAL, EWMA;

        public static Weighting of(String s) {
            if (s == null || s.isBlank() || s.trim().equalsIgnoreCase("equal")) {
                return EQUAL;
            }
            if (s.trim().equalsIgnoreCase("ewma")) {
                return EWMA;
            }
            throw new IllegalArgumentException("drishti.var.parametric.weighting is equal or ewma, not " + s);
        }

        public String text() {
            return this == EQUAL ? "equal" : "ewma";
        }
    }

    /**
     * The weighting.
     *
     * @param weighting equal (1/N, σ with N − 1) or ewma (RiskMetrics, no small-sample correction)
     * @param lambda EWMA's decay, in (0.5, 1); read only with ewma
     */
    public record Settings(Weighting weighting, double lambda) {
        public static final Settings EQUAL = new Settings(Weighting.EQUAL, 0.94);

        public Settings {
            if (weighting == Weighting.EWMA && !(lambda > 0.5 && lambda < 1)) {
                throw new IllegalArgumentException("drishti.var.parametric.lambda must be in (0.5, 1), not " + lambda);
            }
        }
    }

    private ParametricVar() {}

    // ---- the fit ---------------------------------------------------------------------------------------------------------------

    /**
     * The weights of N scenarios (oldest first): equal {@code 1/N}; ewma {@code (1−λ)·λ^(N−j)/(1−λ^N)}, j = 1..N, the newest the largest. With
     * equal weights the fit uses the plain mean and the sample variance (N − 1), not these.
     */
    public static double[] weights(int n, Settings s) {
        double[] w = new double[n];
        if (s.weighting() == Weighting.EQUAL) {
            java.util.Arrays.fill(w, 1.0 / n);
            return w;
        }
        double l = s.lambda();
        double norm = (1 - l) / (1 - Math.pow(l, n));
        for (int j = 1; j <= n; j++) {
            w[j - 1] = norm * Math.pow(l, n - j);
        }
        return w;
    }

    /** μ, σ, the shape and the weights of a vector (σ = 0 for a constant vector or one scenario). */
    public record Fit(int n, double mu, double sigma, double skew, double excessKurtosis, double[] weights, Settings settings) {}

    public static Fit fit(double[] v, Settings s) {
        int n = v.length;
        if (n == 0) {
            return new Fit(0, 0, 0, 0, 0, new double[0], s);
        }
        double[] w = weights(n, s);
        double mu = 0;
        if (s.weighting() == Weighting.EQUAL) {
            for (double x : v) {
                mu += x;
            }
            mu /= n;
        } else {
            for (int j = 0; j < n; j++) {
                mu += w[j] * v[j];
            }
        }
        double m2 = 0;
        double m3 = 0;
        double m4 = 0;
        double ss = 0;
        for (int j = 0; j < n; j++) {
            double d = v[j] - mu;
            double d2 = d * d;
            double wj = s.weighting() == Weighting.EQUAL ? 1.0 / n : w[j];
            ss += d2;
            m2 += wj * d2;
            m3 += wj * d2 * d;
            m4 += wj * d2 * d2;
        }
        double var = s.weighting() == Weighting.EQUAL ? (n > 1 ? ss / (n - 1) : 0) : m2;
        double sigma = Math.sqrt(var);
        double skew = m2 > 0 ? m3 / Math.pow(m2, 1.5) : 0;
        double kurt = m2 > 0 ? m4 / (m2 * m2) - 3 : 0;
        return new Fit(n, mu, sigma, skew, kurt, w, s);
    }

    // ---- the normal ------------------------------------------------------------------------------------------------------------

    private static final double SQRT_2PI = Math.sqrt(2 * Math.PI);

    /** The standard normal density φ(x). */
    public static double phi(double x) {
        return Math.exp(-0.5 * x * x) / SQRT_2PI;
    }

    /**
     * The standard normal distribution Φ(x), Marsaglia's series (J. Stat. Software 11(4), 2004): Φ(x) = ½ + φ(x)(x + x³/3 + x⁵/15 + …), an
     * absolute error under 10⁻¹⁵ on |x| &lt; 8 (0 or 1 beyond).
     */
    public static double cdf(double x) {
        if (x < -8) {
            return 0;
        }
        if (x > 8) {
            return 1;
        }
        double s = x;
        double t = x;
        double q = x * x;
        for (int i = 3; i < 2000; i += 2) {
            t = t * q / i;
            double before = s;
            s += t;
            if (s == before) {
                break;
            }
        }
        return 0.5 + s * phi(x);
    }

    /** Φ⁻¹(p): Acklam's rational approximation refined by one Halley step (a relative error under 10⁻¹⁵ on (10⁻¹⁰, 1 − 10⁻¹⁰)). */
    public static double inverse(double p) {
        if (!(p > 0 && p < 1)) {
            throw new IllegalArgumentException("a probability in (0, 1), not " + p);
        }
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01,
            2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00,
            2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double lo = 0.02425;
        double x;
        if (p < lo) {
            double q = Math.sqrt(-2 * Math.log(p));
            x = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        } else if (p <= 1 - lo) {
            double q = p - 0.5;
            double r = q * q;
            x = (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                    / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        } else {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            x = -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        for (int i = 0; i < 2; i++) {                                     // Halley: a step from Acklam's 1e-9 to the double's precision
            double e = cdf(x) - p;
            double u = e * SQRT_2PI * Math.exp(x * x / 2);
            x = x - u / (1 + x * u / 2);
        }
        return x;
    }

    /** φ(z_ES)/(1 − c_ES): ES's multiplier of σ (2.3378 at 97.5%). */
    public static double esFactor(double esConfidence) {
        return phi(inverse(esConfidence)) / (1 - esConfidence);
    }

    // ---- the figures -----------------------------------------------------------------------------------------------------------

    /**
     * A scope's parametric figures.
     *
     * @param var z·σ − μ, positive for a loss (not floored at zero)
     * @param es σ·φ(z_ES)/(1 − c_ES) − μ
     * @param beyond scenarios whose P&amp;L is below −var
     */
    public record Figures(Fit fit, double confidence, double esConfidence, double z, double zEs, double esFactor, double var, double es,
            double zSigma, int beyond) {

        public double expectedBeyond() {
            return fit.n() * (1 - confidence);
        }
    }

    public static Figures of(double[] v, double confidence, double esConfidence, Settings s) {
        Fit f = fit(v, s);
        double z = inverse(confidence);
        double zEs = inverse(esConfidence);
        double ef = phi(zEs) / (1 - esConfidence);
        double zs = z * f.sigma();
        double var = zs - f.mu();
        double es = f.sigma() * ef - f.mu();
        int beyond = 0;
        for (double x : v) {
            if (x < -var) {
                beyond++;
            }
        }
        return new Figures(f, confidence, esConfidence, z, zEs, ef, var, es, zs, beyond);
    }

    // ---- the decomposition -----------------------------------------------------------------------------------------------------

    /**
     * A part's parametric figures against its scope (section 3 of the design).
     *
     * @param cov cov(pᵢ, v), with σ's weights and normalisation
     * @param rho the part's correlation with the scope (0 when either σ is 0)
     * @param componentVar z·cov/σ − μᵢ (−μᵢ when σ = 0): the parts of a partition add up to the VaR exactly
     * @param componentEs φ(z_ES)/(1 − c_ES)·cov/σ − μᵢ
     * @param standaloneVar z·σᵢ − μᵢ
     * @param incrementalVar VaR(v) − VaR(v − pᵢ)
     */
    public record Component(double mu, double sigma, double cov, double rho, double componentVar, double componentEs, double standaloneVar,
            double incrementalVar) {}

    public static Component component(double[] part, double[] v, Figures scope) {
        Settings s = scope.fit().settings();
        Fit pf = fit(part, s);
        double cov = cov(part, pf.mu(), v, scope.fit().mu(), scope.fit().weights(), s);
        double sig = scope.fit().sigma();
        double rho = sig > 0 && pf.sigma() > 0 ? cov / (pf.sigma() * sig) : 0;
        double cv = (sig > 0 ? scope.z() * cov / sig : 0) - pf.mu();
        double ce = (sig > 0 ? scope.esFactor() * cov / sig : 0) - pf.mu();
        double sa = scope.z() * pf.sigma() - pf.mu();
        double[] rest = new double[v.length];
        for (int j = 0; j < v.length; j++) {
            rest[j] = v[j] - part[j];
        }
        Fit rf = fit(rest, s);
        double inc = scope.var() - (scope.z() * rf.sigma() - rf.mu());
        return new Component(pf.mu(), pf.sigma(), cov, rho, cv, ce, sa, inc);
    }

    /** cov(p, v) in index order: equal weights {@code Σ(p−μp)(v−μ)/(N−1)}, ewma {@code Σ w(p−μp)(v−μ)}. */
    static double cov(double[] p, double mp, double[] v, double mv, double[] w, Settings s) {
        int n = v.length;
        double c = 0;
        if (s.weighting() == Weighting.EQUAL) {
            for (int j = 0; j < n; j++) {
                c += (p[j] - mp) * (v[j] - mv);
            }
            return n > 1 ? c / (n - 1) : 0;
        }
        for (int j = 0; j < n; j++) {
            c += w[j] * (p[j] - mp) * (v[j] - mv);
        }
        return c;
    }

    /**
     * The parts' covariance matrix {@code C = XᵀX} with X the centred, weighted N × k matrix of their vectors (one {@code dgemm} through
     * {@code la}: OpenBLAS where bound, Java otherwise; the components never come from it) and the correlations.
     *
     * @return {covariance[k][k], correlation[k][k]}
     */
    public static double[][][] matrix(List<double[]> parts, Settings s, LinearAlgebra la) {
        int k = parts.size();
        if (k == 0) {
            return new double[][][] {new double[0][0], new double[0][0]};
        }
        int n = parts.get(0).length;
        double[] w = weights(n, s);
        double[] x = new double[n * k];                                    // column-major N x k
        for (int i = 0; i < k; i++) {
            double[] p = parts.get(i);
            double m = fit(p, s).mu();
            for (int j = 0; j < n; j++) {
                double f = s.weighting() == Weighting.EQUAL ? (n > 1 ? 1.0 / Math.sqrt(n - 1) : 0) : Math.sqrt(w[j]);
                x[j + i * n] = f * (p[j] - m);
            }
        }
        double[] c = new double[k * k];
        la.dgemm(true, false, k, k, n, 1.0, x, 0, n, x, 0, n, 0.0, c, 0, k);
        double[][] cov = new double[k][k];
        double[][] cor = new double[k][k];
        for (int i = 0; i < k; i++) {
            for (int l = 0; l < k; l++) {
                cov[i][l] = c[i + l * k];
            }
        }
        for (int i = 0; i < k; i++) {
            for (int l = 0; l < k; l++) {
                double d = Math.sqrt(cov[i][i] * cov[l][l]);
                cor[i][l] = i == l ? 1 : d > 0 ? cov[i][l] / d : 0;
            }
        }
        return new double[][][] {cov, cor};
    }

    // ---- the curve over the histogram ------------------------------------------------------------------------------------------

    /**
     * The fitted normal on the histogram's scale: {@code points} x evenly from the first to the last edge, y = n·binWidth·φ((x − μ)/σ)/σ
     * (expected scenarios per bin), and each bin's expected count n·(Φ((b − μ)/σ) − Φ((a − μ)/σ)).
     */
    public record Curve(double[] x, double[] y, double[] expected, double binWidth) {}

    public static Curve curve(Figures f, double[] edges, int points) {
        int bins = Math.max(0, edges.length - 1);
        double bw = bins == 0 ? 0 : edges[1] - edges[0];
        double mu = f.fit().mu();
        double sig = f.fit().sigma();
        int n = f.fit().n();
        int pts = Math.max(2, points);
        double[] x = new double[pts];
        double[] y = new double[pts];
        double lo = bins == 0 ? mu : edges[0];
        double hi = bins == 0 ? mu : edges[bins];
        for (int i = 0; i < pts; i++) {
            x[i] = lo + (hi - lo) * i / (pts - 1);
            y[i] = sig > 0 ? n * bw * phi((x[i] - mu) / sig) / sig : 0;
        }
        double[] e = new double[bins];
        for (int b = 0; b < bins; b++) {
            e[b] = sig > 0 ? n * (cdf((edges[b + 1] - mu) / sig) - cdf((edges[b] - mu) / sig)) : (mu >= edges[b] && mu < edges[b + 1] ? n : 0);
        }
        return new Curve(x, y, e, bw);
    }
}
