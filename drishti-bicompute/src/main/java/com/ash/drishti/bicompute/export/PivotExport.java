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
package com.ash.drishti.bicompute.export;

import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pivot's answer exported (ARCHITECTURE.md section 26.11): the pivot as shown to Excel (values with formats, the outline as Excel's row
 * grouping levels with summaries below or above as on screen, a {@code SUM} formula only where a measure's total is the plain sum of its parts,
 * every other figure a value, and a Provenance sheet), CSV (one row per cell and measure, or the grid as shown), the underlying rows as CSV,
 * and the view's definition as YAML. Masks and entitlements were applied before the answer was made: a file holds what the screen showed.
 */
public final class PivotExport {

    private static final ObjectMapper JSON = new ObjectMapper();


    /** Excel's rows per worksheet. */
    public static final int EXCEL_ROWS = 1_048_576;

    private PivotExport() {}

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> a, String k) {
        Object o = a.get(k);
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> cells(Map<String, Object> a) {
        Object o = a.get("cells");
        return o instanceof Map<?, ?> m ? (Map<String, Map<String, Object>>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static Double value(Map<String, Object> cell, String label) {
        if (cell == null || !(cell.get("values") instanceof Map<?, ?> v)) {
            return null;
        }
        Object x = ((Map<String, Object>) v).get(label);
        return x instanceof Number n ? n.doubleValue() : null;
    }

    static String label(Map<String, Object> e) {
        Object l = e.get("label");
        String s = l == null ? "" : String.valueOf(l);
        return s.replace(CellKey.OTHERS, "Others");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> path(Map<String, Object> e) {
        Object p = e.get("path");
        return p instanceof List<?> l ? (List<List<String>>) l : List.of();
    }

    /** True when {@code child} is a direct child of {@code parent} on its axis (one level deeper, same path above). */
    private static boolean childOf(Map<String, Object> child, Map<String, Object> parent) {
        List<List<String>> c = path(child);
        List<List<String>> p = path(parent);
        return c.size() == p.size() + 1 && c.subList(0, p.size()).equals(p) && !"header".equals(child.get("kind"));
    }

    /** The workbook: the pivot as shown, then Provenance. Streamed into {@code out}. */
    public static void xlsx(Map<String, Object> answer, OutputStream out, String user, String now) throws IOException {
        List<Map<String, Object>> rows = list(answer, "rows");
        List<Map<String, Object>> cols = list(answer, "columns");
        List<Map<String, Object>> measures = list(answer, "measures");
        Map<String, Map<String, Object>> cells = cells(answer);
        if (rows.size() + 4 > EXCEL_ROWS) {
            throw new IllegalArgumentException("the pivot has " + rows.size() + " rows, more than a worksheet holds (" + EXCEL_ROWS
                    + "): export it as CSV or Arrow, or collapse a level");
        }
        boolean below = !"above".equals(((Map<?, ?>) answer.getOrDefault("view", Map.of())).get("subtotals"));
        int width = cols.size() * measures.size();
        double[] widths = new double[width + 1];
        widths[0] = 36;
        for (int i = 1; i <= width; i++) {
            widths[i] = 16;
        }
        try (Xlsx x = new Xlsx(out)) {
            x.sheet("Pivot", below, widths);
            x.row(0).text(0, "Pivot · " + answer.get("title") + (answer.get("date") == null ? "" : " · " + answer.get("date")), Xlsx.TITLE);
            x.row(0).text(0, String.join(" › ", (List<String>) answer.getOrDefault("rowDimensions", List.of())), Xlsx.HEAD);
            int c = 1;
            for (Map<String, Object> col : cols) {
                for (Map<String, Object> m : measures) {
                    x.text(c++, label(col), Xlsx.HEAD);
                }
            }
            x.row(0).text(0, "", Xlsx.HEAD);
            c = 1;
            for (int i = 0; i < cols.size(); i++) {
                for (Map<String, Object> m : measures) {
                    x.text(c++, String.valueOf(m.get("label")) + ("holistic".equals(m.get("kind")) ? " (each cell from its own members)" : ""), Xlsx.HEAD);
                }
            }
            Map<Integer, Integer> sheetRow = new HashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                sheetRow.put(i, x.rowNumber() + 1 + i);
            }
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> r = rows.get(i);
                String kind = String.valueOf(r.get("kind"));
                int depth = ((Number) r.getOrDefault("depth", 0)).intValue();
                int level = "grand".equals(kind) ? 0 : depth;
                boolean strong = "total".equals(kind) || "grand".equals(kind) || "header".equals(kind);
                x.row(level).text(0, "  ".repeat(Math.max(0, depth - 1)) + label(r), strong ? Xlsx.BOLD : Xlsx.PLAIN);
                if ("header".equals(kind)) {
                    continue;
                }
                List<Integer> children = new ArrayList<>();
                if ("total".equals(kind) || "grand".equals(kind)) {
                    for (int j = 0; j < rows.size(); j++) {
                        if (j != i && childOf(rows.get(j), r)) {
                            children.add(sheetRow.get(j));
                        }
                    }
                }
                c = 1;
                for (Map<String, Object> col : cols) {
                    Map<String, Object> cell = cells.get(r.get("key") + "|" + col.get("key"));
                    for (Map<String, Object> m : measures) {
                        String lab = String.valueOf(m.get("label"));
                        Double v = value(cell, lab);
                        boolean formula = Boolean.TRUE.equals(m.get("plainSum")) && m.get("showAs") == null && !children.isEmpty()
                                && children.size() < 400;
                        int style = m.get("showAs") != null && String.valueOf(m.get("showAs")).startsWith("pct") ? Xlsx.PCT
                                : strong ? Xlsx.NUM_BOLD : Xlsx.NUM;
                        if (formula && cell != null) {
                            String col0 = Xlsx.col(c);
                            List<String> refs = children.stream().map(n -> col0 + n).toList();
                            x.formula(c, "SUM(" + String.join(",", refs) + ")", v, style);
                        } else {
                            x.number(c, v, style);
                        }
                        c++;
                    }
                }
            }
            x.row(0);
            x.row(0).text(0, "SUM formulas only where a total is the plain sum of its parts (sum, count); every other figure is the engine's "
                    + "value, each total computed from its own members.", Xlsx.NOTE);
            x.sheet("Provenance", true, new double[] {28, 110});
            x.row(0).text(0, "Provenance", Xlsx.TITLE);
            x.row(0).text(0, "Exported by", Xlsx.BOLD).text(1, user == null ? "" : user, Xlsx.PLAIN);
            x.row(0).text(0, "Exported at", Xlsx.BOLD).text(1, now, Xlsx.PLAIN);
            x.row(0).text(0, "Provenance id", Xlsx.BOLD).text(1, String.valueOf(answer.get("provenance")), Xlsx.PLAIN);
            if (answer.get("provenanceShared") instanceof Map<?, ?> p) {
                for (Map.Entry<?, ?> e : p.entrySet()) {
                    if (!"functions".equals(e.getKey()) && !"statements".equals(e.getKey())) {
                        x.row(0).text(0, String.valueOf(e.getKey()), Xlsx.BOLD).text(1, String.valueOf(e.getValue()), Xlsx.PLAIN);
                    }
                }
            }
            x.row(0);
            x.row(0).text(0, "Measure", Xlsx.HEAD).text(1, "Function, kind, exactness and how its totals are made", Xlsx.HEAD);
            for (Map<String, Object> m : measures) {
                boolean plain = Boolean.TRUE.equals(m.get("plainSum"));
                x.row(0).text(0, String.valueOf(m.get("label")), Xlsx.BOLD).text(1, m.get("id") + " · " + m.get("kind") + " · "
                        + (Boolean.FALSE.equals(m.get("exact")) ? "≈ (approximate, with its error)" : "exact") + " · "
                        + (plain && m.get("showAs") == null ? "totals written as SUM formulas of their parts" : "every figure a value computed by the engine")
                        + (m.get("showAs") != null ? " · shown as " + m.get("showAs") : ""), Xlsx.PLAIN);
            }
            x.row(0);
            x.row(0).text(0, "Masks and entitlements were applied before aggregation: this file holds what the screen showed to its exporter.",
                    Xlsx.NOTE);
        }
    }

    /** CSV of the answer: {@code cells} (one row per cell and measure) or {@code grid} (the pivot as shown). */
    @SuppressWarnings("unchecked")
    public static void csv(Map<String, Object> answer, OutputStream out, String shape, char delimiter, boolean bom) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        if (bom) {
            w.write('﻿');
        }
        String d = String.valueOf(delimiter);
        List<Map<String, Object>> rows = list(answer, "rows");
        List<Map<String, Object>> cols = list(answer, "columns");
        List<Map<String, Object>> measures = list(answer, "measures");
        Map<String, Map<String, Object>> cells = cells(answer);
        if ("grid".equals(shape)) {
            List<String> head = new ArrayList<>(List.of(q(String.join(" > ", (List<String>) answer.getOrDefault("rowDimensions", List.of())), delimiter)));
            for (Map<String, Object> col : cols) {
                for (Map<String, Object> m : measures) {
                    head.add(q(label(col) + " | " + m.get("label"), delimiter));
                }
            }
            w.write(String.join(d, head) + "\r\n");
            for (Map<String, Object> r : rows) {
                if ("header".equals(r.get("kind"))) {
                    continue;
                }
                List<String> line = new ArrayList<>(List.of(q("  ".repeat(Math.max(0, ((Number) r.getOrDefault("depth", 0)).intValue() - 1)) + label(r), delimiter)));
                for (Map<String, Object> col : cols) {
                    Map<String, Object> cell = cells.get(r.get("key") + "|" + col.get("key"));
                    for (Map<String, Object> m : measures) {
                        Double v = value(cell, String.valueOf(m.get("label")));
                        line.add(v == null ? "" : Xlsx.num(v));
                    }
                }
                w.write(String.join(d, line) + "\r\n");
            }
        } else {
            List<String> dims = new ArrayList<>((List<String>) answer.getOrDefault("rowDimensions", List.of()));
            dims.addAll((List<String>) answer.getOrDefault("columnDimensions", List.of()));
            List<String> head = new ArrayList<>(List.of("ROW", "COLUMN"));
            head.addAll(dims);
            head.addAll(List.of("MEASURE", "VALUE", "EXACT", "MEMBERS", "KIND"));
            w.write(String.join(d, head.stream().map(h -> q(h, delimiter)).toList()) + "\r\n");
            Map<String, Map<String, Object>> rowsByKey = new LinkedHashMap<>();
            rows.forEach(r -> rowsByKey.putIfAbsent(String.valueOf(r.get("key")), r));
            for (Map.Entry<String, Map<String, Object>> e : cells.entrySet()) {
                String[] rc = e.getKey().split("\\|", 2);
                Map<String, Object> cell = e.getValue();
                Map<String, String> coords = cell.get("coords") instanceof Map<?, ?> cm ? (Map<String, String>) cm : Map.of();
                for (Map<String, Object> m : measures) {
                    List<String> line = new ArrayList<>();
                    line.add(q(rc[0], delimiter));
                    line.add(q(rc.length > 1 ? rc[1] : "*", delimiter));
                    for (String dim : dims) {
                        String v = coords.get(dim);
                        line.add(v == null ? "" : q(CellKey.OTHERS.equals(v) ? "Others" : v, delimiter));
                    }
                    Double v = value(cell, String.valueOf(m.get("label")));
                    line.add(q(String.valueOf(m.get("label")), delimiter));
                    line.add(v == null ? "" : Xlsx.num(v));
                    line.add(Boolean.FALSE.equals(m.get("exact")) ? "approx" : "exact");
                    line.add(String.valueOf(cell.getOrDefault("members", "")));
                    line.add(String.valueOf(m.get("kind")));
                    w.write(String.join(d, line) + "\r\n");
                }
            }
        }
        w.flush();
    }

    /** A page of member rows as CSV (drill-through export). */
    public static void rowsCsv(PivotProvider.Rows rows, OutputStream out, char delimiter, boolean bom) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        if (bom) {
            w.write('﻿');
        }
        String d = String.valueOf(delimiter);
        w.write(String.join(d, rows.columns().stream().map(c -> q(c, delimiter)).toList()) + "\r\n");
        for (List<Object> r : rows.rows()) {
            List<String> line = new ArrayList<>();
            for (Object v : r) {
                line.add(v == null ? "" : v instanceof Number n ? Xlsx.num(n.doubleValue()) : q(String.valueOf(v), delimiter));
            }
            w.write(String.join(d, line) + "\r\n");
        }
        w.flush();
    }

    /**
     * JSON Lines of the answer (the data standard, docs/guides/DATA_GUIDE.md): one object a cell and measure, its coordinates by
     * dimension, the measure, the value as a number (null when empty), exact or approx, the members and the measure's kind.
     */
    @SuppressWarnings("unchecked")
    public static void jsonl(Map<String, Object> answer, OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        List<String> dims = new ArrayList<>((List<String>) answer.getOrDefault("rowDimensions", List.of()));
        dims.addAll((List<String>) answer.getOrDefault("columnDimensions", List.of()));
        List<Map<String, Object>> measures = list(answer, "measures");
        for (Map.Entry<String, Map<String, Object>> e : cells(answer).entrySet()) {
            String[] rc = e.getKey().split("\\|", 2);
            Map<String, Object> cell = e.getValue();
            Map<String, String> coords = cell.get("coords") instanceof Map<?, ?> cm ? (Map<String, String>) cm : Map.of();
            for (Map<String, Object> m : measures) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("row", rc[0]);
                line.put("column", rc.length > 1 ? rc[1] : "*");
                Map<String, Object> at = new LinkedHashMap<>();
                for (String dim : dims) {
                    String v = coords.get(dim);
                    at.put(dim, v == null ? null : CellKey.OTHERS.equals(v) ? "Others" : v);
                }
                line.put("coords", at);
                line.put("measure", String.valueOf(m.get("label")));
                line.put("value", value(cell, String.valueOf(m.get("label"))));
                line.put("exact", !Boolean.FALSE.equals(m.get("exact")));
                line.put("members", cell.get("members"));
                line.put("kind", m.get("kind"));
                w.write(JSON.writeValueAsString(line));
                w.write('\n');
            }
        }
        w.flush();
    }

    /** A page of member rows as JSON Lines (drill-through export): one object a row, keyed by the rows' columns. */
    public static void rowsJsonl(PivotProvider.Rows rows, OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        List<String> cols = rows.columns();
        for (List<Object> r : rows.rows()) {
            Map<String, Object> line = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) {
                line.put(cols.get(i), i < r.size() ? r.get(i) : null);
            }
            w.write(JSON.writeValueAsString(line));
            w.write('\n');
        }
        w.flush();
    }

    /** The view's definition as YAML (no figures). */
    public static String definition(Map<String, Object> view) {
        try {
            return "# a pivot view (docs/guides/BI_GUIDE.md): POST it to /api/v1/pivot/query or open it with PIVOT\n"
                    + new ObjectMapper(new YAMLFactory()).writeValueAsString(view);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String q(String s, char delimiter) {
        if (s == null) {
            return "";
        }
        boolean quote = s.indexOf(delimiter) >= 0 || s.contains("\"") || s.contains("\n") || s.contains("\r");
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
