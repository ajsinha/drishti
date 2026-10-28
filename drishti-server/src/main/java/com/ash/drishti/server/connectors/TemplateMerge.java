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
package com.ash.drishti.server.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The deep additive merge of a connector's definition with its pack's template (CONNECTOR_FILES.md, "Upgrades"), for the parts that are
 * not flat settings: a reactive connector's computation ({@code kinds.<kind>.params}, {@code kinds.<kind>.result}, {@code reads},
 * {@code engine}, {@code triggers}, {@code coalesce}, {@code cache}, {@code limits}, {@code history}, at any depth). Rules:
 * <ul>
 *   <li>a key the template has and the file lacks is added, its whole value;</li>
 *   <li>a value the file has is never overwritten: the same key with a different value is a <b>conflict</b>, listed for an
 *       administrator (Admin &rarr; Connectors &rarr; Template updates: <i>use the pack's value</i>);</li>
 *   <li>lists keep the file's items and order and get the template's missing items appended, as the layout lists do; a mapping item
 *       that names the same thing as one of the file's (the same {@code kind}, {@code name}, {@code id}, {@code topic},
 *       {@code connector}, or a trigger's {@code on}) is that item, merged into it rather than appended twice (a trigger gains the
 *       template's new kinds).</li>
 * </ul>
 * Values are compared as text (a 4 in the template and "4" in a file are equal). Stateless.
 */
final class TemplateMerge {

    /** Item keys that say which thing a mapping in a list is. */
    private static final List<String> IDENTITY = List.of("kind", "name", "id", "topic", "connector", "on");

    /**
     * One difference an administrator decides.
     *
     * @param path where it is, as segments (a list item is {@code [i]})
     * @param file the file's value
     * @param template the template's value
     */
    record Conflict(List<String> path, Object file, Object template) {
        String key() {
            StringBuilder b = new StringBuilder();
            for (String s : path) {
                b.append(b.isEmpty() || s.startsWith("[") ? "" : ".").append(s);
            }
            return b.toString();
        }
    }

    /**
     * The merged definition, what was added (paths, for the log and the notice) and the conflicts.
     */
    record Result(Map<String, Object> merged, List<String> added, List<Conflict> conflicts) {
        boolean changed() {
            return !added.isEmpty();
        }
    }

    private TemplateMerge() {
    }

    /** {@code file} with every key {@code template} has and it lacks, at any depth; the file's values kept. */
    static Result merge(Map<String, Object> file, Map<String, Object> template) {
        List<String> added = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        Map<String, Object> out = mergeMaps(file == null ? Map.of() : file, template == null ? Map.of() : template, new ArrayList<>(), added, conflicts);
        return new Result(out, added, conflicts);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeMaps(Map<?, ?> file, Map<?, ?> template, List<String> path, List<String> added, List<Conflict> conflicts) {
        Map<String, Object> out = new LinkedHashMap<>();
        file.forEach((k, v) -> out.put(String.valueOf(k), v));
        template.forEach((k, tv) -> {
            String key = String.valueOf(k);
            List<String> at = with(path, key);
            if (!out.containsKey(key)) {
                out.put(key, copy(tv));
                added.add(String.join(".", at));
                return;
            }
            Object fv = out.get(key);
            if (fv instanceof Map<?, ?> fm && tv instanceof Map<?, ?> tm) {
                out.put(key, mergeMaps(fm, tm, at, added, conflicts));
            } else if (fv instanceof List<?> fl && tv instanceof List<?> tl) {
                out.put(key, mergeLists(fl, tl, at, added, conflicts));
            } else if (!same(fv, tv)) {
                conflicts.add(new Conflict(at, fv, tv));
            }
        });
        return out;
    }

    private static List<Object> mergeLists(List<?> file, List<?> template, List<String> path, List<String> added, List<Conflict> conflicts) {
        List<Object> out = new ArrayList<>(file);
        for (Object item : template) {
            if (out.stream().anyMatch(x -> same(x, item))) {
                continue;
            }
            int match = item instanceof Map<?, ?> im ? sameThing(out, im) : -1;
            if (match >= 0) {
                out.set(match, mergeMaps((Map<?, ?>) out.get(match), (Map<?, ?>) item, with(path, "[" + match + "]"), added, conflicts));
            } else {
                out.add(copy(item));
                added.add(String.join(".", path) + "[+" + String.valueOf(item) + "]");
            }
        }
        return out;
    }

    /** The index of the file's mapping that names the same thing as {@code item}, or -1. */
    private static int sameThing(List<Object> items, Map<?, ?> item) {
        for (String id : IDENTITY) {
            Object want = item.get(id);
            if (want == null) {
                continue;
            }
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof Map<?, ?> m && same(m.get(id), want)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Equal as text, recursively (numbers and their text forms are the same value). */
    static boolean same(Object a, Object b) {
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().stream().map(String::valueOf).toList().containsAll(mb.keySet().stream().map(String::valueOf).toList())
                    || ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : mb.entrySet()) {
                Object av = ma.entrySet().stream().filter(x -> String.valueOf(x.getKey()).equals(String.valueOf(e.getKey()))).map(Map.Entry::getValue)
                        .findFirst().orElse(null);
                if (!same(av, e.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!same(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a == null ? null : String.valueOf(a), b == null ? null : String.valueOf(b));
    }

    /** {@code root} with the value at {@code path} set to {@code value} (a copy; the path's maps and lists exist). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> set(Map<String, Object> root, List<String> path, Object value) {
        Map<String, Object> out = (Map<String, Object>) copy(root);
        Object cur = out;
        for (int i = 0; i < path.size(); i++) {
            String seg = path.get(i);
            boolean last = i == path.size() - 1;
            if (seg.startsWith("[") && cur instanceof List<?> l) {
                int idx = Integer.parseInt(seg.substring(1, seg.length() - 1));
                if (last) {
                    ((List<Object>) l).set(idx, copy(value));
                } else {
                    cur = l.get(idx);
                }
            } else if (cur instanceof Map<?, ?> m) {
                if (last) {
                    ((Map<String, Object>) m).put(seg, copy(value));
                } else {
                    cur = m.get(seg);
                }
            } else {
                throw new IllegalArgumentException("no " + String.join(".", path.subList(0, i + 1)) + " in the connector");
            }
        }
        return out;
    }

    private static List<String> with(List<String> path, String seg) {
        List<String> out = new ArrayList<>(path);
        out.add(seg);
        return out;
    }

    /** A deep copy of maps and lists (so the template's own structure is never shared with a file's). */
    @SuppressWarnings("unchecked")
    static Object copy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), copy(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(copy(x)));
            return out;
        }
        return v;
    }
}
