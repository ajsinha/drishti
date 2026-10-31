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
package com.ash.drishti.engine.history;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One kind's {@code history:} entry of a pack (DATA_ARCHITECTURE.md › History queries: an entity over time, section 6.1): which columns, parts and chart fields its history shows,
 * and its limits. {@link #parse} reports problems instead of throwing: an {@code error:} problem makes the block unusable
 * ({@code DRS-2061}), a {@code warning:} is shown and the rest is used.
 *
 * @param kind the kind
 * @param enabled false: no history for the kind ({@code DRS-1012})
 * @param shape {@code document} or {@code market}
 * @param columns the columns asked for; empty means the defaults (key columns first, then every top-level scalar)
 * @param exclude top-level fields never shown
 * @param ignoreChanges fields shown whose change is not "a change"
 * @param parts the top-level lists and objects shown as counts; null means every one
 * @param chart the market shape's chart fields (at most 4)
 * @param headline the field the high, low and change are taken on; null means the first chart field
 * @param series an embedded series in the newest document, used when no source keeps daily documents; null when none
 * @param defaultDays the range when none is named, or 0 for the server's
 * @param maxDays the most business days, or 0 for the server's
 */
public record HistorySpec(String kind, boolean enabled, String shape, List<Column> columns, Set<String> exclude, Set<String> ignoreChanges,
        List<String> parts, List<Column> chart, String headline, Series series, int defaultDays, int maxDays) {

    public static final String DOCUMENT = "document";
    public static final String MARKET = "market";

    /** A column: a top-level path, or a Rachana-EL expression ({@code first($.LEGS).NOTIONAL}) with its label and format. */
    public record Column(String path, String expr, String label, String format) {
        /** The key a row's values use: the path, else the expression. */
        public String key() {
            return path != null ? path : expr;
        }
    }

    /** A list inside the document with a date per item ({@code HISTORY: [{DATE, CLOSE}]}). */
    public record Series(String path, String date, List<String> values) {}

    private static final Set<String> KEYS = Set.of("enabled", "shape", "columns", "exclude", "ignore-changes", "parts", "chart", "headline",
            "series", "default-days", "max-days");

    /** A kind with no {@code history:} entry: everything by default. */
    public static HistorySpec defaults(String kind) {
        return new HistorySpec(kind, true, DOCUMENT, List.of(), Set.of(), Set.of(), null, List.of(), null, null, 0, 0);
    }

    public boolean market() {
        return MARKET.equals(shape);
    }

    /** The field the summary is taken on: the headline, else the first chart field; null for the document shape. */
    public String headlineKey() {
        if (headline != null) {
            return headline;
        }
        return chart.isEmpty() ? null : chart.get(0).key();
    }

    /**
     * Reads one kind's entry. {@code serverMaxDays} bounds {@code max-days}. Problems are added to {@code problems} as
     * {@code "error: history.<kind>.<key>: ..."} or {@code "warning: ..."}.
     */
    public static HistorySpec parse(String kind, Object raw, int serverMaxDays, List<String> problems) {
        String at = "history." + kind;
        if (raw == null) {
            return defaults(kind);
        }
        if (raw instanceof Boolean b) {
            return b ? defaults(kind) : new HistorySpec(kind, false, DOCUMENT, List.of(), Set.of(), Set.of(), null, List.of(), null, null, 0, 0);
        }
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add("error: " + at + ": expected a map of settings (columns, chart, ...) or true/false");
            return defaults(kind);
        }
        for (Object k : m.keySet()) {
            if (!KEYS.contains(String.valueOf(k))) {
                problems.add("warning: " + at + "." + k + ": unknown key, ignored (known: " + String.join(", ", new java.util.TreeSet<>(KEYS)) + ")");
            }
        }
        boolean enabled = bool(m.get("enabled"), true, at + ".enabled", problems);
        List<Column> columns = columns(m.get("columns"), at + ".columns", problems);
        List<Column> chart = columns(m.get("chart"), at + ".chart", problems);
        if (chart.size() > 4) {
            problems.add("error: " + at + ".chart: at most 4 chart fields (" + chart.size() + " given)");
        }
        String shape;
        Object s = m.get("shape");
        if (s == null) {
            shape = chart.isEmpty() ? DOCUMENT : MARKET;
        } else if (DOCUMENT.equals(String.valueOf(s)) || MARKET.equals(String.valueOf(s))) {
            shape = String.valueOf(s);
        } else {
            problems.add("error: " + at + ".shape: '" + s + "' is not document or market");
            shape = DOCUMENT;
        }
        String headline = m.get("headline") == null ? null : String.valueOf(m.get("headline"));
        if (headline != null && chart.stream().noneMatch(c -> headline.equals(c.key()))) {
            problems.add("error: " + at + ".headline: " + headline + " is not one of chart");
        }
        Series series = series(m.get("series"), at + ".series", problems);
        int maxDays = intIn(m.get("max-days"), 0, 1, serverMaxDays, at + ".max-days", problems);
        int bound = maxDays > 0 ? maxDays : serverMaxDays;
        int defaultDays = intIn(m.get("default-days"), 0, 1, bound, at + ".default-days", problems);
        List<String> parts = m.containsKey("parts") ? paths(m.get("parts"), at + ".parts", problems) : null;
        return new HistorySpec(kind, enabled, shape, columns, Set.copyOf(paths(m.get("exclude"), at + ".exclude", problems)),
                Set.copyOf(paths(m.get("ignore-changes"), at + ".ignore-changes", problems)), parts, chart, headline, series, defaultDays, maxDays);
    }

    private static boolean bool(Object v, boolean dflt, String at, List<String> problems) {
        if (v == null) {
            return dflt;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        problems.add("error: " + at + ": expected true or false, not '" + v + "'");
        return dflt;
    }

    private static int intIn(Object v, int dflt, int min, int max, String at, List<String> problems) {
        if (v == null) {
            return dflt;
        }
        int n;
        try {
            n = v instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            problems.add("error: " + at + ": '" + v + "' is not a whole number");
            return dflt;
        }
        if (n < min || n > max) {
            problems.add("error: " + at + ": " + n + " is not between " + min + " and " + max);
            return dflt;
        }
        return n;
    }

    private static List<String> paths(Object v, String at, List<String> problems) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            problems.add("error: " + at + ": expected a list of field paths");
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object o : l) {
            String p = strip(String.valueOf(o));
            if (p.isBlank()) {
                problems.add("error: " + at + ": an empty path");
            } else {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    private static List<Column> columns(Object v, String at, List<String> problems) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            problems.add("error: " + at + ": expected a list of paths or {path | expr, label, format}");
            return List.of();
        }
        List<Column> out = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof Map<?, ?> c) {
                Object path = c.get("path");
                Object expr = c.get("expr");
                if ((path == null) == (expr == null)) {
                    problems.add("error: " + at + ": each column names a path or an expr, not both nor neither (" + c + ")");
                    continue;
                }
                out.add(new Column(path == null ? null : strip(String.valueOf(path)), expr == null ? null : String.valueOf(expr),
                        c.get("label") == null ? null : String.valueOf(c.get("label")), c.get("format") == null ? null : String.valueOf(c.get("format"))));
            } else if (o != null && !String.valueOf(o).isBlank()) {
                out.add(new Column(strip(String.valueOf(o)), null, null, null));
            } else {
                problems.add("error: " + at + ": an empty column");
            }
        }
        return List.copyOf(out);
    }

    private static Series series(Object v, String at, List<String> problems) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof Map<?, ?> m) || m.get("path") == null || m.get("date") == null || (m.get("value") == null && m.get("values") == null)) {
            problems.add("error: " + at + ": expected {path, date, value | values}");
            return null;
        }
        List<String> values = m.get("values") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(String.valueOf(m.get("value")));
        return new Series(strip(String.valueOf(m.get("path"))), String.valueOf(m.get("date")), values);
    }

    private static String strip(String p) {
        String t = p.trim();
        return t.startsWith("$.") ? t.substring(2) : t;
    }

    /** True when any problem is an error (the block cannot be used). */
    public static boolean hasErrors(List<String> problems) {
        return problems.stream().anyMatch(p -> p.startsWith("error:"));
    }
}
