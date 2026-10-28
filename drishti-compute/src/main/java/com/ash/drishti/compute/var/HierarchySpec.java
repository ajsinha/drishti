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
package com.ash.drishti.compute.var;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One hierarchy of the VaR and exposure engines (VAR_GUIDE.md section 16, 2.9.5): its id ({@code MGMT}, {@code LEGAL}, {@code LL}), its
 * title, where its nodes come from, and the scope word that names its nodes.
 *
 * <ul>
 *   <li>{@code source: risk-hierarchy}: the nodes are the {@code risk-hierarchy} rows of the hierarchy (book-based: each node lists its
 *       books; names and limits on the rows), as before 2.9.5;</li>
 *   <li>{@code source: columns}: the nodes are derived from the trades' own columns, {@code levels: "LIMIT_LETTER > DESK"}: every distinct
 *       path of values is a node, under one root. A node's members are the trades whose values start with its path (rows, not books: a
 *       letter may hold part of a book).</li>
 * </ul>
 *
 * Node ids of a column hierarchy are paths by default ({@code LL~LL-0042/DESK-RATES}); {@code ids: leaf} names a node by its own value
 * ({@code MGMT~DESK-RATES}), allowed only when every value has one parent on the date (checked at load).
 *
 * @param id the hierarchy's id, upper case ({@code [A-Z][A-Z0-9_]*})
 * @param title what the screens call it ({@code Limit letters})
 * @param source {@link #RISK_HIERARCHY} or {@link #COLUMNS}
 * @param levels the level columns, top first (a column hierarchy; empty for risk-hierarchy)
 * @param levelNames the level each column's nodes are at ({@code desk}, {@code book}); the column in lower case when not given
 * @param rootId the root node's own id ({@code BANK}: the node {@code <id>~BANK})
 * @param rootName the root node's name (the title when not given)
 * @param leafIds {@code ids: leaf}
 * @param word the scope word naming the hierarchy's nodes ({@code LL:LL-0042}), or null
 * @param limits the kind the nodes' VaR limits come from ({@code var-limit}), or null (a risk-hierarchy's own {@code LIMIT_VAR})
 */
public record HierarchySpec(String id, String title, String source, List<String> levels, List<String> levelNames, String rootId,
        String rootName, boolean leafIds, String word, String limits) {

    public static final String RISK_HIERARCHY = "risk-hierarchy";
    public static final String COLUMNS = "columns";
    /** The level of a column hierarchy's root. */
    public static final String ROOT_LEVEL = "bank";
    /** The scope words of the engines: a hierarchy's word must be none of them. */
    public static final Set<String> RESERVED = Set.of("BANK", "LE", "DIV", "DESK", "BOOK", "NODE", "POS", "TRD", "CPTY", "PORT", "BASKET",
            "WHERE", "CAPTURE", "NSET", "GROUP");
    private static final String NAME = "[A-Z][A-Z0-9_]*";

    public HierarchySpec {
        id = id == null ? "" : id.strip().toUpperCase(Locale.ROOT);
        title = title == null || title.isBlank() ? id : title.strip();
        source = source == null || source.isBlank() ? (levels == null || levels.isEmpty() ? RISK_HIERARCHY : COLUMNS)
                : source.strip().toLowerCase(Locale.ROOT);
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
    }

    /** A hierarchy read from the rows of the {@code risk-hierarchy} kind, as before 2.9.5 (no word, its rows' limits). */
    public static HierarchySpec riskHierarchy(String id, String title) {
        return new HierarchySpec(id, title, RISK_HIERARCHY, List.of(), List.of(), null, null, false, null, null);
    }

    /** The same hierarchy with another root name. */
    public HierarchySpec withRootName(String name) {
        return new HierarchySpec(id, title, source, levels, levelNames, rootId, name, leafIds, word, limits);
    }

    public boolean columns() {
        return COLUMNS.equals(source);
    }

    /** The root node's id ({@code LL~BANK}). */
    public String rootNodeId() {
        return id + "~" + rootId;
    }

    /** The node id of a path of values (the first {@code depth} of {@code values}): {@code LL~LL-0042/DESK-RATES}, or the leaf's own. */
    public String nodeId(List<String> values, int depth) {
        if (leafIds) {
            return id + "~" + values.get(depth - 1);
        }
        return id + "~" + String.join("/", values.subList(0, depth));
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
        String ids = text(m.get("ids"));
        if (ids != null && !ids.equalsIgnoreCase("leaf") && !ids.equalsIgnoreCase("path")) {
            throw new IllegalArgumentException("hierarchy " + id + ": ids is path or leaf, not " + ids);
        }
        return new HierarchySpec(id, text(m.get("title")), text(m.get("source")), levels, names, rootId, rootName,
                "leaf".equalsIgnoreCase(ids), text(m.get("word")), text(m.get("limits")));
    }

    private static Object first(Map<?, ?> m, String a, String b) {
        return m.get(a) != null ? m.get(a) : m.get(b);
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * The hierarchies read from a map of id to specification, checked: ids and words unique and well formed, no word reserved, a column
     * hierarchy with levels, a known source. {@link IllegalArgumentException} says what is wrong.
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
            if (!s.source().equals(RISK_HIERARCHY) && !s.source().equals(COLUMNS)) {
                throw new IllegalArgumentException("hierarchy " + s.id() + ": source is risk-hierarchy or columns, not " + s.source());
            }
            if (s.columns() && s.levels().isEmpty()) {
                throw new IllegalArgumentException("hierarchy " + s.id() + " is built from columns but names no levels (levels: \"A > B\")");
            }
            if (s.columns() && new LinkedHashSet<>(s.levels()).size() != s.levels().size()) {
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

    /** The default: MGMT and LEGAL from the risk-hierarchy rows, as before 2.9.5. */
    public static List<HierarchySpec> defaults() {
        return List.of(riskHierarchy("MGMT", "Management"), riskHierarchy("LEGAL", "Legal entity"));
    }

    /** A digest of the configuration (a hot file built under another is not reopened): empty for the default, so earlier files stay valid. */
    public static String digest(List<HierarchySpec> specs) {
        if (specs.stream().noneMatch(HierarchySpec::columns) && specs.stream().allMatch(s -> s.limits() == null)) {
            return "";
        }
        StringBuilder b = new StringBuilder("|hier=");
        for (HierarchySpec s : specs) {
            b.append(s.id()).append(':').append(s.source()).append(':').append(s.levels()).append(':').append(s.levelNames()).append(':')
                    .append(s.rootId()).append(':').append(s.leafIds() ? "leaf" : "path").append(':').append(s.limits()).append(';');
        }
        return b.toString();
    }

    /** Every level column of every column hierarchy, in order. */
    public static List<String> levelColumns(List<HierarchySpec> specs) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        specs.stream().filter(HierarchySpec::columns).forEach(s -> out.addAll(s.levels()));
        return List.copyOf(out);
    }
}
