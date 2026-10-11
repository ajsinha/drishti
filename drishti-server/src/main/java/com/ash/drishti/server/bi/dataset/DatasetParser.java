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
    private static final Set<String> TOP = Set.of("rachana", "dataset", "version", "title", "description", "sample", "engine", "tables", "relationships", "measures",
            "hierarchies", "security", "rollups", "owner", "tags", "endorsement", "endorsedBy", "audience");

    private DatasetParser() {}

    public static Result parse(String yaml) {
        List<Problem> out = new ArrayList<>();
        JsonNode root;
        try {
            root = yaml == null || yaml.isBlank() ? null : YAML.readTree(yaml);
        } catch (JsonProcessingException e) {
            out.add(error("", "the YAML is not valid: " + e.getOriginalMessage()));
            return new Result(null, out);
        }
        if (root == null || !root.isObject()) {
            out.add(error("", "a dataset is a YAML mapping (key: value) with at least `dataset:` and `tables:`"));
            return new Result(null, out);
        }
        root.fieldNames().forEachRemaining(k -> {
            if (!TOP.contains(k)) {
                out.add(new Problem("warning", INVALID, k, "unknown key '" + k + "' is ignored"));
            }
        });
        int rachana = root.path("rachana").asInt(1);
        if (rachana != 1) {
            out.add(error("rachana", "this server reads Rachana version 1, not " + rachana));
        }
        String name = text(root, "dataset", "");
        if (name.isBlank()) {
            out.add(error("dataset", "`dataset:` names the model (letters, digits, hyphen, underscore)"));
        } else if (!Pattern.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", name)) {
            out.add(error("dataset", "'" + name + "' is not a usable name (letters, digits, hyphen, underscore, dot; up to 64)"));
        }
        Map<String, Dataset.Table> tables = tables(root.path("tables"), out);
        List<Dataset.Relationship> rels = relationships(root.path("relationships"), out);
        Map<String, Dataset.Measure> measures = measures(root.path("measures"), out);
        Map<String, List<String>> hierarchies = hierarchies(root.path("hierarchies"), out);
        Dataset.Security security = security(root.path("security"), out);
        List<Dataset.Rollup> rollups = rollups(root.path("rollups"), out);
        String engine = text(root, "engine", "duckdb");
        Dataset ds = new Dataset(name, root.path("version").asInt(1), text(root, "title", name), text(root, "description", ""), root.path("sample").asBoolean(false),
                engine, tables, rels, measures, hierarchies, security, rollups);
        if (!"duckdb".equals(engine)) {
            out.add(error("engine", "only the embedded engine `duckdb` is built; '" + engine + "' is planned (RUPAKA.md section 7.1)"));
        }
        if (tables.isEmpty() && root.path("tables").isMissingNode()) {
            out.add(error("tables", "a dataset needs `tables:`"));
        }
        out.addAll(DatasetValidator.validate(ds));
        return new Result(ds, out);
    }

    // ---- sections ----------------------------------------------------------------------------------------------------

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
            if (!schedule.isBlank() && schedule.trim().split("\\s+").length != 5) {
                out.add(error(path + ".refresh.schedule", "a schedule is a cron expression with five fields, for example \"0 */15 * * *\""));
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
            tables.put(t, new Dataset.Table(t, connector, kind, each.isBlank() ? null : each, mode, refresh.path("on-load").asBoolean(true), schedule, text(v, "description", ""), columns));
        });
        return tables;
    }

    private static void column(String path, String name, JsonNode v, Map<String, Dataset.Column> columns, List<Problem> out) {
        if (!IDENT.matcher(name).matches()) {
            out.add(error(path, "'" + name + "' is not a column name (letters, digits, underscore; not starting with a digit)"));
            return;
        }
        if (Dataset.ID.equals(name) || Dataset.DATE.equals(name) || Dataset.INDEX.equals(name)) {
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
                    out.add(error(path, "a measure has `expr:` (or `python:`, stored and not run yet)"));
                    return;
                }
                measures.put(name, new Dataset.Measure(name, expr, v.has("format") ? v.path("format").asText() : null, text(v, "description", ""), python));
            } else {
                out.add(error(path, "a measure is an expression or { expr, format, description }"));
            }
        });
        return measures;
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

    // ---- helpers -----------------------------------------------------------------------------------------------------

    static Problem error(String path, String message) {
        return new Problem("error", INVALID, path, message);
    }

    private static String text(JsonNode n, String key, String dflt) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? dflt : v.asText();
    }
}
