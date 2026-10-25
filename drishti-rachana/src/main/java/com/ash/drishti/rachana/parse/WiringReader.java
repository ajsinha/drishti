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
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.SourceLocation;
import com.ash.drishti.rachana.model.Wiring;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Reads a panel's Sutra Next wiring (SUTRA_NEXT.md 6 to 8): {@code source:} written as a mapping, {@code emits:}, {@code waits:} and
 * {@code children:} written as a list of groups. The older forms stay the kind's options: {@code source: "link(...)"} (an expression,
 * the panel reads that linked entity as {@code $}) and a tree table's {@code children: "@.kids"}.
 *
 * <p>Codes: {@code DRS-2057} a source or an event that cannot be read, {@code DRS-2056} a group of children that cannot be read,
 * {@code DRS-2010}/{@code 2011}/{@code 2012} a missing, unknown or mistyped key. Not thread-safe (one per builder).
 */
final class WiringReader {

    static final String EVENTS = "DRS-2057";
    static final String CHILDREN = "DRS-2056";
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Set<String> SOURCE_KEYS = Set.of("pack", "kind", "id", "where", "search", "limit", "as-of", "model", "params");
    private static final Pattern PARAM = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Set<String> EMIT_KEYS = Set.of("name", "on", "payload");
    private static final Set<String> GROUP_KEYS = Set.of("place", "show", "source", "waits", "panels");

    private final String file;
    private final List<SutraProblem> problems;
    private final Function<PNode, List<PNode>> panelList;
    private final Function<PNode, Panel> panel;

    /**
     * @param panelList a {@code panels:} node as its items, every {@code use:} expanded
     * @param panel one child panel as a panel (null when it cannot be read)
     */
    WiringReader(String file, List<SutraProblem> problems, Function<PNode, List<PNode>> panelList, Function<PNode, Panel> panel) {
        this.file = file;
        this.problems = problems;
        this.panelList = panelList;
        this.panel = panel;
    }

    /** Whether {@code key} with this value is wiring rather than an option of the kind. */
    static boolean isWiring(String key, PNode value) {
        return switch (key) {
            case "emits", "waits" -> true;
            case "source" -> value != null && value.isMap();
            case "children" -> value != null && value.isList();
            default -> false;
        };
    }

    Wiring read(Map<String, PNode> m, PanelKind kind) {
        Wiring.Source source = m.get("source") != null && m.get("source").isMap() ? source(m.get("source")) : null;
        List<Wiring.Emit> emits = m.containsKey("emits") ? emits(m.get("emits"), kind) : List.of();
        String waits = null;
        PNode w = m.get("waits");
        if (w != null) {
            if (!(w.value() instanceof String s) || s.isBlank()) {
                problem(EVENTS, "'waits' is the text a panel shows until the event it reads fires, such as \"Pick a trade\"", w);
            } else {
                waits = s;
            }
        }
        List<Wiring.Children> children = m.get("children") != null && m.get("children").isList() ? groups(m.get("children"), kind) : List.of();
        return source == null && emits.isEmpty() && waits == null && children.isEmpty() ? Wiring.NONE
                : new Wiring(source, emits, waits, children);
    }

    Wiring.Source source(PNode n) {
        Map<String, PNode> m = n.map();
        m.forEach((k, v) -> {
            if (!SOURCE_KEYS.contains(k)) {
                problem("DRS-2011", "unknown key '" + k + "' in source; a source takes pack, kind, id, where, search, limit, as-of, params", v);
            }
        });
        if (m.containsKey("model")) {
            problem(EVENTS, "a source over a BI model is not built yet (SUTRA_NEXT.md 6): name a kind with id, where or search", m.get("model"));
            return null;
        }
        String kind = text(m.get("kind"));
        if (kind == null || !NAME.matcher(kind).matches()) {
            problem("DRS-2010", "a source names the entity kind it reads: kind: <kind>", n);
            return null;
        }
        String pack = text(m.get("pack"));
        if (m.containsKey("pack") && (pack == null || !NAME.matcher(pack).matches())) {
            problem("DRS-2012", "'pack' in a source is a pack name (lower-case kebab)", m.get("pack"));
        }
        String id = expr(m.get("id"));
        String where = expr(m.get("where"));
        String search = expr(m.get("search"));
        int ways = (id != null ? 1 : 0) + (where != null ? 1 : 0) + (search != null ? 1 : 0);
        if (ways != 1) {
            problem(EVENTS, "a source of " + kind + " reads one entity (id), the entities a condition keeps (where) or a search (search): "
                    + (ways == 0 ? "it names none" : "name only one"), n);
            return null;
        }
        int limit = Wiring.Source.DEFAULT_LIMIT;
        PNode l = m.get("limit");
        if (l != null) {
            if (id != null || !(l.value() instanceof Long v) || v < 1 || v > Wiring.Source.MAX_LIMIT) {
                problem(EVENTS, id != null ? "'limit' applies to a where or search source, not one read by id"
                        : "'limit' in a source is a whole number from 1 to " + Wiring.Source.MAX_LIMIT, l);
            } else {
                limit = v.intValue();
            }
        }
        return new Wiring.Source(pack, kind, id, where, search, limit, expr(m.get("as-of")), params(kind, m.get("params"), id != null));
    }

    /**
     * A computed kind's parameters (REACTIVE_CONNECTORS.md section 6.1): a mapping of names to expressions ({@code confidence:
     * ctl.confidence}, {@code horizon: 1}); a number or a switch is its own literal. Only with {@code id}: a computation answers one question.
     */
    private Map<String, String> params(String kind, PNode n, boolean single) {
        if (n == null || n.value() == null) {
            return Map.of();
        }
        if (!n.isMap()) {
            problem("DRS-2012", "'params' in a source is a mapping of parameter names to expressions ({ confidence: ctl.confidence })", n);
            return Map.of();
        }
        if (!single) {
            problem(EVENTS, "'params' goes with a source read by id: a computed kind answers one question (" + kind + ")", n);
            return Map.of();
        }
        Map<String, String> out = new java.util.TreeMap<>();
        n.map().forEach((k, v) -> {
            if (!PARAM.matcher(k).matches()) {
                problem("DRS-2012", "'" + k + "' is not a parameter name (letters, digits, '_' and '-', starting with a letter)", v);
            } else if (v == null || v.value() == null || !v.isScalar()) {
                problem("DRS-2012", "parameter " + k + " is an expression written as text, a number or true/false", v == null ? n : v);
            } else {
                out.put(k, v.text());
            }
        });
        if (out.size() > Wiring.Source.MAX_PARAMS) {
            problem("DRS-2012", "a source takes at most " + Wiring.Source.MAX_PARAMS + " parameters", n);
        }
        return out;
    }

    private List<Wiring.Emit> emits(PNode n, PanelKind kind) {
        if (!n.isList()) {
            problem(EVENTS, "'emits' is a list of events: { name, on, payload }", n);
            return List.of();
        }
        List<Wiring.Emit> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (PNode e : n.list()) {
            if (!e.isMap()) {
                problem(EVENTS, "an event is a mapping: { name, on, payload }", e);
                continue;
            }
            Map<String, PNode> m = e.map();
            m.forEach((k, v) -> {
                if (!EMIT_KEYS.contains(k)) {
                    problem("DRS-2011", "unknown key '" + k + "' in an event; an event takes name, on, payload", v);
                }
            });
            String name = text(m.get("name"));
            if (name == null || !NAME.matcher(name).matches()) {
                problem(EVENTS, "an event needs a name, lower-case kebab (select, open, drill, filter, change)", e);
                continue;
            }
            if (!names.add(name)) {
                problem(EVENTS, "the panel emits '" + name + "' twice", e);
            }
            String on = text(m.get("on"));
            if (on == null || !Wiring.TRIGGERS.contains(on)) {
                problem(EVENTS, "event " + name + ": 'on' is what fires it, one of " + String.join(", ", new java.util.TreeSet<>(Wiring.TRIGGERS))
                        + (on == null ? "" : ", not '" + on + "' (point-click, select, submit, load and tick come in later phases)"), m.get("on") == null ? e : m.get("on"));
                continue;
            }
            boolean rows = kind == PanelKind.TABLE || kind == PanelKind.LADDER;
            if ("row-click".equals(on) && !rows || "bar-click".equals(on) && kind != PanelKind.HBAR) {
                problem(EVENTS, "event " + name + ": a " + (kind == null ? "?" : kind.id()) + " panel cannot fire on " + on
                        + " (row-click: table, ladder; bar-click: hbar)", m.get("on"));
                continue;
            }
            Map<String, Wiring.Field> payload = new LinkedHashMap<>();
            PNode p = m.get("payload");
            if (p != null && !p.isMap()) {
                problem(EVENTS, "event " + name + ": the payload is a mapping of field to expression, such as { trade: \"@.TRADE_ID\" }", p);
            } else if (p != null) {
                p.map().forEach((field, v) -> {
                    Wiring.Field f = field(name, field, v);
                    if (f != null) {
                        payload.put(field, f);
                    }
                });
            }
            out.add(new Wiring.Emit(name, on, payload));
        }
        return out;
    }

    private Wiring.Field field(String event, String field, PNode v) {
        if (!field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            problem(EVENTS, "event " + event + ": payload field '" + field + "' must be a name (letters, digits, _), read as @<panel>." + event + "." + field, v);
            return null;
        }
        if (v.value() instanceof String s) {
            return new Wiring.Field(s, "any");
        }
        if (v.isMap() && v.map().get("bind") != null && v.map().get("bind").value() instanceof String b) {
            String type = text(v.map().get("type"));
            if (type != null && !Wiring.TYPES.contains(type)) {
                problem(EVENTS, "event " + event + ": payload field " + field + " has type '" + type + "'; types are " + String.join(", ", Wiring.TYPES), v);
                return null;
            }
            return new Wiring.Field(b, type == null ? "any" : type);
        }
        problem(EVENTS, "event " + event + ": payload field " + field + " is an expression (\"@.TRADE_ID\") or { bind, type }", v);
        return null;
    }

    private List<Wiring.Children> groups(PNode n, PanelKind kind) {
        List<Wiring.Children> out = new ArrayList<>();
        for (PNode g : n.list()) {
            if (!g.isMap()) {
                problem(CHILDREN, "a group of children is a mapping: { place, show, source, waits, panels }", g);
                continue;
            }
            Map<String, PNode> m = g.map();
            m.forEach((k, v) -> {
                if (!GROUP_KEYS.contains(k)) {
                    problem("DRS-2011", "unknown key '" + k + "' in children; a group takes place, show, source, waits, panels", v);
                }
            });
            String place = text(m.get("place"));
            place = place == null ? "detail-below" : place;
            if (!Wiring.PLACES.contains(place)) {
                problem(CHILDREN, "'place' of children is one of " + String.join(", ", Wiring.PLACES) + ", not '" + place + "'", m.get("place"));
                continue;
            }
            if ("expand-row".equals(place) && kind != PanelKind.TABLE && kind != PanelKind.LADDER) {
                problem(CHILDREN, "children placed expand-row open under a row: only a table or ladder has rows", m.get("place"));
                continue;
            }
            String show = text(m.get("show"));
            show = show == null ? "on-select" : show;
            if (!Wiring.SHOWS.contains(show)) {
                problem(CHILDREN, "'show' of children is one of " + String.join(", ", Wiring.SHOWS) + ", not '" + show + "'", m.get("show"));
                continue;
            }
            Wiring.Source source = m.get("source") == null ? null : m.get("source").isMap() ? source(m.get("source")) : null;
            if (m.get("source") != null && !m.get("source").isMap()) {
                problem(CHILDREN, "the source of children is a mapping: { kind, id }", m.get("source"));
            }
            String waits = text(m.get("waits"));
            List<Panel> panels = new ArrayList<>();
            PNode list = m.get("panels");
            if (list == null || !list.isList() || list.list().isEmpty()) {
                problem(CHILDREN, "a group of children needs panels: a list of panels", list == null ? g : list);
                continue;
            }
            for (PNode c : panelList.apply(list)) {
                Panel p = panel.apply(c);
                if (p != null) {
                    panels.add(p);
                }
            }
            out.add(new Wiring.Children(place, show, source, waits, panels));
        }
        return out;
    }

    private static String text(PNode n) {
        return n == null || n.value() == null ? null : n.text();
    }

    private String expr(PNode n) {
        if (n == null || n.value() == null) {
            return null;
        }
        if (!n.isScalar()) {
            problem("DRS-2012", "a source's id, where, search and as-of are expressions written as text", n);
            return null;
        }
        return n.text();
    }

    private void problem(String code, String message, PNode at) {
        problems.add(new SutraProblem(code, message, new SourceLocation(file, at.line(), at.column())));
    }
}
