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
package com.ash.drishti.server.bi.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A check with an expected value (docs/guides/REPORTS.md section 14, DATASETS.md section 9): a report or dataset declares what a number should be, and Check
 * (and publishing) runs it. {@code measure} asks a measure of the dataset (grouped by {@code by}, one {@code row} picked) under {@code filters}; {@code visual}
 * (reports only) asks what a visual of the report shows. {@code expect} is a number or a range; {@code tolerance} widens it.
 *
 * <pre>
 * tests:
 *   - name: DESK-A mark to market
 *     measure: MTM
 *     by: [trade.desk]
 *     row: { trade.desk: DESK-A }
 *     filters: [{ field: trade.currency, op: eq, values: [USD] }]
 *     date: 2026-10-09
 *     expect: 12500
 *     tolerance: 0.5%        # or an amount: 0.01
 *     role: risk             # run as this role (default: you)
 *   - { name: Total trades, visual: trades-card, expect: { min: 100, max: 120 } }
 * </pre>
 *
 * @param name what the test is called (default: its target)
 * @param visual the id of a visual of the report, or null
 * @param measure a measure of the dataset, or null
 * @param by the fields to group by (a measure test), else empty
 * @param row picks one row of the answer by the values of its fields (field to value); empty: the answer must be one row
 * @param column the measure column to read, for a visual with several (default: its first measure)
 * @param filters conditions applied to the query
 * @param date the business date, or null for the latest
 * @param expect the number expected, or null when a range is
 * @param min the low end of a range, or null
 * @param max the high end of a range, or null
 * @param absolute the tolerance as an amount (0: none)
 * @param percent the tolerance as a share of the expected number, in per cent (0: none)
 * @param role the role the test runs as, or blank for the person checking
 */
public record ExpectedValue(String name, String visual, String measure, List<String> by, Map<String, Object> row, String column, List<Cond> filters, String date, Double expect,
        Double min, Double max, double absolute, double percent, String role) {

    /** A condition of the query: {@code field op values}, the form of a report filter. */
    public record Cond(String field, String op, List<Object> values) {}

    private static final Set<String> KEYS = Set.of("name", "visual", "measure", "by", "row", "column", "filters", "date", "expect", "tolerance", "role");

    /** The most tests one item may declare. */
    public static final int MAX = 100;

    /** What the target is called in a result. */
    public String label() {
        return name != null && !name.isBlank() ? name : visual != null ? visual : measure;
    }

    /** True when {@code actual} is what was expected, within the tolerance. */
    public boolean passes(double actual) {
        if (Double.isNaN(actual)) {
            return false;
        }
        if (expect != null) {
            double tol = Math.max(absolute, Math.abs(expect) * percent / 100.0);
            return Math.abs(actual - expect) <= Math.max(tol, 1e-9 * Math.max(1, Math.abs(expect)));
        }
        double slack = Math.max(absolute, Math.max(Math.abs(min == null ? 0 : min), Math.abs(max == null ? 0 : max)) * percent / 100.0);
        return (min == null || actual >= min - slack) && (max == null || actual <= max + slack);
    }

    /** The expectation as a person reads it: {@code 12,500 ± 0.5%} or {@code 100 to 120}. */
    public String expectText() {
        String tol = percent > 0 ? " ± " + trim(percent) + "%" : absolute > 0 ? " ± " + trim(absolute) : "";
        if (expect != null) {
            return trim(expect) + tol;
        }
        return (min == null ? "up to " + trim(max) : max == null ? trim(min) + " or more" : trim(min) + " to " + trim(max)) + tol;
    }

    private static String trim(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** The test as the writer stores it. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) {
            m.put("name", name);
        }
        if (visual != null) {
            m.put("visual", visual);
        }
        if (measure != null) {
            m.put("measure", measure);
        }
        if (!by.isEmpty()) {
            m.put("by", by);
        }
        if (!row.isEmpty()) {
            m.put("row", row);
        }
        if (column != null) {
            m.put("column", column);
        }
        if (!filters.isEmpty()) {
            m.put("filters", filters.stream().map(f -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("field", f.field());
                x.put("op", f.op());
                x.put("values", f.values());
                return x;
            }).toList());
        }
        if (date != null) {
            m.put("date", date);
        }
        if (expect != null) {
            m.put("expect", expect);
        } else {
            Map<String, Object> r = new LinkedHashMap<>();
            if (min != null) {
                r.put("min", min);
            }
            if (max != null) {
                r.put("max", max);
            }
            m.put("expect", r);
        }
        if (percent > 0) {
            m.put("tolerance", trim(percent) + "%");
        } else if (absolute > 0) {
            m.put("tolerance", absolute);
        }
        if (role != null && !role.isBlank()) {
            m.put("role", role);
        }
        return m;
    }

    /** A message about a test: where and what. */
    public record Problem(String path, String message) {}

    /**
     * Reads a {@code tests:} list. Problems are added to {@code out}; a test with a problem is left out. {@code visuals} says whether a test may name a visual
     * (a report) or only a measure (a dataset).
     */
    public static List<ExpectedValue> parse(JsonNode n, String path, boolean visuals, List<Problem> out) {
        List<ExpectedValue> tests = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return tests;
        }
        if (!n.isArray()) {
            out.add(new Problem(path, "tests is a list: - { name, " + (visuals ? "visual or measure" : "measure") + ", expect, tolerance }"));
            return tests;
        }
        if (n.size() > MAX) {
            out.add(new Problem(path, "at most " + MAX + " tests"));
            return tests;
        }
        int i = 0;
        for (JsonNode t : n) {
            ExpectedValue v = one(t, path + "[" + i++ + "]", visuals, out);
            if (v != null) {
                tests.add(v);
            }
        }
        return tests;
    }

    private static ExpectedValue one(JsonNode t, String p, boolean visuals, List<Problem> out) {
        if (!t.isObject()) {
            out.add(new Problem(p, "a test is { name, " + (visuals ? "visual or measure" : "measure") + ", expect, tolerance }"));
            return null;
        }
        int before = out.size();
        t.fieldNames().forEachRemaining(k -> {
            if (!KEYS.contains(k)) {
                out.add(new Problem(p + "." + k, "unknown key '" + k + "' (" + String.join(", ", new java.util.TreeSet<>(KEYS)) + ")"));
            }
        });
        String visual = text(t, "visual");
        String measure = text(t, "measure");
        if (visual != null && !visuals) {
            out.add(new Problem(p + ".visual", "a dataset's test asks a measure: use measure: <name>"));
        } else if (visual == null && measure == null) {
            out.add(new Problem(p, "a test needs " + (visuals ? "a visual: <id> or a measure: <name>" : "a measure: <name>")));
        } else if (visual != null && measure != null) {
            out.add(new Problem(p, "a test asks a visual or a measure, not both"));
        }
        List<String> by = strings(t.path("by"), p + ".by", out);
        Map<String, Object> row = new LinkedHashMap<>();
        JsonNode r = t.path("row");
        if (r.isObject()) {
            r.fields().forEachRemaining(e -> row.put(e.getKey(), e.getValue().isNumber() ? (Object) e.getValue().asDouble() : e.getValue().asText()));
        } else if (!r.isMissingNode() && !r.isNull()) {
            out.add(new Problem(p + ".row", "row picks one row of the answer: { <field>: <value> }"));
        }
        List<Cond> filters = new ArrayList<>();
        JsonNode f = t.path("filters");
        if (f.isArray()) {
            int k = 0;
            for (JsonNode x : f) {
                String fp = p + ".filters[" + k++ + "]";
                if (!x.isObject() || !x.path("field").isTextual()) {
                    out.add(new Problem(fp, "a filter is { field, op, values }"));
                    continue;
                }
                List<Object> values = new ArrayList<>();
                JsonNode vs = x.has("values") ? x.get("values") : x.get("value");
                if (vs != null && vs.isArray()) {
                    vs.forEach(v -> values.add(scalar(v)));
                } else if (vs != null && !vs.isNull()) {
                    values.add(scalar(vs));
                }
                filters.add(new Cond(x.path("field").asText(), x.path("op").asText("eq").toLowerCase(java.util.Locale.ROOT), values));
            }
        } else if (!f.isMissingNode() && !f.isNull()) {
            out.add(new Problem(p + ".filters", "filters is a list of { field, op, values }"));
        }
        String date = text(t, "date");
        if (date != null) {
            try {
                java.time.LocalDate.parse(date);
            } catch (RuntimeException e) {
                out.add(new Problem(p + ".date", "a date is yyyy-mm-dd"));
            }
        }
        Double expect = null;
        Double min = null;
        Double max = null;
        JsonNode e = t.path("expect");
        if (e.isNumber()) {
            expect = e.asDouble();
        } else if (e.isArray() && e.size() == 2 && e.get(0).isNumber() && e.get(1).isNumber()) {
            min = Math.min(e.get(0).asDouble(), e.get(1).asDouble());
            max = Math.max(e.get(0).asDouble(), e.get(1).asDouble());
        } else if (e.isObject() && (e.path("min").isNumber() || e.path("max").isNumber()) && e.size() == (e.has("min") ? 1 : 0) + (e.has("max") ? 1 : 0)) {
            min = e.path("min").isNumber() ? e.get("min").asDouble() : null;
            max = e.path("max").isNumber() ? e.get("max").asDouble() : null;
            if (min != null && max != null && min > max) {
                out.add(new Problem(p + ".expect", "min is above max"));
            }
        } else {
            out.add(new Problem(p + ".expect", "expect is a number, a range { min, max } or [low, high]"));
        }
        double abs = 0;
        double pct = 0;
        JsonNode tol = t.path("tolerance");
        if (tol.isNumber() && tol.asDouble() >= 0) {
            abs = tol.asDouble();
        } else if (tol.isTextual() && tol.asText().matches("\\s*\\d+(\\.\\d+)?\\s*%\\s*")) {
            pct = Double.parseDouble(tol.asText().replace("%", "").strip());
        } else if (!tol.isMissingNode() && !tol.isNull()) {
            out.add(new Problem(p + ".tolerance", "tolerance is an amount (0.01) or a percentage ('0.5%')"));
        }
        if (out.size() > before) {
            return null;
        }
        return new ExpectedValue(text(t, "name"), visual, measure, by, row, text(t, "column"), filters, date, expect, min, max, abs, pct, text(t, "role"));
    }

    private static String text(JsonNode t, String key) {
        JsonNode v = t.path(key);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static List<String> strings(JsonNode n, String path, List<Problem> out) {
        List<String> list = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> list.add(x.asText()));
        } else if (!n.isMissingNode() && !n.isNull()) {
            out.add(new Problem(path, "a list of field names"));
        }
        return list;
    }

    private static Object scalar(JsonNode v) {
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        return v.asText();
    }
}
