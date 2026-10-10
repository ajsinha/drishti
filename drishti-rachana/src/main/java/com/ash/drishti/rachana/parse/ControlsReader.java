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
import com.ash.drishti.rachana.model.Control;
import com.ash.drishti.rachana.model.Control.Choice;
import com.ash.drishti.rachana.model.Control.Placement;
import com.ash.drishti.rachana.model.ControlKind;
import com.ash.drishti.rachana.model.ControlRules;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the {@code controls:} section of a Sutra and checks it: the kinds, the options each takes, defaults, placement, cascading
 * parents and each panel's {@code on-select}. Problems are {@code DRS-2034} (a control that is not well formed), {@code DRS-2035}
 * (an id used twice), {@code DRS-2037} (a parent that does not exist or comes later), {@code DRS-2038} (an {@code on-select} that
 * names nothing real) and {@code DRS-2039} (a default the control cannot hold). {@code ctl.<id>} references are checked with the
 * expressions ({@code DRS-2036}).
 */
final class ControlsReader {

    private static final Set<String> KEYS = Set.of("id", "kind", "label", "options", "default", "remember", "required", "placement", "panel",
            "parent", "min", "max");
    private static final Pattern ID = Pattern.compile(ControlRules.ID_PATTERN);

    private final String file;
    private final List<SutraProblem> problems;

    ControlsReader(String file, List<SutraProblem> problems) {
        this.file = file;
        this.problems = problems;
    }

    List<Control> read(PNode n, Set<String> panelIds) {
        if (n == null) {
            return List.of();
        }
        if (!n.isList()) {
            problem("DRS-2012", "'controls' must be a list", n);
            return List.of();
        }
        if (n.list().size() > ControlRules.MAX_CONTROLS) {
            problem("DRS-2034", "a Sutra has at most " + ControlRules.MAX_CONTROLS + " controls, found " + n.list().size(), n);
        }
        List<Control> out = new ArrayList<>();
        Map<String, Control> byId = new LinkedHashMap<>();
        for (PNode c : n.list()) {
            Control control = one(c, panelIds, byId);
            if (control != null) {
                if (byId.put(control.id(), control) != null) {
                    problem("DRS-2035", "duplicate control id '" + control.id() + "'", c);
                }
                out.add(control);
            }
        }
        return out;
    }

    private Control one(PNode n, Set<String> panelIds, Map<String, Control> before) {
        if (!n.isMap()) {
            problem("DRS-2034", "a control must be a mapping (id, kind, label, options, default, ...)", n);
            return null;
        }
        Map<String, PNode> m = n.map();
        m.forEach((k, v) -> {
            if (!KEYS.contains(k)) {
                problem("DRS-2011", "unknown key '" + k + "' in control", v);
            }
        });
        String id = text(m.get("id"));
        if (id == null) {
            problem("DRS-2034", "a control needs an 'id' (what ctl.<id> reads)", n);
            return null;
        }
        if (!ID.matcher(id).matches()) {
            problem("DRS-2034", "control id '" + id + "' must be a letter, then letters, digits and underscores (it is read as ctl." + id + ")",
                    m.get("id"));
            return null;
        }
        String kindText = text(m.get("kind"));
        ControlKind kind = kindText == null ? null : ControlKind.parse(kindText).orElse(null);
        if (kind == null) {
            problem("DRS-2034", kindText == null ? "control '" + id + "' needs a 'kind'" : "unknown control kind '" + kindText + "'; expected one of "
                    + String.join(", ", java.util.Arrays.stream(ControlKind.values()).map(ControlKind::id).toList()), m.getOrDefault("kind", n));
            return null;
        }
        List<Choice> choices = new ArrayList<>();
        String from = null;
        String lookup = null;
        PNode opt = m.get("options");
        if (opt != null && opt.value() != null) {
            if (!kind.listed()) {
                problem("DRS-2034", "a " + kind.id() + " control takes no 'options'", opt);
            } else if (opt.isList()) {
                for (PNode o : opt.list()) {
                    Choice c = choice(o);
                    if (c != null) {
                        choices.add(c);
                    }
                }
            } else if (opt.isMap()) {
                lookup = text(opt.map().get("kind"));
                opt.map().keySet().stream().filter(k -> !k.equals("kind")).findFirst().ifPresent(k ->
                        problem("DRS-2034", "a lookup is written options: { kind: <entity kind> }; '" + k + "' is not read", opt));
                if (lookup == null) {
                    problem("DRS-2034", "options as a mapping is a lookup of an entity kind: { kind: counterparty }", opt);
                }
            } else if (opt.value() instanceof String s && !s.isBlank()) {
                from = s;
            } else {
                problem("DRS-2034", "options is a list, an expression over the record (such as $.legs[*].id) or { kind: <entity kind> }", opt);
            }
        } else if (kind.listed()) {
            problem("DRS-2034", "a " + kind.id() + " control needs 'options': a list, an expression such as $.legs[*].id, or { kind: <entity kind> }", n);
        }
        if (choices.size() > Control.MAX_OPTIONS) {
            problem("DRS-2034", "a control lists at most " + Control.MAX_OPTIONS + " fixed options", opt);
        }
        Placement placement = Placement.STRIP;
        String placementText = text(m.get("placement"));
        if (placementText != null) {
            String p = placementText.toLowerCase(Locale.ROOT).replace("panel header", "header").trim();
            placement = switch (p) {
                case "strip" -> Placement.STRIP;
                case "header" -> Placement.HEADER;
                case "side" -> Placement.SIDE;
                default -> null;
            };
            if (placement == null) {
                problem("DRS-2034", "placement must be strip, header or side, not '" + placementText + "'", m.get("placement"));
                placement = Placement.STRIP;
            }
        }
        String panel = text(m.get("panel"));
        if (placement == Placement.HEADER) {
            if (panel == null) {
                problem("DRS-2034", "a control placed in a panel header names the panel: panel: <panel id>", n);
            } else if (!panelIds.contains(panel)) {
                problem("DRS-2034", "control '" + id + "' is placed in the header of panel '" + panel + "', which the Sutra does not have", m.get("panel"));
            }
        } else if (panel != null) {
            problem("DRS-2034", "'panel' is for placement: header", m.get("panel"));
        }
        boolean remember = false;
        String rem = text(m.get("remember"));
        if (rem != null) {
            if (!rem.equals("user") && !rem.equals("none")) {
                problem("DRS-2034", "remember is user or none, not '" + rem + "'", m.get("remember"));
            }
            remember = rem.equals("user");
        }
        boolean required = false;
        PNode req = m.get("required");
        if (req != null && req.value() != null) {
            if (req.value() instanceof Boolean b) {
                required = b;
            } else {
                problem("DRS-2034", "required is true or false, not '" + req.value() + "'", req);
            }
        }
        String parent = text(m.get("parent"));
        if (kind == ControlKind.CASCADING) {
            if (parent == null) {
                problem("DRS-2037", "a cascading control names the control it depends on: parent: <control id>", n);
            } else if (!before.containsKey(parent)) {
                problem("DRS-2037", "control '" + id + "' depends on '" + parent + "', which must be a control declared before it", m.get("parent"));
            }
        } else if (parent != null) {
            problem("DRS-2034", "'parent' is for cascading controls", m.get("parent"));
        }
        String min = bound(m.get("min"), kind);
        String max = bound(m.get("max"), kind);
        String def = defaultOf(m.get("default"));
        checkDefault(id, kind, def, choices, m.get("default") == null ? n : m.get("default"));
        return new Control(id, kind, text(m.get("label")), choices, from, lookup, def, remember, required, placement, panel, parent, min, max, loc(n));
    }

    private Choice choice(PNode o) {
        if (o.isMap()) {
            String value = text(o.map().get("value"));
            if (value == null) {
                problem("DRS-2034", "an option written as a mapping needs a 'value' (and may have a 'label')", o);
                return null;
            }
            String label = text(o.map().get("label"));
            return new Choice(value, label == null ? value : label);
        }
        if (!o.isScalar() || o.value() == null) {
            problem("DRS-2034", "an option is a value, or { value, label }", o);
            return null;
        }
        return new Choice(o.text(), o.text());
    }

    private String bound(PNode n, ControlKind kind) {
        if (n == null || n.value() == null) {
            return null;
        }
        if (kind != ControlKind.NUMBER_RANGE) {
            problem("DRS-2034", "'min' and 'max' are for number-range controls", n);
            return null;
        }
        if (!n.isScalar()) {
            problem("DRS-2034", "min and max are a number or an expression over the record", n);
            return null;
        }
        return n.text();
    }

    private String defaultOf(PNode n) {
        if (n == null || n.value() == null) {
            return null;
        }
        if (n.isList()) {
            return String.join(",", n.list().stream().map(PNode::text).filter(java.util.Objects::nonNull).toList());
        }
        return n.isScalar() ? n.text() : null;
    }

    private void checkDefault(String id, ControlKind kind, String def, List<Choice> choices, PNode at) {
        if (def == null || def.isBlank()) {
            return;
        }
        String why = ControlRules.defaultProblem(kind, def, choices.stream().map(Choice::value).toList());
        if (why != null) {
            problem("DRS-2039", "default of control '" + id + "': " + why, at);
        }
    }

    /**
     * Checks each panel's {@code on-select} against the Sutra's panels and controls: {@code filter} lists panels that exist, and
     * {@code by} names a control (the clicked row or bar sets it).
     */
    void checkSelects(List<Panel> panels, List<Control> controls) {
        Set<String> ids = new java.util.HashSet<>();
        panels.forEach(p -> ids.add(p.id()));
        Set<String> ctl = new java.util.HashSet<>();
        controls.forEach(c -> ctl.add(c.id()));
        for (Panel p : panels) {
            Object raw = p.options().get(Panel.ON_SELECT);
            if (raw == null) {
                continue;
            }
            if (!(raw instanceof Map<?, ?> m)) {
                problems.add(new SutraProblem("DRS-2038", "on-select of panel '" + p.id() + "' is { filter: [panel ids], by: <control id> }", p.location()));
                continue;
            }
            for (Object k : m.keySet()) {
                if (!Set.of("filter", "by", "field").contains(String.valueOf(k))) {
                    problems.add(new SutraProblem("DRS-2038", "on-select of panel '" + p.id() + "' has no key '" + k + "'; it takes filter, by and field", p.location()));
                }
            }
            Object by = m.get("by");
            if (!(by instanceof String s) || !ctl.contains(s)) {
                problems.add(new SutraProblem("DRS-2038", "on-select of panel '" + p.id() + "': 'by' must name one of the Sutra's controls"
                        + (ctl.isEmpty() ? " (it has none)" : " (" + String.join(", ", ctl) + ")") + ", not '" + by + "'", p.location()));
            }
            Object filter = m.get("filter");
            if (filter != null && !(filter instanceof List<?>)) {
                problems.add(new SutraProblem("DRS-2038", "on-select of panel '" + p.id() + "': 'filter' is a list of panel ids", p.location()));
            } else if (filter instanceof List<?> l) {
                for (Object f : l) {
                    if (!ids.contains(String.valueOf(f))) {
                        problems.add(new SutraProblem("DRS-2038", "on-select of panel '" + p.id() + "' filters panel '" + f + "', which the Sutra does not have",
                                p.location()));
                    }
                }
            }
            if (m.get("field") != null && !(m.get("field") instanceof String)) {
                problems.add(new SutraProblem("DRS-2038", "on-select of panel '" + p.id() + "': 'field' is the row field whose value is set", p.location()));
            }
        }
    }

    private static String text(PNode n) {
        return n == null || n.text() == null ? null : n.text();
    }

    private SourceLocation loc(PNode n) {
        return new SourceLocation(file, n.line(), n.column());
    }

    private void problem(String code, String message, PNode at) {
        problems.add(new SutraProblem(code, message, loc(at)));
    }
}
