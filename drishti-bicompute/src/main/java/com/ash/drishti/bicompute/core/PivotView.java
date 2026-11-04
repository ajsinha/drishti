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
 * @param options the provider's own settings of the view, by name (PIVOT_ENGINE.md section 6.3: the VaR provider's {@code scope},
 *     {@code horizon}, {@code confidence}, {@code esConfidence}, {@code method}, {@code set} and {@code ccy}); part of every cache key; the
 *     VaR provider refuses a name it does not know, a provider without options ignores them
 */
public record PivotView(String source, String date, String asOf, List<Dim> rows, List<Dim> columns, List<Value> values,
        Map<String, AggregationPlan.Filter> filters, List<Expansion> expanded, int expandRows, int expandColumns, Map<String, Sort> sort,
        Map<String, Top> top, String subtotals, Currency currency, List<String> slicers, Map<String, Format> formats, Map<String, String> options) {

    /** A view without provider options. */
    public PivotView(String source, String date, String asOf, List<Dim> rows, List<Dim> columns, List<Value> values,
            Map<String, AggregationPlan.Filter> filters, List<Expansion> expanded, int expandRows, int expandColumns, Map<String, Sort> sort,
            Map<String, Top> top, String subtotals, Currency currency, List<String> slicers, Map<String, Format> formats) {
        this(source, date, asOf, rows, columns, values, filters, expanded, expandRows, expandColumns, sort, top, subtotals, currency, slicers,
                formats, Map.of());
    }

    /**
     * A conditional format of a measure (PIVOT_ENGINE.md section 14.1): drawn by the screen from the finished figures (a colour scale or
     * data bars over a level's cells), never changing a figure; kept with the view.
     *
     * @param style {@code scale} or {@code bars}
     */
    public record Format(String style) {
        public Format {
            if (!"scale".equals(style) && !"bars".equals(style)) {
                throw bad("a format is scale or bars, not " + style);
            }
        }
    }

    /**
     * The explicit conversion of money (PIVOT_ENGINE.md section 5.4): every amount of a money field is converted from the currency of its
     * row ({@code field}, else the source's currency field) into {@code report} at the {@code rates} given (units of {@code report} per one
     * unit of the currency), named by {@code source} in every cell's provenance. A row in a currency without a rate is left out of the
     * converted figures, and the cell says how. Without a conversion, a money total over several currencies is refused in words.
     *
     * @param report the reporting currency (three letters)
     * @param field the field of a row's currency (null: the source's currency field)
     * @param rates currency to its rate into {@code report}
     * @param source where the rates come from, in words ({@code ECB close 2026-10-30})
     */
    public record Currency(String report, String field, Map<String, Double> rates, String source) {
        public Currency {
            if (report == null || !report.matches("[A-Z]{3}")) {
                throw bad("currency.report is three capital letters (USD), not " + report);
            }
            rates = new java.util.TreeMap<>(rates == null ? Map.of() : rates);
            for (Map.Entry<String, Double> e : rates.entrySet()) {
                if (!e.getKey().matches("[A-Z]{3}") || e.getValue() == null || !(e.getValue() > 0) || e.getValue().isInfinite()) {
                    throw bad("currency.rates: " + e.getKey() + " = " + e.getValue() + " is not a currency and a positive rate");
                }
            }
            source = source == null || source.isBlank() ? "rates given in the view" : source.strip();
        }

        /** The rate of a currency into the report currency (1 for the report currency itself), or null when none is given. */
        public Double rate(String ccy) {
            return report.equals(ccy) ? Double.valueOf(1.0) : rates.get(ccy);
        }
    }

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
        slicers = slicers == null ? List.of() : List.copyOf(slicers);
        formats = formats == null ? Map.of() : Map.copyOf(formats);
        options = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(options == null ? Map.of() : options));
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
        Currency ccy = null;
        if (m.get("currency") instanceof Map<?, ?> cm) {
            Map<String, Double> rates = new LinkedHashMap<>();
            if (cm.get("rates") instanceof Map<?, ?> rm) {
                rm.forEach((k, v) -> {
                    try {
                        rates.put(String.valueOf(k).strip().toUpperCase(Locale.ROOT), v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v)));
                    } catch (NumberFormatException e) {
                        throw bad("currency.rates." + k + " is not a number: " + v);
                    }
                });
            }
            ccy = new Currency(str(cm.get("report")) == null ? null : str(cm.get("report")).toUpperCase(Locale.ROOT), str(cm.get("field")), rates,
                    str(cm.get("source")));
        }
        List<String> slicers = new ArrayList<>();
        if (m.get("slicers") instanceof List<?> sl) {
            sl.forEach(x -> slicers.add(Dim.parse(x).id()));
        }
        Map<String, Format> formats = new LinkedHashMap<>();
        if (m.get("formats") instanceof Map<?, ?> fm) {
            fm.forEach((k, v) -> formats.put(String.valueOf(k), new Format(v instanceof Map<?, ?> x ? str(x.get("style")) : str(v))));
        }
        Map<String, String> options = new LinkedHashMap<>();
        if (m.get("options") instanceof Map<?, ?> om) {
            om.forEach((k, v) -> {
                if (str(v) != null) {
                    options.put(String.valueOf(k).strip(), str(v));
                }
            });
        }
        return new PivotView(str(m.get("source")), str(m.get("date")), str(m.get("asOf")), rows, cols, values, filters, exp, er, ec, sort, top,
                str(m.get("subtotals")), ccy, slicers, formats, options);
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
        if (!slicers.isEmpty()) {
            m.put("slicers", slicers);
        }
        if (!formats.isEmpty()) {
            Map<String, Object> f2 = new LinkedHashMap<>();
            formats.forEach((k, v) -> f2.put(k, Map.of("style", v.style())));
            m.put("formats", f2);
        }
        if (currency != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("report", currency.report());
            if (currency.field() != null) {
                c.put("field", currency.field());
            }
            c.put("rates", currency.rates());
            c.put("source", currency.source());
            m.put("currency", c);
        }
        if (!options.isEmpty()) {
            m.put("options", options);
        }
        return m;
    }

    /** The canonical text of the options (part of every cache key; empty without options). */
    public String optionSignature() {
        return options.isEmpty() ? "" : options.toString();
    }

    /** The same view on another date (compare, time travel). */
    public PivotView on(String otherDate) {
        return new PivotView(source, otherDate, asOf, rows, columns, values, filters, expanded, expandRows, expandColumns, sort, top, subtotals,
                currency, slicers, formats, options);
    }

    /** The same view with other nodes open. */
    public PivotView withExpanded(List<Expansion> e, int er, int ec) {
        return new PivotView(source, date, asOf, rows, columns, values, filters, e, er, ec, sort, top, subtotals, currency, slicers, formats,
                options);
    }

    /** The same view with other provider options (the MR lens's scope and lens). */
    public PivotView withOptions(Map<String, String> o) {
        return new PivotView(source, date, asOf, rows, columns, values, filters, expanded, expandRows, expandColumns, sort, top, subtotals, currency,
                slicers, formats, o);
    }

    /** The same view with other rows, columns and values (the MR lens's history and what-if read a view's rows). */
    public PivotView reshaped(List<Dim> r, List<Dim> c, List<Value> v) {
        return new PivotView(source, date, asOf, r, c, v, filters, List.of(), 1, Math.max(1, c.size()), Map.of(), Map.of(), subtotals, currency,
                List.of(), Map.of(), options);
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
