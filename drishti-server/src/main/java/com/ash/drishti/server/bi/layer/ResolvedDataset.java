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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A dataset as a model reads it, its {@code extends} chain folded in: the base's source, grain and time, the columns of the base and the additions, and the
 * security of every link of the chain, each kept as its own layer (a narrowing dataset only adds: its rules are ANDed with the base's, its masks added). Each
 * rule layer and filter names the columns by their dataset's own name; {@link Flattener} rewrites them for the alias.
 *
 * @param def the dataset with the chain's columns (the narrowest dataset's name, version and id)
 * @param layers each link's row rules, base first; a link without rules adds no layer
 * @param filters the {@code where:} of each narrowing link
 * @param masked every link's masked columns
 * @param chain the names from this dataset to the root
 */
public record ResolvedDataset(DatasetDef def, List<List<DatasetDef.Rule>> layers, List<String> filters, Set<String> masked, List<String> chain) {

    public ResolvedDataset {
        layers = layers.stream().map(List::copyOf).toList();
        filters = List.copyOf(filters);
        masked = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(masked));   // a stable order (BIR-07): Set.copyOf iterates in a per-JVM salted order
        chain = List.copyOf(chain);
    }

    /** A dataset that extends nothing. */
    public static ResolvedDataset of(DatasetDef def) {
        return new ResolvedDataset(def, def.security().rows().isEmpty() ? List.of() : List.of(def.security().rows()), List.of(), new LinkedHashSet<>(def.security().masked()),
                List.of(def.name()));
    }

    /**
     * {@code narrow} over its resolved base: the base's source, grain and time; the base's columns, then the additions (a column of the same name overrides
     * title, format and meaning, never the type); every layer of both. Rules and filters are rewritten here to the narrowest name, so they all read the same table.
     */
    public static ResolvedDataset extend(DatasetDef narrow, ResolvedDataset base) {
        DatasetDef b = base.def();
        Map<String, DatasetDef.Col> cols = new LinkedHashMap<>(b.columns());
        List<String> subset = narrow.derivation().subset();
        if (!subset.isEmpty()) {                                                // columns: [a, b]: the base's columns kept (its keys always: Check refuses dropping one)
            cols.keySet().removeIf(k -> !subset.contains(k) && !b.grain().keys().contains(k));
        }
        narrow.columns().forEach((k, c) -> {
            DatasetDef.Col was = cols.get(k);
            cols.put(k, was == null ? c : new DatasetDef.Col(k, was.type(), was.path(), was.expr(), c.role() == null ? was.role() : c.role(),
                    c.meaning() == null ? was.meaning() : c.meaning(), was.references(), was.of(), was.unitCurrency(), was.unitOf(), was.additive(), was.aggregate(),
                    c.format() == null ? was.format() : c.format(), was.id(), c.description().isBlank() ? was.description() : c.description()));
        });
        Set<String> names = cols.keySet();
        List<List<DatasetDef.Rule>> layers = new ArrayList<>();
        for (List<DatasetDef.Rule> layer : base.layers()) {
            layers.add(layer.stream().map(r -> new DatasetDef.Rule(r.roles(), RuleText.qualify(r.where(), b.name(), names, ""))).toList());   // bare columns: any name reads them
        }
        if (!narrow.security().rows().isEmpty()) {
            layers.add(narrow.security().rows());
        }
        List<String> filters = new ArrayList<>();
        base.filters().forEach(f -> filters.add(RuleText.qualify(f, b.name(), names, "")));
        if (narrow.where() != null) {
            filters.add(narrow.where());
        }
        Set<String> masked = new LinkedHashSet<>(base.masked());
        masked.addAll(narrow.security().masked());
        for (DatasetDef.Col c : narrow.columns().values()) {                    // a column derived from a masked one is masked: masks come before any arithmetic
            if (c.expr() != null && readsAny(c.expr(), masked)) {
                masked.add(c.name());
            }
        }
        DatasetDef.Derivation nd = narrow.derivation();
        Map<String, DatasetDef.PyColumn> python = new LinkedHashMap<>(b.derivation().python());
        python.putAll(nd.python());
        DatasetDef.Derivation derivation = new DatasetDef.Derivation(nd.subset(), nd.keep() != null ? nd.keep() : b.derivation().keep(), python,
                nd.group() != null ? nd.group() : b.derivation().group());
        DatasetDef.Grain grain = nd.keep() != null || nd.group() != null ? new DatasetDef.Grain(narrow.grain().says(), narrow.grain().keys(), b.grain().date()) : b.grain();
        Map<String, DatasetDef.Col> visible = cols;
        if (nd.group() != null) {                                               // the columns are the group's; the ones before stay as the derivation's source
            visible = grouped(nd.group(), cols);
            derivation = new DatasetDef.Derivation(derivation.subset(), derivation.keep(), derivation.python(), derivation.group(), cols);
        }
        Set<String> exists = new LinkedHashSet<>(visible.keySet());
        exists.addAll(DatasetDef.BUILT_IN);
        masked.retainAll(exists);                                               // a dropped or grouped-away column has no mask left to apply
        DatasetDef merged = new DatasetDef(narrow.name(), narrow.version(), narrow.id(), narrow.title(), narrow.description().isBlank() ? b.description() : narrow.description(),
                narrow.owner(), narrow.sample(), narrow.endorsement(), null, b.source(), grain, visible, b.time(), b.refresh(), narrow.security(), narrow.extendsRef(),
                narrow.where(), narrow.ai(), narrow.tests(), narrow.rollups(), derivation);
        List<String> chain = new ArrayList<>(List.of(narrow.name()));
        chain.addAll(base.chain());
        return new ResolvedDataset(merged, layers, filters, masked, chain);
    }

    /**
     * The columns of a grouped dataset: the group-by columns as they were, then each grouped column: {@code sum} and {@code count} add up across groups (a sum
     * by default), {@code min} and {@code max} keep their aggregate, {@code avg} and {@code distinct} do not add (no default aggregate).
     */
    static Map<String, DatasetDef.Col> grouped(DatasetDef.Group g, Map<String, DatasetDef.Col> source) {
        Map<String, DatasetDef.Col> out = new LinkedHashMap<>();
        for (String by : g.by()) {
            DatasetDef.Col c = source.get(by);
            if (c != null) {
                out.put(by, new DatasetDef.Col(by, c.type(), null, null, "key".equals(c.role()) || "dimension".equals(c.role()) ? c.role() : "dimension", c.meaning(), c.references(),
                        null, c.unitCurrency(), c.unitOf(), "false", null, c.format(), c.id(), c.description()));
            }
        }
        g.columns().forEach((name, agg) -> {
            DatasetDef.Col c = source.get(name);
            String type = switch (agg) {
                case "count", "distinct" -> "integer";
                case "sum" -> c != null && "integer".equals(c.type()) ? "integer" : "decimal";
                case "avg" -> "decimal";
                default -> c == null ? "decimal" : c.type();
            };
            String role = "count".equals(agg) || "distinct".equals(agg) ? "quantity" : c == null ? "amount" : c.role();
            String additive = "sum".equals(agg) || "count".equals(agg) ? (c == null ? "true" : c.semiAdditive() ? c.additive() : "true") : "false";
            String aggregate = switch (agg) {
                case "sum", "count" -> "sum";
                case "min", "max" -> agg;
                default -> null;
            };
            out.put(name, new DatasetDef.Col(name, type, null, null, role, c == null ? null : c.meaning(), null, null, c == null || "count".equals(agg) ? null : c.unitCurrency(),
                    c == null ? null : c.unitOf(), additive, aggregate, c == null ? null : c.format(), c == null ? "" : c.id(), c == null ? agg + " of rows" : agg + " of " + c.description()));
        });
        return out;
    }

    /** True when the expression names one of the columns (bare, or after a dataset or alias name). */
    static boolean readsAny(String expr, Set<String> columns) {
        try {
            return com.ash.drishti.server.bi.dataset.Node.anyMatch(com.ash.drishti.server.bi.expr.Expressions.parse(expr), x -> x instanceof com.ash.drishti.server.bi.dataset.Node.Ref r
                    && columns.contains(r.parts().get(r.parts().size() - 1)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Every role a rule of the chain names. */
    public Set<String> ruleRoles() {
        Set<String> out = new LinkedHashSet<>();
        layers.forEach(l -> l.forEach(r -> out.addAll(r.roles())));
        return out;
    }

    /** True when no row rule and no filter restricts the dataset. */
    public boolean open() {
        return layers.stream().allMatch(List::isEmpty) && filters.isEmpty();
    }
}
