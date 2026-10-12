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
        masked = Set.copyOf(masked);
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
        DatasetDef merged = new DatasetDef(narrow.name(), narrow.version(), narrow.id(), narrow.title(), narrow.description().isBlank() ? b.description() : narrow.description(),
                narrow.owner(), narrow.sample(), narrow.endorsement(), null, b.source(), b.grain(), cols, b.time(), b.refresh(), narrow.security(), narrow.extendsRef(),
                narrow.where(), narrow.ai(), narrow.tests(), narrow.rollups());
        List<String> chain = new ArrayList<>(List.of(narrow.name()));
        chain.addAll(base.chain());
        return new ResolvedDataset(merged, layers, filters, masked, chain);
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
