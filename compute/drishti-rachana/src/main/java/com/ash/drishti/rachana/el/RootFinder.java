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
 * The root finding behind {@code irr}, {@code xirr} and {@code rate} ({@link FinanceFunctions}): a rate r above −1 at which a value
 * function f(r) is 0.
 *
 * <ol>
 *   <li>Newton's method from the guess (Excel's 0.1 by default), with the derivative by a central difference, at most
 *       {@value #NEWTON_STEPS} steps, until a step is under {@value #TOLERANCE} (absolute on the rate); a step that would leave the
 *       domain (r ≤ −1) or does not converge ends it.</li>
 *   <li>Otherwise the domain from −0.9999 to {@value #MAX_RATE} is scanned on a grid of 1 + r spaced logarithmically
 *       ({@value #GRID} points) for a change of sign of f; of the brackets found, the one nearest the guess is narrowed by bisection to
 *       {@value #TOLERANCE}.</li>
 *   <li>No change of sign anywhere: no rate makes the value 0, and the function says so in words (Excel's {@code #NUM!}); never a figure
 *       that is not a root.</li>
 * </ol>
 * Where the flows have several roots (more than one change of sign), the one Newton reaches from the guess is the answer, as Excel's;
 * a different guess can find another.
 */
final class RootFinder {

    private RootFinder() {}

    /** The rate a root is found to (absolute). */
    static final double TOLERANCE = 1e-12;

    /** The most Newton steps. */
    static final int NEWTON_STEPS = 100;

    /** The highest rate the scan reaches (1,000,000 %). */
    static final double MAX_RATE = 1e4;

    /** The points of the scan. */
    static final int GRID = 4_000;

    /** A root of f near the guess, or a problem in words when there is none. */
    static double root(String fn, DoubleUnaryOperator f, double guess) {
        if (!(guess > -1)) {
            throw Args.error(fn, "the guess " + Values.text(Values.normalise(guess)) + " is not above −1 (−100 %)");
        }
        Double newton = newton(f, guess);
        if (newton != null) {
            return newton;
        }
        double lnLo = Math.log(1e-4);                 // 1 + r from 0.0001 (r = −0.9999) …
        double lnHi = Math.log(1 + MAX_RATE);         // … to 1 + MAX_RATE
        double best = Double.NaN;
        double bestLo = 0;
        double bestHi = 0;
        double prevR = Math.exp(lnLo) - 1;
        double prevF = f.applyAsDouble(prevR);
        for (int i = 1; i <= GRID; i++) {
            double r = Math.exp(lnLo + (lnHi - lnLo) * i / GRID) - 1;
            double fr = f.applyAsDouble(r);
            if (Double.isFinite(prevF) && Double.isFinite(fr) && (prevF == 0 || Math.signum(prevF) != Math.signum(fr))) {
                double distance = Math.abs((prevR + r) / 2 - guess);
                if (Double.isNaN(best) || distance < best) {
                    best = distance;
                    bestLo = prevR;
                    bestHi = r;
                }
            }
            prevR = r;
            prevF = fr;
        }
        if (Double.isNaN(best)) {
            throw Args.error(fn, "no rate from −99.99 % to " + (long) (MAX_RATE * 100) + " % makes the value 0");
        }
        return bisect(f, bestLo, bestHi);
    }

    private static Double newton(DoubleUnaryOperator f, double guess) {
        double r = guess;
        for (int i = 0; i < NEWTON_STEPS; i++) {
            double fr = f.applyAsDouble(r);
            if (!Double.isFinite(fr)) {
                return null;
            }
            double h = 1e-7 * Math.max(1, Math.abs(r));
            double up = r + h;
            double down = Math.max(r - h, (r + -1) / 2);
            double d = (f.applyAsDouble(up) - f.applyAsDouble(down)) / (up - down);
            if (!Double.isFinite(d) || d == 0) {
                return null;
            }
            double next = r - fr / d;
            if (!(next > -1) || !Double.isFinite(next)) {
                return null;
            }
            if (Math.abs(next - r) < TOLERANCE) {
                return next;
            }
            r = next;
        }
        return null;
    }

    private static double bisect(DoubleUnaryOperator f, double lo, double hi) {
        double flo = f.applyAsDouble(lo);
        if (flo == 0) {
            return lo;
        }
        for (int i = 0; i < 400 && hi - lo > TOLERANCE; i++) {
            double mid = lo + (hi - lo) / 2;
            double fm = f.applyAsDouble(mid);
            if (fm == 0) {
                return mid;
            }
            if (Math.signum(fm) == Math.signum(flo)) {
                lo = mid;
                flo = fm;
            } else {
                hi = mid;
            }
        }
        return lo + (hi - lo) / 2;
    }
}
