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
 * Evaluates a row-scope expression in a {@link Preview.Frame}: Rachana's evaluator, with {@code $} the entity and {@code @} the grain's
 * element. What Rachana cannot evaluate yet ({@code ^}, {@code in}, list literals, named arguments: part A of 2.9.11.e) throws
 * {@link Pending}, so a preview shows the rest and says what waits. Part A's evaluator replaces {@link #rachana()} where it is created;
 * nothing else changes.
 */
@FunctionalInterface
public interface RowEvaluator {

    /** The value of {@code expr} in the frame. */
    Object eval(String expr, Preview.Frame frame);

    /** An expression that needs what Rachana's checked mode adds. */
    final class Pending extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Pending(String message) {
            super(message);
        }
    }

    /** Rachana's evaluator as it is today. */
    static RowEvaluator rachana() {
        ElCompiler el = new ElCompiler(2_000);
        Formats formats = Formats.defaults();
        return (expr, f) -> {
            if (expr.contains("^") || expr.matches("(?s).*\\bin\\b.*") || expr.contains("[")) {
                com.ash.drishti.tables.expr.Node n = com.ash.drishti.tables.expr.Reader.read(expr);
                boolean[] pending = {false};
                n.walk(x -> {
                    if (x instanceof com.ash.drishti.tables.expr.Node.Path p && p.root().startsWith("^")
                            || x instanceof com.ash.drishti.tables.expr.Node.ListOf
                            || x instanceof com.ash.drishti.tables.expr.Node.Binary b && (b.op().equals("in") || b.op().equals("not-in"))) {
                        pending[0] = true;
                    }
                });
                if (pending[0]) {
                    throw new Pending("uses what Rachana's checked mode adds (in, lists, ^): computed once part A lands");
                }
            }
            EvalContext c = EvalContext.of(f.entity(), formats);
            if (!f.elements().isEmpty()) {
                c = c.withRow(f.root("@"), f.index());
            }
            return Values.simplify(el.compile(expr).eval(c));
        };
    }
}
