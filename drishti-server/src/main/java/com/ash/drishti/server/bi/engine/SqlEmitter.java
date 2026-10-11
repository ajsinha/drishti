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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.ExprParser;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.dataset.Rollups;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an expression tree into SQL text with <em>bound parameters</em>. Names reach the SQL only after {@link Resolver} found them in the
 * dataset and {@link Dialect#quote} quoted them; literals (numbers, text, the reader's attributes) become {@code ?} and are added to
 * {@link #params()} in the order they appear in the text. One emitter writes one clause (select list, from, where, having) so the caller
 * can put the parameter lists together in SQL order.
 */
final class SqlEmitter {

    private final Dataset ds;
    private final Resolver resolver;
    private final Dialect dialect;
    private final Map<String, List<Object>> user;
    private final String base;
    private final Map<String, String> components;
    private final Set<String> tablesUsed = new HashSet<>();
    private final List<Object> params = new ArrayList<>();
    private String current;
    private boolean sawAggregate;
    private final Set<String> trail = new HashSet<>();

    /**
     * @param base the table the query's rows come from (the meaning of {@code count(table)})
     * @param components for a rollup: the column holding each stored aggregate by canonical key; null otherwise
     * @param user the reader's attributes (for row rules)
     */
    SqlEmitter(Dataset ds, Dialect dialect, String base, Map<String, String> components, Map<String, List<Object>> user) {
        this.ds = ds;
        this.resolver = new Resolver(ds);
        this.dialect = dialect;
        this.base = base;
        this.components = components;
        this.user = user == null ? Map.of() : user;
    }

    List<Object> params() {
        return params;
    }

    Set<String> tables() {
        return tablesUsed;
    }

    boolean sawAggregate() {
        return sawAggregate;
    }

    /** Emits a calculated column's expression, with unqualified names meaning columns of {@code table}'s own row (alias {@code t}). */
    String calculated(Dataset.Table table, String expr) {
        current = table.name();
        try {
            return emit(ExprParser.parse(expr), false);
        } finally {
            current = null;
        }
    }

    String emit(Node n, boolean inAgg) {
        return switch (n) {
            case Node.Num x -> {
                params.add(x.integral() && Math.abs(x.value()) < 9e15 ? (Object) (long) x.value() : (Object) x.value());
                yield "?";
            }
            case Node.Str s -> {
                params.add(s.value());
                yield "?";
            }
            case Node.Bool b -> b.value() ? "TRUE" : "FALSE";
            case Node.Null x -> "NULL";
            case Node.Ref r -> ref(r, false);
            case Node.UserRef u -> {
                List<Object> v = user.getOrDefault(u.attribute(), List.of());
                params.add(v.isEmpty() ? "" : v.get(0));
                yield "?";
            }
            case Node.MeasureRef m -> measure(m.name());
            case Node.Call c -> call(c, inAgg);
            case Node.Bin b -> bin(b, inAgg);
            case Node.Un u -> "-".equals(u.op()) ? "(-" + emit(u.operand(), inAgg) + ")" : "(NOT " + emit(u.operand(), inAgg) + ")";
            case Node.In i -> in(i, inAgg);
            case Node.Cond c -> "(CASE WHEN " + emit(c.test(), inAgg) + " THEN " + emit(c.yes(), inAgg) + " ELSE " + emit(c.no(), inAgg) + " END)";
        };
    }

    /** The SQL of a column reference: {@code "trades"."notional"}, or a date part of it. */
    String column(Resolver.ColRef c) {
        tablesUsed.add(c.table());
        String alias = current != null ? "t" : Dialect.quote(c.table());
        String sql = alias + "." + Dialect.quote(c.column());
        return c.part() == null ? sql : dialect.part(c.part(), sql);
    }

    private String ref(Node.Ref r, boolean countRows) {
        Resolver.ColRef c;
        try {
            c = resolver.resolve(r.parts(), current);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, e.getMessage());
        }
        if (c.column() == null) {
            tablesUsed.add(c.table());
            return countRows ? "*" : Dialect.quote(c.table()) + "." + Dialect.quote(Dataset.ID);
        }
        return column(c);
    }

    private String measure(String name) {
        Dataset.Measure m = ds.measures().get(name);
        if (m == null) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "no measure '" + name + "' in this dataset");
        }
        if (m.expr() == null) {
            throw new DrishtiException(ErrorCode.DATASET_NOT_BUILT, "measure '" + name + "' is a Python measure (" + m.python() + "); Python measures are stored and not run yet");
        }
        if (!trail.add(name)) {
            throw new DrishtiException(ErrorCode.DATASET_EXPRESSION, "measures refer to each other in a circle at '" + name + "'");
        }
        try {
            return "(" + emit(ExprParser.parse(m.expr()), false) + ")";
        } finally {
            trail.remove(name);
        }
    }

    private String call(Node.Call c, boolean inAgg) {
        String f = c.function();
        List<Node> a = c.args();
        if (Node.AGGREGATES.contains(f)) {
            sawAggregate = true;
            return aggregate(c);
        }
        return switch (f) {
            case "abs" -> "abs(" + emit(a.get(0), inAgg) + ")";
            case "upper" -> "upper(" + emit(a.get(0), inAgg) + ")";
            case "lower" -> "lower(" + emit(a.get(0), inAgg) + ")";
            case "length" -> "length(" + emit(a.get(0), inAgg) + ")";
            case "round" -> a.size() == 1 ? "round(" + emit(a.get(0), inAgg) + ")" : "round(" + emit(a.get(0), inAgg) + ", " + emit(a.get(1), inAgg) + ")";
            case "coalesce" -> "coalesce(" + String.join(", ", a.stream().map(x -> emit(x, inAgg)).toList()) + ")";
            case "year", "quarter", "month", "day" -> dialect.part(f, emit(a.get(0), inAgg));
            case "if" -> "(CASE WHEN " + emit(a.get(0), inAgg) + " THEN " + emit(a.get(1), inAgg) + " ELSE " + emit(a.get(2), inAgg) + " END)";
            case "contains" -> "(position(lower(" + emit(a.get(1), inAgg) + ") IN lower(" + emit(a.get(0), inAgg) + ")) > 0)";
            case "startsWith" -> "starts_with(lower(" + emit(a.get(0), inAgg) + "), lower(" + emit(a.get(1), inAgg) + "))";
            default -> throw new DrishtiException(ErrorCode.DATASET_NOT_BUILT, "'" + f + "' is not built yet");
        };
    }

    private String aggregate(Node.Call c) {
        String f = c.function();
        List<Node> a = c.args();
        if ("ratio".equals(f)) {
            return "(CAST(" + emit(a.get(0), false) + " AS " + dialect.doubleType() + ") / NULLIF(" + emit(a.get(1), false) + ", 0))";
        }
        if (components != null) {
            return reaggregate(c);
        }
        boolean percentile = "percentile".equals(f);
        Node arg = a.get(0);
        Node cond = percentile ? (a.size() > 2 ? a.get(2) : null) : (a.size() > 1 ? a.get(1) : null);
        boolean rowCount = "count".equals(f) && arg instanceof Node.Ref r && r.parts().size() == 1 && ds.tables().containsKey(r.parts().get(0));
        String inner = rowCount ? "*" : emit(arg, true);
        if (rowCount) {
            tablesUsed.add(((Node.Ref) arg).parts().get(0));
        }
        String call = switch (f) {
            case "sum" -> "sum(" + inner + ")";
            case "avg" -> "avg(" + inner + ")";
            case "min" -> "min(" + inner + ")";
            case "max" -> "max(" + inner + ")";
            case "count" -> "count(" + inner + ")";
            case "distinct" -> "count(DISTINCT " + inner + ")";
            case "percentile" -> dialect.percentile(inner, Double.toString(((Node.Num) a.get(1)).value()));
            default -> throw new DrishtiException(ErrorCode.QUERY_INVALID, "unknown aggregate " + f);
        };
        return cond == null ? call : call + " FILTER (WHERE " + emit(cond, true) + ")";
    }

    /** Answers an aggregate from a rollup's stored components: a sum of sums, a sum of counts, a minimum of minimums. */
    private String reaggregate(Node.Call c) {
        String f = c.function();
        Node arg = c.args().get(0);
        switch (f) {
            case "avg" -> {
                String s = components.get(Rollups.key(new Node.Call("sum", c.args())));
                String n = components.get(Rollups.key(new Node.Call("count", c.args())));
                return "(CAST(sum(" + s + ") AS " + dialect.doubleType() + ") / NULLIF(sum(" + n + "), 0))";
            }
            case "sum" -> {
                return "sum(" + components.get(Rollups.key(c)) + ")";
            }
            case "count" -> {
                return "CAST(coalesce(sum(" + components.get(Rollups.key(c)) + "), 0) AS BIGINT)";       // a count over no rows is 0, as count(*) is
            }
            case "min", "max" -> {
                return f + "(" + components.get(Rollups.key(c)) + ")";
            }
            default -> throw new DrishtiException(ErrorCode.QUERY_INVALID, f + "(" + Rollups.key(c) + ") cannot be answered from a rollup");
        }
    }

    private String bin(Node.Bin b, boolean inAgg) {
        String op = switch (b.op()) {
            case "==" -> "=";
            case "!=" -> "<>";
            case "&&" -> "AND";
            case "||" -> "OR";
            default -> b.op();
        };
        if (("=".equals(op) || "<>".equals(op)) && (b.right() instanceof Node.Null || b.left() instanceof Node.Null)) {
            Node other = b.right() instanceof Node.Null ? b.left() : b.right();
            return "(" + emit(other, inAgg) + ("=".equals(op) ? " IS NULL)" : " IS NOT NULL)");
        }
        if ("/".equals(op)) {
            return "(CAST(" + emit(b.left(), inAgg) + " AS " + dialect.doubleType() + ") / NULLIF(" + emit(b.right(), inAgg) + ", 0))";
        }
        String l = emit(b.left(), inAgg);
        return "(" + l + " " + op + " " + emit(b.right(), inAgg) + ")";
    }

    private String in(Node.In i, boolean inAgg) {
        String value = emit(i.value(), inAgg);
        List<String> parts = new ArrayList<>();
        for (Node item : i.items()) {
            if (item instanceof Node.UserRef u) {
                for (Object v : user.getOrDefault(u.attribute(), List.of())) {
                    params.add(v);
                    parts.add("?");
                }
            } else {
                parts.add(emit(item, inAgg));
            }
        }
        if (parts.isEmpty()) {
            return i.negated() ? "TRUE" : "FALSE";
        }
        return "(" + value + (i.negated() ? " NOT IN (" : " IN (") + String.join(", ", parts) + "))";
    }
}
