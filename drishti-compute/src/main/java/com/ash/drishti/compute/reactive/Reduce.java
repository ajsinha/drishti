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
 * positions') thus runs on every worker of the pool and still answers as if one had computed it.
 *
 * @param rowSums the row fields added up
 * @param tableSums per table, the fields added up
 */
public record Reduce(Set<String> rowSums, Map<String, Set<String>> tableSums) {

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
        return new Reduce(Set.copyOf(c.list("sum")), tables);
    }

    EngineResult apply(EngineResult r) {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        r.tables().forEach((name, rows) -> tables.put(name, tableSums.containsKey(name) ? sum(rows, tableSums.get(name)) : rows));
        return new EngineResult(rowSums.isEmpty() ? r.rows() : sum(r.rows(), rowSums), tables, r.meta());
    }

    private static List<Map<String, Object>> sum(List<Map<String, Object>> rows, Set<String> fields) {
        Map<List<Object>, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> key = new ArrayList<>();
            row.forEach((k, v) -> {
                if (!fields.contains(k)) {
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
