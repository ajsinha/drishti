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
package com.ash.drishti.banking.compute.var;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One hierarchy of the VaR and exposure engines (VAR_GUIDE.md section 15.14): its id ({@code MGMT}, {@code LEGAL}, {@code LL}), its title,
 * its level columns and the scope word that names its nodes. A hierarchy is built from the trades' own <b>promoted columns</b>,
 * {@code levels: "DIVISION > DESK > TT_BOOK"}: every distinct path of values is a node, under one root. A node's members are the trades
 * whose values start with its path (rows, not books: a letter may hold part of a book).
 *
 * <p>Node ids are paths by default ({@code LL~LL-0042/DESK-RATES}); {@code ids: leaf} names a node by its own value
 * ({@code MGMT~DESK-RATES}), allowed only when every value has one parent on the date (checked at load).
 *
 * <p>A row whose level column is empty (an empty text and a missing value alike) is placed under a <b>(Blank)</b> node at that level, under
 * its real parent, the deeper levels continuing beneath it: nothing is dropped. A (Blank) node's own value is {@link #BLANK} and its id is
 * always a path ({@code MGMT~DIV-MARKETS/~}), even in an {@code ids: leaf} hierarchy, so two (Blank) desks under two divisions never
 * collide. Its name is the hierarchy's {@code empty-label} (the server's {@code drishti.var.hierarchy.empty-label} when not set).
 *
 * @param id the hierarchy's id, upper case ({@code [A-Z][A-Z0-9_]*})
 * @param title what the screens call it ({@code Limit letters})
 * @param levels the level columns, top first
 * @param levelNames the level each column's nodes are at ({@code desk}, {@code book}); the column in lower case when not given. A level's
 *     name is also the reference kind its nodes' display names are read from ({@code desk}, {@code book}, {@code legal-entity})
 * @param rootId the root node's own id ({@code BANK}: the node {@code <id>~BANK})
 * @param rootName the root node's name (the title when not given)
 * @param leafIds {@code ids: leaf}
 * @param word the scope word naming the hierarchy's nodes ({@code LL:LL-0042}), or null
 * @param limits the kind the nodes' VaR limits come from ({@code var-limit}), or null (no limits)
 * @param emptyLabel the name of a (Blank) node, or null (the server's default, else {@link #DEFAULT_EMPTY_LABEL})
 */
public record HierarchySpec(String id, String title, List<String> levels, List<String> levelNames, String rootId, String rootName,
        boolean leafIds, String word, String limits, String emptyLabel) {

    /** The level of a hierarchy's root. */
    public static final String ROOT_LEVEL = "bank";
    /** The value a (Blank) node stands for in its path id: reserved, never a real level value. */
    public static final String BLANK = "~";
    /** The name of a (Blank) node when neither the hierarchy nor the server sets one: Power BI's. */
    public static final String DEFAULT_EMPTY_LABEL = "(Blank)";
    /** The scope words of the engines: a hierarchy's word must be none of them. */
    public static final Set<String> RESERVED = Set.of("BANK", "LE", "DIV", "DESK", "BOOK", "NODE", "POS", "TRD", "CPTY", "PORT", "BASKET",
            "WHERE", "CAPTURE", "NSET", "GROUP");
    private static final String NAME = "[A-Z][A-Z0-9_]*";

    public HierarchySpec {
        id = id == null ? "" : id.strip().toUpperCase(Locale.ROOT);
        title = title == null || title.isBlank() ? id : title.strip();
        levels = levels == null ? List.of() : levels.stream().map(s -> s.strip().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            String n = levelNames != null && i < levelNames.size() && levelNames.get(i) != null && !levelNames.get(i).isBlank()
                    ? levelNames.get(i).strip() : levels.get(i).toLowerCase(Locale.ROOT).replace('_', '-');
            names.add(n);
        }
        levelNames = List.copyOf(names);
        rootId = rootId == null || rootId.isBlank() ? "BANK" : rootId.strip();
        rootName = rootName == null || rootName.isBlank() ? title : rootName.strip();
        word = word == null || word.isBlank() ? null : word.strip().toUpperCase(Locale.ROOT);
        limits = limits == null || limits.isBlank() ? null : limits.strip();
        emptyLabel = emptyLabel == null || emptyLabel.isBlank() ? null : emptyLabel.strip();
    }

    /** True when a level value is empty (an empty or blank text and a missing value alike): the row goes under a (Blank) node. */
    public static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** The name of this hierarchy's (Blank) nodes: its own {@code empty-label}, else {@link #DEFAULT_EMPTY_LABEL}. */
    public String blankName() {
        return emptyLabel == null ? DEFAULT_EMPTY_LABEL : emptyLabel;
    }

    /** The same hierarchy with the server's (Blank) name when it sets none of its own. */
    public HierarchySpec withDefaultEmptyLabel(String label) {
        return emptyLabel != null || label == null || label.isBlank() ? this
                : new HierarchySpec(id, title, levels, levelNames, rootId, rootName, leafIds, word, limits, label);
    }

    /** The same hierarchy with another root name. */
    public HierarchySpec withRootName(String name) {
        return new HierarchySpec(id, title, levels, levelNames, rootId, name, leafIds, word, limits, emptyLabel);
    }

    /** The root node's id ({@code LL~BANK}). */
    public String rootNodeId() {
        return id + "~" + rootId;
    }

    /**
     * The node id of a path of values (the first {@code depth} of {@code values}, an empty one {@link #blank}): {@code LL~LL-0042/DESK-RATES},
     * or the leaf's own ({@code ids: leaf}); a (Blank) node's is always its path, each empty value written {@link #BLANK}
     * ({@code MGMT~DIV-MARKETS/~}).
     */
    public String nodeId(List<String> values, int depth) {
        String own = values.get(depth - 1);
        if (leafIds && !blank(own)) {
            return id + "~" + own;
        }
        StringBuilder b = new StringBuilder(id).append('~');
        for (int i = 0; i < depth; i++) {
            b.append(i == 0 ? "" : "/").append(blank(values.get(i)) ? BLANK : values.get(i));
        }
        return b.toString();
    }

    /** True when a node id is a (Blank) node's (its own value {@link #BLANK}). */
    public static boolean blankNode(String nodeId) {
        return nodeId != null && (nodeId.endsWith("/" + BLANK) || nodeId.endsWith("~" + BLANK));
    }

    /** The specification read from a map (a pack's {@code hierarchies:} entry or the {@code drishti.var.hierarchies} setting). */
    public static HierarchySpec of(String id, Map<?, ?> m) {
        Object lv = m.get("levels");
        List<String> levels = new ArrayList<>();
        if (lv instanceof String s) {
            for (String x : s.split(">")) {
                if (!x.isBlank()) {
                    levels.add(x.strip());
                }
            }
        } else if (lv instanceof List<?> l) {
            l.forEach(x -> levels.add(String.valueOf(x)));
        } else if (lv instanceof Map<?, ?> mm) {                          // a list bound by Spring from indexed keys (levels[0]...)
            new java.util.TreeMap<>(mm).values().forEach(x -> levels.add(String.valueOf(x)));
        }
        List<String> names = new ArrayList<>();
        Object ln = first(m, "level-names", "levelNames");
        if (ln instanceof String s) {
            for (String x : s.split("[>,]")) {
                names.add(x.strip());
            }
        } else if (ln instanceof List<?> l) {
            l.forEach(x -> names.add(String.valueOf(x)));
        } else if (ln instanceof Map<?, ?> mm) {
            new java.util.TreeMap<>(mm).values().forEach(x -> names.add(String.valueOf(x)));
        }
        String rootId = null;
        String rootName = null;
        Object root = m.get("root");
        if (root instanceof Map<?, ?> r) {
            rootId = text(r.get("id"));
            rootName = text(r.get("name"));
        } else if (root != null) {
            rootId = text(root);
        }
        if (m.get("source") != null) {
            throw new IllegalArgumentException("hierarchy " + id + ": source is not a setting (the risk-hierarchy kind is removed): a "
                    + "hierarchy is built from the promoted columns its levels name (levels: \"DIVISION > DESK > TT_BOOK\")");
        }
        String ids = text(m.get("ids"));
        if (ids != null && !ids.equalsIgnoreCase("leaf") && !ids.equalsIgnoreCase("path")) {
            throw new IllegalArgumentException("hierarchy " + id + ": ids is path or leaf, not " + ids);
        }
        return new HierarchySpec(id, text(m.get("title")), levels, names, rootId, rootName, "leaf".equalsIgnoreCase(ids), text(m.get("word")),
                text(m.get("limits")), text(first(m, "empty-label", "emptyLabel")));
    }

    private static Object first(Map<?, ?> m, String a, String b) {
        return m.get(a) != null ? m.get(a) : m.get(b);
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * The hierarchies read from a map of id to specification, checked: ids and words unique and well formed, no word reserved, every
     * hierarchy with levels. {@link IllegalArgumentException} says what is wrong.
     */
    public static List<HierarchySpec> parse(Map<?, ?> all) {
        List<HierarchySpec> out = new ArrayList<>();
        if (all != null) {
            all.forEach((k, v) -> {
                if (!(v instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException("hierarchy " + k + ": not a map of settings (title, levels, word, ...)");
                }
                out.add(of(String.valueOf(k), m));
            });
        }
        return check(out);
    }

    /** Checks a list of hierarchies (see {@link #parse}); returns it. */
    public static List<HierarchySpec> check(List<HierarchySpec> specs) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, String> words = new LinkedHashMap<>();
        for (HierarchySpec s : specs) {
            if (!s.id().matches(NAME)) {
                throw new IllegalArgumentException("hierarchy id '" + s.id() + "' is not a name (A-Z, digits, _; a letter first)");
            }
            if (!ids.add(s.id())) {
                throw new IllegalArgumentException("hierarchy " + s.id() + " is configured twice");
            }
            if (s.levels().isEmpty()) {
                throw new IllegalArgumentException("hierarchy " + s.id() + " names no levels: a hierarchy is built from the promoted columns "
                        + "its levels name (levels: \"DIVISION > DESK > TT_BOOK\")");
            }
            if (new LinkedHashSet<>(s.levels()).size() != s.levels().size()) {
                throw new IllegalArgumentException("hierarchy " + s.id() + " names a level column twice: " + s.levels());
            }
            for (String c : s.levels()) {
                if (!c.matches(NAME)) {
                    throw new IllegalArgumentException("hierarchy " + s.id() + ": the level '" + c + "' is not a column name");
                }
            }
            if (s.word() != null) {
                if (!s.word().matches(NAME)) {
                    throw new IllegalArgumentException("hierarchy " + s.id() + ": the scope word '" + s.word() + "' is not a word (A-Z, digits, _)");
                }
                if (RESERVED.contains(s.word())) {
                    throw new IllegalArgumentException("hierarchy " + s.id() + ": the scope word " + s.word() + " is the engine's own: choose another");
                }
                String was = words.put(s.word(), s.id());
                if (was != null) {
                    throw new IllegalArgumentException("the scope word " + s.word() + " names two hierarchies: " + was + " and " + s.id());
                }
            }
        }
        return List.copyOf(specs);
    }

    /** A digest of the configuration (a hot file built under another is not reopened). */
    public static String digest(List<HierarchySpec> specs) {
        if (specs.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("|hier=");
        for (HierarchySpec s : specs) {
            b.append(s.id()).append(':').append(s.levels()).append(':').append(s.levelNames()).append(':').append(s.rootId()).append(':')
                    .append(s.leafIds() ? "leaf" : "path").append(':').append(s.limits()).append(':').append(s.blankName()).append(';');
        }
        return b.toString();
    }

    /** Every level column of every hierarchy, in order. */
    public static List<String> levelColumns(List<HierarchySpec> specs) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        specs.forEach(s -> out.addAll(s.levels()));
        return List.copyOf(out);
    }
}
