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
package com.ash.drishti.engine.search;

import com.ash.drishti.api.DataNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The field names a kind is known to have, for reading the names a search writes: case never matters
 * ({@code producttype} is {@code productType}), and a name no entity of the kind has is reported with the closest
 * known names instead of silently matching nothing. Names come from the kind's promoted columns, its pack's key fields
 * and the documents a search reads; paths are dotted ({@code counterparty.name}), list positions and filters are kept as
 * written ({@code legs[0].rate} is known when {@code legs.rate} is). Immutable and thread-safe.
 */
public final class FieldNames {

    /** How deep into a document names are collected. */
    static final int MAX_DEPTH = 8;
    /** How many elements of a list are looked into for the names of their fields. */
    static final int MAX_ELEMENTS = 50;

    private final Map<String, String> byLower;

    private FieldNames(Map<String, String> byLower) {
        this.byLower = byLower;
    }

    /** Names from plain dotted paths ({@code productType}, {@code counterparty.name}, or with a leading {@code $.}). */
    public static FieldNames of(Collection<String> paths) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : paths) {
            add(m, p.startsWith("$.") ? p.substring(2) : p);
        }
        return new FieldNames(m);
    }

    /** These names and those of the given documents (every field path, at any depth up to {@link #MAX_DEPTH}). */
    public FieldNames with(Collection<DataNode> documents) {
        Map<String, String> m = new LinkedHashMap<>(byLower);
        for (DataNode d : documents) {
            walk(d, "", m, 0);
        }
        return new FieldNames(m);
    }

    public boolean isEmpty() {
        return byLower.isEmpty();
    }

    /**
     * The path as the kind spells it: {@code $.producttype} is {@code $.productType}; positions and filters are kept
     * ({@code $.Legs[0].RATE} is {@code $.legs[0].rate}). Empty when the kind has no such field.
     */
    public Optional<String> canonical(String path) {
        if (!path.startsWith("$.")) {
            return Optional.of(path);
        }
        List<String[]> segments = segments(path.substring(2));
        StringBuilder key = new StringBuilder();
        for (String[] s : segments) {
            key.append(key.isEmpty() ? "" : ".").append(s[0].toLowerCase(Locale.ROOT));
        }
        String known = byLower.get(key.toString());
        if (known == null) {
            return Optional.empty();
        }
        String[] names = known.split("\\.");
        if (names.length != segments.size()) {
            return Optional.of(path);
        }
        StringBuilder out = new StringBuilder("$");
        for (int i = 0; i < names.length; i++) {
            out.append('.').append(names[i]).append(segments.get(i)[1]);
        }
        return Optional.of(out.toString());
    }

    /** The known names closest to {@code path} (by edit distance, ignoring case), at most {@code max}, closest first. */
    public List<String> suggestions(String path, int max) {
        String wanted = (path.startsWith("$.") ? path.substring(2) : path).replaceAll("\\[[^\\]]*]", "").toLowerCase(Locale.ROOT);
        String last = wanted.substring(wanted.lastIndexOf('.') + 1);
        record Scored(String name, int distance) {}
        List<Scored> scored = new ArrayList<>();
        for (String known : new LinkedHashSet<>(byLower.values())) {
            String k = known.toLowerCase(Locale.ROOT);
            int d = Math.min(distance(wanted, k), distance(last, k.substring(k.lastIndexOf('.') + 1)) + (k.contains(".") ? 1 : 0));
            if (d <= Math.max(2, wanted.length() / 2) || k.contains(wanted) || wanted.contains(k)) {
                scored.add(new Scored(known, d));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::distance).thenComparing(Scored::name));
        return scored.stream().limit(max).map(Scored::name).toList();
    }

    /** Every name, in the order first seen (for a message when nothing is close). */
    public Set<String> names() {
        return new LinkedHashSet<>(byLower.values());
    }

    private static void add(Map<String, String> m, String dotted) {
        if (dotted.isBlank()) {
            return;
        }
        String[] parts = dotted.split("\\.");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            path.append(path.isEmpty() ? "" : ".").append(part);
            m.putIfAbsent(path.toString().toLowerCase(Locale.ROOT), path.toString());   // the prefixes are fields too
        }
    }

    private static void walk(DataNode n, String prefix, Map<String, String> m, int depth) {
        if (depth > MAX_DEPTH || n == null) {
            return;
        }
        if (n instanceof DataNode.Obj o) {
            o.fields().forEach((k, v) -> {
                String p = prefix.isEmpty() ? k : prefix + "." + k;
                m.putIfAbsent(p.toLowerCase(Locale.ROOT), p);
                walk(v, p, m, depth + 1);
            });
        } else if (n instanceof DataNode.Arr a) {
            List<DataNode> elements = a.elements();
            for (int i = 0; i < Math.min(MAX_ELEMENTS, elements.size()); i++) {
                walk(elements.get(i), prefix, m, depth + 1);
            }
        }
    }

    /** Dotted segments split outside brackets: each is {name, what follows it in brackets}. */
    static List<String[]> segments(String dotted) {
        List<String[]> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= dotted.length(); i++) {
            char c = i < dotted.length() ? dotted.charAt(i) : '.';
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '.' && depth <= 0) {
                String seg = dotted.substring(start, i);
                int b = seg.indexOf('[');
                out.add(b < 0 ? new String[] {seg, ""} : new String[] {seg.substring(0, b), seg.substring(b)});
                start = i + 1;
            }
        }
        return out;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
