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

import java.util.List;
import java.util.Locale;

/**
 * FVA, the funding cost and benefit of a netting set (docs/architecture/COUNTERPARTY_ENGINES.md section 5), from the EE and ENE profile the
 * exposure engine already computed ({@link ExposureMath}): never a second exposure computation. Per primary date t_k (k = 1..K, t_0 = 0):
 *
 * <pre>
 *   EE*(t_k)  = EE(t_k) · DF(t_k)                 ENE*(t_k) = ENE(t_k) · DF(t_k)
 *   FCA       = − Σ_k EE*(t_k)    · s_B(t_k) · Δt_k   [· S(t_k), survival weighting on]
 *   FBA       = + Σ_k |ENE*(t_k)| · s_L(t_k) · Δt_k   [· S(t_k)]
 *   FVA       = FCA + FBA
 * </pre>
 *
 * s_B is the bank's borrowing (funding) spread and s_L its lending spread; symmetric funding (the default) has s_L = s_B. With the
 * {@code mid} point the period's exposure is the average of both ends, EE*(t_0) the current exposure and ENE*(t_0) the current negative
 * exposure. Signs from the bank's side: FCA ≤ 0, FBA ≥ 0. Every figure is linear in EE and ENE, so a scope's FVA is the sum of its sets'.
 */
public final class FvaMath {

    private FvaMath() {}

    /** Where a period's exposure is read (drishti.ccr.fva.ee-point). */
    public enum Point {
        END, MID;

        public static Point of(String s) {
            String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "", "end" -> END;
                case "mid" -> MID;
                default -> throw new IllegalArgumentException("ee-point is end or mid, not " + s);
            };
        }

        public String text() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A curve of points by time in years: a spread (linear between points, flat beyond), a discount factor (log-linear, DF(0) = 1) or a
     * survival probability (log-linear, S(0) = 1).
     *
     * @param id the curve's id ({@code CRV-USD-OIS}, {@code flat})
     * @param tenors the points' tenors as written ({@code 6M}, {@code 1Y}); may be empty for a flat curve
     * @param t the points' times in years, ascending
     * @param v the points' values
     * @param source where it was read from (the lake, the configuration, the flat setting)
     */
    public record Curve(String id, List<String> tenors, double[] t, double[] v, String source) {

        /** A flat curve: one value at every time. */
        public static Curve flat(String id, double value, String source) {
            return new Curve(id, List.of(), new double[] {0}, new double[] {value}, source);
        }

        public boolean isFlat() {
            if (v.length <= 1) {
                return true;
            }
            for (double x : v) {
                if (Math.abs(x - v[0]) > 1e-15) {
                    return false;
                }
            }
            return true;
        }

        /** Linear in t between points, flat before the first and beyond the last (a spread). */
        public double linear(double x) {
            return FvaMath.linear(t, v, x);
        }

        /** Log-linear in the value between (0, 1) and the points, the last segment's rate beyond (a discount factor or a survival). */
        public double logLinear(double x) {
            return FvaMath.logLinear(t, v, x);
        }
    }

    /**
     * A netting set's (or a scope's) profile at the primary dates.
     *
     * @param t the primary dates' times in years
     * @param ee EE(t_k), after collateral
     * @param ene ENE(t_k) (≤ 0), after collateral
     * @param ce the current exposure (EE at t_0; read with the {@code mid} point)
     * @param nce the current negative exposure (ENE at t_0, ≤ 0)
     */
    public record Profile(double[] t, double[] ee, double[] ene, double ce, double nce) {}

    /**
     * The market terms per primary date.
     *
     * @param df DF(t_k) (all 1 when the paths are already discounted)
     * @param sb the borrowing spread s_B(t_k), in decimals
     * @param sl the lending spread s_L(t_k), in decimals
     * @param survival S_C(t_k) · S_B(t_k), or null when survival weighting is off
     */
    public record Market(double[] df, double[] sb, double[] sl, double[] survival) {}

    /** FVA of one profile, with every term per date. */
    public record Result(double fca, double fba, double fva, double[] t, double[] dt, double[] df, double[] sb, double[] sl, double[] survival,
            double[] ee, double[] ene, double[] eeStar, double[] eneStar, double[] eePoint, double[] enePoint, double[] fcaTerm, double[] fbaTerm,
            double sumEeDt, double sumEneDt, double life) {

        /** EPE over the whole life: Σ EE(t_k) Δt_k / T_life (the EPE reading; not the regulatory first-year EPE). */
        public double epeLife() {
            return life > 0 ? sumEeDt / life : 0;
        }

        /** ENE's time average over the whole life (≤ 0). */
        public double eneLife() {
            return life > 0 ? sumEneDt / life : 0;
        }
    }

    /** Linear interpolation, flat beyond the ends. */
    public static double linear(double[] t, double[] v, double x) {
        int n = t.length;
        if (n == 0) {
            return 0;
        }
        if (x <= t[0]) {
            return v[0];
        }
        if (x >= t[n - 1]) {
            return v[n - 1];
        }
        int i = 1;
        while (t[i] < x) {
            i++;
        }
        double w = (x - t[i - 1]) / (t[i] - t[i - 1]);
        return v[i - 1] + w * (v[i] - v[i - 1]);
    }

    /** Log-linear interpolation anchored at (0, 1); beyond the last point the last segment's continuous rate goes on. */
    public static double logLinear(double[] t, double[] v, double x) {
        if (x <= 0) {
            return 1.0;
        }
        double t0 = 0;
        double l0 = 0;
        for (int i = 0; i < t.length; i++) {
            if (t[i] <= 0) {
                continue;
            }
            double l1 = Math.log(v[i]);
            if (x <= t[i]) {
                return Math.exp(l0 + (x - t0) / (t[i] - t0) * (l1 - l0));
            }
            if (i == t.length - 1) {
                double rate = (l1 - l0) / (t[i] - t0);
                return Math.exp(l1 + rate * (x - t[i]));
            }
            t0 = t[i];
            l0 = l1;
        }
        return 1.0;
    }

    /** A tenor's years: {@code 1D} (1/365), {@code 1W} (7/365), {@code 1M} (1/12), {@code 1Y}; a plain number is years. */
    public static double tenorYears(String tenor) {
        String s = tenor == null ? "" : tenor.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("a tenor is 1D, 1W, 6M, 1Y or a number of years, not empty");
        }
        char u = s.charAt(s.length() - 1);
        try {
            if (Character.isDigit(u) || u == '.') {
                return Double.parseDouble(s);
            }
            double n = Double.parseDouble(s.substring(0, s.length() - 1));
            return switch (u) {
                case 'D' -> n / 365.0;
                case 'W' -> n * 7 / 365.0;
                case 'M' -> n / 12.0;
                case 'Y' -> n;
                default -> throw new IllegalArgumentException("a tenor is 1D, 1W, 6M, 1Y or a number of years, not " + tenor);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("a tenor is 1D, 1W, 6M, 1Y or a number of years, not " + tenor);
        }
    }

    /** The market terms at the profile's dates from the curves (the discount curve null: DF = 1; survival null: off). */
    public static Market market(double[] t, Curve discount, Curve borrow, Curve lend, Curve survival) {
        int k = t.length;
        double[] df = new double[k];
        double[] sb = new double[k];
        double[] sl = new double[k];
        double[] s = survival == null ? null : new double[k];
        for (int i = 0; i < k; i++) {
            df[i] = discount == null ? 1.0 : discount.logLinear(t[i]);
            sb[i] = borrow.linear(t[i]);
            sl[i] = (lend == null ? borrow : lend).linear(t[i]);
            if (s != null) {
                s[i] = survival.logLinear(t[i]);
            }
        }
        return new Market(df, sb, sl, s);
    }

    /** Survival from flat hazard rates: S_C(t) · S_B(t) = e^(−(h_C + h_B) t). */
    public static double[] flatSurvival(double[] t, double hazardCounterparty, double hazardBank) {
        double[] s = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            s[i] = Math.exp(-(hazardCounterparty + hazardBank) * t[i]);
        }
        return s;
    }

    /**
     * FCA, FBA and FVA of a profile.
     *
     * @param fba false: FCA only (asymmetric funding), FBA reported as 0
     */
    public static Result compute(Profile pr, Market mk, Point point, boolean fba) {
        int k = pr.t().length;
        double[] dt = new double[k];
        double[] eeStar = new double[k];
        double[] eneStar = new double[k];
        double[] eePt = new double[k];
        double[] enePt = new double[k];
        double[] fcaT = new double[k];
        double[] fbaT = new double[k];
        double fca = 0;
        double fb = 0;
        double sumEe = 0;
        double sumEne = 0;
        double prev = 0;
        double prevEe = pr.ce();
        double prevEne = Math.abs(pr.nce());
        for (int i = 0; i < k; i++) {
            dt[i] = pr.t()[i] - prev;
            prev = pr.t()[i];
            eeStar[i] = pr.ee()[i] * mk.df()[i];
            eneStar[i] = pr.ene()[i] * mk.df()[i];
            double absEne = Math.abs(eneStar[i]);
            eePt[i] = point == Point.MID ? (prevEe + eeStar[i]) / 2 : eeStar[i];
            enePt[i] = point == Point.MID ? (prevEne + absEne) / 2 : absEne;
            prevEe = eeStar[i];
            prevEne = absEne;
            double w = mk.survival() == null ? 1.0 : mk.survival()[i];
            fcaT[i] = -eePt[i] * mk.sb()[i] * w * dt[i];
            fbaT[i] = fba ? enePt[i] * mk.sl()[i] * w * dt[i] : 0;
            fca += fcaT[i];
            fb += fbaT[i];
            sumEe += pr.ee()[i] * dt[i];
            sumEne += pr.ene()[i] * dt[i];
        }
        double life = k == 0 ? 0 : pr.t()[k - 1];
        return new Result(fca, fb, fca + fb, pr.t().clone(), dt, mk.df().clone(), mk.sb().clone(), mk.sl().clone(),
                mk.survival() == null ? null : mk.survival().clone(), pr.ee().clone(), pr.ene().clone(), eeStar, eneStar, eePt, enePt, fcaT, fbaT,
                sumEe, sumEne, life);
    }

    /** The sum of results on one grid (a scope of several netting sets): figures, terms and profiles added; market terms kept. */
    public static Result sum(List<Result> parts) {
        if (parts.isEmpty()) {
            return compute(new Profile(new double[0], new double[0], new double[0], 0, 0), new Market(new double[0], new double[0], new double[0], null),
                    Point.END, true);
        }
        Result f = parts.get(0);
        int k = f.t().length;
        double[] ee = new double[k];
        double[] ene = new double[k];
        double[] eeStar = new double[k];
        double[] eneStar = new double[k];
        double[] eePt = new double[k];
        double[] enePt = new double[k];
        double[] fcaT = new double[k];
        double[] fbaT = new double[k];
        double fca = 0;
        double fb = 0;
        double sumEe = 0;
        double sumEne = 0;
        for (Result r : parts) {
            if (r.t().length != k) {
                throw new IllegalArgumentException("results on different grids do not add");
            }
            for (int i = 0; i < k; i++) {
                ee[i] += r.ee()[i];
                ene[i] += r.ene()[i];
                eeStar[i] += r.eeStar()[i];
                eneStar[i] += r.eneStar()[i];
                eePt[i] += r.eePoint()[i];
                enePt[i] += r.enePoint()[i];
                fcaT[i] += r.fcaTerm()[i];
                fbaT[i] += r.fbaTerm()[i];
            }
            fca += r.fca();
            fb += r.fba();
            sumEe += r.sumEeDt();
            sumEne += r.sumEneDt();
        }
        double[] survival = f.survival();                                    // one survival for the sum only when every part shares it
        for (Result r : parts) {
            if (survival != null && !java.util.Arrays.equals(survival, r.survival())) {
                survival = null;
            }
        }
        return new Result(fca, fb, fca + fb, f.t(), f.dt(), f.df(), f.sb(), f.sl(), survival, ee, ene, eeStar, eneStar, eePt, enePt, fcaT, fbaT,
                sumEe, sumEne, f.life());
    }
}
