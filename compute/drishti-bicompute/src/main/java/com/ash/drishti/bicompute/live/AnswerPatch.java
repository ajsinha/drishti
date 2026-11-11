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
package com.ash.drishti.bicompute.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The difference between two answers of one view (ARCHITECTURE.md section 26.7, item 5), what a live pivot pushes:
 *
 * <ul>
 *   <li>{@code none}: no cell moved (the stream says {@code status} with the time it was checked);</li>
 *   <li>{@code patch}: the same rows, columns and measures; {@code cells} the cells whose entry changed (figures, members, notes), whole,
 *       by their {@code rowKey|colKey}; {@code removed} the keys of cells that have no members any more; the new provenance id, notes,
 *       exactness and how it was computed;</li>
 *   <li>{@code reshape}: the rows, columns or measures changed (a member came or went, a date moved on): {@code answer} is the whole new
 *       answer.</li>
 * </ul>
 *
 * {@link #apply} applied to the old answer gives the new one's cells exactly (the engine's tests prove a live recompute equals a fresh query
 * this way). The screen applies a patch in place and redraws only the cells it names.
 */
public final class AnswerPatch {

    /** The answer's keys a patch carries besides the cells (they describe the whole answer and change with every computation). */
    static final List<String> CARRIED = List.of("provenance", "provenanceShared", "notes", "exactness", "computedAs", "computed", "version", "date");

    private AnswerPatch() {}

    /** The difference from {@code old} to {@code fresh}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> diff(Map<String, Object> old, Map<String, Object> fresh) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (old == null || !shape(old).equals(shape(fresh))) {
            out.put("kind", "reshape");
            out.put("answer", fresh);
            return out;
        }
        Map<String, Object> before = (Map<String, Object>) old.getOrDefault("cells", Map.of());
        Map<String, Object> after = (Map<String, Object>) fresh.getOrDefault("cells", Map.of());
        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : after.entrySet()) {
            if (!Objects.equals(before.get(e.getKey()), e.getValue())) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        List<String> removed = new ArrayList<>();
        for (String k : before.keySet()) {
            if (!after.containsKey(k)) {
                removed.add(k);
            }
        }
        boolean same = changed.isEmpty() && removed.isEmpty();
        out.put("kind", same ? "none" : "patch");
        out.put("cells", changed);
        out.put("removed", removed);
        out.put("changed", changed.size());
        for (String k : CARRIED) {
            if (fresh.containsKey(k)) {
                out.put(k, fresh.get(k));
            }
        }
        return out;
    }

    /** The old answer with a patch applied (a reshape: its answer). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> apply(Map<String, Object> old, Map<String, Object> patch) {
        if ("reshape".equals(patch.get("kind"))) {
            return (Map<String, Object>) patch.get("answer");
        }
        Map<String, Object> out = new LinkedHashMap<>(old);
        if ("none".equals(patch.get("kind"))) {
            return out;
        }
        Map<String, Object> cells = new LinkedHashMap<>((Map<String, Object>) old.getOrDefault("cells", Map.of()));
        ((List<String>) patch.getOrDefault("removed", List.of())).forEach(cells::remove);
        cells.putAll((Map<String, Object>) patch.getOrDefault("cells", Map.of()));
        out.put("cells", cells);
        for (String k : CARRIED) {
            if (patch.containsKey(k)) {
                out.put(k, patch.get(k));
            }
        }
        return out;
    }

    /** What must stay for a patch: the rows and columns (key, label, kind, depth, others), the measures and the date. */
    @SuppressWarnings("unchecked")
    static List<Object> shape(Map<String, Object> a) {
        List<Object> s = new ArrayList<>();
        s.add(a.get("date"));
        for (String axis : List.of("rows", "columns")) {
            for (Map<String, Object> r : (List<Map<String, Object>>) a.getOrDefault(axis, List.of())) {
                s.add(List.of(String.valueOf(r.get("key")), String.valueOf(r.get("label")), String.valueOf(r.get("kind")), String.valueOf(r.get("expanded"))));
            }
            s.add("|");
        }
        for (Map<String, Object> m : (List<Map<String, Object>>) a.getOrDefault("measures", List.of())) {
            s.add(List.of(String.valueOf(m.get("id")), String.valueOf(m.get("label")), String.valueOf(m.get("exact"))));
        }
        return s;
    }
}
