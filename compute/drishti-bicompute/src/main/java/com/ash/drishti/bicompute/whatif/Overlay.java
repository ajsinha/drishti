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
package com.ash.drishti.bicompute.whatif;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A what-if overlay on a pivot's rows (ARCHITECTURE.md section 26.7, item 3): members left out, an input column scaled or overridden where a
 * condition holds, and hypothetical rows added. It is private, saved with the view, and never written to the lake: the tabular provider joins
 * it into the plan as part of the source's relation, so only the cells it touches change.
 *
 * <pre>
 * { "exclude": { "BOOK": ["B-17"] },
 *   "changes": [ { "field": "PRICE", "scale": 1.10, "where": { "DESK": ["RATES-NY"] } },
 *                { "field": "NOTIONAL_USD", "set": 0, "where": { "TRADE_ID": ["T-1001"] } } ],
 *   "add": [ { "DESK": "RATES-NY", "NOTIONAL_USD": 5000000, "PRICE": 101.2 } ] }
 * </pre>
 *
 * @param exclude field to the values whose rows are left out
 * @param changes each scales ({@code scale}) or sets ({@code set}) one numeric field where {@code where} holds (empty: every row)
 * @param add hypothetical rows (field to value; fields not given are absent)
 */
public record Overlay(Map<String, List<String>> exclude, List<Change> changes, List<Map<String, Object>> add) {

    /** The most hypothetical rows an overlay adds. */
    public static final int MAX_ROWS = 10_000;

    /**
     * One change of a numeric field.
     *
     * @param field the field
     * @param scale the factor (null: not scaled)
     * @param set the value (null: not set)
     * @param where field to the values where the change applies (empty: every row)
     */
    public record Change(String field, Double scale, Double set, Map<String, List<String>> where) {
        public Change {
            where = where == null ? Map.of() : Map.copyOf(where);
        }
    }

    public Overlay {
        exclude = exclude == null ? Map.of() : Map.copyOf(exclude);
        changes = changes == null ? List.of() : List.copyOf(changes);
        add = add == null ? List.of() : List.copyOf(add);
    }

    public boolean empty() {
        return exclude.isEmpty() && changes.isEmpty() && add.isEmpty();
    }

    /** The overlay of a request body; refused in words when it is not one. */
    @SuppressWarnings("unchecked")
    public static Overlay parse(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            throw bad("a what-if is { exclude: {FIELD: [values]}, changes: [{field, scale | set, where}], add: [{FIELD: value}] }");
        }
        Map<String, List<String>> ex = strings(m.get("exclude"));
        List<Change> ch = new ArrayList<>();
        if (m.get("changes") instanceof List<?> l) {
            for (Object x : l) {
                if (!(x instanceof Map<?, ?> c) || c.get("field") == null) {
                    throw bad("a change is { field, scale | set, where }");
                }
                Double scale = number(c.get("scale"));
                Double set = number(c.get("set"));
                if ((scale == null) == (set == null)) {
                    throw bad("a change of " + c.get("field") + " either scales (scale: 1.1) or sets (set: 0) the field");
                }
                ch.add(new Change(String.valueOf(c.get("field")), scale, set, strings(c.get("where"))));
            }
        }
        List<Map<String, Object>> add = new ArrayList<>();
        if (m.get("add") instanceof List<?> l) {
            for (Object x : l) {
                if (!(x instanceof Map<?, ?> r)) {
                    throw bad("an added row is an object of fields and values");
                }
                add.add(new LinkedHashMap<>((Map<String, Object>) r));
            }
        }
        if (add.size() > MAX_ROWS) {
            throw bad("a what-if adds at most " + MAX_ROWS + " rows");
        }
        return new Overlay(ex, ch, add);
    }

    private static Map<String, List<String>> strings(Object o) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                    : List.of(String.valueOf(v))));
        }
        return out;
    }

    private static Double number(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw bad("'" + o + "' is not a number");
        }
    }

    /** A short digest of the overlay (part of the cache key: a what-if never shares states with the data as it is). */
    public String signature() {
        String text = new java.util.TreeMap<>(exclude) + "|" + changes + "|" + add;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** As saved with a view. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exclude", exclude);
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Change c : changes) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("field", c.field());
            if (c.scale() != null) {
                x.put("scale", c.scale());
            }
            if (c.set() != null) {
                x.put("set", c.set());
            }
            x.put("where", c.where());
            cs.add(x);
        }
        m.put("changes", cs);
        m.put("add", add);
        return m;
    }

    static DrishtiException bad(String why) {
        return new DrishtiException(ErrorCode.PIVOT_EXPLAIN, why);
    }
}
