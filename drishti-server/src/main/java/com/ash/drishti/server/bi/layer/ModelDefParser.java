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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.Node;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a {@code model} item (docs/architecture/BI_MODEL_SPEC.md section 2) and checks what the text alone shows, with the codes of section 2.11:
 * {@code DRS-9120} structure, {@code 9122} a relationship that does not name two fields, {@code 9125} model security that names another alias or tries to
 * unmask, {@code 9128} a contract naming what the model lacks, {@code 9129} a later wave's key used now. What needs the datasets (are they there, do the
 * fields exist, is the one side unique, is a path ambiguous, do the measures compile, the security safeguard) is {@link ModelChecker}'s.
 */
public final class ModelDefParser {

    /** The outcome: the model (null when it could not be read) and the problems. */
    public record Result(ModelDef model, List<DatasetParser.Problem> problems) {

        public boolean ok() {
            return model != null && problems.stream().noneMatch(DatasetParser.Problem::error);
        }

        public String firstError() {
            return problems.stream().filter(DatasetParser.Problem::error).findFirst().map(p -> p.code() + " " + p.path() + ": " + p.message()).orElse("");
        }
    }

    static final Set<String> TOP = Set.of("rachana", "model", "version", "id", "datasets", "relationships", "measures", "calc-groups", "hierarchies", "parameters",
            "calendars", "security", "contract", "ai", "golden", "tests", "rollups", "lifecycle", "replaced-by", "layout", "title", "description", "owner", "tags",
            "endorsement", "endorsedBy", "audience", "sample", "implicit-from");
    private static final Set<String> MEASURE_KEYS = Set.of("id", "expr", "format", "description", "currency", "additive", "python", "inputs", "by", "via", "implicit", "title");
    private static final Set<String> CARDINALITIES = Set.of("many-to-one", "one-to-many", "one-to-one", "many-to-many");
    private static final java.util.regex.Pattern LABEL = java.util.regex.Pattern.compile("[^\\p{Cntrl}\\[\\]]{1,80}");

    private ModelDefParser() {}

    public static Result parse(String yaml) {
        LayerYaml y = new LayerYaml();
        JsonNode root = y.read(yaml, "DRS-9120", "model");
        if (root == null) {
            return new Result(null, y.problems());
        }
        return new Result(read(root, y), y.problems());
    }

    static ModelDef read(JsonNode root, LayerYaml y) {
        y.unknownKeys(root, TOP, "DRS-9120", "");
        if (root.path("rachana").asInt(0) != DatasetDef.RACHANA) {
            y.error("DRS-9120", "rachana", "a data model says `rachana: 2`");
        }
        String name = LayerYaml.text(root, "model", "");
        if (name.isBlank() || !LayerYaml.NAME.matcher(name).matches()) {
            y.error("DRS-9120", "model", "`model:` names the model (letters, digits, `.`, `_`, `-`, up to 64)" + (name.isBlank() ? "" : ", not '" + name + "'"));
        }
        String version = LayerYaml.text(root, "version", "");
        if (Semver.parse(version) == null) {
            y.error("DRS-9120", "version", "`version:` is the semver of the model's contract, such as 1.0.0, not '" + version + "'");
        }
        Map<String, ModelDef.Use> datasets = datasets(root.path("datasets"), y);
        List<ModelDef.Rel> rels = relationships(root.path("relationships"), datasets, y);
        Map<String, ModelDef.Meas> measures = measures(root.path("measures"), y);
        Map<String, List<String>> hierarchies = new LinkedHashMap<>();
        root.path("hierarchies").fields().forEachRemaining(e -> hierarchies.put(e.getKey(), y.strings(e.getValue(), "hierarchies." + e.getKey(), "DRS-9120")));
        ModelDef.Security security = security(root.path("security"), datasets, y);
        ModelDef.Contract contract = contract(root.path("contract"), measures, datasets, y);
        List<String> placeholders = new ArrayList<>();
        for (String k : List.of("calc-groups", "parameters")) {
            if (LayerYaml.nonEmpty(root.path(k))) {
                placeholders.add(k);
                y.error("DRS-9129", k, "`" + k + ":` arrives in wave W6; it is kept but refused until then: remove it to use the model now");
            }
        }
        measures.values().stream().filter(m -> m.via() != null).forEach(m -> y.error("DRS-9129", "measures." + m.name() + ".via",
                "`via:` (a measure along an inactive relationship) arrives in wave W7"));
        String lifecycle = LayerYaml.text(root, "lifecycle", "published");
        if (!ModelDef.LIFECYCLES.contains(lifecycle)) {
            y.error("DRS-9120", "lifecycle", "lifecycle is one of " + ModelDef.LIFECYCLES);
        }
        List<Dataset.Rollup> rollups = new ArrayList<>();
        int i = 0;
        for (JsonNode r : root.path("rollups")) {
            String table = LayerYaml.text(r, "table", "");
            if (!datasets.containsKey(table)) {
                y.error("DRS-9120", "rollups[" + i + "].table", "a rollup names the fact alias it aggregates; there is no '" + table + "'");
            }
            rollups.add(new Dataset.Rollup(LayerYaml.text(r, "name", "rollup" + i), table, y.strings(r.path("by"), "rollups[" + i + "].by", "DRS-9120"),
                    y.strings(r.path("measures"), "rollups[" + i + "].measures", "DRS-9120")));
            i++;
        }
        List<JsonNode> tests = new ArrayList<>();
        root.path("tests").forEach(tests::add);
        List<JsonNode> golden = new ArrayList<>();
        root.path("golden").forEach(golden::add);
        return new ModelDef(name, version, LayerYaml.text(root, "id", ""), LayerYaml.text(root, "title", name), LayerYaml.text(root, "description", ""),
                LayerYaml.text(root, "owner", ""), root.path("sample").asBoolean(false), LayerYaml.text(root, "endorsement", "none"), datasets, rels, measures, hierarchies,
                security, contract, lifecycle, root.has("replaced-by") ? LayerYaml.text(root, "replaced-by", "") : null, rollups, tests, golden,
                root.has("ai") ? root.get("ai") : null, root.has("implicit-from") ? LayerYaml.text(root, "implicit-from", "") : null, placeholders);
    }

    private static Map<String, ModelDef.Use> datasets(JsonNode n, LayerYaml y) {
        Map<String, ModelDef.Use> out = new LinkedHashMap<>();
        if (!n.isObject() || n.isEmpty()) {
            y.error("DRS-9120", "datasets", "a model names its datasets: `datasets: { <alias>: { dataset: <name>@<version>, role: fact | dimension } }`");
            return out;
        }
        n.fields().forEachRemaining(e -> {
            String alias = e.getKey();
            String path = "datasets." + alias;
            if (!LayerYaml.ALIAS.matcher(alias).matches()) {
                y.error("DRS-9120", path, "'" + alias + "' is not a usable alias (a letter, then letters, digits, underscore)");
            }
            JsonNode d = e.getValue();
            String ref = d.isTextual() ? d.asText() : LayerYaml.text(d, "dataset", "");
            Semver.Ref r = Semver.Ref.parse(ref);
            if (ref.isBlank() || r == null) {
                y.error("DRS-9120", path + ".dataset", "a dataset reference is name@version (pack.trading.trade@^1), not '" + ref + "'");
                return;
            }
            String role = d.isObject() ? LayerYaml.text(d, "role", "fact") : "fact";
            if (!ModelDef.ROLES.contains(role)) {
                y.error("DRS-9120", path + ".role", "role is one of " + ModelDef.ROLES);
            } else if ("bridge".equals(role)) {
                y.error("DRS-9129", path + ".role", "a bridge (many-to-many) arrives in wave W2");
            }
            out.put(alias, new ModelDef.Use(alias, r.name(), r.range(), role));
        });
        return out;
    }

    private static List<ModelDef.Rel> relationships(JsonNode n, Map<String, ModelDef.Use> datasets, LayerYaml y) {
        List<ModelDef.Rel> out = new ArrayList<>();
        int i = 0;
        for (JsonNode r : n) {
            String path = "relationships[" + i + "]";
            String[] from = field(LayerYaml.text(r, "from", ""));
            String[] to = field(LayerYaml.text(r, "to", ""));
            String card = LayerYaml.text(r, "cardinality", "many-to-one");
            String direction = LayerYaml.text(r, "direction", "single");
            i++;
            if (from == null || to == null) {
                y.error("DRS-9122", path, "a relationship is { from: alias.column, to: alias.column }");
                continue;
            }
            for (String[] f : List.of(from, to)) {
                if (!datasets.containsKey(f[0])) {
                    y.error("DRS-9122", path, "'" + f[0] + "' is not a dataset alias of this model");
                }
            }
            if (!CARDINALITIES.contains(card)) {
                y.error("DRS-9122", path + ".cardinality", "cardinality is one of " + CARDINALITIES);
            } else if ("many-to-many".equals(card)) {
                y.error("DRS-9129", path + ".cardinality", "many-to-many (through a bridge) arrives in wave W2");
            }
            if ("both".equals(direction)) {
                y.error("DRS-9129", path + ".direction", "direction: both arrives in wave W2; W1 filters flow from the one side to the many side");
            } else if (!"single".equals(direction)) {
                y.error("DRS-9122", path + ".direction", "direction is single (or both, from W2)");
            }
            JsonNode s = r.path("suggested");
            boolean oneToMany = "one-to-many".equals(card);
            out.add(new ModelDef.Rel(LayerYaml.text(r, "id", ""), oneToMany ? to[0] : from[0], oneToMany ? to[1] : from[1], oneToMany ? from[0] : to[0],
                    oneToMany ? from[1] : to[1], oneToMany ? "many-to-one" : card, direction, r.path("active").asBoolean(true), LayerYaml.text(r, "name", ""),
                    s.isObject() ? LayerYaml.text(s, "from", "") : null));
        }
        return out;
    }

    /** {@code alias.column} split, or null. */
    static String[] field(String text) {
        int dot = text.indexOf('.');
        if (dot <= 0 || dot == text.length() - 1 || text.indexOf('.', dot + 1) >= 0) {
            return null;
        }
        return new String[] {text.substring(0, dot), text.substring(dot + 1)};
    }

    private static Map<String, ModelDef.Meas> measures(JsonNode n, LayerYaml y) {
        Map<String, ModelDef.Meas> out = new LinkedHashMap<>();
        n.fields().forEachRemaining(e -> {
            String name = e.getKey();
            String path = "measures." + name;
            JsonNode m = e.getValue();
            if (!LABEL.matcher(name).matches()) {
                y.error("DRS-9127", path, "'" + name + "' is not a usable measure name (no brackets, up to 80 characters)");
            }
            if (m.isTextual()) {
                out.put(name, new ModelDef.Meas(name, "", m.asText(), null, "", null, null, null, null, null, null, false));
                return;
            }
            y.unknownKeys(m, MEASURE_KEYS, "DRS-9127", path);
            String expr = m.has("expr") ? LayerYaml.text(m, "expr", "") : null;
            String python = m.has("python") ? LayerYaml.text(m, "python", "") : null;
            if ((expr == null) == (python == null)) {
                y.error("DRS-9127", path, "a measure has an `expr` or names a `python` item, one of the two");
            }
            JsonNode add = m.path("additive");
            String additive = add.isBoolean() ? Boolean.toString(add.asBoolean()) : add.isObject() ? add.path("over-time").asText() : null;
            out.put(name, new ModelDef.Meas(name, LayerYaml.text(m, "id", ""), expr, m.has("format") ? LayerYaml.text(m, "format", "") : null,
                    LayerYaml.text(m, "description", ""), m.has("currency") ? LayerYaml.text(m, "currency", "") : null, additive, python,
                    y.strings(m.path("inputs"), path + ".inputs", "DRS-9127"), y.strings(m.path("by"), path + ".by", "DRS-9127"),
                    m.has("via") ? LayerYaml.text(m, "via", "") : null, m.path("implicit").asBoolean(false)));
        });
        return out;
    }

    private static ModelDef.Security security(JsonNode s, Map<String, ModelDef.Use> datasets, LayerYaml y) {
        if (s.isMissingNode() || s.isNull()) {
            return ModelDef.Security.NONE;
        }
        y.unknownKeys(s, Set.of("rows", "masked", "propagate", "may-see-all", "unmask", "unmasked"), "DRS-9125", "security");
        for (String k : List.of("unmask", "unmasked")) {
            if (s.has(k)) {
                y.error("DRS-9125", "security." + k, "a model cannot unmask: its security is only ever tighter than its datasets'");
            }
        }
        Map<String, List<DatasetDef.Rule>> rows = new LinkedHashMap<>();
        JsonNode r = s.path("rows");
        if (r.isArray()) {
            y.error("DRS-9125", "security.rows", "model row rules are given per dataset alias: `rows: { trades: [ { role, where } ] }`; a rule names only that alias's columns");
        } else {
            r.fields().forEachRemaining(e -> {
                String alias = e.getKey();
                String path = "security.rows." + alias;
                if (!datasets.containsKey(alias)) {
                    y.error("DRS-9125", path, "'" + alias + "' is not a dataset alias of this model");
                }
                List<DatasetDef.Rule> rules = DatasetDefParser.rules(e.getValue(), path, y);
                for (int i = 0; i < rules.size(); i++) {
                    Node n = DatasetDefParser.expression(rules.get(i).where(), path + "[" + i + "].where", "DRS-9125", y);
                    if (n != null) {
                        aliasOnly(n, alias, path + "[" + i + "].where", y);
                    }
                }
                rows.put(alias, rules);
            });
        }
        List<String> masked = y.strings(s.path("masked"), "security.masked", "DRS-9125");
        for (String m : masked) {
            String[] f = field(m);
            if (f == null || !datasets.containsKey(f[0])) {
                y.error("DRS-9125", "security.masked", "a model mask names alias.column; not '" + m + "'");
            }
        }
        String propagate = LayerYaml.text(s, "propagate", "dimension-to-fact");
        if (!"dimension-to-fact".equals(propagate)) {
            y.error("DRS-9125", "security.propagate", "the only propagation in W1 is dimension-to-fact");
        }
        Map<String, List<String>> maySeeAll = new LinkedHashMap<>();
        s.path("may-see-all").fields().forEachRemaining(e -> {
            if (!datasets.containsKey(e.getKey())) {
                y.error("DRS-9130", "security.may-see-all." + e.getKey(), "'" + e.getKey() + "' is not a dataset alias of this model");
            }
            maySeeAll.put(e.getKey(), y.strings(e.getValue(), "security.may-see-all." + e.getKey(), "DRS-9130"));
        });
        return new ModelDef.Security(rows, masked, propagate, maySeeAll);
    }

    /** A model rule on {@code alias} names only that alias's columns (and {@code user.*}). */
    private static void aliasOnly(Node n, String alias, String path, LayerYaml y) {
        Node.walk(n, x -> {
            if (x instanceof Node.Ref r && !(r.parts().size() == 1 || r.parts().get(0).equals(alias))) {
                y.error("DRS-9125", path, "a model rule on '" + alias + "' names only its columns; '" + r.text() + "' belongs to another dataset (give that dataset its own rule)");
            }
        });
    }

    private static ModelDef.Contract contract(JsonNode c, Map<String, ModelDef.Meas> measures, Map<String, ModelDef.Use> datasets, LayerYaml y) {
        if (c.isMissingNode() || c.isNull()) {
            return ModelDef.Contract.ALL;
        }
        String exposes = LayerYaml.text(c, "exposes", "all");
        if (!Set.of("all", "listed").contains(exposes)) {
            y.error("DRS-9128", "contract.exposes", "exposes is all or listed");
        }
        List<String> fields = y.strings(c.path("fields"), "contract.fields", "DRS-9128");
        List<String> ms = y.strings(c.path("measures"), "contract.measures", "DRS-9128");
        for (String f : fields) {
            String[] p = f.split("\\.");
            if (p.length < 2 || !datasets.containsKey(p[0])) {
                y.error("DRS-9128", "contract.fields", "the contract names '" + f + "', which is not alias.column of this model");
            }
        }
        for (String m : ms) {
            if (!measures.containsKey(m)) {
                y.error("DRS-9128", "contract.measures", "the contract names the measure '" + m + "', which the model lacks");
            }
        }
        Map<String, ModelDef.Deprecation> deprecated = new LinkedHashMap<>();
        c.path("deprecated").fields().forEachRemaining(e -> deprecated.put(e.getKey(), new ModelDef.Deprecation(LayerYaml.text(e.getValue(), "since", ""),
                LayerYaml.text(e.getValue(), "use", ""))));
        return new ModelDef.Contract(exposes, fields, ms, deprecated);
    }
}
