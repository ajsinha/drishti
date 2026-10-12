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
    private TimeWindows.Context time;
    /** While a time function's aggregate is emitted: the date expression and the window the rows must fall in. */
    private String windowDate;
    private TimeWindows.Window window;
    private final Map<String, List<WindowField>> windowFields = new java.util.LinkedHashMap<>();

    /** A field of the query as the SELECT writes it, for {@code running()} to order and partition by. */
    private record WindowField(String part, String sql, boolean masked) {}

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

    private boolean planning;
    /** When set, a measure is read from a column of the joined parts of a several-facts query (null: not a column, so its own expression is written). */
    private java.util.function.Function<String, String> joinedColumn;

    /** Writes measure names as the given function says (a column of an already-aggregated result); the expression of a name it answers null for is written in place. */
    SqlEmitter overJoined(java.util.function.Function<String, String> column) {
        this.joinedColumn = column;
        return this;
    }

    /** An emitter that only finds out which tables an expression touches (the planner's probe): the windows are placeholders and a running total needs no field. */
    SqlEmitter planning() {
        this.planning = true;
        this.time = TimeWindows.Context.weekends(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        return this;
    }

    /** The as-of date, the query's start and the calendar the time-intelligence windows are worked out from. */
    SqlEmitter time(TimeWindows.Context context) {
        this.time = context;
        return this;
    }

    /** Tells {@code running()} about a field of the query: its table.column, date part, SQL text and whether it is masked. */
    void windowField(Resolver.ColRef ref, String sql, boolean masked) {
        windowFields.computeIfAbsent(ref.table() + "." + ref.column(), k -> new ArrayList<>()).add(new WindowField(ref.part(), sql, masked));
        windowFields.computeIfAbsent("*", k -> new ArrayList<>()).add(new WindowField(ref.table() + "." + ref.column() + "." + ref.part(), sql, masked));
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
        if (joinedColumn != null) {
            String column = joinedColumn.apply(name);
            if (column != null) {
                return column;
            }
        }
        Dataset.Measure m = ds.measures().get(name);
        if (m == null) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "no measure '" + name + "' in this dataset");
        }
        if (m.expr() == null) {
            throw new DrishtiException(ErrorCode.DATASET_NOT_BUILT, "measure '" + name + "' is a Python measure (" + m.python() + "); Python measures are run by the console's Python runner, shown by a python visual, never by a query");
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
        if (Node.ANALYTIC.contains(f)) {
            return analytic(c);
        }
        if (Node.TIME.contains(f) || Node.WINDOW.contains(f)) {
            return timeCall(c, inAgg);
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
        if (cond == null && window == null) {
            return call;
        }
        String where = cond == null ? "" : emit(cond, true);
        if (window != null) {
            params.add(window.lo().toString());
            params.add(window.hi().toString());
            String range = "(" + windowDate + " >= CAST(? AS DATE) AND " + windowDate + " <= CAST(? AS DATE))";
            where = where.isEmpty() ? range : where + " AND " + range;
        }
        return call + " FILTER (WHERE " + where + ")";
    }

    /** {@code ytd(sum(x), t.date)}: the aggregate over the rows whose date falls in the window; {@code running(sum(x), t.date)}: a running total along the date field. */
    private String timeCall(Node.Call c, boolean inAgg) {
        String f = c.function();
        Resolver.ColRef col;
        try {
            col = resolver.resolve(((Node.Ref) c.args().get(1)).parts(), null);
        } catch (IllegalArgumentException | ClassCastException e) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, f + "(): " + e.getMessage());
        }
        String type = resolver.column(col.table(), col.column()).type();
        String sql = column(new Resolver.ColRef(col.table(), col.column(), null));
        String date = "timestamp".equals(type) ? "CAST(" + sql + " AS DATE)" : sql;
        if (Node.WINDOW.contains(f)) {
            return running(c, col);
        }
        if (time == null) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, f + "() needs an as-of date and a calendar, which this query was not given");
        }
        String savedDate = windowDate;
        TimeWindows.Window saved = window;
        windowDate = date;
        window = TimeWindows.of(f, time);
        try {
            return emit(c.args().get(0), inAgg);
        } finally {
            windowDate = savedDate;
            window = saved;
        }
    }

    /** A running total: the sum (or minimum, maximum) of the aggregate so far along the date field, within the other fields (a coarser part of the same date restarts it). */
    private String running(Node.Call c, Resolver.ColRef date) {
        Node.Call agg = (Node.Call) c.args().get(0);
        String inner = emit(agg, false);
        sawAggregate = true;
        WindowField order = orderField(date, "a running total");
        if (order == null) {
            return "NULL";
        }
        List<String> partition = partitionOf(order);
        String fn = "count".equals(agg.function()) || "sum".equals(agg.function()) ? "sum" : agg.function();
        String over = fn + "(" + inner + ") OVER (" + (partition.isEmpty() ? "" : "PARTITION BY " + String.join(", ", partition) + " ") + "ORDER BY " + order.sql() + ")";
        return "count".equals(agg.function()) ? "CAST(" + over + " AS BIGINT)" : over;
    }

    /** The finest field of the query on the date/order column, for a window to order by; null while only planning. */
    private WindowField orderField(Resolver.ColRef date, String what) {
        List<WindowField> same = windowFields.getOrDefault(date.table() + "." + date.column(), List.of());
        List<String> rank = List.of("year", "quarter", "month", "week", "day");
        WindowField order = null;
        for (WindowField w : same) {
            if (order == null || (w.part() == null ? rank.size() : rank.indexOf(w.part())) > (order.part() == null ? rank.size() : rank.indexOf(order.part()))) {
                order = w;
            }
        }
        if (order == null && planning) {
            return null;
        }
        if (order == null) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, what + " along " + date.table() + "." + date.column() + " needs that column as a field of the visual (its axis): the window runs along it");
        }
        if (order.masked()) {
            throw new DrishtiException(ErrorCode.QUERY_MASKED, "'" + date.table() + "." + date.column() + "' is masked for you: " + what + " cannot run along it");
        }
        return order;
    }

    /** Every other unmasked field of the query: the groups a window restarts in. */
    private List<String> partitionOf(WindowField order) {
        List<String> partition = new ArrayList<>();
        for (WindowField w : windowFields.getOrDefault("*", List.of())) {
            if (!w.masked() && !w.sql().equals(order.sql())) {
                partition.add(w.sql());
            }
        }
        return partition;
    }

    /** The listed partition columns, each of which must be a field of the query; a masked one is left out, so a window never splits on a hidden value. */
    private List<String> partitionOf(List<Node> cols) {
        List<String> out = new ArrayList<>();
        for (Node n : cols) {
            Resolver.ColRef ref = resolver.resolve(((Node.Ref) n).parts(), null);
            List<WindowField> fs = windowFields.getOrDefault(ref.table() + "." + ref.column(), List.of());
            if (fs.isEmpty()) {
                if (planning) {
                    continue;
                }
                throw new DrishtiException(ErrorCode.QUERY_INVALID, "a window partitioned by " + ref.table() + "." + ref.column() + " needs that column as a field of the visual");
            }
            fs.stream().filter(w -> !w.masked()).forEach(w -> out.add(w.sql()));
        }
        return out;
    }

    /** {@code lag}, {@code lead}, {@code moving_*}, {@code rank}, {@code dense_rank}, {@code percent_rank}: window SQL over the grouped rows (the validator checked the shape). */
    private String analytic(Node.Call c) {
        String f = c.function();
        List<Node> a = c.args();
        sawAggregate = true;
        String inner = emit(a.get(0), false);
        if (Node.ORDERED.contains(f)) {
            int n = (int) ((Node.Num) a.get(1)).value();
            Resolver.ColRef ocol = resolver.resolve(((Node.Ref) a.get(2)).parts(), null);
            WindowField order = orderField(ocol, f + "()");
            if (order == null) {
                return "NULL";
            }
            List<String> partition = a.size() > 3 ? partitionOf(a.subList(3, a.size())) : partitionOf(order);
            String over = (partition.isEmpty() ? "" : "PARTITION BY " + String.join(", ", partition) + " ") + "ORDER BY " + order.sql();
            return switch (f) {
                case "lag", "lead" -> f + "(" + inner + ", " + n + ") OVER (" + over + ")";
                default -> f.substring("moving_".length()) + "(" + inner + ") OVER (" + over + " ROWS BETWEEN " + (n - 1) + " PRECEDING AND CURRENT ROW)";
            };
        }
        boolean desc = a.size() < 2 || !"asc".equalsIgnoreCase(((Node.Str) a.get(1)).value());
        List<String> partition = a.size() > 2 ? partitionOf(a.subList(2, a.size())) : List.of();
        return f + "() OVER (" + (partition.isEmpty() ? "" : "PARTITION BY " + String.join(", ", partition) + " ") + "ORDER BY " + inner + (desc ? " DESC" : " ASC") + " NULLS LAST)";
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
