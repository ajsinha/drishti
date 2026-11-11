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
package com.ash.drishti.iceberg.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * The filters planning and reads push down: comparisons of one column (by field id) with literals, joined by
 * {@code and}. What Drishti asks is narrow (a business date or a range of them, {@code id = …}, a column's value), so
 * this is all there is; anything a filter cannot prune is answered by reading. Literals are in the column's internal
 * form ({@link com.ash.drishti.iceberg.types.Type}).
 */
public sealed interface Expr permits Expr.True, Expr.And, Expr.Pred {

    /** The comparisons. */
    enum Op {
        EQ, IN, LT, LE, GT, GE, IS_NULL, NOT_NULL
    }

    /** Everything. */
    record True() implements Expr {}

    /** All of them. */
    record And(List<Expr> parts) implements Expr {
        public And {
            parts = List.copyOf(parts);
        }
    }

    /** One column compared: {@code values} has one literal (none for the null tests, several for IN). */
    record Pred(int fieldId, Op op, List<Object> values) implements Expr {
        public Pred {
            values = List.copyOf(values);
        }

        public Object value() {
            return values.get(0);
        }
    }

    Expr TRUE = new True();

    static Expr eq(int fieldId, Object v) {
        return new Pred(fieldId, Op.EQ, List.of(v));
    }

    static Expr in(int fieldId, List<?> vs) {
        return new Pred(fieldId, Op.IN, new ArrayList<>(vs));
    }

    static Expr cmp(int fieldId, Op op, Object v) {
        return new Pred(fieldId, op, List.of(v));
    }

    static Expr and(Expr... parts) {
        List<Expr> out = new ArrayList<>();
        for (Expr e : parts) {
            if (e instanceof And a) {
                out.addAll(a.parts());
            } else if (!(e instanceof True)) {
                out.add(e);
            }
        }
        return out.isEmpty() ? TRUE : out.size() == 1 ? out.get(0) : new And(out);
    }

    /** The comparisons of this filter (an {@code and} flattened). */
    default List<Pred> preds() {
        return switch (this) {
            case True t -> List.of();
            case Pred p -> List.of(p);
            case And a -> {
                List<Pred> out = new ArrayList<>();
                a.parts().forEach(e -> out.addAll(e.preds()));
                yield out;
            }
        };
    }
}
