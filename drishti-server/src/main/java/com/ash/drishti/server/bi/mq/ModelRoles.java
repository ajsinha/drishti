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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The identity roles as the model layer sees them: who may read a model's datasets, for the role picker of the security tab and for Check's warning
 * {@code DRS-9131} (docs/architecture/BI_MODEL_SPEC.md section 2.6). A row rule names the roles that see rows; every other role that may read the dataset sees
 * none of its rows, silently, so Check names those roles.
 */
public final class ModelRoles {

    /** A role: its name, what it is for, and which pack kinds it may open. */
    public record Role(String name, String description, Predicate<String> opens) {}

    private volatile Supplier<List<Role>> source = List::of;

    /** Where the roles come from (the identity's role catalogue; the server wires it, a test may give its own). */
    public void source(Supplier<List<Role>> roles) {
        this.source = roles == null ? List::of : roles;
    }

    /** Every role that may use BI, by name. */
    public List<Role> all() {
        return List.copyOf(source.get());
    }

    /** The roles that may read a dataset: those that open its pack kind (a dataset not read from a pack: every role). */
    public List<String> readers(ResolvedDataset d) {
        String kind = d.def().source() != null && DatasetDef.Source.PACK.equals(d.def().source().type()) ? d.def().source().kind() : null;
        List<String> out = new ArrayList<>();
        for (Role r : all()) {
            if (kind == null || r.opens().test(kind)) {
                out.add(r.name());
            }
        }
        return out;
    }

    /**
     * Per dataset of the model with row rules (its own or the model's), the readers no rule names: they see no row of it. Each layer of rules must name a role
     * for that role to see rows, so a role missing from any layer is listed.
     */
    public Map<String, List<String>> uncovered(ModelDef m, Map<String, ResolvedDataset> datasets) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, ResolvedDataset> e : datasets.entrySet()) {
            List<List<DatasetDef.Rule>> layers = new ArrayList<>(e.getValue().layers());
            List<DatasetDef.Rule> own = m.security().rows().getOrDefault(e.getKey(), List.of());
            if (!own.isEmpty()) {
                layers.add(own);
            }
            layers.removeIf(List::isEmpty);
            if (layers.isEmpty()) {
                continue;
            }
            Set<String> missing = new LinkedHashSet<>();
            for (String role : readers(e.getValue())) {
                if (layers.stream().anyMatch(l -> l.stream().noneMatch(r -> r.roles().contains(role)))) {
                    missing.add(role);
                }
            }
            if (!missing.isEmpty()) {
                out.put(e.getKey(), List.copyOf(missing));
            }
        }
        return out;
    }

    /** Check's warnings ({@code DRS-9131}): a warning, never a reason to refuse a publish (leaving a role out may be the point). */
    public List<DatasetParser.Problem> warnings(ModelDef m, Map<String, ResolvedDataset> datasets) {
        List<DatasetParser.Problem> out = new ArrayList<>();
        uncovered(m, datasets).forEach((alias, roles) -> out.add(new DatasetParser.Problem("warning", "DRS-9131", "security.rows." + alias,
                "roles without a rule see no rows of '" + alias + "': " + String.join(", ", roles) + ". Add a rule for them (where: \"True\" lets a role see "
                        + "every row), or leave them out on purpose: they get \"you have no access to rows of " + alias + " in this model\"")));
        return out;
    }
}
