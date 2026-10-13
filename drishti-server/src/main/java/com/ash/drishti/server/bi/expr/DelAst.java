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
package com.ash.drishti.server.bi.expr;

import java.util.List;

/**
 * The syntax tree of a DEL v2 expression (docs/architecture/BI_DESIGN.md section 3.3.2): the accepted subset of Python's expression grammar, as
 * {@link DelParser} reads it. Every node knows where it was written ({@code start}, {@code end}: character offsets in the text) so a problem can point at it.
 * Nothing here evaluates; {@link DelChecker} types and scopes the tree and {@link DelLowering} turns it into the compiler's tree. Immutable.
 */
public sealed interface DelAst {

    int start();

    int end();

    /** {@code 12}, {@code 1.5}, {@code 1e6}, {@code 1_000_000}. */
    record Num(String text, double value, boolean integral, int start, int end) implements DelAst {}

    /** {@code 'text'} or {@code "text"}, escapes resolved. */
    record Str(String value, int start, int end) implements DelAst {}

    /** {@code True} or {@code False}. */
    record Bool(boolean value, int start, int end) implements DelAst {}

    /** {@code None}. */
    record NoneLit(int start, int end) implements DelAst {}

    /** {@code ALL}: every field, in the filter-context keywords of wave B ({@code ignore=ALL}, {@code over=ALL}). */
    record AllLit(int start, int end) implements DelAst {}

    /** {@code [a, b, c]}: the right side of {@code in}, or a list of fields for {@code by=}. */
    record ListLit(List<DelAst> items, int start, int end) implements DelAst {
        public ListLit {
            items = List.copyOf(items);
        }
    }

    /** A dotted name: {@code notional}, {@code trades.mtm}, {@code trades.tradeDate.month}, {@code user.desks}, {@code param.shock}. */
    record Field(List<String> parts, int start, int end) implements DelAst {
        public Field {
            parts = List.copyOf(parts);
        }

        public String text() {
            return String.join(".", parts);
        }
    }

    /** A keyword argument {@code name=value}. */
    record Keyword(String name, DelAst value, int start, int end) {}

    /** A call of a catalogue function: positional arguments, then keyword arguments. */
    record Call(String name, List<DelAst> args, List<Keyword> keywords, int start, int end) implements DelAst {
        public Call {
            args = List.copyOf(args);
            keywords = List.copyOf(keywords);
        }

        public DelAst keyword(String name) {
            return keywords.stream().filter(k -> k.name().equals(name)).map(Keyword::value).findFirst().orElse(null);
        }
    }

    /** {@code not x}, {@code -x}, {@code +x}. */
    record Unary(String op, DelAst operand, int start, int end) implements DelAst {}

    /** {@code + - * / // % **}. */
    record Binary(String op, DelAst left, DelAst right, int start, int end) implements DelAst {}

    /** {@code a and b and c}, {@code a or b}. */
    record BoolOp(String op, List<DelAst> values, int start, int end) implements DelAst {
        public BoolOp {
            values = List.copyOf(values);
        }
    }

    /**
     * A comparison, possibly chained ({@code 0 < x <= 10}): {@code first}, then each operator with its right operand. Operators: {@code == != < <= > >= in},
     * {@code not in}, {@code is}, {@code is not}.
     */
    record Compare(DelAst first, List<String> ops, List<DelAst> rest, int start, int end) implements DelAst {
        public Compare {
            ops = List.copyOf(ops);
            rest = List.copyOf(rest);
        }
    }

    /** {@code body if test else orElse}. */
    record IfExp(DelAst body, DelAst test, DelAst orElse, int start, int end) implements DelAst {}

    /** Visits every node, parents first. */
    static void walk(DelAst n, java.util.function.Consumer<DelAst> visit) {
        visit.accept(n);
        switch (n) {
            case ListLit l -> l.items().forEach(i -> walk(i, visit));
            case Call c -> {
                c.args().forEach(a -> walk(a, visit));
                c.keywords().forEach(k -> walk(k.value(), visit));
            }
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
            default -> { }
        }
    }
}
