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
    Set<String> SCALARS = Set.of("abs", "coalesce", "upper", "lower", "round", "year", "quarter", "month", "day", "if", "contains", "startsWith", "length", "endswith", "week",
            "power", "date", "timestamp", "days_between", "business_days_between");
    /**
     * Time intelligence: {@code ytd(agg, table.dateColumn)} and its kin restrict the aggregate to the rows whose date falls in a window that ends at the query's
     * as-of date (the end of its dates); the windows are worked out in Java from the pack's business-day calendar and bound as parameters.
     */
    Set<String> TIME = Set.of("ytd", "mtd", "qtd", "prior_period", "same_period_last_year");
    /** {@code running(agg, table.dateColumn)}: a running total of a sum, count, min or max along the date field of the query. */
    Set<String> WINDOW = Set.of("running", "cumulative");
    /**
     * Window functions over the query's grouped rows (DuckDB window SQL, worked out after the row rules and filters): {@code lag(agg, n, order [, partition...])},
     * {@code lead}, {@code moving_avg|moving_sum|moving_min|moving_max(agg, n, order [, partition...])}, {@code rank|dense_rank|percent_rank(agg [, 'asc'|'desc' [, partition...]])}.
     */
    Set<String> ANALYTIC = Set.of("lag", "lead", "rank", "dense_rank", "percent_rank", "moving_avg", "moving_sum", "moving_min", "moving_max");
    /** The analytic functions that run along an order column (the rest rank by the aggregate itself). */
    Set<String> ORDERED = Set.of("lag", "lead", "moving_avg", "moving_sum", "moving_min", "moving_max");
    /**
     * Wave B (docs/architecture/BI_DESIGN.md sections 3.3.4 and 3.3.5): functions over an aggregate in an edited filter context or at other dates. Their
     * keyword arguments are kept as pseudo-calls {@code Call("=where", [cond])}, {@code Call("=ignore", [refs or ALL])} (see {@link #keyword}).
     */
    Set<String> CONTEXT = Set.of("calc", "share_of", "parent_of", "avg_by", "as_of", "last", "first", "opening", "closing", "avg_over", "convert");
    /** Scalars of wave B: dates that move ({@code shift_date}), the query's as-of date ({@code asof}), FX rates ({@code fx}). */
    Set<String> SCALARS_B = Set.of("shift_date", "asof", "fx");
    /** The prefix of a keyword argument kept in the tree. */
    String KEYWORD = "=";
    /** Keyword arguments that name fields a context drops or keeps (or {@code ALL}): the expression does not read them, so walks of the tree skip them. */
    Set<String> UNREAD = Set.of("=ignore", "=keep", "=over", "=hierarchy", "=via", "=period", "=unit", "=to", "=at");

    /** The value of a keyword argument of a call ({@code null} when absent): its one item, or for a list keyword the pseudo-call itself. */
    static Call keyword(Call c, String name) {
        for (Node a : c.args()) {
            if (a instanceof Call k && k.function().equals(KEYWORD + name)) {
                return k;
            }
        }
        return null;
    }

    /** The positional arguments of a call (its keyword pseudo-calls left out). */
    static List<Node> positional(Call c) {
        return c.args().stream().filter(a -> !(a instanceof Call k && k.function().startsWith(KEYWORD))).toList();
    }

    /** Whether the function is worked out over the result rows, not inside the group. */
    static boolean isWindow(String f) {
        return WINDOW.contains(f) || ANALYTIC.contains(f);
    }

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
            case Call c -> !UNREAD.contains(c.function()) && c.args().stream().anyMatch(a -> anyMatch(a, test));
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
            case Call c -> {
                if (!UNREAD.contains(c.function())) {
                    c.args().forEach(a -> walk(a, visit));
                }
            }
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
