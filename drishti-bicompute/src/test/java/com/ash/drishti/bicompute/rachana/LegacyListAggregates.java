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
package com.ash.drishti.bicompute.rachana;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.rachana.el.Values;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * THE RECORD OF "BEFORE", for {@link SutraAggregateParityTest} only: Rachana's own list aggregates as they were in drishti-rachana
 * until 2.9.11.b ({@code ListFunctions.sum}, {@code ListFunctions.group}, {@code MathFunctions.lenient} over one list), copied unchanged
 * so the parity test can show every shipped Sutra draws the same figures from the engine. Test code: never on a class path that serves
 * anything, and deleted with the parity test once 2.9.11.b is released (ONE_GRAMMAR.md part 2).
 */
final class LegacyListAggregates {

    private LegacyListAggregates() {}

    static final int MAX_GROUPS = 500;

    /** The old {@code sum(list, [field])}. */
    static Object sum(List<Object> a) {
        if (!(a.get(0) instanceof DataNode.Arr arr)) {
            return 0L;
        }
        String field = a.size() > 1 ? Values.text(a.get(1)) : null;
        double total = 0;
        for (DataNode e : arr.elements()) {
            DataNode x = field == null ? e : e.get(field);
            if (x.isMasked()) {
                return Values.mask();
            }
            double v = x.asDouble();
            if (!Double.isNaN(v)) {
                total += v;
            }
        }
        return Values.normalise(total);
    }

    /** The old {@code min}/{@code max} (one list, or several arguments). */
    static Object lenient(List<Object> a, boolean min) {
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

    /** The old {@code group(list, 'key', 'value'?, 'by'?)}. */
    static Object group(List<Object> a) {
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
}
