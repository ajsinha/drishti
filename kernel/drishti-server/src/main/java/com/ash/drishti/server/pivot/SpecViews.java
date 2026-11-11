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
package com.ash.drishti.server.pivot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Sutra's or a pack's {@code pivot:} arrangement (Rachana {@code PivotSpec}: rows, columns, values with their aggregation and show-as,
 * filters) as the pivot engine's view (PIVOT_GUIDE.md section 13): the view a panel's or a kind's Pivot opens with. And back: a view as
 * the arrangement a Sutra's {@code pivot:} block holds, for an author's promotion. What one cannot say the other refuses in words.
 */
public final class SpecViews {

    private SpecViews() {}

    /** The aggregation of an arrangement as a function of the engine. */
    static String function(String agg) {
        return switch (agg == null ? "sum" : agg) {
            case "distinct" -> "count_distinct";
            default -> agg;
        };
    }

    static String showAs(String show) {
        return switch (show == null ? "value" : show) {
            case "pctRow" -> "pct-row";
            case "pctColumn" -> "pct-column";
            case "pctTotal" -> "pct-grand";
            default -> null;
        };
    }

    /** The arrangement ({@code PivotSpec.arrangementMap()}) as a view of {@code source}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> view(String source, String date, Map<String, Object> arrangement) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("source", source);
        if (date != null) {
            v.put("date", date);
        }
        v.put("rows", arrangement.getOrDefault("rows", List.of()));
        v.put("columns", arrangement.getOrDefault("columns", List.of()));
        List<Map<String, Object>> values = new ArrayList<>();
        Object vs = arrangement.get("values");
        if (vs instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    String field = m.get("field") == null ? null : String.valueOf(m.get("field"));
                    String fn = function(m.get("agg") == null ? null : String.valueOf(m.get("agg")));
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("measure", "count".equals(fn) && field == null ? "count()" : fn + "(" + (field == null ? "" : field) + ")");
                    String sa = showAs(m.get("show") == null ? null : String.valueOf(m.get("show")));
                    if (sa != null) {
                        x.put("showAs", sa);
                    }
                    values.add(x);
                }
            }
        }
        if (values.isEmpty()) {
            values.add(Map.of("measure", "count()"));
        }
        v.put("values", values);
        Map<String, Object> filters = new LinkedHashMap<>();
        Object fs = arrangement.get("filters");
        if (fs instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && m.get("values") instanceof List<?> vals && !vals.isEmpty()) {
                    filters.put(String.valueOf(m.get("field")), Map.of("in", vals.stream().map(String::valueOf).toList()));
                }
            }
        }
        v.put("filters", filters);
        if (arrangement.get("mode") != null) {
            v.put("mode", String.valueOf(arrangement.get("mode")));      // how the pivot screen opens it: views, compact or panes
        }
        return v;
    }

    /**
     * A view as a Sutra's {@code pivot:} arrangement: refused in words when it says what an arrangement cannot (a function other than
     * sum, count, avg, min, max and the distinct count; a grouped date or a width; a show-as other than the shares; a filter that leaves
     * values out).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> arrangement(Map<String, Object> view) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("rows", plain((List<Object>) view.getOrDefault("rows", List.of()), "rows"));
        a.put("columns", plain((List<Object>) view.getOrDefault("columns", List.of()), "columns"));
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object o : (List<Object>) view.getOrDefault("values", List.of())) {
            Map<String, Object> m = o instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of("measure", String.valueOf(o));
            String measure = String.valueOf(m.get("measure"));
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\w+)\\(\\s*([\\w.]*)\\s*\\)").matcher(measure);
            if (!mm.matches()) {
                throw new IllegalArgumentException(measure + " cannot be a Sutra's pivot value (one field and sum, count, avg, min, max or distinct)");
            }
            String agg = switch (mm.group(1)) {
                case "count_distinct" -> "distinct";
                case "sum", "count", "avg", "min", "max" -> mm.group(1);
                default -> throw new IllegalArgumentException(measure + ": a Sutra's pivot sums, counts, averages, takes the min or max, or counts "
                        + "distinct values; " + mm.group(1) + " stays in the saved view");
            };
            Map<String, Object> x = new LinkedHashMap<>();
            if (!mm.group(2).isEmpty()) {
                x.put("field", mm.group(2));
            } else if (!"count".equals(agg)) {
                throw new IllegalArgumentException(measure + " names no field");
            }
            x.put("agg", agg);
            Object sa = m.get("showAs");
            if (sa != null) {
                x.put("show", switch (String.valueOf(sa)) {
                    case "pct-row" -> "pctRow";
                    case "pct-column" -> "pctColumn";
                    case "pct-grand" -> "pctTotal";
                    default -> throw new IllegalArgumentException("show-as " + sa + " stays in the saved view: a Sutra's pivot shows % of row, "
                            + "column or total");
                });
            }
            values.add(x);
        }
        a.put("values", values);
        List<Object> filters = new ArrayList<>();
        if (view.get("filters") instanceof Map<?, ?> fm) {
            fm.forEach((k, f) -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("field", String.valueOf(k));
                if (f instanceof Map<?, ?> fmap) {
                    if (fmap.get("notIn") instanceof List<?> n && !n.isEmpty()) {
                        throw new IllegalArgumentException("the filter on " + k + " leaves values out; a Sutra's pivot keeps values (in)");
                    }
                    if (fmap.get("in") instanceof List<?> in && !in.isEmpty()) {
                        x.put("values", in.stream().map(String::valueOf).toList());
                    }
                }
                filters.add(x.size() == 1 ? k : x);
            });
        }
        a.put("filters", filters);
        if (view.get("mode") != null && com.ash.drishti.rachana.model.PivotSpec.MODES.contains(String.valueOf(view.get("mode")))) {
            a.put("mode", String.valueOf(view.get("mode")));
        }
        return a;
    }

    private static List<String> plain(List<Object> dims, String axis) {
        List<String> out = new ArrayList<>();
        for (Object d : dims) {
            String s = String.valueOf(d);
            if (s.contains(":")) {
                throw new IllegalArgumentException(s + " on the " + axis + ": a Sutra's pivot names fields, not grouped dates or widths");
            }
            out.add(s);
        }
        return out;
    }
}
