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
package com.ash.drishti.server.bi.migrate;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.expr.Expressions;
import com.ash.drishti.server.bi.layer.RuleText;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Step 2 of the converter for a {@code rachana: 1} dataset with {@code tables:} (docs/architecture/BI_MODEL_SPEC.md sections 8.1 to 8.3): one user dataset
 * per table ({@code <old>-<table>}, its source, columns, time mode, refresh, masks and row rules) and one model under the old name (the tables as aliases of
 * the same names, so {@code trades.desk} keeps its meaning; the relationships, measures, hierarchies, rollups and tests as written).
 *
 * <p>Security moves to the datasets (section 2.6): a rule that names one table becomes that dataset's rule (its columns bare); a rule that lets every row
 * through ({@code True}) is given to every dataset, so a role the old file did not name still sees nothing. A role restricted on one table and given no rule on
 * another would see nothing of the other; in 2.1 such a question was refused. With {@link Exposure#NOTE} (the shipped samples, section 8.2) the role is given
 * every row of the other datasets and the model notes it in {@code security.may-see-all} (the allow-listed change of section 8.5); with
 * {@link Exposure#CLOSED} (stored items converted unattended) it keeps seeing nothing there, and the report says how to open it. The converter never widens
 * silently. A rule naming two tables cannot be bound to one dataset: the item is refused ({@code DRS-9190}). Pure.
 */
final class DatasetSplitter {

    /** What a role restricted on one dataset sees of the others: every row, noted in the model ({@code NOTE}), or nothing ({@code CLOSED}). */
    enum Exposure { NOTE, CLOSED }

    static final com.fasterxml.jackson.databind.ObjectMapper YAML = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

    /** One file the converter writes: its kind, its name and its path under the BI folder. */
    record Output(String kind, String name, String path, String yaml) {}

    /** The split of one dataset: its datasets, its model (still open to additions), what changed in meaning, the manual steps and the problems. */
    static final class Split {
        final Dataset v1;
        final ModelText model;
        final List<Output> datasets = new ArrayList<>();
        final Map<String, String> tableDataset = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        final List<String> manual = new ArrayList<>();
        final List<String> problems = new ArrayList<>();

        Split(Dataset v1, ModelText model) {
            this.v1 = v1;
            this.model = model;
        }

        int major() {
            return Math.max(1, v1.version());
        }

        String ref() {
            return v1.name() + "@" + major();
        }
    }

    private DatasetSplitter() {}

    /** The user dataset name of a table: {@code trading-risk} and {@code netting_sets} give {@code trading-risk-netting-sets}. */
    static String datasetName(String model, String table) {
        return model + "-" + table.replace('_', '-');
    }

    static Split split(Dataset d, JsonNode root, String header, Exposure exposure) {
        String version = Math.max(1, d.version()) + ".0.0";
        ModelText m = new ModelText(d.name(), version);
        m.header = header;
        Split s = new Split(d, m);
        catalogue(root, m.catalogue);
        List<Dataset.Table> tables = d.tables().values().stream().filter(t -> !t.isHistory()).toList();
        Map<String, List<Rule>> rules = rules(d, tables, exposure, s);
        Set<String> facts = facts(d);
        for (Dataset.Table t : tables) {
            String name = datasetName(d.name(), t.name());
            s.tableDataset.put(t.name(), name);
            m.datasets.put(t.name(), new String[] {name, facts.contains(t.name()) || tables.size() == 1 ? "fact" : "dimension"});
            s.datasets.add(new Output("dataset", name, "datasets/" + name + ".dataset.yaml", dataset(d, t, root, header, version, name, rules.getOrDefault(t.name(), List.of()), s)));
        }
        for (Dataset.Relationship r : d.relationships()) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("from", r.fromTable() + "." + r.fromColumn());
            e.put("to", r.toTable() + "." + r.toColumn());
            e.put("cardinality", "many-to-one".equals(r.cardinality()) ? null : r.cardinality());
            e.put("direction", r.direction() == null || "single".equals(r.direction()) ? null : r.direction());
            boolean twoFacts = r.active() && "fact".equals(role(m, r.fromTable())) && "fact".equals(role(m, r.toTable()));
            if (twoFacts) {
                s.notes.add("the relationship " + r.label() + " joins two facts: kept inactive, so the facts meet on their shared dimensions (BI_MODEL_SPEC.md 8.2)");
            }
            e.put("active", r.active() && !twoFacts ? null : "false");
            m.relationships.add(YamlText.flow(e));
        }
        for (Dataset.Measure x : d.measures().values()) {
            m.measures.put(x.name(), new ModelText.Measure(x.name(), measure(x), x.expr(), null));
        }
        d.hierarchies().forEach((k, v) -> m.hierarchies.put(k, List.copyOf(v)));
        for (Dataset.Rollup r : d.rollups()) {
            m.rollups.add(YamlText.flow(ordered("name", YamlText.scalar(r.name()), "table", r.table(), "by", YamlText.list(r.by()), "measures", YamlText.list(r.measures()))));
        }
        root.path("tests").forEach(t -> m.tests.add(YamlText.node(t)));
        return s;
    }

    private static String role(ModelText m, String alias) {
        String[] d = m.datasets.get(alias);
        return d == null ? "" : d[1];
    }

    static String measure(Dataset.Measure x) {
        Map<String, String> e = new LinkedHashMap<>();
        if (x.python() != null) {
            e.put("python", YamlText.scalar(x.python()));
            e.put("inputs", x.inputs().isEmpty() ? null : YamlText.list(x.inputs()));
            e.put("by", x.by().isEmpty() ? null : YamlText.list(x.by()));
        } else {
            e.put("expr", YamlText.quoted(x.expr()));
        }
        e.put("format", x.format() == null || x.format().isBlank() ? null : YamlText.scalar(x.format()));
        e.put("description", x.description() == null || x.description().isBlank() ? null : YamlText.quoted(x.description()));
        return YamlText.flow(e);
    }

    /** The catalogue keys a model and its datasets carry over: title, description, owner, sample, endorsement, tags, audience. */
    static void catalogue(JsonNode root, Map<String, String> into) {
        for (String k : List.of("title", "description", "owner", "sample", "endorsement", "tags", "audience")) {
            JsonNode v = root.path(k);
            if (!v.isMissingNode() && !v.isNull()) {
                into.put(k, v.isTextual() ? YamlText.quoted(v.asText()) : YamlText.node(v));
            }
        }
    }

    /** The tables a measure aggregates directly: they are the facts (as the compatibility reader decides). */
    static Set<String> facts(Dataset d) {
        Set<String> facts = new LinkedHashSet<>();
        for (Dataset.Measure m : d.measures().values()) {
            if (m.python() != null) {
                m.inputs().forEach(i -> facts.add(i.contains(".") ? i.substring(0, i.indexOf('.')) : i));
                continue;
            }
            try {
                Node.walk(Expressions.parse(m.expr()), n -> {
                    if (n instanceof Node.Call c && Node.AGGREGATES.contains(c.function()) && !c.args().isEmpty() && c.args().get(0) instanceof Node.Ref r) {
                        facts.add(r.parts().get(0));
                    }
                });
            } catch (RuntimeException e) {
                // a measure that does not parse makes no table a fact
            }
        }
        return facts;
    }

    // ---- one dataset ---------------------------------------------------------------------------------------------------

    private static String dataset(Dataset d, Dataset.Table t, JsonNode root, String header, String version, String name, List<Rule> rules, Split s) {
        StringBuilder b = new StringBuilder(header);
        b.append("rachana: 2\nexpressions: 2                  # DEL v2, the Python-like expression language (docs/guides/EXPRESSIONS.md)\n");
        b.append("dataset: ").append(name).append('\n');
        b.append("version: ").append(version).append('\n');
        b.append("title: ").append(YamlText.quoted(text(root, "title", d.name()) + ": " + t.name())).append('\n');
        String desc = t.description() == null || t.description().isBlank() ? "The " + t.name() + " table of the model " + d.name() + "." : t.description();
        b.append("description: ").append(YamlText.quoted(desc)).append('\n');
        Map<String, String> cat = new LinkedHashMap<>();
        catalogue(root, cat);
        for (String k : List.of("owner", "sample", "endorsement", "tags", "audience")) {
            if (cat.containsKey(k)) {
                b.append(k).append(": ").append(cat.get(k)).append('\n');
            }
        }
        Map<String, String> src = new LinkedHashMap<>();
        src.put("connector", t.connector() == null || t.connector().isBlank() ? null : YamlText.scalar(t.connector()));
        src.put("kind", YamlText.scalar(t.kind()));
        src.put("each", t.each() == null || t.each().isBlank() ? null : YamlText.scalar(t.each()));
        b.append("source: ").append(YamlText.flow(src)).append('\n');
        String time = time(t, s);
        if (time != null) {
            b.append("time: ").append(time).append('\n');
        }
        String schedule = t.schedule() != null && !t.schedule().isBlank() ? t.schedule() : d.refresh() != null && d.refresh().scheduled() ? d.refresh().schedule() : null;
        if (schedule != null || t.onLoad()) {
            b.append("refresh: ").append(YamlText.flow(ordered("schedule", schedule == null ? null : YamlText.quoted(schedule), "on-load", t.onLoad() ? "true" : null))).append('\n');
        }
        if (t.shape() != null) {
            s.problems.add("DRS-9190 table '" + t.name() + "' de-duplicates or groups its rows: write it as a dataset with `grain: { one-row-per, keep }` or `group:` by hand");
        }
        b.append("columns:\n");
        int w = t.columns().keySet().stream().mapToInt(String::length).max().orElse(0);
        for (Dataset.Column c : t.columns().values()) {
            if (c.ref() != null) {
                s.problems.add("DRS-9190 column '" + t.name() + "." + c.name() + "' reads another table (" + c.ref() + "): write it as `related()` by hand");
                continue;
            }
            Map<String, String> e = new LinkedHashMap<>();
            e.put("type", c.type());
            e.put("path", c.path() != null && !c.path().equals(c.name()) && c.expr() == null ? YamlText.scalar(c.path()) : null);
            e.put("expr", c.expr() == null ? null : YamlText.quoted(RuleText.qualify(c.expr(), t.name(), t.columns().keySet(), "")));
            e.put("role", c.key() ? "key" : null);
            e.put("format", c.format() == null || c.format().isBlank() ? null : YamlText.scalar(c.format()));
            e.put("description", c.description() == null || c.description().isBlank() ? null : YamlText.quoted(c.description()));
            b.append("  ").append(ModelText.pad(YamlText.key(c.name()) + ":", w + 1)).append(' ').append(YamlText.flow(e)).append('\n');
        }
        List<String> masked = masked(d, t);
        if (!rules.isEmpty() || !masked.isEmpty()) {
            b.append("security:\n");
            if (!rules.isEmpty()) {
                b.append("  rows:\n");
                for (Rule r : rules) {
                    b.append("    - { role: ").append(r.roles().size() == 1 ? YamlText.scalar(r.roles().get(0)) : YamlText.list(r.roles())).append(", where: ")
                            .append(YamlText.quoted(r.where())).append(" }\n");
                }
            }
            if (!masked.isEmpty()) {
                b.append("  masked: ").append(YamlText.list(masked)).append('\n');
            }
        }
        return b.toString();
    }

    private static String time(Dataset.Table t, Split s) {
        if ("direct".equals(t.mode())) {
            return "{ mode: snapshot, storage: direct }";
        }
        if (!t.isLive()) {
            return null;
        }
        Map<String, String> e = new LinkedHashMap<>();
        e.put("mode", "live");
        Dataset.Live l = t.live();
        if (l != null) {
            e.put("live", YamlText.flow(ordered("rows", l.rows() == null ? null : String.valueOf(l.rows()), "age", l.age() == null ? null : span(l.age()))));
            Dataset.History h = l.history();
            if (h != null) {
                e.put("history", YamlText.flow(ordered("every", span(h.every()), "keep", String.valueOf(h.keep()), "by", h.by().isEmpty() ? null : YamlText.list(h.by()),
                        "measures", h.measures().isEmpty() ? null : YamlText.list(h.measures()))));
            }
        }
        return YamlText.flow(e);
    }

    static String span(Duration d) {
        long sec = d.getSeconds();
        if (sec % 3600 == 0 && sec > 0) {
            return sec / 3600 + "h";
        }
        if (sec % 60 == 0 && sec > 0) {
            return sec / 60 + "m";
        }
        return sec + "s";
    }

    /** A table's masked columns: a bare name masks that column in every table that has it, {@code table.column} one table's. */
    static List<String> masked(Dataset d, Dataset.Table t) {
        Set<String> out = new TreeSet<>();
        for (String m : d.security().masked()) {
            if (m.contains(".")) {
                if (m.startsWith(t.name() + ".")) {
                    out.add(m.substring(t.name().length() + 1));
                }
            } else if (t.columns().containsKey(m)) {
                out.add(m);
            }
        }
        return new ArrayList<>(out);
    }

    // ---- row rules -----------------------------------------------------------------------------------------------------

    /** A rule of a converted dataset: the roles, and the condition in the dataset's own terms (bare columns). */
    record Rule(List<String> roles, String where) {}

    private static Map<String, List<Rule>> rules(Dataset d, List<Dataset.Table> tables, Exposure exposure, Split s) {
        Map<String, List<Rule>> out = new LinkedHashMap<>();
        if (d.security().perTable()) {
            s.problems.add("DRS-9190 the dataset's security is written per table: write each dataset's `security.rows` by hand");
            return out;
        }
        List<Dataset.RowRule> rows = d.security().rows();
        if (rows.isEmpty()) {
            return out;
        }
        Set<String> names = new LinkedHashSet<>();
        tables.forEach(t -> names.add(t.name()));
        Map<String, Map<String, List<String>>> bound = new LinkedHashMap<>();      // table -> where -> roles
        Set<String> open = new LinkedHashSet<>();
        Map<String, Set<String>> restrictedOn = new LinkedHashMap<>();            // role -> tables a condition restricts
        List<Dataset.RowRule> everywhere = new ArrayList<>();
        for (Dataset.RowRule r : rows) {
            if (RuleText.everyRow(r.where())) {
                open.add(r.role());
                continue;
            }
            Set<String> named = tablesOf(r.where(), names, tables);
            if (named.size() > 1) {
                s.problems.add("DRS-9190 the row rule for " + r.role() + " (" + r.where() + ") names " + named + ": a rule binds to one dataset; split it by hand");
                continue;
            }
            if (named.isEmpty()) {
                everywhere.add(r);
                continue;
            }
            String t = named.iterator().next();
            Dataset.Table table = d.tables().get(t);
            String where = RuleText.qualify(r.where(), t, table.columns().keySet(), "");
            bound.computeIfAbsent(t, k -> new LinkedHashMap<>()).computeIfAbsent(where, k -> new ArrayList<>()).add(r.role());
            restrictedOn.computeIfAbsent(r.role(), k -> new LinkedHashSet<>()).add(t);
        }
        for (Dataset.Table t : tables) {
            Map<String, List<String>> byWhere = new LinkedHashMap<>(bound.getOrDefault(t.name(), Map.of()));
            for (Dataset.RowRule r : everywhere) {
                byWhere.computeIfAbsent(r.where(), k -> new ArrayList<>()).add(r.role());
            }
            List<String> all = new ArrayList<>(open);
            List<String> noted = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : restrictedOn.entrySet()) {
                String role = e.getKey();
                if (e.getValue().contains(t.name()) || open.contains(role) || everywhere.stream().anyMatch(x -> x.role().equals(role))) {
                    continue;
                }
                if (exposure == Exposure.NOTE) {
                    all.add(role);
                    noted.add(role);
                } else {
                    byWhere.computeIfAbsent("False", k -> new ArrayList<>()).add(role);       // said, not implied: this role sees no rows here
                    s.manual.add("role " + role + " is restricted on " + e.getValue() + " and sees no rows of '" + t.name() + "' (in 2.1 its questions about "
                            + t.name() + " were refused): to let it see every row, add { role: " + role + ", where: \"True\" } to dataset "
                            + datasetName(d.name(), t.name()) + " and may-see-all: { " + t.name() + ": [" + role + "] } to the model");
                }
            }
            if (!all.isEmpty()) {
                byWhere.computeIfAbsent("True", k -> new ArrayList<>()).addAll(all);
            }
            if (!noted.isEmpty()) {
                s.model.maySeeAll.put(t.name(), noted);
                s.notes.add("BI_MODEL_SPEC.md 8.5: " + noted + " restricted on " + restrictedOn.keySet().stream().filter(noted::contains).map(restrictedOn::get).toList()
                        + " now see every row of '" + t.name() + "' (in 2.1 such a question was refused); noted in security.may-see-all");
            }
            List<Rule> list = new ArrayList<>();
            byWhere.forEach((where, roles) -> list.add(new Rule(roles.stream().distinct().toList(), where)));
            out.put(t.name(), list);
        }
        return out;
    }

    /** The tables a rule names: the first part of a dotted name, or the one table that has a bare column. */
    private static Set<String> tablesOf(String where, Set<String> names, List<Dataset.Table> tables) {
        Set<String> out = new LinkedHashSet<>();
        Node n;
        try {
            n = Expressions.parse(where);
        } catch (RuntimeException e) {
            return out;
        }
        Node.walk(n, x -> {
            if (x instanceof Node.Ref r) {
                String first = r.parts().get(0);
                if (names.contains(first)) {
                    out.add(first);
                } else if (r.parts().size() == 1) {
                    tables.stream().filter(t -> t.columns().containsKey(first)).map(Dataset.Table::name).findFirst().ifPresent(out::add);
                }
            }
        });
        return out;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static String text(JsonNode n, String key, String dflt) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? dflt : v.asText();
    }
}
