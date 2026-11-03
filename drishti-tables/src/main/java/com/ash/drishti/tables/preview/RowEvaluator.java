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

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;

/**
 * Evaluates a row-scope expression or a path in a {@link Preview.Frame} with Rachana's own evaluator: {@code $} the entity, the grain's
 * elements as {@code @} and its parents ({@code ^}, {@code ^^}), outermost first ({@link EvalContext#withRows}); {@code in}, lists and
 * named arguments as Rachana evaluates them. The same evaluator the layer's in-memory answers use (part A's {@code RowsEvaluator}).
 */
@FunctionalInterface
public interface RowEvaluator {

    /** The value of {@code expr} in the frame: a Java value, null, or the mask. */
    Object eval(String expr, Preview.Frame frame);

    /** Rachana's evaluator. */
    static RowEvaluator rachana() {
        ElCompiler el = new ElCompiler(2_000);
        Formats formats = Formats.defaults();
        return (expr, f) -> {
            EvalContext c = EvalContext.of(f.entity(), formats);
            if (!f.elements().isEmpty()) {
                c = c.withRows(f.elements());
            }
            Object v = el.compile(expr).eval(c);
            return Values.masked(v) ? v : Values.simplify(v);
        };
    }
}
