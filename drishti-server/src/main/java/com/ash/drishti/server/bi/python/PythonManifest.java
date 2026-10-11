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
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The manifest of a Python item (RUPAKA.md sections 13 and 20.8): the body of a BI item of kind {@code python}. It says what the function is
 * ({@code interface}: a measure or a visual), which columns it reads, which packages it needs, where it may run, and its limits, and carries the
 * source. Parsing never runs the code: the console's runner (which has Python) checks the syntax, and checks it again before every run.
 *
 * <pre>
 * rachana: 1
 * python: desk-pnl-volatility
 * interface: measure            # measure: compute(table, params) -> a number per group; visual: render(table, params) -> a chart
 * inputs: [pnl1d]               # the columns the function reads (a measure's group columns come from the dataset measure's `by`)
 * packages: [pandas, numpy]
 * target: server                # server, browser or both
 * timeout: 10                   # seconds
 * memory: 256                   # megabytes
 * source: |
 *   def compute(table, params):
 *       return float(table["pnl1d"].std())
 * </pre>
 */
public final class PythonManifest {

    public static final Set<String> INTERFACES = Set.of("measure", "visual");
    public static final Set<String> TARGETS = Set.of("server", "browser", "both");
    public static final Set<String> OUTPUTS = Set.of("echarts", "svg", "png");
    /** The most characters of source one item holds. */
    public static final int MAX_SOURCE = 65_536;
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Set<String> TOP = Set.of("rachana", "python", "version", "title", "description", "sample", "owner", "tags", "endorsement", "audience", "interface",
            "entry", "inputs", "packages", "target", "timeout", "memory", "output", "params", "source");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One problem: {@code error} or {@code warning}, where, and what. */
    public record Problem(String level, String path, String message) {
        public Map<String, Object> toMap() {
            return Map.of("level", level, "code", "DRS-9070", "path", path, "message", message);
        }
    }

    /** The parsed manifest. */
    public record Manifest(String name, String interfaceKind, String entry, List<String> inputs, List<String> packages, String target, int timeout, int memory, String output,
            Map<String, Object> params, String source) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("interface", interfaceKind);
            m.put("entry", entry);
            m.put("inputs", inputs);
            m.put("packages", packages);
            m.put("target", target);
            m.put("timeout", timeout);
            m.put("memory", memory);
            m.put("output", output);
            m.put("params", params);
            m.put("source", source);
            return m;
        }
    }

    /** The result: the manifest (null when it could not be read) and every problem found. */
    public record Result(Manifest manifest, List<Problem> problems) {
        public boolean ok() {
            return manifest != null && problems.stream().noneMatch(p -> "error".equals(p.level()));
        }

        public String firstError() {
            return problems.stream().filter(p -> "error".equals(p.level())).findFirst().map(p -> p.path() + ": " + p.message()).orElse("");
        }
    }

    private PythonManifest() {}

    /**
     * Reads and checks a body.
     *
     * @param allowed the packages a manifest may list
     * @param maxTimeout the largest {@code timeout} in seconds, and {@code maxMemory} the largest {@code memory} in megabytes
     */
    public static Result parse(String body, List<String> allowed, int maxTimeout, int maxMemory) {
        List<Problem> out = new ArrayList<>();
        JsonNode root;
        try {
            root = YAML.readTree(body == null ? "" : body);
        } catch (Exception e) {
            return new Result(null, List.of(new Problem("error", "", "the body is not valid YAML: " + firstLine(e.getMessage()))));
        }
        if (root == null || !root.isObject()) {
            return new Result(null, List.of(new Problem("error", "", "a Python item is a YAML mapping: python, interface, inputs, source ...")));
        }
        root.fieldNames().forEachRemaining(k -> {
            if (!TOP.contains(k)) {
                out.add(new Problem("error", k, "unknown key '" + k + "' (keys: " + String.join(", ", new java.util.TreeSet<>(TOP)) + ")"));
            }
        });
        String name = root.path("python").asText("");
        if (!NAME.matcher(name).matches()) {
            out.add(new Problem("error", "python", "python is the item's name: letters, digits, hyphen, underscore (up to 64)"));
        }
        String iface = root.path("interface").asText("").toLowerCase(Locale.ROOT);
        if (!INTERFACES.contains(iface)) {
            out.add(new Problem("error", "interface", "interface is measure (a number per group) or visual (a chart)"));
        }
        String entry = root.has("entry") ? root.path("entry").asText("") : "measure".equals(iface) ? "compute" : "render";
        if (!IDENT.matcher(entry).matches()) {
            out.add(new Problem("error", "entry", "entry is the name of the function in the source (compute for a measure, render for a visual by default)"));
        }
        List<String> inputs = strings(root.path("inputs"), "inputs", out, true);
        if ("measure".equals(iface) && inputs.isEmpty()) {
            out.add(new Problem("error", "inputs", "a measure lists the columns it reads: inputs: [pnl1d]"));
        }
        inputs.stream().filter(c -> !IDENT.matcher(c).matches()).forEach(c -> out.add(new Problem("error", "inputs", "'" + c + "' is not a column name (as the table names it, without the table)")));
        List<String> packages = strings(root.path("packages"), "packages", out, true);
        for (String p : packages) {
            if (!allowed.contains(p)) {
                out.add(new Problem("error", "packages", "'" + p + "' is not an allowed package (allowed: " + String.join(", ", allowed) + ")"));
            }
        }
        String target = root.path("target").asText("server").toLowerCase(Locale.ROOT);
        if (!TARGETS.contains(target)) {
            out.add(new Problem("error", "target", "target is server, browser or both"));
        }
        int timeout = number(root, "timeout", 10, 1, maxTimeout, out);
        int memory = number(root, "memory", 256, 32, maxMemory, out);
        String output = root.path("output").asText("visual".equals(iface) ? "echarts" : "number").toLowerCase(Locale.ROOT);
        if ("visual".equals(iface) && !OUTPUTS.contains(output)) {
            out.add(new Problem("error", "output", "a visual's output is echarts (an option), svg or png"));
        }
        if ("measure".equals(iface)) {
            output = "number";
        }
        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode pn = root.path("params");
        if (!pn.isMissingNode() && !pn.isNull()) {
            if (pn.isObject()) {
                params = JSON.convertValue(pn, LinkedHashMap.class);
            } else {
                out.add(new Problem("error", "params", "params is a mapping of defaults the function reads"));
            }
        }
        String source = root.path("source").asText("");
        if (source.isBlank()) {
            out.add(new Problem("error", "source", "source holds the Python code: a function named " + entry));
        } else if (source.length() > MAX_SOURCE) {
            out.add(new Problem("error", "source", "the source is over " + MAX_SOURCE + " characters"));
        } else if (!Pattern.compile("(?m)^(async\\s+)?def\\s+" + Pattern.quote(entry) + "\\s*\\(").matcher(source).find()) {
            out.add(new Problem("error", "source", "the source defines no function '" + entry + "' (def " + entry + "(table, params): ...)"));
        }
        if ("browser".equals(target) || "both".equals(target)) {
            packages.stream().filter(p -> !Set.of("pandas", "numpy", "scipy", "statsmodels").contains(p))
                    .forEach(p -> out.add(new Problem("error", "packages", "'" + p + "' is not in the browser's Python (Pyodide here has numpy, pandas, scipy and statsmodels): target server")));
        }
        Manifest m = new Manifest(name, iface, entry, inputs, packages, target, timeout, memory, output, params, source);
        return new Result(m, out);
    }

    private static List<String> strings(JsonNode n, String key, List<Problem> out, boolean optional) {
        List<String> list = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return list;
        }
        if (!n.isArray()) {
            out.add(new Problem("error", key, key + " is a list"));
            return list;
        }
        n.forEach(x -> list.add(x.asText()));
        return list;
    }

    private static int number(JsonNode root, String key, int dflt, int min, int max, List<Problem> out) {
        if (!root.has(key)) {
            return Math.min(dflt, max);
        }
        JsonNode v = root.path(key);
        if (!v.isInt() || v.asInt() < min || v.asInt() > max) {
            out.add(new Problem("error", key, key + " is a whole number from " + min + " to " + max));
            return dflt;
        }
        return v.asInt();
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
