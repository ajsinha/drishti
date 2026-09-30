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
package com.ash.drishti.engine.history;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Field-by-field difference between two versions of a document (two business dates, or two "known at" times).
 * Objects are compared by field; arrays of objects that carry a key are matched by it, so an inserted cashflow
 * does not make every later one look changed. The key is the first of {@code id}, a field ending in {@code Id},
 * then a natural key ({@code tenor}, {@code date}, {@code code}, …) that is present and unique in every element;
 * other arrays are compared by position. Leaves are compared by value, and
 * numbers also report their difference (to ten significant digits, so binary noise never shows). Bookkeeping ({@code _meta}) is ignored. Pure and thread-safe.
 */
public final class DocumentDiff {

    /** What happened to one leaf. */
    public enum Kind { ADDED, REMOVED, CHANGED }

    /**
     * @param path readable path ({@code legs[FIXED].rate}, {@code cashflows[3].amount})
     * @param kind added, removed or changed
     * @param before the earlier value (null when added)
     * @param after the later value (null when removed)
     * @param delta after minus before, when both are numbers
     */
    public record Change(String path, Kind kind, Object before, Object after, Double delta) {}

    /**
     * @param changes the changes, in document order, at most {@code limit}
     * @param added number of leaves added
     * @param removed number of leaves removed
     * @param changed number of leaves changed
     * @param truncated true when there were more than {@code limit} changes
     */
    public record Result(List<Change> changes, int added, int removed, int changed, boolean truncated) {}

    /** Natural keys, in preference order, for arrays without an identifier (not display labels, which often embed values). */
    static final List<String> NATURAL_KEYS = List.of("key", "code", "tenor", "date", "month", "quarter", "year", "period", "name",
            "symbol", "currency", "stage", "scope", "bucket", "category", "sector", "region", "type");

    private final int limit;

    public DocumentDiff(int limit) {
        this.limit = limit;
    }

    public Result diff(DataNode before, DataNode after) {
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        flatten(before, "", a);
        flatten(after, "", b);
        List<Change> out = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int changed = 0;
        for (Map.Entry<String, Object> e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                added++;
                add(out, new Change(e.getKey(), Kind.ADDED, null, e.getValue(), null));
            } else {
                Object old = a.get(e.getKey());
                if (!same(old, e.getValue())) {
                    changed++;
                    add(out, new Change(e.getKey(), Kind.CHANGED, old, e.getValue(), delta(old, e.getValue())));
                }
            }
        }
        for (Map.Entry<String, Object> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) {
                removed++;
                add(out, new Change(e.getKey(), Kind.REMOVED, e.getValue(), null, null));
            }
        }
        return new Result(List.copyOf(out), added, removed, changed, added + removed + changed > limit);
    }

    private void add(List<Change> out, Change c) {
        if (out.size() < limit) {
            out.add(c);
        }
    }

    private static void flatten(DataNode n, String path, Map<String, Object> into) {
        if (n == null || n.isMissing()) {
            return;
        }
        switch (n.type()) {
            case OBJECT -> {
                if (n instanceof DataNode.Obj o) {
                    o.fields().forEach((k, v) -> {
                        if (!(path.isEmpty() && k.equals("_meta"))) {
                            flatten(v, path.isEmpty() ? k : path + "." + k, into);
                        }
                    });
                }
            }
            case ARRAY -> {
                String key = identifier(n);
                for (int i = 0; i < n.size(); i++) {
                    DataNode el = n.get(i);
                    String at = key == null ? String.valueOf(i) : el.get(key).asText();
                    flatten(el, path + "[" + at + "]", into);
                }
                if (n.size() == 0) {
                    into.put(path, List.of());
                }
            }
            default -> into.put(path, n.unwrap());
        }
    }

    /** The field that identifies the elements of an array of objects, or null to compare by position. */
    static String identifier(DataNode arr) {
        if (arr.size() == 0 || arr.get(0).type() != NodeType.OBJECT || !(arr.get(0) instanceof DataNode.Obj first)) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        if (first.fields().containsKey("id")) {
            candidates.add("id");
        }
        first.fields().keySet().stream().filter(k -> k.endsWith("Id") && !k.equals("id")).forEach(candidates::add);
        NATURAL_KEYS.stream().filter(first.fields()::containsKey).forEach(candidates::add);
        for (String c : candidates) {
            Set<String> seen = new HashSet<>();
            boolean ok = true;
            for (int i = 0; i < arr.size() && ok; i++) {
                DataNode v = arr.get(i).get(c);
                ok = v.type() == NodeType.STRING || v.type() == NodeType.NUMBER;
                ok = ok && seen.add(v.asText());
            }
            if (ok) {
                return c;
            }
        }
        return null;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return Objects.equals(a, b);
    }

    private static Double delta(Object a, Object b) {
        if (!(a instanceof Number x && b instanceof Number y)) {
            return null;
        }
        double d = y.doubleValue() - x.doubleValue();
        return Double.isFinite(d) ? new java.math.BigDecimal(d).round(new java.math.MathContext(10)).doubleValue() : null;
    }
}
