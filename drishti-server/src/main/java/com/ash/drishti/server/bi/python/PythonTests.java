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
package com.ash.drishti.server.bi.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code tests:} a Python item may carry (PYTHON_BI.md "Tests shipped with an item"). The server only reads and checks their shape; the console's sandbox, which has
 * Python, runs them (Check shows each as passed, failed or could-not-run; publishing through the console refuses an item with a failing one).
 *
 * <pre>
 * tests:
 *   - name: two desks                     # required, unique
 *     input: { columns: [desk, pnl1d], rows: [[A, 1.0], [A, 2.0], [B, 5.0]] }     # inline frame ...
 *     # sample: { dataset: trading-risk, fields: [trades.desk, trades.pnl1d], limit: 200 }   # ... or rows of a dataset, as the person running the test may see them
 *     params: { k: 2 }                    # over the item's defaults
 *     by: [desk]                          # a measure: the group columns (otherwise the whole input is one group)
 *     expect: { A: 6.0, B: 10.0 }         # a measure: a number, or a number per group; a visual: { kind: echarts, has: { "series.0.type": bar } }
 *     tolerance: 0.001                    # absolute; default 1e-9
 * </pre>
 *
 * A transform takes {@code input} as a map of its input datasets to frames and {@code expect: { columns: [...], rows: 3 }}.
 */
final class PythonTests {

    /** The most tests one item carries, the most inline rows of one frame and the most rows a sample asks for. */
    static final int MAX_TESTS = 50;
    static final int MAX_ROWS = 1000;
    private static final Set<String> KEYS = Set.of("name", "input", "sample", "params", "by", "expect", "tolerance");
    private static final Set<String> KINDS = Set.of("echarts", "vega-lite", "svg", "png");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private PythonTests() {}

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parse(JsonNode n, String iface, List<PythonManifest.Problem> out) {
        List<Map<String, Object>> tests = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return tests;
        }
        if (!n.isArray()) {
            out.add(new PythonManifest.Problem("error", "tests", "tests is a list of {name, input, expect}"));
            return tests;
        }
        if (n.size() > MAX_TESTS) {
            out.add(new PythonManifest.Problem("error", "tests", "an item carries at most " + MAX_TESTS + " tests"));
            return tests;
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < n.size(); i++) {
            JsonNode t = n.get(i);
            String p = "tests[" + i + "]";
            if (!t.isObject()) {
                out.add(new PythonManifest.Problem("error", p, "a test is a mapping: name, input (or sample), expect"));
                continue;
            }
            t.fieldNames().forEachRemaining(k -> {
                if (!KEYS.contains(k)) {
                    out.add(new PythonManifest.Problem("error", p + "." + k, "unknown key '" + k + "' (keys: " + String.join(", ", new java.util.TreeSet<>(KEYS)) + ")"));
                }
            });
            String name = t.path("name").asText("");
            if (name.isBlank()) {
                out.add(new PythonManifest.Problem("error", p + ".name", "a test has a name"));
            } else if (!names.add(name)) {
                out.add(new PythonManifest.Problem("error", p + ".name", "two tests are called '" + name + "'"));
            }
            boolean transform = "transform".equals(iface);
            boolean inline = t.has("input");
            boolean sample = t.has("sample");
            if (inline == sample) {
                out.add(new PythonManifest.Problem("error", p, "a test has its rows from input (a frame written in the test) or from sample (a dataset), one of the two"));
            } else if (inline) {
                if (transform) {
                    JsonNode in = t.path("input");
                    if (!in.isObject() || in.isEmpty()) {
                        out.add(new PythonManifest.Problem("error", p + ".input", "a transform test gives one frame for each input dataset: input: { <dataset>: { columns: [...], rows: [...] } }"));
                    } else {
                        in.fields().forEachRemaining(e -> frame(e.getValue(), p + ".input." + e.getKey(), out));
                    }
                } else {
                    frame(t.path("input"), p + ".input", out);
                }
            } else if (transform) {
                out.add(new PythonManifest.Problem("error", p + ".sample", "a transform test gives its frames in input; sample is for measures and visuals"));
            } else {
                JsonNode s = t.path("sample");
                if (!s.isObject() || s.path("dataset").asText("").isBlank() || !s.path("fields").isArray() || s.path("fields").isEmpty()) {
                    out.add(new PythonManifest.Problem("error", p + ".sample", "sample: { dataset: <dataset>, fields: [table.column, ...], limit: 200 }"));
                } else if (s.has("limit") && (!s.path("limit").isInt() || s.path("limit").asInt() < 1 || s.path("limit").asInt() > MAX_ROWS)) {
                    out.add(new PythonManifest.Problem("error", p + ".sample.limit", "limit is a whole number from 1 to " + MAX_ROWS));
                }
            }
            if (t.has("by") && (!"measure".equals(iface) || !t.path("by").isArray() || t.path("by").isEmpty()
                    || java.util.stream.StreamSupport.stream(t.path("by").spliterator(), false).anyMatch(x -> !IDENT.matcher(x.asText()).matches()))) {
                out.add(new PythonManifest.Problem("error", p + ".by", "by is the list of group columns of a measure"));
            }
            if (t.has("params") && !t.path("params").isObject()) {
                out.add(new PythonManifest.Problem("error", p + ".params", "params is a mapping"));
            }
            if (t.has("tolerance") && (!t.path("tolerance").isNumber() || t.path("tolerance").asDouble() < 0)) {
                out.add(new PythonManifest.Problem("error", p + ".tolerance", "tolerance is a number, 0 or more"));
            }
            expect(t, iface, p, out);
            tests.add(JSON.convertValue(t, LinkedHashMap.class));
        }
        return tests;
    }

    private static void frame(JsonNode f, String p, List<PythonManifest.Problem> out) {
        if (!f.isObject() || !f.path("columns").isArray() || f.path("columns").isEmpty() || !f.path("rows").isArray()) {
            out.add(new PythonManifest.Problem("error", p, "a frame is { columns: [name, ...], rows: [[value, ...], ...] }"));
            return;
        }
        if (f.path("rows").size() > MAX_ROWS) {
            out.add(new PythonManifest.Problem("error", p + ".rows", "an inline frame has at most " + MAX_ROWS + " rows; use sample for more"));
            return;
        }
        int width = f.path("columns").size();
        for (int r = 0; r < f.path("rows").size(); r++) {
            if (!f.path("rows").get(r).isArray() || f.path("rows").get(r).size() != width) {
                out.add(new PythonManifest.Problem("error", p + ".rows[" + r + "]", "row " + r + " does not have the " + width + " values of the columns"));
                return;
            }
        }
    }

    private static void expect(JsonNode t, String iface, String p, List<PythonManifest.Problem> out) {
        JsonNode e = t.path("expect");
        if (e.isMissingNode() || e.isNull()) {
            out.add(new PythonManifest.Problem("error", p + ".expect", "a test says what it expects"));
            return;
        }
        switch (iface) {
            case "measure" -> {
                boolean grouped = t.has("by");
                if (grouped ? !e.isObject() || e.isEmpty() || !allNumbers(e) : !e.isNumber()) {
                    out.add(new PythonManifest.Problem("error", p + ".expect", grouped ? "expect is a number for each group: { A: 6.0, B: 10.0 }" : "expect is the number the function returns for the whole input"));
                }
            }
            case "visual" -> {
                if (!e.isObject() || !KINDS.contains(e.path("kind").asText(""))) {
                    out.add(new PythonManifest.Problem("error", p + ".expect", "expect: { kind: " + String.join(" | ", KINDS) + ", has: { <dotted path>: <value> } }"));
                } else if (e.has("has") && !e.path("has").isObject()) {
                    out.add(new PythonManifest.Problem("error", p + ".expect.has", "has is a mapping of dotted paths (series.0.type) to the values the result holds"));
                }
            }
            default -> {
                if (!e.isObject() || !(e.has("columns") || e.has("rows"))) {
                    out.add(new PythonManifest.Problem("error", p + ".expect", "a transform test expects { columns: [...], rows: <count> }"));
                }
            }
        }
    }

    private static boolean allNumbers(JsonNode o) {
        for (JsonNode v : o) {
            if (!v.isNumber()) {
                return false;
            }
        }
        return true;
    }
}
