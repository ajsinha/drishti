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
import com.ash.drishti.api.NodeType;
import com.ash.drishti.common.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * How the function families read their arguments and write their results: numbers as decimals (money never passes
 * through binary floating point in a function), whole numbers, text, ISO dates and lists, each null for an empty value
 * and a {@code DRS-2102} {@link ElException} naming the function and the value for one of the wrong type.
 */
final class Args {

    private Args() {}

    /** The problem a function raises for a value of the wrong type: {@code round_up: 'abc' is not a number}. */
    static ElException typeError(String fn, Object value, String wanted) {
        return new ElException(ErrorCode.EL_EVAL, fn + ": " + describe(value) + " is not " + wanted);
    }

    /** A problem with an argument's value: {@code sqrt: -4 is negative}. */
    static ElException error(String fn, String why) {
        return new ElException(ErrorCode.EL_EVAL, fn + ": " + why);
    }

    /** A value as a problem names it: text in quotes, a list or object by its kind. */
    static String describe(Object value) {
        Object v = Values.simplify(value);
        if (v instanceof DataNode n) {
            return n.type() == NodeType.ARRAY ? "a list" : "an object";
        }
        if (v instanceof String s) {
            return "'" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "'";
        }
        if (v instanceof Link l) {
            return "a link (" + l.display() + ")";
        }
        return String.valueOf(Values.text(v));
    }

    /** True for an empty value: missing, null, or a number that is not a number (what arithmetic on a missing field gives). */
    static boolean empty(Object value) {
        Object v = Values.simplify(value);
        return v == null || v instanceof Double d && d.isNaN() || v instanceof Float f && f.isNaN();
    }

    /** The significant digits a binary floating-point number is read to (Excel's 15). */
    static final java.math.MathContext DOUBLE_DIGITS = new java.math.MathContext(15, java.math.RoundingMode.HALF_EVEN);

    /** The argument as a decimal; null when empty. A number, or text that reads as one ({@code '12.5'}); nothing else. */
    static BigDecimal decimal(String fn, Object value) {
        if (empty(value)) {
            return null;
        }
        Object v = Values.simplify(value);
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof java.math.BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isInfinite(d)) {
                throw typeError(fn, v, "a finite number");
            }
            // the shortest decimal that reads back as the double (2.675 stays 2.675), to 15 significant digits as Excel keeps
            // them, so the binary noise of arithmetic goes: 0.1 + 0.2 is 0.3, not 0.30000000000000004
            return BigDecimal.valueOf(d).round(DOUBLE_DIGITS);
        }
        if (v instanceof String s && Values.couldBeNumber(s)) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw typeError(fn, v, "a number");
            }
        }
        throw typeError(fn, v, "a number");
    }

    /** The argument as a whole number (a fraction is cut towards zero, as Excel does); null when empty. */
    static Integer whole(String fn, Object value) {
        BigDecimal d = decimal(fn, value);
        if (d == null) {
            return null;
        }
        BigDecimal w = d.setScale(0, java.math.RoundingMode.DOWN);
        if (w.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 || w.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0) {
            throw error(fn, describe(value) + " is too large a whole number");
        }
        return w.intValue();
    }

    /** A decimal result: a whole number as a {@code Long} (so it prints {@code 2}), otherwise the decimal without trailing zeros. */
    static Object out(BigDecimal d) {
        if (d == null) {
            return null;
        }
        BigDecimal s = d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
        if (s.scale() <= 0 && s.precision() - s.scale() <= 18) {
            return s.longValueExact();
        }
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    /** A result of a function over real numbers ({@code exp}, {@code ln}): whole numbers as {@code Long}. */
    static Object out(String fn, double d) {
        if (Double.isNaN(d)) {
            throw error(fn, "the result is not a real number");
        }
        if (Double.isInfinite(d)) {
            throw error(fn, "the result is too large");
        }
        return Values.normalise(d);
    }

    /** The argument as text; null when empty. A number reads as it prints ({@code 2}, {@code 0.5}); a list or object is refused. */
    static String text(String fn, Object value) {
        Object v = Values.simplify(value);
        if (v == null) {
            return null;
        }
        if (v instanceof DataNode) {
            throw typeError(fn, v, "text");
        }
        return Values.text(v);
    }

    /**
     * The argument as a date: ISO text {@code 2026-10-16}, or a timestamp read by its date ({@code 2026-10-16T09:30:00Z});
     * null when empty.
     */
    static LocalDate date(String fn, Object value) {
        Object v = Values.simplify(value);
        if (v == null) {
            return null;
        }
        LocalDate d = v instanceof String s ? parseDate(s) : null;
        if (d == null) {
            throw typeError(fn, v, "a date (yyyy-mm-dd)");
        }
        return d;
    }

    /** ISO text or a timestamp's date, or null when the text is not one. */
    static LocalDate parseDate(String text) {
        String t = text.trim();
        if (t.length() < 10 || t.length() > 10 && t.charAt(10) != 'T' && t.charAt(10) != ' ') {
            return null;
        }
        try {
            return LocalDate.parse(t.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A date result, as ISO text (what {@code today()} gives and what the data holds). */
    static Object out(LocalDate d) {
        return d == null ? null : d.toString();
    }

    /** The argument as a list; null when empty. */
    static DataNode.Arr list(String fn, Object value) {
        Object v = Values.simplify(value);
        if (v == null) {
            return null;
        }
        if (v instanceof DataNode.Arr a) {
            return a;
        }
        throw typeError(fn, v, "a list");
    }

    /** A list result: each value a node (a link as its text, a masked value masked). */
    static DataNode.Arr list(List<?> values) {
        List<DataNode> out = new java.util.ArrayList<>(values.size());
        for (Object v : values) {
            out.add(node(v));
        }
        return new DataNode.Arr(List.copyOf(out));
    }

    /** A value as a node, to be an element of a list. */
    static DataNode node(Object v) {
        if (v instanceof DataNode n) {
            return n;
        }
        if (Values.masked(v)) {
            return DataNode.masked();
        }
        return v instanceof Link l ? DataNode.of(l.display()) : DataNode.of(v);
    }
}
