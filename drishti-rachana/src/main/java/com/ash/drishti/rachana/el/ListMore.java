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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * More list functions (FUNCTION_CATALOGUE.md section 6), MATLAB's and Octave's that are not aggregates: {@code fillmissing},
 * {@code interp1}, {@code polyval}, {@code trapz}, {@code cumtrapz}, and the set functions {@code union}, {@code intersect},
 * {@code difference} (MATLAB's setdiff) and {@code is_member} (MATLAB's ismember). The statistics over a list (running and moving forms,
 * {@code zscore}, {@code isoutlier}, {@code polyfit} …) are the pivot engine's, registered into Rachana (ONE_GRAMMAR.md part 2).
 *
 * <p>An element that is not a number (empty, or text that is no number) is <b>missing</b>; a masked element makes the result masked. The
 * set functions compare as {@code ==} does (numbers by value, {@code 1} and {@code 1.0} equal; text exactly) and give their result in
 * {@code sort}'s order, each value once, as MATLAB's do; an empty element is a value like any other there.
 */
final class ListMore {

    private ListMore() {}

    /** The fill methods of {@code fillmissing}. */
    static final Set<String> FILLS = Set.of("previous", "next", "linear", "nearest", "constant");

    /** The methods of {@code interp1}. */
    static final Set<String> INTERPOLATIONS = Set.of("linear", "previous", "next", "nearest");

    static void register(Functions.Table t) {
        FinanceFunctions.fn(t, "fillmissing", 2, 3, (a, c) -> fillmissing(a));
        t.check("fillmissing", args -> Functions.literal(args, 1) instanceof String m && !FILLS.contains(m)
                ? "unknown method '" + m + "' (known: " + String.join(", ", new java.util.TreeSet<>(FILLS)) + ")" : null);
        FinanceFunctions.fn(t, "interp1", 3, 4, (a, c) -> interp1(a));
        t.check("interp1", args -> Functions.literal(args, 3) instanceof String m && !INTERPOLATIONS.contains(m)
                ? "unknown method '" + m + "' (known: " + String.join(", ", new java.util.TreeSet<>(INTERPOLATIONS)) + ")" : null);
        FinanceFunctions.fn(t, "polyval", 2, 2, (a, c) -> polyval(a));
        FinanceFunctions.fn(t, "trapz", 1, 2, (a, c) -> trapz(a, false));
        FinanceFunctions.fn(t, "cumtrapz", 1, 2, (a, c) -> trapz(a, true));
        FinanceFunctions.fn(t, "union", 2, 2, (a, c) -> sets("union", a));
        FinanceFunctions.fn(t, "intersect", 2, 2, (a, c) -> sets("intersect", a));
        FinanceFunctions.fn(t, "difference", 2, 2, (a, c) -> sets("difference", a));
        FinanceFunctions.fn(t, "is_member", 2, 2, (a, c) -> isMember(a));
    }

    /** The list's elements as numbers, NaN for a missing one; null for an empty argument; masked element: masked. */
    static double[] numbers(String fn, Object v) {
        DataNode.Arr list = Args.list(fn, v);
        if (list == null) {
            return null;
        }
        double[] x = new double[list.size()];
        for (int i = 0; i < x.length; i++) {
            DataNode e = list.get(i);
            MaskedValue.check(e);
            Object s = Values.simplify(e);
            x[i] = s instanceof Boolean ? Double.NaN : Values.number(s);
        }
        return x;
    }

    /** Numbers as a list, NaN as an empty element. */
    static DataNode.Arr out(double[] x) {
        List<DataNode> out = new ArrayList<>(x.length);
        for (double d : x) {
            out.add(Double.isNaN(d) || Double.isInfinite(d) ? DataNode.nullValue() : DataNode.of(Values.normalise(d)));
        }
        return new DataNode.Arr(List.copyOf(out));
    }

    /**
     * {@code fillmissing(list, method, [value])}: each missing element filled: {@code 'previous'} (the last value before; a leading gap
     * stays), {@code 'next'} (the first after; a trailing gap stays), {@code 'nearest'} (the closer by position, the next on a tie, as
     * MATLAB), {@code 'linear'} (by position between the neighbours, and extended in a straight line past the ends from the two nearest
     * values, MATLAB's default end rule), {@code 'constant'} (value). Elements that are present are kept as they are.
     */
    private static Object fillmissing(List<Object> a) {
        DataNode.Arr list = Args.list("fillmissing", a.get(0));
        String method = Args.text("fillmissing", a.get(1));
        if (list == null || method == null) {
            return null;
        }
        if (!FILLS.contains(method)) {
            throw Args.error("fillmissing", "unknown method '" + method + "' (known: " + String.join(", ", new java.util.TreeSet<>(FILLS)) + ")");
        }
        double[] x = numbers("fillmissing", list);
        int n = x.length;
        List<DataNode> out = new ArrayList<>(list.elements());
        if ("constant".equals(method)) {
            if (a.size() < 3) {
                throw Args.error("fillmissing", "'constant' needs the value to fill with: fillmissing(list, 'constant', 0)");
            }
            Object fill = Values.simplify(a.get(2));
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(x[i])) {
                    out.set(i, Args.node(fill));
                }
            }
            return new DataNode.Arr(List.copyOf(out));
        }
        int[] prev = new int[n];
        int[] next = new int[n];
        int last = -1;
        for (int i = 0; i < n; i++) {
            prev[i] = last;
            if (!Double.isNaN(x[i])) {
                last = i;
            }
        }
        last = -1;
        for (int i = n - 1; i >= 0; i--) {
            next[i] = last;
            if (!Double.isNaN(x[i])) {
                last = i;
            }
        }
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(x[i])) {
                continue;
            }
            int p = prev[i];
            int q = next[i];
            double v = Double.NaN;
            switch (method) {
                case "previous" -> v = p < 0 ? Double.NaN : x[p];
                case "next" -> v = q < 0 ? Double.NaN : x[q];
                case "nearest" -> {
                    if (p < 0 && q >= 0) {
                        v = x[q];
                    } else if (q < 0 && p >= 0) {
                        v = x[p];
                    } else if (p >= 0) {
                        v = i - p < q - i ? x[p] : x[q];
                    }
                }
                default -> {
                    int lo = p;
                    int hi = q;
                    if (lo < 0 && hi >= 0) {
                        lo = hi;
                        hi = next[hi];                                 // before the first value: the line through the first two
                    } else if (hi < 0 && lo >= 0) {
                        hi = lo;
                        lo = prev[lo];                                 // after the last: the line through the last two
                    }
                    if (lo >= 0 && hi >= 0 && lo != hi) {
                        v = x[lo] + (x[hi] - x[lo]) * (i - lo) / (hi - lo);
                    }
                }
            }
            if (!Double.isNaN(v)) {
                out.set(i, DataNode.of(Values.normalise(v)));
            }
        }
        return new DataNode.Arr(List.copyOf(out));
    }

    /**
     * {@code interp1(xs, ys, xq, [method])}: the value at xq (a number or a list of them) of the curve through the points (xs, ys), by
     * {@code 'linear'} (the default), {@code 'previous'}, {@code 'next'} or {@code 'nearest'} interpolation, MATLAB's interp1; empty outside
     * the points' range (MATLAB's NaN). The points are taken in x order; pairs with a missing value are left out; two points at one x are a
     * problem.
     */
    private static Object interp1(List<Object> a) {
        double[] xs = numbers("interp1", a.get(0));
        double[] ys = numbers("interp1", a.get(1));
        Object q = Values.simplify(a.get(2));
        String method = a.size() > 3 ? Args.text("interp1", a.get(3)) : "linear";
        if (xs == null || ys == null || q == null || method == null) {
            return null;
        }
        if (!INTERPOLATIONS.contains(method)) {
            throw Args.error("interp1", "unknown method '" + method + "'");
        }
        if (xs.length != ys.length) {
            throw Args.error("interp1", "the lists differ in length (" + xs.length + " and " + ys.length + ")");
        }
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            if (!Double.isNaN(xs[i]) && !Double.isNaN(ys[i])) {
                pts.add(new double[] {xs[i], ys[i]});
            }
        }
        pts.sort((u, v) -> Double.compare(u[0], v[0]));
        for (int i = 1; i < pts.size(); i++) {
            if (pts.get(i)[0] == pts.get(i - 1)[0]) {
                throw Args.error("interp1", "two points share x = " + Values.text(Values.normalise(pts.get(i)[0])));
            }
        }
        if (q instanceof DataNode.Arr ql) {
            double[] at = numbers("interp1", ql);
            double[] out = new double[at.length];
            for (int i = 0; i < at.length; i++) {
                out[i] = Double.isNaN(at[i]) ? Double.NaN : at(pts, at[i], method);
            }
            return out(out);
        }
        BigDecimal x = Args.decimal("interp1", q);
        double v = at(pts, x.doubleValue(), method);
        return Double.isNaN(v) ? null : Values.normalise(v);
    }

    private static double at(List<double[]> pts, double x, String method) {
        int n = pts.size();
        if (n == 0 || x < pts.get(0)[0] || x > pts.get(n - 1)[0]) {
            return Double.NaN;
        }
        int lo = 0;
        int hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (pts.get(mid)[0] <= x) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double[] p = pts.get(lo);
        double[] q = pts.get(hi);
        if (x == p[0]) {
            return p[1];
        }
        if (x == q[0]) {
            return q[1];
        }
        return switch (method) {
            case "previous" -> p[1];
            case "next" -> q[1];
            case "nearest" -> x - p[0] < q[0] - x ? p[1] : q[1];
            default -> p[1] + (q[1] - p[1]) * (x - p[0]) / (q[0] - p[0]);
        };
    }

    /** {@code polyval(p, x)}: the polynomial with coefficients p (highest power first, as polyfit gives) at x, or at each of a list's. */
    private static Object polyval(List<Object> a) {
        double[] p = numbers("polyval", a.get(0));
        Object x = Values.simplify(a.get(1));
        if (p == null || x == null) {
            return null;
        }
        for (double d : p) {
            if (Double.isNaN(d)) {
                return null;
            }
        }
        if (x instanceof DataNode.Arr xl) {
            double[] at = numbers("polyval", xl);
            double[] out = new double[at.length];
            for (int i = 0; i < at.length; i++) {
                out[i] = horner(p, at[i]);
            }
            return out(out);
        }
        return Args.out("polyval", horner(p, Args.decimal("polyval", x).doubleValue()));
    }

    private static double horner(double[] p, double x) {
        double v = 0;
        for (double c : p) {
            v = v * x + c;
        }
        return v;
    }

    /**
     * {@code trapz(ys)} or {@code trapz(xs, ys)}: the area under the points by the trapezoid rule (unit spacing without xs), MATLAB's order;
     * {@code cumtrapz} the running area, 0 first. Empty when a value is missing (an area over a gap is not guessed).
     */
    private static Object trapz(List<Object> a, boolean running) {
        String fn = running ? "cumtrapz" : "trapz";
        double[] xs = a.size() > 1 ? numbers(fn, a.get(0)) : null;
        double[] ys = numbers(fn, a.get(a.size() - 1));
        if (ys == null || a.size() > 1 && xs == null) {
            return null;
        }
        if (xs != null && xs.length != ys.length) {
            throw Args.error(fn, "the lists differ in length (" + xs.length + " and " + ys.length + ")");
        }
        double[] cum = new double[ys.length];
        double area = 0;
        for (int i = 0; i < ys.length; i++) {
            if (Double.isNaN(ys[i]) || xs != null && Double.isNaN(xs[i])) {
                return null;
            }
            if (i > 0) {
                double dx = xs == null ? 1 : xs[i] - xs[i - 1];
                area += dx * (ys[i] + ys[i - 1]) / 2;
            }
            cum[i] = area;
        }
        if (running) {
            return out(cum);
        }
        return ys.length == 0 ? (Object) 0L : Args.out(fn, area);
    }

    /** An element's identity for the set functions: numbers by value, text as written, lists and objects by content. */
    private static Object key(DataNode e) {
        Object s = Values.simplify(e);
        if (s instanceof Number) {
            BigDecimal d = Args.decimal("union", s);
            return d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
        }
        return s instanceof DataNode n ? n.unwrap() : s == null ? List.of() : s;
    }

    private static Map<Object, DataNode> distinct(String fn, Object v) {
        DataNode.Arr list = Args.list(fn, v);
        if (list == null) {
            return null;
        }
        Map<Object, DataNode> out = new LinkedHashMap<>();
        for (DataNode e : list.elements()) {
            MaskedValue.check(e);
            out.putIfAbsent(key(e), e);
        }
        return out;
    }

    /** {@code union(a, b)}, {@code intersect(a, b)}, {@code difference(a, b)}: each value once, in {@code sort}'s order. */
    private static Object sets(String fn, List<Object> a) {
        Map<Object, DataNode> x = distinct(fn, a.get(0));
        Map<Object, DataNode> y = distinct(fn, a.get(1));
        if (x == null || y == null) {
            return null;
        }
        List<DataNode> out = new ArrayList<>();
        switch (fn) {
            case "union" -> {
                out.addAll(x.values());
                y.forEach((k, e) -> {
                    if (!x.containsKey(k)) {
                        out.add(e);
                    }
                });
            }
            case "intersect" -> x.forEach((k, e) -> {
                if (y.containsKey(k)) {
                    out.add(e);
                }
            });
            default -> x.forEach((k, e) -> {
                if (!y.containsKey(k)) {
                    out.add(e);
                }
            });
        }
        out.sort((p, q) -> {
            Object u = Values.simplify(p);
            Object v = Values.simplify(q);
            boolean ue = Args.empty(u);
            boolean ve = Args.empty(v);
            return ue || ve ? Boolean.compare(ue, ve) : ListFunctions.compare(u, v);
        });
        return new DataNode.Arr(List.copyOf(out));
    }

    /**
     * {@code is_member(x, list)}: true when x is in the list (as {@code ==} compares; MATLAB's ismember), and for a list x, a list of
     * true/false, one per element. An empty list holds nothing ({@code one_of}, for a control's choices, holds everything when none is
     * chosen).
     */
    private static Object isMember(List<Object> a) {
        Object x = Values.simplify(a.get(0));
        Map<Object, DataNode> set = distinct("is_member", a.get(1));
        if (set == null) {
            return null;
        }
        if (x instanceof DataNode.Arr xl) {
            List<DataNode> out = new ArrayList<>(xl.size());
            for (DataNode e : xl.elements()) {
                MaskedValue.check(e);
                out.add(DataNode.of(set.containsKey(key(e))));
            }
            return new DataNode.Arr(List.copyOf(out));
        }
        return x == null ? (Object) set.containsKey(List.of()) : (Object) set.containsKey(key(DataNode.of(x)));
    }
}
