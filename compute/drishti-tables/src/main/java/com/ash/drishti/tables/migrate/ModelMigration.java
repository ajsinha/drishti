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
package com.ash.drishti.tables.migrate;

import com.ash.drishti.tables.check.ExpressionChecker;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.def.TableName;
import com.ash.drishti.tables.yaml.Yaml;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One BI model into a model of the layer, key by key (docs/architecture/ARCHITECTURE.md §28 section 3.3): {@code datasets:} becomes {@code tables:} with the
 * aliases kept (Q10) and each dataset named as its table (Q11); {@code via:} becomes {@code calc(..., via = 'r_5')}; every expression goes
 * through {@link Translate}; the other keys are kept as written.
 */
final class ModelMigration {

    private static final Set<String> ABOUT = Set.of("id", "title", "description", "owner", "sample", "endorsement", "endorsedBy", "audience", "tags",
            "ai", "layout", "lifecycle", "replaced-by", "golden", "contract", "perspectives", "fields");

    private ModelMigration() {}

    /**
     * @param tables a dataset's name to its migrated table (for the columns of each alias), or null when the dataset is not in the run
     */
    static ModelDef migrate(JsonNode m, Translate translate, Function<String, TableDef> tables, Function<String, JsonNode> datasets,
            List<TablesMigrator.Change> changes, List<String> notes) {
        Map<String, Object> history = new LinkedHashMap<>();
        String name = m.path("model").asText();
        Map<String, ModelDef.Use> uses = new LinkedHashMap<>();
        Set<String> columns = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> e : m.path("datasets").properties()) {
            String alias = e.getKey();
            JsonNode v = e.getValue();
            String ref = v.isValueNode() ? v.asText() : v.path("dataset").asText();
            TableName.Ref r = TableName.Ref.parse(ref);
            String table = TableName.ofDataset(r.name());
            uses.put(alias, new ModelDef.Use(alias, new TableName.Ref(table, r.major()), v.path("role").asText("fact")));
            JsonNode ds = datasets.apply(r.name());
            if (ds != null && ds.path("time").path("history").isObject()) {
                history.put(alias, Yaml.MAPPER.convertValue(ds.path("time").path("history"), Object.class));
            }
            TableDef t = tables.apply(r.name());
            if (t == null) {
                notes.add("alias " + alias + ": " + r.name() + " is not in this run; its columns are taken as they are written");
                continue;
            }
            t.columns().keySet().forEach(c -> columns.add(alias + "." + c));
            t.keys().forEach(k -> keys.add(alias + "." + k));
            TableDef.BUILT_INS.forEach(b -> {
                columns.add(alias + "." + b);
                keys.add(alias + "." + b);
            });
        }
        Map<String, List<String>> hierarchies = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> h : m.path("hierarchies").properties()) {
            List<String> levels = new ArrayList<>();
            h.getValue().forEach(x -> levels.add(x.asText()));
            hierarchies.put(h.getKey(), levels);
        }
        Map<String, Object> about = new LinkedHashMap<>();
        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : m.properties()) {
            if (ABOUT.contains(e.getKey())) {
                about.put(e.getKey(), Yaml.MAPPER.convertValue(e.getValue(), Object.class));
            } else if (ModelDef.EXTRA_KEYS.contains(e.getKey())) {
                extras.put(e.getKey(), Yaml.MAPPER.convertValue(e.getValue(), Object.class));
            }
        }
        if (!history.isEmpty() && !extras.containsKey("history")) {
            extras.put("history", history.size() == 1 ? history.values().iterator().next() : history);
            notes.add("the live history of " + String.join(", ", history.keySet()) + " is the model's history: (it samples measures; Q14)");
        }
        if (extras.containsKey("calc-groups")) {
            notes.add("calc-groups: their item expressions are DEL templates with SELECTED; part A's translator converts them (kept as written here)");
        }
        if (m.has("implicit-from")) {
            notes.add("implicit-from: a table's implicit model is table:<name> in the layer; the key is dropped");
        }
        Set<String> scope = columns.isEmpty() ? null : columns;
        return new ModelDef(name, DatasetMigration.version(m.path("version").asText("1.0.0")), about, uses, relationships(m.path("relationships")),
                measures(m.path("measures"), name, scope, keys, translate, changes), hierarchies, security(m.path("security"), name, scope, keys, translate, changes),
                extras);
    }

    static List<ModelDef.Relationship> relationships(JsonNode r) {
        List<ModelDef.Relationship> out = new ArrayList<>();
        for (JsonNode x : r) {
            out.add(new ModelDef.Relationship(x.path("id").asText(null), x.path("from").asText(null), x.path("to").asText(null),
                    x.path("cardinality").asText("many-to-one"), x.path("direction").asText(null), x.path("active").asBoolean(true), x.path("name").asText(null)));
        }
        return out;
    }

    static Map<String, ModelDef.Measure> measures(JsonNode ms, String item, Set<String> columns, Set<String> keys, Translate translate,
            List<TablesMigrator.Change> changes) {
        Map<String, ModelDef.Measure> out = new LinkedHashMap<>();
        Set<String> cols = columns == null ? Set.of() : columns;
        for (Map.Entry<String, JsonNode> e : ms.properties()) {
            String n = e.getKey();
            JsonNode v = e.getValue();
            Map<String, Object> python = new LinkedHashMap<>();
            Map<String, Object> other = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> k : v.properties()) {
                Object val = Yaml.MAPPER.convertValue(k.getValue(), Object.class);
                switch (k.getKey()) {
                    case "python", "inputs", "by" -> python.put(k.getKey(), val);
                    case "expr", "via", "implicit" -> { }
                    default -> other.put(k.getKey(), val);
                }
            }
            String expr = null;
            if (v.isValueNode() || v.has("expr")) {
                String del = v.isValueNode() ? v.asText() : v.path("expr").asText();
                Translate.Out o = translate.model(del, ExpressionChecker.Scope.AGGREGATE, cols, keys);
                expr = o.text() == null ? del : o.text();
                if (v.has("via") && o.text() != null) {
                    expr = "calc(" + expr + ", via = '" + v.path("via").asText() + "')";
                }
                List<String> notes = new ArrayList<>(o.notes());
                if (v.has("via")) {
                    notes.add("via: " + v.path("via").asText() + " is calc(..., via = '" + v.path("via").asText() + "')");
                }
                changes.add(new TablesMigrator.Change(item, "measures." + n + ".expr", del, o.text() == null ? null : expr, notes, o.refusal()));
            }
            out.put(n, new ModelDef.Measure(n, expr, python, other));
        }
        return out;
    }

    static ModelDef.Security security(JsonNode s, String item, Set<String> columns, Set<String> keys, Translate translate, List<TablesMigrator.Change> changes) {
        if (s.isMissingNode() || s.isNull()) {
            return ModelDef.Security.NONE;
        }
        Set<String> cols = columns == null ? Set.of() : columns;
        Map<String, List<TableDef.Rule>> rows = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : s.path("rows").properties()) {
            List<TableDef.Rule> rules = new ArrayList<>();
            int i = 0;
            for (JsonNode r : e.getValue()) {
                String del = r.path("where").asText("True");
                Translate.Out o = translate.model(del, ExpressionChecker.Scope.MODEL_RULE, cols, keys);
                changes.add(new TablesMigrator.Change(item, "security.rows." + e.getKey() + "[" + i++ + "].where", del, o.text(), o.notes(), o.refusal()));
                List<String> roles = new ArrayList<>();
                JsonNode role = r.has("roles") ? r.get("roles") : r.path("role");
                if (role.isArray()) {
                    role.forEach(x -> roles.add(x.asText()));
                } else if (!role.isMissingNode()) {
                    roles.add(role.asText());
                }
                rules.add(new TableDef.Rule(roles, o.text() == null ? del : o.text()));
            }
            rows.put(e.getKey(), rules);
        }
        List<String> masked = new ArrayList<>();
        s.path("masked").forEach(x -> masked.add(x.asText()));
        Map<String, List<String>> maySeeAll = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : s.path("may-see-all").properties()) {
            List<String> roles = new ArrayList<>();
            e.getValue().forEach(x -> roles.add(x.asText()));
            maySeeAll.put(e.getKey(), roles);
        }
        Map<String, Object> others = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : s.properties()) {
            if (!Set.of("rows", "masked", "may-see-all").contains(e.getKey())) {
                others.put(e.getKey(), Yaml.MAPPER.convertValue(e.getValue(), Object.class));
            }
        }
        return new ModelDef.Security(rows, masked, maySeeAll, others);
    }
}
