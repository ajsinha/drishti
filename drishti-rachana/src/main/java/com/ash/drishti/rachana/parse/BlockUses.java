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
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared blocks a Sutra's text uses, as the designer shows them: each {@code { use: name@N, with: {...} }} of its panels (and of
 * a tabs panel's panels) with the block it resolves to, the block's parameters (defaults and the values given), the panels it brings
 * (id, kind, title, after the parameters are filled) and its {@code DRS-2048} problems. The expansion is {@link SutraBlocks}'s own, so
 * the designer groups exactly the panels the server draws. Stateless and thread-safe.
 */
public final class BlockUses {

    /** One panel a use brings; {@code panel} is the panel written out (plain maps, lists and scalars), what "unpack" writes in place of the use. */
    public record Brought(String id, String kind, String title, String when, Object panel) {}

    /**
     * One use: {@code item} is its place among the Sutra's written panels (for a use inside a tabs panel, the tabs panel's place),
     * {@code at} where it is ({@code panels[2]}, {@code panels[1].panels[0]}), {@code line} and {@code column} its place in the text.
     */
    public record Use(int item, String at, int line, int column, String ref, String name, Integer version, String file, String source,
            String description, Map<String, String> params, Map<String, String> with, List<Brought> panels, List<SutraProblem> problems) {}

    private BlockUses() {}

    /** The uses in a Sutra's text, in order; empty when it has none or cannot be read. */
    public static List<Use> of(String text) {
        PNode root;
        try {
            root = new PositionalYamlReader().read(text == null ? "" : text, new ArrayList<>());
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        if (root == null || !root.isMap() || root.map().get("panels") == null || !root.map().get("panels").isList()) {
            return List.of();
        }
        List<Use> out = new ArrayList<>();
        List<PNode> items = root.map().get("panels").list();
        for (int i = 0; i < items.size(); i++) {
            PNode p = items.get(i);
            if (SutraBlocks.isUse(p)) {
                out.add(use(i, "panels[" + i + "]", p));
            } else if (p.isMap() && p.map().get("panels") != null && p.map().get("panels").isList()) {
                List<PNode> inner = p.map().get("panels").list();
                for (int j = 0; j < inner.size(); j++) {
                    if (SutraBlocks.isUse(inner.get(j))) {
                        out.add(use(i, "panels[" + i + "].panels[" + j + "]", inner.get(j)));
                    }
                }
            }
        }
        return out;
    }

    private static Use use(int item, String at, PNode p) {
        String ref = p.map().get("use").text() == null ? "" : p.map().get("use").text().trim();
        Map<String, String> with = new LinkedHashMap<>();
        PNode w = p.map().get("with");
        if (w != null && w.isMap()) {
            w.map().forEach((k, v) -> with.put(k, v.text() == null ? "" : v.text()));
        }
        List<SutraProblem> problems = new ArrayList<>();
        List<Brought> brought = new ArrayList<>();
        for (PNode q : SutraBlocks.expand(List.of(p), "studio", problems)) {
            Map<String, PNode> m = q.isMap() ? q.map() : Map.of();
            brought.add(new Brought(text(m.get("id")), text(m.get("kind")), text(m.get("title")), text(m.get("when")), plain(q)));
        }
        SutraBlocks.Block b = SutraBlocks.find(ref).orElse(null);
        String name = ref.contains("@") ? ref.substring(0, ref.indexOf('@')) : ref;
        return new Use(item, at, p.line(), p.column(), ref, name, b == null ? null : b.version(), b == null ? null : b.file(), b == null ? null : b.path(),
                b == null ? "" : b.description(), b == null ? Map.of() : b.params(), with, List.copyOf(brought), List.copyOf(problems));
    }

    /** A node as plain data: maps, lists and scalars, in order. */
    public static Object plain(PNode n) {
        if (n == null) {
            return null;
        }
        if (n.isMap()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.map().forEach((k, v) -> m.put(k, plain(v)));
            return m;
        }
        if (n.isList()) {
            List<Object> l = new ArrayList<>();
            n.list().forEach(v -> l.add(plain(v)));
            return l;
        }
        return n.value();
    }

    private static String text(PNode n) {
        return n == null || n.text() == null ? null : n.text();
    }
}
