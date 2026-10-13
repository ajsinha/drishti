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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The checks of a model against its resolved datasets (docs/architecture/BI_MODEL_SPEC.md section 2.11): {@code DRS-9121} an island (a warning),
 * {@code 9122} a relationship naming no field or fields of different types, {@code 9123} a relationship whose one side is not unique (it would repeat rows),
 * {@code 9124} two active paths between two datasets, {@code 9125} a model mask naming no column, {@code 9127} a measure that does not compile (today's
 * {@code 9011} to {@code 9013} conditions, with the model's path), {@code 9128} a contract field the model lacks, and the security safeguard {@code 9130}.
 *
 * <p><b>The safeguard</b> (the product owner's condition on security per dataset, section 2.6): a role is <em>restricted</em> in a model when a row rule of
 * the model, or of one of its datasets, names it. Every dataset of the model that no row rule restricts at all is open to such a role, which then sees every one
 * of its rows (with rules bound per dataset, a rule on trades no longer hides netting sets). Check flags each such dataset and role as a warning; the model
 * cannot be published until the dataset gets a rule (in the dataset or the model) or the model says {@code may-see-all: { <alias>: [<role>] }}, which the
 * publish records in the audit log.
 */
public final class ModelChecker {

    /** A dataset that a restricted role would see in full, and that the model has not said it may. */
    public record Exposure(String alias, String role) {}

    private ModelChecker() {}

    public static List<DatasetParser.Problem> check(ModelDef m, Map<String, ResolvedDataset> datasets) {
        return check(m, datasets, kind -> java.util.Optional.empty());
    }

    /** As {@link #check(ModelDef, Map)}, with the pack kinds {@code related()} columns read. */
    public static List<DatasetParser.Problem> check(ModelDef m, Map<String, ResolvedDataset> datasets, java.util.function.Function<String, java.util.Optional<DatasetDef>> kinds) {
        LayerYaml y = new LayerYaml();
        for (Dataset.Rollup r : m.rollups()) {
            ResolvedDataset d = datasets.get(r.table());
            if (d != null && (d.def().derivation().shaped() || !d.def().derivation().python().isEmpty())) {
                y.error("DRS-9120", "rollups." + r.name(), "'" + r.table() + "' is de-duplicated, grouped or has Python columns: a rollup of it arrives in wave B");
            }
        }
        relationships(m, datasets, y);
        islands(m, y);
        ambiguity(m, y);
        for (String mask : m.security().masked()) {
            String[] f = ModelDefParser.field(mask);
            ResolvedDataset r = f == null ? null : datasets.get(f[0]);
            if (r != null && !r.def().columns().containsKey(f[1])) {
                y.error("DRS-9125", "security.masked", "the mask names '" + mask + "', which is not a column of " + r.def().name());
            }
        }
        for (Map.Entry<String, List<DatasetDef.Rule>> e : m.security().rows().entrySet()) {
            ResolvedDataset r = datasets.get(e.getKey());
            if (r == null) {
                continue;
            }
            for (int i = 0; i < e.getValue().size(); i++) {
                com.ash.drishti.server.bi.dataset.Node n = DatasetDefParser.expression(e.getValue().get(i).where(), "security.rows." + e.getKey() + "[" + i + "].where", "DRS-9125", y);
                if (n != null) {
                    DatasetDefParser.ownColumnsOnly(n, e.getKey(), r.def().columns(), false, "security.rows." + e.getKey() + "[" + i + "].where", "DRS-9125", y);
                }
            }
        }
        if (m.contract().listed()) {
            for (String f : m.contract().fields()) {
                String[] p = f.split("\\.");
                ResolvedDataset r = p.length >= 2 ? datasets.get(p[0]) : null;
                if (r != null && !r.def().columns().containsKey(p[1]) && !DatasetDef.BUILT_IN.contains(p[1])) {
                    y.error("DRS-9128", "contract.fields", "the contract names '" + f + "', which is not a column of " + r.def().name());
                }
            }
        }
        for (Exposure x : exposures(m, datasets)) {
            y.warning("DRS-9130", "security", "the role '" + x.role() + "' is restricted elsewhere in this model, but no row rule restricts '" + x.alias() + "' ("
                    + datasets.get(x.alias()).def().name() + "): that role sees every one of its rows. Add a rule for it on '" + x.alias() + "', or say so with "
                    + "`may-see-all: { " + x.alias() + ": [" + x.role() + "] }` under security; until then the model cannot be published");
        }
        if (y.problems().stream().noneMatch(DatasetParser.Problem::error)) {
            Flattener.Flat flat = Flattener.flatten(m, datasets, kinds);
            flat.problems().forEach(p -> y.problems().add(p));
            for (DatasetParser.Problem p : DatasetParser.check(flat.dataset())) {
                y.problems().add(new DatasetParser.Problem(p.level(), modelCode(p), p.path(), p.message()));
            }
        }
        return y.problems();
    }

    /** The engine's checks of the flattened model, in the model's codes: measures 9127, relationships 9122, the rest 9120. */
    private static String modelCode(DatasetParser.Problem p) {
        if (p.path().startsWith("measures")) {
            return "DRS-9127";
        }
        return p.path().startsWith("relationships") ? "DRS-9122" : "DRS-9120";
    }

    /**
     * The safeguard's findings: each dataset with no row rule at all (in itself or in the model) and each role restricted elsewhere in the model, unless the
     * model's {@code may-see-all} names that role for that dataset.
     */
    public static List<Exposure> exposures(ModelDef m, Map<String, ResolvedDataset> datasets) {
        Set<String> restricted = new LinkedHashSet<>();
        m.security().rows().values().forEach(rules -> rules.forEach(r -> {
            if (!RuleText.everyRow(r.where())) {
                restricted.addAll(r.roles());
            }
        }));
        for (ResolvedDataset r : datasets.values()) {
            r.layers().forEach(l -> l.forEach(rule -> {
                if (!RuleText.everyRow(rule.where())) {
                    restricted.addAll(rule.roles());
                }
            }));
        }
        List<Exposure> out = new ArrayList<>();
        for (Map.Entry<String, ResolvedDataset> e : datasets.entrySet()) {
            boolean open = e.getValue().layers().stream().allMatch(List::isEmpty) && m.security().rows().getOrDefault(e.getKey(), List.of()).isEmpty();
            if (!open) {
                continue;
            }
            List<String> allowed = m.security().maySeeAll().getOrDefault(e.getKey(), List.of());
            for (String role : restricted) {
                if (!allowed.contains(role)) {
                    out.add(new Exposure(e.getKey(), role));
                }
            }
        }
        return out;
    }

    private static void relationships(ModelDef m, Map<String, ResolvedDataset> datasets, LayerYaml y) {
        for (int i = 0; i < m.relationships().size(); i++) {
            ModelDef.Rel r = m.relationships().get(i);
            String path = "relationships[" + i + "]";
            ResolvedDataset from = datasets.get(r.fromAlias());
            ResolvedDataset to = datasets.get(r.toAlias());
            if (from == null || to == null) {
                continue;
            }
            String ft = typeOf(from.def(), r.fromColumn());
            String tt = typeOf(to.def(), r.toColumn());
            if (ft == null || tt == null) {
                y.error("DRS-9122", path, (ft == null ? r.fromAlias() + "." + r.fromColumn() : r.toAlias() + "." + r.toColumn()) + " is not a column of the model");
                continue;
            }
            if (!ft.equals(tt)) {
                y.error("DRS-9122", path, r.label() + " joins a " + ft + " to a " + tt + ": both sides of a relationship have one type");
            }
            if (!unique(to.def(), r.toColumn())) {
                y.error("DRS-9123", path, r.toAlias() + "." + r.toColumn() + " is not the grain key of " + to.def().name() + " (keys " + to.def().grain().keys()
                        + "), so one row of " + r.fromAlias() + " could meet several: join to the key, or make the key part of the grain");
            }
        }
    }

    private static String typeOf(DatasetDef d, String column) {
        if (DatasetDef.BUILT_IN.contains(column)) {
            return switch (column) {
                case "businessDate" -> "date";
                case "index", "updateSeq" -> "integer";
                case "updatedAt" -> "timestamp";
                default -> "string";
            };
        }
        DatasetDef.Col c = d.columns().get(column);
        return c == null ? null : c.type();
    }

    /** A column one row of the dataset per value of: the entity id of a pack kind (not over an array), or the one key of the grain. */
    static boolean unique(DatasetDef d, String column) {
        boolean pack = d.source() != null && DatasetDef.Source.PACK.equals(d.source().type()) && d.source().each() == null;
        if ("id".equals(column) && pack) {
            return true;
        }
        List<String> keys = d.grain() == null ? List.of() : d.grain().keys();
        return keys.size() == 1 && keys.get(0).equals(column);
    }

    private static void islands(ModelDef m, LayerYaml y) {
        if (m.datasets().size() < 2) {
            return;
        }
        Set<String> linked = new LinkedHashSet<>();
        m.relationships().forEach(r -> {
            linked.add(r.fromAlias());
            linked.add(r.toAlias());
        });
        m.datasets().keySet().stream().filter(a -> !linked.contains(a)).forEach(a -> y.warning("DRS-9121", "datasets." + a,
                "'" + a + "' has no relationship to the other datasets: it can be asked alone, never with their fields"));
    }

    /** Two active paths between the same two datasets make a query's join ambiguous: the second relationship that closes a loop is the problem. */
    private static void ambiguity(ModelDef m, LayerYaml y) {
        Map<String, String> parent = new HashMap<>();
        for (int i = 0; i < m.relationships().size(); i++) {
            ModelDef.Rel r = m.relationships().get(i);
            if (!r.active()) {
                continue;
            }
            String a = root(parent, r.fromAlias());
            String b = root(parent, r.toAlias());
            if (a.equals(b)) {
                y.error("DRS-9124", "relationships[" + i + "]", r.label() + " is a second active path between datasets already joined: make one of the paths inactive");
            } else {
                parent.put(a, b);
            }
        }
    }

    private static String root(Map<String, String> parent, String x) {
        String r = x;
        while (parent.containsKey(r)) {
            r = parent.get(r);
        }
        return r;
    }

    /** The stable ids a model and its measures, relationships and hierarchies carry (for the breaking-change rules). */
    public static Map<String, String> ids(ModelDef m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.measures().values().forEach(x -> {
            if (!x.id().isBlank()) {
                out.put(x.id(), "measure " + x.name());
            }
        });
        m.relationships().forEach(r -> {
            if (!r.id().isBlank()) {
                out.put(r.id(), "relationship " + r.label() + (r.active() ? "" : " (inactive)"));
            }
        });
        return out;
    }
}
