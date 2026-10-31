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

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * How a VaR answer is read from a scope's 1-day scenario P&amp;L vector (2.9.7, VAR_GUIDE.md section 18.6): the horizon, the 10-day method
 * and the confidences. Nothing new is stored: every lens is computed on demand from the same 1-day vector.
 *
 * <ul>
 *   <li><b>1-day</b> ({@link Method#NONE}): the vector as it is.</li>
 *   <li><b>10-day by √10</b> ({@link Method#SQRT}): every scenario P&amp;L times √10; VaR and ES are the 1-day ones times √10 exactly (the
 *       order of the scenarios does not change), components and contributions the scaled parts'.</li>
 *   <li><b>10-day sum</b> ({@link Method#SUM}): window {@code i} is the sum of the 1-day P&amp;Ls of scenarios {@code i .. i + 9} in the
 *       scenario set's date order (oldest first), so N scenarios give N - 9 overlapping windows (509: 500; 250: 241). The sum is taken
 *       left to right in a fixed order from the scope's 1-day vector (itself summed from position rows in the fixed reduction order), so
 *       a 10-day vector is the same to the bit whatever the shards or threads that summed its 1-day vector.</li>
 * </ul>
 * The confidences pick another rank of the same sorted vector (k = ceil(N (1 - a)) by the rank rule).
 *
 * @param days 1 or 10
 * @param method {@link Method#NONE} for 1 day, else how the 10 days are made
 * @param confidence VaR's
 * @param esConfidence ES's
 */
public record VarLens(int days, Method method, double confidence, double esConfidence) {

    /** The days of the long horizon. */
    public static final int TEN = 10;
    /** √10, the scaling factor of the {@link Method#SQRT} method. */
    public static final double SQRT10 = Math.sqrt(TEN);

    /** How a 10-day figure is made from the 1-day inputs. */
    public enum Method {
        /** 1-day: no transformation. */
        NONE,
        /** 1-day × √10. */
        SQRT,
        /** The sum of 10 consecutive 1-day scenario P&amp;Ls (overlapping windows). */
        SUM;

        /** {@code sqrt10} or {@code sum} (the setting's words); NONE is {@code null}. */
        public String word() {
            return this == SQRT ? "sqrt10" : this == SUM ? "sum" : null;
        }

        /** Reads {@code sqrt}, {@code sqrt10}, {@code √10}, {@code scaled} as SQRT, {@code sum}, {@code overlapping} as SUM. */
        public static Method parse(String s) {
            String v = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "sqrt", "sqrt10", "√10", "scaled", "scale" -> SQRT;
                case "sum", "overlapping", "windows" -> SUM;
                default -> throw new IllegalArgumentException("method '" + s + "' is sqrt10 (the 1-day × √10) or sum (10 consecutive days)");
            };
        }
    }

    public VarLens {
        if (days != 1 && days != TEN) {
            throw new IllegalArgumentException("horizon " + days + " days: the horizons are 1d and 10d");
        }
        method = days == 1 ? Method.NONE : method == null || method == Method.NONE ? Method.SQRT : method;
    }

    /** The 1-day lens at the given confidences. */
    public static VarLens oneDay(double confidence, double esConfidence) {
        return new VarLens(1, Method.NONE, confidence, esConfidence);
    }

    /** Reads a horizon: {@code 1d}, {@code 1}, {@code 10d}, {@code 10} (any case); null or blank is 1 day. */
    public static int parseHorizon(String h) {
        String v = h == null ? "" : h.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "1", "1d", "1-day", "1day" -> 1;
            case "10", "10d", "10-day", "10day" -> TEN;
            default -> throw new IllegalArgumentException("horizon '" + h + "' is 1d or 10d");
        };
    }

    public boolean tenDay() {
        return days == TEN;
    }

    /** {@code 1D} or {@code 10D}, as records and answers name it. */
    public String horizon() {
        return days + "D";
    }

    /** The method as records name it: {@code SQRT10}, {@code SUM}, or null for 1 day. */
    public String methodName() {
        return method == Method.NONE ? null : method == Method.SQRT ? "SQRT10" : "SUM";
    }

    /** {@code 1-day}, {@code 10-day (√10)}, {@code 10-day (sum)}. */
    public String label() {
        return method == Method.NONE ? "1-day" : method == Method.SQRT ? "10-day (√10)" : "10-day (sum)";
    }

    /** A key for caches: {@code 1D|0.99|0.975}. */
    public String key() {
        return horizon() + (method == Method.NONE ? "" : "-" + method) + "|" + confidence + "|" + esConfidence;
    }

    /** The calculator at this lens's confidences, the rest of {@code base}'s settings kept. */
    public VarCalc calc(VarCalc base) {
        return new VarCalc(confidence, esConfidence, base.rule(), base.window(), base.scaleToVar());
    }

    /** How many scenarios (or windows) a vector of {@code n} 1-day scenarios gives. */
    public int width(int n) {
        return method == Method.SUM ? Math.max(0, n - TEN + 1) : n;
    }

    /** The vector this lens reads, from a 1-day vector: itself (1 day), a scaled copy (√10), or the windows' sums. */
    public double[] apply(double[] oneDay) {
        return switch (method) {
            case NONE -> oneDay;
            case SQRT -> scaled(oneDay, SQRT10);
            case SUM -> tenDaySum(oneDay);
        };
    }

    /**
     * The measures of a scope at this lens: on the 1-day vector at the lens's confidences, then (√10) VaR, ES and the smoothed VaR times
     * √10 exactly (the order, the tail and the component scale are the 1-day ones), or (sum) on the windows.
     *
     * @param base the engine's calculator (rank rule, component window)
     * @param oneDay the scope's 1-day vector
     * @param lensed {@code apply(oneDay)}
     */
    public VarCalc.Measures measures(VarCalc base, double[] oneDay, double[] lensed) {
        VarCalc c = calc(base);
        if (method != Method.SQRT) {
            return c.measures(lensed);
        }
        VarCalc.Measures m = c.measures(oneDay);
        return new VarCalc.Measures(m.var() * SQRT10, m.es() * SQRT10, m.order(), m.tail(), m.smoothed() * SQRT10, m.scale(), m.varScenario(),
                m.k(), m.esCount());
    }

    /** {@code v} times {@code f}, element by element, into a new array. */
    public static double[] scaled(double[] v, double f) {
        double[] out = new double[v.length];
        for (int j = 0; j < v.length; j++) {
            out[j] = v[j] * f;
        }
        return out;
    }

    /**
     * The 10-day sums: {@code out[i] = v[i] + v[i+1] + ... + v[i+9]}, added left to right (a fixed order: the same bits for the same 1-day
     * vector); {@code n - 9} windows, none when {@code n < 10}.
     */
    public static double[] tenDaySum(double[] v) {
        int w = Math.max(0, v.length - TEN + 1);
        double[] out = new double[w];
        for (int i = 0; i < w; i++) {
            double s = v[i];
            for (int j = 1; j < TEN; j++) {
                s += v[i + j];
            }
            out[i] = s;
        }
        return out;
    }

    /**
     * The date each scenario (or window) of this lens is labelled with: the scenario's own date, or a window's last day (its first is
     * {@link #windowStart}).
     */
    public List<LocalDate> dates(List<LocalDate> scenarioDates) {
        return method == Method.SUM ? scenarioDates.subList(Math.min(scenarioDates.size(), TEN - 1), scenarioDates.size()) : scenarioDates;
    }

    /** A window's first scenario date (the scenario's own date on the other lenses). */
    public LocalDate windowStart(List<LocalDate> scenarioDates, int i) {
        return scenarioDates.get(i);
    }

    /** A sentence for the method strip and provenance. */
    public String describe(int scenarios) {
        String conf = pct(confidence) + " VaR, ES " + pct(esConfidence);
        return switch (method) {
            case NONE -> "1-day · " + conf + " · historical simulation, " + scenarios + " scenarios";
            case SQRT -> "10-day by √10 · " + conf + " · the 1-day figures × √10 (" + String.format(Locale.ROOT, "%.4f", SQRT10)
                    + "); assumes independent days and a roughly linear book";
            case SUM -> "10-day sum of days · " + conf + " · " + width(scenarios) + " overlapping windows of 10 consecutive scenario days ("
                    + scenarios + " scenarios); positions as of today";
        };
    }

    /** {@code 99%}, {@code 97.5%}. */
    public static String pct(double a) {
        double p = Math.round(a * 10_000) / 100.0;
        return (p == Math.rint(p) ? Long.toString((long) p) : Double.toString(p)) + "%";
    }
}
