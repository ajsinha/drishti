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
 * Information and choice (RACHANA_REFERENCE.md section F1): {@code is_number}, {@code is_text}, {@code is_date}, {@code is_boolean},
 * {@code is_blank}, {@code is_error}, {@code type_of} and {@code choose}. The tests answer true or false, never empty: {@code is_blank}
 * is the one that is true for an empty value; a masked value gives a masked answer (whether a hidden field is filled in is itself
 * hidden). {@code is_number} is true for a number as the data holds it, not for number-like text (Excel's ISNUMBER; arithmetic still
 * reads {@code '12.5'} as a number); {@code is_text} is true for any text, an ISO date's included, and {@code is_date} for text that is
 * an ISO date or timestamp (dates are text in Rachana).
 */
final class InfoFunctions {

    private InfoFunctions() {}

    static void register(Functions.Table t) {
        t.fn("is_number", 1, 1, (a, c) -> {
            Object v = Values.simplify(a.get(0));
            return v instanceof Number n && !(n instanceof Double d && d.isNaN());
        });
        t.fn("is_text", 1, 1, (a, c) -> Values.simplify(a.get(0)) instanceof String);
        t.fn("is_date", 1, 1, (a, c) -> Values.simplify(a.get(0)) instanceof String s && Args.parseDate(s) != null);
        t.fn("is_boolean", 1, 1, (a, c) -> Values.simplify(a.get(0)) instanceof Boolean);
        t.fn("is_blank", 1, 1, (a, c) -> {
            Object v = Values.simplify(a.get(0));
            return Args.empty(v) || v instanceof String s && s.isBlank();
        });
        t.form("is_error", 1, 1, InfoFunctions::isError);
        t.fn("type_of", 1, 1, (a, c) -> typeOf(a.get(0)));
        t.form("choose", 2, 64, InfoFunctions::choose);
    }

    /**
     * {@code is_error(x)}: true when x raises a problem (a value of the wrong type, a bad date, an argument out of a function's domain,
     * a refused pattern), as Excel's ISERROR. A division by zero is empty in Rachana, not an error: {@code is_blank} tests it.
     */
    private static Object isError(List<Expr> args, EvalContext c) {
        Object v;
        try {
            v = args.get(0).eval(c);
        } catch (DrishtiException e) {
            return true;
        }
        if (Values.masked(v)) {
            return Values.mask();
        }
        return false;
    }

    /** {@code type_of(x)}: 'number', 'text', 'boolean', 'list', 'object', 'link' or 'empty' (Excel's TYPE, in words). */
    private static Object typeOf(Object value) {
        Object v = Values.simplify(value);
        if (Args.empty(v)) {
            return "empty";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof String) {
            return "text";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Link) {
            return "link";
        }
        if (v instanceof DataNode n) {
            return n.type() == NodeType.ARRAY ? "list" : "object";
        }
        return "text";
    }

    /** {@code choose(i, a1, a2, ...)}: the i-th of the values (1 the first), Excel's CHOOSE; only that one is evaluated. */
    private static Object choose(List<Expr> args, EvalContext c) {
        Object i = args.get(0).eval(c);
        if (Values.masked(i)) {
            return Values.mask();
        }
        Integer n = Args.whole("choose", i);
        if (n == null) {
            return null;
        }
        if (n < 1 || n > args.size() - 1) {
            throw Args.error("choose", "the index " + n + " is not from 1 to " + (args.size() - 1));
        }
        return args.get(n).eval(c);
    }
}
