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
package com.ash.drishti.tables.yaml;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.TableDef;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes {@code config/tables/<domain>/<name>.table.yaml} (planned in BI_FOUNDATION_2911E.md section 3.1, built here). Reading
 * collects every problem by key; it checks the file's shape only (the meaning is {@code TableChecks}'s). Writing gives the canonical
 * form the migration writes, keys in a fixed order, so a second migration changes nothing.
 */
public final class TableYaml {

    /** The language version of the layer's files (Q25). */
    public static final int RACHANA = 3;

    static final Set<String> TOP = Set.of("rachana", "table", "was", "version", "from", "grain", "filter", "columns", "keys", "keep", "says",
            "materialize", "as-of", "refresh", "rules", "hidden-for", "tests");
    static final Set<String> FROM = Set.of("kind", "pack", "connector", "lake", "table", "query", "params", "python", "inputs", "model");
    static final Set<String> COLUMN = Set.of("path", "expr", "python", "type", "role", "meaning", "references", "unit", "additive", "aggregate",
            "format", "key", "description", "masked", "hidden-for", "title", "id", "term", "means", "sign");

    /** What was read: the table (null when it could not be built) and every problem. */
    public record Result(TableDef table, List<Problem> problems) {
        public boolean ok() {
            return table != null && problems.stream().noneMatch(Problem::isError);
        }
    }

    private TableYaml() {}

    /** True when {@code yaml} looks like a table of the layer ({@code table:} at the top) rather than a BI dataset. */
    public static boolean isTable(String yaml) {
        return yaml != null && yaml.lines().anyMatch(l -> l.startsWith("table:"));
    }

    public static Result read(String yaml) {
        Yaml y = new Yaml();
        JsonNode root = y.tree(yaml);
        if (root == null) {
            return new Result(null, y.problems);
        }
        if (root.has("dataset") || root.has("datasets")) {
            y.error(TableCodes.OLD_FORM, "dataset", "a BI dataset, not a virtual table: run drishti.py tables migrate on it (--dry-run first)");
            return new Result(null, y.problems);
        }
        Set<String> known = new java.util.HashSet<>(TOP);
        known.addAll(Yaml.ABOUT);
        y.unknownKeys(root, known, "");
        String rachana = y.text(root, "rachana", "rachana", null);
        if (rachana == null) {
            y.warning(TableCodes.UNREADABLE, "rachana", "add rachana: 3 (the language version of the file)");
        } else if (!String.valueOf(RACHANA).equals(rachana)) {
            y.error(TableCodes.OLD_FORM, "rachana", "rachana: " + rachana + " is BI's form: run drishti.py tables migrate (--dry-run first)");
        }
        String name = y.text(root, "table", "table", null);
        if (name == null) {
            y.error(TableCodes.UNREADABLE, "table", "a table names itself: table: <domain>.<name>");
        }
        Map<String, Object> about = new LinkedHashMap<>();
        for (String k : Yaml.ABOUT) {
            if (root.has(k)) {
                about.put(k, Yaml.plain(root.get(k)));
            }
        }
        TableDef.Source from = from(root.get("from"), y);
        Map<String, ColumnDef> columns = columns(root.get("columns"), y);
        List<String> keys = y.strings(root, "keys", "");
        columns.values().stream().filter(ColumnDef::key).map(ColumnDef::name).filter(k -> !keys.contains(k)).forEach(keys::add);
        String keepLatest = null;
        String retention = null;
        JsonNode keep = root.get("keep");
        if (keep != null && keep.isObject()) {
            y.unknownKeys(keep, Set.of("latest"), "keep");
            keepLatest = y.text(keep, "latest", "keep", null);
        } else if (keep != null && keep.isValueNode()) {
            retention = keep.asText();
        }
        TableDef.Materialize mat = materialize(root.get("materialize"), y);
        List<TableDef.Rule> rules = rules(root.get("rules"), "rules", y);
        List<Map<String, Object>> tests = new ArrayList<>();
        JsonNode t = root.get("tests");
        if (t != null && t.isArray()) {
            t.forEach(x -> tests.add(Yaml.map(x)));
        } else if (t != null && !t.isNull()) {
            y.error(TableCodes.UNREADABLE, "tests", "a list of tests");
        }
        TableDef def = name == null ? null : new TableDef(name, y.text(root, "was", "was", null), y.text(root, "version", "version", null), about, from,
                y.text(root, "grain", "grain", null), y.text(root, "filter", "filter", null), columns, keys, keepLatest, y.text(root, "says", "says", null),
                mat, y.text(root, "as-of", "as-of", null), retention, Yaml.map(root.get("refresh")), rules, y.strings(root, "hidden-for", ""), tests);
        return new Result(def, y.problems);
    }

    private static TableDef.Source from(JsonNode f, Yaml y) {
        if (f == null || f.isNull()) {
            y.error(TableCodes.UNREADABLE, "from", "a table says where its rows come from: from: { kind: trade } (or lake, table, query, python, model)");
            return null;
        }
        if (f.isValueNode()) {
            return TableDef.Source.ofKind(f.asText());
        }
        y.unknownKeys(f, FROM, "from");
        JsonNode model = f.get("model");
        JsonNode query = f.get("query");
        String modelName = model == null ? null : model.asText();
        Map<String, Object> modelQuery = modelName != null && query != null && query.isObject() ? Yaml.map(query) : null;
        String sql = modelName == null && query != null && query.isValueNode() ? query.asText() : null;
        TableDef.Source s = new TableDef.Source(y.text(f, "kind", "from", null), y.text(f, "pack", "from", null), y.text(f, "connector", "from", null),
                y.text(f, "lake", "from", null), y.text(f, "table", "from", null), sql, Yaml.map(f.get("params")), y.text(f, "python", "from", null),
                Yaml.map(f.get("inputs")), modelName, modelQuery);
        if (s.form() == TableDef.Source.Form.NONE) {
            y.error(TableCodes.UNREADABLE, "from", "from names a kind, a lake table, a table, a query, a Python item or a model");
        }
        return s;
    }

    private static Map<String, ColumnDef> columns(JsonNode c, Yaml y) {
        Map<String, ColumnDef> out = new LinkedHashMap<>();
        if (c == null || c.isNull()) {
            y.error(TableCodes.UNREADABLE, "columns", "a table has columns");
            return out;
        }
        if (!c.isObject()) {
            y.error(TableCodes.UNREADABLE, "columns", "a mapping of column name to its path or definition");
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = c.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String n = e.getKey();
            JsonNode v = e.getValue();
            String path = "columns." + n;
            if (v.isValueNode()) {
                out.put(n, ColumnDef.ofPath(n, v.asText(), null));          // the short form: NAME: $.PATH
                continue;
            }
            if (!v.isObject()) {
                y.error(TableCodes.UNREADABLE, path, "a path or a mapping");
                continue;
            }
            y.unknownKeys(v, COLUMN, path);
            JsonNode add = v.get("additive");
            out.put(n, new ColumnDef(n, y.text(v, "path", path, null), y.text(v, "expr", path, null), Yaml.plain(v.get("python")),
                    y.text(v, "type", path, null), y.text(v, "role", path, null), y.text(v, "meaning", path, null), y.text(v, "references", path, null),
                    y.text(v, "unit", path, null), add == null || add.isNull() ? null : add.asBoolean(), y.text(v, "aggregate", path, null),
                    y.text(v, "format", path, null), v.path("key").asBoolean(false) || "key".equals(v.path("role").asText()), y.text(v, "description", path, null),
                    v.path("masked").asBoolean(false), y.strings(v, "hidden-for", path)));
        }
        return out;
    }

    private static TableDef.Materialize materialize(JsonNode m, Yaml y) {
        if (m == null || m.isNull()) {
            return TableDef.Materialize.NONE;
        }
        if (m.isValueNode()) {
            String mode = m.asText();
            if (!TableDef.Materialize.MODES.contains(mode)) {
                y.error(TableCodes.MATERIALIZE, "materialize", "materialize is none, on-load or { live: { rows, age } }, not '" + mode + "'");
            }
            return new TableDef.Materialize(mode, null, null);
        }
        y.unknownKeys(m, Set.of("live"), "materialize");
        JsonNode live = m.get("live");
        if (live == null || !live.isObject()) {
            y.error(TableCodes.MATERIALIZE, "materialize", "materialize is none, on-load or { live: { rows, age } }");
            return TableDef.Materialize.NONE;
        }
        y.unknownKeys(live, Set.of("rows", "age"), "materialize.live");
        Integer rows = live.has("rows") ? live.get("rows").asInt() : null;
        return new TableDef.Materialize(TableDef.Materialize.MODE_LIVE, rows, y.text(live, "age", "materialize.live", null));
    }

    static List<TableDef.Rule> rules(JsonNode r, String path, Yaml y) {
        List<TableDef.Rule> out = new ArrayList<>();
        if (r == null || r.isNull()) {
            return out;
        }
        if (!r.isArray()) {
            y.error(TableCodes.RULE, path, "a list of rules: - { role: desk-head, where: \"$.DESK in user.desks\" }");
            return out;
        }
        int i = 0;
        for (JsonNode x : r) {
            String p = path + "[" + i++ + "]";
            if (!x.isObject()) {
                y.error(TableCodes.RULE, p, "a rule is { role, where }");
                continue;
            }
            y.unknownKeys(x, Set.of("role", "roles", "where"), p);
            List<String> roles = y.strings(x, x.has("roles") ? "roles" : "role", p);
            JsonNode w = x.get("where");
            out.add(new TableDef.Rule(roles, w == null || w.isNull() ? null : w.asText()));
        }
        return out;
    }

    // ---- writing --------------------------------------------------------------------------------------------------------------------

    /** The canonical YAML of {@code t}, with {@code header} comment lines first (each written as {@code # line}). */
    public static String write(TableDef t, List<String> header) {
        Writer w = new Writer();
        header.forEach(h -> w.line("# " + h));
        w.line("rachana: " + RACHANA);
        w.kv("table", t.name());
        if (t.was() != null) {
            w.kv("was", t.was());
        }
        w.kv("version", t.version());
        for (String k : List.of("id", "title", "description", "owner", "sample", "endorsement", "endorsedBy", "audience", "tags", "lifecycle",
                "replaced-by", "ai", "layout")) {
            if (t.about().containsKey(k)) {
                w.kv(k, t.about().get(k));
            }
        }
        w.kv("from", from(t.from()));
        if (t.grain() != null) {
            w.kv("grain", t.grain());
        }
        if (t.says() != null) {
            w.kv("says", t.says());
        }
        if (t.filter() != null) {
            w.kv("filter", t.filter());
        }
        if (!t.keys().isEmpty()) {
            w.kv("keys", t.keys());
        }
        if (t.keepLatest() != null) {
            w.kv("keep", Map.of("latest", t.keepLatest()));
        } else if (t.retention() != null) {
            w.kv("keep", t.retention());
        }
        w.line("columns:");
        int width = t.columns().keySet().stream().mapToInt(String::length).max().orElse(0) + 1;
        for (ColumnDef c : t.columns().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (c.path() != null) {
                m.put("path", c.path());
            }
            if (c.expr() != null) {
                m.put("expr", c.expr());
            }
            if (c.python() != null) {
                m.put("python", c.python());
            }
            if (c.type() != null) {
                m.put("type", c.type());
            }
            m.putAll(c.describing());
            w.line("  " + Writer.key(c.name()) + ":" + " ".repeat(Math.max(1, width - Writer.key(c.name()).length())) + Writer.flow(m));
        }
        if (!t.rules().isEmpty()) {
            w.line("rules:");
            for (TableDef.Rule r : t.rules()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", r.roles().size() == 1 ? r.roles().get(0) : r.roles());
                m.put("where", r.where());
                w.line("  - " + Writer.flow(m));
            }
        }
        if (!t.hiddenFor().isEmpty()) {
            w.kv("hidden-for", t.hiddenFor());
        }
        if (t.asOf() != null) {
            w.kv("as-of", t.asOf());
        }
        if (!t.refresh().isEmpty()) {
            w.kv("refresh", t.refresh());
        }
        TableDef.Materialize m = t.materialize();
        if (m.live()) {
            Map<String, Object> live = new LinkedHashMap<>();
            if (m.rows() != null) {
                live.put("rows", m.rows());
            }
            if (m.age() != null) {
                live.put("age", m.age());
            }
            w.kv("materialize", Map.of("live", live));
        } else {
            w.kv("materialize", m.mode());
        }
        if (!t.tests().isEmpty()) {
            w.line("tests:");
            t.tests().forEach(x -> w.line("  - " + Writer.flow(x)));
        }
        return w.text();
    }

    private static Map<String, Object> from(TableDef.Source s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            return m;
        }
        put(m, "kind", s.kind());
        put(m, "pack", s.pack());
        put(m, "connector", s.connector());
        put(m, "lake", s.lake());
        put(m, "table", s.table());
        put(m, "python", s.python());
        put(m, "model", s.model());
        if (s.query() != null) {
            m.put("query", s.query());
        } else if (!s.modelQuery().isEmpty()) {
            m.put("query", s.modelQuery());
        }
        if (!s.params().isEmpty()) {
            m.put("params", s.params());
        }
        if (!s.inputs().isEmpty()) {
            m.put("inputs", s.inputs());
        }
        return m;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
