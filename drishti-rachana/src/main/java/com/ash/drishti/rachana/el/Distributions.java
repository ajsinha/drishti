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

import java.math.BigDecimal;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Distributions and the statistics a person builds a test from (RACHANA_REFERENCE.md section F9), each Excel's function under one name:
 * {@code norm_dist}, {@code norm_inv}, {@code norm_s_dist}, {@code norm_s_inv}, {@code lognorm_dist}, {@code lognorm_inv},
 * {@code t_dist}, {@code t_inv}, {@code chisq_dist}, {@code chisq_inv}, {@code f_dist}, {@code f_inv}, {@code binom_dist},
 * {@code binom_inv}, {@code poisson_dist}, {@code expon_dist}, {@code gamma_dist}, {@code gamma_inv}, {@code gamma}, {@code gamma_ln},
 * {@code beta_dist}, {@code beta_inv}, {@code weibull_dist}, {@code hypgeom_dist}, {@code negbinom_dist}, {@code erf}, {@code erfc},
 * {@code standardize}, {@code confidence_norm}, {@code confidence_t}, {@code fisher}, {@code fisher_inv}.
 *
 * <p>Rules: an empty argument gives an empty result, a masked one a masked result; text that is not a number is {@code DRS-2102}; an
 * argument outside the function's domain (a standard deviation of 0, a probability of 1.2: Excel's {@code #NUM!}) is {@code DRS-2102}
 * naming the argument, which {@code iferror} turns into a fallback. {@code cumulative} is optional and true by default (Excel requires it);
 * degrees of freedom and counts are cut to whole numbers, as Excel cuts them. These are real-number functions: computed in binary floating
 * point, as Excel computes them, by the algorithms {@link SpecialFunctions} cites; the tests hold them to Excel's documented figures.
 * The VaR engine's backtests keep their own statistics; these serve the tests a person builds.
 */
final class Distributions {

    private Distributions() {}

    static void register(Functions.Table t) {
        t.fn("norm_dist", 3, 4, (a, c) -> normDist(a));
        t.fn("norm_inv", 3, 3, (a, c) -> {
            Double p = num("norm_inv", a, 0);
            Double m = num("norm_inv", a, 1);
            Double s = num("norm_inv", a, 2);
            return p == null || m == null || s == null ? null : out("norm_inv", m + positive("norm_inv", "the standard deviation", s)
                    * SpecialFunctions.normalInverse(open("norm_inv", p)));
        });
        t.fn("norm_s_dist", 1, 2, (a, c) -> {
            Double z = num("norm_s_dist", a, 0);
            Boolean cum = cumulative("norm_s_dist", a, 1);
            return z == null || cum == null ? null : out("norm_s_dist", cum ? SpecialFunctions.normalCdf(z) : SpecialFunctions.normalPdf(z));
        });
        t.fn("norm_s_inv", 1, 1, (a, c) -> {
            Double p = num("norm_s_inv", a, 0);
            return p == null ? null : out("norm_s_inv", SpecialFunctions.normalInverse(open("norm_s_inv", p)));
        });
        t.fn("lognorm_dist", 3, 4, (a, c) -> lognormDist(a));
        t.fn("lognorm_inv", 3, 3, (a, c) -> {
            Double p = num("lognorm_inv", a, 0);
            Double m = num("lognorm_inv", a, 1);
            Double s = num("lognorm_inv", a, 2);
            return p == null || m == null || s == null ? null : out("lognorm_inv", Math.exp(m + positive("lognorm_inv",
                    "the standard deviation", s) * SpecialFunctions.normalInverse(open("lognorm_inv", p))));
        });
        t.fn("t_dist", 2, 3, (a, c) -> tDist(a));
        t.fn("t_inv", 2, 2, (a, c) -> {
            Double p = num("t_inv", a, 0);
            Double df = df("t_inv", a, 1);
            return p == null || df == null ? null : out("t_inv", tInverse(open("t_inv", p), df));
        });
        t.fn("chisq_dist", 2, 3, (a, c) -> chisqDist(a));
        t.fn("chisq_inv", 2, 2, (a, c) -> {
            Double p = num("chisq_inv", a, 0);
            Double df = df("chisq_inv", a, 1);
            if (p == null || df == null) {
                return null;
            }
            double q = halfOpen("chisq_inv", p);
            return out("chisq_inv", q == 0 ? 0 : invert("chisq_inv", x -> SpecialFunctions.gammaP(df / 2, x / 2), q, df));
        });
        t.fn("f_dist", 3, 4, (a, c) -> fDist(a));
        t.fn("f_inv", 3, 3, (a, c) -> {
            Double p = num("f_inv", a, 0);
            Double d1 = df("f_inv", a, 1);
            Double d2 = df("f_inv", a, 2);
            if (p == null || d1 == null || d2 == null) {
                return null;
            }
            double q = halfOpen("f_inv", p);
            return out("f_inv", q == 0 ? 0 : invert("f_inv", x -> fCdf(x, d1, d2), q, 1));
        });
        t.fn("binom_dist", 3, 4, (a, c) -> binomDist(a));
        t.fn("binom_inv", 3, 3, (a, c) -> binomInv(a));
        t.fn("poisson_dist", 2, 3, (a, c) -> poissonDist(a));
        t.fn("expon_dist", 2, 3, (a, c) -> {
            Double x = num("expon_dist", a, 0);
            Double l = num("expon_dist", a, 1);
            Boolean cum = cumulative("expon_dist", a, 2);
            if (x == null || l == null || cum == null) {
                return null;
            }
            nonNegative("expon_dist", "x", x);
            positive("expon_dist", "lambda", l);
            return out("expon_dist", cum ? -Math.expm1(-l * x) : l * Math.exp(-l * x));
        });
        t.fn("gamma_dist", 3, 4, (a, c) -> gammaDist(a));
        t.fn("gamma_inv", 3, 3, (a, c) -> {
            Double p = num("gamma_inv", a, 0);
            Double al = num("gamma_inv", a, 1);
            Double be = num("gamma_inv", a, 2);
            if (p == null || al == null || be == null) {
                return null;
            }
            positive("gamma_inv", "alpha", al);
            positive("gamma_inv", "beta", be);
            double q = halfOpen("gamma_inv", p);
            return out("gamma_inv", q == 0 ? 0 : invert("gamma_inv", x -> SpecialFunctions.gammaP(al, x / be), q, al * be));
        });
        t.fn("gamma", 1, 1, (a, c) -> {
            Double x = num("gamma", a, 0);
            if (x == null) {
                return null;
            }
            if (x <= 0 && x == Math.rint(x)) {
                throw Args.error("gamma", "Γ is not defined at 0 or a negative whole number (" + Args.describe(a.get(0)) + ")");
            }
            return out("gamma", SpecialFunctions.gamma(x));
        });
        t.fn("gamma_ln", 1, 1, (a, c) -> {
            Double x = num("gamma_ln", a, 0);
            return x == null ? null : out("gamma_ln", SpecialFunctions.lnGamma(positive("gamma_ln", "x", x)));
        });
        t.fn("beta_dist", 3, 6, (a, c) -> betaDist(a));
        t.fn("beta_inv", 3, 5, (a, c) -> betaInv(a));
        t.fn("weibull_dist", 3, 4, (a, c) -> {
            Double x = num("weibull_dist", a, 0);
            Double al = num("weibull_dist", a, 1);
            Double be = num("weibull_dist", a, 2);
            Boolean cum = cumulative("weibull_dist", a, 3);
            if (x == null || al == null || be == null || cum == null) {
                return null;
            }
            nonNegative("weibull_dist", "x", x);
            positive("weibull_dist", "alpha", al);
            positive("weibull_dist", "beta", be);
            double z = Math.pow(x / be, al);
            return out("weibull_dist", cum ? -Math.expm1(-z) : al / be * Math.pow(x / be, al - 1) * Math.exp(-z));
        });
        t.fn("hypgeom_dist", 4, 5, (a, c) -> hypgeomDist(a));
        t.fn("negbinom_dist", 3, 4, (a, c) -> negbinomDist(a));
        t.fn("erf", 1, 1, (a, c) -> {
            Double x = num("erf", a, 0);
            return x == null ? null : out("erf", SpecialFunctions.erf(x));
        });
        t.fn("erfc", 1, 1, (a, c) -> {
            Double x = num("erfc", a, 0);
            return x == null ? null : out("erfc", SpecialFunctions.erfc(x));
        });
        t.fn("standardize", 3, 3, (a, c) -> {
            BigDecimal x = Args.decimal("standardize", a.get(0));
            BigDecimal m = Args.decimal("standardize", a.get(1));
            BigDecimal s = Args.decimal("standardize", a.get(2));
            if (x == null || m == null || s == null) {
                return null;
            }
            if (s.signum() <= 0) {
                throw Args.error("standardize", "the standard deviation " + Args.describe(a.get(2)) + " is not above zero");
            }
            return Args.out(x.subtract(m).divide(s, java.math.MathContext.DECIMAL64));
        });
        t.fn("confidence_norm", 3, 3, (a, c) -> confidence("confidence_norm", a, false));
        t.fn("confidence_t", 3, 3, (a, c) -> confidence("confidence_t", a, true));
        t.fn("fisher", 1, 1, (a, c) -> {
            Double x = num("fisher", a, 0);
            if (x == null) {
                return null;
            }
            if (!(x > -1 && x < 1)) {
                throw Args.error("fisher", Args.describe(a.get(0)) + " is not between −1 and 1 (exclusive)");
            }
            return out("fisher", 0.5 * Math.log((1 + x) / (1 - x)));
        });
        t.fn("fisher_inv", 1, 1, (a, c) -> {
            Double y = num("fisher_inv", a, 0);
            return y == null ? null : out("fisher_inv", Math.tanh(y));
        });
    }

    // ---- arguments ----------------------------------------------------------------------------------------------------------------

    /**
     * Argument i as a double; null when empty. A binary number is taken as it is (not cut to Excel's 15 digits as a decimal argument is:
     * {@code sin(pi() / 6)} needs every digit of π); a decimal or number-like text is read as {@link Args#decimal} reads it.
     */
    static Double num(String fn, List<Object> a, int i) {
        return real(fn, a.get(i));
    }

    /** A value as a double, as {@link #num} reads an argument; null when empty. */
    static Double real(String fn, Object v) {
        Object s = Values.simplify(v);
        if ((s instanceof Double || s instanceof Float) && !Args.empty(s)) {
            double d = ((Number) s).doubleValue();
            if (Double.isInfinite(d)) {
                throw Args.typeError(fn, s, "a finite number");
            }
            return d;
        }
        BigDecimal d = Args.decimal(fn, s);
        return d == null ? null : d.doubleValue();
    }

    /** The {@code cumulative} flag at i: true by default; a true/false, or a number (0 is false), as Excel reads it. */
    static Boolean cumulative(String fn, List<Object> a, int i) {
        if (a.size() <= i) {
            return true;
        }
        Object v = Values.simplify(a.get(i));
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        BigDecimal d = Args.decimal(fn, v);
        return d.signum() != 0;
    }

    /** Degrees of freedom at i: cut to a whole number, at least 1. */
    private static Double df(String fn, List<Object> a, int i) {
        Double d = num(fn, a, i);
        if (d == null) {
            return null;
        }
        double w = Math.floor(d);
        if (w < 1 || w > 1e10) {
            throw Args.error(fn, "degrees of freedom " + Args.describe(a.get(i)) + " are not from 1 to 1e10");
        }
        return w;
    }

    static double positive(String fn, String what, double v) {
        if (!(v > 0)) {
            throw Args.error(fn, what + " " + Values.text(Values.normalise(v)) + " is not above zero");
        }
        return v;
    }

    static double nonNegative(String fn, String what, double v) {
        if (v < 0) {
            throw Args.error(fn, what + " " + Values.text(Values.normalise(v)) + " is negative");
        }
        return v;
    }

    /** A probability strictly between 0 and 1. */
    private static double open(String fn, double p) {
        if (!(p > 0 && p < 1)) {
            throw Args.error(fn, "the probability " + Values.text(Values.normalise(p)) + " is not between 0 and 1 (exclusive)");
        }
        return p;
    }

    /** A probability from 0 (included) to 1 (excluded). */
    private static double halfOpen(String fn, double p) {
        if (!(p >= 0 && p < 1)) {
            throw Args.error(fn, "the probability " + Values.text(Values.normalise(p)) + " is not from 0 to under 1");
        }
        return p;
    }

    /** A probability from 0 to 1, both included. */
    private static double closed(String fn, String what, double p) {
        if (!(p >= 0 && p <= 1)) {
            throw Args.error(fn, what + " " + Values.text(Values.normalise(p)) + " is not from 0 to 1");
        }
        return p;
    }

    /** The density at 0 of a shape below 1 (or 2 degrees of freedom) is infinite: no figure, said in words (Excel's #NUM!). */
    private static double infinite(String fn) {
        throw Args.error(fn, "the density at 0 is infinite for this shape");
    }

    static Object out(String fn, double v) {
        return Args.out(fn, v);
    }

    private static double invert(String fn, DoubleUnaryOperator cdf, double p, double start) {
        try {
            return SpecialFunctions.invert(cdf, p, 0, Math.max(start, 1e-3), false);
        } catch (IllegalStateException e) {
            throw Args.error(fn, e.getMessage());
        }
    }

    // ---- the distributions --------------------------------------------------------------------------------------------------------

    /** {@code norm_dist(x, mean, sd, [cumulative])}: Excel's NORM.DIST. */
    private static Object normDist(List<Object> a) {
        Double x = num("norm_dist", a, 0);
        Double m = num("norm_dist", a, 1);
        Double s = num("norm_dist", a, 2);
        Boolean cum = cumulative("norm_dist", a, 3);
        if (x == null || m == null || s == null || cum == null) {
            return null;
        }
        double z = (x - m) / positive("norm_dist", "the standard deviation", s);
        return out("norm_dist", cum ? SpecialFunctions.normalCdf(z) : SpecialFunctions.normalPdf(z) / s);
    }

    /** {@code lognorm_dist(x, mean, sd, [cumulative])}: Excel's LOGNORM.DIST, of ln x normal with that mean and sd. */
    private static Object lognormDist(List<Object> a) {
        Double x = num("lognorm_dist", a, 0);
        Double m = num("lognorm_dist", a, 1);
        Double s = num("lognorm_dist", a, 2);
        Boolean cum = cumulative("lognorm_dist", a, 3);
        if (x == null || m == null || s == null || cum == null) {
            return null;
        }
        positive("lognorm_dist", "x", x);
        double z = (Math.log(x) - m) / positive("lognorm_dist", "the standard deviation", s);
        return out("lognorm_dist", cum ? SpecialFunctions.normalCdf(z) : SpecialFunctions.normalPdf(z) / (x * s));
    }

    static double tCdf(double x, double df) {
        double tail = 0.5 * SpecialFunctions.betaI(df / 2, 0.5, df / (df + x * x));
        return x >= 0 ? 1 - tail : tail;
    }

    static double tInverse(double p, double df) {
        if (p == 0.5) {
            return 0;
        }
        if (p < 0.5) {
            return -tInverse(1 - p, df);
        }
        return invert("t_inv", x -> tCdf(x, df), p, 1);
    }

    /** {@code t_dist(x, df, [cumulative])}: Excel's T.DIST, the left tail (T.DIST.2T and .RT are 2·(1 − t_dist(|x|)) and 1 − it). */
    private static Object tDist(List<Object> a) {
        Double x = num("t_dist", a, 0);
        Double df = df("t_dist", a, 1);
        Boolean cum = cumulative("t_dist", a, 2);
        if (x == null || df == null || cum == null) {
            return null;
        }
        if (cum) {
            return out("t_dist", tCdf(x, df));
        }
        double ln = SpecialFunctions.lnGamma((df + 1) / 2) - SpecialFunctions.lnGamma(df / 2) - 0.5 * Math.log(df * Math.PI)
                - (df + 1) / 2 * Math.log1p(x * x / df);
        return out("t_dist", Math.exp(ln));
    }

    /** {@code chisq_dist(x, df, [cumulative])}: Excel's CHISQ.DIST, the left tail. */
    private static Object chisqDist(List<Object> a) {
        Double x = num("chisq_dist", a, 0);
        Double df = df("chisq_dist", a, 1);
        Boolean cum = cumulative("chisq_dist", a, 2);
        if (x == null || df == null || cum == null) {
            return null;
        }
        nonNegative("chisq_dist", "x", x);
        if (cum) {
            return out("chisq_dist", SpecialFunctions.gammaP(df / 2, x / 2));
        }
        if (x == 0) {
            return out("chisq_dist", df == 2 ? 0.5 : df < 2 ? infinite("chisq_dist") : 0);
        }
        double k = df / 2;
        return out("chisq_dist", Math.exp((k - 1) * Math.log(x) - x / 2 - k * Math.log(2) - SpecialFunctions.lnGamma(k)));
    }

    static double fCdf(double x, double d1, double d2) {
        return x <= 0 ? 0 : SpecialFunctions.betaI(d1 / 2, d2 / 2, d1 * x / (d1 * x + d2));
    }

    /** {@code f_dist(x, df1, df2, [cumulative])}: Excel's F.DIST, the left tail. */
    private static Object fDist(List<Object> a) {
        Double x = num("f_dist", a, 0);
        Double d1 = df("f_dist", a, 1);
        Double d2 = df("f_dist", a, 2);
        Boolean cum = cumulative("f_dist", a, 3);
        if (x == null || d1 == null || d2 == null || cum == null) {
            return null;
        }
        nonNegative("f_dist", "x", x);
        if (cum) {
            return out("f_dist", fCdf(x, d1, d2));
        }
        if (x == 0) {
            return out("f_dist", d1 == 2 ? 1 : d1 < 2 ? infinite("f_dist") : 0);
        }
        double ln = 0.5 * (d1 * Math.log(d1 * x) + d2 * Math.log(d2) - (d1 + d2) * Math.log(d1 * x + d2)) - Math.log(x)
                - (SpecialFunctions.lnGamma(d1 / 2) + SpecialFunctions.lnGamma(d2 / 2) - SpecialFunctions.lnGamma((d1 + d2) / 2));
        return out("f_dist", Math.exp(ln));
    }

    /** A count at i: cut to a whole number, not negative. */
    private static Double count(String fn, String what, List<Object> a, int i) {
        Double d = num(fn, a, i);
        if (d == null) {
            return null;
        }
        if (d < 0) {
            throw Args.error(fn, what + " " + Args.describe(a.get(i)) + " is negative");
        }
        return Math.floor(d);
    }

    static double binomCdf(double k, double n, double p) {
        if (k >= n) {
            return 1;
        }
        if (p == 0) {
            return 1;
        }
        if (p == 1) {
            return 0;
        }
        return SpecialFunctions.betaI(n - k, k + 1, 1 - p);
    }

    private static double binomPmf(double k, double n, double p) {
        if (p == 0) {
            return k == 0 ? 1 : 0;
        }
        if (p == 1) {
            return k == n ? 1 : 0;
        }
        return Math.exp(SpecialFunctions.lnChoose(n, k) + k * Math.log(p) + (n - k) * Math.log1p(-p));
    }

    /** {@code binom_dist(k, n, p, [cumulative])}: Excel's BINOM.DIST. */
    private static Object binomDist(List<Object> a) {
        Double k = count("binom_dist", "the successes", a, 0);
        Double n = count("binom_dist", "the trials", a, 1);
        Double p = num("binom_dist", a, 2);
        Boolean cum = cumulative("binom_dist", a, 3);
        if (k == null || n == null || p == null || cum == null) {
            return null;
        }
        closed("binom_dist", "the probability", p);
        if (k > n) {
            throw Args.error("binom_dist", "the successes " + Args.describe(a.get(0)) + " are more than the trials " + Args.describe(a.get(1)));
        }
        return out("binom_dist", cum ? binomCdf(k, n, p) : binomPmf(k, n, p));
    }

    /** {@code binom_inv(n, p, alpha)}: Excel's BINOM.INV, the smallest k whose cumulative probability reaches alpha. */
    private static Object binomInv(List<Object> a) {
        Double n = count("binom_inv", "the trials", a, 0);
        Double p = num("binom_inv", a, 1);
        Double alpha = num("binom_inv", a, 2);
        if (n == null || p == null || alpha == null) {
            return null;
        }
        closed("binom_inv", "the probability", p);
        closed("binom_inv", "alpha", alpha);
        long lo = 0;
        long hi = (long) (double) n;
        while (lo < hi) {
            long mid = lo + (hi - lo) / 2;
            if (binomCdf(mid, n, p) >= alpha) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /** {@code poisson_dist(k, mean, [cumulative])}: Excel's POISSON.DIST. */
    private static Object poissonDist(List<Object> a) {
        Double k = count("poisson_dist", "the events", a, 0);
        Double m = num("poisson_dist", a, 1);
        Boolean cum = cumulative("poisson_dist", a, 2);
        if (k == null || m == null || cum == null) {
            return null;
        }
        nonNegative("poisson_dist", "the mean", m);
        if (m == 0) {
            return out("poisson_dist", cum || k == 0 ? 1 : 0);
        }
        return out("poisson_dist", cum ? SpecialFunctions.gammaQ(k + 1, m)
                : Math.exp(k * Math.log(m) - m - SpecialFunctions.lnFactorial(k)));
    }

    /** {@code gamma_dist(x, alpha, beta, [cumulative])}: Excel's GAMMA.DIST, beta the scale. */
    private static Object gammaDist(List<Object> a) {
        Double x = num("gamma_dist", a, 0);
        Double al = num("gamma_dist", a, 1);
        Double be = num("gamma_dist", a, 2);
        Boolean cum = cumulative("gamma_dist", a, 3);
        if (x == null || al == null || be == null || cum == null) {
            return null;
        }
        nonNegative("gamma_dist", "x", x);
        positive("gamma_dist", "alpha", al);
        positive("gamma_dist", "beta", be);
        if (cum) {
            return out("gamma_dist", SpecialFunctions.gammaP(al, x / be));
        }
        if (x == 0) {
            return out("gamma_dist", al == 1 ? 1 / be : al < 1 ? infinite("gamma_dist") : 0);
        }
        return out("gamma_dist", Math.exp((al - 1) * Math.log(x) - x / be - al * Math.log(be) - SpecialFunctions.lnGamma(al)));
    }

    /** {@code beta_dist(x, alpha, beta, [cumulative], [A], [B])}: Excel's BETA.DIST on the interval A to B (0 to 1 by default). */
    private static Object betaDist(List<Object> a) {
        Double x = num("beta_dist", a, 0);
        Double al = num("beta_dist", a, 1);
        Double be = num("beta_dist", a, 2);
        Boolean cum = cumulative("beta_dist", a, 3);
        Double lo = a.size() > 4 ? num("beta_dist", a, 4) : Double.valueOf(0);
        Double hi = a.size() > 5 ? num("beta_dist", a, 5) : Double.valueOf(1);
        if (x == null || al == null || be == null || cum == null || lo == null || hi == null) {
            return null;
        }
        positive("beta_dist", "alpha", al);
        positive("beta_dist", "beta", be);
        if (!(lo < hi) || x < lo || x > hi) {
            throw Args.error("beta_dist", "x " + Values.text(Values.normalise(x)) + " is not within A to B (" + Values.text(Values.normalise(lo))
                    + " to " + Values.text(Values.normalise(hi)) + ")");
        }
        double u = (x - lo) / (hi - lo);
        if (cum) {
            return out("beta_dist", SpecialFunctions.betaI(al, be, u));
        }
        double ln = (al - 1) * Math.log(u) + (be - 1) * Math.log1p(-u) - (SpecialFunctions.lnGamma(al) + SpecialFunctions.lnGamma(be)
                - SpecialFunctions.lnGamma(al + be));
        return out("beta_dist", Math.exp(ln) / (hi - lo));
    }

    /** {@code beta_inv(p, alpha, beta, [A], [B])}: Excel's BETA.INV. */
    private static Object betaInv(List<Object> a) {
        Double p = num("beta_inv", a, 0);
        Double al = num("beta_inv", a, 1);
        Double be = num("beta_inv", a, 2);
        Double lo = a.size() > 3 ? num("beta_inv", a, 3) : Double.valueOf(0);
        Double hi = a.size() > 4 ? num("beta_inv", a, 4) : Double.valueOf(1);
        if (p == null || al == null || be == null || lo == null || hi == null) {
            return null;
        }
        closed("beta_inv", "the probability", p);
        positive("beta_inv", "alpha", al);
        positive("beta_inv", "beta", be);
        if (!(lo < hi)) {
            throw Args.error("beta_inv", "A is not below B");
        }
        double u = p == 0 ? 0 : p == 1 ? 1 : SpecialFunctions.invert(x -> SpecialFunctions.betaI(al, be, Math.min(1, x)), p, 0, 1, false);
        return out("beta_inv", lo + u * (hi - lo));
    }

    /** {@code hypgeom_dist(k, n, K, N, [cumulative])}: Excel's HYPGEOM.DIST, k successes in a sample of n from K in a population of N. */
    private static Object hypgeomDist(List<Object> a) {
        Double k = count("hypgeom_dist", "the sample's successes", a, 0);
        Double n = count("hypgeom_dist", "the sample", a, 1);
        Double big = count("hypgeom_dist", "the population's successes", a, 2);
        Double pop = count("hypgeom_dist", "the population", a, 3);
        Boolean cum = cumulative("hypgeom_dist", a, 4);
        if (k == null || n == null || big == null || pop == null || cum == null) {
            return null;
        }
        if (n > pop || big > pop || k > n || k > big || k < n - (pop - big) || n < 1 || pop < 1) {
            throw Args.error("hypgeom_dist", "the counts do not fit: 0 ≤ k ≤ min(n, K), n − (N − K) ≤ k, n and K at most N");
        }
        double denom = SpecialFunctions.lnChoose(pop, n);
        double from = cum ? Math.max(0, n - (pop - big)) : k;
        double sum = 0;
        for (double i = from; i <= k; i++) {
            sum += Math.exp(SpecialFunctions.lnChoose(big, i) + SpecialFunctions.lnChoose(pop - big, n - i) - denom);
        }
        return out("hypgeom_dist", Math.min(1, sum));
    }

    /** {@code negbinom_dist(failures, successes, p, [cumulative])}: Excel's NEGBINOM.DIST, failures before the s-th success. */
    private static Object negbinomDist(List<Object> a) {
        Double f = count("negbinom_dist", "the failures", a, 0);
        Double s = count("negbinom_dist", "the successes", a, 1);
        Double p = num("negbinom_dist", a, 2);
        Boolean cum = cumulative("negbinom_dist", a, 3);
        if (f == null || s == null || p == null || cum == null) {
            return null;
        }
        if (s < 1) {
            throw Args.error("negbinom_dist", "the successes are fewer than 1");
        }
        if (!(p > 0 && p < 1)) {
            throw Args.error("negbinom_dist", "the probability " + Values.text(Values.normalise(p)) + " is not between 0 and 1 (exclusive)");
        }
        if (cum) {
            return out("negbinom_dist", SpecialFunctions.betaI(s, f + 1, p));
        }
        return out("negbinom_dist", Math.exp(SpecialFunctions.lnChoose(f + s - 1, s - 1) + s * Math.log(p) + f * Math.log1p(-p)));
    }

    /**
     * {@code confidence_norm(alpha, sd, n)}, {@code confidence_t(alpha, sd, n)}: Excel's CONFIDENCE.NORM and CONFIDENCE.T, the half-width
     * of the 1 − alpha confidence interval for a mean (the normal's quantile, or Student's t with n − 1 degrees of freedom) times sd / √n.
     */
    private static Object confidence(String fn, List<Object> a, boolean t) {
        Double alpha = num(fn, a, 0);
        Double sd = num(fn, a, 1);
        Double n = num(fn, a, 2);
        if (alpha == null || sd == null || n == null) {
            return null;
        }
        open(fn, alpha);
        positive(fn, "the standard deviation", sd);
        double size = Math.floor(n);
        if (size < (t ? 2 : 1)) {
            throw Args.error(fn, "the size " + Args.describe(a.get(2)) + " is under " + (t ? 2 : 1));
        }
        double q = t ? tInverse(1 - alpha / 2, size - 1) : SpecialFunctions.normalInverse(1 - alpha / 2);
        return out(fn, q * sd / Math.sqrt(size));
    }
}
