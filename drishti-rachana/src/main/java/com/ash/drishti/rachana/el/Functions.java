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
            Map.entry("max", new int[] {1, 8}), Map.entry("upper", new int[] {1, 1}), Map.entry("lower", new int[] {1, 1}));

    private static final Map<String, ElFunction> FUNCTIONS = Map.ofEntries(
            Map.entry("link", Functions::link), Map.entry("size", (a, c) -> size(a.get(0))),
            Map.entry("sum", Functions::sum), Map.entry("fmt", (a, c) -> c.formats().format(Values.text(a.get(1)), a.get(0))),
            Map.entry("coalesce", (a, c) -> a.stream().filter(v -> !Values.isNull(v)).findFirst().orElse(null)),
            Map.entry("first", (a, c) -> a.get(0) instanceof DataNode n ? n.get(0) : null),
            Map.entry("last", (a, c) -> a.get(0) instanceof DataNode n ? n.get(-1) : null),
            Map.entry("abs", (a, c) -> Values.normalise(Math.abs(Values.number(a.get(0))))),
            Map.entry("min", (a, c) -> extreme(a, true)), Map.entry("max", (a, c) -> extreme(a, false)),
            Map.entry("upper", (a, c) -> Values.text(a.get(0)).toUpperCase(Locale.ROOT)),
            Map.entry("lower", (a, c) -> Values.text(a.get(0)).toLowerCase(Locale.ROOT)));

    private Functions() {}

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
            double v = field == null ? e.asDouble() : e.get(field).asDouble();
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
            double v = Values.number(o);
            if (!Double.isNaN(v) && (best == null || (min ? v < best : v > best))) {
                best = v;
            }
        }
        return best == null ? null : Values.normalise(best);
    }
}
