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
package com.ash.drishti.server.var;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A report definition (VAR_GUIDE.md section 18.12; REGULATORY_VAR_REPORTING.md section 8.2.6): one YAML file per report in
 * {@code drishti.regulatory.reports-dir}, read when asked (a changed file is a new revision, its digest kept with every run). Two types:
 * {@code var-table} (rows of official nodes, a computed diversification row and a total; columns of period statistics) and
 * {@code backtesting-chart} (a node's backtest over the window). A wrong field is refused naming the file and the field.
 *
 * @param id the report's id: its file name without {@code .yaml}
 * @param type {@code var-table} or {@code backtesting-chart}
 * @param measure the official record's figure read ({@code var}, {@code es}, {@code svar}, {@code ses})
 * @param units {@code units} or {@code thousands} (how the files show the figures; the run keeps them whole)
 * @param node the backtesting chart's node (a {@code var-table} names its nodes per row)
 * @param signOff the steps of the sign-off chain, in order (maker first)
 * @param stepTitles a step's title on the screens ({@code approver: CRO})
 */
record VarReportDefinition(String id, String title, String description, String type, String period, String measure, String units, String node,
        List<Row> rows, List<String> columns, List<Map<String, Object>> checks, List<String> outputs, List<String> signOff,
        Map<String, String> stepTitles, String file, String digest) {

    /** A row of a var-table: an official node, the computed diversification, or the total (a node too). */
    record Row(String label, String code, String node, String compute, boolean total) {}

    static final Set<String> TYPES = Set.of("var-table", "backtesting-chart");
    static final List<String> COLUMNS = List.of("period-end", "average", "high", "low", "prior-period-end", "movement");
    static final Set<String> CHECKS = Set.of("total-equals-official", "rows-sum-to-total", "no-missing-records", "same-total-as",
            "movement-needs-note", "backtesting-exceptions", "window-complete");
    static final Set<String> KEYS = Set.of("report", "title", "description", "type", "period", "measure", "units", "node", "rows", "columns",
            "checks", "outputs", "sign-off", "step-titles");

    /** Every definition of the folder, by id (a folder that does not exist: none). */
    static Map<String, VarReportDefinition> all(Path dir) {
        Map<String, VarReportDefinition> out = new java.util.TreeMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                VarReportDefinition d = read(f);
                out.put(d.id(), d);
            }
        } catch (IOException e) {
            throw new IllegalStateException("the report definitions of " + dir + " could not be read: " + e.getMessage(), e);
        }
        return out;
    }

    static VarReportDefinition read(Path f) {
        String text;
        try {
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(f + ": " + e.getMessage(), e);
        }
        String name = f.getFileName().toString().replaceFirst("\\.yaml$", "");
        return parse(name, text, f.toString());
    }

    @SuppressWarnings("unchecked")
    static VarReportDefinition parse(String id, String text, String file) {
        Object y = new org.yaml.snakeyaml.Yaml().load(text);
        if (!(y instanceof Map<?, ?> m)) {
            throw bad(file, "the file is not a YAML map");
        }
        for (Object k : m.keySet()) {
            if (!KEYS.contains(String.valueOf(k))) {
                throw bad(file, "unknown field '" + k + "' (the fields: " + new java.util.TreeSet<>(KEYS) + ")");
            }
        }
        String report = str(m, "report", id);
        if (!report.equals(id)) {
            throw bad(file, "report: " + report + " is not the file's name (" + id + ".yaml)");
        }
        String type = str(m, "type", "var-table");
        if (!TYPES.contains(type)) {
            throw bad(file, "type: " + type + " is not one of " + TYPES);
        }
        String measure = str(m, "measure", "var");
        if (!VarPeriodStats.MEASURES.contains(measure)) {
            throw bad(file, "measure: " + measure + " is not one of " + VarPeriodStats.MEASURES);
        }
        String units = str(m, "units", "units");
        if (!units.equals("units") && !units.equals("thousands")) {
            throw bad(file, "units: " + units + " is units or thousands");
        }
        String period = str(m, "period", "fiscal-year");
        if (!period.equals("fiscal-year") && !period.equals("fiscal-quarter")) {
            throw bad(file, "period: " + period + " is fiscal-year or fiscal-quarter");
        }
        List<Row> rows = new ArrayList<>();
        if (m.get("rows") instanceof List<?> rl) {
            for (Object o : rl) {
                if (!(o instanceof Map<?, ?> r)) {
                    throw bad(file, "rows: each row is a map (label, code, node, compute, total)");
                }
                String node = r.get("node") == null ? null : String.valueOf(r.get("node"));
                String compute = r.get("compute") == null ? null : String.valueOf(r.get("compute"));
                if ((node == null) == (compute == null)) {
                    throw bad(file, "rows: the row '" + r.get("label") + "' names a node or compute: diversification, one of them");
                }
                if (compute != null && !compute.equals("diversification")) {
                    throw bad(file, "rows: compute: " + compute + " (the one computed row is diversification)");
                }
                rows.add(new Row(String.valueOf(r.get("label")), r.get("code") == null ? null : String.valueOf(r.get("code")), node, compute,
                        Boolean.TRUE.equals(r.get("total"))));
            }
        }
        String node = m.get("node") == null ? null : String.valueOf(m.get("node"));
        if (type.equals("var-table") && rows.stream().filter(Row::total).count() != 1) {
            throw bad(file, "rows: a var-table has one row with total: true");
        }
        if (type.equals("backtesting-chart") && node == null) {
            throw bad(file, "node: the backtesting chart's node (MGMT~BANK)");
        }
        List<String> columns = list(m, "columns", COLUMNS);
        for (String c : columns) {
            if (!COLUMNS.contains(c)) {
                throw bad(file, "columns: " + c + " is not one of " + COLUMNS);
            }
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        if (m.get("checks") instanceof List<?> cl) {
            for (Object o : cl) {
                Map<String, Object> c = o instanceof Map<?, ?> cm ? new LinkedHashMap<>((Map<String, Object>) cm) : new LinkedHashMap<>(Map.of("check", String.valueOf(o)));
                if (!CHECKS.contains(String.valueOf(c.get("check")))) {
                    throw bad(file, "checks: " + c.get("check") + " is not one of " + new java.util.TreeSet<>(CHECKS));
                }
                checks.add(c);
            }
        }
        List<String> outputs = list(m, "outputs", List.of("xlsx", "csv", "pdf"));
        for (String o : outputs) {
            if (!List.of("xlsx", "csv", "pdf").contains(o)) {
                throw bad(file, "outputs: " + o + " is xlsx, csv or pdf");
            }
        }
        List<String> signOff = list(m, "sign-off", List.of("maker", "checker", "approver"));
        if (signOff.isEmpty() || !signOff.get(0).equals("maker") || new java.util.HashSet<>(signOff).size() != signOff.size()) {
            throw bad(file, "sign-off: the steps in order, maker first, each once (maker, checker, approver)");
        }
        Map<String, String> titles = new LinkedHashMap<>();
        if (m.get("step-titles") instanceof Map<?, ?> t) {
            t.forEach((k, v) -> titles.put(String.valueOf(k), String.valueOf(v)));
        }
        return new VarReportDefinition(id, str(m, "title", id), str(m, "description", ""), type, period, measure, units, node, List.copyOf(rows),
                columns, checks, outputs, signOff, titles, file, com.ash.drishti.identity.content.ContentDocs.hash(text));
    }

    private static String str(Map<?, ?> m, String k, String dflt) {
        Object v = m.get(k);
        return v == null ? dflt : String.valueOf(v).strip();
    }

    private static List<String> list(Map<?, ?> m, String k, List<String> dflt) {
        return m.get(k) instanceof List<?> l ? l.stream().map(String::valueOf).toList() : dflt;
    }

    private static IllegalArgumentException bad(String file, String what) {
        return new IllegalArgumentException(file + ": " + what);
    }

    /** The node of the total row (a var-table) or the chart's node. */
    String totalNode() {
        return type.equals("backtesting-chart") ? node : rows.stream().filter(Row::total).map(Row::node).findFirst().orElse(null);
    }

    /** What the screens call a step ({@code approver}: the definition's title for it, say CRO). */
    String stepTitle(String step) {
        return stepTitles.getOrDefault(step, Character.toUpperCase(step.charAt(0)) + step.substring(1));
    }
}
