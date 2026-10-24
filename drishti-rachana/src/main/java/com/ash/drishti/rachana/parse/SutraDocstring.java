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
package com.ash.drishti.rachana.parse;

import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.model.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code doc:} block every fragment and every Sutra carries, like a Python docstring, so a component browser or an AI agent can
 * read what it shows and how to use it (SUTRA_NEXT.md, "The doc: docstring").
 *
 * <pre>
 * doc:
 *   summary: A line chart of one series from a list of rows.        # one line, at most 120 characters
 *   description: |                                                    # what it shows, when to use it
 *     ...
 *   params:                                                           # every params: key, nothing else
 *     rows: { type: expression, default: $.POINTS, meaning: The list of rows to draw }
 *   emits: []                                                         # events it fires (none until phase 2)
 *   expects: []                                                       # what it reads from a parent (none until phase 3)
 *   examples:
 *     - { use: series-line@1, with: { rows: $.POINTS, x: TENOR, y: ZERO_RATE } }
 *   since: "2.7.0"
 * </pre>
 *
 * A fragment's doc is checked strictly (a problem is an error, {@code DRS-2055}, and the fragment is not loaded); a Sutra's and an
 * older block's are checked as warnings. Stateless and thread-safe.
 */
public final class SutraDocstring {

    /** A missing or incomplete {@code doc:}. */
    public static final String CODE = "DRS-2055";
    /** The kinds of value a parameter may hold. */
    public static final Set<String> TYPES = Set.of("text", "expression", "field", "format", "key", "area", "id", "number", "boolean", "kind", "columns");
    /** The longest summary line. */
    public static final int SUMMARY_MAX = 120;

    private SutraDocstring() {}

    /**
     * A fragment's doc, checked strictly: summary, description, params (each declared parameter with type, default and meaning, its
     * default the declared one, nothing undeclared), emits and expects (lists, empty allowed), examples (at least one, each a use of
     * this fragment with declared parameters only) and since.
     */
    public static List<SutraProblem> checkFragment(PNode root, String name, Map<String, String> params, String file) {
        List<SutraProblem> out = new ArrayList<>();
        PNode doc = root == null ? null : root.map().get("doc");
        if (doc == null || !doc.isMap()) {
            out.add(problem("fragment " + name + " has no doc: block (summary, description, params, emits, expects, examples, since)", file,
                    root == null ? 1 : root.line(), 1));
            return out;
        }
        Map<String, PNode> d = doc.map();
        common(d, doc, "fragment " + name, file, out);
        PNode ps = d.get("params");
        if (!params.isEmpty() && (ps == null || !ps.isMap())) {
            out.add(problem("fragment " + name + ": doc.params documents every parameter " + params.keySet(), file, doc.line(), doc.column()));
        } else if (ps != null) {
            for (String p : params.keySet()) {
                PNode e = ps.map().get(p);
                if (e == null || !e.isMap()) {
                    out.add(problem("fragment " + name + ": doc.params." + p + " is missing (type, default, meaning)", file, ps.line(), ps.column()));
                    continue;
                }
                String type = text(e.map().get("type"));
                if (type == null || !TYPES.contains(type)) {
                    out.add(problem("fragment " + name + ": doc.params." + p + ".type is one of " + new java.util.TreeSet<>(TYPES), file, e.line(), e.column()));
                }
                if (!e.map().containsKey("default")) {
                    out.add(problem("fragment " + name + ": doc.params." + p + ".default is missing (write default: '' for none)", file, e.line(), e.column()));
                } else if (!Objects.equals(nz(SutraBlocks.flow(e.map().get("default")).replace("pXq{", "%{")), nz(params.get(p)))) {
                    out.add(problem("fragment " + name + ": doc.params." + p + ".default '" + nz(SutraBlocks.flow(e.map().get("default")).replace("pXq{", "%{"))
                            + "' is not the declared default '" + nz(params.get(p)) + "'", file, e.line(), e.column()));
                }
                if (blank(text(e.map().get("meaning")))) {
                    out.add(problem("fragment " + name + ": doc.params." + p + ".meaning says what it does", file, e.line(), e.column()));
                }
            }
            for (Map.Entry<String, PNode> e : ps.map().entrySet()) {
                if (!params.containsKey(e.getKey())) {
                    out.add(problem("fragment " + name + ": doc.params." + e.getKey() + " documents a parameter it does not declare under params",
                            file, e.getValue().line(), e.getValue().column()));
                }
            }
        }
        for (String key : new String[] {"emits", "expects"}) {
            PNode n = d.get(key);
            if (n == null || !(n.isList() || n.isMap())) {
                out.add(problem("fragment " + name + ": doc." + key + " is a list (write " + key + ": [] for none)", file, doc.line(), doc.column()));
            }
        }
        wiring(root, d, name, file, out);
        for (PNode ex : d.get("examples") == null ? List.<PNode>of() : d.get("examples").list()) {
            String use = ex.isMap() ? text(ex.map().get("use")) : null;
            String used = use == null ? null : use.contains("@") ? use.substring(0, use.indexOf('@')) : use;
            if (!name.equals(used)) {
                out.add(problem("fragment " + name + ": an example is a use of it ({ use: " + name + "@N, with: {...} })", file, ex.line(), ex.column()));
                continue;
            }
            PNode with = ex.map().get("with");
            if (with != null) {
                for (String k : with.map().keySet()) {
                    if (!params.containsKey(k)) {
                        out.add(problem("fragment " + name + ": an example sets '" + k + "', which it does not declare", file, with.line(), with.column()));
                    }
                }
            }
        }
        return out;
    }

    /**
     * A composite fragment's {@code doc.uses}: exactly the inner fragments its panels use, with their pinned versions, so a component
     * browser can expand them and "where used" sees both levels. A fragment that uses none may leave it out.
     */
    public static List<SutraProblem> checkUses(PNode root, String name, List<String> inner, String file) {
        PNode doc = root == null ? null : root.map().get("doc");
        PNode uses = doc == null ? null : doc.map().get("uses");
        List<String> listed = new ArrayList<>();
        if (uses != null) {
            uses.list().forEach(u -> listed.add(text(u)));
        }
        if (new java.util.TreeSet<>(listed).equals(new java.util.TreeSet<>(inner))) {
            return List.of();
        }
        return List.of(problem("fragment " + name + ": doc.uses lists the fragments it uses, pinned: " + new java.util.TreeSet<>(inner)
                + " (it lists " + listed + ")", file, doc == null ? 1 : doc.line(), 1));
    }

    /** A Sutra's (or an older block's) doc: summary, description, examples and since; problems are warnings today. */
    public static List<SutraProblem> checkSutra(PNode root, String what, String file) {
        List<SutraProblem> out = new ArrayList<>();
        PNode doc = root == null ? null : root.map().get("doc");
        if (doc == null || !doc.isMap()) {
            out.add(problem(what + " has no doc: block (summary, description, examples, since)", file, root == null ? 1 : root.line(), 1));
            return out;
        }
        common(doc.map(), doc, what, file, out);
        return out;
    }

    private static void common(Map<String, PNode> d, PNode doc, String what, String file, List<SutraProblem> out) {
        String summary = text(d.get("summary"));
        if (blank(summary)) {
            out.add(problem(what + ": doc.summary is one line saying what it shows", file, doc.line(), doc.column()));
        } else if (summary.contains("\n") || summary.length() > SUMMARY_MAX) {
            out.add(problem(what + ": doc.summary is one line of at most " + SUMMARY_MAX + " characters", file, d.get("summary").line(), 1));
        }
        if (blank(text(d.get("description")))) {
            out.add(problem(what + ": doc.description says what it shows and when to use it", file, doc.line(), doc.column()));
        }
        PNode ex = d.get("examples");
        if (ex == null || !ex.isList() || ex.list().isEmpty()) {
            out.add(problem(what + ": doc.examples holds at least one example", file, doc.line(), doc.column()));
        }
        if (blank(text(d.get("since")))) {
            out.add(problem(what + ": doc.since names the release it arrived in (since: \"2.7.0\")", file, doc.line(), doc.column()));
        }
    }

    /** The doc as plain maps, lists and text, for the library API (empty when there is none). */
    public static Map<String, Object> plain(PNode root) {
        PNode doc = root == null ? null : root.map().get("doc");
        if (doc == null || !doc.isMap()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) plainOf(doc);
        return m;
    }

    private static Object plainOf(PNode n) {
        if (n == null) {
            return null;
        }
        if (n.isMap()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.map().forEach((k, v) -> m.put(k, plainOf(v)));
            return m;
        }
        if (n.isList()) {
            List<Object> l = new ArrayList<>();
            n.list().forEach(v -> l.add(plainOf(v)));
            return l;
        }
        return n.value() instanceof String s ? s.replace("pXq{", "%{") : n.value();
    }

    private static String text(PNode n) {
        return n == null || n.text() == null ? null : n.text().replace("pXq{", "%{");   // a block's text is read with %{ masked
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * A fragment whose panels fire events declares them in {@code doc.emits} (each { name, on, payload }), and one whose panels read
     * {@code @parent} declares what it expects in {@code doc.expects} (SUTRA_NEXT.md 7, 8): the component browser and an agent wire
     * from the doc, not from the panels.
     */
    private static void wiring(PNode root, Map<String, PNode> d, String name, String file, List<SutraProblem> out) {
        PNode panels = root.map().get("panels");
        if (panels == null || !panels.isList()) {
            return;
        }
        boolean emits = false;
        boolean parent = false;
        for (PNode p : panels.list()) {
            if (p.isMap()) {
                emits |= p.map().containsKey("emits");
                parent |= p.toString().contains("@parent");
            }
        }
        PNode de = d.get("emits");
        if (emits) {
            if (de == null || !de.isList() || de.list().isEmpty()) {
                out.add(problem("fragment " + name + ": its panels emit events, so doc.emits lists each { name, on, payload }", file, root.line(), 1));
            } else {
                for (PNode e : de.list()) {
                    if (!e.isMap() || text(e.map().get("name")) == null || text(e.map().get("on")) == null) {
                        out.add(problem("fragment " + name + ": each of doc.emits is { name, on, payload } (the payload's fields with their types)",
                                file, e.line(), e.column()));
                    }
                }
            }
        }
        PNode dx = d.get("expects");
        if (parent && (dx == null || !dx.isList() || dx.list().isEmpty())) {
            out.add(problem("fragment " + name + ": its panels read @parent, so doc.expects says which parent event and fields they read", file, root.line(), 1));
        }
    }

    private static SutraProblem problem(String message, String file, int line, int column) {
        return new SutraProblem(CODE, message, new SourceLocation(file, line, column));
    }
}
