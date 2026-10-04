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
package com.ash.drishti.rachana.design.ops;

import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binds a path of the data to a panel, in the role the panel's kind gives it: {@code rows} (the list the panel reads),
 * a field option such as {@code x}, {@code y}, {@code value}, {@code label}, {@code by}, {@code across}, or one of the
 * adding roles {@code column} (table, ladder), {@code field} (kv, status) and {@code series} (area), which append an
 * entry. Without a role it is the kind's next unfilled one. The roles a kind takes are the options it accepts, so a
 * role the kind does not know is refused with the list it does.
 *
 * @param panel the panel's id
 * @param path the path or field: {@code $.legs} for a list, {@code notional} or {@code @.notional} for a field of a row
 * @param role the role, or null for the kind's default
 */
public record Bind(String panel, String path, String role) implements Op {

    /** Options that hold a path or field of the data (the others hold formats, labels and switches). */
    private static final Set<String> BINDABLE = Set.of("rows", "x", "y", "value", "label", "size", "group", "open", "high", "low", "close",
            "volume", "date", "detail", "status", "tone", "by", "across", "nodes", "edges", "source", "each", "max", "total", "sum", "delta");

    @Override
    public String name() {
        return "bind";
    }

    /** The roles {@code kind} takes. */
    public static List<String> roles(PanelKind kind) {
        Set<String> out = new LinkedHashSet<>();
        for (String o : kind.required()) {
            if (BINDABLE.contains(o)) {
                out.add(o);
            }
        }
        for (String o : kind.optional()) {
            if (BINDABLE.contains(o)) {
                out.add(o);
            }
        }
        if (kind.readsData()) {
            out.add("source");
        }
        switch (kind) {
            case TABLE, LADDER -> out.add("column");
            case KV, STATUS -> out.add("field");
            case AREA -> out.add("series");
            default -> { }
        }
        return new ArrayList<>(out);
    }

    @Override
    public void applyTo(SutraDoc doc) {
        if (path == null || path.isBlank()) {
            throw new OpException(OpException.MALFORMED, "bind needs a 'path': a field or a path such as $.legs");
        }
        Panel p = doc.panel(panel);
        List<String> roles = roles(p.kind());
        String r = role != null ? role : defaultRole(p, roles);
        if (r == null || !roles.contains(r)) {
            throw new OpException(OpException.NOT_ACCEPTED, "'" + p.kind().id() + "' panels " + (roles.isEmpty() ? "bind no data"
                    : "take these roles: " + String.join(", ", roles)) + (role == null ? "" : "; not '" + role + "'"));
        }
        String bare = path.strip();
        switch (r) {
            case "column" -> doc.setPanelKey(panel, "columns", append(columns(p), entry("label", label(bare), "bind", rowPath(bare))));
            case "field" -> doc.setPanelKey(panel, "fields", append(list(p, "fields"), entry("label", label(bare), "bind", docPath(bare))));
            case "series" -> doc.setPanelKey(panel, "series", append(list(p, "series"), entry("label", label(bare), "value", rowPath(bare))));
            case "by" -> doc.setPanelKey(panel, "by", by(p, bare));
            default -> {
                OpChecks.option(p.kind(), r, bare);
                doc.setPanelKey(panel, r, bare);
            }
        }
    }

    /** The first role that needs filling: rows when the kind needs it and the panel has none, then the kind's habitual one. */
    private static String defaultRole(Panel p, List<String> roles) {
        if (p.kind().required().contains("rows") && !p.options().containsKey("rows")) {
            return "rows";
        }
        List<String> prefer = switch (p.kind()) {
            case TABLE, LADDER -> List.of("column");
            case KV, STATUS -> List.of("field");
            case AREA -> List.of("series");
            case LINE -> List.of("x", "y");
            case SCATTER -> List.of("x", "y", "size");
            case PIVOT -> List.of("by", "across", "value");
            case CANDLESTICK -> List.of("close", "open", "high", "low");
            case TIMELINE -> List.of("date", "label");
            case HBAR, WATERFALL -> List.of("label", "value");
            case GRAPH -> List.of("nodes", "edges");
            case TABS -> List.of("each");
            default -> List.of("value", "rows");
        };
        for (String c : prefer) {
            if (roles.contains(c) && (c.equals("column") || c.equals("field") || c.equals("series") || !p.options().containsKey(c))) {
                return c;
            }
        }
        return prefer.stream().filter(roles::contains).findFirst().orElse(null);
    }

    private static List<Object> columns(Panel p) {
        List<Object> out = new ArrayList<>();
        for (Column c : p.columns()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (c.label() != null) {
                m.put("label", c.label());
            }
            m.put("bind", c.bind());
            if (c.fmt() != null) {
                m.put("fmt", c.fmt());
            }
            if (c.tone() != null) {
                m.put("tone", c.tone());
            }
            if (c.total()) {
                m.put("total", true);
            }
            if (c.link()) {
                m.put("link", true);
            }
            out.add(m);
        }
        return out;
    }

    private static List<Object> list(Panel p, String option) {
        return p.options().get(option) instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
    }

    private static List<Object> append(List<Object> list, Map<String, Object> item) {
        list.add(item);
        return list;
    }

    private static Object by(Panel p, String field) {
        List<Object> now = new ArrayList<>();
        Object cur = p.options().get("by");
        if (cur instanceof List<?> l) {
            now.addAll(l);
        } else if (cur != null) {
            now.add(cur);
        }
        if (!now.contains(field)) {
            now.add(field);
        }
        return now.size() == 1 ? now.get(0) : now;
    }

    private static Map<String, Object> entry(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static String rowPath(String s) {
        return s.startsWith("$") || s.startsWith("@") || s.contains("(") ? s : "@." + s;
    }

    private static String docPath(String s) {
        return s.startsWith("$") || s.startsWith("@") || s.contains("(") ? s : "$." + s;
    }

    /** The last field name of a path, as a header: {@code $.legs[*].notional} gives {@code notional}. */
    private static String label(String s) {
        String t = s.replaceAll("\\[[^\\]]*\\]", "");
        int dot = t.lastIndexOf('.');
        String last = dot >= 0 ? t.substring(dot + 1) : t;
        return last.isBlank() ? s : last;
    }
}
