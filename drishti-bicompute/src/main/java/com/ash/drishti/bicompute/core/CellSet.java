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
package com.ash.drishti.bicompute.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A declarative set of cell keys (ARCHITECTURE.md section 23.4.3): the cells made by grouping the view's rows by {@code grouping}, restricted to
 * one parent ({@code at}: the node being expanded, every key carries these coordinates), less the members of {@code exclude} (the Others of
 * a top N), each key carrying {@code marks} (the Others coordinate). A provider decides how to evaluate it: the tabular provider compiles
 * sets that share a restriction into one {@code GROUP BY GROUPING SETS} query; a set-based provider maps each key to its members.
 *
 * @param grouping the dimension ids grouped (row path dimensions, then column path dimensions); empty for the grand total
 * @param at the restriction: dimension id to value; every one is also in {@code grouping} or {@code marks}-free
 * @param exclude dimension id to the values left out (Others: the top N of the level)
 * @param marks coordinates added to every key of the set (Others: the level's dimension to {@link CellKey#OTHERS})
 */
public record CellSet(List<String> grouping, Map<String, String> at, Map<String, List<String>> exclude, Map<String, String> marks) {

    public CellSet {
        grouping = List.copyOf(grouping);
        at = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(at == null ? Map.of() : at));
        exclude = java.util.Collections.unmodifiableMap(new TreeMap<>(exclude == null ? Map.of() : exclude));
        marks = java.util.Collections.unmodifiableMap(new TreeMap<>(marks == null ? Map.of() : marks));
    }

    public static CellSet of(List<String> grouping, Map<String, String> at) {
        return new CellSet(grouping, at, Map.of(), Map.of());
    }

    /** The dimensions the restriction names, in order. */
    public List<String> atDims() {
        return new ArrayList<>(at.keySet());
    }

    /** True when the set's rows are all of the table's rows under the view's filters (no restriction, no exclusion). */
    public boolean unrestricted() {
        return at.isEmpty() && exclude.isEmpty();
    }

    /** The canonical text: grouping, restriction, exclusion and marks (part of every cache key). */
    public String signature() {
        StringBuilder b = new StringBuilder("g=").append(String.join(",", new java.util.TreeSet<>(grouping)));
        TreeMap<String, String> a = new TreeMap<>(at);
        b.append(";at=");
        a.forEach((k, v) -> b.append(k).append('=').append(v).append(','));
        b.append(";ex=");
        exclude.forEach((k, v) -> b.append(k).append("!=").append(String.join("\u0001", new java.util.TreeSet<>(v))).append(','));
        b.append(";mk=");
        marks.forEach((k, v) -> b.append(k).append('=').append(v).append(','));
        return b.toString();
    }

    /** The key of a row of this set: its grouped values and its marks. */
    public CellKey key(Map<String, String> grouped) {
        Map<String, String> m = new TreeMap<>(grouped);
        m.putAll(marks);
        return CellKey.of(m);
    }
}
