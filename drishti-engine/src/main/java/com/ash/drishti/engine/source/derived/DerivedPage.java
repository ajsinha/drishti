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
package com.ash.drishti.engine.source.derived;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a derived kind works out over a group's page once its aggregates and rows are in (docs/guides/PACK_DEVELOPER_GUIDE.md
 * Part III): ratios, weighted averages and picks ({@code then:}, expressions over the page), and the group's own hierarchy
 * ({@code tree:}, built from each row's parent). Declared beside the kind's other settings:
 *
 * <pre>
 * country-group:
 *   fields:
 *     GDP_USD_BN: sum $.GDP_USD_BN
 *     GROWTH_WEIGHTED: sum $.GROWTH == null ? null : $.GDP_USD_BN * $.GROWTH
 *     GROWTH_WEIGHT: sum $.GROWTH == null ? null : $.GDP_USD_BN
 *   then:                        # over the page: the aggregates, the rows and the key ($.id); not one another
 *     GROWTH: $.GROWTH_WEIGHT > 0 ? $.GROWTH_WEIGHTED / $.GROWTH_WEIGHT : null
 *     BY_INCOME: group($.ROWS, 'INCOME')
 * lei-group:
 *   tree:                        # the rows as a tree under the group's key: each row's LEVEL, the nested TREE and the deepest LEVELS
 *     id: LEI                    # the row column holding a member's id
 *     parent: PARENT             # the row column holding its parent's id; a parent outside the group hangs the row under the key
 *   sort:                        # a list field of the page in order: COLUMN, asc or desc (the default), and at most N rows
 *     BY_COUNTRY: value desc 20
 *     ROWS: REVENUE desc
 * </pre>
 *
 * Every {@code then} field reads the page as the aggregates and rows left it, so their order does not matter; a field that fails to
 * evaluate is empty, never the page. The tree comes first, then the {@code then} fields, then the sorts; rows without the sort
 * column go last, ties keep their order.
 */
final class DerivedPage {

    /** The tree a kind builds from its rows: the id and parent columns, and where the results go. */
    record Tree(String id, String parent, String field, String level, String levels) {}

    /** One sort of a list field: the column, the direction and the most rows kept (0: all). */
    record Sort(String column, boolean descending, int limit) {}

    private final Map<String, Expr> then;
    private final Map<String, Sort> sorts;
    private final Tree tree;
    private final String rowsField;
    private final Formats formats;

    private DerivedPage(Map<String, Expr> then, Map<String, Sort> sorts, Tree tree, String rowsField, Formats formats) {
        this.then = then;
        this.sorts = sorts;
        this.tree = tree;
        this.rowsField = rowsField;
        this.formats = formats;
    }

    /** The {@code then} and {@code tree} settings of a kind ({@code p} its prefix, such as {@code lei-group.}); null when it has neither. */
    static DerivedPage parse(String kind, String p, Map<String, String> settings, String rowsField, ElCompiler el, Formats formats) {
        Map<String, Expr> then = new TreeMap<>();
        String tp = p + "then.";
        settings.forEach((k, v) -> {
            if (k.startsWith(tp)) {
                String name = k.substring(tp.length());
                try {
                    then.put(name, el.compile(v));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(kind + ".then." + name + ": '" + v + "' is not an expression: " + e.getMessage(), e);
                }
            }
        });
        Map<String, Sort> sorts = new TreeMap<>();
        String sp = p + "sort.";
        settings.forEach((k, v) -> {
            if (k.startsWith(sp)) {
                String name = k.substring(sp.length());
                String[] w = v.trim().split("\\s+");
                if (w.length == 0 || w[0].isBlank() || w.length > 3) {
                    throw new IllegalArgumentException(kind + ".sort." + name + ": '" + v + "' must be COLUMN [asc|desc] [N]");
                }
                boolean desc = true;
                int limit = 0;
                for (int i = 1; i < w.length; i++) {
                    if (w[i].equalsIgnoreCase("asc") || w[i].equalsIgnoreCase("desc")) {
                        desc = w[i].equalsIgnoreCase("desc");
                    } else {
                        try {
                            limit = Integer.parseInt(w[i]);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException(kind + ".sort." + name + ": '" + v + "' must be COLUMN [asc|desc] [N]", e);
                        }
                    }
                }
                sorts.put(name, new Sort(w[0], desc, Math.max(0, limit)));
            }
        });
        Tree tree = null;
        String id = settings.get(p + "tree.id");
        String parent = settings.get(p + "tree.parent");
        if (id != null || parent != null) {
            if (id == null || parent == null || id.isBlank() || parent.isBlank()) {
                throw new IllegalArgumentException(kind + ".tree: needs both id and parent (the row columns of a member's id and its parent's)");
            }
            tree = new Tree(id.trim(), parent.trim(), settings.getOrDefault(p + "tree.field", "TREE"), settings.getOrDefault(p + "tree.level", "LEVEL"),
                    settings.getOrDefault(p + "tree.levels", "LEVELS"));
        }
        return then.isEmpty() && tree == null && sorts.isEmpty() ? null : new DerivedPage(Map.copyOf(then), Map.copyOf(sorts), tree, rowsField, formats);
    }

    /** The field the tree goes to, or null when the kind builds none. */
    String treeField() {
        return tree == null ? null : tree.field();
    }

    /** The {@code then} field names, in order (for the explanation of a page and the tests). */
    Set<String> thenFields() {
        return new java.util.TreeSet<>(then.keySet());
    }

    /** The {@code then} expression of a field as written, or null. */
    Expr thenExpr(String field) {
        return then.get(field);
    }

    /** Lays the tree and the {@code then} fields over a built page (the map the page is made from). */
    void finish(String key, Map<String, Object> doc) {
        if (tree != null) {
            tree(key, doc);
        }
        if (!then.isEmpty()) {
            then(doc);
        }
        sorts.forEach((name, s) -> sort(doc, name, s));
    }

    private void then(Map<String, Object> doc) {
        DataNode page = DataNode.of(doc);
        EvalContext c = EvalContext.of(page, formats);
        Map<String, Object> out = new LinkedHashMap<>();
        then.forEach((name, e) -> {
            Object v;
            try {
                v = Values.simplify(e.eval(c));
            } catch (RuntimeException ex) {
                v = null;                                   // one odd page never fails the kind
            }
            if (v instanceof Double d) {
                v = Double.isNaN(d) || Double.isInfinite(d) ? null : Values.normalise(d);
            }
            out.put(name, v instanceof DataNode n ? (n.isNull() ? null : n.unwrap()) : v);
        });
        doc.putAll(out);
    }

    @SuppressWarnings("unchecked")
    private static void sort(Map<String, Object> doc, String name, Sort s) {
        Object raw = doc.get(name);
        if (!(raw instanceof List<?> list)) {
            return;
        }
        List<Object> rows = new ArrayList<>();
        for (Object r : list) {
            rows.add(r instanceof DataNode n ? n.unwrap() : r);
        }
        java.util.Comparator<Object> by = (a, b) -> {
            Object x = a instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(s.column()) : null;
            Object y = b instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(s.column()) : null;
            if (x == null || y == null) {
                return x == null ? (y == null ? 0 : 1) : -1;   // rows without the column go last
            }
            int c = x instanceof Number p && y instanceof Number q ? Double.compare(p.doubleValue(), q.doubleValue())
                    : String.valueOf(x).compareTo(String.valueOf(y));
            return s.descending() ? -c : c;
        };
        rows.sort(by);
        doc.put(name, s.limit() > 0 && rows.size() > s.limit() ? new ArrayList<>(rows.subList(0, s.limit())) : rows);
    }

    @SuppressWarnings("unchecked")
    private void tree(String key, Map<String, Object> doc) {
        Object raw = doc.get(rowsField);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object r : list) {
                Object plain = r instanceof DataNode n ? n.unwrap() : r;
                rows.add(plain instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>());
            }
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object id = r.get(tree.id());
            if (id != null) {
                byId.putIfAbsent(String.valueOf(id), r);
            }
        }
        Map<String, Integer> level = new HashMap<>();
        int deepest = 0;
        for (String id : byId.keySet()) {
            deepest = Math.max(deepest, levelOf(key, id, byId, level, new HashSet<>()));
        }
        // the nodes: each row once, its children in row order; a parent outside the group (or none) hangs a row under the key
        Map<String, Map<String, Object>> node = new LinkedHashMap<>();
        byId.forEach((id, r) -> {
            r.put(tree.level(), level.get(id));
            Map<String, Object> n = new LinkedHashMap<>(r);
            node.put(id, n);
        });
        Map<String, Object> root = node.computeIfAbsent(key, k -> {
            Map<String, Object> made = new LinkedHashMap<>();
            made.put(tree.id(), k);
            made.put(tree.level(), 0);
            return made;
        });
        for (Map.Entry<String, Map<String, Object>> e : node.entrySet()) {
            String id = e.getKey();
            if (id.equals(key)) {
                continue;
            }
            Object p = byId.get(id) == null ? null : byId.get(id).get(tree.parent());
            String parent = p == null || !node.containsKey(String.valueOf(p)) || String.valueOf(p).equals(id) || cyclic(key, id, byId)
                    ? key : String.valueOf(p);
            ((List<Object>) node.get(parent).computeIfAbsent("CHILDREN", x -> new ArrayList<>())).add(e.getValue());
        }
        doc.put(rowsField, rows);
        doc.put(tree.field(), List.of(root));
        doc.put(tree.levels(), deepest);
    }

    /** A row's depth under the key: the key 0, a row whose parent is outside the group 1, any other its parent's plus one. */
    private int levelOf(String key, String id, Map<String, Map<String, Object>> byId, Map<String, Integer> memo, Set<String> path) {
        Integer known = memo.get(id);
        if (known != null) {
            return known;
        }
        int out;
        if (id.equals(key)) {
            out = 0;
        } else {
            Object p = byId.get(id).get(tree.parent());
            String parent = p == null ? null : String.valueOf(p);
            if (parent == null || parent.equals(key) || !byId.containsKey(parent) || !path.add(id)) {
                out = 1;                                    // under the key: its parent is the key, outside the group, or a cycle
            } else {
                out = levelOf(key, parent, byId, memo, path) + 1;
            }
        }
        memo.put(id, out);
        return out;
    }

    /** True when following parents from {@code id} comes back to it before reaching the key or leaving the group. */
    private boolean cyclic(String key, String id, Map<String, Map<String, Object>> byId) {
        Set<String> seen = new HashSet<>();
        String at = id;
        while (at != null && !at.equals(key) && byId.containsKey(at)) {
            if (!seen.add(at)) {
                return true;
            }
            Object p = byId.get(at).get(tree.parent());
            at = p == null ? null : String.valueOf(p);
        }
        return false;
    }
}
