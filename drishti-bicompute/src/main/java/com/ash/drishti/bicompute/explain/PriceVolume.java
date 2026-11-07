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

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The price and quantity effects of a value {@code Σ price × quantity} between two cells (ARCHITECTURE.md section 23.10, item 1): per member of
 * {@code by}, its quantity {@code q = Σ QUANTITY} and its price {@code p = Σ PRICE·QUANTITY / Σ QUANTITY} on each side, so {@code p·q} is the
 * member's value exactly. The difference is, in this fixed order: the price effect {@code Σ (p₁ − p₀)·q₀}, the quantity effect
 * {@code Σ p₀·(q₁ − q₀)}, the cross term {@code Σ (p₁ − p₀)·(q₁ − q₀)} (members on both sides), then members new in the second cell and
 * members gone from the first. The five steps add up to the difference.
 */
final class PriceVolume {

    private PriceVolume() {}

    static Map<String, Object> explain(PivotCore core, Viewer v, Explain.Request r, PivotView a, PivotView b, Map<String, String> from,
            Map<String, String> to, String by) {
        String value = "sum(" + r.price() + " * " + r.quantity() + ")";
        String price = "wavg(" + r.price() + ", " + r.quantity() + ")";
        String qty = "sum(" + r.quantity() + ")";
        Explain.Side x0 = Explain.side(core, v, with(a, value, price, qty), from, by, qty);
        Explain.Side y0 = Explain.side(core, v, with(b, value, price, qty), to, by, qty);
        Explain.Side xp = Explain.side(core, v, with(a, value, price, qty), from, by, price);
        Explain.Side yp = Explain.side(core, v, with(b, value, price, qty), to, by, price);
        Explain.Side xv = Explain.side(core, v, with(a, value, price, qty), from, by, value);
        Explain.Side yv = Explain.side(core, v, with(b, value, price, qty), to, by, value);
        TreeSet<String> members = new TreeSet<>(x0.children.keySet());
        members.addAll(y0.children.keySet());
        double priceEffect = 0;
        double qtyEffect = 0;
        double cross = 0;
        double added = 0;
        double gone = 0;
        List<Map<String, Object>> perMember = new ArrayList<>();
        for (String m : members) {
            Double q0 = fin(x0, m);
            Double q1 = fin(y0, m);
            Double p0 = fin(xp, m);
            Double p1 = fin(yp, m);
            double v0 = Explain.num(fin(xv, m));
            double v1 = Explain.num(fin(yv, m));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("member", m);
            if (!x0.children.containsKey(m)) {
                added += v1;
                row.put("cause", "new");
                row.put("value", v1);
            } else if (!y0.children.containsKey(m)) {
                gone -= v0;
                row.put("cause", "gone");
                row.put("value", -v0);
            } else {
                double dp = Explain.num(p1) - Explain.num(p0);
                double dq = Explain.num(q1) - Explain.num(q0);
                double pe = dp * Explain.num(q0);
                double qe = Explain.num(p0) * dq;
                double ce = dp * dq;
                double rest = (v1 - v0) - (pe + qe + ce);   // a member with no quantity: its value moved without a price
                priceEffect += pe;
                qtyEffect += qe + rest;
                cross += ce;
                row.put("cause", "changed");
                row.put("price", pe);
                row.put("quantity", qe + rest);
                row.put("cross", ce);
            }
            perMember.add(row);
        }
        Double vx = xv.total == null ? null : xv.fn.fn().finish(xv.total);
        Double vy = yv.total == null ? null : yv.fn.fn().finish(yv.total);
        double diff = Explain.num(vy) - Explain.num(vx);
        List<Map<String, Object>> steps = new ArrayList<>();
        double sum = 0;
        double[] parts = {priceEffect, qtyEffect, cross, added, gone};
        String[] names = {"price effect", "quantity effect", "price × quantity (cross term)", "new members", "members gone"};
        String[] causes = {"price", "quantity", "cross", "new", "gone"};
        for (int i = 0; i < parts.length; i++) {
            sum += parts[i];
            steps.add(Explain.step(names[i], causes[i], null, null, parts[i], sum));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("measure", value);
        out.put("function", value);
        out.put("kind", "mergeable");
        out.put("by", by);
        out.put("from", Explain.cellMap(from, xv.date, vx));
        out.put("to", Explain.cellMap(to, yv.date, vy));
        out.put("difference", diff);
        out.put("method", "price and quantity effects in a fixed order (price, quantity, cross term, new members, members gone); per member "
                + "price = Σ " + r.price() + "·" + r.quantity() + " / Σ " + r.quantity() + " and quantity = Σ " + r.quantity());
        out.put("order", "price, quantity, cross, new, gone");
        out.put("steps", steps);
        out.put("members", perMember);
        double residual = diff - sum;
        out.put("residual", Map.of("value", residual, "name", "rounding (floating point), or rows whose " + by + " is blank"));
        out.put("addsUp", true);
        out.put("words", Explain.words(value, vx, vy, diff, steps));
        return out;
    }

    private static Double fin(Explain.Side s, String m) {
        Object st = s.children.get(m);
        return st == null ? null : s.fn.fn().finish(st);
    }

    private static PivotView with(PivotView view, String... values) {
        Map<String, Object> m = new LinkedHashMap<>(view.toMap());
        m.put("values", List.of(values));
        m.remove("sort");
        m.remove("top");
        return PivotView.parse(m);
    }
}
