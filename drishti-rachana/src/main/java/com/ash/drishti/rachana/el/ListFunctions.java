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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Lists, Excel's dynamic arrays over {@code [*]} (FUNCTION_CATALOGUE.md section 6): {@code size}, {@code first},
 * {@code last}, {@code flatten}, {@code sort}, {@code unique}, {@code take}, {@code drop}, {@code sequence}, {@code map},
 * {@code reduce}; and the list forms Rachana had before the pivot engine took aggregation ({@code sum}, {@code group}),
 * kept with their names and results until they move there (ONE_GRAMMAR.md part 2). {@code map}, {@code sort} and
 * {@code reduce} evaluate their expression once per element with {@code @} the element ({@code @acc} the running value
 * of {@code reduce}). A masked list, or a masked value a result depends on, makes the result masked.
 */
final class ListFunctions {

    private ListFunctions() {}

    /** The most groups {@code group(...)} returns: a chart or table of groups stays readable and the work stays bounded. */
    static final int MAX_GROUPS = 500;

    /** The longest list {@code sequence} makes: a bound on the work. */
    static final int MAX_SEQUENCE = 10_000;

    static void register(Functions.Table t) {
        t.fn("size", 1, 1, (a, c) -> size(a.get(0)));
        t.fn("first", 1, 1, (a, c) -> a.get(0) instanceof DataNode n ? n.get(0) : null);
        t.fn("last", 1, 1, (a, c) -> a.get(0) instanceof DataNode n ? n.get(-1) : null);
        t.fn("flatten", 1, 1, (a, c) -> flatten(a.get(0)));
        t.fn("sum", 1, 2, ListFunctions::sum);
        t.fn("group", 2, 4, ListFunctions::group);
        t.form("sort", 1, 3, ListFunctions::sort);
        t.fn("unique", 1, 1, (a, c) -> unique(a.get(0)));
        t.fn("take", 2, 2, (a, c) -> slice("take", a, true));
        t.fn("drop", 2, 2, (a, c) -> slice("drop", a, false));
        t.fn("sequence", 1, 3, (a, c) -> sequence(a)).check("sequence", args -> Functions.literal(args, 0) instanceof Number n
                && (n.longValue() < 0 || n.longValue() > MAX_SEQUENCE) ? "the length " + n + " is outside 0.." + MAX_SEQUENCE : null);
        t.form("map", 2, 2, ListFunctions::map);
        t.form("reduce", 3, 3, ListFunctions::reduce);
    }

    private static Object size(Object v) {
        Object s = Values.simplify(v);
        if (s instanceof DataNode n) {
            return (long) n.size();
        }
        return s instanceof String str ? (long) str.length() : 0L;
    }

    /**
     * {@code flatten(list)}: a list of lists made one list ({@code flatten($.legs[*].cashflows)} is every cashflow of every leg, in
     * order); elements that are not lists are kept as they are; anything but a list is empty. One level only, so it stays cheap.
     */
    private static Object flatten(Object v) {
        if (!(Values.simplify(v) instanceof DataNode.Arr arr)) {
            return null;
        }
        List<DataNode> out = new ArrayList<>();
        for (DataNode e : arr.elements()) {
            if (e instanceof DataNode.Arr inner) {
                out.addAll(inner.elements());
            } else if (!e.isNull()) {
                out.add(e);
            }
        }
        return new DataNode.Arr(List.copyOf(out));
    }

    private static Object sum(List<Object> a, EvalContext c) {
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

    /**
     * {@code group(list, 'key', 'value'?, 'by'?)}: the list's elements grouped by the field {@code key} (a dotted path), one row
     * {@code {label, value, count}} per distinct key in key order: {@code value} the sum of the field {@code value} over the group
     * (non-numbers, blanks and number-like text read as numbers; the count when no value field is given), {@code count} its
     * elements. {@code by} cuts an ISO date key to its {@code month} (2026-10) or {@code year} (2026) first. Elements without a key
     * are left out; empty for anything but a list. A masked value makes the group's value masked.
     */
    private static Object group(List<Object> a, EvalContext c) {
        if (!(Values.simplify(a.get(0)) instanceof DataNode.Arr arr)) {
            return null;
        }
        String key = Values.text(a.get(1));
        String value = a.size() > 2 && !Values.isNull(a.get(2)) && !Values.text(a.get(2)).isBlank() ? Values.text(a.get(2)) : null;
        String by = a.size() > 3 ? Values.text(a.get(3)) : "";
        TreeMap<String, double[]> groups = new TreeMap<>();
        Set<String> masked = new HashSet<>();
        for (DataNode e : arr.elements()) {
            String k = e.at(key).asText();
            if (k == null || k.isBlank()) {
                continue;
            }
            k = "month".equals(by) && k.length() >= 7 ? k.substring(0, 7) : "year".equals(by) && k.length() >= 4 ? k.substring(0, 4) : k;
            if (!groups.containsKey(k) && groups.size() >= MAX_GROUPS) {
                continue;
            }
            double[] g = groups.computeIfAbsent(k, x -> new double[2]);
            g[1]++;
            if (value == null) {
                g[0]++;
                continue;
            }
            DataNode v = e.at(value);
            if (v.isMasked()) {
                masked.add(k);
            }
            double d = v.asDouble();
            if (Double.isNaN(d) && v.type() == NodeType.STRING) {
                try {
                    d = Double.parseDouble(v.asText().trim());
                } catch (NumberFormatException ignored) {
                    d = Double.NaN;
                }
            }
            if (!Double.isNaN(d)) {
                g[0] += d;
            }
        }
        List<DataNode> out = new ArrayList<>(groups.size());
        groups.forEach((k, g) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", k);
            row.put("value", masked.contains(k) ? null : Values.normalise(g[0]));
            row.put("count", (long) g[1]);
            out.add(DataNode.of(row));
        });
        return new DataNode.Arr(List.copyOf(out));
    }

    /** The list argument of a form, evaluated: null when empty, the mask when masked. */
    private static Object listOf(String fn, Expr e, EvalContext c) {
        Object v = e.eval(c);
        if (Values.masked(v)) {
            return Values.mask();
        }
        return Args.list(fn, v);
    }

    /**
     * {@code sort(list, [key], [desc])}: the elements ordered by {@code key} (an expression over {@code @}, the element itself
     * by default), ascending unless {@code desc} is true; numbers as numbers, text as text (ISO dates order), numbers before
     * text, empty keys last either way; equal keys keep their order. Masked when a key is masked.
     */
    private static Object sort(List<Expr> args, EvalContext c) {
        Object v = listOf("sort", args.get(0), c);
        if (!(v instanceof DataNode.Arr list)) {
            return v;
        }
        boolean desc = false;
        if (args.size() > 2) {
            Object d = args.get(2).eval(c);
            if (Values.masked(d)) {
                return Values.mask();
            }
            desc = Values.truthy(d);
        }
        List<Object[]> keyed = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            DataNode e = list.get(i);
            Object k = args.size() > 1 ? args.get(1).eval(c.withRow(e, i)) : e;
            if (Values.masked(k)) {
                return Values.mask();                                     // an order by a hidden value would tell it
            }
            keyed.add(new Object[] {Values.simplify(k), e});
        }
        boolean descending = desc;
        keyed.sort((x, y) -> {
            boolean xe = Args.empty(x[0]);
            boolean ye = Args.empty(y[0]);
            if (xe || ye) {
                return Boolean.compare(xe, ye);                           // empty keys last, ascending or not
            }
            int order = compare(x[0], y[0]);
            return descending ? -order : order;
        });
        List<DataNode> out = new ArrayList<>(keyed.size());
        keyed.forEach(k -> out.add((DataNode) k[1]));
        return new DataNode.Arr(List.copyOf(out));
    }

    /** Numbers (and number-like text) as numbers, before text; text character by character; anything else by its text. */
    static int compare(Object x, Object y) {
        double a = x instanceof Boolean ? Double.NaN : Values.number(x);
        double b = y instanceof Boolean ? Double.NaN : Values.number(y);
        if (!Double.isNaN(a) && !Double.isNaN(b)) {
            return Args.decimal("sort", x).compareTo(Args.decimal("sort", y));
        }
        if (!Double.isNaN(a) || !Double.isNaN(b)) {
            return Double.isNaN(a) ? 1 : -1;
        }
        return Values.text(x).compareTo(Values.text(y));
    }

    /**
     * {@code unique(list)}: the distinct elements in the order first seen (numbers equal by value, {@code 1} and {@code 1.0};
     * text exactly; lists and objects by their content). Masked when an element is.
     */
    private static Object unique(Object v) {
        DataNode.Arr list = Args.list("unique", v);
        if (list == null) {
            return null;
        }
        Set<Object> seen = new HashSet<>();
        List<DataNode> out = new ArrayList<>();
        for (DataNode e : list.elements()) {
            if (e.isMasked()) {
                return Values.mask();
            }
            Object s = Values.simplify(e);
            Object key = s instanceof Number ? Args.out(Args.decimal("unique", s)) : s instanceof DataNode n ? n.unwrap() : s;
            if (key instanceof BigDecimal b) {
                key = b.stripTrailingZeros();
            }
            if (seen.add(key == null ? List.of() : key)) {
                out.add(e);
            }
        }
        return new DataNode.Arr(List.copyOf(out));
    }

    /**
     * {@code take(list, n)}: the first n elements (the last |n| when n is negative); {@code drop(list, n)}: all but the first n
     * (all but the last |n|). Beyond the list's length, the whole list or nothing.
     */
    private static Object slice(String fn, List<Object> a, boolean take) {
        DataNode.Arr list = Args.list(fn, a.get(0));
        Integer n = Args.whole(fn, a.get(1));
        if (list == null || n == null) {
            return null;
        }
        int size = list.size();
        int k = Math.min(Math.abs(n), size);
        List<DataNode> all = list.elements();
        List<DataNode> out;
        if (take) {
            out = n >= 0 ? all.subList(0, k) : all.subList(size - k, size);
        } else {
            out = n >= 0 ? all.subList(k, size) : all.subList(0, size - k);
        }
        return new DataNode.Arr(List.copyOf(out));
    }

    /** {@code sequence(n, [start], [step])}: n numbers from start (1) by step (1), in decimal: {@code sequence(3, 0.1, 0.1)} is 0.1, 0.2, 0.3. */
    private static Object sequence(List<Object> a) {
        Integer n = Args.whole("sequence", a.get(0));
        BigDecimal start = a.size() > 1 ? Args.decimal("sequence", a.get(1)) : BigDecimal.ONE;
        BigDecimal step = a.size() > 2 ? Args.decimal("sequence", a.get(2)) : BigDecimal.ONE;
        if (n == null || start == null || step == null) {
            return null;
        }
        if (n < 0 || n > MAX_SEQUENCE) {
            throw Args.error("sequence", "the length " + n + " is outside 0.." + MAX_SEQUENCE);
        }
        List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(Args.out(start.add(step.multiply(BigDecimal.valueOf(i)))));
        }
        return Args.list(out);
    }

    /** {@code map(list, expr)}: {@code expr} for each element ({@code @} the element, {@code #index} its position), as a list. */
    private static Object map(List<Expr> args, EvalContext c) {
        Object v = listOf("map", args.get(0), c);
        if (!(v instanceof DataNode.Arr list)) {
            return v;
        }
        List<Object> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(args.get(1).eval(c.withRow(list.get(i), i)));
        }
        return Args.list(out);
    }

    /**
     * {@code reduce(list, init, expr)}: the list folded from {@code init}: {@code expr} for each element in turn, with {@code @}
     * the element and {@code @acc} the value so far; the last value. Adding up with {@code +} or {@code -} is refused when the
     * expression compiles: {@code sum(...)} is the aggregate (ONE_GRAMMAR.md), {@code concat(...)} joins text.
     */
    private static Object reduce(List<Expr> args, EvalContext c) {
        Object v = listOf("reduce", args.get(0), c);
        if (!(v instanceof DataNode.Arr list)) {
            return v;
        }
        Object acc = args.get(1).eval(c);
        for (int i = 0; i < list.size(); i++) {
            acc = args.get(2).eval(c.withRow(list.get(i), i).withLocal(ACC, acc));
        }
        return acc;
    }

    /** The name {@code @acc} is bound to inside {@code reduce}'s expression. */
    static final String ACC = "@acc";
}
