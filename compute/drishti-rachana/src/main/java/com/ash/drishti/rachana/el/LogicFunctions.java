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
import com.ash.drishti.common.DrishtiException;
import java.util.List;

/**
 * Logic and references (RACHANA_REFERENCE.md section F1, and section 2's tests on a range or a list): {@code if},
 * {@code ifs}, {@code switch}, {@code iferror}, {@code let}, {@code coalesce}, {@code nonblank}, {@code within},
 * {@code one_of}, {@code link}. {@code if}, {@code ifs} and {@code let} are built by the parser ({@code if} and
 * {@code ifs} as the conditional {@code ?:}, so only the branch taken is evaluated; {@code let} binds names).
 */
final class LogicFunctions {

    private LogicFunctions() {}

    static void register(Functions.Table t) {
        t.syntax("if", 2, 3).syntax("ifs", 2, 64).syntax("let", 2, 64);
        t.form("switch", 3, 64, LogicFunctions::switchOf);
        t.form("iferror", 2, 2, LogicFunctions::iferror);
        t.fn("coalesce", 1, 8, (a, c) -> a.stream().filter(v -> !Values.isNull(v)).findFirst().orElse(null)).passesMask("coalesce");
        t.fn("nonblank", 1, 8, (a, c) -> a.stream().filter(v -> !Values.isNull(v) && !Values.text(Values.simplify(v)).isBlank())
                .findFirst().orElse(null)).passesMask("nonblank");
        t.fn("within", 2, 2, (a, c) -> within(a.get(0), Values.text(a.get(1))));
        t.fn("one_of", 2, 2, (a, c) -> oneOf(a.get(0), a.get(1)));
        t.old("oneOf", "one_of", 2, 2);                                  // read for compatibility: existing Sutras write oneOf
        t.fn("link", 1, 3, LogicFunctions::link);
    }

    /**
     * {@code switch(x, v1, a1, v2, a2, ..., [else])}: the answer {@code a} of the first value {@code v} equal to {@code x}
     * (equality as {@code ==}: numbers numerically, otherwise as text; an empty {@code x} equals a {@code null} case), else
     * the last argument when their count is even, else empty. Only the answer chosen is evaluated; a masked {@code x}, or a
     * masked case reached before the match, makes the result masked.
     */
    private static Object switchOf(List<Expr> args, EvalContext c) {
        Object x = args.get(0).eval(c);
        if (Values.masked(x)) {
            return Values.mask();
        }
        int pairs = (args.size() - 1) / 2;
        for (int i = 0; i < pairs; i++) {
            Object v = args.get(1 + 2 * i).eval(c);
            if (Values.masked(v)) {
                return Values.mask();
            }
            if (Values.equal(x, v)) {
                return args.get(2 + 2 * i).eval(c);
            }
        }
        return (args.size() - 1) % 2 == 1 ? args.get(args.size() - 1).eval(c) : null;
    }

    /**
     * {@code iferror(x, y)}: {@code y} when {@code x} fails (raises a problem, such as a value of the wrong type or a bad
     * pattern), divides by zero (empty), is not a number (NaN) or is empty; otherwise {@code x}. A masked {@code x} stays
     * masked. {@code y} is evaluated only when needed.
     */
    private static Object iferror(List<Expr> args, EvalContext c) {
        Object x;
        try {
            x = args.get(0).eval(c);
        } catch (DrishtiException e) {
            return args.get(1).eval(c);
        }
        if (Values.masked(x)) {
            return x;
        }
        return Args.empty(x) ? args.get(1).eval(c) : x;
    }

    /**
     * {@code within(value, range)}: the value lies in a {@code from..to} range (a date-range or number-range control; either end
     * may be empty, and an empty range holds everything). Numbers compare as numbers, anything else as text, so ISO dates order.
     */
    private static boolean within(Object value, String range) {
        int cut = range.indexOf("..");
        String from = cut < 0 ? range.trim() : range.substring(0, cut).trim();
        String to = cut < 0 ? "" : range.substring(cut + 2).trim();
        if (from.isEmpty() && to.isEmpty()) {
            return true;
        }
        Object v = Values.simplify(value);
        if (v == null) {
            return false;
        }
        double x = Values.number(v);
        boolean numeric = !Double.isNaN(x) && (from.isEmpty() || !Double.isNaN(Values.number(from)))
                && (to.isEmpty() || !Double.isNaN(Values.number(to)));
        if (numeric) {
            return (from.isEmpty() || x >= Values.number(from)) && (to.isEmpty() || x <= Values.number(to));
        }
        String t = Values.text(v);
        String day = t.length() > 10 && t.charAt(10) == 'T' ? t.substring(0, 10) : t;     // a timestamp is compared by its date
        return (from.isEmpty() || day.compareTo(from) >= 0) && (to.isEmpty() || day.compareTo(to) <= 0);
    }

    /** {@code one_of(value, list)}: the value is one of a multi-select control's choices; nothing chosen holds everything. */
    private static boolean oneOf(Object value, Object choices) {
        Object list = Values.simplify(choices);
        if (list instanceof DataNode n && n.type() == NodeType.ARRAY) {
            if (n.size() == 0) {
                return true;
            }
            for (int i = 0; i < n.size(); i++) {
                if (Values.equal(value, n.get(i))) {
                    return true;
                }
            }
            return false;
        }
        return list == null || Values.text(list).isEmpty() || Values.equal(value, list);
    }

    private static Object link(List<Object> a, EvalContext c) {
        String id = Values.text(a.get(0));
        if (id.isEmpty()) {
            return null;
        }
        String kind = a.size() > 1 && !Values.isNull(a.get(1)) ? Values.text(a.get(1)) : null;
        String label = a.size() > 2 && !Values.isNull(a.get(2)) ? Values.text(a.get(2)) : null;
        return new Link(id, kind, label);
    }
}
