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
import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Match;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.SourceLocation;
import com.ash.drishti.rachana.model.StripItem;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.model.Title;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a {@link PNode} tree into a {@link Sutra}, collecting every problem with its line and column.
 * One builder per file; not thread-safe.
 *
 * <p>Codes: 2001 YAML syntax, 2010 missing key, 2011 unknown key, 2012 wrong type, 2020 bad name or
 * version, 2021 unknown panel kind, 2022 missing kind option, 2023 option not valid for kind, 2024
 * duplicate panel id, 2025 duplicate or invalid function key, 2026 strip too long, 2027 bad area.
 */
final class SutraBuilder {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Pattern FKEY = Pattern.compile("F([1-9]|1[0-2])");
    private static final Set<String> TOP = Set.of("sutra", "version", "domain", "match", "title", "strip", "panels", "keys", "description");
    private static final Set<String> PANEL = Set.of("id", "kind", "title", "key", "code", "area", "infer", "columns", "body");
    private static final Set<String> STRIP = Set.of("label", "bind", "fmt", "tone", "emphasis");
    private static final Set<String> COLUMN = Set.of("label", "bind", "fmt", "tone", "total", "link");

    private final String file;
    private final List<SutraProblem> problems = new ArrayList<>();

    SutraBuilder(String file) {
        this.file = file;
    }

    List<SutraProblem> problems() {
        return problems;
    }

    Sutra build(PNode root, String domain) {
        if (!root.isMap()) {
            problem("DRS-2012", "a Sutra must be a mapping", root);
            return null;
        }
        Map<String, PNode> m = root.map();
        unknownKeys(m, TOP, "top level");
        String name = requiredText(m, "sutra", root);
        if (name != null && !NAME.matcher(name).matches()) {
            problem("DRS-2020", "name '" + name + "' must be lower-case kebab, 2-64 characters", m.get("sutra"));
        }
        int version = 0;
        PNode v = m.get("version");
        if (v == null) {
            problem("DRS-2010", "missing 'version'", root);
        } else if (!(v.value() instanceof Long l) || l < 1) {
            problem("DRS-2020", "version must be a positive integer", v);
        } else {
            version = l.intValue();
        }
        Match match = match(m.get("match"), root);
        Title title = title(m.get("title"));
        List<StripItem> strip = strip(m.get("strip"));
        List<Panel> panels = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (PNode p : listOf(m.get("panels"), "panels")) {
            Panel panel = panel(p, false);
            if (panel != null) {
                if (!ids.add(panel.id())) {
                    problem("DRS-2024", "duplicate panel id '" + panel.id() + "'", p);
                }
                if (panel.key() != null && !keys.add(panel.key())) {
                    problem("DRS-2025", "function key " + panel.key() + " is used twice", p);
                }
                panels.add(panel);
            }
        }
        Map<String, String> keyMap = new LinkedHashMap<>();
        PNode k = m.get("keys");
        if (k != null) {
            if (!k.isMap()) {
                problem("DRS-2012", "'keys' must be a mapping of F-key to action", k);
            } else {
                k.map().forEach((key, action) -> {
                    if (!FKEY.matcher(key).matches()) {
                        problem("DRS-2025", "'" + key + "' is not a function key (F1-F12)", action);
                    } else if (!keys.add(key)) {
                        problem("DRS-2025", "function key " + key + " is used twice", action);
                    } else if (action.text() == null) {
                        problem("DRS-2012", "action for " + key + " must be text", action);
                    } else {
                        keyMap.put(key, action.text());
                    }
                });
            }
        }
        if (!problems.isEmpty()) {
            return null;
        }
        return new Sutra(name, version, text(m.get("domain"), domain), match, title, strip, panels, keyMap, loc(root));
    }

    private Match match(PNode n, PNode root) {
        if (n == null || !n.isMap()) {
            problem("DRS-2010", "missing 'match' mapping with at least 'kind'", n == null ? root : n);
            return new Match("?", null, 0);
        }
        unknownKeys(n.map(), Set.of("kind", "where", "priority"), "match");
        String kind = requiredText(n.map(), "kind", n);
        PNode pr = n.map().get("priority");
        int priority = pr != null && pr.value() instanceof Long l ? l.intValue() : 0;
        return new Match(kind, text(n.map().get("where"), null), priority);
    }

    private Title title(PNode n) {
        if (n == null) {
            return new Title(null, "$.id", null);
        }
        if (!n.isMap()) {
            problem("DRS-2012", "'title' must be a mapping (pill, id, with)", n);
            return null;
        }
        unknownKeys(n.map(), Set.of("pill", "id", "with"), "title");
        return new Title(text(n.map().get("pill"), null), text(n.map().get("id"), "$.id"), text(n.map().get("with"), null));
    }

    private List<StripItem> strip(PNode n) {
        List<StripItem> out = new ArrayList<>();
        List<PNode> items = listOf(n, "strip");
        if (items.size() > com.ash.drishti.rachana.model.Sutra.MAX_STRIP) {
            problem("DRS-2026", "the strip holds at most " + Sutra.MAX_STRIP + " figures, found " + items.size(), n);
        }
        for (PNode i : items) {
            if (!i.isMap()) {
                problem("DRS-2012", "a strip item must be a mapping (label, bind, fmt, tone, emphasis)", i);
                continue;
            }
            Map<String, PNode> m = i.map();
            unknownKeys(m, STRIP, "strip item");
            out.add(new StripItem(text(m.get("label"), null), requiredText(m, "bind", i), text(m.get("fmt"), null),
                    text(m.get("tone"), null), bool(m.get("emphasis")), loc(i)));
        }
        return out;
    }

    private Panel panel(PNode n, boolean nested) {
        if (!n.isMap()) {
            problem("DRS-2012", "a panel must be a mapping", n);
            return null;
        }
        Map<String, PNode> m = n.map();
        String kindText = requiredText(m, "kind", n);
        PanelKind kind = kindText == null ? null : PanelKind.parse(kindText).orElse(null);
        if (kindText != null && kind == null) {
            problem("DRS-2021", "unknown panel kind '" + kindText + "'; expected one of " + kinds(), m.get("kind"));
            return null;
        }
        String id = nested ? text(m.get("id"), "body") : requiredText(m, "id", n);
        String key = text(m.get("key"), null);
        if (key != null && !FKEY.matcher(key).matches()) {
            problem("DRS-2025", "'" + key + "' is not a function key (F1-F12)", m.get("key"));
        }
        Area area = Area.MAIN;
        String areaText = text(m.get("area"), null);
        if (areaText != null) {
            try {
                area = Area.valueOf(areaText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problem("DRS-2027", "area must be 'main' or 'right'", m.get("area"));
            }
        }
        List<Column> columns = new ArrayList<>();
        for (PNode c : listOf(m.get("columns"), "columns")) {
            if (!c.isMap()) {
                problem("DRS-2012", "a column must be a mapping (label, bind, fmt, tone, total, link)", c);
                continue;
            }
            unknownKeys(c.map(), COLUMN, "column");
            columns.add(new Column(text(c.map().get("label"), null), requiredText(c.map(), "bind", c),
                    text(c.map().get("fmt"), null), text(c.map().get("tone"), null), bool(c.map().get("total")),
                    bool(c.map().get("link"))));
        }
        Panel body = null;
        if (m.containsKey("body")) {
            if (kind != PanelKind.TABS) {
                problem("DRS-2023", "only 'tabs' panels take a 'body'", m.get("body"));
            } else {
                body = panel(m.get("body"), true);
            }
        }
        Map<String, Object> options = new LinkedHashMap<>();
        if (kind != null) {
            m.forEach((opt, val) -> {
                if (PANEL.contains(opt)) {
                    return;
                }
                if (!kind.accepts(opt)) {
                    problem("DRS-2023", "search".equals(opt) ? "option 'search' applies only to table panels, not '" + kind.id() + "'"
                            : "option '" + opt + "' is not valid for '" + kind.id() + "' panels", val);
                } else if (val.value() != null) {
                    options.put(opt, val.isScalar() ? val.value() : plain(val));
                }
            });
            for (String req : kind.required()) {
                if (!m.containsKey(req)) {
                    problem("DRS-2022", "'" + kind.id() + "' panel '" + id + "' needs option '" + req + "'", n);
                }
            }
        }
        return new Panel(id, kind, text(m.get("title"), null), key, text(m.get("code"), null), area, bool(m.get("infer")),
                columns, body, options, loc(n));
    }

    /** Converts a structured option (for example a series list) to plain maps and lists. */
    private static Object plain(PNode n) {
        if (n.isMap()) {
            Map<String, Object> out = new LinkedHashMap<>();
            n.map().forEach((k, v) -> out.put(k, plain(v)));
            return out;
        }
        if (n.isList()) {
            return n.list().stream().map(SutraBuilder::plain).toList();
        }
        return n.value();
    }

    private List<PNode> listOf(PNode n, String what) {
        if (n == null) {
            return List.of();
        }
        if (!n.isList()) {
            problem("DRS-2012", "'" + what + "' must be a list", n);
            return List.of();
        }
        return n.list();
    }

    private void unknownKeys(Map<String, PNode> m, Set<String> allowed, String where) {
        m.forEach((k, v) -> {
            if (!allowed.contains(k)) {
                problem("DRS-2011", "unknown key '" + k + "' in " + where, v);
            }
        });
    }

    private String requiredText(Map<String, PNode> m, String key, PNode parent) {
        PNode n = m.get(key);
        if (n == null || n.value() == null) {
            problem("DRS-2010", "missing '" + key + "'", parent);
            return null;
        }
        if (!n.isScalar()) {
            problem("DRS-2012", "'" + key + "' must be text", n);
            return null;
        }
        return n.text();
    }

    private static String text(PNode n, String fallback) {
        return n == null || n.text() == null ? fallback : n.text();
    }

    private static boolean bool(PNode n) {
        return n != null && Boolean.TRUE.equals(n.value());
    }

    private static String kinds() {
        StringBuilder sb = new StringBuilder();
        for (PanelKind k : PanelKind.values()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(k.id());
        }
        return sb.toString();
    }

    private SourceLocation loc(PNode n) {
        return new SourceLocation(file, n.line(), n.column());
    }

    private void problem(String code, String message, PNode at) {
        problems.add(new SutraProblem(code, message, loc(at)));
    }
}
