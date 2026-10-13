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
import com.ash.drishti.server.bi.dataset.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The wave-W1 compatibility reader (docs/architecture/BI_MODEL_SPEC.md section 8.4): a {@code rachana: 1} dataset is read as a model, so reports, alerts
 * and Analyse can ask it through model queries before wave W4 converts the files. Its tables are the model's datasets under the same names (so
 * {@code trades.desk} keeps its meaning), a table that measures aggregate is a fact and the others dimensions, and the measures, hierarchies and relationships
 * are as written. A composed or derived dataset is an implicit model of its one table.
 *
 * <p>Security is <b>not</b> split per dataset here: the old file's rules keep their version-1 meaning (joined into every query, refusing what they cannot
 * reach), so an old file answers exactly as before through either path. Binding the rules per dataset changes answers (section 8.5) and, by the security
 * safeguard, needs a reviewed model; that happens when the converter (wave W4) writes the model. The engine dataset of a compatibility model is therefore the
 * parsed dataset itself. Deleted in W4.
 */
public final class CompatReader {

    private CompatReader() {}

    /** The model view of a version-1 dataset: names, roles, measures, relationships and the contract (everything). */
    public static ModelDef read(Dataset d, String id) {
        Set<String> facts = new LinkedHashSet<>();
        for (Dataset.Measure m : d.measures().values()) {
            if (m.expr() == null) {
                continue;
            }
            try {
                Node.walk(com.ash.drishti.server.bi.expr.Expressions.parse(m.expr()), n -> {
                    if (n instanceof Node.Call c && Node.AGGREGATES.contains(c.function()) && !c.args().isEmpty() && c.args().get(0) instanceof Node.Ref r) {
                        facts.add(r.parts().get(0));
                    }
                });
            } catch (RuntimeException e) {
                // a measure that does not parse makes no table a fact
            }
        }
        Map<String, ModelDef.Use> uses = new LinkedHashMap<>();
        for (Dataset.Table t : d.tables().values()) {
            uses.put(t.name(), new ModelDef.Use(t.name(), t.kind(), Semver.Range.ANY, facts.contains(t.name()) || d.tables().size() == 1 ? "fact" : "dimension"));
        }
        List<ModelDef.Rel> rels = new ArrayList<>();
        int i = 0;
        for (Dataset.Relationship r : d.relationships()) {
            rels.add(new ModelDef.Rel("r_" + (++i), r.fromTable(), r.fromColumn(), r.toTable(), r.toColumn(), r.cardinality(), r.direction(), r.active(), "", null));
        }
        Map<String, ModelDef.Meas> measures = new LinkedHashMap<>();
        for (Dataset.Measure m : d.measures().values()) {
            measures.put(m.name(), new ModelDef.Meas(m.name(), "", m.expr(), m.format(), m.description(), null, null, m.python(), m.inputs(), m.by(), null, false));
        }
        String version = Math.max(1, d.version()) + ".0.0";
        return new ModelDef(d.name(), version, id, d.title(), d.description(), "", d.sample(), "none", uses, rels, measures, d.hierarchies(), ModelDef.Security.NONE,
                ModelDef.Contract.ALL, "published", null, d.rollups(), List.of(), List.of(), null, d.framed() ? d.name() : null, List.of());
    }
}
