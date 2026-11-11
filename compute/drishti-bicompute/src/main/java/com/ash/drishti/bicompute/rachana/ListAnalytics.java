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
package com.ash.drishti.bicompute.rachana;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.rachana.el.FunctionProvider.Provided;
import com.ash.drishti.rachana.el.Values;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MATLAB's (and Octave's) list analytics that rest on an aggregate (RACHANA_REFERENCE.md section F6, "MATLAB/Octave coverage"), provided
 * to Rachana by the pivot engine so every sum, mean, standard deviation, median, minimum and maximum in them is the engine's one
 * implementation (the same {@link AggFunction} a pivot cell runs): {@code cumsum}, {@code cumprod}, {@code cummax}, {@code cummin} (running
 * forms over a list), {@code movsum}, {@code movmean}, {@code movstd}, {@code movmedian} (moving windows), {@code maxk}, {@code mink},
 * {@code bounds}, {@code zscore}, {@code rescale}, {@code isoutlier} and {@code polyfit}.
 *
 * <p>Rules: a list's element that is not a number (empty, text that is no number) is <b>missing</b>: a running or moving form leaves its
 * place empty and carries on past it (MATLAB's {@code 'omitmissing'}), a statistic leaves it out; a masked element makes the whole result
 * masked. Each returns a list as long as its input unless it says otherwise ({@code maxk}, {@code bounds}, {@code polyfit}).
 */
final class ListAnalytics {

    private ListAnalytics() {}

    /** The most element-windows the moving forms compute (n · window): a bound on the work. */
    static final long MAX_WINDOW_WORK = 20_000_000L;

    /** The highest degree {@code polyfit} fits: a small least-squares problem, solved here by Householder QR. */
    static final int MAX_DEGREE = 6;

    /** MATLAB's scale factor of the median absolute deviation, 1 / (√2 · erfcinv(3/2)): the MAD of a normal sample estimates σ. */
    static final double MAD_SCALE = 1.482602218505602;

    static void register(List<Provided> out) {
        list(out, "cumsum", 1, 1, "list", "the running sum; an empty element's place stays empty (MATLAB's cumsum, omitting missing)",
                "cumsum($.CASH_FLOWS[*].AMOUNT)", a -> running("cumsum", "sum", a));
        list(out, "cumprod", 1, 1, "list", "the running product (MATLAB's cumprod)", "cumprod(map($.RETURNS, 1 + @))",
                a -> running("cumprod", "product", a));
        list(out, "cummax", 1, 1, "list", "the running maximum (MATLAB's cummax)", "cummax($.PNL_HISTORY)", a -> running("cummax", "max", a));
        list(out, "cummin", 1, 1, "list", "the running minimum (MATLAB's cummin)", "cummin($.PNL_HISTORY)", a -> running("cummin", "min", a));
        list(out, "movsum", 2, 3, "list, k | list, before, after", "the sum over a moving window (MATLAB's movsum, the window shrinking at the ends)",
                "movsum($.PNL_HISTORY, 5)", a -> moving("movsum", "sum", a));
        list(out, "movmean", 2, 3, "list, k | list, before, after", "the mean over a moving window (MATLAB's movmean)",
                "movmean($.PNL_HISTORY, 20)", a -> moving("movmean", "avg", a));
        list(out, "movstd", 2, 3, "list, k | list, before, after", "the sample standard deviation over a moving window (MATLAB's movstd)",
                "movstd($.PNL_HISTORY, 20)", a -> moving("movstd", "stddev_samp", a));
        list(out, "movmedian", 2, 3, "list, k | list, before, after", "the median over a moving window (MATLAB's movmedian)",
                "movmedian($.PNL_HISTORY, 5)", a -> moving("movmedian", "median", a));
        list(out, "maxk", 2, 2, "list, k", "the k largest numbers, largest first (MATLAB's maxk)", "maxk($.TRADES[*].NOTIONAL, 3)",
                a -> topK("maxk", a, true));
        list(out, "mink", 2, 2, "list, k", "the k smallest numbers, smallest first (MATLAB's mink)", "mink($.TRADES[*].NOTIONAL, 3)",
                a -> topK("mink", a, false));
        list(out, "bounds", 1, 1, "list", "[smallest, largest] (MATLAB's bounds); empty for no number", "bounds($.TRADES[*].PRICE)",
                ListAnalytics::bounds);
        list(out, "zscore", 1, 1, "list", "(x − mean) / s, the sample standard deviation (MATLAB's zscore; Excel's STANDARDIZE per value)",
                "zscore($.TRADES[*].PNL)", ListAnalytics::zscore);
        list(out, "rescale", 1, 3, "list, [lo], [hi]", "the values mapped linearly onto lo..hi, 0..1 by default (MATLAB's rescale)",
                "rescale($.TRADES[*].NOTIONAL)", ListAnalytics::rescale);
        list(out, "isoutlier", 1, 2, "list, [threshold]",
                "true where a value lies more than threshold (3) scaled MADs from the median (MATLAB's isoutlier default)",
                "isoutlier($.TRADES[*].PNL)", ListAnalytics::isoutlier);
        list(out, "polyfit", 3, 3, "xs, ys, degree", "the least-squares polynomial's coefficients, highest power first (MATLAB's polyfit)",
                "polyfit($.TENORS, $.RATES, 2)", ListAnalytics::polyfit);
    }

    private static void list(List<Provided> out, String name, int min, int max, String args, String description, String example,
            java.util.function.Function<List<Object>, Object> f) {
        out.add(new Provided(name, min, max, args, "list", description, example, (a, c) -> {
            try {
                return f.apply(a);
            } catch (Masked m) {
                return Values.mask();
            }
        }));
    }

    /** A masked element met: the result is masked. */
    private static final class Masked extends RuntimeException {
        private static final long serialVersionUID = 1L;
        static final Masked INSTANCE = new Masked();

        private Masked() {
            super("masked", null, false, false);
        }
    }

    /** The list's elements as numbers, NaN for a missing one; null when the argument is empty. */
    static double[] numbers(String fn, Object v) {
        Object s = Values.simplify(v);
        if (s == null) {
            return null;
        }
        if (!(s instanceof DataNode.Arr list)) {
            throw ListAggregates.error(fn, Values.text(s) + " is not a list");
        }
        double[] x = new double[list.size()];
        for (int i = 0; i < x.length; i++) {
            DataNode e = list.get(i);
            if (e.isMasked()) {
                throw Masked.INSTANCE;
            }
            x[i] = ListAggregates.number(e);
        }
        return x;
    }

    private static double number(String fn, Object v, String what) {
        Object s = Values.simplify(v);
        if (s instanceof Number n) {
            return n.doubleValue();
        }
        double d = s instanceof String str ? Values.number(str) : Double.NaN;
        if (Double.isNaN(d)) {
            throw ListAggregates.error(fn, what + " " + (s == null ? "is empty" : "'" + Values.text(s) + "' is not a number"));
        }
        return d;
    }

    private static int whole(String fn, Object v, String what, int min) {
        double d = number(fn, v, what);
        if (d != Math.rint(d) || d < min || d > Integer.MAX_VALUE) {
            throw ListAggregates.error(fn, what + " " + Values.text(Values.normalise(d)) + " is not a whole number of at least " + min);
        }
        return (int) d;
    }

    /** A list of numbers (NaN as empty) as Rachana's list. */
    static DataNode.Arr out(double[] x) {
        List<DataNode> out = new ArrayList<>(x.length);
        for (double d : x) {
            out.add(Double.isNaN(d) || Double.isInfinite(d) ? DataNode.nullValue() : DataNode.of(Values.normalise(d)));
        }
        return new DataNode.Arr(List.copyOf(out));
    }

    /** The engine's aggregate {@code name} with its parameters. */
    static AggFunction<Object> engine(String name, double... params) {
        List<Double> p = new ArrayList<>();
        for (double d : params) {
            p.add(d);
        }
        return Functions.builtIn(name, List.of("x"), p).orElseThrow();
    }

    /** The engine's aggregate over values[from, to); NaN when it has no figure. */
    static double over(AggFunction<Object> f, double[] values, int from, int to) {
        Double r = f.finish(f.add(f.start(), new Batch(to - from, new Object[] {Arrays.copyOfRange(values, from, to)})));
        return r == null ? Double.NaN : r;
    }

    /** {@code cumsum}, {@code cumprod}, {@code cummax}, {@code cummin}: the engine's state, one element added at a time. */
    private static Object running(String fn, String aggregate, List<Object> a) {
        double[] x = numbers(fn, a.get(0));
        if (x == null) {
            return null;
        }
        AggFunction<Object> f = engine(aggregate);
        Object state = f.start();
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            if (Double.isNaN(x[i])) {
                y[i] = Double.NaN;
                continue;
            }
            state = f.add(state, new Batch(1, new Object[] {new double[] {x[i]}}));
            Double r = f.finish(state);
            y[i] = r == null ? Double.NaN : r;
        }
        return out(y);
    }

    /**
     * The moving forms: {@code k} a window of k elements centred on each (an even k: k/2 before and k/2 − 1 after, as MATLAB), or
     * {@code before} and {@code after}; at the ends the window shrinks to the elements there are. Missing elements are left out of a
     * window; a window with none gives an empty place.
     */
    private static Object moving(String fn, String aggregate, List<Object> a) {
        double[] x = numbers(fn, a.get(0));
        if (x == null) {
            return null;
        }
        int before;
        int after;
        if (a.size() == 2) {
            int k = whole(fn, a.get(1), "the window", 1);
            before = k / 2;
            after = k % 2 == 1 ? k / 2 : k / 2 - 1;
        } else {
            before = whole(fn, a.get(1), "the elements before", 0);
            after = whole(fn, a.get(2), "the elements after", 0);
        }
        if ((long) x.length * (before + after + 1L) > MAX_WINDOW_WORK) {
            throw ListAggregates.error(fn, "a window of " + (before + after + 1L) + " over " + x.length + " elements is more work than "
                    + MAX_WINDOW_WORK + " element-windows");
        }
        AggFunction<Object> f = engine(aggregate);
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            int from = Math.max(0, i - before);
            int to = (int) Math.min(x.length, (long) i + after + 1);
            y[i] = over(f, x, from, to);
        }
        if ("movsum".equals(fn)) {                                          // a window of only missing values has no sum, not 0
            for (int i = 0; i < x.length; i++) {
                boolean any = false;
                for (int j = Math.max(0, i - before); j < Math.min(x.length, (long) i + after + 1); j++) {
                    any |= !Double.isNaN(x[j]);
                }
                if (!any) {
                    y[i] = Double.NaN;
                }
            }
        }
        return out(y);
    }

    /** The list's numbers, missing ones left out. */
    private static double[] present(double[] x) {
        return Arrays.stream(x).filter(d -> !Double.isNaN(d)).toArray();
    }

    /** {@code maxk(list, k)}, {@code mink(list, k)}: the k largest (largest first) or smallest (smallest first); fewer when fewer. */
    private static Object topK(String fn, List<Object> a, boolean largest) {
        double[] x = numbers(fn, a.get(0));
        if (x == null) {
            return null;
        }
        int k = whole(fn, a.get(1), "k", 1);
        AggFunction<Object> f = null;
        double[] p = present(x);
        double[] y = new double[Math.min(k, p.length)];
        for (int i = 0; i < y.length; i++) {
            f = engine(largest ? "kth_largest" : "kth_smallest", i + 1);
            y[i] = over(f, p, 0, p.length);
        }
        return out(y);
    }

    /** {@code bounds(list)}: [min, max] by the engine's min and max; empty for no number. */
    private static Object bounds(List<Object> a) {
        double[] x = numbers("bounds", a.get(0));
        if (x == null) {
            return null;
        }
        double lo = over(engine("min"), x, 0, x.length);
        double hi = over(engine("max"), x, 0, x.length);
        return Double.isNaN(lo) ? null : out(new double[] {lo, hi});
    }

    /** {@code zscore(list)}: each value's distance from the mean in sample standard deviations; empty without spread or under two values. */
    private static Object zscore(List<Object> a) {
        double[] x = numbers("zscore", a.get(0));
        if (x == null) {
            return null;
        }
        double mean = over(engine("avg"), x, 0, x.length);
        double sd = over(engine("stddev_samp"), x, 0, x.length);
        if (Double.isNaN(sd) || sd == 0) {
            return null;
        }
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            y[i] = (x[i] - mean) / sd;
        }
        return out(y);
    }

    /** {@code rescale(list, [lo], [hi])}: lo + (x − min) / (max − min) · (hi − lo); every value lo when all are equal (MATLAB's). */
    private static Object rescale(List<Object> a) {
        double[] x = numbers("rescale", a.get(0));
        if (x == null) {
            return null;
        }
        double lo = a.size() > 1 ? number("rescale", a.get(1), "the low end") : 0;
        double hi = a.size() > 2 ? number("rescale", a.get(2), "the high end") : 1;
        if (lo > hi) {
            throw ListAggregates.error("rescale", "the low end is above the high end");
        }
        double min = over(engine("min"), x, 0, x.length);
        double max = over(engine("max"), x, 0, x.length);
        if (Double.isNaN(min)) {
            return null;
        }
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            y[i] = max == min ? (Double.isNaN(x[i]) ? Double.NaN : lo) : lo + (x[i] - min) / (max - min) * (hi - lo);
        }
        return out(y);
    }

    /**
     * {@code isoutlier(list, [threshold])}: true where |x − median| > threshold · 1.4826 · median(|x − median|) (MATLAB's default
     * 'median' method, three scaled MADs); a missing element is not an outlier (false). Every value false when the MAD is 0 and no value
     * differs from the median.
     */
    private static Object isoutlier(List<Object> a) {
        double[] x = numbers("isoutlier", a.get(0));
        if (x == null) {
            return null;
        }
        double t = a.size() > 1 ? number("isoutlier", a.get(1), "the threshold") : 3;
        if (!(t > 0)) {
            throw ListAggregates.error("isoutlier", "the threshold is not above 0");
        }
        double med = over(engine("median"), x, 0, x.length);
        double[] dev = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            dev[i] = Math.abs(x[i] - med);
        }
        double mad = MAD_SCALE * over(engine("median"), dev, 0, dev.length);
        List<DataNode> out = new ArrayList<>(x.length);
        for (int i = 0; i < x.length; i++) {
            out.add(DataNode.of(!Double.isNaN(x[i]) && dev[i] > t * mad));
        }
        return new DataNode.Arr(List.copyOf(out));
    }

    /**
     * {@code polyfit(xs, ys, n)}: the coefficients, highest power first, of the degree-n polynomial nearest the points in least squares
     * (pairs with a missing value left out). Solved by Householder QR of the Vandermonde matrix in x centred and scaled (n + 1 ≤ 7
     * columns: a small problem, not the matrix tier), then expanded back to powers of x. Refused in words when there are fewer distinct x
     * than n + 1 or the columns are numerically dependent (a reciprocal condition under 1e-12).
     */
    private static Object polyfit(List<Object> a) {
        double[] xs = numbers("polyfit", a.get(0));
        double[] ys = numbers("polyfit", a.get(1));
        if (xs == null || ys == null) {
            return null;
        }
        int n = whole("polyfit", a.get(2), "the degree", 0);
        if (n > MAX_DEGREE) {
            throw ListAggregates.error("polyfit", "fits degrees up to " + MAX_DEGREE + " (a higher degree is a matrix problem for the matrix tier)");
        }
        if (xs.length != ys.length) {
            throw ListAggregates.error("polyfit", "the lists differ in length (" + xs.length + " and " + ys.length + ")");
        }
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            if (!Double.isNaN(xs[i]) && !Double.isNaN(ys[i])) {
                pts.add(new double[] {xs[i], ys[i]});
            }
        }
        long distinct = pts.stream().mapToDouble(p -> p[0]).distinct().count();
        if (distinct < n + 1) {
            throw ListAggregates.error("polyfit", "a degree-" + n + " polynomial needs " + (n + 1) + " different x values, the points have "
                    + distinct);
        }
        int m = pts.size();
        double mu = 0;
        for (double[] p : pts) {
            mu += p[0];
        }
        mu /= m;
        double sigma = 0;
        for (double[] p : pts) {
            sigma = Math.max(sigma, Math.abs(p[0] - mu));
        }
        if (sigma == 0) {
            sigma = 1;
        }
        int cols = n + 1;
        double[][] v = new double[m][cols];                                 // columns 1, z, z², … with z = (x − mu) / sigma
        double[] b = new double[m];
        for (int i = 0; i < m; i++) {
            double z = (pts.get(i)[0] - mu) / sigma;
            double pw = 1;
            for (int j = 0; j < cols; j++) {
                v[i][j] = pw;
                pw *= z;
            }
            b[i] = pts.get(i)[1];
        }
        double[] c = leastSquares(v, b, cols);
        // expand Σ c_j ((x − mu)/sigma)^j into powers of x: coefficient of x^k = Σ_j c_j C(j, k) (−mu)^(j−k) / sigma^j
        double[] power = new double[cols];
        for (int j = 0; j < cols; j++) {
            double binom = 1;
            for (int k = 0; k <= j; k++) {
                if (k > 0) {
                    binom = binom * (j - k + 1) / k;
                }
                power[k] += c[j] * binom * Math.pow(-mu, j - k) / Math.pow(sigma, j);
            }
        }
        double[] desc = new double[cols];
        for (int k = 0; k < cols; k++) {
            desc[k] = power[cols - 1 - k];
        }
        return out(desc);
    }

    /** The least-squares solution of v·c ≈ b by Householder QR (Golub and Van Loan, Matrix Computations, 4th ed., section 5.3). */
    static double[] leastSquares(double[][] v, double[] b, int cols) {
        int m = v.length;
        double[][] r = new double[m][];
        for (int i = 0; i < m; i++) {
            r[i] = v[i].clone();
        }
        double[] y = b.clone();
        double maxDiag = 0;
        double minDiag = Double.MAX_VALUE;
        for (int k = 0; k < cols; k++) {
            double norm = 0;
            for (int i = k; i < m; i++) {
                norm = Math.hypot(norm, r[i][k]);
            }
            if (norm == 0) {
                throw ListAggregates.error("polyfit", "the points do not determine the polynomial (dependent columns)");
            }
            double alpha = r[k][k] > 0 ? -norm : norm;
            double[] u = new double[m];
            for (int i = k; i < m; i++) {
                u[i] = r[i][k];
            }
            u[k] -= alpha;
            double uu = 0;
            for (int i = k; i < m; i++) {
                uu += u[i] * u[i];
            }
            if (uu > 0) {
                for (int j = k; j < cols; j++) {
                    double s = 0;
                    for (int i = k; i < m; i++) {
                        s += u[i] * r[i][j];
                    }
                    s = 2 * s / uu;
                    for (int i = k; i < m; i++) {
                        r[i][j] -= s * u[i];
                    }
                }
                double s = 0;
                for (int i = k; i < m; i++) {
                    s += u[i] * y[i];
                }
                s = 2 * s / uu;
                for (int i = k; i < m; i++) {
                    y[i] -= s * u[i];
                }
            }
            maxDiag = Math.max(maxDiag, Math.abs(r[k][k]));
            minDiag = Math.min(minDiag, Math.abs(r[k][k]));
        }
        if (minDiag < 1e-12 * maxDiag) {
            throw ListAggregates.error("polyfit", "the points are too close to determine a polynomial of this degree (ill-conditioned: "
                    + "a reciprocal condition under 1e-12)");
        }
        double[] c = new double[cols];
        for (int k = cols - 1; k >= 0; k--) {
            double s = y[k];
            for (int j = k + 1; j < cols; j++) {
                s -= r[k][j] * c[j];
            }
            c[k] = s / r[k][k];
        }
        return c;
    }
}
