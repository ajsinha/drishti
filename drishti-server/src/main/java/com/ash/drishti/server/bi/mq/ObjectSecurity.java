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
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.expr.Expressions;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Object-level security (docs/architecture/BI_DESIGN.md section 5.1, "hidden-for"): for a reader, a column hidden by its dataset or the model, a measure the
 * model hides, and every measure that reads a hidden column <em>do not exist</em>: absent from {@code describe}, unknown to DEL and Drishti SQL, and a model
 * query naming one is refused {@code DRS-9152} "not found" (indistinguishable from a typo, on purpose). Hidden measures are kept as {@code measure:<name>}.
 */
public final class ObjectSecurity {

    public static final String MEASURE = "measure:";

    private ObjectSecurity() {}

    /** The fields ({@code alias.column}) and measures ({@code measure:<name>}) hidden from a reader holding {@code roles}. */
    public static Set<String> hidden(ModelView v, Set<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        v.datasets().forEach((alias, r) -> datasetHidden(alias, r, roles, out));
        for (ModelDef.HiddenFor h : v.def().semantics().hiddenFor()) {
            if (h.roles().stream().anyMatch(roles::contains)) {
                out.addAll(h.fields());
                h.measures().forEach(m -> out.add(MEASURE + m));
            }
        }
        if (out.isEmpty()) {
            return out;
        }
        Dataset flat = v.flat();
        for (String m : flat.measures().keySet()) {                            // a measure that reads a hidden column is hidden too
            if (reads(flat, m, out, new LinkedHashSet<>())) {
                out.add(MEASURE + m);
            }
        }
        return out;
    }

    private static void datasetHidden(String alias, ResolvedDataset r, Set<String> roles, Set<String> out) {
        if (r != null && r.def().security().hiddenRoles().stream().anyMatch(roles::contains)) {
            r.def().security().hiddenColumns().forEach(c -> out.add(alias + "." + c));
        }
    }

    private static boolean reads(Dataset flat, String measure, Set<String> hidden, Set<String> seen) {
        Dataset.Measure m = flat.measures().get(measure);
        if (m == null || !seen.add(measure)) {
            return false;
        }
        if (hidden.contains(MEASURE + measure)) {
            return true;
        }
        if (m.expr() == null) {
            return m.inputs().stream().anyMatch(hidden::contains);
        }
        try {
            return Node.anyMatch(Expressions.parse(m.expr()), x -> x instanceof Node.Ref r && r.parts().size() >= 2 && hidden.contains(r.parts().get(0) + "." + r.parts().get(1))
                    || x instanceof Node.MeasureRef mr && reads(flat, mr.name(), hidden, seen));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Refuses a query that names a field or measure hidden from the reader, as if it did not exist ({@code DRS-9152}). */
    public static void check(ModelView v, ModelQuery q, Set<String> hidden) {
        if (hidden.isEmpty()) {
            return;
        }
        Map<String, String> ids = v.ids();
        List<MqException.Problem> out = new ArrayList<>();
        for (int i = 0; i < q.fields().size(); i++) {
            field(ids.getOrDefault(q.fields().get(i), q.fields().get(i)), "select.fields[" + i + "]", hidden, out);
        }
        for (int i = 0; i < q.measures().size(); i++) {
            measure(ids.getOrDefault(q.measures().get(i), q.measures().get(i)), "select.measures[" + i + "]", hidden, out);
        }
        for (int i = 0; i < q.where().size(); i++) {
            ModelQuery.Where w = q.where().get(i);
            field(ids.getOrDefault(w.field(), w.field()), "where[" + i + "].field", hidden, out);
            if (w.by() != null) {
                measure(ids.getOrDefault(w.by(), w.by()), "where[" + i + "].by", hidden, out);
            }
        }
        for (int i = 0; i < q.having().size(); i++) {
            measure(ids.getOrDefault(q.having().get(i).measure(), q.having().get(i).measure()), "having[" + i + "].measure", hidden, out);
        }
        for (int i = 0; i < q.sort().size(); i++) {
            String by = ids.getOrDefault(q.sort().get(i).by(), q.sort().get(i).by());
            if (by.contains(".")) {
                field(by, "sort[" + i + "].by", hidden, out);
            } else {
                measure(by, "sort[" + i + "].by", hidden, out);
            }
        }
        q.define().forEach((name, expr) -> {
            try {
                Node.walk(Expressions.parse(Expressions.withMeasureNames(expr, ids)), x -> {
                    if (x instanceof Node.Ref r && r.parts().size() >= 2) {
                        field(r.parts().get(0) + "." + r.parts().get(1), "define." + name, hidden, out);
                    } else if (x instanceof Node.MeasureRef mr) {
                        measure(mr.name(), "define." + name, hidden, out);
                    }
                });
            } catch (RuntimeException e) {
                // the validator reports the expression
            }
        });
        if (!out.isEmpty()) {
            throw new MqException(out);
        }
    }

    private static void field(String f, String path, Set<String> hidden, List<MqException.Problem> out) {
        String[] p = f.split("\\.");
        if (p.length >= 2 && hidden.contains(p[0] + "." + p[1])) {
            out.add(MqException.Problem.of("DRS-9152", path, "no field '" + f + "' in this model"));
        }
    }

    private static void measure(String m, String path, Set<String> hidden, List<MqException.Problem> out) {
        if (hidden.contains(MEASURE + m)) {
            out.add(MqException.Problem.of("DRS-9152", path, "no measure '" + m + "' in this model"));
        }
    }
}
