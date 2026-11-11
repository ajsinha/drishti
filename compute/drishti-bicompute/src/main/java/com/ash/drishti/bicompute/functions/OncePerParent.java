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
package com.ash.drishti.bicompute.functions;

import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A coarser figure in a finer view, counted once per parent (ARCHITECTURE.md section 26.4, rule 2): {@code sum_once(x, p)},
 * {@code count_once(x, p)} and {@code avg_once(x, p)}. In a leg-grain view a trade's {@code QUANTITY} repeats on each of its legs; these
 * take each parent's ({@code p}, the trade's id) value once per cell, so a trade with three legs adds its quantity once. Holistic: the
 * state is the cell's parents and their values, exact from the cell's own rows. The pivot engine writes them when a view asks
 * {@code sum(QUANTITY)} at a finer grain; a person never has to.
 */
public final class OncePerParent implements AggFunction<Object> {

    private final String name;

    /** @param name {@code sum_once}, {@code count_once} or {@code avg_once} */
    public OncePerParent(String name) {
        if (!List.of("sum_once", "count_once", "avg_once").contains(name)) {
            throw new IllegalArgumentException("no once-per-parent function " + name);
        }
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Kind kind() {
        return Kind.HOLISTIC;
    }

    @Override
    public int inputs() {
        return 2;
    }

    @Override
    public boolean numeric() {
        return false;
    }

    @Override
    public Object start() {
        return new HashMap<String, Double>();
    }

    private static Double number(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            double d = Double.parseDouble(s.strip());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object add(Object s, Batch b) {
        Map<String, Double> m = (Map<String, Double>) s;
        String[] x = b.text(0);
        String[] p = b.text(1);
        for (int i = 0; i < b.rows(); i++) {
            Double v = number(x[i]);
            if (v != null && p[i] != null) {
                m.putIfAbsent(p[i], v);                      // a parent's value is the same on every one of its children
            }
        }
        return m;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object merge(Object a, Object b) {
        if (a instanceof Final || b instanceof Final) {
            throw new IllegalStateException(name + " is holistic: a figure computed by the engine is never merged");
        }
        Map<String, Double> out = new HashMap<>((Map<String, Double>) a);
        ((Map<String, Double>) b).forEach(out::putIfAbsent);
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Double finish(Object s) {
        if (s instanceof Final f) {
            return f.value();
        }
        Map<String, Double> m = (Map<String, Double>) s;
        if ("count_once".equals(name)) {
            return (double) m.size();
        }
        if (m.isEmpty()) {
            return null;
        }
        double sum = 0;
        double c = 0;
        for (double v : new TreeMap<>(m).values()) {            // a fixed order: the same figure whatever the merge order
            double y = v - c;
            double t = sum + y;
            c = (t - sum) - y;
            sum = t;
        }
        return "sum_once".equals(name) ? sum : sum / m.size();
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] encode(Object s) {
        if (s instanceof Final f) {
            return f.encode();
        }
        StringBuilder b = new StringBuilder("\u0001");
        new TreeMap<>((Map<String, Double>) s).forEach((k, v) -> b.append(k.replace("\u0000", "")).append('\u0000').append(v).append('\u0000'));
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Object decode(byte[] b) {
        if (b[0] == Final.TAG) {
            return Final.decode(b);
        }
        Map<String, Double> out = new HashMap<>();
        String[] parts = new String(b, 1, b.length - 1, StandardCharsets.UTF_8).split("\u0000");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            out.put(parts[i], Double.valueOf(parts[i + 1]));
        }
        return out;
    }

    @Override
    public long bytes(Object s) {
        return s instanceof Map<?, ?> m ? 48 + 64L * m.size() : 32;
    }

    @Override
    public List<String> sqlState(List<String> in) {
        String x = "TRY_CAST(" + in.get(0) + " AS DOUBLE)";
        String p = "CAST(" + in.get(1) + " AS VARCHAR)";
        String pairs = "list_distinct(list(struct_pack(p := " + p + ", v := " + x + ")) FILTER (WHERE " + x + " IS NOT NULL AND " + p
                + " IS NOT NULL))";
        String sum = "list_sum(list_transform(" + pairs + ", e -> e.v))";
        String count = "count(DISTINCT CASE WHEN " + x + " IS NOT NULL THEN " + p + " END)";
        return switch (name) {
            case "count_once" -> List.of(count);
            case "sum_once" -> List.of(sum);
            default -> List.of(sum, count);
        };
    }

    @Override
    public Object fromSql(Object[] r) {
        Double a = r[0] instanceof Number n ? n.doubleValue() : null;
        if ("avg_once".equals(name)) {
            double c = r[1] instanceof Number n ? n.doubleValue() : 0;
            return new Final(a == null || c == 0 ? null : a / c);
        }
        if ("count_once".equals(name)) {
            return new Final(a == null ? 0.0 : a);
        }
        return new Final(a);
    }
}
