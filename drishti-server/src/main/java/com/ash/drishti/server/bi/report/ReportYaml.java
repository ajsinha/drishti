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
package com.ash.drishti.server.bi.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes a report model as YAML the way a person writes one (RUPAKA.md section 8, docs/guides/REPORTS.md): block style for the document, its pages and its visuals, flow
 * style for the small parts ({@code at: { x: 0, y: 0, w: 6, h: 4 }}, {@code wells: { axis: [trades.desk], values: [MTM] }}, a filter), keys in the order the
 * reference lists them, and a string quoted only when it would otherwise be read as something else. What it writes is read back by {@link ReportParser} to the
 * same model. (A general YAML library writes every nested map as a block, quotes {@code y} and {@code n}, and loses the order; none of that reads well.)
 */
public final class ReportYaml {

    private static final List<String> TOP = List.of("rachana", "report", "version", "title", "description", "sample", "dataset", "theme", "dates", "filters", "bookmarks", "layouts", "pages");
    private static final List<String> PAGE = List.of("id", "title", "drillthrough", "filters", "visuals");
    private static final List<String> VISUAL = List.of("id", "type", "title", "at", "wells", "filters", "sort", "limit", "hierarchy", "drillthrough", "format", "text", "src", "alt",
            "interacts", "action");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_ ./()&%+\\-]*");
    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");
    private static final Set<String> WORDS = Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n", "~");

    private final StringBuilder out = new StringBuilder();

    private ReportYaml() {}

    public static String write(Map<String, ?> model) {
        ReportYaml w = new ReportYaml();
        w.map(ordered(model, TOP), 0, TOP, 0);
        return w.out.toString();
    }

    // ---- block style -------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void map(Map<String, ?> m, int indent, List<String> order, int depth) {
        for (var e : ordered(m, order).entrySet()) {
            Object v = e.getValue();
            String key = key(e.getKey());
            if (v instanceof List<?> l && "pages".equals(e.getKey()) && depth == 0) {
                line(indent, key + ":");
                for (Object p : l) {
                    item(indent + 2, (Map<String, Object>) p, PAGE, 1);
                }
            } else if (v instanceof List<?> l && "visuals".equals(e.getKey()) && depth == 1) {
                line(indent, key + ":");
                for (Object x : l) {
                    item(indent + 2, (Map<String, Object>) x, VISUAL, 2);
                }
            } else if (v instanceof Map<?, ?> lm && "layouts".equals(e.getKey()) && depth == 0) {
                line(indent, key + ":");
                lm.forEach((device, byPage) -> {
                    line(indent + 2, key(String.valueOf(device)) + ":");
                    ((Map<?, ?>) byPage).forEach((page, lay) -> line(indent + 4, key(String.valueOf(page)) + ": " + flow(lay)));
                });
            } else if (v instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
                line(indent, key + ":");
                for (Object x : l) {
                    line(indent + 2, "- " + flow(x));
                }
            } else {
                line(indent, key + ": " + flow(v));
            }
        }
    }

    /** A list item that is a mapping: {@code - key: value} then the other keys under it. */
    private void item(int indent, Map<String, Object> m, List<String> order, int depth) {
        int mark = out.length();
        map(m, indent + 2, order, depth);
        String body = out.substring(mark);
        out.setLength(mark);
        int dash = indent;
        out.append(" ".repeat(dash)).append("- ").append(body.substring(indent + 2));
    }

    private void line(int indent, String text) {
        out.append(" ".repeat(indent)).append(text).append('\n');
    }

    // ---- flow style and scalars --------------------------------------------------------------------------------------

    private static String flow(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            List<String> parts = new ArrayList<>();
            m.forEach((k, x) -> parts.add(key(String.valueOf(k)) + ": " + flow(x)));
            return "{ " + String.join(", ", parts) + " }";
        }
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            l.forEach(x -> parts.add(flow(x)));
            return "[" + String.join(", ", parts) + "]";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return scalar(String.valueOf(v));
    }

    static String key(String k) {
        return PLAIN.matcher(k).matches() && !k.contains(" ") ? k : scalar(k);
    }

    static String scalar(String s) {
        boolean plain = !s.isEmpty() && PLAIN.matcher(s).matches() && !s.endsWith(" ") && !NUMBER.matcher(s).matches() && !WORDS.contains(s.toLowerCase(Locale.ROOT));
        if (plain) {
            return s;
        }
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> q.append("\\\"");
                case '\\' -> q.append("\\\\");
                case '\n' -> q.append("\\n");
                case '\t' -> q.append("\\t");
                default -> q.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return q.append('"').toString();
    }

    private static Map<String, Object> ordered(Map<String, ?> m, List<String> order) {
        Map<String, Object> out = new LinkedHashMap<>();
        order.forEach(k -> {
            if (m.containsKey(k)) {
                out.put(k, m.get(k));
            }
        });
        m.forEach((k, v) -> out.putIfAbsent(k, v));
        return out;
    }
}
