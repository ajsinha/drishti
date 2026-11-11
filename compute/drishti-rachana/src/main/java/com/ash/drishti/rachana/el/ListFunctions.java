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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists, Excel's dynamic arrays over {@code [*]} (FUNCTION_CATALOGUE.md section 6): {@code size}, {@code first},
 * {@code last}, {@code flatten}, {@code sort}, {@code unique}, {@code take}, {@code drop}, {@code sequence}, {@code map},
 * {@code reduce}; and {@code group} ({@link GroupForm}: Rachana binds the key and the inputs, the pivot engine groups and
 * aggregates). Aggregates over a list ({@code sum} …) are the engine's (docs/architecture/ARCHITECTURE.md part 2). {@code map}, {@code sort} and
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
        t.form("group", 2, 4, GroupForm::group);
        t.form("sort", 1, 17, ListFunctions::sort);
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
     * text, empty keys last either way; equal keys keep their order. Several keys, each with its own direction, order as
     * MATLAB's {@code sortrows} and SQL's {@code ORDER BY}: {@code sort(list, key1, desc1, key2, desc2, ...)}, the later keys
     * ordering what the earlier leave equal. Masked when a key is masked.
     */
    private static Object sort(List<Expr> args, EvalContext c) {
        Object v = listOf("sort", args.get(0), c);
        if (!(v instanceof DataNode.Arr list)) {
            return v;
        }
        int keys = Math.max(1, args.size() / 2);                           // (list), (list, k), (list, k, d), (list, k, d, k2) …
        boolean[] desc = new boolean[keys];
        for (int j = 0; j < keys; j++) {
            int at = 2 + 2 * j;
            if (args.size() > at) {
                Object d = args.get(at).eval(c);
                if (Values.masked(d)) {
                    return Values.mask();
                }
                desc[j] = Values.truthy(d);
            }
        }
        List<Object[]> keyed = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            DataNode e = list.get(i);
            Object[] row = new Object[keys + 1];
            for (int j = 0; j < keys; j++) {
                Object k = args.size() > 1 + 2 * j ? args.get(1 + 2 * j).eval(c.withRow(e, i)) : e;
                if (Values.masked(k)) {
                    return Values.mask();                                 // an order by a hidden value would tell it
                }
                row[j] = Values.simplify(k);
            }
            row[keys] = e;
            keyed.add(row);
        }
        keyed.sort((x, y) -> {
            for (int j = 0; j < keys; j++) {
                boolean xe = Args.empty(x[j]);
                boolean ye = Args.empty(y[j]);
                if (xe || ye) {
                    if (xe != ye) {
                        return Boolean.compare(xe, ye);                   // empty keys last, ascending or not
                    }
                    continue;
                }
                int order = compare(x[j], y[j]);
                if (order != 0) {
                    return desc[j] ? -order : order;
                }
            }
            return 0;
        });
        List<DataNode> out = new ArrayList<>(keyed.size());
        keyed.forEach(k -> out.add((DataNode) k[keys]));
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
     * expression compiles: {@code sum(...)} is the aggregate (docs/architecture/ARCHITECTURE.md), {@code concat(...)} joins text.
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
