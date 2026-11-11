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
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.def.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes {@code config/tables/<domain>/<name>.model.yaml} (HOW_IT_FITS.md §A28 section 3.3): BI's model keys with
 * {@code tables:} in place of {@code datasets:}, aliases kept (Q10), measures in Rachana.
 */
public final class ModelYaml {

    static final Set<String> TOP = Set.of("rachana", "model", "version", "tables", "relationships", "measures", "hierarchies", "security", "bridges", "functions");
    static final Set<String> MEASURE = Set.of("expr", "python", "inputs", "by", "format", "description", "currency", "additive", "id", "title",
            "display", "folder", "hidden", "implicit");
    static final Set<String> RELATIONSHIP = Set.of("id", "from", "to", "cardinality", "direction", "active", "name", "suggested");

    /** What was read: the model (null when it could not be built) and every problem. */
    public record Result(ModelDef model, List<Problem> problems) {
        public boolean ok() {
            return model != null && problems.stream().noneMatch(Problem::isError);
        }
    }

    private ModelYaml() {}

    public static Result read(String yaml) {
        Yaml y = new Yaml();
        JsonNode root = y.tree(yaml);
        if (root == null) {
            return new Result(null, y.problems);
        }
        if (root.has("datasets")) {
            y.error(TableCodes.OLD_FORM, "datasets", "a BI model (datasets:), not a model of the layer (tables:): run drishti.py tables migrate on it");
            return new Result(null, y.problems);
        }
        Set<String> known = new java.util.HashSet<>(TOP);
        known.addAll(Yaml.ABOUT);
        known.addAll(ModelDef.EXTRA_KEYS);
        y.unknownKeys(root, known, "");
        String rachana = y.text(root, "rachana", "rachana", null);
        if (rachana == null) {
            y.warning(TableCodes.UNREADABLE, "rachana", "add rachana: 3 (the language version of the file)");
        } else if (!String.valueOf(TableYaml.RACHANA).equals(rachana)) {
            y.error(TableCodes.OLD_FORM, "rachana", "rachana: " + rachana + " is BI's form: run drishti.py tables migrate (--dry-run first)");
        }
        String name = y.text(root, "model", "model", null);
        if (name == null) {
            y.error(TableCodes.UNREADABLE, "model", "a model names itself: model: <name>");
            return new Result(null, y.problems);
        }
        Map<String, Object> about = new LinkedHashMap<>();
        for (String k : Yaml.ABOUT) {
            if (root.has(k)) {
                about.put(k, Yaml.plain(root.get(k)));
            }
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        for (String k : ModelDef.EXTRA_KEYS) {
            if (root.has(k)) {
                extras.put(k, Yaml.plain(root.get(k)));
            }
        }
        Map<String, List<String>> hierarchies = new LinkedHashMap<>();
        JsonNode h = root.get("hierarchies");
        if (h != null && h.isObject()) {
            h.fieldNames().forEachRemaining(k -> hierarchies.put(k, y.strings(h, k, "hierarchies")));
        }
        ModelDef m = new ModelDef(name, y.text(root, "version", "version", null), about, tables(root.get("tables"), y),
                relationships(root.get("relationships"), y), measures(root.get("measures"), y), hierarchies, security(root.get("security"), y), extras);
        return new Result(m, y.problems);
    }

    private static Map<String, ModelDef.Use> tables(JsonNode t, Yaml y) {
        Map<String, ModelDef.Use> out = new LinkedHashMap<>();
        if (t == null || t.isNull()) {
            y.error(TableCodes.UNREADABLE, "tables", "a model reads tables: tables: { trades: { table: banking.trading_risk_trades, role: fact } }");
            return out;
        }
        if (t.isArray()) {                                                  // HOW_IT_FITS.md section A4's list form: the alias is the table's own name
            for (JsonNode x : t) {
                String ref = x.asText();
                TableName.Ref r = TableName.Ref.parse(ref);
                String alias = r.name().contains(".") ? r.name().substring(r.name().indexOf('.') + 1) : r.name();
                out.put(alias, new ModelDef.Use(alias, r, "fact"));
            }
            y.info(TableCodes.UNREADABLE, "tables", "a list of tables: each is under its own name as alias (" + String.join(", ", out.keySet())
                    + "); write tables: { alias: { table, role } } to choose");
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = t.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String p = "tables." + e.getKey();
            JsonNode v = e.getValue();
            if (v.isValueNode()) {
                out.put(e.getKey(), new ModelDef.Use(e.getKey(), TableName.Ref.parse(v.asText()), "fact"));
                continue;
            }
            y.unknownKeys(v, Set.of("table", "role"), p);
            String table = y.text(v, "table", p, null);
            if (table == null) {
                y.error(TableCodes.UNREADABLE, p + ".table", "the table this alias reads");
                continue;
            }
            out.put(e.getKey(), new ModelDef.Use(e.getKey(), TableName.Ref.parse(table), y.text(v, "role", p, "fact")));
        }
        return out;
    }

    private static List<ModelDef.Relationship> relationships(JsonNode r, Yaml y) {
        List<ModelDef.Relationship> out = new ArrayList<>();
        if (r == null || r.isNull()) {
            return out;
        }
        if (!r.isArray()) {
            y.error(TableCodes.UNREADABLE, "relationships", "a list: - { from: trades.DESK, to: desks.id }");
            return out;
        }
        int i = 0;
        for (JsonNode x : r) {
            String p = "relationships[" + i++ + "]";
            y.unknownKeys(x, RELATIONSHIP, p);
            out.add(new ModelDef.Relationship(y.text(x, "id", p, null), y.text(x, "from", p, null), y.text(x, "to", p, null),
                    y.text(x, "cardinality", p, "many-to-one"), y.text(x, "direction", p, null), x.path("active").asBoolean(true), y.text(x, "name", p, null)));
        }
        return out;
    }

    private static Map<String, ModelDef.Measure> measures(JsonNode m, Yaml y) {
        Map<String, ModelDef.Measure> out = new LinkedHashMap<>();
        if (m == null || m.isNull()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = m.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String p = "measures." + e.getKey();
            JsonNode v = e.getValue();
            if (v.isValueNode()) {
                out.put(e.getKey(), new ModelDef.Measure(e.getKey(), v.asText(), null, null));
                continue;
            }
            Set<String> known = new java.util.HashSet<>(MEASURE);
            known.add("via");
            y.unknownKeys(v, known, p);
            Map<String, Object> python = new LinkedHashMap<>();
            Map<String, Object> keys = new LinkedHashMap<>();
            Map<String, Object> all = Yaml.map(v);
            all.forEach((k, x) -> {
                if (Set.of("python", "inputs", "by").contains(k)) {
                    python.put(k, x);
                } else if (!k.equals("expr") && !k.equals("via")) {
                    keys.put(k, x);
                }
            });
            String expr = y.text(v, "expr", p, null);
            if (expr == null && python.isEmpty()) {
                y.error(TableCodes.UNREADABLE, p, "a measure has an expr (or is a Python measure)");
            }
            out.put(e.getKey(), new ModelDef.Measure(e.getKey(), expr, python, keys));
        }
        return out;
    }

    private static ModelDef.Security security(JsonNode s, Yaml y) {
        if (s == null || s.isNull()) {
            return ModelDef.Security.NONE;
        }
        Map<String, List<TableDef.Rule>> rows = new LinkedHashMap<>();
        JsonNode r = s.get("rows");
        if (r != null && r.isObject()) {
            r.fieldNames().forEachRemaining(alias -> rows.put(alias, TableYaml.rules(r.get(alias), "security.rows." + alias, y)));
        }
        Map<String, List<String>> maySeeAll = new LinkedHashMap<>();
        JsonNode a = s.get("may-see-all");
        if (a != null && a.isObject()) {
            a.fieldNames().forEachRemaining(alias -> maySeeAll.put(alias, y.strings(a, alias, "security.may-see-all")));
        }
        Map<String, Object> others = Yaml.map(s);
        others.keySet().removeAll(Set.of("rows", "masked", "may-see-all"));
        return new ModelDef.Security(rows, y.strings(s, "masked", "security"), maySeeAll, others);
    }

    // ---- writing --------------------------------------------------------------------------------------------------------------------

    /** The canonical YAML of {@code m}, with {@code header} comment lines first. */
    public static String write(ModelDef m, List<String> header) {
        Writer w = new Writer();
        header.forEach(h -> w.line(h.startsWith("#") || h.isEmpty() ? h : "# " + h));
        w.line("rachana: " + TableYaml.RACHANA);
        w.kv("model", m.name());
        w.kv("version", m.version());
        for (Map.Entry<String, Object> e : m.about().entrySet()) {
            w.kv(e.getKey(), e.getValue());
        }
        w.line("tables:");
        int width = m.tables().keySet().stream().mapToInt(String::length).max().orElse(0) + 1;
        for (ModelDef.Use u : m.tables().values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("table", u.table().toString());
            x.put("role", u.role());
            w.line("  " + Writer.key(u.alias()) + ":" + " ".repeat(Math.max(1, width - Writer.key(u.alias()).length())) + Writer.flow(x));
        }
        if (!m.relationships().isEmpty()) {
            w.line("relationships:");
            for (ModelDef.Relationship r : m.relationships()) {
                Map<String, Object> x = new LinkedHashMap<>();
                if (r.id() != null) {
                    x.put("id", r.id());
                }
                x.put("from", r.from());
                x.put("to", r.to());
                if (!"many-to-one".equals(r.cardinality())) {
                    x.put("cardinality", r.cardinality());
                }
                if (r.direction() != null) {
                    x.put("direction", r.direction());
                }
                if (!r.active()) {
                    x.put("active", false);
                }
                if (r.label() != null) {
                    x.put("name", r.label());
                }
                w.line("  - " + Writer.flow(x));
            }
        }
        if (!m.measures().isEmpty()) {
            w.line("measures:");
            int mw = m.measures().keySet().stream().mapToInt(k -> Writer.key(k).length()).max().orElse(0) + 1;
            for (ModelDef.Measure x : m.measures().values()) {
                Map<String, Object> v = new LinkedHashMap<>();
                if (x.expr() != null) {
                    v.put("expr", x.expr());
                }
                v.putAll(x.python());
                v.putAll(x.keys());
                String k = Writer.key(x.name());
                w.line("  " + k + ":" + " ".repeat(Math.max(1, mw - k.length())) + Writer.flow(v));
            }
        }
        if (!m.hierarchies().isEmpty()) {
            w.line("hierarchies:");
            m.hierarchies().forEach((k, levels) -> w.line("  " + Writer.key(k) + ": " + Writer.flow(levels)));
        }
        for (Map.Entry<String, Object> e : m.extras().entrySet()) {
            w.kv(e.getKey(), e.getValue());
        }
        ModelDef.Security s = m.security();
        if (!s.isEmpty()) {
            w.line("security:");
            if (!s.rows().isEmpty()) {
                w.line("  rows:");
                s.rows().forEach((alias, rules) -> {
                    w.line("    " + Writer.key(alias) + ":");
                    for (TableDef.Rule r : rules) {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("role", r.roles().size() == 1 ? r.roles().get(0) : r.roles());
                        x.put("where", r.where());
                        w.line("      - " + Writer.flow(x));
                    }
                });
            }
            if (!s.masked().isEmpty()) {
                w.line("  masked: " + Writer.flow(s.masked()));
            }
            if (!s.maySeeAll().isEmpty()) {
                w.line("  may-see-all: " + Writer.flow(s.maySeeAll()));
            }
            s.others().forEach((k, v) -> w.line("  " + Writer.key(k) + ": " + Writer.flow(v)));
        }
        return w.text();
    }
}
