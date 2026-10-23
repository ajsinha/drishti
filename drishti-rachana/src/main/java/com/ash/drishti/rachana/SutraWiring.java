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
package com.ash.drishti.rachana;

import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.model.Wiring;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The lint of a Sutra Next screen's wiring (SUTRA_NEXT.md 14): every {@code @panel.event.field} names a panel of the screen that
 * emits that event with that field, a child's {@code @parent} has a parent that provides it, no panel waits on itself or on a
 * panel that waits on it (a cycle), children nest at most {@link Wiring#MAX_DEPTH} deep, {@code $src} is read only where a source
 * is named, and a source's id does not read a payload field typed as a number, a date or a truth value (an id is text).
 * {@code DRS-2057} for events and sources, {@code DRS-2056} for children. Stateless.
 */
public final class SutraWiring {

    /** One panel of the screen at any depth: its parent (null on the screen) and its depth (1 on the screen). */
    public record Node(Panel panel, Panel parent, Wiring.Children group, int depth) {}

    private SutraWiring() {}

    /** Every panel of {@code s}, screen panels first, then each one's children depth first, by id. */
    public static Map<String, Node> nodes(Sutra s) {
        Map<String, Node> out = new LinkedHashMap<>();
        for (Panel p : s.panels()) {
            walk(p, null, null, 1, out);
        }
        return out;
    }

    private static void walk(Panel p, Panel parent, Wiring.Children group, int depth, Map<String, Node> out) {
        out.putIfAbsent(p.id(), new Node(p, parent, group, depth));
        for (Wiring.Children g : p.wiring().children()) {
            for (Panel c : g.panels()) {
                walk(c, p, g, depth + 1, out);
            }
        }
    }

    /** The texts of a panel that may read events: title, column binds, string options, field binds, its source's and its group's. */
    public static List<String> texts(Panel p, Wiring.Children group) {
        List<String> out = new ArrayList<>();
        if (p.title() != null) {
            out.add(p.title());
        }
        for (Column c : p.columns()) {
            out.add(c.bind());
        }
        p.options().values().forEach(v -> {
            if (v instanceof String s) {
                out.add(s);
            } else if (v instanceof List<?> l) {
                l.forEach(o -> {
                    if (o instanceof Map<?, ?> m && m.get("bind") instanceof String b) {
                        out.add(b);
                    }
                });
            }
        });
        if (p.wiring().source() != null) {
            out.addAll(p.wiring().source().expressions());
        }
        if (group != null && group.source() != null) {
            out.addAll(group.source().expressions());
        }
        return out;
    }

    /** The ids of the panels whose events {@code p} reads ({@code parent} for {@code @parent}). */
    public static Set<String> reads(Panel p, Wiring.Children group) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : texts(p, group)) {
            Wiring.refs(t).forEach(r -> out.add(r.panel()));
        }
        return out;
    }

    public static List<SutraProblem> check(Sutra s) {
        List<SutraProblem> out = new ArrayList<>();
        Map<String, Node> nodes = nodes(s);
        Map<String, Set<String>> deps = new HashMap<>();
        for (Node n : nodes.values()) {
            Panel p = n.panel();
            if ("parent".equals(p.id())) {
                out.add(new SutraProblem("DRS-2057", "no panel may be called 'parent': @parent names a child's parent", p.location()));
            }
            if (n.depth() > Wiring.MAX_DEPTH) {
                out.add(new SutraProblem("DRS-2056", "panel '" + p.id() + "' is nested " + n.depth() + " deep: children nest at most " + Wiring.MAX_DEPTH
                        + " (screen panel, child, grandchild)", p.location()));
            }
            Set<String> d = deps.computeIfAbsent(p.id(), k -> new LinkedHashSet<>());
            if (n.parent() != null) {
                d.add(n.parent().id());
            }
            boolean sourced = p.wiring().source() != null || n.group() != null && n.group().source() != null;
            Set<String> idTexts = new HashSet<>();
            if (p.wiring().source() != null && p.wiring().source().id() != null) {
                idTexts.add(p.wiring().source().id());
            }
            if (n.group() != null && n.group().source() != null && n.group().source().id() != null) {
                idTexts.add(n.group().source().id());
            }
            for (String t : texts(p, n.group())) {
                if (!sourced && t.replaceAll("'[^']*'|\"[^\"]*\"", "").matches("(?s).*\\$src\\b.*")) {
                    out.add(new SutraProblem("DRS-2057", "panel '" + p.id() + "' reads $src but names no source: (nor does its group of children)", p.location()));
                }
                for (Wiring.Ref r : Wiring.refs(t)) {
                    ref(n, r, nodes, idTexts.contains(t), out).ifPresent(d::add);
                }
            }
        }
        cycles(deps, nodes, out);
        return out;
    }

    /** Checks one reference; the id of the panel it makes {@code n} wait on, when it names one. */
    private static Optional<String> ref(Node n, Wiring.Ref r, Map<String, Node> nodes, boolean inId, List<SutraProblem> out) {
        Panel p = n.panel();
        boolean parent = "parent".equals(r.panel());
        Panel target;
        String code = parent ? "DRS-2056" : "DRS-2057";
        if (parent) {
            if (n.parent() == null) {
                out.add(new SutraProblem(code, "panel '" + p.id() + "' reads @parent but is not a child panel (no parent provides it)", p.location()));
                return Optional.empty();
            }
            target = n.parent();
        } else {
            Node t = nodes.get(r.panel());
            if (t == null) {
                out.add(new SutraProblem(code, "panel '" + p.id() + "' reads @" + r.panel() + ", which is no panel of this screen (panels: "
                        + String.join(", ", nodes.keySet()) + ")", p.location()));
                return Optional.empty();
            }
            target = t.panel();
            if (target == p) {
                out.add(new SutraProblem(code, "panel '" + p.id() + "' reads its own event @" + r.panel() + ": a panel cannot wait on itself", p.location()));
                return Optional.empty();
            }
        }
        String who = parent ? "its parent '" + target.id() + "'" : "panel '" + target.id() + "'";
        if (r.event() == null) {
            out.add(new SutraProblem(code, "panel '" + p.id() + "' reads @" + r.panel() + " without an event: write @" + r.panel() + ".<event>.<field>", p.location()));
            return Optional.of(target.id());
        }
        Optional<Wiring.Emit> e = target.wiring().emit(r.event());
        if (e.isEmpty()) {
            List<String> has = target.wiring().emits().stream().map(Wiring.Emit::name).toList();
            out.add(new SutraProblem(code, "panel '" + p.id() + "' reads @" + r.panel() + "." + r.event() + ", but " + who + " emits "
                    + (has.isEmpty() ? "no events" : String.join(", ", has)), p.location()));
            return Optional.of(target.id());
        }
        if (r.field() != null && !e.get().payload().containsKey(r.field())) {
            out.add(new SutraProblem(code, "panel '" + p.id() + "' reads @" + r.panel() + "." + r.event() + "." + r.field() + ", but the payload of "
                    + r.event() + " has " + (e.get().payload().isEmpty() ? "no fields" : String.join(", ", e.get().payload().keySet())), p.location()));
        } else if (r.field() != null && inId) {
            String type = e.get().payload().get(r.field()).type();
            if (Set.of("number", "date", "bool").contains(type)) {
                out.add(new SutraProblem(code, "panel '" + p.id() + "': its source's id reads @" + r.panel() + "." + r.event() + "." + r.field()
                        + ", a " + type + "; an id is text (type id or text)", p.location()));
            }
        }
        return Optional.of(target.id());
    }

    /** A panel that waits, through events or as a child, on a panel that waits on it: the clicks could never settle. */
    private static void cycles(Map<String, Set<String>> deps, Map<String, Node> nodes, List<SutraProblem> out) {
        Map<String, Integer> state = new HashMap<>();             // 1: on the path being walked, 2: walked
        Set<String> reported = new HashSet<>();
        for (String start : deps.keySet()) {
            visit(start, deps, new ArrayList<>(), state, reported, nodes, out);
        }
    }

    private static void visit(String id, Map<String, Set<String>> deps, List<String> path, Map<String, Integer> state, Set<String> reported,
            Map<String, Node> nodes, List<SutraProblem> out) {
        Integer st = state.get(id);
        if (st != null && st == 2) {
            return;
        }
        if (st != null) {
            List<String> loop = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
            loop.add(id);
            if (reported.add(new java.util.TreeSet<>(loop).toString())) {
                out.add(new SutraProblem("DRS-2057", "panels wait on each other in a cycle: " + String.join(" > ", loop)
                        + " (each waits on the next one's event, or is its child)", nodes.get(id).panel().location()));
            }
            return;
        }
        state.put(id, 1);
        path.add(id);
        for (String next : deps.getOrDefault(id, Set.of())) {
            if (nodes.containsKey(next)) {
                visit(next, deps, path, state, reported, nodes, out);
            }
        }
        path.remove(path.size() - 1);
        state.put(id, 2);
    }
}
