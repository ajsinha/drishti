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
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * More math (FUNCTION_CATALOGUE.md section 1), beside {@link MathFunctions}: {@code round} (half away from zero, Excel's ROUND),
 * {@code round_sig} (to significant digits), {@code log(x, [base])}, {@code fact}, {@code combin}, {@code permut}, {@code gcd},
 * {@code lcm}, {@code even}, {@code odd}, {@code pi}, the trigonometric functions ({@code sin}, {@code cos}, {@code tan}, {@code asin},
 * {@code acos}, {@code atan}, {@code atan2}, {@code sinh}, {@code cosh}, {@code tanh}, {@code radians}, {@code degrees}), and
 * {@code base}/{@code decimal} (a whole number written in another radix and read back: Excel's BASE, DECIMAL, DEC2HEX, HEX2DEC …).
 * Rounding and whole-number work stay in decimal (money never passes through binary floating point); the logarithm and the trigonometric
 * functions are real-number functions and give doubles.
 */
final class NumberFunctions {

    private NumberFunctions() {}

    /** The largest n {@code fact}, {@code combin} and {@code permut} take: their results stay exact whole numbers (and bounded work). */
    static final int MAX_FACT = 1_000;

    static void register(Functions.Table t) {
        t.fn("round", 1, 2, (a, c) -> round(a));
        t.fn("round_sig", 2, 2, (a, c) -> roundSig(a));
        t.fn("log", 1, 2, (a, c) -> log(a));
        t.fn("fact", 1, 1, (a, c) -> {
            Integer n = small("fact", a.get(0));
            return n == null ? null : Args.out(factorial(1, n));
        });
        t.fn("combin", 2, 2, (a, c) -> combin("combin", a, false));
        t.fn("permut", 2, 2, (a, c) -> combin("permut", a, true));
        t.fn("gcd", 1, 64, (a, c) -> gcdLcm("gcd", a, true));
        t.fn("lcm", 1, 64, (a, c) -> gcdLcm("lcm", a, false));
        t.fn("even", 1, 1, (a, c) -> evenOdd("even", a.get(0), true));
        t.fn("odd", 1, 1, (a, c) -> evenOdd("odd", a.get(0), false));
        t.fn("pi", 0, 0, (a, c) -> Math.PI);
        trig(t, "sin", Math::sin, false);
        trig(t, "cos", Math::cos, false);
        trig(t, "tan", Math::tan, false);
        trig(t, "asin", Math::asin, true);
        trig(t, "acos", Math::acos, true);
        trig(t, "atan", Math::atan, false);
        trig(t, "sinh", Math::sinh, false);
        trig(t, "cosh", Math::cosh, false);
        trig(t, "tanh", Math::tanh, false);
        trig(t, "radians", Math::toRadians, false);
        trig(t, "degrees", Math::toDegrees, false);
        t.fn("atan2", 2, 2, (a, c) -> {
            Double x = Distributions.real("atan2", a.get(0));
            Double y = Distributions.real("atan2", a.get(1));
            if (x == null || y == null) {
                return null;
            }
            if (x == 0 && y == 0) {
                throw Args.error("atan2", "the point (0, 0) has no angle");
            }
            return Args.out("atan2", Math.atan2(y, x));                    // Excel's order: x first
        });
        t.fn("base", 2, 3, (a, c) -> base(a));
        t.fn("decimal", 2, 2, (a, c) -> decimal(a));
    }

    /** {@code round(x, [n])}: half away from zero to n places (negative: tens, hundreds …), Excel's ROUND, in decimal. */
    private static Object round(List<Object> a) {
        BigDecimal x = Args.decimal("round", a.get(0));
        Integer n = a.size() > 1 ? Args.whole("round", a.get(1)) : Integer.valueOf(0);
        if (x == null || n == null) {
            return null;
        }
        if (Math.abs(n) > MathFunctions.MAX_PLACES) {
            throw Args.error("round", "places " + n + " is beyond ±" + MathFunctions.MAX_PLACES);
        }
        return Args.out(x.setScale(n, RoundingMode.HALF_UP));
    }

    /** {@code round_sig(x, digits)}: half away from zero to that many significant digits ({@code round_sig(123456, 2)} is 120000). */
    private static Object roundSig(List<Object> a) {
        BigDecimal x = Args.decimal("round_sig", a.get(0));
        Integer d = Args.whole("round_sig", a.get(1));
        if (x == null || d == null) {
            return null;
        }
        if (d < 1 || d > MathFunctions.MAX_PLACES) {
            throw Args.error("round_sig", "significant digits " + d + " are not from 1 to " + MathFunctions.MAX_PLACES);
        }
        return x.signum() == 0 ? (Object) 0L : Args.out(x.round(new MathContext(d, RoundingMode.HALF_UP)));
    }

    /** {@code log(x, [base])}: the logarithm in a base (10 by default, Excel's LOG). */
    private static Object log(List<Object> a) {
        BigDecimal x = Args.decimal("log", a.get(0));
        BigDecimal b = a.size() > 1 ? Args.decimal("log", a.get(1)) : BigDecimal.TEN;
        if (x == null || b == null) {
            return null;
        }
        if (x.signum() <= 0) {
            throw Args.error("log", Args.describe(a.get(0)) + " is not above zero");
        }
        if (b.signum() <= 0 || b.compareTo(BigDecimal.ONE) == 0) {
            throw Args.error("log", "the base " + Args.describe(a.size() > 1 ? a.get(1) : 10) + " is not above zero and other than 1");
        }
        double r = b.compareTo(BigDecimal.TEN) == 0 ? Math.log10(x.doubleValue()) : Math.log(x.doubleValue()) / Math.log(b.doubleValue());
        return Args.out("log", r);
    }

    /** A whole number from 0 to {@value #MAX_FACT} (a fraction cut, as Excel's FACT cuts it). */
    private static Integer small(String fn, Object v) {
        Integer n = Args.whole(fn, v);
        if (n != null && (n < 0 || n > MAX_FACT)) {
            throw Args.error(fn, n + " is not from 0 to " + MAX_FACT);
        }
        return n;
    }

    /** from · (from + 1) · … · to, exact. */
    private static BigDecimal factorial(int from, int to) {
        BigInteger f = BigInteger.ONE;
        for (int i = Math.max(from, 2); i <= to; i++) {
            f = f.multiply(BigInteger.valueOf(i));
        }
        return new BigDecimal(f);
    }

    /** {@code combin(n, k)}: n choose k; {@code permut(n, k)}: n!/(n − k)!, exact whole numbers (Excel's COMBIN, PERMUT). */
    private static Object combin(String fn, List<Object> a, boolean ordered) {
        Integer n = small(fn, a.get(0));
        Integer k = small(fn, a.get(1));
        if (n == null || k == null) {
            return null;
        }
        if (k > n) {
            throw Args.error(fn, "k " + k + " is more than n " + n);
        }
        BigDecimal p = factorial(n - k + 1, n);
        return Args.out(ordered ? p : p.divide(factorial(1, k)));
    }

    /** {@code gcd(a, b, ...)}, {@code lcm(a, b, ...)}: of whole numbers ≥ 0 (fractions cut, Excel's); empty arguments skipped. */
    private static Object gcdLcm(String fn, List<Object> a, boolean gcd) {
        BigInteger acc = null;
        for (Object v : a) {
            BigDecimal d = Args.decimal(fn, v);
            if (d == null) {
                continue;
            }
            if (d.signum() < 0) {
                throw Args.error(fn, Args.describe(v) + " is negative");
            }
            BigInteger x = d.setScale(0, RoundingMode.DOWN).toBigIntegerExact();
            if (acc == null) {
                acc = x;
            } else if (gcd) {
                acc = acc.gcd(x);
            } else {
                acc = acc.signum() == 0 || x.signum() == 0 ? BigInteger.ZERO : acc.multiply(x).divide(acc.gcd(x));
            }
        }
        return acc == null ? null : Args.out(new BigDecimal(acc));
    }

    /** {@code even(x)}, {@code odd(x)}: up, away from zero, to the next even or odd whole number (Excel's EVEN, ODD). */
    private static Object evenOdd(String fn, Object v, boolean even) {
        BigDecimal x = Args.decimal(fn, v);
        if (x == null) {
            return null;
        }
        BigDecimal two = BigDecimal.valueOf(2);
        BigDecimal m = x.abs();
        BigDecimal r = even ? m.divide(two, 0, RoundingMode.CEILING).multiply(two)
                : m.subtract(BigDecimal.ONE).divide(two, 0, RoundingMode.CEILING).multiply(two).add(BigDecimal.ONE);
        if (!even && r.signum() <= 0) {
            r = BigDecimal.ONE;                                         // odd(0) is 1
        }
        return Args.out(x.signum() < 0 ? r.negate() : r);
    }

    private static void trig(Functions.Table t, String name, DoubleUnaryOperator f, boolean unitRange) {
        t.fn(name, 1, 1, (a, c) -> {
            Double x = Distributions.real(name, a.get(0));
            if (x == null) {
                return null;
            }
            if (unitRange && Math.abs(x) > 1) {
                throw Args.error(name, Args.describe(a.get(0)) + " is not from −1 to 1");
            }
            return Args.out(name, f.applyAsDouble(x));
        });
    }

    /** {@code base(n, radix, [min_length])}: a whole number ≥ 0 written in radix 2 to 36, upper case, padded with zeros (Excel's BASE). */
    private static Object base(List<Object> a) {
        BigDecimal n = Args.decimal("base", a.get(0));
        Integer r = Args.whole("base", a.get(1));
        Integer len = a.size() > 2 ? Args.whole("base", a.get(2)) : Integer.valueOf(0);
        if (n == null || r == null || len == null) {
            return null;
        }
        radix("base", r);
        if (n.signum() < 0) {
            throw Args.error("base", Args.describe(a.get(0)) + " is negative");
        }
        if (len < 0 || len > 255) {
            throw Args.error("base", "the length " + len + " is not from 0 to 255");
        }
        String s = n.setScale(0, RoundingMode.DOWN).toBigIntegerExact().toString(r).toUpperCase(Locale.ROOT);
        return s.length() >= len ? s : "0".repeat(len - s.length()) + s;
    }

    /** {@code decimal(text, radix)}: text in radix 2 to 36 read as a whole number (Excel's DECIMAL); a digit outside the radix is a problem. */
    private static Object decimal(List<Object> a) {
        String s = Args.text("decimal", a.get(0));
        Integer r = Args.whole("decimal", a.get(1));
        if (s == null || r == null) {
            return null;
        }
        radix("decimal", r);
        String t = s.strip();
        if (t.isEmpty() || t.length() > 255) {
            throw Args.error("decimal", "'" + s + "' is not a number of 1 to 255 digits");
        }
        try {
            return Args.out(new BigDecimal(new BigInteger(t, r)));
        } catch (NumberFormatException e) {
            throw Args.error("decimal", "'" + s + "' is not a number in radix " + r);
        }
    }

    private static void radix(String fn, int r) {
        if (r < 2 || r > 36) {
            throw Args.error(fn, "the radix " + r + " is not from 2 to 36");
        }
    }
}
