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
package com.ash.drishti.server.bi.sql;

import java.util.List;

/**
 * The syntax tree of a Drishti SQL statement (docs/architecture/BI_DESIGN.md section 3.4), as {@link DsqlParser} reads it. Every node knows where it was
 * written ({@code at}: a character offset) so a refusal can point at it. Immutable; nothing here runs.
 */
public final class SqlAst {

    private SqlAst() {}

    /** The whole statement: a query, possibly asked to be explained. */
    public record Statement(Query query, boolean explain) {}

    /** {@code [WITH ...] body [ORDER BY ...] [LIMIT n [OFFSET m]]}. */
    public record Query(List<Cte> with, Body body, List<Order> orderBy, Long limit, Long offset, int at) {
        public Query {
            with = List.copyOf(with);
            orderBy = List.copyOf(orderBy);
        }
    }

    /** A common table expression: {@code name AS (query)}. */
    public record Cte(String name, Query query, int at) {}

    /** A query body: one select, or a union of bodies. */
    public sealed interface Body {
        int at();
    }

    /** {@code left UNION [ALL] right}. */
    public record Union(Body left, Body right, boolean all, int at) implements Body {}

    /**
     * One {@code SELECT}.
     *
     * @param time the table clause ({@code AS OF DATE '...'}, {@code FOR LIVE}, {@code FOR WINDOW ...}), or null
     */
    public record Select(boolean distinct, List<Item> items, List<From> from, Expr where, List<Expr> groupBy, Expr having, List<Order> orderBy, Long limit,
            Long offset, TimeClause time, int at) implements Body {
        public Select {
            items = List.copyOf(items);
            from = List.copyOf(from);
            groupBy = List.copyOf(groupBy);
            orderBy = List.copyOf(orderBy);
        }
    }

    /** A select item: {@code *}, or an expression with an optional alias. */
    public record Item(Expr expr, String alias, boolean star, int at) {}

    /** A table of {@code FROM}: a name (a model alias or a CTE) or a subquery, with how it is joined to the ones before ({@code join} null for the first). */
    public record From(String table, Query subquery, String alias, String join, Expr on, int at) {
        public String name() {
            return alias != null ? alias : table;
        }
    }

    /** {@code AS OF DATE 'd'}, {@code FOR LIVE}, {@code FOR WINDOW LAST n unit}, {@code FOR WINDOW FROM DATE 'a' TO DATE 'b'}. */
    public record TimeClause(String kind, String asOf, String from, String to, String last, int at) {}

    /** An {@code ORDER BY} key. */
    public record Order(Expr expr, boolean desc, int at) {}

    /** An expression. */
    public sealed interface Expr {
        int at();
    }

    /** A dotted name: {@code trades.mtm}, {@code mtm}, {@code x.desk}. */
    public record Column(List<String> parts, int at) implements Expr {
        public Column {
            parts = List.copyOf(parts);
        }

        public String text() {
            return String.join(".", parts);
        }
    }

    /** A literal: a number, a text, {@code TRUE}/{@code FALSE}, {@code NULL}, {@code DATE '...'}, {@code TIMESTAMP '...'}. */
    public record Literal(Object value, String type, int at) implements Expr {}

    /** {@code MEASURE('name')} or {@code MEASURE("name")}: a measure of the model by name or stable id. */
    public record Measure(String name, int at) implements Expr {}

    /** A function call; {@code distinct} for {@code COUNT(DISTINCT x)}; {@code within} the {@code WITHIN GROUP (ORDER BY x)} key; {@code over} a window. */
    public record Call(String name, List<Expr> args, boolean distinct, boolean star, Expr within, Over over, int at) implements Expr {
        public Call {
            args = List.copyOf(args);
        }
    }

    /** {@code OVER (PARTITION BY ... ORDER BY ...)}. */
    public record Over(List<Expr> partitionBy, List<Order> orderBy, int at) {
        public Over {
            partitionBy = List.copyOf(partitionBy);
            orderBy = List.copyOf(orderBy);
        }
    }

    /** A binary operator: comparisons, {@code AND}, {@code OR}, arithmetic, {@code ||}. */
    public record Binary(String op, Expr left, Expr right, int at) implements Expr {}

    /** {@code NOT x}, {@code -x}. */
    public record Unary(String op, Expr operand, int at) implements Expr {}

    /** {@code x [NOT] IN (a, b)}. */
    public record In(Expr value, List<Expr> items, boolean negated, int at) implements Expr {
        public In {
            items = List.copyOf(items);
        }
    }

    /** {@code x [NOT] BETWEEN a AND b}. */
    public record Between(Expr value, Expr low, Expr high, boolean negated, int at) implements Expr {}

    /** {@code x [NOT] LIKE 'p%'}. */
    public record Like(Expr value, Expr pattern, boolean negated, int at) implements Expr {}

    /** {@code x IS [NOT] NULL}. */
    public record IsNull(Expr value, boolean negated, int at) implements Expr {}

    /** {@code CASE [x] WHEN a THEN b ... [ELSE c] END}. */
    public record Case(Expr operand, List<Expr> whens, List<Expr> thens, Expr orElse, int at) implements Expr {
        public Case {
            whens = List.copyOf(whens);
            thens = List.copyOf(thens);
        }
    }

    /** {@code CAST(x AS type)}. */
    public record Cast(Expr value, String type, int at) implements Expr {}

    /** A subquery used as a value ({@code IN (SELECT ...)}, {@code (SELECT ...)}): refused (a semi-join is a relationship of the model). */
    public record SubqueryExpr(Query query, int at) implements Expr {}

    /** Visits every expression of a tree, parents first. */
    public static void walk(Expr e, java.util.function.Consumer<Expr> visit) {
        if (e == null) {
            return;
        }
        visit.accept(e);
        switch (e) {
            case Call c -> {
                c.args().forEach(a -> walk(a, visit));
                walk(c.within(), visit);
                if (c.over() != null) {
                    c.over().partitionBy().forEach(a -> walk(a, visit));
                    c.over().orderBy().forEach(o -> walk(o.expr(), visit));
                }
            }
            case Binary b -> {
                walk(b.left(), visit);
                walk(b.right(), visit);
            }
            case Unary u -> walk(u.operand(), visit);
            case In i -> {
                walk(i.value(), visit);
                i.items().forEach(a -> walk(a, visit));
            }
            case Between b -> {
                walk(b.value(), visit);
                walk(b.low(), visit);
                walk(b.high(), visit);
            }
            case Like l -> {
                walk(l.value(), visit);
                walk(l.pattern(), visit);
            }
            case IsNull n -> walk(n.value(), visit);
            case Case c -> {
                walk(c.operand(), visit);
                c.whens().forEach(a -> walk(a, visit));
                c.thens().forEach(a -> walk(a, visit));
                walk(c.orElse(), visit);
            }
            case Cast c -> walk(c.value(), visit);
            default -> { }
        }
    }

    /** True when the tree holds a node the test accepts. */
    public static boolean any(Expr e, java.util.function.Predicate<Expr> test) {
        boolean[] found = {false};
        walk(e, x -> found[0] |= test.test(x));
        return found[0];
    }
}
