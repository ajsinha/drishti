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

import com.ash.drishti.common.Branding;
import com.ash.drishti.server.dist.XlsxWriter;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.playground.SheetStream;
import com.ash.drishti.server.playground.WorkbookStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A cube view's answer exported (MR_CUBE_LENS.md section 4.8): CSV (one row per cell and total, unformatted), Arrow (the same rows as a
 * columnar stream) and an Excel workbook. In the workbook an additive measure's totals are live {@code SUM} formulas over its cells; a
 * non-additive measure's cells and totals are the server's values (each re-aggregated from its own members' vectors or inputs), said so on
 * the sheet, with the diversification row a formula ({@code SUM(cells) − total}); a Provenance sheet lists every cell's members and scope.
 */
public final class CubeExport {

    /** The template's styles this workbook uses. */
    static final List<String> STYLES = List.of("title", "head", "text", "note", "money", "money.bold", "int", "total", "label", "wrap");

    private CubeExport() {}

    /** Every cell, row total, column total and the grand total as flat records: kind, the coordinates, members, scope, then each measure. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> records(Map<String, Object> answer) {
        List<String> dims = new ArrayList<>((List<String>) answer.get("rowDimensions"));
        dims.addAll((List<String>) answer.get("columnDimensions"));
        List<String> measures = measureIds(answer);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) answer.get("cells")) {
            out.add(record("cell", e, dims, measures));
        }
        for (Map<String, Object> e : (List<Map<String, Object>>) answer.get("rows")) {
            out.add(record("row", e, dims, measures));
        }
        for (Map<String, Object> e : (List<Map<String, Object>>) answer.get("columns")) {
            out.add(record("column", e, dims, measures));
        }
        out.add(record("total", (Map<String, Object>) answer.get("total"), dims, measures));
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<String> measureIds(Map<String, Object> answer) {
        List<String> ids = new ArrayList<>();
        for (Object m : (List<?>) answer.get("measures")) {
            ids.add(m instanceof CubeMeasure cm ? cm.id() : String.valueOf(((Map<String, Object>) m).get("id")));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> record(String kind, Map<String, Object> e, List<String> dims, List<String> measures) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("KIND", kind);
        Map<String, String> coords = e.get("coords") instanceof Map<?, ?> c ? (Map<String, String>) c : Map.of();
        for (String d : dims) {
            r.put(d, coords.getOrDefault(d, ""));
        }
        r.put("MEMBERS", ((Number) e.getOrDefault("members", 0)).longValue());
        r.put("SCOPE", e.get("scope") == null ? "" : String.valueOf(e.get("scope")));
        Map<String, Object> values = e.get("values") instanceof Map<?, ?> v ? (Map<String, Object>) v : Map.of();
        for (String m : measures) {
            Object x = values.get(m);
            r.put(m, x instanceof Number n ? n.doubleValue() : null);
        }
        return r;
    }

    /** The records as CSV (RFC 4180 quoting), the header first. */
    public static String csv(Map<String, Object> answer) {
        List<Map<String, Object>> rows = records(answer);
        StringBuilder b = new StringBuilder();
        List<String> head = new ArrayList<>(rows.get(0).keySet());
        b.append(String.join(",", head.stream().map(CubeExport::quote).toList())).append("\r\n");
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            for (String h : head) {
                Object v = r.get(h);
                cells.add(v == null ? "" : v instanceof Double d ? XlsxWriter.num(d) : quote(String.valueOf(v)));
            }
            b.append(String.join(",", cells)).append("\r\n");
        }
        return b.toString();
    }

    private static String quote(String s) {
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** The answer with its records, for the Arrow stream ({@code shape: records} at {@code records}). */
    public static Map<String, Object> forArrow(Map<String, Object> answer) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cube", answer.get("cube"));
        out.put("text", answer.get("text"));
        out.put("date", answer.get("date"));
        out.put("canonical", answer.get("canonical"));
        out.put("provenance", answer.get("provenance"));
        out.put("records", records(answer));
        return out;
    }

    /** The workbook, written to {@code out} as it is made. */
    @SuppressWarnings("unchecked")
    public static void xlsx(Map<String, Object> answer, OutputStream out, String user, String now) {
        PlaygroundTemplate t = PlaygroundTemplate.builtIn("mr-cube", STYLES);
        Map<String, String> v = new LinkedHashMap<>(t.brandingValues(Branding.current()));
        v.put("TITLE", "Cube view · " + answer.get("text"));
        v.put("SUBJECT", "the view's cells and totals, " + answer.get("date"));
        v.put("USER", user == null ? "" : user);
        v.put("NOW", now);
        List<Map<String, Object>> measures = new ArrayList<>();
        for (Object m : (List<?>) answer.get("measures")) {
            if (m instanceof CubeMeasure cm) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", cm.id());
                x.put("label", cm.label());
                x.put("kind", cm.kind().name());
                measures.add(x);
            } else {
                measures.add((Map<String, Object>) m);
            }
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) answer.get("rows");
        List<Map<String, Object>> cols = (List<Map<String, Object>>) answer.get("columns");
        Map<String, Map<String, Object>> cells = new LinkedHashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) answer.get("cells")) {
            cells.put(c.get("r") + "|" + c.get("c"), c);
        }
        Map<String, Object> total = (Map<String, Object>) answer.get("total");
        try (WorkbookStream wb = new WorkbookStream(out, t, v)) {
            SheetStream about = wb.sheet("About", "1F3A5F").width(0, 34).width(1, 110);
            int r = 1;
            about.row(r++).text(0, Branding.current().productOr("") + " · cube view", "title");
            about.row(r++).text(0, "View", "label").text(1, String.valueOf(answer.get("text")), "text");
            about.row(r++).text(0, "Business date", "label").text(1, String.valueOf(answer.get("date")), "text");
            about.row(r++).text(0, "Scope", "label").text(1, String.valueOf(answer.get("canonical")), "text");
            about.row(r++).text(0, "Provenance id", "label").text(1, String.valueOf(answer.get("provenance")), "text");
            r++;
            for (String[] e : t.explain()) {
                about.row(r++).text(0, e[0], "label").text(1, e[1], "wrap");
            }
            for (Map<String, Object> m : measures) {
                boolean additive = "ADDITIVE".equals(String.valueOf(m.get("kind")));
                String id = String.valueOf(m.get("id"));
                SheetStream s = wb.sheet(sheetName(id), additive ? "2E7D32" : "B23B3B").width(0, 30).widths(1, Math.max(2, cols.size() + 2), 16);
                int row = 1;
                s.row(row++).text(0, m.get("label") + " · " + (additive ? "additive: every total is a SUM formula of its cells"
                        : "not additive: every cell and total is the server's figure of its own members, re-aggregated from their vectors or inputs; "
                                + "the totals are values, never sums"), "title");
                s.row(row++).text(0, String.join(" › ", (List<String>) answer.get("rowDimensions")) + " \\ "
                        + String.join(" › ", (List<String>) answer.get("columnDimensions")), "head");
                for (int c = 0; c < cols.size(); c++) {
                    s.text(c + 1, label(cols.get(c)), "head");
                }
                s.text(cols.size() + 1, cols.isEmpty() ? String.valueOf(m.get("label")) : "Total", "head");
                int first = row;
                for (int i = 0; i < rows.size(); i++) {
                    s.row(row).text(0, label(rows.get(i)), "label");
                    for (int c = 0; c < cols.size(); c++) {
                        Double x = num(cells.get(i + "|" + c), id);
                        if (x != null) {
                            s.number(c + 1, x, "money");
                        }
                    }
                    Double rt = num(rows.get(i), id);
                    String rowRange = XlsxWriter.col(1) + row + ":" + XlsxWriter.col(cols.size()) + row;
                    if (additive && !cols.isEmpty()) {
                        s.formula(cols.size() + 1, "SUM(" + rowRange + ")", rt, "money.bold");
                    } else if (rt != null) {
                        s.number(cols.size() + 1, rt, "money.bold");
                    }
                    row++;
                }
                int last = row - 1;
                s.row(row).text(0, rows.isEmpty() ? "Total" : additive ? "Total (SUM)" : "Total (re-aggregated by the server)", "total");
                for (int c = 0; c <= cols.size(); c++) {
                    Double x = c < cols.size() ? num(cols.get(c), id) : num(total, id);
                    String col = XlsxWriter.col(c + 1);
                    if (additive && !rows.isEmpty()) {
                        s.formula(c + 1, "SUM(" + col + first + ":" + col + last + ")", x, "total");
                    } else if (x != null) {
                        s.number(c + 1, x, "total");
                    }
                }
                int totalRow = row++;
                if (!additive && !rows.isEmpty()) {
                    s.row(row).text(0, "Σ cells − total (diversification)", "note");
                    for (int c = 0; c <= cols.size(); c++) {
                        String col = XlsxWriter.col(c + 1);
                        Double sum = null;
                        Double tot = c < cols.size() ? num(cols.get(c), id) : num(total, id);
                        double acc = 0;
                        boolean any = false;
                        for (int i = 0; i < rows.size(); i++) {
                            Double x = c < cols.size() ? num(cells.get(i + "|" + c), id) : num(rows.get(i), id);
                            if (x != null) {
                                acc += x;
                                any = true;
                            }
                        }
                        if (any && tot != null) {
                            sum = acc - tot;
                        }
                        if (tot != null) {
                            s.formula(c + 1, "SUM(" + col + first + ":" + col + last + ")-" + col + totalRow, sum, "money");
                        }
                    }
                    row++;
                }
            }
            SheetStream p = wb.sheet("Provenance", "555555").width(0, 10).width(1, 34).width(2, 10).width(3, 90).width(4, 10);
            int pr = 1;
            p.row(pr++).text(0, "Every cell's members and scope; then what the whole view shares", "title");
            p.row(pr++).text(0, "Kind", "head").text(1, "Cell", "head").text(2, "Trades", "head").text(3, "Scope (ask /api/v1/var/scope of it)", "head")
                    .text(4, "Cached", "head");
            for (Map<String, Object> rec : records(answer)) {
                Map<String, Object> e = rec;
                List<String> coords = new ArrayList<>();
                for (Map.Entry<String, Object> x : e.entrySet()) {
                    if (!List.of("KIND", "MEMBERS", "SCOPE").contains(x.getKey()) && x.getValue() instanceof String sv && !sv.isEmpty()) {
                        coords.add(x.getKey() + "=" + sv);
                    }
                }
                p.row(pr++).text(0, String.valueOf(e.get("KIND")), "text").text(1, coords.isEmpty() ? "(the view)" : String.join(", ", coords), "text")
                        .number(2, ((Number) e.get("MEMBERS")).doubleValue(), "int").text(3, String.valueOf(e.get("SCOPE")), "text");
            }
            pr++;
            if (answer.get("provenanceShared") instanceof Map<?, ?> shared) {
                for (Map.Entry<?, ?> x : shared.entrySet()) {
                    p.row(pr++).text(0, "", "text").text(1, String.valueOf(x.getKey()), "label").text(3, String.valueOf(x.getValue()), "wrap");
                }
            }
        }
    }

    private static String sheetName(String id) {
        String s = id.replaceAll("[\\[\\]:*?/\\\\]", "_");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    @SuppressWarnings("unchecked")
    private static String label(Map<String, Object> axis) {
        Object l = axis.get("labels");
        return l instanceof List<?> ls && !ls.isEmpty() ? String.join(" › ", (List<String>) ls) : String.valueOf(axis.get("key"));
    }

    @SuppressWarnings("unchecked")
    private static Double num(Map<String, Object> e, String id) {
        if (e == null || !(e.get("values") instanceof Map<?, ?> v)) {
            return null;
        }
        Object x = ((Map<String, Object>) v).get(id);
        return x instanceof Number n ? n.doubleValue() : null;
    }
}
