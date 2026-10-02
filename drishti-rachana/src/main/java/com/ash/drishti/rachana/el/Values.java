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

/**
 * Coercions between evaluation results. A result is a {@link DataNode}, a {@code String}, a
 * {@code Number}, a {@code Boolean}, a {@link Link} or null. Missing and null behave the same.
 */
public final class Values {

    private Values() {}

    /** Unwraps scalar nodes to Java values; containers stay nodes; missing becomes null. */
    public static Object simplify(Object v) {
        if (v instanceof DataNode n) {
            return switch (n.type()) {
                case MISSING, NULL -> null;
                case STRING, NUMBER, BOOLEAN -> ((DataNode.Val) n).value();
                default -> n;
            };
        }
        return v;
    }

    /**
     * True for the masked value ({@link DataNode#MASK}): a field the caller's role may not see, or anything computed from
     * one. Expressions carry the mask through (arithmetic, comparisons, functions), so a value derived from a masked field
     * is masked too, and a condition on one is never true.
     */
    public static boolean masked(Object v) {
        return v instanceof DataNode n ? n.isMasked() : DataNode.MASK.equals(v);
    }

    /** The masked value, as an expression result. */
    public static DataNode mask() {
        return DataNode.masked();
    }

    public static boolean isNull(Object v) {
        return simplify(v) == null;
    }

    public static double number(Object v) {
        Object s = simplify(v);
        if (s instanceof Number n) {
            return n.doubleValue();
        }
        if (s instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        if (s instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return Double.NaN;
    }

    public static boolean isNumber(Object v) {
        return simplify(v) instanceof Number;
    }

    public static String text(Object v) {
        Object s = simplify(v);
        if (s == null) {
            return "";
        }
        if (s instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        if (s instanceof Link l) {
            return l.display();
        }
        if (s instanceof DataNode n) {
            return n.type() == NodeType.ARRAY ? "[" + n.size() + "]" : "{" + n.size() + "}";
        }
        return s.toString();
    }

    public static boolean truthy(Object v) {
        Object s = simplify(v);
        if (s == null || masked(s)) {
            return false;
        }
        if (s instanceof Boolean b) {
            return b;
        }
        if (s instanceof Number n) {
            return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        }
        if (s instanceof String str) {
            return !str.isEmpty();
        }
        if (s instanceof DataNode n) {
            return n.size() > 0;
        }
        return true;
    }

    /** Numeric results that are whole become {@code Long}, so {@code 1 + 1} prints {@code 2}. */
    public static Number normalise(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 && !Double.isInfinite(d) ? (Number) (long) d : (Number) d;
    }

    public static boolean equal(Object a, Object b) {
        Object x = simplify(a);
        Object y = simplify(b);
        if (x == null || y == null) {
            return x == y;
        }
        if (x instanceof Number || y instanceof Number) {
            return number(x) == number(y);
        }
        return text(x).equals(text(y));
    }
}
