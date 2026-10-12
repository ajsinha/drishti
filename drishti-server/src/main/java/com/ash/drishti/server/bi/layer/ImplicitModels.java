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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The implicit model of a consumer that names one dataset (docs/architecture/BI_MODEL_SPEC.md section 3): made when it is read, never stored, id
 * {@code implicit:<dataset id>@<major>}. The dataset is the one fact under its default alias with the default measures; for a pack-generated dataset every column
 * that {@code references} a kind with a generated dataset brings that dataset along as a dimension, one hop, with an active many-to-one relationship to its
 * {@code id}. Security is the datasets' own; the contract is everything; the version is the dataset's.
 */
public final class ImplicitModels {

    /** The prefix of an implicit model's id and of the reference a query names one by ({@code dataset:pack.trading.trade}). */
    public static final String ID_PREFIX = "implicit:";
    public static final String QUERY_PREFIX = "dataset:";

    /** An implicit model and the datasets it reads under their aliases. */
    public record Implicit(ModelDef model, Map<String, ResolvedDataset> datasets) {}

    private ImplicitModels() {}

    /**
     * The implicit model of {@code dataset}.
     *
     * @param packDatasetOfKind the generated dataset of a kind ({@code desk} gives {@code pack.banking-core.desk}), or empty; only consulted for a generated dataset
     */
    public static Implicit of(ResolvedDataset dataset, Function<String, Optional<ResolvedDataset>> packDatasetOfKind) {
        DatasetDef d = dataset.def();
        String alias = d.defaultAlias();
        Map<String, ModelDef.Use> uses = new LinkedHashMap<>();
        Map<String, ResolvedDataset> resolved = new LinkedHashMap<>();
        uses.put(alias, new ModelDef.Use(alias, d.name(), Semver.Range.ANY, "fact"));
        resolved.put(alias, dataset);
        List<ModelDef.Rel> rels = new ArrayList<>();
        boolean generated = d.isGenerated() || dataset.chain().stream().anyMatch(n -> n.startsWith(DatasetDef.PACK_PREFIX));
        if (generated) {
            for (DatasetDef.Col c : d.columns().values()) {
                if (c.references() == null || c.expr() != null) {
                    continue;
                }
                Optional<ResolvedDataset> dim = packDatasetOfKind.apply(c.references());
                if (dim.isEmpty() || dim.get().def().name().equals(d.name())) {
                    continue;
                }
                String dimAlias = dim.get().def().defaultAlias();
                if (uses.containsKey(dimAlias)) {
                    dimAlias = alias + "_" + c.name();
                    if (uses.containsKey(dimAlias)) {
                        continue;
                    }
                }
                uses.put(dimAlias, new ModelDef.Use(dimAlias, dim.get().def().name(), Semver.Range.ANY, "dimension"));
                resolved.put(dimAlias, dim.get());
                rels.add(new ModelDef.Rel("r_" + c.name(), alias, c.name(), dimAlias, "id", "many-to-one", "single", true, "", "pack-reference"));
            }
        }
        String id = ID_PREFIX + (d.id().isBlank() ? d.name() : d.id()) + "@" + d.major();
        ModelDef m = new ModelDef(d.name(), d.version(), id, "Implicit model of " + d.title(), d.description(), d.owner(), d.sample(), d.endorsement(), uses, rels,
                Map.of(), Map.of(), ModelDef.Security.NONE, ModelDef.Contract.ALL, "published", null, List.of(), List.of(), List.of(), null, d.name(), List.of());
        return new Implicit(m, resolved);
    }
}
