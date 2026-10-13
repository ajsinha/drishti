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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a dataset's YAML (RUPAKA.md section 6, docs/guides/DATASETS.md) into a {@link Dataset} and checks it: structure here, expressions and
 * references in {@link DatasetValidator}. Never throws for a bad document; the problems come back with their DRS code and the path of the
 * offending key, so an author sees every mistake at once. Nothing in a dataset is executed: it is parsed, validated and compiled to SQL with bound
 * values.
 */
public final class DatasetParser {

    /** One finding. {@code level} is {@code error} (the dataset cannot be used) or {@code warning}. */
    public record Problem(String level, String code, String path, String message) {
        public boolean error() {
            return "error".equals(level);
        }
    }

    /** The outcome: the dataset when the structure was readable (null otherwise), and every problem found. */
    public record Result(Dataset dataset, List<Problem> problems) {
        public boolean ok() {
            return dataset != null && problems.stream().noneMatch(Problem::error);
        }

        public String firstError() {
            return problems.stream().filter(Problem::error).findFirst().map(p -> p.code() + " " + p.path() + ": " + p.message()).orElse("");
        }
    }

    static final String INVALID = "DRS-9010";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    /** Names of tables, columns, measures' tables: plain identifiers, so they can be quoted into SQL safely and written in expressions. */
    static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    /** Measure names may hold spaces and punctuation: they are labels, never SQL. */
    static final Pattern LABEL = Pattern.compile("[^\\p{Cntrl}\\[\\]]{1,80}");
    private static final Set<String> TOP = Set.of("rachana", "expressions", "dataset", "version", "title", "description", "sample", "engine", "tables", "relationships", "measures",
            "hierarchies", "security", "rollups", "refresh", "layout", "owner", "tags", "endorsement", "endorsedBy", "audience", "from", "join", "derived", "columns", "tests");

    private DatasetParser() {}

    /** The checks of a parsed dataset's expressions, relationships, hierarchies and rollups (what the model layer runs over a flattened model). */
    public static List<Problem> check(Dataset ds) {
        return DatasetValidator.validate(ds);
    }

    /** The dataset with the history tables of its live tables added (problems to {@code out}), as a parsed dataset gets them. */
    public static Dataset withHistories(Dataset ds, List<Problem> out) {
        return Histories.expand(ds, out);
    }

    public static Result parse(String yaml) {
        List<Problem> out = new ArrayList<>();
        JsonNode root;
        try {
            root = yaml == null || yaml.isBlank() ? null : YAML.readTree(yaml);
        } catch (JsonProcessingException e) {
            out.add(error("", "the YAML is not valid: " + com.ash.drishti.server.bi.YamlErrors.describe(e)));
            return new Result(null, out);
        }
        if (root == null || !root.isObject()) {
            out.add(error("", "a dataset is a YAML mapping (key: value) with at least `dataset:` and `tables:`"));
            return new Result(null, out);
        }
        int rachana = root.path("rachana").asInt(1);
        if (rachana == com.ash.drishti.server.bi.layer.DatasetDef.RACHANA && root.has("dataset")) {
            return com.ash.drishti.server.bi.layer.Standalone.asVersion1(yaml);       // a model-layer dataset, read as the one-table dataset of its implicit model
        }
        root.fieldNames().forEachRemaining(k -> {
            if (!TOP.contains(k)) {
                out.add(new Problem("warning", INVALID, k, "unknown key '" + k + "' is ignored"));
            }
        });
        if (rachana != 1) {
            out.add(error("rachana", "this server reads Rachana version 1, not " + rachana));
        }
        String name = text(root, "dataset", "");
        if (name.isBlank()) {
            out.add(error("dataset", "`dataset:` names the model (letters, digits, hyphen, underscore)"));
        } else if (!Pattern.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", name)) {
            out.add(error("dataset", "'" + name + "' is not a usable name (letters, digits, hyphen, underscore, dot; up to 64)"));
        }
        boolean composed = root.has("from");
        boolean derived = root.has("derived");
        if (composed && derived) {
            out.add(error("derived", "a dataset is composed (`from:`) or derived (`derived:`), not both"));
        }
        if ((composed || derived) && root.has("tables")) {
            out.add(error("tables", "a composed or derived dataset has no `tables:`: its one table is built from its sources"));
        }
        Map<String, Dataset.Table> tables = tables(root.path("tables"), out);
        List<Dataset.Relationship> rels = relationships(root.path("relationships"), out);
        Map<String, Dataset.Measure> measures = measures(root.path("measures"), out);
        Map<String, List<String>> hierarchies = hierarchies(root.path("hierarchies"), out);
        Dataset.Security security = security(root.path("security"), out);
        List<Dataset.Rollup> rollups = rollups(root.path("rollups"), out);
        String engine = text(root, "engine", "duckdb");
        List<ExpectedValue.Problem> testProblems = new ArrayList<>();
        List<ExpectedValue> tests = ExpectedValue.parse(root.path("tests"), "tests", false, testProblems);
        testProblems.forEach(tp -> out.add(error(tp.path(), tp.message())));
        Dataset.Compose compose = composed && !derived ? new Dataset.Compose(inputs(root.path("from"), "from", out), joins(root.path("join"), out), List.of()) : null;
        Dataset.Derived derivedSpec = derived && !composed ? derived(root.path("derived"), out) : null;
        Dataset ds = new Dataset(name, root.path("version").asInt(1), text(root, "title", name), text(root, "description", ""), root.path("sample").asBoolean(false),
                engine, tables, rels, measures, hierarchies, security, rollups, refresh(root.path("refresh"), "refresh", out), compose, derivedSpec, tests);
        ds = Histories.expand(ds, out);
        if (!"duckdb".equals(engine)) {
            out.add(error("engine", "only the embedded engine `duckdb` is built; '" + engine + "' is planned (RUPAKA.md section 7.1)"));
        }
        if (ds.framed()) {
            if (!rollups.isEmpty() || !rels.isEmpty()) {
                out.add(error(rollups.isEmpty() ? "relationships" : "rollups", "a composed or derived dataset has one table built at query time: it takes no " + (rollups.isEmpty() ? "relationships" : "rollups")));
            }
            ds = Composition.expand(ds, declaredColumns(root.path("columns"), out), out);
        } else if (tables.isEmpty() && root.path("tables").isMissingNode()) {
            out.add(error("tables", "a dataset needs `tables:` (or `from:` to be composed of other datasets, or `derived:` to be made by a Python Transform)"));
        }
        out.addAll(DatasetValidator.validate(ds));
        return new Result(ds, out);
    }

    // ---- sections ----------------------------------------------------------------------------------------------------

    /** The dataset's {@code refresh:}: a schedule text, or a mapping with {@code schedule:}. */
    private static Dataset.Refresh refresh(JsonNode n, String path, List<Problem> out) {
        if (n.isMissingNode() || n.isNull()) {
            return Dataset.Refresh.NONE;
        }
        String text = n.isTextual() ? n.asText() : n.isObject() && n.path("schedule").isTextual() ? n.path("schedule").asText() : "";
        if (text.isBlank()) {
            out.add(error(path, "refresh is a schedule such as \"daily 06:00\", \"business-days 07:30\", \"0 */15 * * *\" or \"after-load\", or a mapping { schedule: ... }"));
            return Dataset.Refresh.NONE;
        }
        return new Dataset.Refresh(checkedSchedule(path, text, out));
    }

    private static String checkedSchedule(String path, String text, List<Problem> out) {
        try {
            RefreshSpec.parse(text);
            return RefreshSpec.normalise(text);
        } catch (IllegalArgumentException e) {
            out.add(error(path, e.getMessage()));
            return "";
        }
    }

    private static Map<String, Dataset.Table> tables(JsonNode n, List<Problem> out) {
        Map<String, Dataset.Table> tables = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return tables;
        }
        if (!n.isObject()) {
            out.add(error("tables", "tables is a mapping: tableName: { source: {connector, kind}, columns: {...} }"));
            return tables;
        }
        n.fields().forEachRemaining(e -> {
            String t = e.getKey();
            String path = "tables." + t;
            JsonNode v = e.getValue();
            if (!IDENT.matcher(t).matches()) {
                out.add(error(path, "'" + t + "' is not a table name (letters, digits, underscore; not starting with a digit)"));
                return;
            }
            if (!v.isObject()) {
                out.add(error(path, "a table is a mapping with `source:` and `columns:`"));
                return;
            }
            JsonNode src = v.path("source");
            String connector = text(src, "connector", "");
            String kind = text(src, "kind", "");
            if (kind.isBlank()) {
                out.add(error(path + ".source.kind", "a table needs source: { connector: <logical name>, kind: <pack kind> }"));
            }
            String mode = text(v, "mode", "cached");
            if (!Dataset.MODES.contains(mode)) {
                out.add(error(path + ".mode", "mode is live, cached or direct, not '" + mode + "'"));
            }
            JsonNode refresh = v.path("refresh");
            String schedule = text(refresh, "schedule", "");
            if (!schedule.isBlank()) {
                schedule = checkedSchedule(path + ".refresh.schedule", schedule, out);
            }
            Map<String, Dataset.Column> columns = new LinkedHashMap<>();
            JsonNode cols = v.path("columns");
            if (!cols.isObject()) {
                out.add(error(path + ".columns", "a table lists its columns: name: { type: string, path: $.doc.path } (an empty list is allowed: the table then has only id and businessDate)"));
            } else {
                cols.fields().forEachRemaining(c -> column(path + ".columns." + c.getKey(), c.getKey(), c.getValue(), columns, out));
            }
            String each = text(src, "each", "");
            if (!each.isBlank() && (each.length() > 200 || each.contains("'") || each.contains("\""))) {
                out.add(error(path + ".source.each", "each is the path of an array in the document, for example legs or schedule"));
                each = "";
            }
            if (!each.isBlank() && each.startsWith("$.")) {
                each = each.substring(2);
            }
            Dataset.Live live = live(path, v.path("live"), mode, each, out);
            tables.put(t, new Dataset.Table(t, connector, kind, each.isBlank() ? null : each, mode, refresh.path("on-load").asBoolean(true), schedule, text(v, "description", ""), columns, live));
        });
        return tables;
    }

    private static void column(String path, String name, JsonNode v, Map<String, Dataset.Column> columns, List<Problem> out) {
        if (!IDENT.matcher(name).matches()) {
            out.add(error(path, "'" + name + "' is not a column name (letters, digits, underscore; not starting with a digit)"));
            return;
        }
        if (Dataset.ID.equals(name) || Dataset.DATE.equals(name) || Dataset.INDEX.equals(name) || Dataset.SEQ.equals(name) || Dataset.UPDATED.equals(name)) {
            out.add(error(path, "'" + name + "' is a column every table has already; pick another name"));
            return;
        }
        String type;
        String colPath = name;
        String format = null;
        boolean key = false;
        String expr = null;
        String description = "";
        if (v.isTextual()) {
            type = v.asText();
        } else if (v.isObject()) {
            type = text(v, "type", v.has("expr") ? "decimal" : "string");
            String p = text(v, "path", "");
            colPath = p.isBlank() ? name : p.startsWith("$.") ? p.substring(2) : p.startsWith("$") ? p.substring(1) : p;
            format = v.has("format") ? v.path("format").asText() : null;
            key = v.path("key").asBoolean(false);
            expr = v.has("expr") ? v.path("expr").asText() : null;
            description = text(v, "description", "");
        } else {
            out.add(error(path, "a column is `type` or a mapping { type, path, format, key, expr }"));
            return;
        }
        if (!Dataset.TYPES.contains(type)) {
            out.add(error(path + ".type", "type is one of " + new java.util.TreeSet<>(Dataset.TYPES) + ", not '" + type + "'"));
        }
        if (colPath.isBlank() || colPath.length() > 200 || colPath.contains("'") || colPath.contains("\"") || colPath.chars().anyMatch(Character::isISOControl)) {
            out.add(error(path + ".path", "a document path such as counterparty.id or $.legs[0].rate"));
        }
        columns.put(name, new Dataset.Column(name, colPath, type, format, key, expr, description));
    }

    private static List<Dataset.Relationship> relationships(JsonNode n, List<Problem> out) {
        List<Dataset.Relationship> rels = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return rels;
        }
        if (!n.isArray()) {
            out.add(error("relationships", "relationships is a list of { from: table.column, to: table.column, cardinality }"));
            return rels;
        }
        for (int i = 0; i < n.size(); i++) {
            JsonNode r = n.get(i);
            String path = "relationships[" + i + "]";
            String from = text(r, "from", "");
            String to = text(r, "to", "");
            String[] f = from.split("\\.", -1);
            String[] t = to.split("\\.", -1);
            if (f.length != 2 || t.length != 2) {
                out.add(error(path, "from and to are table.column, for example from: trades.desk, to: desks.desk"));
                continue;
            }
            String card = text(r, "cardinality", "many-to-one");
            if (!Dataset.CARDINALITIES.contains(card)) {
                out.add(error(path + ".cardinality", "cardinality is one of " + new java.util.TreeSet<>(Dataset.CARDINALITIES)));
            }
            String dir = text(r, "direction", "single");
            if (!Set.of("single", "both").contains(dir)) {
                out.add(error(path + ".direction", "direction is single or both"));
            }
            rels.add(new Dataset.Relationship(f[0], f[1], t[0], t[1], card, dir, r.path("active").asBoolean(true)));
        }
        return rels;
    }

    private static Map<String, Dataset.Measure> measures(JsonNode n, List<Problem> out) {
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return measures;
        }
        if (!n.isObject()) {
            out.add(error("measures", "measures is a mapping: Name: { expr: \"sum(trades.notional)\", format: amount0 }"));
            return measures;
        }
        n.fields().forEachRemaining(e -> {
            String name = e.getKey();
            String path = "measures." + name;
            JsonNode v = e.getValue();
            if (!LABEL.matcher(name).matches()) {
                out.add(error(path, "a measure name is 1 to 80 characters without [ ] or control characters"));
                return;
            }
            if (v.isTextual()) {
                measures.put(name, new Dataset.Measure(name, v.asText(), null, "", null));
            } else if (v.isObject()) {
                String expr = v.has("expr") ? v.path("expr").asText() : null;
                String python = v.has("python") ? v.path("python").asText() : null;
                if (expr == null && python == null) {
                    out.add(error(path, "a measure has `expr:` (or `python:`, run by the console's Python runner: RUPAKA.md section 20.8)"));
                    return;
                }
                if (python != null && expr != null) {
                    out.add(error(path, "a measure is an `expr:` or a `python:`, not both"));
                    return;
                }
                measures.put(name, new Dataset.Measure(name, expr, v.has("format") ? v.path("format").asText() : null, text(v, "description", ""), python,
                        names(v.path("inputs")), names(v.path("by"))));
            } else {
                out.add(error(path, "a measure is an expression or { expr, format, description }"));
            }
        });
        return measures;
    }

    private static List<String> names(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        }
        return out;
    }

    private static Map<String, List<String>> hierarchies(JsonNode n, List<Problem> out) {
        Map<String, List<String>> h = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return h;
        }
        if (!n.isObject()) {
            out.add(error("hierarchies", "hierarchies is a mapping: Name: [level, level, ...]"));
            return h;
        }
        n.fields().forEachRemaining(e -> {
            if (!e.getValue().isArray() || e.getValue().isEmpty()) {
                out.add(error("hierarchies." + e.getKey(), "a hierarchy is a list of levels, coarse to fine"));
                return;
            }
            List<String> levels = new ArrayList<>();
            e.getValue().forEach(x -> levels.add(x.asText()));
            h.put(e.getKey(), levels);
        });
        return h;
    }

    private static Dataset.Security security(JsonNode n, List<Problem> out) {
        if (n.isMissingNode() || n.isNull()) {
            return Dataset.Security.NONE;
        }
        if (!n.isObject()) {
            out.add(error("security", "security has `rows:` (row rules per role) and `masked:` (columns)"));
            return Dataset.Security.NONE;
        }
        List<Dataset.RowRule> rows = new ArrayList<>();
        JsonNode r = n.path("rows");
        for (int i = 0; i < r.size(); i++) {
            String role = text(r.get(i), "role", "");
            String where = r.get(i).has("where") ? r.get(i).path("where").asText() : "";
            if (role.isBlank() || where.isBlank()) {
                out.add(error("security.rows[" + i + "]", "a row rule is { role: <role>, where: \"<condition>\" }"));
                continue;
            }
            rows.add(new Dataset.RowRule(role, where));
        }
        List<String> masked = new ArrayList<>();
        n.path("masked").forEach(m -> masked.add(m.asText()));
        return new Dataset.Security(rows, masked);
    }

    private static List<Dataset.Rollup> rollups(JsonNode n, List<Problem> out) {
        List<Dataset.Rollup> list = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return list;
        }
        if (!n.isArray()) {
            out.add(error("rollups", "rollups is a list of { name, table, by: [columns], measures: [names] }"));
            return list;
        }
        for (int i = 0; i < n.size(); i++) {
            JsonNode r = n.get(i);
            List<String> by = new ArrayList<>();
            r.path("by").forEach(x -> by.add(x.asText()));
            List<String> ms = new ArrayList<>();
            r.path("measures").forEach(x -> ms.add(x.asText()));
            String name = text(r, "name", "");
            if (!IDENT.matcher(name).matches() || text(r, "table", "").isBlank() || ms.isEmpty()) {
                out.add(error("rollups[" + i + "]", "a rollup is { name: <identifier>, table: <table>, by: [columns], measures: [measure names] }"));
                continue;
            }
            list.add(new Dataset.Rollup(name, text(r, "table", ""), by, ms));
        }
        return list;
    }

    // ---- composed and derived ----------------------------------------------------------------------------------------

    private static final Pattern AS = Pattern.compile("^(.*\\S)\\s+as\\s+(\\S.*)$", Pattern.CASE_INSENSITIVE);

    private static Dataset.Named named(String text) {
        java.util.regex.Matcher m = AS.matcher(text.trim());
        return m.matches() ? new Dataset.Named(m.group(1).trim(), m.group(2).trim()) : new Dataset.Named(text.trim(), null);
    }

    private static List<Dataset.Named> namedList(JsonNode n, String path, List<Problem> out) {
        List<Dataset.Named> list = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return list;
        }
        if (!n.isArray()) {
            out.add(error(path, "this is a list of names: [trade.desk, \"trade.cpty as counterparty\"]"));
            return list;
        }
        for (JsonNode x : n) {
            if (!x.isTextual() || x.asText().isBlank()) {
                out.add(error(path, "each entry is text: a name, or \"name as other_name\""));
                continue;
            }
            Dataset.Named nm = named(x.asText());
            if (nm.as() != null && !LABEL.matcher(nm.as()).matches()) {
                out.add(error(path, "'" + nm.as() + "' is not a usable name"));
                continue;
            }
            list.add(nm);
        }
        return list;
    }

    private static Object scalar(JsonNode x) {
        return x.isIntegralNumber() ? (Object) x.asLong() : x.isNumber() ? (Object) x.asDouble() : x.isBoolean() ? (Object) x.asBoolean() : x.asText();
    }

    /** {@code from:} of a composed dataset, or {@code derived.inputs:}: alias: { dataset, by, measures | fields, filters }. */
    private static Map<String, Dataset.Input> inputs(JsonNode n, String path, List<Problem> out) {
        Map<String, Dataset.Input> inputs = new LinkedHashMap<>();
        if (!n.isObject()) {
            out.add(new Problem("error", Composition.COMPOSE, path, "this is a mapping from an alias to a source: alias: { dataset: trades-basic, by: [...], measures: [...] }"));
            return inputs;
        }
        n.fields().forEachRemaining(e -> {
            String alias = e.getKey();
            String p = path + "." + alias;
            JsonNode v = e.getValue();
            if (!IDENT.matcher(alias).matches()) {
                out.add(new Problem("error", Composition.COMPOSE, p, "'" + alias + "' is not an alias (letters, digits, underscore; not starting with a digit)"));
                return;
            }
            if (!v.isObject() || !v.path("dataset").isTextual() || v.path("dataset").asText().isBlank()) {
                out.add(new Problem("error", Composition.COMPOSE, p, "a source is { dataset: <dataset>, by: [...], measures: [...] } (or `fields: [...]` for rows)"));
                return;
            }
            v.fieldNames().forEachRemaining(k -> {
                if (!Set.of("dataset", "by", "measures", "fields", "filters", "key").contains(k)) {
                    out.add(new Problem("warning", Composition.COMPOSE, p + "." + k, "unknown key '" + k + "' is ignored (dataset, by, measures, fields, filters, key)"));
                }
            });
            List<Dataset.InputFilter> filters = new ArrayList<>();
            JsonNode fs = v.path("filters");
            if (!fs.isMissingNode() && !fs.isNull() && !fs.isArray()) {
                out.add(new Problem("error", Composition.COMPOSE, p + ".filters", "filters is a list of { field, op, values }"));
            } else {
                for (JsonNode f : fs) {
                    String op = f.path("op").asText("in").toLowerCase(java.util.Locale.ROOT);
                    if (!f.path("field").isTextual() || !com.ash.drishti.server.bi.engine.QueryRequest.OPS.contains(op)) {
                        out.add(new Problem("error", Composition.COMPOSE, p + ".filters", "a filter is { field, op, values }; op is one of "
                                + new java.util.TreeSet<>(com.ash.drishti.server.bi.engine.QueryRequest.OPS)));
                        continue;
                    }
                    List<Object> values = new ArrayList<>();
                    JsonNode vs = f.has("values") ? f.get("values") : f.get("value");
                    if (vs != null && vs.isArray()) {
                        vs.forEach(x -> values.add(scalar(x)));
                    } else if (vs != null && !vs.isNull()) {
                        values.add(scalar(vs));
                    }
                    filters.add(new Dataset.InputFilter(f.path("field").asText(), op, values));
                }
            }
            List<String> key = new ArrayList<>();
            v.path("key").forEach(x -> key.add(x.asText()));
            inputs.put(alias, new Dataset.Input(alias, v.path("dataset").asText(), namedList(v.path("by"), p + ".by", out), namedList(v.path("measures"), p + ".measures", out),
                    namedList(v.path("fields"), p + ".fields", out), filters, key));
        });
        return inputs;
    }

    private static List<Dataset.Join> joins(JsonNode n, List<Problem> out) {
        List<Dataset.Join> list = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return list;
        }
        if (!n.isArray()) {
            out.add(new Problem("error", Composition.COMPOSE, "join", "join is a list: - { left: a, right: b, on: [field], type: inner }"));
            return list;
        }
        for (int i = 0; i < n.size(); i++) {
            JsonNode j = n.get(i);
            List<String> on = new ArrayList<>();
            if (j.path("on").isArray()) {
                j.path("on").forEach(x -> on.add(x.asText()));
            } else if (j.path("on").isTextual()) {
                on.add(j.path("on").asText());
            }
            if (!j.path("left").isTextual() || !j.path("right").isTextual()) {
                out.add(new Problem("error", Composition.COMPOSE, "join[" + i + "]", "a join is { left: <alias>, right: <alias>, on: [fields], type: inner|left|full }"));
                continue;
            }
            list.add(new Dataset.Join(j.path("left").asText(), j.path("right").asText(), on, text(j, "type", "inner").toLowerCase(java.util.Locale.ROOT), j.path("allow-repeat").asBoolean(false)));
        }
        return list;
    }

    private static Dataset.Derived derived(JsonNode n, List<Problem> out) {
        if (!n.isObject()) {
            out.add(new Problem("error", Composition.DERIVED, "derived", "derived is { transform: <python item>, inputs: { alias: { dataset, ... } }, params: {...} }"));
            return new Dataset.Derived("", new LinkedHashMap<>(), Map.of());
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!Set.of("transform", "inputs", "params").contains(k)) {
                out.add(new Problem("warning", Composition.DERIVED, "derived." + k, "unknown key '" + k + "' is ignored (transform, inputs, params)"));
            }
        });
        Map<String, Object> params = new LinkedHashMap<>();
        if (n.path("params").isObject()) {
            n.path("params").fields().forEachRemaining(e -> params.put(e.getKey(), scalar(e.getValue())));
        }
        return new Dataset.Derived(text(n, "transform", ""), inputs(n.path("inputs"), "derived.inputs", out), params);
    }

    /** The {@code columns:} of a derived dataset: name: type. */
    private static Map<String, String> declaredColumns(JsonNode n, List<Problem> out) {
        Map<String, String> cols = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return cols;
        }
        if (!n.isObject()) {
            out.add(error("columns", "columns is a mapping from a column name to its type: pnl: decimal"));
            return cols;
        }
        n.fields().forEachRemaining(e -> cols.put(e.getKey(), e.getValue().isObject() ? e.getValue().path("type").asText("string") : e.getValue().asText()));
        return cols;
    }

    // ---- live tables -------------------------------------------------------------------------------------------------

    static final String LIVE_TABLE = "DRS-9050";
    private static final Pattern SPAN = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    /** {@code live: { rows: 50000, age: 15m }} of a live table; refused on other modes and on a table over an array. */
    private static Dataset.Live live(String path, JsonNode n, String mode, String each, List<Problem> out) {
        if (n.isMissingNode() || n.isNull()) {
            if ("live".equals(mode) && !each.isBlank()) {
                out.add(new Problem("error", LIVE_TABLE, path + ".source.each", "a live table follows whole entities: it cannot be over the array '" + each + "' (use mode: cached for a table of array elements)"));
            }
            return null;
        }
        if (!"live".equals(mode)) {
            out.add(error(path + ".live", "`live:` (retention) belongs to a table with mode: live, not " + mode));
            return null;
        }
        if (!each.isBlank()) {
            out.add(new Problem("error", LIVE_TABLE, path + ".source.each", "a live table follows whole entities: it cannot be over the array '" + each + "' (use mode: cached for a table of array elements)"));
        }
        if (!n.isObject()) {
            out.add(error(path + ".live", "live is a mapping: { rows: 50000, age: 15m }"));
            return null;
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!Set.of("rows", "age", "history").contains(k)) {
                out.add(new Problem("warning", INVALID, path + ".live." + k, "unknown live key '" + k + "' is ignored (rows, age, history)"));
            }
        });
        Integer rows = null;
        if (n.has("rows")) {
            if (n.get("rows").canConvertToInt() && n.get("rows").asInt() >= 1) {
                rows = n.get("rows").asInt();
            } else {
                out.add(error(path + ".live.rows", "rows is a positive number: the most rows the table keeps"));
            }
        }
        java.time.Duration age = null;
        if (n.has("age")) {
            age = span(n.get("age").asText());
            if (age == null) {
                out.add(error(path + ".live.age", "age is a time such as 90s, 15m, 2h, 1d or PT15M: rows that have not changed for that long are dropped"));
            }
        }
        return new Dataset.Live(rows, age, history(path + ".live.history", n.path("history"), out));
    }

    /** {@code history: { table: history, every: 10s, keep: 360, by: [symbol], measures: [Volume] }} of a live table. */
    private static Dataset.History history(String path, JsonNode n, List<Problem> out) {
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isObject()) {
            out.add(new Problem("error", LIVE_TABLE, path, "history is a mapping: { every: 10s, keep: 360, by: [symbol], measures: [Volume] }"));
            return null;
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!Set.of("table", "every", "keep", "by", "measures").contains(k)) {
                out.add(new Problem("warning", INVALID, path + "." + k, "unknown history key '" + k + "' is ignored (table, every, keep, by, measures)"));
            }
        });
        String table = text(n, "table", "history");
        if (!IDENT.matcher(table).matches()) {
            out.add(new Problem("error", LIVE_TABLE, path + ".table", "'" + table + "' is not a table name (letters, digits, underscore; not starting with a digit)"));
        }
        java.time.Duration every = n.has("every") ? span(n.get("every").asText()) : java.time.Duration.ofSeconds(10);
        if (every == null || every.compareTo(Histories.MIN_EVERY) < 0) {
            out.add(new Problem("error", LIVE_TABLE, path + ".every", "every is the time between samples, 1s or more: 10s, 1m, 5m"));
            every = java.time.Duration.ofSeconds(10);
        }
        int keep = 360;
        if (n.has("keep")) {
            if (n.get("keep").canConvertToInt() && n.get("keep").asInt() >= 2 && n.get("keep").asInt() <= 100000) {
                keep = n.get("keep").asInt();
            } else {
                out.add(new Problem("error", LIVE_TABLE, path + ".keep", "keep is how many samples are kept, 2 to 100000"));
            }
        }
        return new Dataset.History(table, every, keep, strings(n.path("by")), strings(n.path("measures")));
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        } else if (n.isTextual()) {
            out.add(n.asText());
        }
        return out;
    }

    /** {@code 90s}, {@code 15m}, {@code 2h}, {@code 1d} or an ISO-8601 duration; null when it is none of those. */
    public static java.time.Duration span(String text) {
        String t = text == null ? "" : text.trim();
        try {
            if (t.regionMatches(true, 0, "PT", 0, 2) || t.regionMatches(true, 0, "P", 0, 1)) {
                java.time.Duration d = java.time.Duration.parse(t.toUpperCase(java.util.Locale.ROOT));
                return d.isZero() || d.isNegative() ? null : d;
            }
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
        java.util.regex.Matcher m = SPAN.matcher(t);
        if (!m.matches()) {
            return null;
        }
        long v = Long.parseLong(m.group(1));
        java.time.Duration d = switch (m.group(2)) {
            case "ms" -> java.time.Duration.ofMillis(v);
            case "s" -> java.time.Duration.ofSeconds(v);
            case "m" -> java.time.Duration.ofMinutes(v);
            case "h" -> java.time.Duration.ofHours(v);
            default -> java.time.Duration.ofDays(v);
        };
        return d.isZero() ? null : d;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    static Problem error(String path, String message) {
        return new Problem("error", INVALID, path, message);
    }

    private static String text(JsonNode n, String key, String dflt) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? dflt : v.asText();
    }
}
