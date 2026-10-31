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
package com.ash.drishti.compute.var;

/**
 * The measures of historical-simulation VaR read from a scope's P&amp;L vector (gains positive), VAR_GUIDE.md section 18. Every
 * function works on plain {@code double[]} and returns losses as positive numbers.
 *
 * <ul>
 *   <li><b>Rank rule</b> ({@link RankRule}): {@code KTH_WORST} takes the k-th worst value, k = ceil(N (1 - a)) (the 6th worst of 509 at
 *       99%); {@code INTERPOLATED} interpolates linearly between the order statistics around N (1 - a).</li>
 *   <li><b>ES(a)</b>: minus the mean of the k worst values, k = ceil(N (1 - a)) (the 13 worst of 509 at 97.5%).</li>
 *   <li><b>Component VaR</b>: a part's mean P&amp;L over the scope's scenarios ranked k - h .. k + h (h = window / 2), scaled so the
 *       parts add up to the VaR exactly (Euler allocation on a smoothed tail; without the scale they add up to the smoothed VaR).</li>
 *   <li><b>Component ES</b>: a part's mean P&amp;L over the scope's ES scenarios: adds up to ES exactly.</li>
 * </ul>
 * The order of scenarios breaks ties by index (the older scenario first), the same rule as tools/packgen/var/varmath.py, so the demo
 * data's stored figures and the engine's are the same numbers.
 */
public final class VarMath {

    private static final double EPS = 1e-9;

    /** How VaR is read from the sorted vector (open question 3 of the engine design, VAR_GUIDE.md section 23.2; {@code drishti.var.rank-rule}). */
    public enum RankRule { KTH_WORST, INTERPOLATED }

    private VarMath() {}

    /** k = ceil(N (1 - a)), at least 1. */
    public static int rank(int n, double alpha) {
        return Math.max(1, Math.min(n, (int) Math.ceil(n * (1.0 - alpha) - EPS)));
    }

    /** Scenario indices from the worst value to the best (ties: the lower index first). No boxing: a merge sort over an int[]. */
    public static int[] order(double[] v) {
        int n = v.length;
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        int[] tmp = new int[n];
        for (int width = 1; width < n; width *= 2) {
            for (int lo = 0; lo < n - width; lo += 2 * width) {
                int mid = lo + width;
                int hi = Math.min(lo + 2 * width, n);
                int i = lo;
                int j = mid;
                int k = lo;
                while (i < mid && j < hi) {
                    int a = idx[i];
                    int b = idx[j];
                    if (v[a] < v[b] || v[a] == v[b] && a < b) {
                        tmp[k++] = a;
                        i++;
                    } else {
                        tmp[k++] = b;
                        j++;
                    }
                }
                while (i < mid) {
                    tmp[k++] = idx[i++];
                }
                while (j < hi) {
                    tmp[k++] = idx[j++];
                }
                System.arraycopy(tmp, lo, idx, lo, hi - lo);
            }
        }
        return idx;
    }

    /** The most worst values a VaR takes by selection rather than a full {@link #order(double[])} (2.9.7). */
    static final int SELECT_MAX = 64;

    /**
     * VaR at {@code alpha} by the rank rule: the same value as {@code var(v, order(v), alpha, rule)}, to the bit. Only the worst
     * {@code k} (or {@code k + 1}) values are needed, so they are kept in a small buffer in the order's own terms (ascending, ties in
     * index order) instead of ordering every scenario (2.9.7: each part's standalone VaR and each incremental VaR asked one). A
     * vector holding NaN, or a rank above {@link #SELECT_MAX}, takes the full order.
     */
    public static double var(double[] v, double alpha, RankRule rule) {
        int n = v.length;
        if (n == 0) {
            return 0;
        }
        int lo;
        int hi;
        double w = 0;
        if (rule == RankRule.INTERPOLATED) {
            double pos = n * (1.0 - alpha);
            lo = Math.max(1, (int) Math.floor(pos));
            hi = Math.min(n, lo + 1);
            w = Math.min(1.0, Math.max(0.0, pos - lo));
        } else {
            lo = rank(n, alpha);
            hi = lo;
        }
        double[] worst = worst(v, hi);
        if (worst == null) {
            return var(v, order(v), alpha, rule);
        }
        return rule == RankRule.INTERPOLATED ? -((1 - w) * worst[lo - 1] + w * worst[hi - 1]) : -worst[lo - 1];
    }

    /**
     * The {@code m} smallest values of {@code v} in {@link #order(double[])}'s order (ascending; equal values in index order, so 0.0 and
     * -0.0 come as the order has them); null when {@code m} is above {@link #SELECT_MAX} or a value is NaN.
     */
    static double[] worst(double[] v, int m) {
        if (m > SELECT_MAX || m < 1) {
            return null;
        }
        double[] buf = new double[m];
        int c = 0;
        for (double x : v) {
            if (Double.isNaN(x)) {
                return null;
            }
            if (c == m && !(x < buf[m - 1])) {
                continue;                                            // not among the m worst (an equal value comes after: a later index)
            }
            int p = c < m ? c++ : m - 1;
            while (p > 0 && x < buf[p - 1]) {
                buf[p] = buf[p - 1];
                p--;
            }
            buf[p] = x;
        }
        return buf;
    }

    public static double var(double[] v, double alpha) {
        return var(v, alpha, RankRule.KTH_WORST);
    }

    /** VaR with the order already known. */
    public static double var(double[] v, int[] order, double alpha, RankRule rule) {
        int n = v.length;
        if (n == 0) {
            return 0;
        }
        if (rule == RankRule.INTERPOLATED) {
            double pos = n * (1.0 - alpha);                 // 1-based rank, between order statistics
            int lo = Math.max(1, (int) Math.floor(pos));
            int hi = Math.min(n, lo + 1);
            double w = Math.min(1.0, Math.max(0.0, pos - lo));
            return -((1 - w) * v[order[lo - 1]] + w * v[order[hi - 1]]);
        }
        return -v[order[rank(n, alpha) - 1]];
    }

    /** Expected shortfall at {@code alpha}: minus the mean of the ceil(N (1 - a)) worst values. */
    public static double es(double[] v, double alpha) {
        return es(v, order(v), alpha);
    }

    public static double es(double[] v, int[] order, double alpha) {
        int k = rank(v.length, alpha);
        double s = 0;
        for (int i = 0; i < k; i++) {
            s += v[order[i]];
        }
        return v.length == 0 ? 0 : -s / k;
    }

    /** The scenarios component VaR averages over: ranks k - h .. k + h of the scope's order. */
    public static int[] tail(int[] order, double alpha, int window) {
        int n = order.length;
        int k = rank(n, alpha);
        int h = Math.max(0, window / 2);
        int lo = Math.max(1, k - h);
        int hi = Math.min(n, k + h);
        int[] out = new int[hi - lo + 1];
        System.arraycopy(order, lo - 1, out, 0, out.length);
        return out;
    }

    /** The scope's VaR as the components see it: minus its mean over the tail scenarios. */
    public static double smoothedVar(double[] total, int[] tail) {
        double s = 0;
        for (int j : tail) {
            s += total[j];
        }
        return tail.length == 0 ? 0 : -s / tail.length;
    }

    /**
     * Each part's component VaR: minus its mean over the tail scenarios, times {@code scale} (VaR / smoothed VaR to make the parts add up
     * to the VaR, 1 to add up to the smoothed VaR).
     */
    public static double component(double[] part, int[] tail, double scale) {
        double s = 0;
        for (int j : tail) {
            s += part[j];
        }
        return tail.length == 0 ? 0 : -s / tail.length * scale;
    }

    /** The scale that makes components add up to the VaR exactly (1 when the smoothed VaR is about zero). */
    public static double componentScale(double var, double smoothed) {
        return Math.abs(smoothed) > EPS ? var / smoothed : 1.0;
    }

    /** A part's component ES: minus its mean over the scope's ES scenarios (the parts add up to ES exactly). */
    public static double componentEs(double[] part, int[] order, double alpha) {
        int k = rank(order.length, alpha);
        double s = 0;
        for (int i = 0; i < k; i++) {
            s += part[order[i]];
        }
        return order.length == 0 ? 0 : -s / k;
    }

    /** {@code a - b}, element by element, into a new array. */
    public static double[] minus(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int j = 0; j < a.length; j++) {
            out[j] = a[j] - b[j];
        }
        return out;
    }

    /** {@code acc += v}. */
    public static void addInto(double[] acc, double[] v) {
        for (int j = 0; j < acc.length; j++) {
            acc[j] += v[j];
        }
    }
}
