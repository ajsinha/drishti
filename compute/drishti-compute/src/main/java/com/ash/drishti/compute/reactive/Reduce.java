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
package com.ash.drishti.compute.reactive;

import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.compute.ConfigValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the answers of a fanned-out request's chunks become one ({@code engine.reduce}): the fields listed are added up (numbers, or lists
 * of numbers element by element: P&amp;L paths) over the rows that agree on every other field. For rows, {@code sum: [...]}; for each
 * table, {@code tables: { <name>: [...] }}. A computation that is a sum over its rows (a scope's P&amp;L path is the sum of its
 * positions') thus runs on every worker of the pool and still answers as if one had computed it. Fields that cannot be added up (the
 * worst day of a path) are worked out again from the gathered tables with {@code then: { WORST: min:total.PNL }} ({@code min}, {@code max},
 * {@code sum} or {@code count} of a table's column), and are not part of what rows must agree on.
 *
 * @param rowSums the row fields added up
 * @param tableSums per table, the fields added up
 * @param then row fields worked out from the gathered tables: field to {@code op:table.column}
 */
public record Reduce(Set<String> rowSums, Map<String, Set<String>> tableSums, Map<String, String> then) {

    /** Reads {@code engine.reduce}; null when absent. */
    static Reduce parse(ConfigValues c) {
        if (c.keys().isEmpty()) {
            return null;
        }
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        ConfigValues t = c.section("tables");
        for (String name : t.keys()) {
            tables.put(name, Set.copyOf(t.list(name)));
        }
        Map<String, String> then = new LinkedHashMap<>();
        ConfigValues d = c.section("then");
        for (String f : d.keys()) {
            String rule = d.text(f, "");
            if (!rule.matches("(min|max|sum|count):[A-Za-z0-9._-]+\\.[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("engine.reduce.then." + f + " is min, max, sum or count of a table's column (min:total.PNL), not '" + rule + "'");
            }
            then.put(f, rule);
        }
        return new Reduce(Set.copyOf(c.list("sum")), tables, then);
    }

    EngineResult apply(EngineResult r) {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        r.tables().forEach((name, rows) -> tables.put(name, tableSums.containsKey(name) ? sum(rows, tableSums.get(name), Set.of()) : rows));
        List<Map<String, Object>> rows = rowSums.isEmpty() && then.isEmpty() ? r.rows() : sum(r.rows(), rowSums, then.keySet());
        if (!then.isEmpty()) {
            for (Map<String, Object> row : rows) {
                then.forEach((f, rule) -> row.put(f, derive(rule, tables)));
            }
        }
        return new EngineResult(rows, tables, r.meta());
    }

    private static Object derive(String rule, Map<String, List<Map<String, Object>>> tables) {
        String op = rule.substring(0, rule.indexOf(':'));
        String ref = rule.substring(rule.indexOf(':') + 1);
        int dot = ref.lastIndexOf('.');
        List<Map<String, Object>> t = tables.getOrDefault(ref.substring(0, dot), List.of());
        String col = ref.substring(dot + 1);
        double acc = op.equals("min") ? Double.POSITIVE_INFINITY : op.equals("max") ? Double.NEGATIVE_INFINITY : 0;
        long n = 0;
        for (Map<String, Object> row : t) {
            if (row.get(col) instanceof Number x) {
                double v = x.doubleValue();
                acc = switch (op) {
                    case "min" -> Math.min(acc, v);
                    case "max" -> Math.max(acc, v);
                    default -> acc + v;
                };
                n++;
            }
        }
        if (op.equals("count")) {
            return n;
        }
        return n == 0 ? null : acc;
    }

    private static List<Map<String, Object>> sum(List<Map<String, Object>> rows, Set<String> fields, Set<String> ignored) {
        Map<List<Object>, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> key = new ArrayList<>();
            row.forEach((k, v) -> {
                if (!fields.contains(k) && !ignored.contains(k)) {
                    key.add(k);
                    key.add(v);
                }
            });
            Map<String, Object> acc = groups.get(key);
            if (acc == null) {
                groups.put(key, new LinkedHashMap<>(row));
                continue;
            }
            for (String f : fields) {
                acc.put(f, add(acc.get(f), row.get(f)));
            }
        }
        return new ArrayList<>(groups.values());
    }

    private static Object add(Object a, Object b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a instanceof Number x && b instanceof Number y) {
            if ((x instanceof Long || x instanceof Integer) && (y instanceof Long || y instanceof Integer)) {
                return x.longValue() + y.longValue();
            }
            return x.doubleValue() + y.doubleValue();
        }
        if (a instanceof List<?> x && b instanceof List<?> y && x.size() == y.size()) {
            List<Object> out = new ArrayList<>(x.size());
            for (int i = 0; i < x.size(); i++) {
                out.add(add(x.get(i), y.get(i)));
            }
            return out;
        }
        return a;
    }
}
