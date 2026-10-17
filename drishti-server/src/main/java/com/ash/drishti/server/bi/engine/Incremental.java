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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.expr.DelPrinter;
import com.ash.drishti.server.bi.expr.Expressions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Incremental maintenance of a grouped answer over a live table (docs/architecture/BI_DESIGN.md section 4.6). {@link #classify} sorts a question by the rule
 * that keeps it current when the live table changes:
 * <ul>
 * <li>{@link Kind#DIFFERENCE}: every measure is made of {@code sum}, {@code count} and {@code avg} (as sum and count) of the changed table's own columns,
 * combined by arithmetic; the fields, the query's filters and the reader's row rules on that table are worked out per changed row ({@link RowEval}), and no
 * dimension's rule reaches the table for this reader: each changed row's old contribution is taken away from its group and the new one added;</li>
 * <li>{@link Kind#TOUCHED}: the fields are the changed table's columns, but a measure needs its group's rows ({@code min}, {@code max}, {@code distinct},
 * {@code percentile}) or a filter or rule cannot be worked out per row: the touched groups are asked again (the engine applies security);</li>
 * <li>{@link Kind#RECOMPUTE}: a filter context or a field of another table: the question is asked again on each change;</li>
 * <li>{@link Kind#TICK}: windows, time intelligence, Python: asked again on the throttled tick.</li>
 * </ul>
 * A {@link Kind#DIFFERENCE} state starts from one engine answer of the components (row count, and per aggregate its sum and non-null count, as the reader) and
 * then needs no query. Not thread-safe: the standing query that owns it serialises its use.
 */
public final class Incremental {

    public enum Kind {
        DIFFERENCE, TOUCHED, RECOMPUTE, TICK
    }

    /** How a question is maintained, and why (for explain and the status). */
    public record Shape(Kind kind, String reason) {}

    private static final Set<String> BY_DIFFERENCE = Set.of("sum", "count", "avg");
    private static final Set<String> BY_GROUP = Set.of("min", "max", "distinct", "percentile");
    private static final Set<String> ROW_FUNCTIONS = Set.of("upper", "lower", "length", "abs", "coalesce", "contains", "startsWith", "endswith", "year", "quarter", "month",
            "day", "week", "date");
    private static final Set<String> FILTER_OPS = Set.of("eq", "ne", "in", "not-in", "lt", "le", "gt", "ge", "between", "is-null", "not-null", "contains", "starts-with");
    private static final String ROWS = "__inc_n";

    /** One aggregate the measures are made of: its function, the value it adds up and its own condition. */
    private record Leaf(String fn, Node arg, Node cond, boolean rowCount, boolean integral) {}

    private final Dataset ds;
    private final QueryRequest q;
    private final String table;
    private final List<Leaf> leaves = new ArrayList<>();
    private final Map<String, Node> trees = new LinkedHashMap<>();
    private final Node rule;
    private final boolean denied;
    private final SqlCompiler.Reader reader;
    private final Map<List<Object>, double[]> groups = new HashMap<>();

    private Incremental(Dataset ds, QueryRequest q, String table, SqlCompiler.Reader reader) {
        this.ds = ds;
        this.q = q;
        this.table = table;
        this.reader = reader;
        TableRules.Bound b = ds.security().perTable() ? TableRules.bind(ds, Set.of(table), reader) : new TableRules.Bound(List.of(), false);
        this.rule = b.rules().isEmpty() ? null : b.rules().get(0);
        this.denied = b.denied();
        for (String m : q.measures()) {
            trees.put(m, collect(inline(Expressions.parse(ds.measures().get(m).expr()), new LinkedHashSet<>())));
        }
    }

    // ---- classification ---------------------------------------------------------------------------------------------

    /** How the question {@code q} over {@code ds} is kept current when the live table {@code table} changes, for this reader. */
    public static Shape classify(Dataset ds, QueryRequest q, SqlCompiler.Reader reader, String table, boolean extra) {
        Kind worst = Kind.DIFFERENCE;
        String why = "sum and count by difference";
        Resolver r = new Resolver(ds);
        for (String m : q.measures()) {
            Dataset.Measure def = ds.measures().get(m);
            if (def == null || def.expr() == null) {
                return new Shape(Kind.TICK, "a Python measure: recomputed on the tick");
            }
            Shape s = measureShape(ds, Expressions.parse(def.expr()), table, new LinkedHashSet<>());
            if (s.kind().ordinal() > worst.ordinal()) {
                worst = s.kind();
                why = s.reason();
            }
            if (reader.masks() != null && new SqlCompiler(ds, Dialect.DUCKDB).measureMasked(m, reader.masks())) {
                return new Shape(Kind.RECOMPUTE, "a masked measure");
            }
        }
        if (worst.ordinal() >= Kind.RECOMPUTE.ordinal()) {
            return new Shape(worst, why);
        }
        for (String f : q.fields()) {
            Resolver.ColRef c;
            try {
                c = r.resolve(List.of(f.split("\\.", -1)), null);
            } catch (IllegalArgumentException e) {
                return new Shape(Kind.RECOMPUTE, "a field it cannot resolve");
            }
            if (!table.equals(c.table()) || c.column() == null) {
                return new Shape(Kind.RECOMPUTE, "a field of another table (" + f + "): asked again on each change");
            }
            if (reader.masks() != null && reader.masks().masked(c.table(), c.column())) {
                return new Shape(Kind.RECOMPUTE, "a masked field");
            }
            Dataset.Column col = r.column(c.table(), c.column());
            if (col != null && "timestamp".equals(col.type())) {
                return new Shape(Kind.RECOMPUTE, "a timestamp field");
            }
        }
        if (extra) {
            return new Shape(Kind.TOUCHED, "a filter on a measure or totals: the touched groups are asked again");
        }
        if (worst == Kind.TOUCHED) {
            return new Shape(worst, why);
        }
        for (QueryRequest.Filter f : q.filters()) {
            if (!FILTER_OPS.contains(f.op()) || ds.measures().containsKey(f.field())) {
                return new Shape(Kind.TOUCHED, "a filter worked out by the engine");
            }
            try {
                Resolver.ColRef c = r.resolve(List.of(f.field().split("\\.", -1)), null);
                if (!table.equals(c.table())) {
                    return new Shape(Kind.TOUCHED, "a filter on another table: the touched groups are asked again");
                }
            } catch (IllegalArgumentException e) {
                return new Shape(Kind.TOUCHED, "a filter it cannot resolve");
            }
        }
        if (ds.security().perTable()) {
            if (!TableRules.reached(ds, Set.of(table), reader).isEmpty()) {
                return new Shape(Kind.TOUCHED, "a dimension's row rule reaches the table: the touched groups are asked again");
            }
            TableRules.Bound b = TableRules.bind(ds, Set.of(table), reader);
            if (!b.rules().isEmpty() && !rowEvaluable(b.rules().get(0), table)) {
                return new Shape(Kind.TOUCHED, "a row rule worked out by the engine");
            }
        } else if (reader.enforceRows() && !ds.security().rows().isEmpty()) {
            return new Shape(Kind.TOUCHED, "the dataset's row rules: the touched groups are asked again");
        }
        return new Shape(Kind.DIFFERENCE, why);
    }

    private static Shape measureShape(Dataset ds, Node n, String table, Set<String> seen) {
        Kind[] worst = {Kind.DIFFERENCE};
        String[] why = {"sum and count by difference"};
        java.util.function.BiConsumer<Kind, String> raise = (k, w) -> {
            if (k.ordinal() > worst[0].ordinal()) {
                worst[0] = k;
                why[0] = w;
            }
        };
        walkAggregates(n, ds, seen, (fn, c) -> {
            if ("python".equals(fn)) {
                raise.accept(Kind.TICK, "a Python measure: recomputed on the tick");
            } else if (Node.TIME.contains(fn) || Node.isWindow(fn)) {
                raise.accept(Kind.TICK, fn + "(): recomputed on the tick");
            } else if (Node.CONTEXT.contains(fn) || Node.SCALARS_B.contains(fn)) {
                raise.accept(Kind.RECOMPUTE, fn + "(): the touched group and its coarser groups move; asked again on each change");
            } else if (BY_GROUP.contains(fn)) {
                raise.accept(Kind.TOUCHED, fn + "(): the touched groups are asked again");
            } else if (BY_DIFFERENCE.contains(fn)) {
                Node arg = c.args().get(0);
                boolean rowCount = "count".equals(fn) && arg instanceof Node.Ref r && r.parts().size() == 1 && ds.tables().containsKey(r.parts().get(0));
                if (rowCount && !r(arg).parts().get(0).equals(table) || !rowCount && !rowEvaluable(arg, table)) {
                    raise.accept(Kind.RECOMPUTE, fn + "() of another table");
                }
                if (c.args().size() > 1 && !rowEvaluable(c.args().get(1), table)) {
                    raise.accept(Kind.TOUCHED, fn + "(where=) worked out by the engine");
                }
            }
        });
        return new Shape(worst[0], why[0]);
    }

    private static Node.Ref r(Node n) {
        return (Node.Ref) n;
    }

    /** Visits each aggregate-level call of a measure (through the measures it names), and fails over to TICK for a shape it does not know. */
    private static void walkAggregates(Node n, Dataset ds, Set<String> seen, BiFunctionVisitor v) {
        switch (n) {
            case Node.MeasureRef m -> {
                Dataset.Measure def = ds.measures().get(m.name());
                if (def == null || def.expr() == null) {
                    v.visit("python", null);
                } else if (seen.add(m.name())) {
                    walkAggregates(Expressions.parse(def.expr()), ds, seen, v);
                }
            }
            case Node.Call c -> {
                String fn = c.function();
                if (Node.AGGREGATES.contains(fn) && !"ratio".equals(fn) || Node.TIME.contains(fn) || Node.isWindow(fn) || Node.CONTEXT.contains(fn)
                        || Node.SCALARS_B.contains(fn)) {
                    v.visit(fn, c);
                } else {
                    c.args().forEach(a -> walkAggregates(a, ds, seen, v));
                }
            }
            case Node.Bin b -> {
                walkAggregates(b.left(), ds, seen, v);
                walkAggregates(b.right(), ds, seen, v);
            }
            case Node.Un u -> walkAggregates(u.operand(), ds, seen, v);
            case Node.Cond c -> {
                walkAggregates(c.yes(), ds, seen, v);
                walkAggregates(c.no(), ds, seen, v);
                walkAggregates(c.test(), ds, seen, v);
            }
            default -> { }
        }
    }

    @FunctionalInterface
    private interface BiFunctionVisitor {
        void visit(String fn, Node.Call c);
    }

    /** True when the expression reads only the table's own columns and functions {@link RowEval} knows. */
    static boolean rowEvaluable(Node n, String table) {
        return !Node.anyMatch(n, x -> x instanceof Node.Ref r && r.parts().size() >= 2 && !r.parts().get(0).equals(table) && !"param".equals(r.parts().get(0))
                || x instanceof Node.Call c && !ROW_FUNCTIONS.contains(c.function()) || x instanceof Node.MeasureRef);
    }

    // ---- the state ---------------------------------------------------------------------------------------------------

    /**
     * Starts a {@link Kind#DIFFERENCE} state from the engine: {@code run} asks the components (the row count and each aggregate's sum and count per group) as the
     * reader, on a copy of the dataset with those measures.
     */
    public static Incremental start(Dataset ds, QueryRequest q, SqlCompiler.Reader reader, String table, BiFunction<Dataset, QueryRequest, QueryService.Result> run) {
        Incremental inc = new Incremental(ds, q, table, reader);
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>(ds.measures());
        List<String> asked = new ArrayList<>();
        measures.put(ROWS, new Dataset.Measure(ROWS, "count(" + table + ")", null, "incremental row count", null));
        asked.add(ROWS);
        for (int i = 0; i < inc.leaves.size(); i++) {
            Leaf l = inc.leaves.get(i);
            String where = l.cond() == null ? "" : ", where=" + DelPrinter.print(l.cond());
            String s = "__inc_s" + i;
            String c = "__inc_c" + i;
            String arg = l.rowCount() ? table : DelPrinter.print(l.arg());
            measures.put(c, new Dataset.Measure(c, "count(" + arg + where + ")", null, "incremental count", null));
            asked.add(c);
            if (!l.rowCount() && !"count".equals(l.fn())) {
                measures.put(s, new Dataset.Measure(s, "sum(" + arg + where + ")", null, "incremental sum", null));
                asked.add(s);
            }
        }
        QueryRequest components = new QueryRequest(q.fields(), asked, q.filters(), q.from(), q.to(), List.of(), null, "columns");
        QueryService.Result r = run.apply(ds.withMeasures(measures), components);
        int nf = q.fields().size();
        for (int row = 0; row < r.rows(); row++) {
            List<Object> key = new ArrayList<>();
            for (int f = 0; f < nf; f++) {
                key.add(r.columns().get(f).values()[row]);
            }
            double[] acc = new double[1 + 2 * inc.leaves.size()];
            acc[0] = number(value(r, ROWS, row));
            for (int i = 0; i < inc.leaves.size(); i++) {
                acc[1 + 2 * i] = inc.leaves.get(i).rowCount() || "count".equals(inc.leaves.get(i).fn()) ? 0 : number(value(r, "__inc_s" + i, row));
                acc[2 + 2 * i] = number(value(r, "__inc_c" + i, row));
            }
            if (acc[0] > 0) {
                inc.groups.put(key, acc);
            }
        }
        return inc;
    }

    private static Object value(QueryService.Result r, String column, int row) {
        for (ArrowIpcWriter.Column c : r.columns()) {
            if (c.name().equals(column)) {
                return c.values()[row];
            }
        }
        return null;
    }

    private static double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    /** Applies a live batch of the table: each changed row's old contribution out, its new one in. */
    public void apply(GenerationBus.Change change) {
        if (denied) {
            return;
        }
        RowEval ev = new RowEval(table, change.columns(), reader.user());
        Resolver res = new Resolver(ds);
        List<Node> fieldNodes = q.fields().stream().map(f -> (Node) new Node.Ref(List.of(f.split("\\.", -1)))).toList();
        List<Node> filterNodes = q.filters().stream().map(f -> filter(f, res)).toList();
        change.before().values().forEach(old -> {
            if (old != null) {
                add(ev, old, fieldNodes, filterNodes, -1);
            }
        });
        change.deletes().values().forEach(old -> add(ev, old, fieldNodes, filterNodes, -1));
        change.upserts().values().forEach(row -> add(ev, row, fieldNodes, filterNodes, 1));
        groups.values().removeIf(acc -> acc[0] <= 0.5);
    }

    private void add(RowEval ev, Object[] row, List<Node> fields, List<Node> filters, int sign) {
        if (rule != null && !ev.test(rule, row)) {
            return;
        }
        for (Node f : filters) {
            if (!ev.test(f, row)) {
                return;
            }
        }
        List<Object> key = new ArrayList<>();
        for (Node f : fields) {
            key.add(RowEval.answerValue(ev.eval(f, row)));
        }
        double[] acc = groups.computeIfAbsent(key, k -> new double[1 + 2 * leaves.size()]);
        acc[0] += sign;
        for (int i = 0; i < leaves.size(); i++) {
            Leaf l = leaves.get(i);
            if (l.cond() != null && !ev.test(l.cond(), row)) {
                continue;
            }
            if (l.rowCount()) {
                acc[2 + 2 * i] += sign;
                continue;
            }
            Object v = ev.eval(l.arg(), row);
            if (v != null) {
                if (!"count".equals(l.fn())) {
                    acc[1 + 2 * i] += sign * RowEval.num(v);
                }
                acc[2 + 2 * i] += sign;
            }
        }
    }

    /** A filter of the query as a DEL condition over the row. */
    private static Node filter(QueryRequest.Filter f, Resolver r) {
        Node field = new Node.Ref(List.of(f.field().split("\\.", -1)));
        Resolver.ColRef c = r.resolve(List.of(f.field().split("\\.", -1)), null);
        Dataset.Column col = c.part() == null ? r.column(c.table(), c.column()) : null;
        boolean date = col != null && "date".equals(col.type());
        List<Node> vals = f.values().stream().map(v -> literal(v, date)).toList();
        return switch (f.op()) {
            case "eq" -> new Node.Bin("==", field, vals.get(0));
            case "ne" -> new Node.Bin("!=", field, vals.get(0));
            case "lt" -> new Node.Bin("<", field, vals.get(0));
            case "le" -> new Node.Bin("<=", field, vals.get(0));
            case "gt" -> new Node.Bin(">", field, vals.get(0));
            case "ge" -> new Node.Bin(">=", field, vals.get(0));
            case "in" -> vals.isEmpty() ? new Node.Bool(false) : new Node.In(field, vals, false);
            case "not-in" -> vals.isEmpty() ? new Node.Bool(true) : new Node.In(field, vals, true);
            case "between" -> new Node.Bin("&&", new Node.Bin(">=", field, vals.get(0)), new Node.Bin("<=", field, vals.get(1)));
            case "is-null" -> new Node.Bin("==", field, new Node.Null());
            case "not-null" -> new Node.Bin("!=", field, new Node.Null());
            case "contains" -> new Node.Call("contains", List.of(field, vals.get(0)));
            default -> new Node.Call("startsWith", List.of(field, vals.get(0)));
        };
    }

    private static Node literal(Object v, boolean date) {
        if (v instanceof Number n) {
            return n instanceof Long || n instanceof Integer ? new Node.Num(n.doubleValue(), true) : new Node.Num(n.doubleValue(), false);
        }
        if (v instanceof Boolean b) {
            return new Node.Bool(b);
        }
        return new Node.Str(String.valueOf(v));
    }

    /** The answer now: the fields, then each measure, in the query's order (its sort, else the fields ascending), at most its limit. */
    public List<List<Object>> rows() {
        List<List<Object>> out = new ArrayList<>();
        for (Map.Entry<List<Object>, double[]> e : groups.entrySet()) {
            List<Object> row = new ArrayList<>(e.getKey());
            for (String m : q.measures()) {
                row.add(evalMeasure(trees.get(m), e.getValue()));
            }
            out.add(row);
        }
        out.sort(order());
        return q.limit() == null || out.size() <= q.limit() ? out : new ArrayList<>(out.subList(0, q.limit()));
    }

    public int groups() {
        return groups.size();
    }

    private Comparator<List<Object>> order() {
        List<String> names = new ArrayList<>(q.fields());
        names.addAll(q.measures());
        List<QueryRequest.Sort> sorts = q.orderBy().isEmpty() ? q.fields().stream().map(f -> new QueryRequest.Sort(f, false)).toList() : q.orderBy();
        return (a, b) -> {
            for (QueryRequest.Sort s : sorts) {
                int i = names.indexOf(s.by());
                if (i < 0) {
                    continue;
                }
                Object x = a.get(i);
                Object y = b.get(i);
                if (x == null || y == null) {
                    if (x != y) {
                        return x == null ? 1 : -1;                                // NULLS LAST, either way
                    }
                    continue;
                }
                int c = RowEval.compare(x, y);
                if (c != 0) {
                    return s.desc() ? -c : c;
                }
            }
            return 0;
        };
    }

    // ---- measures as arithmetic over the leaves -------------------------------------------------------------------------

    private Node inline(Node n, Set<String> seen) {
        return switch (n) {
            case Node.MeasureRef m -> {
                if (!seen.add(m.name())) {
                    throw new IllegalStateException("measures in a circle");
                }
                Node t = inline(Expressions.parse(ds.measures().get(m.name()).expr()), seen);
                seen.remove(m.name());
                yield t;
            }
            case Node.Call c when BY_DIFFERENCE.contains(c.function()) -> c;
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(a -> inline(a, seen)).toList());
            case Node.Bin b -> new Node.Bin(b.op(), inline(b.left(), seen), inline(b.right(), seen));
            case Node.Un u -> new Node.Un(u.op(), inline(u.operand(), seen));
            case Node.Cond c -> new Node.Cond(inline(c.test(), seen), inline(c.yes(), seen), inline(c.no(), seen));
            default -> n;
        };
    }

    /** Replaces each sum, count and avg with a reference to its leaf ({@code Call("=leaf", [i])}). */
    private Node collect(Node n) {
        return switch (n) {
            case Node.Call c when BY_DIFFERENCE.contains(c.function()) -> {
                Node arg = c.args().get(0);
                boolean rowCount = "count".equals(c.function()) && arg instanceof Node.Ref r && r.parts().size() == 1 && ds.tables().containsKey(r.parts().get(0));
                boolean integral = !rowCount && integral(arg);
                leaves.add(new Leaf(c.function(), arg, c.args().size() > 1 ? c.args().get(1) : null, rowCount, integral));
                yield new Node.Call("=leaf", List.of(new Node.Num(leaves.size() - 1, true)));
            }
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(this::collect).toList());
            case Node.Bin b -> new Node.Bin(b.op(), collect(b.left()), collect(b.right()));
            case Node.Un u -> new Node.Un(u.op(), collect(u.operand()));
            case Node.Cond c -> new Node.Cond(collect(c.test()), collect(c.yes()), collect(c.no()));
            default -> n;
        };
    }

    private boolean integral(Node arg) {
        if (arg instanceof Node.Ref r && r.parts().size() == 2) {
            Dataset.Column col = new Resolver(ds).column(r.parts().get(0), r.parts().get(1));
            return col != null && "integer".equals(col.type());
        }
        return false;
    }

    private Object evalMeasure(Node n, double[] acc) {
        return switch (n) {
            case Node.Call c when "=leaf".equals(c.function()) -> leaf((int) ((Node.Num) c.args().get(0)).value(), acc);
            case Node.Num x -> x.integral() ? (Object) (long) x.value() : (Object) x.value();
            case Node.Null x -> null;
            case Node.Un u -> {
                Object v = evalMeasure(u.operand(), acc);
                yield v == null ? null : v instanceof Long l ? (Object) (-l) : (Object) (-((Number) v).doubleValue());
            }
            case Node.Bin b -> {
                Object l = evalMeasure(b.left(), acc);
                Object r = evalMeasure(b.right(), acc);
                if (l == null || r == null) {
                    yield null;
                }
                double x = ((Number) l).doubleValue();
                double y = ((Number) r).doubleValue();
                yield switch (b.op()) {
                    case "+" -> l instanceof Long a && r instanceof Long z ? (Object) (a + z) : (Object) (x + y);
                    case "-" -> l instanceof Long a && r instanceof Long z ? (Object) (a - z) : (Object) (x - y);
                    case "*" -> l instanceof Long a && r instanceof Long z ? (Object) (a * z) : (Object) (x * y);
                    case "/" -> y == 0 ? null : (Object) (x / y);
                    default -> throw new IllegalStateException("operator " + b.op());
                };
            }
            case Node.Call c when "ratio".equals(c.function()) -> {
                Object l = evalMeasure(c.args().get(0), acc);
                Object r = evalMeasure(c.args().get(1), acc);
                yield l == null || r == null || ((Number) r).doubleValue() == 0 ? null : (Object) (((Number) l).doubleValue() / ((Number) r).doubleValue());
            }
            case Node.Call c when "round".equals(c.function()) -> {
                Object v = evalMeasure(c.args().get(0), acc);
                int digits = c.args().size() > 1 ? (int) ((Node.Num) c.args().get(1)).value() : 0;
                yield v == null ? null : (Object) (java.math.BigDecimal.valueOf(((Number) v).doubleValue()).setScale(digits, java.math.RoundingMode.HALF_UP).doubleValue());
            }
            case Node.Call c when "abs".equals(c.function()) -> {
                Object v = evalMeasure(c.args().get(0), acc);
                yield v == null ? null : v instanceof Long l ? (Object) Math.abs(l) : (Object) Math.abs(((Number) v).doubleValue());
            }
            case Node.Call c when "coalesce".equals(c.function()) -> {
                for (Node a : c.args()) {
                    Object v = evalMeasure(a, acc);
                    if (v != null) {
                        yield v;
                    }
                }
                yield null;
            }
            default -> throw new IllegalStateException("not maintained by difference: " + n);
        };
    }

    private Object leaf(int i, double[] acc) {
        Leaf l = leaves.get(i);
        double sum = acc[1 + 2 * i];
        double count = acc[2 + 2 * i];
        return switch (l.fn()) {
            case "count" -> (long) Math.round(count);
            case "avg" -> count < 0.5 ? null : (Object) (sum / count);
            default -> count < 0.5 ? null : l.integral() ? (Object) Math.round(sum) : (Object) sum;
        };
    }

    /** The measures' leaves are all sums, counts and averages the state can hold (a measure whose shape it cannot evaluate makes the caller recompute). */
    public static boolean evaluable(Dataset ds, QueryRequest q) {
        try {
            Incremental probe = new Incremental(ds, q, "", new SqlCompiler.Reader(false, Set.of(), Map.of(), SqlCompiler.Masks.NONE));
            for (Node t : probe.trees.values()) {
                if (Node.anyMatch(t, x -> x instanceof Node.Call c && !"=leaf".equals(c.function()) && !Set.of("ratio", "round", "abs", "coalesce").contains(c.function()))) {
                    return false;
                }
                if (Node.anyMatch(t, x -> x instanceof Node.Bin b && !Set.of("+", "-", "*", "/").contains(b.op()) || x instanceof Node.Cond || x instanceof Node.Str)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
