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
package com.ash.drishti.server.cube;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A cube view (MR_CUBE_LENS.md section 4.1): a scope, the dimensions on rows and columns, the measures, filters, a business date and a
 * lens. It is what a request sends, what a saved view keeps (never the numbers: a shared view opens with the reader's entitlements) and what
 * the command line writes as text:
 *
 * <pre>
 *   MCUBE BANK BY DESK,RISK_CLASS MEASURE VAR                 rows DESK, columns RISK_CLASS, VaR
 *   MCUBE DESK:DESK-RATES BY TT_BOOK>POSITION,CURRENCY MEASURE VAR,ES,PV FILTER LEGAL_ENTITY=LE-NY|LE-LDN H 10D DATE 2026-09-30
 * </pre>
 *
 * {@code BY rows,columns} (a {@code >} nests dimensions on one axis); {@code FILTER DIM=a|b} keeps the members with one of the values;
 * {@code H 10D} the horizon ({@code 10D SUM} the 10-day sum), {@code C 97.5} the confidence, {@code CCY EUR}, {@code SET STRESSED}.
 *
 * @param cube the cube ({@code mr}; {@code ccr} on the counterparty cube)
 * @param scope the scope in the engine's grammar ({@code BANK}, {@code DESK:DESK-RATES}, {@code LE:LE-NY - BOOK:X})
 * @param rows the row dimensions, outer first
 * @param columns the column dimensions (none: one column, the rows' totals)
 * @param measures the measures, the first one the sort and sparkline measure
 * @param filters dimension to the values kept
 * @param date the business date (null: the newest)
 * @param lens the source's lens words ({@code horizon}, {@code method}, {@code confidence}, {@code esConfidence}, {@code set}, {@code ccy})
 * @param sort {@code desc} or {@code asc} by the first measure's row total; {@code none} or null: the dimension's own order
 * @param history the dates of a sparkline per cell (0: none; at most 20)
 */
public record CubeView(String cube, String scope, List<String> rows, List<String> columns, List<String> measures, Map<String, List<String>> filters,
        LocalDate date, Map<String, String> lens, String sort, int history) {

    /** The command word (MR_CUBE_LENS.md section 6.4, chosen by the product owner on 2026-10-31). */
    public static final String WORD = "MCUBE";
    private static final Pattern DIM = Pattern.compile("[A-Z][A-Z0-9_]*(\\.[A-Z0-9_]+)?");
    private static final List<String> KEYWORDS = List.of("BY", "MEASURE", "MEASURES", "FILTER", "DATE", "H", "C", "CCY", "SET", "SORT", "HISTORY");

    public CubeView {
        cube = cube == null || cube.isBlank() ? "mr" : cube.strip().toLowerCase(Locale.ROOT);
        scope = scope == null || scope.isBlank() ? "BANK" : scope.strip();
        rows = upper(rows);
        columns = upper(columns);
        measures = upper(measures);
        Map<String, List<String>> f = new LinkedHashMap<>();
        if (filters != null) {
            filters.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isEmpty()) {
                    f.put(k.strip().toUpperCase(Locale.ROOT), List.copyOf(v.stream().map(x -> String.valueOf(x).strip()).toList()));
                }
            });
        }
        filters = java.util.Collections.unmodifiableMap(f);
        lens = lens == null ? Map.of() : Map.copyOf(lens);
        sort = sort == null || sort.isBlank() ? "none" : sort.strip().toLowerCase(Locale.ROOT);
        history = Math.max(0, Math.min(20, history));
        List<String> all = new ArrayList<>(rows);
        all.addAll(columns);
        for (String d : all) {
            if (!DIM.matcher(d).matches()) {
                throw bad("a dimension is a word such as DESK, RISK_CLASS or MGMT.DESK, not '" + d + "'");
            }
            if (all.indexOf(d) != all.lastIndexOf(d)) {
                throw bad(d + " is on the view twice: a dimension is on the rows or the columns, once");
            }
        }
        if (!List.of("desc", "asc", "none").contains(sort)) {
            throw bad("sort is desc, asc or none, not " + sort);
        }
    }

    static DrishtiException bad(String why) {
        return new DrishtiException(ErrorCode.CUBE_BAD_VIEW, why);
    }

    private static List<String> upper(List<String> l) {
        if (l == null) {
            return List.of();
        }
        return l.stream().filter(x -> x != null && !x.isBlank()).map(x -> x.strip().toUpperCase(Locale.ROOT)).distinct().toList();
    }

    /** The same view on another business date (a sparkline's earlier date). */
    public CubeView on(LocalDate d) {
        return new CubeView(cube, scope, rows, columns, measures, filters, d, lens, sort, history);
    }

    /** The same view with more filters (a drill keeps the cell's coordinates) and other rows and columns. */
    public CubeView drilled(Map<String, String> coords, List<String> newRows, List<String> newColumns) {
        Map<String, List<String>> f = new LinkedHashMap<>(filters);
        coords.forEach((k, v) -> f.put(k, List.of(v)));
        return new CubeView(cube, scope, newRows, newColumns, measures, f, date, lens, sort, history);
    }

    /** A view from a request's JSON body ({@code {cube, scope, rows, columns, measures, filters, date, lens, sort, history}} or {@code {text}}). */
    @SuppressWarnings("unchecked")
    public static CubeView of(Map<String, Object> m, String cube) {
        if (m == null) {
            throw bad("a view is a JSON object: {scope, rows, columns, measures, filters, date, lens}");
        }
        if (m.get("text") instanceof String t && !t.isBlank() && m.get("scope") == null && m.get("rows") == null) {
            CubeView v = parse(t, cube);
            return m.get("history") instanceof Number n && v.history() == 0
                    ? new CubeView(v.cube(), v.scope(), v.rows(), v.columns(), v.measures(), v.filters(), v.date(), v.lens(), v.sort(), n.intValue()) : v;
        }
        Map<String, List<String>> filters = new LinkedHashMap<>();
        if (m.get("filters") instanceof Map<?, ?> f) {
            f.forEach((k, v) -> filters.put(String.valueOf(k), v instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                    : List.of(String.valueOf(v).split("\\|"))));
        }
        Map<String, String> lens = new LinkedHashMap<>();
        if (m.get("lens") instanceof Map<?, ?> l) {
            l.forEach((k, v) -> {
                if (v != null && !String.valueOf(v).isBlank()) {
                    lens.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        Object h = m.get("history");
        return new CubeView(m.get("cube") == null ? cube : String.valueOf(m.get("cube")), str(m.get("scope")), list(m.get("rows")),
                list(m.get("columns")), list(m.get("measures")), filters, date(str(m.get("date"))), lens, str(m.get("sort")),
                h instanceof Number n ? n.intValue() : h == null ? 0 : Integer.parseInt(String.valueOf(h)));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> list(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        if (o instanceof String s && !s.isBlank()) {
            return List.of(s.split("\\s*[,>]\\s*"));
        }
        return List.of();
    }

    static LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.strip());
        } catch (DateTimeParseException e) {
            throw bad("date is a business date, yyyy-MM-dd (not " + s + ")");
        }
    }

    /** A view from the command line's text ({@code MCUBE BANK BY DESK,RISK_CLASS MEASURE VAR}); the word {@code MCUBE} is optional. */
    public static CubeView parse(String text, String cube) {
        List<String> words = new ArrayList<>(List.of(text.strip().replace("<GO>", "").strip().split("\\s+")));
        if (!words.isEmpty() && words.get(0).equalsIgnoreCase(WORD)) {
            words.remove(0);
        }
        StringBuilder scope = new StringBuilder();
        List<String> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        List<String> measures = new ArrayList<>();
        Map<String, List<String>> filters = new LinkedHashMap<>();
        Map<String, String> lens = new LinkedHashMap<>();
        LocalDate date = null;
        String sort = null;
        int history = 0;
        int i = 0;
        while (i < words.size() && !KEYWORDS.contains(words.get(i).toUpperCase(Locale.ROOT))) {
            scope.append(scope.isEmpty() ? "" : " ").append(words.get(i++));
        }
        while (i < words.size()) {
            String k = words.get(i++).toUpperCase(Locale.ROOT);
            String v = i < words.size() ? words.get(i++) : "";
            switch (k) {
                case "BY" -> {
                    String[] axes = v.split(",", 2);
                    rows.addAll(List.of(axes[0].split(">")));
                    if (axes.length > 1) {
                        for (String c : axes[1].split("[,>]")) {
                            columns.add(c);
                        }
                    }
                }
                case "MEASURE", "MEASURES" -> measures.addAll(List.of(v.split(",")));
                case "FILTER" -> {
                    int eq = v.indexOf('=');
                    if (eq <= 0) {
                        throw bad("FILTER is DIM=a|b, not " + v);
                    }
                    filters.put(v.substring(0, eq), List.of(v.substring(eq + 1).split("\\|")));
                }
                case "DATE" -> date = date(v);
                case "H" -> {
                    lens.put("horizon", v.toLowerCase(Locale.ROOT));
                    if (i < words.size() && words.get(i).equalsIgnoreCase("SUM")) {
                        lens.put("method", "sum");
                        i++;
                    }
                }
                case "C" -> lens.put("confidence", v);
                case "CCY" -> lens.put("ccy", v);
                case "SET" -> lens.put("set", v.toLowerCase(Locale.ROOT));
                case "SORT" -> sort = v;
                case "HISTORY" -> history = Integer.parseInt(v);
                default -> throw bad("unknown word " + k);
            }
        }
        return new CubeView(cube, scope.toString(), rows, columns, measures, filters, date, lens, sort, history);
    }

    /** The view as the command line writes it. */
    public String text() {
        StringBuilder b = new StringBuilder(WORD).append(' ').append(scope);
        if (!rows.isEmpty() || !columns.isEmpty()) {
            b.append(" BY ").append(String.join(">", rows));
            if (!columns.isEmpty()) {
                b.append(',').append(String.join(">", columns));
            }
        }
        if (!measures.isEmpty()) {
            b.append(" MEASURE ").append(String.join(",", measures));
        }
        filters.forEach((k, v) -> b.append(" FILTER ").append(k).append('=').append(String.join("|", v)));
        if (lens.get("horizon") != null) {
            b.append(" H ").append(lens.get("horizon").toUpperCase(Locale.ROOT)).append("sum".equalsIgnoreCase(lens.get("method")) ? " SUM" : "");
        }
        if (lens.get("confidence") != null) {
            b.append(" C ").append(lens.get("confidence"));
        }
        if (lens.get("ccy") != null) {
            b.append(" CCY ").append(lens.get("ccy").toUpperCase(Locale.ROOT));
        }
        if (lens.get("set") != null && !"normal".equalsIgnoreCase(lens.get("set"))) {
            b.append(" SET ").append(lens.get("set").toUpperCase(Locale.ROOT));
        }
        if (date != null) {
            b.append(" DATE ").append(date);
        }
        return b.toString();
    }

    /** The view as JSON keeps it (a saved view's body). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cube", cube);
        m.put("scope", scope);
        m.put("rows", rows);
        m.put("columns", columns);
        m.put("measures", measures);
        m.put("filters", filters);
        m.put("date", date == null ? null : date.toString());
        m.put("lens", lens);
        m.put("sort", sort);
        m.put("history", history);
        m.put("text", text());
        return m;
    }
}
