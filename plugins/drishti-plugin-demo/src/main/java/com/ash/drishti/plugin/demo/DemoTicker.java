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
package com.ash.drishti.plugin.demo;

import com.ash.drishti.api.DataNode;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Moves live demo documents the way markets do: small random walks on prices, rates and MTM, keeping
 * dependent fields consistent (a future's MTM follows its last price; the intraday settlement row follows
 * too). Works on plain maps; one instance per plugin, called only from the tick thread.
 */
final class DemoTicker {

    private final SplittableRandom rnd;

    DemoTicker(long seed) {
        this.rnd = new SplittableRandom(seed);
    }

    /**
     * A moved copy of {@code doc}. A fixture's {@code _meta.walk} ({@code {"field": stepSize}}) walks those top-level
     * numeric fields, so any pack can make its samples tick without code; the finance kinds below also keep
     * dependent values consistent.
     */
    @SuppressWarnings("unchecked")
    DataNode tick(String kind, DataNode doc, DataNode walk) {
        Map<String, Object> m = (Map<String, Object>) doc.unwrap();
        if (walk instanceof DataNode.Obj w && w.size() > 0) {
            w.fields().forEach((field, step) -> {
                if (m.get(field) instanceof Number n) {
                    double sd = step.asDouble();
                    double v = n.doubleValue() + gauss(sd);
                    m.put(field, sd >= 1 ? (Object) Math.round(v) : (Object) round(v, 2));
                }
            });
            return DataNode.of(m);
        }
        switch (kind) {
            case "curve" -> curve(m);
            case "fx-spot" -> m.put("spot", round(num(m, "spot") + gauss(0.00008), 5));
            case "netting-set" -> nettingSet(m);
            case "trade" -> trade(m);
            default -> { }
        }
        return DataNode.of(m);
    }

    @SuppressWarnings("unchecked")
    private void curve(Map<String, Object> m) {
        List<Map<String, Object>> pts = (List<Map<String, Object>>) m.get("points");
        String field = pts.isEmpty() ? null : pts.get(0).containsKey("rate") ? "rate" : pts.get(0).containsKey("price") ? "price" : "points";
        double step = "rate".equals(field) ? 0.004 : "price".equals(field) ? 0.03 : 0.3;
        double shift = gauss(step);
        for (Map<String, Object> p : pts) {
            p.put(field, round(num(p, field) + shift + gauss(step / 3), "rate".equals(field) ? 3 : 2));
        }
    }

    @SuppressWarnings("unchecked")
    private void trade(Map<String, Object> m) {
        if ("FUT".equals(m.get("productType"))) {
            double last = round(num(m, "lastPrice") + gauss(0.03), 2);
            double lots = num(m, "lots");
            m.put("lastPrice", last);
            m.put("mtm", Math.round((last - num(m, "tradePrice")) * lots * 1000));
            List<Map<String, Object>> ladder = (List<Map<String, Object>>) m.get("settlements");
            if (ladder != null && ladder.size() >= 2) {
                Map<String, Object> today = ladder.get(ladder.size() - 1);
                Map<String, Object> prev = ladder.get(ladder.size() - 2);
                double change = round(last - num(prev, "settle"), 2);
                long vm = Math.round(change * lots * 1000);
                today.put("settle", last);
                today.put("change", change);
                today.put("variationMargin", vm);
                today.put("cumulative", Math.round(num(prev, "cumulative")) + vm);
                Map<String, Object> margin = (Map<String, Object>) m.get("margin");
                if (margin != null) {
                    margin.put("variationMarginToday", vm);
                }
            }
            return;
        }
        if (m.containsKey("mtm")) {
            m.put("mtm", Math.round(num(m, "mtm") + gauss(Math.abs(num(m, "mtm")) * 0.002 + 400)));
        }
        if (m.containsKey("dv01")) {
            m.put("dv01", Math.round(num(m, "dv01") + gauss(15)));
        }
        if (m.containsKey("swapPoints")) {
            m.put("swapPoints", round(num(m, "swapPoints") + gauss(0.1), 1));
        }
    }

    @SuppressWarnings("unchecked")
    private void nettingSet(Map<String, Object> m) {
        m.put("netMtm", Math.round(num(m, "netMtm") + gauss(2500)));
        List<Map<String, Object>> exposure = (List<Map<String, Object>>) m.get("exposure");
        if (exposure != null) {
            double f = 1 + gauss(0.004);
            for (Map<String, Object> p : exposure) {
                p.put("ee", Math.round(num(p, "ee") * f));
                p.put("pfe95", Math.round(num(p, "pfe95") * f));
            }
        }
    }

    private double gauss(double sd) {
        double u = 1 - rnd.nextDouble();
        double v = rnd.nextDouble();
        return sd * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
