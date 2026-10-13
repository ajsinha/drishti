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

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Check of a narrowing dataset's derivation against its base (docs/architecture/BI_DESIGN.md section 3.1): a column subset keeps the base's keys
 * ({@code DRS-9105}); the de-duplication's key and order, the grouping's columns and a Python column's inputs exist ({@code DRS-9104}, {@code DRS-9101}); a
 * dataset that is already de-duplicated or grouped is not reshaped again (wave B); {@code related(column.field)} follows a column that references a pack kind
 * to a field that kind has ({@code DRS-9101}). And the lineage of a dataset down to its root, whose break is {@code DRS-9110}.
 */
public final class Derivations {

    private Derivations() {}

    /** The problems of {@code d}'s derivation over its resolved base {@code b}. */
    public static List<DatasetParser.Problem> check(DatasetDef d, DatasetDef b, java.util.function.Function<String, Optional<DatasetDef>> kinds) {
        List<DatasetParser.Problem> out = new ArrayList<>();
        DatasetDef.Derivation dv = d.derivation();
        Map<String, DatasetDef.Col> all = new LinkedHashMap<>(b.columns());
        all.putAll(d.columns());
        for (String s : dv.subset()) {
            if (!b.columns().containsKey(s) && !DatasetDef.BUILT_IN.contains(s)) {
                out.add(error("DRS-9105", "columns", "'" + s + "' is not a column of the base " + b.name() + " (it has " + b.columns().keySet() + ")"));
            }
        }
        if (!dv.subset().isEmpty()) {
            for (String k : b.grain().keys()) {
                if (!dv.subset().contains(k) && !DatasetDef.BUILT_IN.contains(k)) {
                    out.add(error("DRS-9105", "columns", "the base's key '" + k + "' cannot be dropped: add it to the list"));
                }
            }
        }
        if ((dv.keep() != null || dv.group() != null || !dv.python().isEmpty()) && (b.derivation().keep() != null || b.derivation().group() != null)) {
            out.add(error("DRS-9105", "extends", "'" + b.name() + "' is de-duplicated or grouped already: reshaping it again arrives in wave B; extend its own base"));
        }
        java.util.Set<String> masked = new java.util.LinkedHashSet<>(b.security().masked());
        masked.addAll(d.security().masked());
        if (dv.keep() != null && masked.contains(dv.keep().by())) {
            out.add(error("DRS-9106", "grain.keep", "'" + dv.keep().by() + "' is masked: the rows kept cannot be chosen by a masked value"));
        }
        if (dv.group() != null) {
            dv.group().by().stream().filter(masked::contains).forEach(g -> out.add(error("DRS-9106", "group.by", "'" + g + "' is masked: a masked column is never grouped by")));
        }
        if (dv.keep() != null) {
            for (String k : dv.keep().keys()) {
                if (!all.containsKey(k) && !DatasetDef.BUILT_IN.contains(k)) {
                    out.add(error("DRS-9104", "grain.one-row-per", "'" + k + "' is not a column of the dataset"));
                }
            }
            if (!all.containsKey(dv.keep().by()) && !DatasetDef.BUILT_IN.contains(dv.keep().by())) {
                out.add(error("DRS-9104", "grain.keep", "'" + dv.keep().by() + "' is not a column of the dataset"));
            }
        }
        if (dv.group() != null) {
            for (String g : dv.group().by()) {
                if (!all.containsKey(g)) {
                    out.add(error("DRS-9101", "group.by", "'" + g + "' is not a column of the dataset"));
                }
            }
            dv.group().columns().forEach((name, agg) -> {
                if (!all.containsKey(name) && !"count".equals(agg)) {
                    out.add(error("DRS-9101", "group.columns." + name, "'" + name + "' is not a column of the dataset (only count may name a new column: it counts rows)"));
                }
            });
        }
        dv.python().forEach((name, py) -> {
            if (py.item().isBlank()) {
                out.add(error("DRS-9101", "derive." + name + ".python", "python names the Python item (interface transform until wave B's column)"));
            }
            for (String in : py.inputs()) {
                if (!all.containsKey(in) && !DatasetDef.BUILT_IN.contains(in)) {
                    out.add(error("DRS-9101", "derive." + name + ".inputs", "'" + in + "' is not a column of the dataset"));
                }
            }
        });
        d.columns().forEach((name, c) -> related(c).ifPresent(r -> {
            DatasetDef.Col via = all.get(r[0]);
            if (via == null || via.references() == null || via.references().isBlank()) {
                out.add(error("DRS-9101", "derive." + name + ".expr", "related(" + r[0] + "." + r[1] + ") follows a column that references a pack kind; '" + r[0] + "' "
                        + (via == null ? "is not a column" : "references none (references: <kind>)")));
                return;
            }
            Optional<DatasetDef> kind = kinds == null ? Optional.empty() : kinds.apply(via.references());
            if (kind.isEmpty()) {
                out.add(error("DRS-9101", "derive." + name + ".expr", "no enabled pack generates the kind '" + via.references() + "' that '" + r[0] + "' references"));
            } else if (!kind.get().columns().containsKey(r[1]) && !DatasetDef.BUILT_IN.contains(r[1])) {
                out.add(error("DRS-9101", "derive." + name + ".expr", "the kind '" + via.references() + "' has no field '" + r[1] + "' (it has " + kind.get().columns().keySet() + ")"));
            }
        }));
        return out;
    }

    /** {@code related(x.y)} as its two names, when the column is one. */
    public static Optional<String[]> related(DatasetDef.Col c) {
        if (c.expr() == null || !c.expr().contains("related")) {
            return Optional.empty();
        }
        try {
            Node n = com.ash.drishti.server.bi.expr.Expressions.parse(c.expr());
            if (n instanceof Node.Call call && "related".equals(call.function()) && call.args().size() == 1 && call.args().get(0) instanceof Node.Ref r && r.parts().size() == 2) {
                return Optional.of(new String[] {r.parts().get(0), r.parts().get(1)});
            }
        } catch (RuntimeException e) {
            // the parse error is Check's
        }
        return Optional.empty();
    }

    /**
     * The lineage of a resolved dataset, root last: each step's {@code kind} ({@code dataset}, {@code pack-kind}, {@code connector-query}, {@code python},
     * {@code model}, {@code sql}) and name. A root that cannot be traced (a kind no enabled pack generates) is a {@code DRS-9110} problem.
     */
    public static List<Map<String, Object>> lineage(ResolvedDataset r, PackDatasets packs, List<DatasetParser.Problem> problems) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : r.chain()) {
            out.add(Map.of("kind", "dataset", "name", name));
        }
        DatasetDef.Source s = r.def().source();
        if (s == null) {
            problems.add(error("DRS-9110", "source", "the root of '" + r.def().name() + "' cannot be traced: no source along " + r.chain()));
            return out;
        }
        switch (s.type()) {
            case DatasetDef.Source.PACK -> {
                Optional<DatasetDef> gen = packs == null ? Optional.empty() : packs.ofKind(s.kind());
                if (gen.isEmpty()) {
                    problems.add(error("DRS-9110", "source", "the root of '" + r.def().name() + "' cannot be traced: no enabled pack generates the kind '" + s.kind() + "'"));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", "pack-kind");
                m.put("name", s.kind());
                m.put("pack", gen.map(g -> g.generated() == null ? s.pack() : g.generated().pack()).orElse(s.pack()));
                m.put("connector", s.connector() == null || s.connector().isBlank() ? "(whichever serves the kind)" : s.connector());
                out.add(m);
            }
            case DatasetDef.Source.CONNECTOR -> out.add(Map.of("kind", "connector-query", "name", s.connector()));
            case DatasetDef.Source.PYTHON -> out.add(Map.of("kind", "python", "name", s.python(), "inputs", s.inputs().keySet()));
            case DatasetDef.Source.MODEL -> out.add(Map.of("kind", "model", "name", s.model()));
            case DatasetDef.Source.SQL -> out.add(Map.of("kind", "sql-view", "name", s.model()));
            default -> problems.add(error("DRS-9110", "source", "the root of '" + r.def().name() + "' cannot be traced: an unknown source '" + s.type() + "'"));
        }
        return out;
    }

    private static DatasetParser.Problem error(String code, String path, String message) {
        return new DatasetParser.Problem("error", code, path, message);
    }
}
