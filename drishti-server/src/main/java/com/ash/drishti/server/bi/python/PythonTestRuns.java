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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the {@code tests:} a Python item ships with (REPORTS_GUIDE.md "Tests shipped with an item"; their shape is {@link PythonTests}'s): each hands the item
 * a frame, written in the test or rows of a dataset as the person running it may see them, runs it on the compute pool as a report would, and
 * compares the answer with what the test expects. Check shows each as passed, failed or could-not-run; publishing an item with a failing test is
 * refused (a bi-admin may force it). Nothing is kept.
 */
public final class PythonTestRuns {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PythonRuns runs;
    private final PythonFrames frames;

    public PythonTestRuns(PythonRuns runs, PythonFrames frames) {
        this.runs = runs;
        this.frames = frames;
    }

    /** Every test of {@code manifest} (as {@code PythonManifest.Manifest#toMap} has it), one after another: {@code {total, passed, failed, errors, ok, results}}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Principal who, Map<String, Object> manifest) {
        List<Map<String, Object>> results = new ArrayList<>();
        Object tests = manifest.get("tests");
        if (tests instanceof List<?> l) {
            for (Object t : l) {
                results.add(one(who, manifest, (Map<String, Object>) t));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", results.size());
        out.put("passed", count(results, "pass"));
        out.put("failed", count(results, "fail"));
        out.put("errors", count(results, "error"));
        out.put("ok", results.stream().allMatch(r -> "pass".equals(r.get("status"))));
        out.put("results", results);
        return out;
    }

    private static long count(List<Map<String, Object>> results, String status) {
        return results.stream().filter(r -> status.equals(r.get("status"))).count();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> one(Principal who, Map<String, Object> m, Map<String, Object> t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", String.valueOf(t.getOrDefault("name", "")));
        String status;
        String message;
        try {
            double tolerance = t.get("tolerance") instanceof Number n ? n.doubleValue() : 1e-9;
            Map<String, Object> params = t.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            String iface = String.valueOf(m.get("interface"));
            String subject = "test '" + out.get("name") + "' of " + m.getOrDefault("name", "");
            if ("transform".equals(iface)) {
                Map<String, Object> input = t.get("input") instanceof Map<?, ?> i ? (Map<String, Object>) i : Map.of();
                Map<String, byte[]> files = new LinkedHashMap<>();
                List<String> names = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                int n = 0;
                for (Object name : (List<Object>) m.getOrDefault("inputs", List.of())) {
                    if (!(input.get(String.valueOf(name)) instanceof Map<?, ?> f)) {
                        missing.add(String.valueOf(name));
                        continue;
                    }
                    files.put("in_" + n + ".arrow", inline((Map<String, Object>) f));
                    names.add("in_" + n++ + ".arrow");
                }
                if (!missing.isEmpty()) {
                    return result(out, "error", "the test gives no frame for the input '" + missing.get(0) + "'");
                }
                PythonRuns.Answer a = runs.run(who, iface, m, params, new PythonRuns.Inputs(files, Map.of("frames", names)), false, subject);
                Map<String, Object> want = (Map<String, Object>) t.getOrDefault("expect", Map.of());
                List<String> problems = new ArrayList<>();
                List<String> got = new ArrayList<>();
                ((List<Map<String, Object>>) a.body().getOrDefault("columns", List.of())).forEach(c -> got.add(String.valueOf(c.get("name"))));
                long rows = ((Number) a.body().getOrDefault("rows", 0)).longValue();
                if (want.containsKey("columns") && !got.equals(want.get("columns"))) {
                    problems.add("columns: expected " + want.get("columns") + ", got " + got);
                }
                if (want.get("rows") instanceof Number r && r.longValue() != rows) {
                    problems.add("rows: expected " + r + ", got " + rows);
                }
                status = problems.isEmpty() ? "pass" : "fail";
                message = problems.isEmpty() ? rows + " rows" : String.join("; ", problems);
                return result(out, status, message);
            }
            byte[] arrow;
            if (t.get("input") instanceof Map<?, ?> f) {
                arrow = inline((Map<String, Object>) f);
            } else if (t.get("sample") instanceof Map<?, ?> s) {
                int limit = s.get("limit") instanceof Number x ? x.intValue() : 200;
                arrow = frames.read(who, JSON.valueToTree(s), Math.max(1, Math.min(limit, PythonTests.MAX_ROWS))).arrow();
            } else {
                return result(out, "error", "the test has no input");
            }
            if ("measure".equals(iface)) {
                List<String> by = t.get("by") instanceof List<?> b ? b.stream().map(String::valueOf).toList() : List.of();
                Map<String, Object> how = new LinkedHashMap<>();
                how.put("frame", "frame.arrow");
                how.put("group_by", by);
                PythonRuns.Answer a = runs.run(who, iface, m, params, new PythonRuns.Inputs(Map.of("frame.arrow", arrow), how), false, subject);
                List<List<Object>> rows = (List<List<Object>>) a.body().getOrDefault("rows", List.of());
                if (by.isEmpty()) {
                    if (rows.size() != 1) {
                        return result(out, "fail", "expected one number, the item gave " + rows.size() + " groups");
                    }
                    Object v = rows.get(0).get(rows.get(0).size() - 1);
                    return v != null && same(v, t.get("expect"), tolerance) ? result(out, "pass", String.valueOf(v))
                            : result(out, "fail", "expected " + t.get("expect") + ", got " + v);
                }
                Map<String, Object> got = new LinkedHashMap<>();
                for (List<Object> r : rows) {
                    List<String> key = new ArrayList<>();
                    for (int i = 0; i < by.size(); i++) {
                        key.add(r.get(i) == null ? "" : String.valueOf(r.get(i)));
                    }
                    got.put(String.join(" / ", key), r.get(r.size() - 1));
                }
                Map<String, Object> expected = t.get("expect") instanceof Map<?, ?> e ? (Map<String, Object>) e : Map.of();
                List<String> problems = new ArrayList<>();
                expected.forEach((k, v) -> {
                    Object g = got.get(String.valueOf(k));
                    if (g == null || !same(g, v, tolerance)) {
                        problems.add(k + ": expected " + v + ", got " + (got.containsKey(String.valueOf(k)) ? g : "no group"));
                    }
                });
                got.keySet().stream().filter(k -> !expected.containsKey(k)).forEach(k -> problems.add(k + ": a group the test does not expect"));
                return problems.isEmpty() ? result(out, "pass", got.size() + " groups") : result(out, "fail", String.join("; ", problems.subList(0, Math.min(5, problems.size()))));
            }
            PythonRuns.Answer a = runs.run(who, iface, m, params, PythonRuns.Inputs.one(arrow), false, subject);
            Map<String, Object> want = t.get("expect") instanceof Map<?, ?> e ? (Map<String, Object>) e : Map.of();
            Object kind = a.body().get("kind");
            if (!String.valueOf(want.get("kind")).equals(String.valueOf(kind))) {
                return result(out, "fail", "expected " + want.get("kind") + ", the item returned " + kind);
            }
            Object res = "echarts".equals(kind) ? a.body().get("option") : a.body().get("spec");
            List<String> problems = new ArrayList<>();
            if (want.get("has") instanceof Map<?, ?> has) {
                for (Map.Entry<?, ?> e : has.entrySet()) {
                    Object got = lookup(res, String.valueOf(e.getKey()));
                    if (got == MISSING) {
                        problems.add(e.getKey() + ": not in the result");
                    } else if (!same(got, e.getValue(), tolerance)) {
                        problems.add(e.getKey() + ": expected " + e.getValue() + ", got " + got);
                    }
                }
            }
            return problems.isEmpty() ? result(out, "pass", String.valueOf(kind)) : result(out, "fail", String.join("; ", problems.subList(0, Math.min(5, problems.size()))));
        } catch (DrishtiException e) {
            return result(out, "error", e.getMessage());
        } catch (RuntimeException e) {
            return result(out, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Map<String, Object> result(Map<String, Object> out, String status, String message) {
        out.put("status", status);
        out.put("message", message);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static byte[] inline(Map<String, Object> frame) {
        List<String> cols = ((List<Object>) frame.getOrDefault("columns", List.of())).stream().map(String::valueOf).toList();
        List<List<Object>> rows = (List<List<Object>>) frame.getOrDefault("rows", List.of());
        return PythonRuns.arrow(cols, rows);
    }

    private static boolean same(Object a, Object b, double tolerance) {
        if (a instanceof Number x && b instanceof Number y && !(a instanceof Boolean) && !(b instanceof Boolean)) {
            return Math.abs(x.doubleValue() - y.doubleValue()) <= tolerance;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static final Object MISSING = new Object();

    /** {@code series.0.type} in a nested result: a key of a mapping or an index of a list. */
    private static Object lookup(Object node, String path) {
        Object at = node;
        for (String part : path.split("\\.")) {
            if (at instanceof List<?> l) {
                try {
                    int i = Integer.parseInt(part);
                    if (i < 0 || i >= l.size()) {
                        return MISSING;
                    }
                    at = l.get(i);
                } catch (NumberFormatException e) {
                    return MISSING;
                }
            } else if (at instanceof Map<?, ?> m && m.containsKey(part)) {
                at = m.get(part);
            } else {
                return MISSING;
            }
        }
        return at;
    }
}
