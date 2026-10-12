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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 2 of the model query's compilation (docs/architecture/BI_MODEL_SPEC.md section 5.4): a model and its resolved datasets become the engine's in-memory
 * {@link Dataset}, so the existing planner, per-grain splitting, layers, rollups and live tables answer it unchanged. Each alias becomes a table (its source,
 * its time mode as the engine's mode, its columns), each relationship a relationship, the measures (with the default ones) as they are, and the security
 * bound per table: the dataset's layers and the model's rules ANDed, the masks of both, a dimension's rules reaching its facts.
 */
public final class Flattener {

    /** The engine dataset, and the aliases whose source this server cannot read yet (a connector query, a Python or a model source: wave W1 package S4). */
    public record Flat(Dataset dataset, Set<String> unbuilt, List<DatasetParser.Problem> problems) {}

    private Flattener() {}

    public static Flat flatten(ModelDef m, Map<String, ResolvedDataset> datasets) {
        List<DatasetParser.Problem> problems = new ArrayList<>();
        Map<String, Dataset.Table> tables = new LinkedHashMap<>();
        Set<String> unbuilt = new LinkedHashSet<>();
        Map<String, Dataset.TableSecurity> security = new LinkedHashMap<>();
        List<String> masked = new ArrayList<>();
        for (ModelDef.Use use : m.datasets().values()) {
            ResolvedDataset r = datasets.get(use.alias());
            if (r == null) {
                continue;
            }
            DatasetDef d = r.def();
            tables.put(use.alias(), table(use.alias(), d, unbuilt));
            Set<String> cols = d.columns().keySet();
            List<List<Dataset.RowRule>> layers = new ArrayList<>();
            for (List<DatasetDef.Rule> layer : r.layers()) {
                layers.add(rowRules(layer, d.name(), cols, use.alias()));
            }
            List<DatasetDef.Rule> modelRules = m.security().rows().getOrDefault(use.alias(), List.of());
            if (!modelRules.isEmpty()) {
                layers.add(rowRules(modelRules, use.alias(), cols, use.alias()));
            }
            List<String> filters = r.filters().stream().map(f -> RuleText.qualify(f, d.name(), cols, use.alias())).toList();
            security.put(use.alias(), new Dataset.TableSecurity(filters, layers, "dimension".equals(use.role())));
            r.masked().forEach(c -> masked.add(use.alias() + "." + c));
        }
        m.security().masked().forEach(x -> {
            if (!masked.contains(x)) {
                masked.add(x);
            }
        });
        List<Dataset.Relationship> rels = new ArrayList<>();
        for (ModelDef.Rel r : m.relationships()) {
            rels.add(new Dataset.Relationship(r.fromAlias(), r.fromColumn(), r.toAlias(), r.toColumn(), r.cardinality(), r.direction(), r.active()));
        }
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>();
        for (ModelDef.Meas x : withDefaults(m, datasets).values()) {
            measures.put(x.name(), new Dataset.Measure(x.name(), x.expr(), x.format(), x.description(), x.python(), x.inputs(), x.by()));
        }
        Dataset.Security sec = new Dataset.Security(List.of(), masked, security);
        Dataset ds = new Dataset(m.name(), Math.max(1, m.major()), m.title(), m.description(), m.sample(), "duckdb", tables, rels, measures, m.hierarchies(), sec,
                m.rollups(), Dataset.Refresh.NONE, null, null, List.of());
        ds = DatasetParser.withHistories(ds, problems);
        return new Flat(ds, unbuilt, problems);
    }

    private static List<Dataset.RowRule> rowRules(List<DatasetDef.Rule> rules, String name, Set<String> cols, String alias) {
        List<Dataset.RowRule> out = new ArrayList<>();
        for (DatasetDef.Rule r : rules) {
            String where = RuleText.qualify(r.where(), name, cols, alias);
            r.roles().forEach(role -> out.add(new Dataset.RowRule(role, where)));
        }
        return out;
    }

    /** The engine table of a dataset under its alias. */
    static Dataset.Table table(String alias, DatasetDef d, Set<String> unbuilt) {
        DatasetDef.Source s = d.source();
        DatasetDef.Time t = d.time() == null ? DatasetDef.Time.SNAPSHOT : d.time();
        Map<String, Dataset.Column> cols = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(d.grain() == null ? List.of() : d.grain().keys());
        for (DatasetDef.Col c : d.columns().values()) {
            cols.put(c.name(), new Dataset.Column(c.name(), c.expr() == null ? c.pathOrName() : c.name(), c.type(), c.format(), "key".equals(c.role()) || keys.contains(c.name()),
                    c.expr(), c.description()));
        }
        boolean pack = s != null && DatasetDef.Source.PACK.equals(s.type());
        if (!pack) {
            unbuilt.add(alias);
        }
        String mode = switch (t.mode()) {
            case "live" -> "live";
            default -> t.direct() ? "direct" : "cached";
        };
        Dataset.Live live = null;
        if ("live".equals(mode)) {
            DatasetDef.History h = t.history();
            live = new Dataset.Live(t.liveRows(), t.liveAge(), h == null ? null : new Dataset.History("history", h.every(), h.keep(), h.by(), h.measures()));
        }
        return new Dataset.Table(alias, pack ? s.connector() : "", pack ? s.kind() : "", pack ? s.each() : null, mode, d.refresh().onLoad(), d.refresh().schedule(),
                d.description(), cols, live);
    }

    // ---- default measures --------------------------------------------------------------------------------------------

    /**
     * The model's measures and its default ones (section 2.4): for every fact alias, one per column whose {@code aggregate} is set ({@code Sum of notional}) and
     * {@code Count of <alias>}, marked implicit. A name the model already has is not shadowed; a second fact's clash gets the alias in brackets.
     */
    public static Map<String, ModelDef.Meas> withDefaults(ModelDef m, Map<String, ResolvedDataset> datasets) {
        Map<String, ModelDef.Meas> out = new LinkedHashMap<>(m.measures());
        for (ModelDef.Use use : m.datasets().values()) {
            ResolvedDataset r = datasets.get(use.alias());
            if (r == null || !use.fact()) {
                continue;
            }
            add(out, "Count of " + use.alias(), "count(" + use.alias() + ")", use.alias(), "true", INTEGER, null);
            for (DatasetDef.Col c : r.def().columns().values()) {
                if (c.aggregate() == null) {
                    continue;
                }
                String name = switch (c.aggregate()) {
                    case "sum" -> "Sum of " + c.name();
                    case "avg" -> "Average of " + c.name();
                    case "min" -> "Minimum of " + c.name();
                    case "max" -> "Maximum of " + c.name();
                    case "count" -> "Count of " + c.name();
                    default -> "Distinct " + c.name();
                };
                String additive = "sum".equals(c.aggregate()) || "count".equals(c.aggregate()) ? c.additive() : "false";
                String currency = c.perRowCurrency(r.def().columns()) ? use.alias() + "." + c.unitCurrency() : null;          // a fixed currency always adds
                add(out, name, c.aggregate() + "(" + use.alias() + "." + c.name() + ")", use.alias(), additive, defaultFormat(c), "sum".equals(c.aggregate()) ? currency : null);
            }
        }
        return out;
    }

    /** The format a default measure takes when its column names none (section 2.4): counts are whole numbers, amounts show two decimals. */
    static final String INTEGER = "integer";
    static final String AMOUNT = "amount2";

    static String defaultFormat(DatasetDef.Col c) {
        if (c.format() != null && !c.format().isBlank()) {
            return c.format();
        }
        if ("count".equals(c.aggregate()) || "distinct".equals(c.aggregate())) {
            return INTEGER;
        }
        if ("amount".equals(c.role())) {
            return AMOUNT;
        }
        return "quantity".equals(c.role()) && "integer".equals(c.type()) ? INTEGER : null;
    }

    private static void add(Map<String, ModelDef.Meas> out, String name, String expr, String alias, String additive, String format, String currency) {
        String n = out.containsKey(name) ? name + " (" + alias + ")" : name;
        if (out.containsKey(n)) {
            return;
        }
        String id = "m_" + n.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        out.put(n, new ModelDef.Meas(n, id, expr, format, "", currency, additive, null, null, null, null, true));
    }
}
