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
package com.ash.drishti.server.bi.layer;

import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Printer;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.lang.Trees;
import com.ash.drishti.tables.yaml.TableYaml;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A virtual table of the table layer ({@code config/tables/<domain>/<name>.table.yaml}, {@code rachana: 3}; TABLES_GUIDE.md) as the
 * definition BI's compiler plans over (BI_FOUNDATION_2911E.md section 3.1 read backwards, step 5: BI reads the layer). The table is read by
 * the layer's own reader ({@link TableYaml}) and checked there; this class only says it in BI's terms: {@code from:} as BI's source (a table
 * over a table as {@code extends}), the grain's lists as {@code each}, paths relative to the grain, {@code keep: { latest }} as one row per
 * key, rules and masks as BI's security, {@code materialize: { live }} as BI's live mode. Expressions stay Rachana: a column of the source
 * named by its path ({@code $.MTM}) becomes the column that reads that path ({@code MTM}), so BI's compiler reads one name per column.
 * Pure.
 */
public final class LayerTables {

    private LayerTables() {}

    /** True when the text is a table of the layer ({@code rachana: 3} with {@code table:}). */
    public static boolean isTable(String yaml) {
        return TableYaml.isTable(yaml);
    }

    /** The table read by the layer's reader and said in BI's terms; the layer's problems, with their codes, as BI's. */
    public static DatasetDefParser.Result read(String yaml) {
        TableYaml.Result r = TableYaml.read(yaml);
        List<DatasetParser.Problem> problems = new ArrayList<>();
        r.problems().forEach(p -> problems.add(new DatasetParser.Problem(p.level(), p.code(), p.path() == null ? "" : p.path(), p.message())));
        if (r.table() == null) {
            return new DatasetDefParser.Result(null, problems);
        }
        // the layer's own checks of one table (a live table over a list, live limits, built-in names ...), as the catalogue runs them
        boolean overTable = r.table().from() != null && r.table().from().form() == TableDef.Source.Form.TABLE;      // its base's names are the catalogue's to check
        new com.ash.drishti.tables.check.TableChecks().check(r.table(), null).stream().filter(p -> !"info".equals(p.level()))
                .filter(p -> !(overTable && (com.ash.drishti.tables.TableCodes.UNKNOWN_NAME.equals(p.code()) || com.ash.drishti.tables.TableCodes.KEYS.equals(p.code()))))
                .forEach(p -> problems.add(new DatasetParser.Problem(p.level(), p.code(), p.path() == null ? "" : p.path(), p.message())));
        try {
            return new DatasetDefParser.Result(dataset(r.table(), problems, NOT_KEPT.matcher(yaml).find()), problems);
        } catch (IllegalArgumentException e) {
            problems.add(new DatasetParser.Problem("error", "DRS-9101", "", e.getMessage()));
            return new DatasetDefParser.Result(null, problems);
        }
    }

    /** The table in BI's terms. */
    /** A table that says it is not kept ({@code materialize: none}, written out): read at its source on every query. Left out, it is kept as asked. */
    private static final java.util.regex.Pattern NOT_KEPT = java.util.regex.Pattern.compile("(?m)^materialize:\\s*none\\s*(#.*)?$");

    static DatasetDef dataset(TableDef t, List<DatasetParser.Problem> problems, boolean notKept) {
        Map<String, Object> about = t.about();
        int depth = t.depth();
        Map<String, String> byPath = new LinkedHashMap<>();
        t.columns().values().forEach(c -> {
            if (c.path() != null) {
                byPath.putIfAbsent(c.path(), c.name());
            }
        });
        Map<String, DatasetDef.Col> columns = new LinkedHashMap<>();
        Map<String, DatasetDef.PyColumn> python = new LinkedHashMap<>();
        List<String> masked = new ArrayList<>();
        for (ColumnDef c : t.columns().values()) {
            if (c.python() != null) {                                         // a column a Python item computes from the row's inputs
                Object item = c.python() instanceof Map<?, ?> pm ? pm.get("item") : c.python();
                List<String> inputs = new ArrayList<>();
                if (c.python() instanceof Map<?, ?> pm && pm.get("inputs") instanceof List<?> l) {
                    l.forEach(x -> inputs.add(String.valueOf(x)));
                }
                if (item == null || String.valueOf(item).isBlank()) {
                    problems.add(new DatasetParser.Problem("error", "DRS-9101", "columns." + c.name() + ".python", "a Python column names its item: python: { item, inputs }"));
                } else {
                    python.put(c.name(), new DatasetDef.PyColumn(String.valueOf(item), inputs, c.type() == null ? "string" : c.type()));
                }
                continue;
            }
            String type = c.type() == null ? "string" : c.type();
            String role = c.role() == null ? DatasetDefParser.defaultRole(type, c.expr()) : "key".equals(c.role()) || DatasetDef.ROLES.contains(c.role()) ? c.role() : null;
            if (role == null) {
                role = c.key() ? "key" : DatasetDefParser.defaultRole(type, c.expr());
            }
            String additive = c.additive() == null ? null : Boolean.toString(c.additive());
            String path = c.path() == null ? null : path(c.path(), depth);
            if (path != null && path.equals(c.name())) {
                path = null;
            }
            String expr = c.expr() == null ? null : rowExpression(c.expr(), byPath, "columns." + c.name() + ".expr");
            Object of = c.extras().get("of");
            Object id = c.extras().get("id");
            // the layer's unit: a currency, either a column of the table (each row's own) or an ISO code; any other word is a unit of quantity
            boolean currency = c.unit() != null && (t.columns().containsKey(c.unit()) || c.unit().matches("[A-Z]{3}"));
            columns.put(c.name(), new DatasetDef.Col(c.name(), type, path, expr, role, c.meaning(), c.references(), of == null ? null : String.valueOf(of),
                    currency ? c.unit() : null, currency ? null : c.unit(), additive, c.aggregate(), c.format(), id == null ? "" : String.valueOf(id), c.description()));
            if (c.masked()) {
                masked.add(c.name());
            }
        }
        List<DatasetDef.Rule> rules = new ArrayList<>();
        for (int i = 0; i < t.rules().size(); i++) {
            TableDef.Rule r = t.rules().get(i);
            rules.add(new DatasetDef.Rule(r.roles(), rowExpression(r.where(), byPath, "rules[" + i + "].where")));
        }
        TableDef.Source from = t.from();
        String extendsRef = null;
        DatasetDef.Source source = switch (from.form()) {
            case KIND -> DatasetDef.Source.ofKind(from.pack() == null ? "" : from.pack(), from.kind(), from.connector() == null ? "" : from.connector(), each(t.grain()));
            case SQL -> new DatasetDef.Source(DatasetDef.Source.CONNECTOR, null, null, from.connector(), null, from.query(), strings(from.params()), null, null, null,
                    null, null, null);
            case PYTHON -> new DatasetDef.Source(DatasetDef.Source.PYTHON, null, null, null, null, null, null, from.python(), json(from.inputs()), "server", null,
                    null, from.params());
            case MODEL -> from.modelQuery().get("sql") != null                  // a SQL view: the statement's answer over the model
                    ? new DatasetDef.Source(DatasetDef.Source.SQL, null, null, null, null, String.valueOf(from.modelQuery().get("sql")), null, null, null, null, from.model(),
                            null, null)
                    : new DatasetDef.Source(DatasetDef.Source.MODEL, null, null, null, null, null, null, null, null, null, from.model(),
                    LayerYaml.YAML.valueToTree(from.modelQuery()), null);
            case RUN -> DatasetDef.Source.ofRun(from.run(), from.params().get("version") == null ? "latest" : String.valueOf(from.params().get("version")),
                    from.params().get("token") == null ? "" : String.valueOf(from.params().get("token")));
            case TABLE -> {
                extendsRef = from.table();
                yield null;
            }
            default -> throw new IllegalArgumentException("BI reads a table from a kind, a connector query, a Python Transform, a model, a promoted run or another table; '" + t.name()
                    + "' reads " + from.form().name().toLowerCase(java.util.Locale.ROOT));
        };
        String where = t.filter() == null ? null : rowExpression(t.filter(), byPath, "filter");
        DatasetDef.Keep keep = t.keepLatest() == null ? null : new DatasetDef.Keep(t.keys(), t.keepLatest(), true);
        DatasetDef.Grain grain = new DatasetDef.Grain(t.says() == null ? "" : t.says(), t.keys(), "businessDate");
        DatasetDef.Time time = time(t, notKept);
        Map<String, Object> rf = t.refresh();
        boolean onLoad = Boolean.TRUE.equals(rf.get("on-load")) || TableDef.Materialize.MODE_ON_LOAD.equals(t.materialize().mode());     // materialized on load: rebuilt on load
        if (rf.get("schedule") != null && !String.valueOf(rf.get("schedule")).isBlank()) {
            try {
                com.ash.drishti.server.bi.dataset.RefreshSpec.parse(String.valueOf(rf.get("schedule")));       // the subscriptions' vocabulary
            } catch (IllegalArgumentException e) {
                problems.add(new DatasetParser.Problem("error", "DRS-9101", "refresh", e.getMessage()));
            }
        }
        DatasetDef.Refresh refresh = rf.isEmpty() && !onLoad ? DatasetDef.Refresh.NONE
                : new DatasetDef.Refresh(rf.get("schedule") == null ? "" : String.valueOf(rf.get("schedule")), onLoad);
        // hidden-for: the table's roles hide it whole; a column's roles hide that column (BI says it as one set of roles over the hidden
        // columns: their roles together, so a column is never shown to a role one of them hides it from)
        List<String> hiddenColumns = new ArrayList<>();
        java.util.Set<String> hiddenRoles = new java.util.LinkedHashSet<>();
        if (t.hiddenFor().isEmpty()) {
            t.columns().values().stream().filter(c -> !c.hiddenFor().isEmpty()).forEach(c -> {
                hiddenColumns.add(c.name());
                hiddenRoles.addAll(c.hiddenFor());
            });
        } else {
            hiddenRoles.addAll(t.hiddenFor());
        }
        DatasetDef.Security security = rules.isEmpty() && masked.isEmpty() && hiddenRoles.isEmpty() ? DatasetDef.Security.NONE
                : new DatasetDef.Security(rules, masked, List.copyOf(hiddenRoles), hiddenColumns);
        List<JsonNode> tests = new ArrayList<>();
        t.tests().forEach(x -> tests.add(LayerYaml.YAML.valueToTree(x)));
        Object ai = about.get("ai");
        return new DatasetDef(t.name(), t.version(), text(about, "id", ""), text(about, "title", t.name()), text(about, "description", ""), text(about, "owner", ""),
                Boolean.TRUE.equals(about.get("sample")), text(about, "endorsement", "none"), null, source, grain, columns, time, refresh, security, extendsRef, where,
                ai == null ? null : LayerYaml.YAML.valueToTree(ai), tests, List.of(), new DatasetDef.Derivation(List.of(), keep, python, null));
    }

    /**
     * {@code materialize: { live: { rows, age } }} as BI's live mode; a table over a kind that says it is not kept ({@code materialize: none}, written out)
     * as BI's direct reads (asked at the source on every query); the rest as BI's default (a snapshot), with the table's as-of and retention.
     */
    private static DatasetDef.Time time(TableDef t, boolean notKept) {
        if (t.materialize().live()) {
            Duration age = t.materialize().age() == null ? null : DatasetParser.span(t.materialize().age());
            return new DatasetDef.Time("live", t.asOf() == null ? "business-date" : t.asOf(), t.retention(), null, t.materialize().rows(), age, null);
        }
        if (notKept && t.from().form() == TableDef.Source.Form.KIND) {
            return new DatasetDef.Time("snapshot", t.asOf() == null ? "business-date" : t.asOf(), t.retention(), "direct", null, null, null);
        }
        if (t.asOf() != null || t.retention() != null) {
            return new DatasetDef.Time("snapshot", t.asOf() == null ? "business-date" : t.asOf(), t.retention(), null, null, null, null);
        }
        return null;
    }

    /** {@code $.LEGS[*].CASH_FLOWS[*]} as BI's {@code each}: {@code LEGS[].CASH_FLOWS}; null without a grain. */
    static String each(String grain) {
        if (grain == null || grain.isBlank()) {
            return null;
        }
        String g = grain.startsWith("$.") ? grain.substring(2) : grain;
        List<String> parts = new ArrayList<>();
        for (String p : g.split("\\.")) {
            parts.add(p.endsWith("[*]") ? p.substring(0, p.length() - 3) : p);
        }
        return parts.size() == 1 ? parts.get(0) : String.join("[].", parts);
    }

    /**
     * A column's path as BI reads it: relative to the grain's element ({@code @.X} is {@code X}), the document's fields from under a grain one
     * {@code ^} per level ({@code $.X} under one list is {@code ^.X}), the parent's as written ({@code ^.LEG_ID}), indexes in brackets.
     */
    static String path(String p, int depth) {
        if (p.startsWith("@.")) {
            return p.substring(2);
        }
        if (p.startsWith("$.")) {
            return depth == 0 ? p.substring(2) : "^".repeat(depth) + "." + p.substring(2);
        }
        return p;
    }

    /** A row expression of the table with each path of the source written as the column that reads it. */
    static String rowExpression(String text, Map<String, String> byPath, String where) {
        Expr tree;
        try {
            tree = CheckedMode.parse(text).expr();
        } catch (ElException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage());
        }
        boolean[] changed = {false};
        Expr out = Trees.map(tree, e -> {
            if (!(e instanceof Expr.Field || e instanceof Expr.At) || !List.of("$", "@", "^", "^^").contains(String.valueOf(Trees.root(e)))) {
                return e;
            }
            String path = Trees.name(e);
            String name = byPath.get(path);
            if (name == null && path != null && path.matches("\\$\\.[A-Za-z_][A-Za-z0-9_]*")) {
                name = path.substring(2);                                      // a column of the base table (a table over a table reads its base's columns)
            }
            if (name == null) {
                return e;
            }
            changed[0] = true;
            return new Expr.Field(new Expr.RowOrRoot(), name, null);
        });
        return changed[0] ? Printer.print(out) : text;
    }

    private static String text(Map<String, Object> about, String key, String otherwise) {
        Object v = about.get(key);
        return v == null ? otherwise : String.valueOf(v);
    }

    private static Map<String, String> strings(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    private static Map<String, JsonNode> json(Map<String, Object> m) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, LayerYaml.YAML.valueToTree(v)));
        return out;
    }
}
