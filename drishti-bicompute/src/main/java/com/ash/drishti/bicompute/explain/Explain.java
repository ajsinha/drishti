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
package com.ash.drishti.bicompute.explain;

import com.ash.drishti.kernel.api.vocabulary.DomainVocabulary;
import com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall;
import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.CellSet;
import com.ash.drishti.kernel.api.pivot.Dim;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * "Explain the difference" between two cells, or one cell on two dates (HOW_IT_FITS.md section A26.7, item 1), in exact steps that add up.
 *
 * <p>The difference {@code to − from} is split by the members of a dimension the person picks ({@code by}): each step moves one member's rows
 * from the first cell to the second (the member's state replaced, the total's states merged and finished again), in a fixed order (by
 * member, or by the size of the member's own change), so the steps telescope: their sum is exactly the change in the merged figure. For a
 * sum or a count each step is the member's own change, whatever the order; for a ratio, an average or a variance a step is that member's
 * effect on the total given the steps before it, and a different order splits the same difference differently (the order is shown). Each
 * step says its cause: a member new in the second cell, a member gone, or a member whose figure changed.
 *
 * <p>Where states cannot be merged (a provider's own holistic figure, VaR from summed vectors), each member's own change is a step and the
 * part of the difference they do not explain is named as one more step (for VaR: diversification). With {@code effects} (a price and a
 * quantity field) a {@code sum(PRICE * QUANTITY)}-like measure is split into the price effect, the quantity effect and the cross term, in
 * that order, per member, plus new and gone members. Whatever the method, the steps and the residual add up to the difference.
 */
public final class Explain {

    /** The most members explained one by one; the rest are one "other members" step (it still adds up). */
    public static final int MAX_MEMBERS = 500;

    /**
     * What to explain.
     *
     * @param view the view (source, filters, values); its date is the first cell's
     * @param measure the value's label or id (null: the first value)
     * @param from the first cell's coordinates
     * @param fromDate the first cell's date (null: the view's)
     * @param to the second cell's coordinates (null: the same as {@code from})
     * @param toDate the second cell's date (null: the view's)
     * @param by the dimension that splits the difference
     * @param order {@code member} or {@code size}
     * @param price a price field (with {@code quantity}: price, quantity and cross effects)
     * @param quantity a quantity field
     */
    public record Request(PivotView view, String measure, Map<String, String> from, String fromDate, Map<String, String> to, String toDate,
            String by, String order, String price, String quantity) {}

    private Explain() {}

    /** The explanation as the API answers it. */
    public static Map<String, Object> explain(PivotCore core, Viewer v, Request r) {
        if (r.by() == null || r.by().isBlank()) {
            throw bad("explain needs by: the dimension whose members split the difference (DESK, BOOK, CURRENCY, ...)");
        }
        Map<String, String> from = r.from() == null ? Map.of() : r.from();
        Map<String, String> to = r.to() == null ? from : r.to();
        String by = Dim.parse(r.by()).id();
        if (from.containsKey(by) || to.containsKey(by)) {
            throw bad("the cells already fix " + by + ": split the difference by another dimension");
        }
        PivotView a = r.fromDate() == null ? r.view() : r.view().on(r.fromDate());
        PivotView b = r.toDate() == null ? r.view() : r.view().on(r.toDate());
        if (r.price() != null && r.quantity() != null) {
            return PriceVolume.explain(core, v, r, a, b, from, to, by);
        }
        Side x = side(core, v, a, from, by, r.measure());
        Side y = side(core, v, b, to, by, r.measure());
        FunctionCall fc = x.fn;
        AggFunction<Object> f = fc.fn();
        TreeSet<String> members = new TreeSet<>(x.children.keySet());
        members.addAll(y.children.keySet());
        Double vx = x.total == null ? null : f.finish(x.total);
        Double vy = y.total == null ? null : f.finish(y.total);
        double diff = num(vy) - num(vx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("measure", fc.label());
        out.put("function", fc.id());
        out.put("kind", f.kind().word());
        out.put("by", by);
        out.put("from", cellMap(from, x.date, vx));
        out.put("to", cellMap(to, y.date, vy));
        out.put("difference", diff);
        List<Map<String, Object>> steps = new ArrayList<>();
        boolean mergeable = mergeable(f, x, y);
        List<String> order = ordered(members, x, y, f, r.order(), mergeable);
        double sum = 0;
        if (mergeable) {
            out.put("method", f.plainSum() ? "each member's own change (the measure adds up)" : "sequential replacement: each step moves one "
                    + "member's rows from the first cell to the second and finishes the merged states again; the order is shown");
            Map<String, Object> current = new TreeMap<>();
            members.forEach(m -> {
                if (x.children.containsKey(m)) {
                    current.put(m, x.children.get(m));
                }
            });
            Double prev = finish(f, current.values());
            List<String> one = order.size() > MAX_MEMBERS ? order.subList(0, MAX_MEMBERS) : order;
            for (String m : one) {
                if (y.children.containsKey(m)) {
                    current.put(m, y.children.get(m));
                } else {
                    current.remove(m);
                }
                Double now = finish(f, current.values());
                double step = num(now) - num(prev);
                sum += step;
                steps.add(step(m, cause(x, y, m), fig(f, x, m), fig(f, y, m), step, sum));
                prev = now;
            }
            if (one.size() < order.size()) {
                for (String m : order.subList(MAX_MEMBERS, order.size())) {
                    if (y.children.containsKey(m)) {
                        current.put(m, y.children.get(m));
                    } else {
                        current.remove(m);
                    }
                }
                Double now = finish(f, current.values());
                double step = num(now) - num(prev);
                sum += step;
                steps.add(step("(" + (order.size() - MAX_MEMBERS) + " other members)", "changed", null, null, step, sum));
            }
        } else {
            out.put("method", "each member's own change; what they do not explain is its own step (the measure is " + f.kind().word()
                    + " and computed from each cell's own members)");
            for (String m : order) {
                Double fx = fig(f, x, m);
                Double fy = fig(f, y, m);
                double step = num(fy) - num(fx);
                sum += step;
                steps.add(step(m, cause(x, y, m), fx, fy, step, sum));
            }
            double rest = diff - sum;
            sum += rest;
            steps.add(step(isDiversifying(fc) ? "diversification" : "not additive", "residual", null, null, rest, sum));
        }
        out.put("order", r.order() == null || r.order().isBlank() ? "member" : r.order());
        out.put("steps", steps);
        double residual = diff - sum;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("value", residual);
        res.put("name", Math.abs(residual) <= 1e-9 * Math.max(1, Math.abs(diff)) ? "rounding (floating point)" : "rows outside the members of "
                + by + " (a filter or a blank member)");
        out.put("residual", res);
        out.put("addsUp", true);
        out.put("words", words(fc.label(), vx, vy, diff, steps));
        return out;
    }

    // ---- the two sides --------------------------------------------------------------------------------------------------------------

    /** One cell: its own total state, its children's states by member, its date. */
    static final class Side {
        FunctionCall fn;
        int index;
        Object total;
        Map<String, Object> children = new TreeMap<>();
        Map<String, Long> members = new TreeMap<>();
        String date;
    }

    static Side side(PivotCore core, Viewer v, PivotView view, Map<String, String> coords, String by, String measure) {
        Side s = new Side();
        List<String> g = new ArrayList<>(coords.keySet());
        PivotCore.SetStates total = core.states(v, view, CellSet.of(g, coords));
        s.index = index(total.functions(), measure);
        s.fn = total.functions().get(s.index);
        s.date = total.date();
        Object[] t = total.states().get(CellKey.of(coords));
        s.total = t == null ? null : t[s.index];
        List<String> g2 = new ArrayList<>(g);
        g2.add(by);
        PivotCore.SetStates kids = core.states(v, view, CellSet.of(g2, coords));
        kids.states().forEach((k, st) -> {
            s.children.put(k.coords().get(by), st[s.index]);
            s.members.put(k.coords().get(by), kids.members().getOrDefault(k, 0L));
        });
        return s;
    }

    static int index(List<FunctionCall> fns, String measure) {
        if (measure == null || measure.isBlank()) {
            return 0;
        }
        for (int i = 0; i < fns.size(); i++) {
            if (fns.get(i).label().equals(measure) || fns.get(i).id().equals(measure)) {
                return i;
            }
        }
        throw bad("'" + measure + "' is not a value of the view: " + fns.stream().map(FunctionCall::label).toList());
    }

    private static boolean mergeable(AggFunction<Object> f, Side x, Side y) {
        if (f.kind() == Kind.HOLISTIC && !f.sqlStateMergeable()) {
            // a holistic built-in's SQL state is its figure; it merges only when the states hold the values (a provider fed them)
            Object any = x.children.values().stream().findFirst().orElse(y.children.values().stream().findFirst().orElse(null));
            if (any == null) {
                return true;
            }
            try {
                f.merge(f.start(), any);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    private static List<String> ordered(TreeSet<String> members, Side x, Side y, AggFunction<Object> f, String order, boolean mergeable) {
        List<String> out = new ArrayList<>(members);
        if ("size".equals(order)) {
            out.sort((p, q) -> Double.compare(Math.abs(num(fig(f, y, q)) - num(fig(f, x, q))), Math.abs(num(fig(f, y, p)) - num(fig(f, x, p)))));
        }
        return out;
    }

    private static Double finish(AggFunction<Object> f, java.util.Collection<Object> states) {
        Object s = f.start();
        for (Object o : states) {
            s = f.merge(s, f.decode(f.encode(o)));
        }
        return f.finish(s);
    }

    private static Double fig(AggFunction<Object> f, Side s, String m) {
        Object st = s.children.get(m);
        return st == null ? null : f.finish(st);
    }

    private static String cause(Side x, Side y, String m) {
        return !x.children.containsKey(m) ? "new" : !y.children.containsKey(m) ? "gone" : "changed";
    }

    static Map<String, Object> step(String member, String cause, Double from, Double to, double step, double cumulative) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("member", member);
        m.put("cause", cause);
        m.put("from", from);
        m.put("to", to);
        m.put("step", step);
        m.put("cumulative", cumulative);
        return m;
    }

    static Map<String, Object> cellMap(Map<String, String> coords, String date, Double value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coords", coords);
        m.put("date", date);
        m.put("value", value);
        return m;
    }

    private static boolean isDiversifying(FunctionCall fc) {
        String n = fc.fn().name().toLowerCase(java.util.Locale.ROOT);
        return DomainVocabulary.anyMatch(d -> d.diversifies(n, fc.fn().inputs()));
    }

    static double num(Double d) {
        return d == null ? 0 : d;
    }

    static String words(String measure, Double a, Double b, double diff, List<Map<String, Object>> steps) {
        List<Map<String, Object>> big = new ArrayList<>(steps);
        big.sort((p, q) -> Double.compare(Math.abs((Double) q.get("step")), Math.abs((Double) p.get("step"))));
        StringBuilder w = new StringBuilder(measure + " moved from " + fmt(a) + " to " + fmt(b) + " (" + (diff >= 0 ? "+" : "") + fmt(diff) + ")");
        if (!big.isEmpty()) {
            w.append("; the largest steps: ");
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> s : big.subList(0, Math.min(3, big.size()))) {
                double st = (Double) s.get("step");
                parts.add(s.get("member") + " " + (st >= 0 ? "+" : "") + fmt(st) + (s.get("cause").equals("changed") ? "" : " (" + s.get("cause") + ")"));
            }
            w.append(String.join(", ", parts));
        }
        return w.append('.').toString();
    }

    static String fmt(Double d) {
        if (d == null) {
            return "nothing";
        }
        return String.format(java.util.Locale.ROOT, Math.abs(d) >= 1000 ? "%,.0f" : "%.4g", d);
    }

    static DrishtiException bad(String why) {
        return new DrishtiException(ErrorCode.PIVOT_EXPLAIN, why);
    }
}
