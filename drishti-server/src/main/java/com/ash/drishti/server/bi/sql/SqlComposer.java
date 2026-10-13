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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A composite Drishti SQL statement (docs/architecture/BI_DESIGN.md section 3.4): {@code WITH} queries, {@code UNION}, subqueries in {@code FROM} and
 * windows over the answer. Every {@code SELECT} that reads the model's tables is a <em>leaf</em>, bound to one model query ({@link SqlBinder}) and answered as
 * the reader (secured and masked); the rest is relational work over those answers (join, union, project, filter, window, sort, limit), printed here as
 * SQL over the leaves' answers and run on the Java tier in an in-memory DuckDB that can read nothing else. So a composite statement can never see more than its
 * leaves. Aggregating answers again is refused (a measure belongs in the model); every literal is a bound parameter. One composer per statement.
 */
final class SqlComposer {

    /** A leaf and the table its answer is loaded as. */
    record LeafUse(String table, SqlBinder.Leaf leaf) {}

    /**
     * The composite: its leaves, the outer SQL over their tables with its parameters, the output columns, and the columns of each table the outer query joins,
     * filters, sorts or partitions on (refused when one is masked for the reader: {@code DRS-9161}).
     */
    record Composite(List<LeafUse> leaves, String sql, List<Object> params, Map<String, Set<String>> sensitive, String shape) {}

    private final SqlBinder binder;
    private final List<LeafUse> leaves = new ArrayList<>();
    private final List<Object> params = new ArrayList<>();
    private final Map<String, String> cteTables = new LinkedHashMap<>();
    private final Map<String, Set<String>> sensitive = new LinkedHashMap<>();
    /** In the select being printed: the name each FROM entry goes by, to its table. */
    private Map<String, String> scope = Map.of();
    private final Set<String> shapes = new LinkedHashSet<>();

    SqlComposer(SqlBinder binder) {
        this.binder = binder;
    }

    /** True when the statement needs more than one model query (or a window over the answer). */
    static boolean composite(SqlAst.Query q) {
        if (!q.with().isEmpty() || !(q.body() instanceof SqlAst.Select s)) {
            return true;
        }
        return s.from().stream().anyMatch(f -> f.subquery() != null) || s.items().stream().anyMatch(i -> i.expr() != null && SqlAst.any(i.expr(), x -> x instanceof SqlAst.Call c
                && c.over() != null));
    }

    Composite compose(SqlAst.Query q) {
        String sql = query(q, true);
        return new Composite(List.copyOf(leaves), sql, List.copyOf(params), sensitive, String.join("+", shapes));
    }

    private String query(SqlAst.Query q, boolean top) {
        for (SqlAst.Cte c : q.with()) {
            if (!top) {
                throw new DsqlException(DsqlException.SHAPE, "a WITH inside a subquery is not Drishti SQL (yet)", "put every WITH query at the top", c.at());
            }
            if (cteTables.containsKey(c.name().toLowerCase(Locale.ROOT))) {
                throw new DsqlException(DsqlException.SHAPE, "the WITH query '" + c.name() + "' is named twice", null, c.at());
            }
            cteTables.put(c.name().toLowerCase(Locale.ROOT), leafTable(c.query(), c.at(), "the WITH query '" + c.name() + "'"));
        }
        shapes.add(q.with().isEmpty() ? "query" : "with");
        StringBuilder b = new StringBuilder(body(q.body()));
        order(q.orderBy(), b);
        limit(q.limit(), q.offset(), b);
        return b.toString();
    }

    /** A query that must be one model query (a WITH query, a subquery in FROM): its answer's table. */
    private String leafTable(SqlAst.Query q, int at, String what) {
        if (!q.with().isEmpty() || !(q.body() instanceof SqlAst.Select s) || !binder.readsModel(s, cteTables.keySet())) {
            throw new DsqlException(DsqlException.SHAPE, what + " is not one SELECT over the model's tables", "make it a plain SELECT ... FROM <alias> ...", at);
        }
        if (s.items().stream().anyMatch(i -> i.expr() != null && SqlAst.any(i.expr(), x -> x instanceof SqlAst.Call c && c.over() != null))) {
            throw new DsqlException(DsqlException.SHAPE, "a window inside " + what + " is not Drishti SQL (yet)", "rank in the outer SELECT", at);
        }
        if (!q.orderBy().isEmpty() || q.limit() != null) {
            throw new DsqlException(DsqlException.SHAPE, "ORDER BY and LIMIT after a UNION inside " + what + " are not Drishti SQL", null, at);
        }
        String table = "t_" + leaves.size();
        leaves.add(new LeafUse(table, binder.leaf(s)));
        return table;
    }

    private String body(SqlAst.Body body) {
        if (body instanceof SqlAst.Union u) {
            shapes.add("union");
            return "(" + body(u.left()) + ") UNION " + (u.all() ? "ALL " : "") + "(" + body(u.right()) + ")";
        }
        SqlAst.Select s = (SqlAst.Select) body;
        boolean model = binder.readsModel(s, cteTables.keySet());
        boolean windows = s.items().stream().anyMatch(i -> i.expr() != null && SqlAst.any(i.expr(), x -> x instanceof SqlAst.Call c && c.over() != null));
        if (model && !windows) {
            String table = "t_" + leaves.size();
            leaves.add(new LeafUse(table, binder.leaf(s)));
            return "SELECT * FROM " + q(table);
        }
        if (model) {
            return windowOverLeaf(s);
        }
        if (s.from().stream().anyMatch(f -> f.subquery() == null && !cteTables.containsKey(f.table().toLowerCase(Locale.ROOT)))) {
            SqlAst.From f = s.from().stream().filter(x -> x.subquery() == null && !cteTables.containsKey(x.table().toLowerCase(Locale.ROOT))).findFirst().orElseThrow();
            throw new DsqlException(DsqlException.SHAPE, "'" + f.table() + "' is a table of the model in a SELECT over WITH queries", "put the model's tables in a WITH query of their own",
                    f.at());
        }
        return outer(s);
    }

    /** {@code SELECT d, MEASURE('m') AS m, RANK() OVER (ORDER BY m) ...}: the leaf without its windows, and the windows over its answer. */
    private String windowOverLeaf(SqlAst.Select s) {
        shapes.add("window");
        List<SqlAst.Item> plain = s.items().stream().filter(i -> !SqlAst.any(i.expr(), x -> x instanceof SqlAst.Call c && c.over() != null)).toList();
        SqlAst.Select stripped = new SqlAst.Select(s.distinct(), plain, s.from(), s.where(), s.groupBy(), s.having(), List.of(), null, null, s.time(), s.at());
        SqlBinder.Leaf leaf = binder.leaf(stripped);
        String table = "t_" + leaves.size();
        leaves.add(new LeafUse(table, leaf));
        Map<String, String> columns = new LinkedHashMap<>();                     // what the window may name, to the leaf's output column
        for (int i = 0; i < plain.size(); i++) {
            SqlBinder.Out o = leaf.columns().get(i);
            columns.put(o.name().toLowerCase(Locale.ROOT), o.name());
            columns.put(SqlBinder.text(plain.get(i).expr()).toLowerCase(Locale.ROOT), o.name());
            columns.put(o.source().toLowerCase(Locale.ROOT), o.name());
            if (plain.get(i).expr() instanceof SqlAst.Measure m) {
                columns.put(("MEASURE('" + m.name() + "')").toLowerCase(Locale.ROOT), o.name());
            }
        }
        scope = Map.of(table, table);
        List<String> items = new ArrayList<>();
        int k = 0;
        for (SqlAst.Item it : s.items()) {
            if (SqlAst.any(it.expr(), x -> x instanceof SqlAst.Call c && c.over() != null)) {
                items.add(leafExpr(it.expr(), columns, table) + " AS " + q(it.alias() != null ? it.alias() : SqlBinder.text(it.expr())));
            } else {
                items.add(q(table) + "." + q(leaf.columns().get(k++).name()));
            }
        }
        StringBuilder b = new StringBuilder("SELECT " + String.join(", ", items) + " FROM " + q(table));
        if (!s.orderBy().isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (SqlAst.Order o : s.orderBy()) {
                String key = o.expr() instanceof SqlAst.Column c && c.parts().size() == 1 && s.items().stream().anyMatch(i -> c.text().equalsIgnoreCase(i.alias()))
                        ? q(c.text()) : leafExpr(o.expr(), columns, table);
                keys.add(key + (o.desc() ? " DESC" : " ASC") + " NULLS LAST");
            }
            b.append(" ORDER BY ").append(String.join(", ", keys));
        }
        limit(s.limit(), s.offset(), b);
        return b.toString();
    }

    /** An expression over a leaf's answer: names and measures become its columns; a window's partition and order are sensitive (masked: refused). */
    private String leafExpr(SqlAst.Expr e, Map<String, String> columns, String table) {
        String col = columns.get(SqlBinder.text(e).toLowerCase(Locale.ROOT));
        if (col != null) {
            return q(table) + "." + q(col);
        }
        return switch (e) {
            case SqlAst.Call c when c.over() != null -> {
                List<String> args = c.args().stream().map(a -> leafExpr(a, columns, table)).toList();
                List<String> part = new ArrayList<>();
                for (SqlAst.Expr p : c.over().partitionBy()) {
                    part.add(leafExpr(p, columns, table));
                    mark(table, columns.get(SqlBinder.text(p).toLowerCase(Locale.ROOT)));
                }
                List<String> order = new ArrayList<>();
                for (SqlAst.Order o : c.over().orderBy()) {
                    order.add(leafExpr(o.expr(), columns, table) + (o.desc() ? " DESC" : " ASC") + " NULLS LAST");
                    mark(table, columns.get(SqlBinder.text(o.expr()).toLowerCase(Locale.ROOT)));
                }
                yield windowCall(c, args, part, order);
            }
            case SqlAst.Literal l -> literal(l);
            case SqlAst.Binary b -> "(" + leafExpr(b.left(), columns, table) + " " + sqlOp(b.op()) + " " + leafExpr(b.right(), columns, table) + ")";
            case SqlAst.Unary u -> "NOT".equals(u.op()) ? "(NOT " + leafExpr(u.operand(), columns, table) + ")" : "(-" + leafExpr(u.operand(), columns, table) + ")";
            case SqlAst.Call c when SqlFunctions.SCALARS.contains(c.name()) -> c.name() + "(" + String.join(", ", c.args().stream().map(a -> leafExpr(a, columns, table)).toList()) + ")";
            default -> throw new DsqlException(DsqlException.WINDOW, "the window names '" + SqlBinder.text(e) + "', which the SELECT does not select", "select it, and order the window by its name",
                    e.at());
        };
    }

    private String windowCall(SqlAst.Call c, List<String> args, List<String> part, List<String> order) {
        if (!SqlFunctions.WINDOWS.contains(c.name()) && !SqlFunctions.AGGREGATES.contains(c.name())) {
            throw SqlFunctions.refused(c.name(), c.at());
        }
        StringBuilder b = new StringBuilder(c.name()).append('(').append(c.star() ? "*" : String.join(", ", args)).append(") OVER (");
        if (!part.isEmpty()) {
            b.append("PARTITION BY ").append(String.join(", ", part)).append(' ');
        }
        if (!order.isEmpty()) {
            b.append("ORDER BY ").append(String.join(", ", order));
        }
        return b.append(')').toString();
    }

    // ---- a select over answers ---------------------------------------------------------------------------------------

    private String outer(SqlAst.Select s) {
        shapes.add("join");
        if (!s.groupBy().isEmpty() || s.having() != null || s.items().stream().anyMatch(i -> i.expr() != null && SqlAst.any(i.expr(),
                x -> x instanceof SqlAst.Call c && SqlFunctions.AGGREGATES.contains(c.name()) && c.over() == null))) {
            throw new DsqlException(DsqlException.SHAPE, "aggregating answers again (GROUP BY or an aggregate over WITH queries) is not Drishti SQL",
                    "aggregate in the model: a measure, or a SELECT with GROUP BY over the model's tables", s.at());
        }
        if (s.time() != null) {
            throw new DsqlException(DsqlException.TIME, "the time clause belongs to a SELECT over the model's tables", null, s.time().at());
        }
        Map<String, String> names = new LinkedHashMap<>();
        StringBuilder from = new StringBuilder();
        for (SqlAst.From f : s.from()) {
            String table = f.subquery() != null ? leafTable(f.subquery(), f.at(), "the subquery " + f.name()) : cteTables.get(f.table().toLowerCase(Locale.ROOT));
            names.put(f.name().toLowerCase(Locale.ROOT), table);
            if (f.join() == null) {
                from.append(q(table)).append(" AS ").append(q(f.name()));
            } else {
                if (f.on() == null) {
                    throw new DsqlException(DsqlException.JOIN, "a join of two answers needs ON (their shared columns)", "JOIN " + f.name() + " ON a.x = " + f.name() + ".x", f.at());
                }
                from.append("left".equals(f.join()) ? " LEFT JOIN " : " JOIN ").append(q(table)).append(" AS ").append(q(f.name()));
                scope = names;
                from.append(" ON ").append(expr(f.on(), true));
            }
        }
        scope = names;
        List<String> items = new ArrayList<>();
        for (SqlAst.Item it : s.items()) {
            if (it.star()) {
                items.add("*");
                continue;
            }
            items.add(expr(it.expr(), false) + (it.alias() == null ? "" : " AS " + q(it.alias())));
        }
        StringBuilder b = new StringBuilder("SELECT ").append(s.distinct() ? "DISTINCT " : "").append(String.join(", ", items)).append(" FROM ").append(from);
        if (s.where() != null) {
            b.append(" WHERE ").append(expr(s.where(), true));
        }
        order(s.orderBy(), b);
        limit(s.limit(), s.offset(), b);
        return b.toString();
    }

    private void order(List<SqlAst.Order> order, StringBuilder b) {
        if (order.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (SqlAst.Order o : order) {
            keys.add((o.expr() instanceof SqlAst.Literal l && l.value() instanceof Long n ? Long.toString(n) : expr(o.expr(), true)) + (o.desc() ? " DESC" : " ASC") + " NULLS LAST");
        }
        b.append(" ORDER BY ").append(String.join(", ", keys));
    }

    private static void limit(Long limit, Long offset, StringBuilder b) {
        if (limit != null) {
            b.append(" LIMIT ").append(limit);
        }
        if (offset != null) {
            b.append(" OFFSET ").append(offset);
        }
    }

    /** An expression over the answers' columns; {@code sensitive}: it joins, filters or sorts, so a masked column is refused. */
    private String expr(SqlAst.Expr e, boolean sensitiveUse) {
        return switch (e) {
            case SqlAst.Column c -> {
                if (sensitiveUse) {
                    markColumn(c);
                }
                yield c.parts().stream().map(SqlComposer::q).reduce((x, y) -> x + "." + y).orElseThrow();
            }
            case SqlAst.Literal l -> literal(l);
            case SqlAst.Measure m -> throw new DsqlException(DsqlException.MEASURE, "MEASURE('" + m.name() + "') over WITH queries: a measure is asked in a SELECT over the model's tables",
                    "select it in the WITH query, then name its column here", m.at());
            case SqlAst.Binary b -> "(" + expr(b.left(), sensitiveUse) + " " + sqlOp(b.op()) + " " + expr(b.right(), sensitiveUse) + ")";
            case SqlAst.Unary u -> "NOT".equals(u.op()) ? "(NOT " + expr(u.operand(), sensitiveUse) + ")" : "(-" + expr(u.operand(), sensitiveUse) + ")";
            case SqlAst.In i -> "(" + expr(i.value(), sensitiveUse) + (i.negated() ? " NOT IN (" : " IN (") + String.join(", ", i.items().stream().map(x -> {
                if (x instanceof SqlAst.SubqueryExpr s) {
                    throw new DsqlException(DsqlException.SHAPE, "IN (SELECT ...) is not Drishti SQL", "JOIN the WITH query", s.at());
                }
                return expr(x, sensitiveUse);
            }).toList()) + "))";
            case SqlAst.Between b -> "(" + expr(b.value(), sensitiveUse) + (b.negated() ? " NOT BETWEEN " : " BETWEEN ") + expr(b.low(), sensitiveUse) + " AND "
                    + expr(b.high(), sensitiveUse) + ")";
            case SqlAst.Like l -> "(" + expr(l.value(), sensitiveUse) + (l.negated() ? " NOT LIKE " : " LIKE ") + expr(l.pattern(), sensitiveUse) + ")";
            case SqlAst.IsNull n -> "(" + expr(n.value(), sensitiveUse) + (n.negated() ? " IS NOT NULL)" : " IS NULL)");
            case SqlAst.Case c -> {
                StringBuilder b = new StringBuilder("(CASE");
                if (c.operand() != null) {
                    b.append(' ').append(expr(c.operand(), sensitiveUse));
                }
                for (int i = 0; i < c.whens().size(); i++) {
                    b.append(" WHEN ").append(expr(c.whens().get(i), sensitiveUse)).append(" THEN ").append(expr(c.thens().get(i), sensitiveUse));
                }
                if (c.orElse() != null) {
                    b.append(" ELSE ").append(expr(c.orElse(), sensitiveUse));
                }
                yield b.append(" END)").toString();
            }
            case SqlAst.Cast c -> "CAST(" + expr(c.value(), sensitiveUse) + " AS " + castType(c) + ")";
            case SqlAst.Call c -> call(c, sensitiveUse);
            case SqlAst.SubqueryExpr s -> throw new DsqlException(DsqlException.SHAPE, "a subquery as a value is not Drishti SQL", null, s.at());
        };
    }

    private String call(SqlAst.Call c, boolean sensitiveUse) {
        if (c.over() != null) {
            List<String> args = c.args().stream().map(a -> expr(a, false)).toList();
            List<String> part = c.over().partitionBy().stream().map(p -> expr(p, true)).toList();
            List<String> order = c.over().orderBy().stream().map(o -> expr(o.expr(), true) + (o.desc() ? " DESC" : " ASC") + " NULLS LAST").toList();
            return windowCall(c, args, part, order);
        }
        if (!SqlFunctions.SCALARS.contains(c.name())) {
            throw SqlFunctions.refused(c.name(), c.at());
        }
        return c.name() + "(" + String.join(", ", c.args().stream().map(a -> expr(a, sensitiveUse)).toList()) + ")";
    }

    private static String castType(SqlAst.Cast c) {
        return switch (c.type()) {
            case "VARCHAR", "TEXT", "STRING", "CHAR" -> "VARCHAR";
            case "INTEGER", "INT", "BIGINT", "SMALLINT" -> "BIGINT";
            case "DOUBLE", "DECIMAL", "NUMERIC", "FLOAT", "REAL" -> "DOUBLE";
            case "DATE" -> "DATE";
            case "TIMESTAMP" -> "TIMESTAMP";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            default -> throw new DsqlException(DsqlException.SHAPE, "CAST to " + c.type() + " is not Drishti SQL", "VARCHAR, INTEGER, DOUBLE, DECIMAL, DATE, TIMESTAMP, BOOLEAN", c.at());
        };
    }

    private String literal(SqlAst.Literal l) {
        return switch (l.type()) {
            case "null" -> "NULL";
            case "boolean" -> Boolean.TRUE.equals(l.value()) ? "TRUE" : "FALSE";
            case "date" -> {
                params.add(String.valueOf(l.value()));
                yield "CAST(? AS DATE)";
            }
            case "timestamp" -> {
                params.add(String.valueOf(l.value()));
                yield "CAST(? AS TIMESTAMP)";
            }
            default -> {
                params.add(l.value());
                yield "?";
            }
        };
    }

    private static String sqlOp(String op) {
        return switch (op) {
            case "AND", "OR", "=", "<>", "<", "<=", ">", ">=", "+", "-", "*", "/", "%", "||" -> op;
            default -> throw new DsqlException(DsqlException.SYNTAX, "unknown operator " + op, null, 0);
        };
    }

    private void markColumn(SqlAst.Column c) {
        if (c.parts().size() >= 2) {
            String t = scope.get(c.parts().get(0).toLowerCase(Locale.ROOT));
            if (t != null) {
                mark(t, c.parts().get(1));
            }
        } else {
            scope.values().forEach(t -> mark(t, c.parts().get(0)));
        }
    }

    private void mark(String table, String column) {
        if (column != null) {
            sensitive.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(column);
        }
    }

    static String q(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
