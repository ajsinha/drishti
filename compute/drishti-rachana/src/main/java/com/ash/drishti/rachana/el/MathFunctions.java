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

import com.ash.drishti.api.DataNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Math (FUNCTION_CATALOGUE.md section 1): {@code abs}, {@code round_up}, {@code round_down}, {@code mround}, {@code ceiling},
 * {@code floor}, {@code mod}, {@code power}, {@code sqrt}, {@code exp}, {@code ln}, {@code log10}, {@code sign},
 * {@code clamp}, {@code least}, {@code greatest}, and {@code min}/{@code max} (over a list, or the old spelling of
 * {@code least}/{@code greatest}). Rounding, multiples, remainders, whole powers and roots are worked in decimal, so
 * {@code round_up(2.675, 2)} is {@code 2.68} and money never passes through binary floating point; {@code exp},
 * {@code ln}, {@code log10} and fractional powers are real-number functions and give doubles.
 */
final class MathFunctions {

    private MathFunctions() {}

    /** The most decimal places (either way) a rounding takes: a bound on the work, far past any currency's. */
    static final int MAX_PLACES = 100;

    /** The largest whole exponent {@code power} works exactly in decimal; beyond it, real-number arithmetic. */
    static final int MAX_EXACT_EXPONENT = 64;

    static void register(Functions.Table t) {
        t.fn("abs", 1, 1, (a, c) -> Values.isNull(a.get(0)) ? null : Values.normalise(Math.abs(Values.number(a.get(0)))));   // null in, null out
        t.fn("round_up", 1, 2, (a, c) -> round("round_up", a, RoundingMode.UP));
        t.fn("round_down", 1, 2, (a, c) -> round("round_down", a, RoundingMode.DOWN));
        t.fn("mround", 2, 2, (a, c) -> mround(a));
        t.fn("ceiling", 1, 2, (a, c) -> multiple("ceiling", a, RoundingMode.CEILING));
        t.fn("floor", 1, 2, (a, c) -> multiple("floor", a, RoundingMode.FLOOR));
        t.fn("mod", 2, 2, (a, c) -> mod(a));
        t.fn("power", 2, 2, (a, c) -> power(a));
        t.fn("sqrt", 1, 1, (a, c) -> sqrt(a.get(0)));
        t.fn("exp", 1, 1, (a, c) -> real("exp", a.get(0), Math::exp, false));
        t.fn("ln", 1, 1, (a, c) -> real("ln", a.get(0), Math::log, true));
        t.fn("log10", 1, 1, (a, c) -> real("log10", a.get(0), Math::log10, true));
        t.fn("sign", 1, 1, (a, c) -> {
            BigDecimal x = Args.decimal("sign", a.get(0));
            return x == null ? null : (Object) (long) x.signum();
        });
        t.fn("clamp", 3, 3, (a, c) -> clamp(a));
        t.fn("least", 1, 64, (a, c) -> pick("least", a, true));
        t.fn("greatest", 1, 64, (a, c) -> pick("greatest", a, false));
        // min/max over one list are the pivot engine's aggregates (docs/architecture/ARCHITECTURE.md part 2); with several arguments they are the
        // old spelling of least/greatest, read for compatibility with the results they always gave
        t.fn("min", 1, 8, (a, c) -> lenient(a, true, c));
        t.fn("max", 1, 8, (a, c) -> lenient(a, false, c));
    }

    /** {@code round_up(x, [n])}, {@code round_down(x, [n])}: away from or towards zero, to n decimal places (negative: tens …). */
    private static Object round(String fn, List<Object> a, RoundingMode mode) {
        BigDecimal x = Args.decimal(fn, a.get(0));
        Integer n = a.size() > 1 ? Args.whole(fn, a.get(1)) : Integer.valueOf(0);
        if (x == null || n == null) {
            return null;
        }
        if (Math.abs(n) > MAX_PLACES) {
            throw Args.error(fn, "places " + n + " is beyond ±" + MAX_PLACES);
        }
        return Args.out(x.setScale(n, mode));
    }

    /** {@code mround(x, m)}: the nearest multiple of m, a half away from zero; x and m of one sign (Excel's MROUND). */
    private static Object mround(List<Object> a) {
        BigDecimal x = Args.decimal("mround", a.get(0));
        BigDecimal m = Args.decimal("mround", a.get(1));
        if (x == null || m == null) {
            return null;
        }
        if (m.signum() == 0) {
            return 0L;
        }
        if (x.signum() != 0 && x.signum() != m.signum()) {
            throw Args.error("mround", Args.describe(a.get(0)) + " and " + Args.describe(a.get(1)) + " are of different signs");
        }
        return Args.out(x.divide(m, 0, RoundingMode.HALF_UP).multiply(m));
    }

    /** {@code ceiling(x, [m])}, {@code floor(x, [m])}: up (towards +∞) or down (towards −∞) to a multiple of |m| (1 by default). */
    private static Object multiple(String fn, List<Object> a, RoundingMode mode) {
        BigDecimal x = Args.decimal(fn, a.get(0));
        BigDecimal m = a.size() > 1 ? Args.decimal(fn, a.get(1)) : BigDecimal.ONE;
        if (x == null || m == null) {
            return null;
        }
        if (m.signum() == 0) {
            return 0L;
        }
        BigDecimal step = m.abs();
        return Args.out(x.divide(step, 0, mode).multiply(step));
    }

    /** {@code mod(x, y)}: the remainder with the sign of y (Excel's MOD: {@code mod(-3, 2)} is 1); empty when y is 0. */
    private static Object mod(List<Object> a) {
        BigDecimal x = Args.decimal("mod", a.get(0));
        BigDecimal y = Args.decimal("mod", a.get(1));
        if (x == null || y == null || y.signum() == 0) {
            return null;                                           // as x % 0: a division by zero is empty
        }
        BigDecimal r = x.remainder(y);
        if (r.signum() != 0 && r.signum() != y.signum()) {
            r = r.add(y);
        }
        return Args.out(r);
    }

    /**
     * {@code power(x, y)}: x to the power y; a whole y up to ±{@value #MAX_EXACT_EXPONENT} in decimal (34 significant digits),
     * any other in real numbers. Empty for 0 to a negative power (a division by zero); a problem when there is no real result.
     */
    private static Object power(List<Object> a) {
        BigDecimal x = Args.decimal("power", a.get(0));
        BigDecimal y = Args.decimal("power", a.get(1));
        if (x == null || y == null) {
            return null;
        }
        boolean whole = y.signum() == 0 || y.stripTrailingZeros().scale() <= 0;
        if (whole && y.abs().compareTo(BigDecimal.valueOf(MAX_EXACT_EXPONENT)) <= 0) {
            if (x.signum() == 0 && y.signum() < 0) {
                return null;
            }
            return Args.out(x.pow(y.intValueExact(), MathContext.DECIMAL128));
        }
        if (x.signum() == 0 && y.signum() < 0) {
            return null;
        }
        double r = Math.pow(x.doubleValue(), y.doubleValue());
        if (Double.isNaN(r)) {
            throw Args.error("power", Args.describe(a.get(0)) + " to the power " + Args.describe(a.get(1)) + " is not a real number");
        }
        return Args.out("power", r);
    }

    /** {@code sqrt(x)}: the square root in decimal (16 significant digits; exact for exact squares: {@code sqrt(0.0144)} is 0.12). */
    private static Object sqrt(Object v) {
        BigDecimal x = Args.decimal("sqrt", v);
        if (x == null) {
            return null;
        }
        if (x.signum() < 0) {
            throw Args.error("sqrt", Args.describe(v) + " is negative");
        }
        return Args.out(x.sqrt(MathContext.DECIMAL64));
    }

    private static Object real(String fn, Object v, java.util.function.DoubleUnaryOperator f, boolean positive) {
        BigDecimal x = Args.decimal(fn, v);
        if (x == null) {
            return null;
        }
        if (positive && x.signum() <= 0) {
            throw Args.error(fn, Args.describe(v) + " is not above zero");
        }
        return Args.out(fn, f.applyAsDouble(x.doubleValue()));
    }

    /** {@code clamp(x, lo, hi)}: x held between lo and hi ({@code MAX(lo, MIN(hi, x))}); a problem when lo is above hi. */
    private static Object clamp(List<Object> a) {
        BigDecimal x = Args.decimal("clamp", a.get(0));
        BigDecimal lo = Args.decimal("clamp", a.get(1));
        BigDecimal hi = Args.decimal("clamp", a.get(2));
        if (x == null || lo == null || hi == null) {
            return null;
        }
        if (lo.compareTo(hi) > 0) {
            throw Args.error("clamp", "the low bound " + Args.describe(a.get(1)) + " is above the high bound " + Args.describe(a.get(2)));
        }
        return Args.out(x.max(lo).min(hi));
    }

    /**
     * {@code least(a, b, ...)}, {@code greatest(a, b, ...)}: the smallest or largest argument. Numbers compare as numbers; when
     * no argument is a number they compare as text, so ISO dates order ({@code greatest($.MATURITY_DATE, today())}). Empty
     * arguments are skipped (empty when all are); a mix of numbers and other text, a list or a true/false is a problem.
     */
    private static Object pick(String fn, List<Object> a, boolean least) {
        boolean numbers = false;
        for (Object v : a) {
            Object s = Values.simplify(v);
            if (s instanceof Number || s instanceof String str && !Double.isNaN(Values.number(str))) {
                numbers = true;
                break;
            }
        }
        BigDecimal bestNumber = null;
        String bestText = null;
        for (Object v : a) {
            if (Args.empty(v)) {
                continue;
            }
            Object s = Values.simplify(v);
            if (numbers) {
                BigDecimal d = Args.decimal(fn, s);
                if (bestNumber == null || (least ? d.compareTo(bestNumber) < 0 : d.compareTo(bestNumber) > 0)) {
                    bestNumber = d;
                }
            } else if (s instanceof String str) {
                if (bestText == null || (least ? str.compareTo(bestText) < 0 : str.compareTo(bestText) > 0)) {
                    bestText = str;
                }
            } else {
                throw Args.typeError(fn, s, "a number, a date or text");
            }
        }
        return numbers ? Args.out(bestNumber) : bestText;
    }

    /**
     * {@code min}/{@code max}: over one list's elements, the pivot engine's aggregate (through its {@link FunctionProvider}; refused
     * with the reason where the engine is not on the class path); over several arguments, the old spelling of
     * {@code least}/{@code greatest}: numbers only, anything else skipped; empty if none; masked when an argument is.
     */
    private static Object lenient(List<Object> a, boolean min, EvalContext c) {
        if (a.size() == 1 && a.get(0) instanceof DataNode.Arr) {
            return Aggregates.call(min ? "min" : "max", a, c);
        }
        Double best = null;
        for (Object o : a) {
            if (Values.masked(o)) {
                return Values.mask();
            }
            double v = Values.number(o);
            if (!Double.isNaN(v) && (best == null || (min ? v < best : v > best))) {
                best = v;
            }
        }
        return best == null ? null : Values.normalise(best);
    }
}
