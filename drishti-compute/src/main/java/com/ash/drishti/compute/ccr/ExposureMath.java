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
package com.ash.drishti.compute.ccr;

import java.util.Arrays;

/**
 * The exposure measures (EXP.md sections 5 and 6) over plain arrays, the same rules as tools/packgen/ccr/ccrmath.py (the parity test
 * compares them on the committed test data). A matrix is one row of P path values per date, row-major: {@code m[i * P + p]}; the
 * exposure matrices hold the primary dates only (row i is the grid's i-th primary point). Every sum and mean is in double.
 *
 * <ul>
 *   <li><b>Collateral</b> ({@link Model#LAGGED}): what the CSA calls at the look-back point on the same path ({@link CsaTerms#called});
 *       a primary point with no look-back point keeps today's collateral; {@link Model#CURRENT} holds today's collateral on every path;
 *       {@link Model#NONE} none (and no initial margin). An unmargined set holds its independent amount.</li>
 *   <li><b>Exposure</b> {@code E = max(V - C - IM, 0)}, negative exposure {@code N = min(V - C, 0)}; both add per path across sets.</li>
 *   <li><b>Statistics</b>: EE and ENE the means over paths; PFE_q the ceil(P q - 1e-9)-th smallest exposure ({@link Rule#KTH}) or
 *       interpolated; EEE the running maximum of EE from the current exposure; EPE and EEPE their time averages over the first year,
 *       T = min(1 year, the longest remaining maturity), the interval that crosses T counted up to T; the peaks over the primary dates
 *       (the earliest on a tie).</li>
 *   <li><b>Contributions</b>: parts whose path values add up to the scope's E; a part's EE contribution is its mean, its PFE
 *       contribution its mean over the paths ranked around the quantile at the peak date ({@link #window}), scaled so the parts add up
 *       to the peak PFE.</li>
 * </ul>
 */
public final class ExposureMath {

    private static final double EPS = 1e-9;

    /** How the collateral on a path is found (drishti.ccr.exposure.collateral-model). */
    public enum Model {
        LAGGED, CURRENT, NONE;

        public static Model of(String s) {
            String v = s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (v) {
                case "", "lagged" -> LAGGED;
                case "current" -> CURRENT;
                case "none" -> NONE;
                default -> throw new IllegalArgumentException("collateral is lagged, current or none, not " + s);
            };
        }

        public String text() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** How PFE is read from the sorted exposures (drishti.ccr.exposure.rank-rule). */
    public enum Rule { KTH, INTERPOLATED }

    private ExposureMath() {}

    /** The 1-based rank of PFE_q among P exposures: ceil(P q - 1e-9), within 1..P. */
    public static int rank(int p, double q) {
        return Math.max(1, Math.min(p, (int) Math.ceil(p * q - EPS)));
    }

    /** PFE_q of {@code sorted} (ascending). */
    public static double quantileSorted(double[] sorted, double q, Rule rule) {
        int p = sorted.length;
        if (p == 0) {
            return 0;
        }
        if (rule == Rule.INTERPOLATED) {
            double pos = p * q;
            int lo = Math.max(1, Math.min(p, (int) Math.floor(pos)));
            int hi = Math.min(p, lo + 1);
            double w = Math.min(1.0, Math.max(0.0, pos - lo));
            return (1 - w) * sorted[lo - 1] + w * sorted[hi - 1];
        }
        return sorted[rank(p, q) - 1];
    }

    /** Row i of a matrix of P columns, sorted ascending (a copy). */
    public static double[] sortedRow(double[] m, int i, int p) {
        double[] s = Arrays.copyOfRange(m, i * p, i * p + p);
        Arrays.sort(s);
        return s;
    }

    /**
     * One netting set's E, N and C at the primary dates from its value matrix (every grid date).
     *
     * @param v the set's value, {@code G x P}
     * @param csa its terms ({@link CsaTerms#NONE}: no collateral)
     * @return {@code {E, N, C}}, each {@code G_primary x P}
     */
    public static double[][] exposure(double[] v, ExposureGrid grid, int p, CsaTerms csa, Model model) {
        int gp = grid.primaryCount();
        double[] e = new double[gp * p];
        double[] n = new double[gp * p];
        double[] c = new double[gp * p];
        double im = model == Model.NONE || csa == null ? 0 : csa.imHeld();
        for (int i = 0; i < gp; i++) {
            int k = grid.primaryAt(i);
            int lb = grid.lookbackFor(k);
            for (int j = 0; j < p; j++) {
                double x = collateralAt(v, lb, j, p, csa, model);
                double val = v[k * p + j];
                c[i * p + j] = x;
                e[i * p + j] = Math.max(val - x - im, 0);
                n[i * p + j] = Math.min(val - x, 0);
            }
        }
        return new double[][] {e, n, c};
    }

    /** The collateral on path j at a primary point whose look-back point is {@code lb} (-1: none). */
    static double collateralAt(double[] v, int lb, int j, int p, CsaTerms csa, Model model) {
        if (csa == null || !csa.present() || model == Model.NONE) {
            return 0;
        }
        if (!csa.margined()) {
            return csa.ia();
        }
        if (model == Model.CURRENT || lb < 0) {
            return csa.heldToday();
        }
        return csa.called(v[lb * p + j]);
    }

    /** The current exposure of a set: {@code max(MTM - C_today - IM, 0)} (no collateral: {@code max(MTM, 0)}). */
    public static double currentExposure(double v0, CsaTerms csa, Model model) {
        if (csa == null || !csa.present() || model == Model.NONE) {
            return Math.max(v0, 0);
        }
        double held = csa.margined() ? csa.heldToday() : csa.ia();
        return Math.max(v0 - held - csa.imHeld(), 0);
    }

    /** {@code max(V, 0)} at the primary dates: the exposure without collateral, {@code G_primary x P}. */
    public static double[] positive(double[] v, ExposureGrid grid, int p) {
        int gp = grid.primaryCount();
        double[] out = new double[gp * p];
        for (int i = 0; i < gp; i++) {
            int k = grid.primaryAt(i);
            for (int j = 0; j < p; j++) {
                out[i * p + j] = Math.max(v[k * p + j], 0);
            }
        }
        return out;
    }

    /** {@code acc += m}, element by element. */
    public static void addInto(double[] acc, double[] m) {
        for (int i = 0; i < acc.length; i++) {
            acc[i] += m[i];
        }
    }

    /**
     * The statistics of a scope from its summed matrices.
     *
     * @param e the scope's exposure, {@code G_primary x P}
     * @param n its negative exposure
     * @param uncoll the exposure without collateral (sum over sets of max(V, 0)), or null
     * @param times the primary points' times in years
     * @param horizon T in years (EPE and EEPE average over [0, T])
     * @param qs the confidences computed
     * @param ce the current exposure (EEE starts from it)
     */
    public static Stats statistics(double[] e, double[] n, double[] uncoll, double[] times, int p, double horizon, double[] qs, double ce, Rule rule) {
        int gp = times.length;
        Stats s = new Stats(gp, qs);
        s.ce = ce;
        double eee = ce;
        for (int i = 0; i < gp; i++) {
            s.ee[i] = mean(e, i, p);
            s.ene[i] = mean(n, i, p);
            s.eeUncoll[i] = uncoll == null ? Double.NaN : mean(uncoll, i, p);
            eee = Math.max(eee, s.ee[i]);
            s.eee[i] = eee;
            double[] sorted = sortedRow(e, i, p);
            for (int a = 0; a < qs.length; a++) {
                s.pfe[a][i] = quantileSorted(sorted, qs[a], rule);
            }
        }
        s.horizon = horizon;
        if (horizon > 0) {
            double prev = 0;
            for (int i = 0; i < gp; i++) {
                if (prev >= horizon - EPS) {
                    break;
                }
                double dt = Math.min(times[i], horizon) - prev;
                s.epe += s.ee[i] * dt;
                s.eepe += s.eee[i] * dt;
                prev = times[i];
            }
            s.epe /= horizon;
            s.eepe /= horizon;
        }
        for (int i = 0; i < gp; i++) {
            s.peakEe = Math.max(s.peakEe, s.ee[i]);
            for (int a = 0; a < qs.length; a++) {
                if (s.peakAt[a] < 0 || s.pfe[a][i] > s.peakPfe[a]) {
                    s.peakPfe[a] = s.pfe[a][i];
                    s.peakAt[a] = i;
                }
            }
        }
        return s;
    }

    static double mean(double[] m, int i, int p) {
        double sum = 0;
        for (int j = 0; j < p; j++) {
            sum += m[i * p + j];
        }
        return p == 0 ? 0 : sum / p;
    }

    /** Path indices of row i from the smallest value to the largest (ties: the lower index first). */
    public static int[] order(double[] m, int i, int p) {
        Integer[] idx = new Integer[p];
        for (int j = 0; j < p; j++) {
            idx[j] = j;
        }
        int off = i * p;
        Arrays.sort(idx, (a, b) -> {
            int c = Double.compare(m[off + a], m[off + b]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[] out = new int[p];
        for (int j = 0; j < p; j++) {
            out[j] = idx[j];
        }
        return out;
    }

    /** The paths ranked around the quantile at row i: ranks k - h .. k + h (h = size / 2) of the scope's exposures. */
    public static int[] window(double[] e, int i, int p, double q, int size) {
        int[] o = order(e, i, p);
        int k = rank(p, q);
        int h = Math.max(0, size / 2);
        int lo = Math.max(1, k - h);
        int hi = Math.min(p, k + h);
        return Arrays.copyOfRange(o, lo - 1, hi);
    }

    /** A part's mean over the given paths of row i. */
    public static double meanOver(double[] part, int i, int p, int[] paths) {
        double s = 0;
        for (int j : paths) {
            s += part[i * p + j];
        }
        return paths.length == 0 ? 0 : s / paths.length;
    }

    /** The scale that makes window means add up to the peak PFE (1 when they add up to about zero). */
    public static double scale(double peak, double sumOfMeans) {
        return Math.abs(sumOfMeans) > EPS ? peak / sumOfMeans : 1.0;
    }

    /**
     * One trade's share of its set's exposure on every path at the primary dates: {@code (MTM_i - w_i (C + IM)) 1{E > 0}} with
     * {@code w_i = MTM_i / V} (0 where V is 0); the shares of a set's trades add up to its E on every path.
     */
    public static double[] tradeShare(double[] mtm, double[] v, double[] e, double[] c, double im, ExposureGrid grid, int p) {
        int gp = grid.primaryCount();
        double[] out = new double[gp * p];
        for (int i = 0; i < gp; i++) {
            int k = grid.primaryAt(i);
            for (int j = 0; j < p; j++) {
                double vv = v[k * p + j];
                if (e[i * p + j] > 0 && vv != 0) {
                    double m = mtm[k * p + j];
                    out[i * p + j] = m - m / vv * (c[i * p + j] + im);
                }
            }
        }
        return out;
    }

    /** The statistics of a scope: the profile at the primary points and the time averages and peaks. */
    public static final class Stats {
        public final double[] ee;
        public final double[] ene;
        public final double[] eee;
        public final double[] eeUncoll;
        /** PFE at each confidence of {@link #qs}, by primary point. */
        public final double[][] pfe;
        public final double[] qs;
        public final double[] peakPfe;
        /** The primary point (index among the primaries) of each peak. */
        public final int[] peakAt;
        public double epe;
        public double eepe;
        public double peakEe;
        public double ce;
        public double horizon;

        Stats(int gp, double[] qs) {
            this.ee = new double[gp];
            this.ene = new double[gp];
            this.eee = new double[gp];
            this.eeUncoll = new double[gp];
            this.qs = qs.clone();
            this.pfe = new double[qs.length][gp];
            this.peakPfe = new double[qs.length];
            this.peakAt = new int[qs.length];
            Arrays.fill(peakAt, -1);
        }

        /** The index of confidence q among {@link #qs}, -1 when it was not computed. */
        public int at(double q) {
            for (int a = 0; a < qs.length; a++) {
                if (Math.abs(qs[a] - q) < 1e-12) {
                    return a;
                }
            }
            return -1;
        }
    }
}
