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
package com.ash.drishti.bicompute.formula;

import java.util.List;
import java.util.function.Consumer;

/**
 * The syntax tree of a pivot formula (PIVOT_ENGINE.md section 8.2, "Formula of built-ins (DEL)"): DEL's expression syntax (the accepted
 * subset of Python's expression grammar, BI_DESIGN.md section 3.3.2) restricted to what a measure needs: numbers, strings, fields, arithmetic,
 * comparisons, {@code and}/{@code or}/{@code not}, {@code x if c else y}, scalar calls ({@code abs}, {@code sqrt}, ...) and aggregate calls
 * of the built-ins with an optional {@code where=} condition. Immutable.
 */
public sealed interface FormulaAst {

    /** {@code 12}, {@code 1.5e3}. */
    record Num(double value) implements FormulaAst {}

    /** {@code 'RATES'}. */
    record Str(String value) implements FormulaAst {}

    /** {@code True}, {@code False}. */
    record Bool(boolean value) implements FormulaAst {}

    /** {@code None}. */
    record NoneLit() implements FormulaAst {}

    /** A field of the source ({@code NOTIONAL_USD}, {@code trades.MTM}). */
    record Field(String name) implements FormulaAst {}

    /** {@code [a, b]}: the right side of {@code in}. */
    record ListLit(List<FormulaAst> items) implements FormulaAst {
        public ListLit {
            items = List.copyOf(items);
        }
    }

    /** {@code -x}, {@code +x}, {@code not x}. */
    record Unary(String op, FormulaAst operand) implements FormulaAst {}

    /** {@code + - * / // % **}. */
    record Binary(String op, FormulaAst left, FormulaAst right) implements FormulaAst {}

    /** {@code a and b}, {@code a or b}. */
    record BoolOp(String op, List<FormulaAst> values) implements FormulaAst {
        public BoolOp {
            values = List.copyOf(values);
        }
    }

    /** A comparison, possibly chained: {@code == != < <= > >= in}, {@code not in}. */
    record Compare(FormulaAst first, List<String> ops, List<FormulaAst> rest) implements FormulaAst {
        public Compare {
            ops = List.copyOf(ops);
            rest = List.copyOf(rest);
        }
    }

    /** {@code body if test else orElse}. */
    record IfExp(FormulaAst body, FormulaAst test, FormulaAst orElse) implements FormulaAst {}

    /** A scalar function of row values ({@code abs(PNL)}). */
    record Scalar(String name, List<FormulaAst> args) implements FormulaAst {
        public Scalar {
            args = List.copyOf(args);
        }
    }

    /**
     * An aggregate call of a built-in ({@code sum(PNL, where=PNL > 0)}, {@code percentile(PNL, 0.99)}, {@code count()}).
     *
     * @param name the built-in's name
     * @param args the row expressions (the inputs of the built-in)
     * @param numbers the built-in's numeric parameters (a percentile's q)
     * @param where the row condition (null: every row)
     */
    record Agg(String name, List<FormulaAst> args, List<Double> numbers, FormulaAst where) implements FormulaAst {
        public Agg {
            args = List.copyOf(args);
            numbers = List.copyOf(numbers);
        }
    }

    /** Visits every node, parents first. */
    static void walk(FormulaAst n, Consumer<FormulaAst> visit) {
        visit.accept(n);
        switch (n) {
            case ListLit l -> l.items().forEach(i -> walk(i, visit));
            case Unary u -> walk(u.operand(), visit);
            case Binary b -> {
                walk(b.left(), visit);
                walk(b.right(), visit);
            }
            case BoolOp b -> b.values().forEach(v -> walk(v, visit));
            case Compare c -> {
                walk(c.first(), visit);
                c.rest().forEach(r -> walk(r, visit));
            }
            case IfExp i -> {
                walk(i.body(), visit);
                walk(i.test(), visit);
                walk(i.orElse(), visit);
            }
            case Scalar s -> s.args().forEach(a -> walk(a, visit));
            case Agg a -> {
                a.args().forEach(x -> walk(x, visit));
                if (a.where() != null) {
                    walk(a.where(), visit);
                }
            }
            default -> { }
        }
    }
}
