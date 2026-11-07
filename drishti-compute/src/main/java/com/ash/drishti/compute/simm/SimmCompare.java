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
package com.ash.drishti.compute.simm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Two SIMM runs of the same netting set side by side (SIMM_GUIDE.md section 8): the
 * difference per product class, risk class and measure, the bucket where most of it arises and the step that changed (the concentration
 * factors, the weighted sensitivities, the bucket's K, or the sum across buckets), and one sentence in words.
 */
public final class SimmCompare {

    private SimmCompare() {}

    /** One line of the comparison. */
    public record Line(String productClass, String riskClass, String measure, double base, double alternative, double difference, String bucket,
            String step) {}

    /** The comparison. */
    public record Diff(String label, double baseTotal, double alternativeTotal, double difference, List<Line> lines, String words) {}

    public static Diff compare(Simm.Result a, Simm.Result b, String label) {
        Set<String> keys = new LinkedHashSet<>();
        a.charges().forEach(c -> keys.add(c.productClass + "|" + c.riskClass + "|" + c.measure));
        b.charges().forEach(c -> keys.add(c.productClass + "|" + c.riskClass + "|" + c.measure));
        List<Line> lines = new ArrayList<>();
        for (String k : keys) {
            String[] p = k.split("\\|");
            Simm.Charge x = a.charge(p[0], p[1], p[2]);
            Simm.Charge y = b.charge(p[0], p[1], p[2]);
            double mx = x == null ? 0 : x.margin;
            double my = y == null ? 0 : y.margin;
            if (Math.abs(my - mx) < 0.005) {
                continue;
            }
            String bucket = "";
            double worst = -1;
            Map<String, Double> kx = new LinkedHashMap<>();
            if (x != null) {
                x.buckets.forEach(bk -> kx.put(bk.label, bk.k));
            }
            if (y != null) {
                for (Simm.Bucket bk : y.buckets) {
                    double d = Math.abs(bk.k - kx.getOrDefault(bk.label, 0.0));
                    if (d > worst) {
                        worst = d;
                        bucket = bk.label;
                    }
                }
            }
            lines.add(new Line(p[0], p[1], p[2], mx, my, my - mx, bucket, step(a, b, x, y)));
        }
        lines.sort(Comparator.comparingDouble((Line l) -> -Math.abs(l.difference())));
        double d = b.total() - a.total();
        StringBuilder w = new StringBuilder();
        w.append(label).append(d >= 0 ? " raises" : " lowers").append(" the margin by ").append(money(Math.abs(d)));
        if (a.total() != 0) {
            w.append(String.format(Locale.ROOT, " (%.1f%%)", 100 * Math.abs(d) / a.total()));
        }
        if (!lines.isEmpty()) {
            Line top = lines.get(0);
            w.append(", most of it in ").append(top.productClass()).append(' ').append(top.riskClass()).append(' ').append(top.measure().toLowerCase(Locale.ROOT));
            if (!top.bucket().isEmpty()) {
                w.append(", bucket ").append(top.bucket());
            }
            w.append(", at the ").append(top.step()).append(" step");
        } else {
            w.append(": no risk class changes");
        }
        w.append('.');
        return new Diff(label, a.total(), b.total(), d, List.copyOf(lines), w.toString());
    }

    private static String step(Simm.Result a, Simm.Result b, Simm.Charge x, Simm.Charge y) {
        if (x == null || y == null) {
            return "weighted sensitivity";
        }
        boolean cr = false;
        boolean ws = false;
        boolean hvr = false;
        Map<String, Simm.Risk> byKey = new LinkedHashMap<>();
        for (Simm.Bucket bk : x.buckets) {
            for (int m : bk.members) {
                byKey.put(a.risks().get(m).key, a.risks().get(m));
            }
        }
        for (Simm.Bucket bk : y.buckets) {
            for (int m : bk.members) {
                Simm.Risk r = b.risks().get(m);
                Simm.Risk o = byKey.get(r.key);
                if (o == null || Math.abs(o.ws - r.ws) > 1e-9 * Math.max(1, Math.abs(o.ws))) {
                    ws = true;
                    cr |= o != null && Math.abs(o.cr - r.cr) > 1e-12;
                    hvr |= o != null && Math.abs(o.hvr - r.hvr) > 1e-12;
                }
            }
        }
        if (hvr) {
            return "historical volatility ratio (the vega WS and its concentration factor)";
        }
        if (cr) {
            return "concentration factor (WS)";
        }
        if (ws) {
            return "weighted sensitivity (risk weight or HVR)";
        }
        for (int i = 0; i < Math.min(x.buckets.size(), y.buckets.size()); i++) {
            if (Math.abs(x.buckets.get(i).k - y.buckets.get(i).k) > 1e-9 * Math.max(1, x.buckets.get(i).k)) {
                return "bucket K_b (correlation)";
            }
        }
        return x.measure.equals(Simm.CURVATURE) ? "curvature (θ, λ or the scale)" : "across buckets (γ)";
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }
}
