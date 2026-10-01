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
package com.ash.drishti.rachana.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An interactive pivot a table-like panel (or a kind's search results) opts into: the fields a user may pivot by, and the
 * arrangement it opens with. Written {@code pivot: true} (the panel's own columns, no default arrangement) or as a mapping:
 *
 * <pre>
 * pivot:
 *   fields: [product, currency, maturityBucket, notional, mtm]   # row paths; { field, bind, label, fmt } for an expression
 *   rows: [product]
 *   columns: [maturityBucket]
 *   values: [{ field: notional, agg: sum }]
 *   filters: [currency]                                          # or { field, values: [..] } / { field, min, max }
 *   heat: true
 *   chart: bar
 * </pre>
 *
 * The same object, with document paths for fields, opts a kind's search results in ({@code pivot:} in pack.yaml). Parsing
 * collects every problem (the Sutra's {@code DRS-2031}); a spec is immutable and thread-safe.
 *
 * @param fields the fields offered, in order (for {@code pivot: true}, the panel's columns or the kind's key fields)
 * @param explicit true when the Sutra (or pack) listed {@code fields} itself
 */
public record PivotSpec(List<Field> fields, boolean explicit, List<String> rows, List<String> columns, List<Value> values, List<Filter> filters,
        boolean heat, String chart, boolean totals) {

    /** A field: its name (used by rows, columns, values and filters), the expression it reads, and how it is shown. */
    public record Field(String name, String bind, String label, String fmt) {}

    /** A value cell: a field, how it is combined, and how it is shown (as itself or as a share). */
    public record Value(String field, String agg, String show) {}

    /** A filter: the values kept (text), or an inclusive range ({@code min}, {@code max}: numbers or ISO dates); all null keeps all. */
    public record Filter(String field, List<String> values, Object min, Object max) {}

    /** Where a spec comes from: a panel (fields are paths over the row, or expressions) or a kind (document paths only). */
    public enum Mode { PANEL, KIND }

    /** A parsed spec ({@code null} when the option is {@code false}) and every problem found. */
    public record Parsed(PivotSpec spec, List<String> problems) {}

    public static final List<String> AGGREGATIONS = List.of("sum", "count", "avg", "min", "max", "distinct");
    public static final List<String> SHOWS = List.of("value", "pctRow", "pctColumn", "pctTotal");
    public static final List<String> CHARTS = List.of("bar", "line", "heatmap");
    public static final int MAX_LEVELS = 4;
    public static final int MAX_VALUES = 6;
    public static final int MAX_FIELDS = 40;
    static final List<String> KEYS = List.of("fields", "rows", "columns", "values", "filters", "heat", "chart", "totals");
    private static final List<String> ARRANGEMENT = List.of("rows", "columns", "values", "filters", "heat", "chart");
    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,63}");
    private static final Pattern ROW_PATH = Pattern.compile("@\\.(" + PATH.pattern() + ")");

    public PivotSpec {
        fields = List.copyOf(fields);
        rows = List.copyOf(rows);
        columns = List.copyOf(columns);
        values = List.copyOf(values);
        filters = List.copyOf(filters);
    }

    /** The field called {@code name}, or null. */
    public Field field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    /** Every field name, in order. */
    public List<String> names() {
        return fields.stream().map(Field::name).toList();
    }

    /** The same fields with another arrangement. */
    public PivotSpec with(PivotSpec arrangement) {
        return new PivotSpec(fields, explicit, arrangement.rows, arrangement.columns, arrangement.values, arrangement.filters,
                arrangement.heat, arrangement.chart, totals);
    }

    // ---- parsing ------------------------------------------------------------------------------------------------

    /**
     * Reads a {@code pivot:} value.
     *
     * @param raw {@code true}, {@code false} or a mapping (plain maps and lists, as YAML or JSON reads them)
     * @param mode a panel's (row paths and expressions) or a kind's (document paths)
     * @param implicit the fields offered when the mapping lists none (the panel's columns, the kind's key fields)
     */
    public static Parsed parse(Object raw, Mode mode, List<Field> implicit) {
        List<String> problems = new ArrayList<>();
        if (Boolean.FALSE.equals(raw)) {
            return new Parsed(null, problems);
        }
        if (Boolean.TRUE.equals(raw)) {
            return new Parsed(new PivotSpec(implicit, false, List.of(), List.of(), List.of(), List.of(), false, null, true), problems);
        }
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add("'pivot' is true, false, or a mapping of " + String.join(", ", KEYS) + "; not '" + raw + "'");
            return new Parsed(null, problems);
        }
        for (Object k : m.keySet()) {
            if (!KEYS.contains(String.valueOf(k))) {
                problems.add("unknown key '" + k + "' in pivot; expected " + String.join(", ", KEYS));
            }
        }
        boolean explicit = m.containsKey("fields");
        List<Field> fields = explicit ? fields(m.get("fields"), mode, problems) : implicit;
        Set<String> known = new HashSet<>();
        fields.forEach(f -> known.add(f.name()));
        PivotSpec spec = arrangement(m, fields, explicit, known, !explicit && implicit.isEmpty(), problems);
        return new Parsed(problems.isEmpty() ? spec : null, problems);
    }

    /**
     * Reads a user's arrangement ({@code rows}, {@code columns}, {@code values}, {@code filters}, {@code heat},
     * {@code chart}) against the fields a spec offers: what a saved pivot holds and a promotion writes.
     */
    public static Parsed arrangement(Object raw, PivotSpec offered) {
        List<String> problems = new ArrayList<>();
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add("a pivot arrangement is a mapping of " + String.join(", ", ARRANGEMENT));
            return new Parsed(null, problems);
        }
        for (Object k : m.keySet()) {
            if (!ARRANGEMENT.contains(String.valueOf(k))) {
                problems.add("unknown key '" + k + "' in a pivot arrangement; expected " + String.join(", ", ARRANGEMENT));
            }
        }
        PivotSpec spec = arrangement(m, offered.fields, offered.explicit, new HashSet<>(offered.names()), false, problems);
        return new Parsed(problems.isEmpty() ? new PivotSpec(offered.fields, offered.explicit, spec.rows, spec.columns, spec.values,
                spec.filters, spec.heat, spec.chart, offered.totals) : null, problems);
    }

    private static PivotSpec arrangement(Map<?, ?> m, List<Field> fields, boolean explicit, Set<String> known, boolean unknownFields,
            List<String> problems) {
        List<String> rows = names(m.get("rows"), "rows", known, unknownFields, problems);
        List<String> columns = names(m.get("columns"), "columns", known, unknownFields, problems);
        if (rows.size() > MAX_LEVELS || columns.size() > MAX_LEVELS) {
            problems.add("a pivot has at most " + MAX_LEVELS + " row fields and " + MAX_LEVELS + " column fields");
        }
        for (String r : rows) {
            if (columns.contains(r)) {
                problems.add("pivot field '" + r + "' is in both rows and columns");
            }
        }
        List<Value> values = values(m.get("values"), known, unknownFields, problems);
        List<Filter> filters = filters(m.get("filters"), known, unknownFields, problems);
        Object heat = m.get("heat");
        if (heat != null && !(heat instanceof Boolean)) {
            problems.add("pivot 'heat' is true or false, not '" + heat + "'");
        }
        Object totals = m.get("totals");
        if (totals != null && !(totals instanceof Boolean)) {
            problems.add("pivot 'totals' is true or false, not '" + totals + "'");
        }
        Object chart = m.get("chart");
        if (chart != null && !CHARTS.contains(String.valueOf(chart))) {
            problems.add("pivot 'chart' must be one of " + String.join(", ", CHARTS) + ", not '" + chart + "'");
        }
        return new PivotSpec(fields, explicit, rows, columns, values, filters, Boolean.TRUE.equals(heat),
                chart == null ? null : String.valueOf(chart), !Boolean.FALSE.equals(totals));
    }

    private static List<Field> fields(Object raw, Mode mode, List<String> problems) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            problems.add("pivot 'fields' is a non-empty list of field paths (or { field, bind, label, fmt })");
            return List.of();
        }
        if (list.size() > MAX_FIELDS) {
            problems.add("pivot 'fields' lists at most " + MAX_FIELDS + " fields, found " + list.size());
        }
        List<Field> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : list) {
            Field f = field(o, mode, problems);
            if (f == null) {
                continue;
            }
            if (!seen.add(f.name())) {
                problems.add("pivot field '" + f.name() + "' is listed twice");
                continue;
            }
            out.add(f);
        }
        return out;
    }

    private static Field field(Object o, Mode mode, List<String> problems) {
        if (o instanceof String s) {
            String t = s.strip();
            if (mode == Mode.KIND && t.startsWith("$.")) {
                t = t.substring(2);
            }
            if (mode == Mode.PANEL && t.startsWith("@.")) {
                t = t.substring(2);
            }
            if (!PATH.matcher(t).matches()) {
                problems.add("pivot field '" + s + "' is not a field path (letters, digits, _ and dots)"
                        + (mode == Mode.PANEL ? "; for an expression write { field: name, bind: <expression> }" : ""));
                return null;
            }
            return new Field(t, (mode == Mode.PANEL ? "@." : "$.") + t, null, null);
        }
        if (o instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) {
                if (!List.of("field", "bind", "label", "fmt").contains(String.valueOf(k))) {
                    problems.add("unknown key '" + k + "' in a pivot field; expected field, bind, label, fmt");
                }
            }
            String name = m.get("field") == null ? null : String.valueOf(m.get("field")).strip();
            if (name == null || !NAME.matcher(name).matches()) {
                problems.add("a pivot field needs 'field': a name of letters, digits, _, . and - (found '" + m.get("field") + "')");
                return null;
            }
            Object bind = m.get("bind");
            if (bind != null && mode == Mode.KIND) {
                problems.add("pivot field '" + name + "' of a kind is a document path: 'bind' is for panels");
                return null;
            }
            if (bind == null && !PATH.matcher(name).matches()) {
                problems.add("pivot field '" + name + "' is not a field path: give it a 'bind'");
                return null;
            }
            String b = bind == null ? (mode == Mode.PANEL ? "@." : "$.") + name : String.valueOf(bind);
            return new Field(name, b, text(m.get("label")), text(m.get("fmt")));
        }
        problems.add("a pivot field is a path or a mapping { field, bind, label, fmt }, not '" + o + "'");
        return null;
    }

    private static List<String> names(Object raw, String key, Set<String> known, boolean unknownFields, List<String> problems) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            problems.add("pivot '" + key + "' is a list of field names");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            String n = String.valueOf(o).strip();
            if (!unknownFields && !known.contains(n)) {
                problems.add(unknownField(n, key, known));
            } else if (out.contains(n)) {
                problems.add("pivot field '" + n + "' is twice in " + key);
            } else {
                out.add(n);
            }
        }
        return out;
    }

    private static List<Value> values(Object raw, Set<String> known, boolean unknownFields, List<String> problems) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            problems.add("pivot 'values' is a list of { field, agg, show }");
            return List.of();
        }
        if (list.size() > MAX_VALUES) {
            problems.add("a pivot shows at most " + MAX_VALUES + " values, found " + list.size());
        }
        List<Value> out = new ArrayList<>();
        for (Object o : list) {
            String field;
            String agg = "sum";
            String show = "value";
            if (o instanceof Map<?, ?> m) {
                for (Object k : m.keySet()) {
                    if (!List.of("field", "agg", "show").contains(String.valueOf(k))) {
                        problems.add("unknown key '" + k + "' in a pivot value; expected field, agg, show");
                    }
                }
                field = m.get("field") == null ? "" : String.valueOf(m.get("field")).strip();
                if (m.get("agg") != null) {
                    agg = String.valueOf(m.get("agg")).strip();
                }
                if (m.get("show") != null) {
                    show = String.valueOf(m.get("show")).strip();
                }
            } else {
                field = String.valueOf(o).strip();
            }
            if (!AGGREGATIONS.contains(agg)) {
                problems.add("pivot value 'agg' must be one of " + String.join(", ", AGGREGATIONS) + ", not '" + agg + "'");
            }
            if (!SHOWS.contains(show)) {
                problems.add("pivot value 'show' must be one of " + String.join(", ", SHOWS) + ", not '" + show + "'");
            }
            if (!unknownFields && !known.contains(field)) {
                problems.add(unknownField(field, "values", known));
                continue;
            }
            out.add(new Value(field, agg, show));
        }
        return out;
    }

    private static List<Filter> filters(Object raw, Set<String> known, boolean unknownFields, List<String> problems) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            problems.add("pivot 'filters' is a list of field names (or { field, values } / { field, min, max })");
            return List.of();
        }
        List<Filter> out = new ArrayList<>();
        for (Object o : list) {
            Filter f;
            if (o instanceof Map<?, ?> m) {
                for (Object k : m.keySet()) {
                    if (!List.of("field", "values", "min", "max").contains(String.valueOf(k))) {
                        problems.add("unknown key '" + k + "' in a pivot filter; expected field, values, min, max");
                    }
                }
                Object vs = m.get("values");
                if (vs != null && !(vs instanceof List<?>)) {
                    problems.add("pivot filter 'values' is a list");
                    vs = null;
                }
                if (vs != null && (m.get("min") != null || m.get("max") != null)) {
                    problems.add("a pivot filter keeps either 'values' or a range ('min', 'max'), not both");
                }
                for (String end : new String[] {"min", "max"}) {
                    Object e = m.get(end);
                    if (e != null && !(e instanceof Number) && !(e instanceof String)) {
                        problems.add("pivot filter '" + end + "' is a number or a date");
                    }
                }
                f = new Filter(m.get("field") == null ? "" : String.valueOf(m.get("field")).strip(),
                        vs == null ? null : ((List<?>) vs).stream().map(String::valueOf).toList(), plain(m.get("min")), plain(m.get("max")));
            } else {
                f = new Filter(String.valueOf(o).strip(), null, null, null);
            }
            if (!unknownFields && !known.contains(f.field())) {
                problems.add(unknownField(f.field(), "filters", known));
                continue;
            }
            out.add(f);
        }
        return out;
    }

    /** A range end as a whole number (Long), another number (Double) or text, whichever reader produced it. */
    private static Object plain(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) n.longValue() : (Object) d;
        }
        return v == null ? null : String.valueOf(v);
    }

    private static String unknownField(String name, String key, Set<String> known) {
        return "pivot " + key + " name '" + name + "', which is not one of its fields (" + String.join(", ", known.stream().sorted().toList()) + ")";
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ---- implicit fields ----------------------------------------------------------------------------------------

    /** The fields of {@code pivot: true} on a table: one per column, named after its row path (or its label). */
    public static List<Field> fromColumns(List<Column> columns) {
        List<Field> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            var m = c.bind() == null ? null : ROW_PATH.matcher(c.bind().strip());
            String name = m != null && m.matches() ? m.group(1) : slug(c.label());
            if (name.isEmpty() || !NAME.matcher(name).matches()) {
                name = "col" + (i + 1);
            }
            String unique = name;
            for (int n = 2; !seen.add(unique); n++) {
                unique = name + "-" + n;
            }
            out.add(new Field(unique, c.bind(), c.label(), c.fmt()));
        }
        return out;
    }

    /** The fields of {@code pivot: true} on a kind: its key fields (document paths). */
    public static List<Field> fromPaths(List<String> paths) {
        List<Field> out = new ArrayList<>();
        for (String p : paths) {
            String t = p.startsWith("$.") ? p.substring(2) : p;
            if (PATH.matcher(t).matches() && out.stream().noneMatch(f -> f.name().equals(t))) {
                out.add(new Field(t, "$." + t, null, null));
            }
        }
        return out;
    }

    private static String slug(String label) {
        if (label == null) {
            return "";
        }
        String s = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return s.isEmpty() || !Character.isLetter(s.charAt(0)) ? "" : s;
    }

    // ---- writing --------------------------------------------------------------------------------------------------

    /** The spec as plain maps and lists (JSON for the console): fields, then the arrangement. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> fs = new ArrayList<>();
        for (Field f : fields) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("label", f.label());
            m.put("fmt", f.fmt());
            fs.add(m);
        }
        out.put("fields", fs);
        out.putAll(arrangementMap());
        out.put("totals", totals);
        return out;
    }

    /** The arrangement alone: what a user keeps and what a promotion writes. */
    public Map<String, Object> arrangementMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("columns", columns);
        List<Map<String, Object>> vs = new ArrayList<>();
        for (Value v : values) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", v.field());
            m.put("agg", v.agg());
            if (!"value".equals(v.show())) {
                m.put("show", v.show());
            }
            vs.add(m);
        }
        out.put("values", vs);
        List<Object> fs = new ArrayList<>();
        for (Filter f : filters) {
            if (f.values() == null && f.min() == null && f.max() == null) {
                fs.add(f.field());
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", f.field());
            if (f.values() != null) {
                m.put("values", f.values());
            }
            if (f.min() != null) {
                m.put("min", f.min());
            }
            if (f.max() != null) {
                m.put("max", f.max());
            }
            fs.add(m);
        }
        out.put("filters", fs);
        out.put("heat", heat);
        out.put("chart", chart);
        return out;
    }
}
