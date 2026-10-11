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
package com.ash.drishti.server.bi.dataset;

import java.util.List;
import java.util.Set;

/**
 * A node of a dataset expression (Rachana-EL syntax extended with aggregation, RUPAKA.md section 6): the tree {@link ExprParser} builds and
 * {@link com.ash.drishti.server.bi.engine.SqlCompiler} turns into parameterised SQL. Immutable.
 */
public sealed interface Node {

    /** The aggregate functions. */
    Set<String> AGGREGATES = Set.of("sum", "avg", "min", "max", "count", "distinct", "ratio", "percentile");
    /** Row-level functions. */
    Set<String> SCALARS = Set.of("abs", "coalesce", "upper", "lower", "round", "year", "quarter", "month", "day", "if", "contains", "startsWith", "length");
    /** Grammar the language reserves for later (RUPAKA.md section 6): parsed, then refused with DRS-9013. */
    Set<String> RESERVED = Set.of("running", "lag", "rank", "ytd", "mtd", "qtd", "same_period_last_year");

    record Num(double value, boolean integral) implements Node {}

    record Str(String value) implements Node {}

    record Bool(boolean value) implements Node {}

    record Null() implements Node {}

    /** A dotted name: {@code trades.notional}, {@code trades.tradeDate.year}, a bare {@code notional} (a column of the table in context), or a table in {@code count(trades)}. */
    record Ref(List<String> parts) implements Node {
        public String text() {
            return String.join(".", parts);
        }
    }

    /** {@code user.desks}, {@code user.name}: an attribute of the reader. */
    record UserRef(String attribute) implements Node {}

    /** {@code [MTM]}: another measure of the dataset. */
    record MeasureRef(String name) implements Node {}

    record Call(String function, List<Node> args) implements Node {}

    record Bin(String op, Node left, Node right) implements Node {}

    record Un(String op, Node operand) implements Node {}

    /** {@code x in (a, b)} or {@code x in user.desks}; {@code negated} for {@code not in}. */
    record In(Node value, List<Node> items, boolean negated) implements Node {}

    record Cond(Node test, Node yes, Node no) implements Node {}

    /** True when the tree holds an aggregate call. */
    static boolean hasAggregate(Node n) {
        return anyMatch(n, x -> x instanceof Call c && AGGREGATES.contains(c.function()));
    }

    /** True when any node of the tree satisfies the test. */
    static boolean anyMatch(Node n, java.util.function.Predicate<Node> test) {
        if (test.test(n)) {
            return true;
        }
        return switch (n) {
            case Call c -> c.args().stream().anyMatch(a -> anyMatch(a, test));
            case Bin b -> anyMatch(b.left(), test) || anyMatch(b.right(), test);
            case Un u -> anyMatch(u.operand(), test);
            case In i -> anyMatch(i.value(), test) || i.items().stream().anyMatch(a -> anyMatch(a, test));
            case Cond c -> anyMatch(c.test(), test) || anyMatch(c.yes(), test) || anyMatch(c.no(), test);
            default -> false;
        };
    }

    /** Visits every node of the tree, parents first. */
    static void walk(Node n, java.util.function.Consumer<Node> visit) {
        visit.accept(n);
        switch (n) {
            case Call c -> c.args().forEach(a -> walk(a, visit));
            case Bin b -> {
                walk(b.left(), visit);
                walk(b.right(), visit);
            }
            case Un u -> walk(u.operand(), visit);
            case In i -> {
                walk(i.value(), visit);
                i.items().forEach(a -> walk(a, visit));
            }
            case Cond c -> {
                walk(c.test(), visit);
                walk(c.yes(), visit);
                walk(c.no(), visit);
            }
            default -> { }
        }
    }
}
