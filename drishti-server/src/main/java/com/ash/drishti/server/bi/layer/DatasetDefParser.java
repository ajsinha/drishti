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

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.ExprParser;
import com.ash.drishti.server.bi.dataset.Node;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and checks a {@code rachana: 2} dataset (docs/architecture/BI_MODEL_SPEC.md section 1) with the problem codes of section 1.11: {@code DRS-9100}
 * structure, {@code 9101} columns, {@code 9102} a source the item itself shows is unusable, {@code 9103} a time mode the source cannot give, {@code 9104} the
 * grain, {@code 9105} {@code extends}, {@code 9106} security, {@code 9108} an input query that is not a model query. What needs the server (does the pack, the
 * kind, the connector exist; is the base of {@code extends} there; {@code 9107} an edit of a generated dataset) is checked by {@link ModelCatalog}. Keys that a
 * later wave gives behaviour to ({@code ai}, {@code hidden-for}, the {@code references-exist} test) are read and kept.
 */
public final class DatasetDefParser {

    /** The outcome: the dataset (null when it could not be read at all) and every problem found. */
    public record Result(DatasetDef dataset, List<DatasetParser.Problem> problems) {

        public boolean ok() {
            return dataset != null && problems.stream().noneMatch(DatasetParser.Problem::error);
        }

        public String firstError() {
            return problems.stream().filter(DatasetParser.Problem::error).findFirst().map(p -> p.code() + " " + p.path() + ": " + p.message()).orElse("");
        }
    }

    static final Set<String> TOP = Set.of("rachana", "dataset", "version", "id", "source", "grain", "columns", "time", "refresh", "security", "ai", "tests", "extends",
            "where", "rollups", "layout", "title", "description", "owner", "tags", "endorsement", "endorsedBy", "audience", "sample", "generated");
    private static final Set<String> COLUMN_KEYS = Set.of("type", "path", "expr", "role", "meaning", "references", "of", "unit", "additive", "aggregate", "format", "id",
            "description", "title", "term", "means", "sign");

    private DatasetDefParser() {}

    public static Result parse(String yaml) {
        LayerYaml y = new LayerYaml();
        JsonNode root = y.read(yaml, "DRS-9100", "dataset");
        if (root == null) {
            return new Result(null, y.problems());
        }
        return new Result(read(root, y), y.problems());
    }

    /** True when the text is a model-layer dataset ({@code rachana: 2} with {@code dataset:}), not a version-1 one. */
    public static boolean isVersion2(String yaml) {
        try {
            JsonNode root = yaml == null || yaml.isBlank() ? null : LayerYaml.YAML.readTree(yaml);
            return root != null && root.isObject() && root.path("rachana").asInt(1) == DatasetDef.RACHANA && root.has("dataset");
        } catch (Exception e) {
            return false;
        }
    }

    static DatasetDef read(JsonNode root, LayerYaml y) {
        y.unknownKeys(root, TOP, "DRS-9100", "");
        if (root.path("rachana").asInt(0) != DatasetDef.RACHANA) {
            y.error("DRS-9100", "rachana", "a dataset of the model layer says `rachana: 2`");
        }
        String name = LayerYaml.text(root, "dataset", "");
        boolean generated = root.has("generated");
        if (name.isBlank()) {
            y.error("DRS-9100", "dataset", "`dataset:` names the table (letters, digits, `.`, `_`, `-`, up to 64)");
        } else if (!LayerYaml.NAME.matcher(name).matches()) {
            y.error("DRS-9100", "dataset", "'" + name + "' is not a usable name (letters, digits, `.`, `_`, `-`, up to 64)");
        } else if (name.startsWith(DatasetDef.PACK_PREFIX) && !generated) {
            y.error("DRS-9100", "dataset", "a name starting `pack.` belongs to a pack-generated dataset: choose another name, or `extends: " + name + "` to narrow it");
        }
        String version = LayerYaml.text(root, "version", "");
        if (Semver.parse(version) == null) {
            y.error("DRS-9100", "version", "`version:` is the semver of the dataset's contract, such as 1.0.0, not '" + version + "'");
        }
        String extendsRef = root.has("extends") ? LayerYaml.text(root, "extends", "") : null;
        if (extendsRef != null && Semver.Ref.parse(extendsRef) == null) {
            y.error("DRS-9105", "extends", "'" + extendsRef + "' is not a dataset reference (name, or name@version such as pack.trading.trade@1)");
        }
        DatasetDef.Source source = source(root.path("source"), extendsRef != null, y);
        Map<String, DatasetDef.Col> columns = columns(root.path("columns"), y);
        if (columns.isEmpty() && source != null && !DatasetDef.Source.PACK.equals(source.type()) && extendsRef == null) {
            y.error("DRS-9101", "columns", "a " + source.type() + " dataset declares its columns (its contract)");
        }
        checkColumns(columns, y);
        DatasetDef.Grain grain = grain(root.path("grain"), columns, source, extendsRef != null, y);
        DatasetDef.Time time = time(root.path("time"), source, y);
        DatasetDef.Refresh refresh = refresh(root.path("refresh"), y);
        DatasetDef.Security security = security(root.path("security"), name, columns, extendsRef != null, y);
        String where = root.has("where") ? LayerYaml.text(root, "where", "") : null;
        if (where != null) {
            if (extendsRef == null) {
                y.error("DRS-9105", "where", "`where:` narrows the dataset this one `extends`; a dataset with its own source filters in its source");
            } else {
                expression(where, "where", "DRS-9105", y);
            }
        }
        List<JsonNode> tests = new ArrayList<>();
        root.path("tests").forEach(tests::add);
        tests(tests, columns, y);
        List<JsonNode> rollups = new ArrayList<>();
        root.path("rollups").forEach(rollups::add);
        DatasetDef.Generated gen = generated ? new DatasetDef.Generated(LayerYaml.text(root.path("generated"), "by", "pack"),
                LayerYaml.text(root.path("generated"), "pack", ""), LayerYaml.text(root.path("generated"), "packVersion", "")) : null;
        return new DatasetDef(name, version, LayerYaml.text(root, "id", ""), LayerYaml.text(root, "title", name), LayerYaml.text(root, "description", ""),
                LayerYaml.text(root, "owner", ""), root.path("sample").asBoolean(false), LayerYaml.text(root, "endorsement", "none"), gen, source, grain, columns, time,
                refresh, security, extendsRef, where, root.has("ai") ? root.get("ai") : null, tests, rollups);
    }

    // ---- source ------------------------------------------------------------------------------------------------------

    private static DatasetDef.Source source(JsonNode s, boolean extending, LayerYaml y) {
        if (s.isMissingNode() || s.isNull()) {
            if (!extending) {
                y.error("DRS-9100", "source", "a dataset needs `source:`: a pack kind, a connector query, a Python Transform or a model's answer (or `extends:` another dataset)");
            }
            return null;
        }
        if (extending) {
            y.error("DRS-9105", "source", "a dataset that `extends` another reads the base's source: remove `source:`");
            return null;
        }
        if (!s.isObject()) {
            y.error("DRS-9100", "source", "`source:` is a mapping");
            return null;
        }
        boolean model = s.has("model");
        boolean query = s.has("query") && !model;                       // a model source's `query:` is its model query, not a connector's SQL
        boolean python = s.has("python");
        boolean kind = s.has("kind");
        int n = (query ? 1 : 0) + (python ? 1 : 0) + (model ? 1 : 0) + (kind ? 1 : 0);
        if (n != 1) {
            y.error("DRS-9100", "source", n == 0 ? "`source:` names a `kind` (with an optional pack and connector), a connector `query`, a `python` Transform or a `model`"
                    : "a dataset has exactly one source; this one names " + n + " (kind, query, python, model)");
            return null;
        }
        if (kind) {
            y.unknownKeys(s, Set.of("pack", "kind", "connector", "each"), "DRS-9100", "source");
            return DatasetDef.Source.ofKind(LayerYaml.text(s, "pack", ""), LayerYaml.text(s, "kind", ""), LayerYaml.text(s, "connector", ""),
                    s.has("each") ? LayerYaml.text(s, "each", "") : null);
        }
        if (query) {
            y.unknownKeys(s, Set.of("connector", "query", "params"), "DRS-9100", "source");
            String connector = LayerYaml.text(s, "connector", "");
            if (connector.isBlank()) {
                y.error("DRS-9102", "source.connector", "a connector query names the SQL-capable connector it runs in");
            }
            Map<String, String> params = new LinkedHashMap<>();
            s.path("params").fields().forEachRemaining(e -> {
                if (!DatasetDef.PARAMS.contains(e.getValue().asText())) {
                    y.error("DRS-9102", "source.params." + e.getKey(), "a query parameter binds one of " + DatasetDef.PARAMS + ", never text from a user; not '" + e.getValue().asText() + "'");
                }
                params.put(e.getKey(), e.getValue().asText());
            });
            String sql = LayerYaml.text(s, "query", "");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)").matcher(sql);
            while (m.find()) {
                if (!params.containsKey(m.group(1))) {
                    y.error("DRS-9102", "source.query", "the query uses :" + m.group(1) + ", which `params:` does not bind");
                }
            }
            return new DatasetDef.Source(DatasetDef.Source.CONNECTOR, null, null, connector, null, sql, params, null, null, null, null, null, null);
        }
        if (python) {
            y.unknownKeys(s, Set.of("python", "inputs", "params", "where"), "DRS-9100", "source");
            Map<String, JsonNode> inputs = new LinkedHashMap<>();
            s.path("inputs").fields().forEachRemaining(e -> {
                inputs.put(e.getKey(), e.getValue());
                innerQuery(e.getValue(), "source.inputs." + e.getKey(), y);
            });
            String where = LayerYaml.text(s, "where", "server");
            if (!Set.of("server", "browser", "auto").contains(where)) {
                y.error("DRS-9100", "source.where", "a Python dataset runs on the server, in the browser or auto, not '" + where + "'");
            }
            Map<String, Object> params = new LinkedHashMap<>();
            s.path("params").fields().forEachRemaining(e -> params.put(e.getKey(), LayerYaml.YAML.convertValue(e.getValue(), Object.class)));
            return new DatasetDef.Source(DatasetDef.Source.PYTHON, null, null, null, null, null, null, LayerYaml.text(s, "python", ""), inputs, where, null, null, params);
        }
        y.unknownKeys(s, Set.of("model", "query"), "DRS-9100", "source");
        JsonNode q = s.path("query");
        innerQuery(withModel(q, LayerYaml.text(s, "model", "")), "source.query", y);
        return new DatasetDef.Source(DatasetDef.Source.MODEL, null, null, null, null, null, null, null, null, null, LayerYaml.text(s, "model", ""), q, null);
    }

    private static JsonNode withModel(JsonNode q, String model) {
        com.fasterxml.jackson.databind.node.ObjectNode o = q.isObject() ? ((com.fasterxml.jackson.databind.node.ObjectNode) q).deepCopy() : LayerYaml.YAML.createObjectNode();
        if (!o.has("model")) {
            o.put("model", model);
        }
        return o;
    }

    /** An input of a Python or model source is a model query: its shape is checked here (DRS-9108 with the query's own code); its names when it runs. */
    private static void innerQuery(JsonNode q, String path, LayerYaml y) {
        com.fasterxml.jackson.databind.node.ObjectNode o = q.isObject() ? ((com.fasterxml.jackson.databind.node.ObjectNode) q).deepCopy() : null;
        if (o == null) {
            y.error("DRS-9108", path, "an input is a model query: { model, select: { fields, measures }, where, ... }");
            return;
        }
        if (!o.has("mq")) {
            o.put("mq", 1);
        }
        try {
            com.ash.drishti.server.bi.mq.MqParser.parse(o);
        } catch (com.ash.drishti.server.bi.mq.MqException e) {
            e.problems().forEach(p -> y.error("DRS-9108", path + (p.path().isEmpty() ? "" : "." + p.path()), p.code() + " " + p.detail()));
        }
    }

    // ---- columns -----------------------------------------------------------------------------------------------------

    private static Map<String, DatasetDef.Col> columns(JsonNode n, LayerYaml y) {
        Map<String, DatasetDef.Col> out = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return out;
        }
        if (!n.isObject()) {
            y.error("DRS-9101", "columns", "`columns:` is a mapping of column name to its type or properties");
            return out;
        }
        n.fields().forEachRemaining(e -> {
            String name = e.getKey();
            String path = "columns." + name;
            JsonNode c = e.getValue();
            if (!LayerYaml.ALIAS.matcher(name).matches()) {
                y.error("DRS-9101", path, "'" + name + "' is not a usable column name (a letter, then letters, digits, underscore)");
            }
            if (DatasetDef.BUILT_IN.contains(name)) {
                y.error("DRS-9101", path, "'" + name + "' is a built-in column every table has: it is not declared");
            }
            if (c.isTextual()) {
                out.put(name, new DatasetDef.Col(name, c.asText(), null, null, defaultRole(c.asText(), null), null, null, null, null, null, null, null, null, null, null));
                return;
            }
            if (!c.isObject()) {
                y.error("DRS-9101", path, "a column is a type (string) or a mapping { type, role, ... }");
                return;
            }
            y.unknownKeys(c, COLUMN_KEYS, "DRS-9101", path);
            String type = LayerYaml.text(c, "type", "string");
            String expr = c.has("expr") ? LayerYaml.text(c, "expr", "") : null;
            String role = LayerYaml.text(c, "role", defaultRole(type, expr));
            String additive = additive(c.path("additive"), role, path, y);
            String aggregate = c.has("aggregate") ? LayerYaml.text(c, "aggregate", "") : null;
            JsonNode unit = c.path("unit");
            String currency = unit.isObject() && unit.has("currency") ? unit.get("currency").asText() : null;
            String unitOf = unit.isObject() && unit.has("of") ? unit.get("of").asText() : unit.isTextual() ? unit.asText() : null;
            out.put(name, new DatasetDef.Col(name, type, c.has("path") ? LayerYaml.text(c, "path", "") : null, expr, role, c.has("meaning") ? LayerYaml.text(c, "meaning", "") : null,
                    c.has("references") ? LayerYaml.text(c, "references", "") : null, c.has("of") ? LayerYaml.text(c, "of", "") : null, currency, unitOf, additive, aggregate,
                    c.has("format") ? LayerYaml.text(c, "format", "") : null, LayerYaml.text(c, "id", ""), LayerYaml.text(c, "description", "")));
        });
        return out;
    }

    /** The role a column has when it does not say: decimals are amounts, dates dates, everything else a dimension. */
    static String defaultRole(String type, String expr) {
        return switch (type) {
            case "decimal", "integer" -> "amount";
            case "date", "timestamp" -> "date";
            default -> "dimension";
        };
    }

    private static String additive(JsonNode a, String role, String path, LayerYaml y) {
        if (a.isMissingNode() || a.isNull()) {
            return switch (role) {
                case "amount", "quantity" -> "true";
                default -> "false";
            };
        }
        if (a.isBoolean()) {
            return Boolean.toString(a.asBoolean());
        }
        if (a.isObject() && a.has("over-time")) {
            String t = a.get("over-time").asText();
            if (!DatasetDef.OVER_TIME.contains(t)) {
                y.error("DRS-9101", path + ".additive", "over-time is one of " + DatasetDef.OVER_TIME + ", not '" + t + "'");
            }
            return t;
        }
        y.error("DRS-9101", path + ".additive", "additive is true, false, or { over-time: last | first | avg | none }");
        return "false";
    }

    private static void checkColumns(Map<String, DatasetDef.Col> columns, LayerYaml y) {
        for (DatasetDef.Col c : columns.values()) {
            String path = "columns." + c.name();
            if (!DatasetDef.TYPES.contains(c.type())) {
                y.error("DRS-9101", path + ".type", "type is one of " + DatasetDef.TYPES + ", not '" + c.type() + "'");
            }
            if (!DatasetDef.ROLES.contains(c.role())) {
                y.error("DRS-9101", path + ".role", "role is one of " + DatasetDef.ROLES + ", not '" + c.role() + "'");
            }
            if (c.aggregate() != null && !DatasetDef.AGGREGATES.contains(c.aggregate())) {
                y.error("DRS-9101", path + ".aggregate", "aggregate is one of " + DatasetDef.AGGREGATES + ", not '" + c.aggregate() + "'");
            }
            if (c.aggregate() != null && Set.of("sum", "avg").contains(c.aggregate()) && !Set.of("integer", "decimal").contains(c.type())) {
                y.error("DRS-9101", path + ".aggregate", c.aggregate() + " needs a number; '" + c.name() + "' is " + c.type());
            }
            if (c.of() != null && !columns.containsKey(c.of())) {
                y.error("DRS-9101", path + ".of", "`of:` names the key this attribute describes; there is no column '" + c.of() + "'");
            }
            if (c.references() != null && c.references().isBlank()) {
                y.error("DRS-9101", path + ".references", "`references:` names the kind whose ids the column holds");
            }
            if (c.expr() != null) {
                expression(c.expr(), path + ".expr", "DRS-9101", y);
            }
        }
    }

    static Node expression(String text, String path, String code, LayerYaml y) {
        try {
            return ExprParser.parse(text);
        } catch (RuntimeException e) {
            y.error(code, path, "the expression does not parse: " + e.getMessage());
            return null;
        }
    }

    // ---- grain, time, refresh ----------------------------------------------------------------------------------------

    private static DatasetDef.Grain grain(JsonNode g, Map<String, DatasetDef.Col> columns, DatasetDef.Source source, boolean extending, LayerYaml y) {
        if (g.isMissingNode() || g.isNull()) {
            if (!extending && source != null && !DatasetDef.Source.PACK.equals(source.type())) {
                y.error("DRS-9104", "grain", "`grain:` says what one row is and which columns identify it (keys)");
            }
            return new DatasetDef.Grain("", List.of(), "businessDate");
        }
        List<String> keys = y.strings(g.path("keys"), "grain.keys", "DRS-9104");
        for (String k : keys) {
            if (!columns.containsKey(k) && !DatasetDef.BUILT_IN.contains(k) && !(extending && columns.isEmpty())) {
                y.error("DRS-9104", "grain.keys", "the key '" + k + "' is not a column of the dataset");
            }
        }
        String date = LayerYaml.text(g, "date", "businessDate");
        if (!columns.containsKey(date) && !DatasetDef.BUILT_IN.contains(date) && !extending) {
            y.error("DRS-9104", "grain.date", "the date column '" + date + "' is not a column of the dataset");
        }
        return new DatasetDef.Grain(LayerYaml.text(g, "says", ""), keys, date);
    }

    private static DatasetDef.Time time(JsonNode t, DatasetDef.Source source, LayerYaml y) {
        if (t.isMissingNode() || t.isNull()) {
            return null;                                                   // the default is decided with the source: live where it pushes, else snapshot (D6)
        }
        String mode = t.isTextual() ? t.asText() : LayerYaml.text(t, "mode", "snapshot");
        if (!DatasetDef.MODES.contains(mode)) {
            y.error("DRS-9103", "time.mode", "the time mode is one of " + DatasetDef.MODES + ", not '" + mode + "'");
        }
        if (source != null) {
            if ("live".equals(mode) && source.each() != null) {
                y.error("DRS-9103", "time.mode", "a table over an array (each) cannot be live");
            }
            if ("live".equals(mode) && Set.of(DatasetDef.Source.CONNECTOR, DatasetDef.Source.PYTHON).contains(source.type())) {
                y.error("DRS-9103", "time.mode", "a " + source.type() + " dataset is a snapshot (or a history), never live");
            }
        }
        JsonNode live = t.path("live");
        Integer rows = live.has("rows") ? live.get("rows").asInt() : null;
        Duration age = live.has("age") ? DatasetParser.span(live.get("age").asText()) : null;
        if (live.has("age") && age == null) {
            y.error("DRS-9103", "time.live.age", "an age such as 2h, 30m or PT2H");
        }
        DatasetDef.History history = null;
        JsonNode h = t.path("history");
        if (h.isObject()) {
            Duration every = DatasetParser.span(LayerYaml.text(h, "every", "10s"));
            if (every == null) {
                y.error("DRS-9103", "time.history.every", "an interval such as 10s or 1m");
            }
            history = new DatasetDef.History(every, h.path("keep").asInt(360), y.strings(h.path("by"), "time.history.by", "DRS-9103"),
                    y.strings(h.path("measures"), "time.history.measures", "DRS-9103"));
        } else if ("history".equals(mode)) {
            y.error("DRS-9103", "time.history", "a history dataset says how it is sampled: `history: { every, keep, by }`");
        }
        String storage = t.isObject() ? LayerYaml.text(t, "storage", null) : null;
        if (storage != null && !Set.of("columns", "direct").contains(storage)) {
            y.error("DRS-9103", "time.storage", "storage is columns or direct");
        }
        String asOf = t.isObject() ? LayerYaml.text(t, "as-of", "business-date") : "business-date";
        if (!Set.of("business-date", "latest-load").contains(asOf)) {
            y.error("DRS-9103", "time.as-of", "as-of is business-date or latest-load");
        }
        return new DatasetDef.Time(mode, asOf, t.isObject() ? LayerYaml.text(t, "keep", null) : null, storage, rows, age, history);
    }

    private static DatasetDef.Refresh refresh(JsonNode r, LayerYaml y) {
        if (r.isMissingNode() || r.isNull()) {
            return DatasetDef.Refresh.NONE;
        }
        if (r.isTextual()) {
            return new DatasetDef.Refresh(r.asText(), false);
        }
        if (!r.isObject()) {
            y.error("DRS-9100", "refresh", "refresh is a schedule in words, or { schedule, on-load }");
            return DatasetDef.Refresh.NONE;
        }
        return new DatasetDef.Refresh(LayerYaml.text(r, "schedule", ""), r.path("on-load").asBoolean(false));
    }

    // ---- security ----------------------------------------------------------------------------------------------------

    private static DatasetDef.Security security(JsonNode s, String name, Map<String, DatasetDef.Col> columns, boolean extending, LayerYaml y) {
        if (s.isMissingNode() || s.isNull()) {
            return DatasetDef.Security.NONE;
        }
        y.unknownKeys(s, Set.of("rows", "masked", "hidden-for"), "DRS-9106", "security");
        List<DatasetDef.Rule> rules = rules(s.path("rows"), "security.rows", y);
        for (int i = 0; i < rules.size(); i++) {
            Node n = expression(rules.get(i).where(), "security.rows[" + i + "].where", "DRS-9106", y);
            if (n != null) {
                ownColumnsOnly(n, name, columns, extending, "security.rows[" + i + "].where", "DRS-9106", y);
            }
        }
        List<String> masked = y.strings(s.path("masked"), "security.masked", "DRS-9106");
        for (String m : masked) {
            if (!extending && !columns.containsKey(m)) {
                y.error("DRS-9106", "security.masked", "a mask names a column of this dataset; there is no '" + m + "'");
            }
        }
        JsonNode h = s.path("hidden-for");
        return new DatasetDef.Security(rules, masked, y.strings(h.path("roles"), "security.hidden-for.roles", "DRS-9106"),
                y.strings(h.path("columns"), "security.hidden-for.columns", "DRS-9106"));
    }

    /** Row rules: {@code - { role: desk-head, where: "..." }}, the role one name or a list. */
    static List<DatasetDef.Rule> rules(JsonNode n, String path, LayerYaml y) {
        List<DatasetDef.Rule> out = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return out;
        }
        if (!n.isArray()) {
            y.error("DRS-9106", path, "row rules are a list of { role, where }");
            return out;
        }
        int i = 0;
        for (JsonNode r : n) {
            List<String> roles = y.strings(r.path("role"), path + "[" + i + "].role", "DRS-9106");
            String where = LayerYaml.text(r, "where", "");
            if (roles.isEmpty() || where.isBlank()) {
                y.error("DRS-9106", path + "[" + i + "]", "a row rule is { role: <role or list>, where: <condition> }");
            } else {
                out.add(new DatasetDef.Rule(roles, where));
            }
            i++;
        }
        return out;
    }

    /**
     * A rule of a dataset names only that dataset's columns: {@code col}, {@code <name>.col} (the dataset's own name, or its last part) and {@code user.*}
     * attributes. Anything else is the given code.
     */
    static void ownColumnsOnly(Node n, String name, Map<String, DatasetDef.Col> columns, boolean lenient, String path, String code, LayerYaml y) {
        Set<String> own = new LinkedHashSet<>(List.of(name, name.substring(name.lastIndexOf('.') + 1)));
        Node.walk(n, x -> {
            if (x instanceof Node.Ref r) {
                List<String> parts = r.parts();
                String column = parts.size() == 1 ? parts.get(0) : parts.get(parts.size() - 1);
                String table = parts.size() == 1 ? null : String.join(".", parts.subList(0, parts.size() - 1));
                if (table != null && !own.contains(table)) {
                    y.error(code, path, "a rule of '" + name + "' names only its own columns; '" + r.text() + "' belongs to another table (write that rule in a model)");
                } else if (!lenient && !columns.containsKey(column) && !DatasetDef.BUILT_IN.contains(column)) {
                    y.error(code, path, "the rule names '" + column + "', which is not a column of '" + name + "'");
                }
            }
        });
    }

    private static void tests(List<JsonNode> tests, Map<String, DatasetDef.Col> columns, LayerYaml y) {
        for (int i = 0; i < tests.size(); i++) {
            JsonNode t = tests.get(i);
            String path = "tests[" + i + "]";
            if (!t.isObject()) {
                y.error("DRS-9100", path, "a test is a mapping { name, unique | not-null | references-exist | agg, expect }");
                continue;
            }
            for (String k : List.of("unique", "not-null")) {
                for (String c : y.strings(t.path(k), path + "." + k, "DRS-9104")) {
                    if (!columns.isEmpty() && !columns.containsKey(c) && !DatasetDef.BUILT_IN.contains(c)) {
                        y.error("unique".equals(k) ? "DRS-9104" : "DRS-9101", path + "." + k, "no column '" + c + "'");
                    }
                }
            }
            if (t.has("agg") && !DatasetDef.AGGREGATES.contains(t.get("agg").asText())) {
                y.error("DRS-9101", path + ".agg", "agg is one of " + DatasetDef.AGGREGATES);
            }
        }
    }
}
