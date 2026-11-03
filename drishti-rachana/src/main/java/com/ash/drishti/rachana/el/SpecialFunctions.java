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
package com.ash.drishti.rachana.el;

import java.util.function.DoubleUnaryOperator;

/**
 * The numerics behind the distributions ({@link Distributions}), each a well-known, published algorithm (no library: nothing for it is
 * on Rachana's class path, and a dependency for a few hundred lines is not worth its weight):
 *
 * <ul>
 *   <li>{@link #lnGamma}: Lanczos's approximation with g = 7 and nine coefficients (C. Lanczos, "A precise approximation of the gamma
 *       function", SIAM J. Numer. Anal. B 1, 1964; the coefficients as P. Godfrey computed them), relative error about 1e-15, and the
 *       reflection formula below one half;</li>
 *   <li>{@link #gammaP}, {@link #gammaQ}: the regularised incomplete gamma functions, by the series below a + 1 and the continued
 *       fraction (modified Lentz) above (Press, Teukolsky, Vetterling, Flannery, <i>Numerical Recipes</i>, 3rd ed., section 6.2);</li>
 *   <li>{@link #betaI}: the regularised incomplete beta function by its continued fraction, modified Lentz, with the symmetry
 *       I<sub>x</sub>(a, b) = 1 − I<sub>1−x</sub>(b, a) where the fraction converges faster (<i>Numerical Recipes</i>, section 6.4);</li>
 *   <li>{@link #erfc}, {@link #normalCdf}: from the incomplete gamma, erfc(x) = Q(½, x²), so a tail keeps its relative accuracy;</li>
 *   <li>{@link #normalInverse}: P. J. Acklam's rational approximation (2003, relative error 1.15e-9) refined by one step of Halley's
 *       method on {@link #normalCdf}, which brings it to the precision of the CDF;</li>
 *   <li>{@link #invert}: any other continuous inverse by bisection on the CDF within a bracket grown until it holds the probability, to a
 *       relative 1e-15 or {@value #MAX_BISECTIONS} halvings.</li>
 * </ul>
 * Each converges to a relative {@value #EPS} or stops at its iteration bound and says so ({@link IllegalStateException}), never returning a
 * figure it has not reached.
 */
final class SpecialFunctions {

    private SpecialFunctions() {}

    /** The relative precision a series or continued fraction is taken to. */
    static final double EPS = 1e-16;

    /** The smallest number the continued fractions use in place of a zero (Lentz). */
    private static final double FPMIN = 1e-300;

    /** The most terms a series or continued fraction takes. */
    static final int MAX_TERMS = 100_000;

    /** The most halvings {@link #invert} makes. */
    static final int MAX_BISECTIONS = 2_000;

    private static final double[] LANCZOS = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

    private static final double LN_SQRT_2PI = 0.5 * Math.log(2 * Math.PI);

    /** ln |Γ(x)|, x not 0 nor a negative whole number. */
    static double lnGamma(double x) {
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lnGamma(1 - x);
        }
        double z = x - 1;
        double a = LANCZOS[0];
        double t = z + 7.5;
        for (int i = 1; i < 9; i++) {
            a += LANCZOS[i] / (z + i);
        }
        return LN_SQRT_2PI + (z + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** Γ(x): exact for whole numbers up to 170 (the factorial), else from {@link #lnGamma} with its sign; NaN at 0 and negative wholes. */
    static double gamma(double x) {
        if (x == Math.rint(x)) {
            if (x <= 0) {
                return Double.NaN;
            }
            if (x <= 171) {
                double f = 1;
                for (int i = 2; i < x; i++) {
                    f *= i;
                }
                return f;
            }
        }
        double g = Math.exp(lnGamma(x));
        // Γ is negative between −1 and 0, −3 and −2 …: where ⌊x⌋ is odd and negative
        return x < 0 && ((long) Math.floor(x)) % 2 != 0 ? -g : g;
    }

    /** ln n! for a whole n ≥ 0. */
    static double lnFactorial(double n) {
        return lnGamma(n + 1);
    }

    /** ln C(n, k), the log of the binomial coefficient, 0 ≤ k ≤ n. */
    static double lnChoose(double n, double k) {
        return lnFactorial(n) - lnFactorial(k) - lnFactorial(n - k);
    }

    /** P(a, x), the regularised lower incomplete gamma function, a > 0, x ≥ 0. */
    static double gammaP(double a, double x) {
        if (x <= 0) {
            return 0;
        }
        return x < a + 1 ? gammaSeries(a, x) : 1 - gammaFraction(a, x);
    }

    /** Q(a, x) = 1 − P(a, x), computed directly in the upper tail. */
    static double gammaQ(double a, double x) {
        if (x <= 0) {
            return 1;
        }
        return x < a + 1 ? 1 - gammaSeries(a, x) : gammaFraction(a, x);
    }

    private static double gammaSeries(double a, double x) {
        double ap = a;
        double sum = 1 / a;
        double del = sum;
        for (int n = 0; n < MAX_TERMS; n++) {
            ap += 1;
            del *= x / ap;
            sum += del;
            if (Math.abs(del) < Math.abs(sum) * EPS) {
                return sum * Math.exp(-x + a * Math.log(x) - lnGamma(a));
            }
        }
        throw new IllegalStateException("the incomplete gamma series did not converge for a = " + a + ", x = " + x);
    }

    private static double gammaFraction(double a, double x) {
        double b = x + 1 - a;
        double c = 1 / FPMIN;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i < MAX_TERMS; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < FPMIN) {
                d = FPMIN;
            }
            c = b + an / c;
            if (Math.abs(c) < FPMIN) {
                c = FPMIN;
            }
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < EPS) {
                return Math.exp(-x + a * Math.log(x) - lnGamma(a)) * h;
            }
        }
        throw new IllegalStateException("the incomplete gamma fraction did not converge for a = " + a + ", x = " + x);
    }

    /** I<sub>x</sub>(a, b), the regularised incomplete beta function, a, b > 0, 0 ≤ x ≤ 1. */
    static double betaI(double a, double b, double x) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }
        double front = Math.exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * Math.log(x) + b * Math.log1p(-x));
        if (x < (a + 1) / (a + b + 2)) {
            return front * betaFraction(a, b, x) / a;
        }
        return 1 - front * betaFraction(b, a, 1 - x) / b;
    }

    private static double betaFraction(double a, double b, double x) {
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double c = 1;
        double d = 1 - qab * x / qap;
        if (Math.abs(d) < FPMIN) {
            d = FPMIN;
        }
        d = 1 / d;
        double h = d;
        for (int m = 1; m < MAX_TERMS; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < FPMIN) {
                d = FPMIN;
            }
            c = 1 + aa / c;
            if (Math.abs(c) < FPMIN) {
                c = FPMIN;
            }
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < FPMIN) {
                d = FPMIN;
            }
            c = 1 + aa / c;
            if (Math.abs(c) < FPMIN) {
                c = FPMIN;
            }
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < EPS) {
                return h;
            }
        }
        throw new IllegalStateException("the incomplete beta fraction did not converge for a = " + a + ", b = " + b + ", x = " + x);
    }

    /** erf(x) = P(½, x²) with the sign of x. */
    static double erf(double x) {
        double p = gammaP(0.5, x * x);
        return x < 0 ? -p : p;
    }

    /** erfc(x) = 1 − erf(x), accurate in the upper tail: Q(½, x²) for x ≥ 0, 2 − Q for x below. */
    static double erfc(double x) {
        double q = gammaQ(0.5, x * x);
        return x < 0 ? 2 - q : q;
    }

    /** Φ(z), the standard normal distribution function, = ½ erfc(−z / √2). */
    static double normalCdf(double z) {
        return 0.5 * erfc(-z / Math.sqrt(2));
    }

    /** φ(z), the standard normal density. */
    static double normalPdf(double z) {
        return Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
    }

    private static final double[] A = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
        -3.066479806614716e+01, 2.506628277459239e+00};
    private static final double[] B = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
        -1.328068155288572e+01};
    private static final double[] C = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
        4.374664141464968e+00, 2.938163982698783e+00};
    private static final double[] D = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};

    /** Φ⁻¹(p), 0 &lt; p &lt; 1: Acklam's approximation refined by one Halley step. */
    static double normalInverse(double p) {
        double x;
        double low = 0.02425;
        if (p < low) {
            double q = Math.sqrt(-2 * Math.log(p));
            x = (((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1);
        } else if (p <= 1 - low) {
            double q = p - 0.5;
            double r = q * q;
            x = (((((A[0] * r + A[1]) * r + A[2]) * r + A[3]) * r + A[4]) * r + A[5]) * q
                    / (((((B[0] * r + B[1]) * r + B[2]) * r + B[3]) * r + B[4]) * r + 1);
        } else {
            double q = Math.sqrt(-2 * Math.log1p(-p));
            x = -(((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1);
        }
        // one Halley step on Φ: in the upper tail on the complement, where 1 − p keeps its digits
        double e = p > 0.5 ? -(0.5 * erfc(x / Math.sqrt(2)) - (1 - p)) : 0.5 * erfc(-x / Math.sqrt(2)) - p;
        double u = e * Math.sqrt(2 * Math.PI) * Math.exp(x * x / 2);
        return x - u / (1 + x * u / 2);
    }

    /**
     * The x at which a continuous, non-decreasing {@code cdf} reaches p, searched from {@code lo} (the support's lower end, or a start
     * for a support unbounded below) and {@code hi} (a first guess at an upper end, doubled until the CDF there reaches p).
     */
    static double invert(DoubleUnaryOperator cdf, double p, double lo, double hi, boolean unboundedBelow) {
        int grow = 0;
        while (cdf.applyAsDouble(hi) < p) {
            lo = hi;
            hi = hi <= 0 ? 1 : hi * 2;
            if (++grow > 2_000 || Double.isInfinite(hi)) {
                throw new IllegalStateException("no value reaches the probability " + p);
            }
        }
        while (unboundedBelow && cdf.applyAsDouble(lo) > p) {
            hi = lo;
            lo = lo >= 0 ? -1 : lo * 2;
            if (++grow > 4_000 || Double.isInfinite(lo)) {
                throw new IllegalStateException("no value reaches the probability " + p);
            }
        }
        for (int i = 0; i < MAX_BISECTIONS; i++) {
            double mid = lo + (hi - lo) / 2;
            if (mid == lo || mid == hi || hi - lo <= 1e-15 * Math.abs(mid)) {
                return mid;
            }
            if (cdf.applyAsDouble(mid) < p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo + (hi - lo) / 2;
    }
}
