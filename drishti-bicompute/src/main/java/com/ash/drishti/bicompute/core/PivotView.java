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
package com.ash.drishti.bicompute.core;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A pivot view (PIVOT_ENGINE.md section 4.1): what a person asked and what a saved view stores. The API takes it as JSON:
 *
 * <pre>{@code
 * { "source": "trade", "date": "2026-10-30", "rows": ["LEGAL_ENTITY", "DESK"], "columns": ["TRADE_DATE:month"],
 *   "values": [{"measure": "sum(NOTIONAL_USD)", "as": "Notional"}, {"measure": "count()", "as": "Trades"}],
 *   "filters": {"PRODUCT_GROUP": {"in": ["RATES", "FX"]}, "TRADE_STATUS": {"notIn": ["MATURED"]}},
 *   "expanded": [{"axis": "rows", "path": [["LEGAL_ENTITY", "LE-UK"]], "by": "DESK"}],
 *   "expandTo": {"rows": 1, "columns": 1}, "sort": {"DESK": {"by": "Notional", "desc": true}},
 *   "top": {"DESK": {"n": 10, "by": "Notional"}}, "subtotals": "below" }
 * }</pre>
 *
 * Rows are nested levels opened lazily: only level one, the nodes named in {@code expanded} and the levels up to {@code expandTo} are
 * computed. A node may be expanded {@code by} any dimension (expand by…, per member); without {@code by} its children are the next row
 * dimension not on its path.
 *
 * @param source the source ({@code trade}, a kind of a pack; {@code var})
 * @param date the business date (null: the latest)
 * @param asOf a data version (null: the latest)
 * @param rows the row dimensions, outermost first
 * @param columns the column dimensions
 * @param values the measures
 * @param filters dimension id to its filter
 * @param expanded the nodes opened, with the dimension each is broken down by
 * @param expandRows open every row node down to this level (1: level one only)
 * @param expandColumns open every column node down to this level
 * @param sort per dimension: by a measure's label or by {@code label}, ascending or descending
 * @param top per dimension: its top N by a measure, the rest in one Others member
 * @param subtotals {@code below} or {@code above} each group
 */
public record PivotView(String source, String date, String asOf, List<Dim> rows, List<Dim> columns, List<Value> values,
        Map<String, AggregationPlan.Filter> filters, List<Expansion> expanded, int expandRows, int expandColumns, Map<String, Sort> sort,
        Map<String, Top> top, String subtotals) {

    /**
     * A measure of the view.
     *
     * @param measure the function's text ({@code sum(NOTIONAL_USD)})
     * @param as its column header (null: the text)
     * @param showAs a show-as transform of the finished figure: {@code pct-grand}, {@code pct-row}, {@code pct-column},
     *     {@code pct-parent-row}, {@code rank} (null: the figure)
     */
    public record Value(String measure, String as, String showAs) {
        public String label() {
            return as == null || as.isBlank() ? measure : as;
        }
    }

    /**
     * An open node.
     *
     * @param axis {@code rows} or {@code columns}
     * @param path the node's coordinates from the top: (dimension id, value) pairs
     * @param by the dimension its children are broken down by (null: the next of the axis)
     */
    public record Expansion(String axis, List<List<String>> path, String by) {
        public Expansion {
            path = path.stream().map(List::copyOf).toList();
        }
    }

    /** @param by a measure's label, or {@code label} (the default) @param desc descending */
    public record Sort(String by, boolean desc) {}

    /** @param n how many members are shown @param by the measure's label they are ranked by (null: the first measure) */
    public record Top(int n, String by) {}

    public PivotView {
        rows = List.copyOf(rows);
        columns = List.copyOf(columns);
        values = List.copyOf(values);
        filters = Map.copyOf(filters);
        expanded = List.copyOf(expanded);
        sort = Map.copyOf(sort);
        top = Map.copyOf(top);
        subtotals = "above".equals(subtotals) ? "above" : "below";
        expandRows = Math.max(1, expandRows);
        expandColumns = Math.max(1, expandColumns);
        if (source == null || source.isBlank()) {
            throw bad("a view needs a source (a table or a cube): \"source\": \"trade\"");
        }
        if (values.isEmpty()) {
            throw bad("a view needs at least one value: \"values\": [{\"measure\": \"sum(NOTIONAL_USD)\"}]");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Dim d : rows) {
            if (!seen.add(d.id())) {
                throw bad(d.id() + " is on the rows twice");
            }
        }
        for (Dim d : columns) {
            if (!seen.add(d.id())) {
                throw bad(d.id() + " is on the rows and the columns, or on the columns twice");
            }
        }
        Set<String> labels = new LinkedHashSet<>();
        for (Value v : values) {
            if (!labels.add(v.label())) {
                throw bad("two values are called '" + v.label() + "': name one with \"as\"");
            }
        }
    }

    /** The view refused in words ({@code DRS-5701}). */
    public static DrishtiException bad(String why) {
        return new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, why);
    }

    /** A view from its JSON map. */
    @SuppressWarnings("unchecked")
    public static PivotView parse(Map<String, Object> m) {
        if (m == null) {
            throw bad("no view was sent");
        }
        List<Dim> rows = dims(m.get("rows"));
        List<Dim> cols = dims(m.get("columns"));
        List<Value> values = new ArrayList<>();
        Object vs = m.get("values");
        if (vs instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> vm) {
                    values.add(new Value(str(vm.get("measure")), str(vm.get("as")), str(vm.get("showAs"))));
                } else if (o != null) {
                    values.add(new Value(String.valueOf(o), null, null));
                }
            }
        }
        Map<String, AggregationPlan.Filter> filters = new LinkedHashMap<>();
        if (m.get("filters") instanceof Map<?, ?> fm) {
            fm.forEach((k, v) -> {
                if (v instanceof Map<?, ?> f) {
                    filters.put(Dim.parse(k).id(), new AggregationPlan.Filter(strings(f.get("in")), strings(f.get("notIn"))));
                } else {
                    filters.put(Dim.parse(k).id(), new AggregationPlan.Filter(strings(v), List.of()));
                }
            });
        }
        List<Expansion> exp = new ArrayList<>();
        if (m.get("expanded") instanceof List<?> el) {
            for (Object o : el) {
                if (o instanceof Map<?, ?> em) {
                    String axis = "columns".equals(em.get("axis")) ? "columns" : "rows";
                    exp.add(new Expansion(axis, path(em.get("path")), str(em.get("by"))));
                }
            }
        }
        int er = 1;
        int ec = Math.max(1, cols.size());
        if (m.get("expandTo") instanceof Map<?, ?> et) {
            er = intOf(et.get("rows"), er);
            ec = intOf(et.get("columns"), ec);
        }
        Map<String, Sort> sort = new LinkedHashMap<>();
        if (m.get("sort") instanceof Map<?, ?> sm) {
            sm.forEach((k, v) -> {
                if (v instanceof Map<?, ?> s) {
                    sort.put(Dim.parse(k).id(), new Sort(str(s.get("by")), Boolean.TRUE.equals(s.get("desc")) || "true".equals(String.valueOf(s.get("desc")))));
                }
            });
        }
        Map<String, Top> top = new LinkedHashMap<>();
        if (m.get("top") instanceof Map<?, ?> tm) {
            tm.forEach((k, v) -> {
                if (v instanceof Map<?, ?> t) {
                    int n = intOf(t.get("n"), 10);
                    if (n < 1) {
                        throw bad("top " + n + " of " + k + ": show at least one member");
                    }
                    top.put(Dim.parse(k).id(), new Top(n, str(t.get("by"))));
                }
            });
        }
        return new PivotView(str(m.get("source")), str(m.get("date")), str(m.get("asOf")), rows, cols, values, filters, exp, er, ec, sort, top,
                str(m.get("subtotals")));
    }

    /** The view as its JSON map (what a saved view stores). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pivot", 1);
        m.put("source", source);
        if (date != null) {
            m.put("date", date);
        }
        if (asOf != null) {
            m.put("asOf", asOf);
        }
        m.put("rows", rows.stream().map(Dim::id).toList());
        m.put("columns", columns.stream().map(Dim::id).toList());
        List<Map<String, Object>> vs = new ArrayList<>();
        for (Value v : values) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("measure", v.measure());
            if (v.as() != null) {
                x.put("as", v.as());
            }
            if (v.showAs() != null) {
                x.put("showAs", v.showAs());
            }
            vs.add(x);
        }
        m.put("values", vs);
        Map<String, Object> f = new LinkedHashMap<>();
        filters.forEach((k, v) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            if (!v.in().isEmpty()) {
                x.put("in", v.in());
            }
            if (!v.notIn().isEmpty()) {
                x.put("notIn", v.notIn());
            }
            f.put(k, x);
        });
        m.put("filters", f);
        List<Map<String, Object>> ex = new ArrayList<>();
        for (Expansion e : expanded) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("axis", e.axis());
            x.put("path", e.path());
            if (e.by() != null) {
                x.put("by", e.by());
            }
            ex.add(x);
        }
        m.put("expanded", ex);
        m.put("expandTo", Map.of("rows", expandRows, "columns", expandColumns));
        Map<String, Object> s = new LinkedHashMap<>();
        sort.forEach((k, v) -> s.put(k, Map.of("by", v.by() == null ? "label" : v.by(), "desc", v.desc())));
        m.put("sort", s);
        Map<String, Object> t = new LinkedHashMap<>();
        top.forEach((k, v) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("n", v.n());
            if (v.by() != null) {
                x.put("by", v.by());
            }
            t.put(k, x);
        });
        m.put("top", t);
        m.put("subtotals", subtotals);
        return m;
    }

    /** The same view on another date (compare, time travel). */
    public PivotView on(String otherDate) {
        return new PivotView(source, otherDate, asOf, rows, columns, values, filters, expanded, expandRows, expandColumns, sort, top, subtotals);
    }

    /** The same view with other nodes open. */
    public PivotView withExpanded(List<Expansion> e, int er, int ec) {
        return new PivotView(source, date, asOf, rows, columns, values, filters, e, er, ec, sort, top, subtotals);
    }

    private static List<Dim> dims(Object o) {
        List<Dim> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                out.add(Dim.parse(x));
            }
        } else if (o instanceof String s && !s.isBlank()) {
            for (String x : s.split(",")) {
                out.add(Dim.parse(x.strip()));
            }
        }
        return out;
    }

    static List<List<String>> path(Object o) {
        List<List<String>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof List<?> pair && pair.size() == 2) {
                    out.add(List.of(Dim.parse(pair.get(0)).id(), String.valueOf(pair.get(1))));
                } else if (x instanceof Map<?, ?> pm && pm.size() == 1) {
                    Map.Entry<?, ?> e = pm.entrySet().iterator().next();
                    out.add(List.of(Dim.parse(e.getKey()).id(), String.valueOf(e.getValue())));
                } else {
                    throw bad("a node's path is a list of [dimension, value] pairs, not " + x);
                }
            }
        }
        return out;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        } else if (o != null && !String.valueOf(o).isBlank()) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).strip();
    }

    private static int intOf(Object o, int dflt) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? dflt : Integer.parseInt(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Lower-case words, for the show-as names. */
    static String low(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
