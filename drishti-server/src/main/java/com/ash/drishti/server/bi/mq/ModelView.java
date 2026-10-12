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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.ExprParser;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.Flattener;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import com.ash.drishti.server.bi.report.Totals;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A model ready to be asked: its definition, the engine dataset it flattens to, the datasets it reads (empty for a version-1 file read through the
 * compatibility reader), the aliases this server cannot read yet, and how its queries run. The run is bound to one reader by {@link MqService}: a version-1
 * file runs exactly as the dataset query runs it (the same code path, so the answers are the same), a model through the engine with security per dataset.
 *
 * @param key the layer key the engine stores the model's columns under
 * @param hash a fingerprint of the model's and its datasets' texts (the result cache keys on it)
 * @param compat true for a version-1 dataset read as a model
 */
public record ModelView(String id, String key, String hash, ModelDef def, Dataset flat, Map<String, ResolvedDataset> datasets, Set<String> unbuilt, boolean compat) {

    /** What the answer says about a measure: additivity ({@code true}, {@code false}, or an over-time rule), the per-row currency column, its format and id. */
    public record MeasureInfo(String additive, String currency, String format, String id, boolean python) {

        public boolean adds() {
            return "true".equals(additive);
        }

        public boolean semiAdditive() {
            return DatasetDef.OVER_TIME.contains(additive);
        }
    }

    /** The model's measures with the defaults, as written (for ids, additivity and currency). */
    public Map<String, ModelDef.Meas> measureDefs() {
        return compat ? def.measures() : Flattener.withDefaults(def, datasets);
    }

    /** The facts of the model: the aliases whose role is fact. */
    public Set<String> facts() {
        Set<String> out = new LinkedHashSet<>();
        def.datasets().values().stream().filter(ModelDef.Use::fact).forEach(u -> out.add(u.alias()));
        return out;
    }

    /** A column's definition in the model, or null (a version-1 file has none). */
    public DatasetDef.Col column(String alias, String column) {
        ResolvedDataset r = datasets.get(alias);
        return r == null ? null : r.def().columns().get(column);
    }

    /** The answer's facts about a measure of {@code ds} (the flattened dataset, possibly with local measures). */
    public MeasureInfo info(Dataset ds, String measure) {
        ModelDef.Meas m = measureDefs().get(measure);
        Dataset.Measure em = ds.measures().get(measure);
        String format = m != null ? m.format() : em == null ? null : em.format();
        String id = m == null || m.id() == null ? "" : m.id();
        boolean python = em != null && em.python() != null;
        String additive = m == null ? null : m.additive();
        String currency = m == null ? null : m.currency();
        if ((additive == null || currency == null) && em != null && em.expr() != null) {
            Node n;
            try {
                n = ExprParser.parse(em.expr());
            } catch (RuntimeException e) {
                n = null;
            }
            if (n instanceof Node.Call c && List.of("sum", "count").contains(c.function()) && !c.args().isEmpty()) {
                if ("count".equals(c.function())) {
                    additive = additive == null ? "true" : additive;
                } else if (c.args().get(0) instanceof Node.Ref r && r.parts().size() == 2) {
                    DatasetDef.Col col = column(r.parts().get(0), r.parts().get(1));
                    if (additive == null) {
                        additive = col == null ? "true" : col.additive();
                    }
                    if (currency == null && col != null && col.unitCurrency() != null) {
                        currency = col.perRowCurrency(datasets.get(r.parts().get(0)).def().columns()) ? r.parts().get(0) + "." + col.unitCurrency() : null;
                    }
                }
            }
            if (additive == null) {
                additive = Totals.adds(ds, measure) ? "true" : "false";
            }
        }
        return new MeasureInfo(additive == null ? "false" : additive, currency, format, id, python);
    }

    /** The tables a measure aggregates (through the measures it names). */
    public static Set<String> tablesOf(Dataset ds, String measure) {
        Set<String> out = new LinkedHashSet<>();
        collect(ds, measure, out, new LinkedHashSet<>());
        return out;
    }

    private static void collect(Dataset ds, String measure, Set<String> out, Set<String> seen) {
        Dataset.Measure m = ds.measures().get(measure);
        if (m == null || !seen.add(measure)) {
            return;
        }
        if (m.expr() == null) {
            m.inputs().forEach(i -> out.add(i.substring(0, Math.max(0, i.indexOf('.')))));
            return;
        }
        Node.walk(ExprParser.parse(m.expr()), n -> {
            if (n instanceof Node.Ref r && ds.tables().containsKey(r.parts().get(0))) {
                out.add(r.parts().get(0));
            } else if (n instanceof Node.MeasureRef x) {
                collect(ds, x.name(), out, seen);
            }
        });
    }

    /** The stable id of a field ({@code ds_x/c_y}), or blank. */
    public String fieldId(String field) {
        String[] p = field.split("\\.");
        if (p.length < 2) {
            return "";
        }
        ResolvedDataset r = datasets.get(p[0]);
        DatasetDef.Col c = r == null ? null : r.def().columns().get(p[1]);
        return c == null || c.id().isBlank() || r.def().id().isBlank() ? "" : r.def().id() + "/" + c.id();
    }

    /** Maps the stable ids of fields ({@code ds_x/c_y}) and measures ({@code m_x}) to names. */
    public Map<String, String> ids() {
        Map<String, String> out = new LinkedHashMap<>();
        datasets.forEach((alias, r) -> r.def().columns().values().forEach(c -> {
            if (!c.id().isBlank() && !r.def().id().isBlank()) {
                out.put(r.def().id() + "/" + c.id(), alias + "." + c.name());
            }
        }));
        measureDefs().values().forEach(m -> {
            if (m.id() != null && !m.id().isBlank()) {
                out.put(m.id(), m.name());
            }
        });
        return out;
    }
}
