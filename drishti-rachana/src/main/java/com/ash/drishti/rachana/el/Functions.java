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
import java.util.Locale;
import java.util.Map;

/**
 * The closed set of Rachana-EL functions. Adding one is an ADR-003 amendment: functions must stay pure,
 * total (never throw on odd input) and cheap.
 */
public final class Functions {

    /** Name to {min args, max args}. */
    static final Map<String, int[]> ARITY = Map.ofEntries(
            Map.entry("link", new int[] {1, 3}), Map.entry("size", new int[] {1, 1}), Map.entry("sum", new int[] {1, 2}),
            Map.entry("fmt", new int[] {2, 2}), Map.entry("coalesce", new int[] {1, 8}), Map.entry("first", new int[] {1, 1}),
            Map.entry("last", new int[] {1, 1}), Map.entry("abs", new int[] {1, 1}), Map.entry("min", new int[] {1, 8}),
            Map.entry("max", new int[] {1, 8}), Map.entry("upper", new int[] {1, 1}), Map.entry("lower", new int[] {1, 1}),
            Map.entry("contains", new int[] {2, 2}), Map.entry("startsWith", new int[] {2, 2}),
            Map.entry("matches", new int[] {2, 2}), Map.entry("matchesCase", new int[] {2, 2}),
            Map.entry("within", new int[] {2, 2}), Map.entry("oneOf", new int[] {2, 2}),
            Map.entry("flatten", new int[] {1, 1}), Map.entry("nonblank", new int[] {1, 8}));

    private static final Map<String, ElFunction> FUNCTIONS = Map.ofEntries(
            Map.entry("link", Functions::link), Map.entry("size", (a, c) -> size(a.get(0))),
            Map.entry("sum", Functions::sum), Map.entry("fmt", (a, c) -> c.formats().format(Values.text(a.get(1)), a.get(0))),
            Map.entry("coalesce", (a, c) -> a.stream().filter(v -> !Values.isNull(v)).findFirst().orElse(null)),
            Map.entry("first", (a, c) -> a.get(0) instanceof DataNode n ? n.get(0) : null),
            Map.entry("last", (a, c) -> a.get(0) instanceof DataNode n ? n.get(-1) : null),
            Map.entry("abs", (a, c) -> Values.normalise(Math.abs(Values.number(a.get(0))))),
            Map.entry("min", (a, c) -> extreme(a, true)), Map.entry("max", (a, c) -> extreme(a, false)),
            Map.entry("upper", (a, c) -> Values.text(a.get(0)).toUpperCase(Locale.ROOT)),
            Map.entry("lower", (a, c) -> Values.text(a.get(0)).toLowerCase(Locale.ROOT)),
            Map.entry("contains", (a, c) -> contains(a.get(0), Values.text(a.get(1)))),
            Map.entry("startsWith", (a, c) -> Values.text(a.get(0)).toLowerCase(Locale.ROOT).startsWith(Values.text(a.get(1)).toLowerCase(Locale.ROOT))),
            Map.entry("matches", (a, c) -> matches(a.get(0), Values.text(a.get(1)), true)),
            Map.entry("matchesCase", (a, c) -> matches(a.get(0), Values.text(a.get(1)), false)),
            Map.entry("within", (a, c) -> within(a.get(0), Values.text(a.get(1)))),
            Map.entry("oneOf", (a, c) -> oneOf(a.get(0), a.get(1))),
            Map.entry("flatten", (a, c) -> flatten(a.get(0))),
            Map.entry("nonblank", (a, c) -> a.stream().filter(v -> !Values.isNull(v) && !Values.text(Values.simplify(v)).isBlank()).findFirst().orElse(null)));

    /**
     * {@code flatten(list)}: a list of lists made one list ({@code flatten($.legs[*].cashflows)} is every cashflow of every leg, in
     * order); elements that are not lists are kept as they are; anything but a list is empty. One level only, so it stays cheap.
     */
    private static Object flatten(Object v) {
        if (!(Values.simplify(v) instanceof DataNode.Arr arr)) {
            return null;
        }
        java.util.List<DataNode> out = new java.util.ArrayList<>();
        for (DataNode e : arr.elements()) {
            if (e instanceof DataNode.Arr inner) {
                out.addAll(inner.elements());
            } else if (!e.isNull()) {
                out.add(e);
            }
        }
        return new DataNode.Arr(java.util.List.copyOf(out));
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, com.ash.drishti.common.SafeRegex> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Regular-expression search over text, by the linear-time engine ({@link com.ash.drishti.common.SafeRegex}): a pattern it
     * refuses raises DRS-4010, one that runs past its time budget DRS-4011. Matches anywhere in the value; null never matches.
     */
    private static boolean matches(Object value, String pattern, boolean ignoreCase) {
        if (PATTERNS.size() > 512) {
            PATTERNS.clear();
        }
        com.ash.drishti.common.SafeRegex re = PATTERNS.computeIfAbsent((ignoreCase ? "i:" : "c:") + pattern,
                k -> com.ash.drishti.common.SafeRegex.compile(pattern, ignoreCase));
        return !Values.isNull(value) && re.find(Values.text(value));
    }

    private Functions() {}

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

    /** {@code oneOf(value, list)}: the value is one of a multi-select control's choices; nothing chosen holds everything. */
    private static boolean oneOf(Object value, Object choices) {
        Object list = Values.simplify(choices);
        if (list instanceof DataNode n && n.type() == com.ash.drishti.api.NodeType.ARRAY) {
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

    /** Case-insensitive: text contains the part, or a list has an element that does. */
    private static boolean contains(Object where, String part) {
        String p = part.toLowerCase(Locale.ROOT);
        if (where instanceof DataNode n && n.type() == com.ash.drishti.api.NodeType.ARRAY) {
            for (int i = 0; i < n.size(); i++) {
                if (contains(n.get(i), part)) {
                    return true;
                }
            }
            return false;
        }
        return !Values.isNull(where) && Values.text(where).toLowerCase(Locale.ROOT).contains(p);
    }

    /** Every function of Rachana-EL with its argument count: {name: [min, max]}. */
    public static java.util.Map<String, int[]> arity() {
        return ARITY;
    }

    static ElFunction get(String name) {
        return FUNCTIONS.get(name);
    }

    private static Object link(java.util.List<Object> a, EvalContext c) {
        String id = Values.text(a.get(0));
        if (id.isEmpty()) {
            return null;
        }
        String kind = a.size() > 1 && !Values.isNull(a.get(1)) ? Values.text(a.get(1)) : null;
        String label = a.size() > 2 && !Values.isNull(a.get(2)) ? Values.text(a.get(2)) : null;
        return new Link(id, kind, label);
    }

    private static Object size(Object v) {
        Object s = Values.simplify(v);
        if (s instanceof DataNode n) {
            return (long) n.size();
        }
        return s instanceof String str ? (long) str.length() : 0L;
    }

    private static Object sum(java.util.List<Object> a, EvalContext c) {
        if (!(a.get(0) instanceof DataNode.Arr arr)) {
            return 0L;
        }
        String field = a.size() > 1 ? Values.text(a.get(1)) : null;
        double total = 0;
        for (DataNode e : arr.elements()) {
            DataNode x = field == null ? e : e.get(field);
            if (x.isMasked()) {
                return Values.mask();                 // a masked value is never added up: the sum is masked
            }
            double v = x.asDouble();
            if (!Double.isNaN(v)) {
                total += v;
            }
        }
        return Values.normalise(total);
    }

    private static Object extreme(java.util.List<Object> a, boolean min) {
        Iterable<?> items = a.size() == 1 && a.get(0) instanceof DataNode.Arr arr ? arr.elements() : a;
        Double best = null;
        for (Object o : items) {
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
