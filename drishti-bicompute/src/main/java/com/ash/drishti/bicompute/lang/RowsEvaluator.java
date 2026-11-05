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
package com.ash.drishti.bicompute.lang;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Java form of a compiled expression over rows held in memory (a live table: BI_FOUNDATION_2911E.md 2.6, step 3): a row expression
 * is Rachana's own evaluator; an aggregate feeds the engine's {@link AggFunction} the rows' inputs (masks applied before: a masked input
 * is nothing, as its SQL reads null) after the measure's own condition; a {@link Lowered.Shape} combines its parts with Rachana. What
 * needs an answer (a context sub-plan, a window, time intelligence, a conversion) is computed by the query over the answer and has no
 * Java form here; it is refused in words.
 */
public final class RowsEvaluator {

    private final EvalContext base;

    /** {@code base}: the context rows are read in (the as-of date of {@code asof()}); each row becomes the document of its expressions. */
    public RowsEvaluator(EvalContext base) {
        this.base = base;
    }

    /** A row expression's value for one row: a Java value ({@code Long}, {@code Double}, {@code BigDecimal}, text, boolean), null or the mask. */
    public Object row(Lowered.Row r, DataNode row) {
        Object v = r.expr().eval(base.withRoot(row));
        return Values.masked(v) ? v : Values.simplify(v);
    }

    /** A measure over the rows; {@code measures}: the values of the model's measures it names. */
    public Object measure(Lowered l, List<DataNode> rows, Map<String, Object> measures) {
        return switch (l) {
            case Lowered.Aggregate a -> aggregate(a, rows);
            case Lowered.Shape s -> {
                EvalContext c = base;
                for (int i = 0; i < s.parts().size(); i++) {
                    c = c.withLocal(Lowered.Shape.local(i), measure(s.parts().get(i), rows, measures));
                }
                Object v = s.expr().eval(c);
                yield Values.masked(v) ? v : Values.simplify(v);
            }
            case Lowered.Measure m -> {
                if (m.scope() != null || !measures.containsKey(m.name())) {
                    throw new IllegalArgumentException("measure('" + m.name() + "') is not known here");
                }
                yield measures.get(m.name());
            }
            case Lowered.Row r -> throw new IllegalArgumentException("a row expression is not a measure");
            default -> throw new IllegalArgumentException(name(l) + " is computed by the query over an answer (a context, a window or a period): "
                    + "a live table's rows in memory have no other groups, dates or currencies to read");
        };
    }

    private static String name(Lowered l) {
        return switch (l) {
            case Lowered.Context c -> c.fn() + "()";
            case Lowered.Window w -> w.fn() + "()";
            case Lowered.Time t -> t.fn() + "()";
            case Lowered.Convert c -> "convert()";
            default -> "this";
        };
    }

    private Object aggregate(Lowered.Aggregate a, List<DataNode> rows) {
        List<DataNode> kept = new ArrayList<>();
        for (DataNode r : rows) {
            if (a.where() == null || Values.truthy(row(a.where(), r))) {
                kept.add(r);
            }
        }
        AggFunction<Object> f = function(a);
        Object[] columns = new Object[a.inputs().size()];
        for (int i = 0; i < a.inputs().size(); i++) {
            List<Object> values = new ArrayList<>(kept.size());
            for (DataNode r : kept) {
                Object v = row(a.inputs().get(i), r);
                values.add(Values.masked(v) ? null : v);                     // masks before aggregation: a masked value is nothing
            }
            columns[i] = f.numeric() ? numbers(values) : texts(values);
        }
        Object state = f.add(f.start(), new Batch(kept.size(), columns));
        return f.result(state);
    }

    static AggFunction<Object> function(Lowered.Aggregate a) {
        return Functions.builtIn(a.fn(), a.inputs().isEmpty() ? List.of() : List.of("x"), a.numbers())
                .orElseThrow(() -> new IllegalArgumentException(a.fn() + "() is not an aggregate of the engine"));
    }

    private static double[] numbers(List<Object> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            Object v = values.get(i);
            out[i] = v instanceof Number n ? n.doubleValue() : v instanceof Boolean b ? (b ? 1 : 0) : Double.NaN;
        }
        return out;
    }

    private static String[] texts(List<Object> values) {
        String[] out = new String[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i) == null ? null : Values.text(values.get(i));
        }
        return out;
    }
}
