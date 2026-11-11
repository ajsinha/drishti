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
package com.ash.drishti.tables.preview;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.tables.lang.LateBound;
import com.ash.drishti.tables.lang.Trees;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates a row-scope expression or a path in a {@link Preview.Frame} with Rachana's own evaluator: {@code $} the entity, the grain's
 * elements as {@code @} and its parents ({@code ^}, {@code ^^}), outermost first ({@link EvalContext#withRows}). {@code fx} and
 * {@code related} are lowered by the layer ({@link LateBound}): their values are worked out for the row and read as its columns.
 */
@FunctionalInterface
public interface RowEvaluator {

    /** The value of {@code expr} in the frame: a Java value, null, or the mask. */
    Object eval(String expr, Preview.Frame frame);

    /** Rachana's evaluator, with no FX source and no other tables. */
    static RowEvaluator rachana() {
        return rachana(LateBound.Fx.NONE, LateBound.Lookup.NONE, null);
    }

    /** Rachana's evaluator; {@code fx} and {@code related} read {@code fx} and {@code lookup} at {@code date} (null: today). */
    static RowEvaluator rachana(LateBound.Fx fx, LateBound.Lookup lookup, LocalDate date) {
        Formats formats = Formats.defaults();
        Map<String, LateBound.Bound> bound = new ConcurrentHashMap<>();
        return (expr, f) -> {
            LateBound.Bound b = bound.computeIfAbsent(expr, x -> LateBound.bind(Trees.parse(x), false));
            EvalContext c = context(f.entity(), f.elements(), formats);
            if (!b.none()) {
                Map<String, DataNode> late = LateBound.values(b, c, date == null ? LocalDate.now(java.time.ZoneOffset.UTC) : date, fx, lookup);
                List<DataNode> elements = new ArrayList<>(f.elements());
                DataNode entity = f.entity();
                if (elements.isEmpty()) {
                    entity = with(entity, late);
                } else {
                    elements.set(elements.size() - 1, with(elements.get(elements.size() - 1), late));
                }
                c = context(entity, elements, formats);
            }
            Object v = b.expr().eval(c);
            return Values.masked(v) ? v : Values.simplify(v);
        };
    }

    private static EvalContext context(DataNode entity, List<DataNode> elements, Formats formats) {
        EvalContext c = EvalContext.of(entity, formats);
        return elements.isEmpty() ? c : c.withRows(elements);
    }

    private static DataNode with(DataNode node, Map<String, DataNode> more) {
        Map<String, DataNode> fields = node instanceof DataNode.Obj o ? new LinkedHashMap<>(o.fields()) : new LinkedHashMap<>();
        fields.putAll(more);
        return new DataNode.Obj(fields);
    }
}
