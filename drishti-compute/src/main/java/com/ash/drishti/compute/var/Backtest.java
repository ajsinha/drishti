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
 * VaR backtesting over a history (VAR_ENGINE.md section 11): a day is an <b>exception</b> when its loss (hypothetical P&amp;L below zero)
 * is larger than the previous day's VaR. Over the last 250 days the count gives the Basel traffic light (green 0-4, amber 5-9, red 10 or
 * more); Kupiec's proportion-of-failures test asks whether the exception rate fits the confidence; Christoffersen's test asks whether
 * exceptions come independently (not in clusters).
 *
 * @param days the days compared (each with a previous day's VaR)
 * @param exceptions the exceptions among them
 * @param exceptionFlags per day of the history, whether it was an exception (the first day never is)
 * @param light green, amber or red (Basel, scaled to 250 days)
 * @param kupiecLr Kupiec's likelihood ratio (chi-squared, 1 degree of freedom)
 * @param kupiecPValue its p-value
 * @param christoffersenLr the independence likelihood ratio (chi-squared, 1 degree of freedom)
 * @param christoffersenPValue its p-value
 */
public record Backtest(int days, int exceptions, boolean[] exceptionFlags, String light, double kupiecLr, double kupiecPValue,
        double christoffersenLr, double christoffersenPValue) {

    /** Compares each day's P&amp;L with the previous day's VaR (both arrays oldest first, VaR positive). */
    public static Backtest of(double[] pnl, double[] var, double alpha) {
        int n = Math.min(pnl.length, var.length);
        boolean[] flags = new boolean[n];
        int days = 0;
        int x = 0;
        for (int i = 1; i < n; i++) {
            if (Double.isNaN(pnl[i]) || Double.isNaN(var[i - 1])) {
                continue;
            }
            days++;
            if (pnl[i] < -var[i - 1]) {
                flags[i] = true;
                x++;
            }
        }
        double p = 1 - alpha;
        double kupiec = kupiec(days, x, p);
        double christ = christoffersen(flags);
        return new Backtest(days, x, flags, light(x, days), kupiec, chi2PValue(kupiec), christ, chi2PValue(christ));
    }

    /** Basel's zones for 250 days at 99% (green 0-4, amber 5-9, red 10+), the limits scaled for a shorter history. */
    public static String light(int exceptions, int days) {
        double scale = days <= 0 ? 1 : Math.min(1.0, days / 250.0);
        if (exceptions <= Math.round(4 * scale)) {
            return "green";
        }
        return exceptions <= Math.round(9 * scale) ? "amber" : "red";
    }

    static double kupiec(int n, int x, double p) {
        if (n == 0) {
            return 0;
        }
        double phat = (double) x / n;
        double l0 = (n - x) * Math.log(1 - p) + x * Math.log(p);
        double l1 = (x == 0 ? 0 : x * Math.log(phat)) + (x == n ? 0 : (n - x) * Math.log(1 - phat));
        return Math.max(0, -2 * (l0 - l1));
    }

    static double christoffersen(boolean[] f) {
        int n00 = 0;
        int n01 = 0;
        int n10 = 0;
        int n11 = 0;
        for (int i = 2; i < f.length; i++) {
            boolean a = f[i - 1];
            boolean b = f[i];
            if (!a && !b) {
                n00++;
            } else if (!a) {
                n01++;
            } else if (!b) {
                n10++;
            } else {
                n11++;
            }
        }
        double pi0 = n00 + n01 == 0 ? 0 : (double) n01 / (n00 + n01);
        double pi1 = n10 + n11 == 0 ? 0 : (double) n11 / (n10 + n11);
        double pi = (double) (n01 + n11) / Math.max(1, n00 + n01 + n10 + n11);
        double l0 = xlogy(n00 + n10, 1 - pi) + xlogy(n01 + n11, pi);
        double l1 = xlogy(n00, 1 - pi0) + xlogy(n01, pi0) + xlogy(n10, 1 - pi1) + xlogy(n11, pi1);
        return Math.max(0, -2 * (l0 - l1));
    }

    private static double xlogy(int n, double p) {
        return n == 0 ? 0 : n * Math.log(Math.max(p, 1e-300));
    }

    /** P(chi-squared with 1 degree of freedom &gt; x) = erfc(sqrt(x / 2)). */
    static double chi2PValue(double x) {
        return x <= 0 ? 1.0 : erfc(Math.sqrt(x / 2));
    }

    /** The complementary error function (Numerical Recipes' Chebyshev fit, |error| &lt; 1.2e-7). */
    static double erfc(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806
                + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return z >= 0 ? ans : 2.0 - ans;
    }
}
