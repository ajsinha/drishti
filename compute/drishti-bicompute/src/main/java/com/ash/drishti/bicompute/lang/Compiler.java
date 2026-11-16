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
package com.ash.drishti.bicompute.lang;

import com.ash.drishti.bicompute.context.FilterContext;
import com.ash.drishti.bicompute.context.Periods;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.check.Checker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one compiler from Rachana's tree to the engine (docs/architecture/ARCHITECTURE.md §28 2.6). The expression is parsed and checked by Rachana
 * ({@code CheckedMode}); this lowers it: a row expression to the engine's row expression (Rachana's tree for Java, {@link SqlLowering}
 * for SQL), an aggregate to the engine's aggregate function ({@link Functions#builtIn}), a filter-context function to a context
 * sub-plan ({@link FilterContext}), a window or time function to a computation over the answer, and what combines them to a
 * {@link Lowered.Shape}. DEL's lowering ({@code DelLowering}, BI's {@code Node} tree) is replaced by this one when BI moves onto the
 * table layer (part B); until then both exist only because DEL is the documented exception of {@code OneGrammarRulesTest}.
 */
public final class Compiler {

    /** The aggregates of a measure the engine computes over rows (DEL's seven and the engine's others, by the engine's names). */
    static final Set<String> AGGREGATES = Set.of("sum", "avg", "min", "max", "count", "distinct", "percentile", "median", "wavg", "variance",
            "stddev", "variance_pop", "stddev_pop", "nmiss", "range", "stderr", "cv", "skewness", "kurtosis", "geomean", "harmean", "corr",
            "covar", "slope", "intercept", "rsq", "mode", "iqr", "trimmean", "kth_largest", "kth_smallest");
    static final Set<String> CONTEXT = Set.of("calc", "share_of", "parent_of", "avg_by", "sum_by", "min_by", "max_by", "avg_over", "as_of");
    static final Set<String> WINDOWS = Set.of("lag", "lead", "rank", "dense_rank", "percent_rank", "moving_avg", "moving_sum", "moving_min",
            "moving_max");
    static final Set<String> TIME = Set.of("ytd", "mtd", "qtd", "prior_period", "same_period_last_year", "running", "fiscal_ytd", "latest",
            "earliest", "opening", "closing");

    private final SqlLowering rows;
    private final SqlLowering.Columns columns;

    public Compiler(SqlLowering.Columns columns, SqlLowering.Options options) {
        this.columns = columns;
        this.rows = new SqlLowering(columns, options);
    }

    /** A row expression (a column's {@code expr}, a filter, a rule). */
    public Lowered.Row row(Expr e) {
        return rows.row(e);
    }

    /** A measure (aggregate scope). */
    public Lowered measure(Expr e) {
        if (e instanceof Expr.Call c) {
            String fn = com.ash.drishti.rachana.el.Functions.oneName(c.name());
            if (AGGREGATES.contains(fn)) {
                return aggregate(fn, c);
            }
            if ("measure".equals(fn)) {
                return new Lowered.Measure(text(c.args().get(0)), c.args().size() > 1 ? text(c.args().get(1)) : null);
            }
            if ("ratio".equals(fn)) {
                return shape(new Expr.Binary("/", new Expr.Local(Lowered.Shape.local(0)), new Expr.Local(Lowered.Shape.local(1))),
                        List.of(measure(c.args().get(0)), measure(c.args().get(1))));
            }
            if (CONTEXT.contains(fn)) {
                return context(fn, c);
            }
            if (WINDOWS.contains(fn)) {
                return window(fn, c);
            }
            if (TIME.contains(fn)) {
                String period = "opening".equals(fn) || "closing".equals(fn) ? Periods.period(fn, text(c.arg("period"))) : null;
                return new Lowered.Time(fn, measure(c.args().get(0)), field(c.args().get(1)), period);
            }
            if ("convert".equals(fn)) {
                return new Lowered.Convert(measure(c.args().get(0)), c.arg("to") == null ? "USD" : text(c.arg("to")),
                        c.arg("at") == null ? "as-of" : text(c.arg("at")));
            }
        }
        List<Lowered> parts = new ArrayList<>();
        Expr shape = holes(e, parts);
        return shape(shape, parts);
    }

    private Lowered.Shape shape(Expr shape, List<Lowered> parts) {
        return new Lowered.Shape(shape, rows.sql(shape), parts);
    }

    /** The expression with each measure-level part replaced by a local {@code __hN} (its lowering appended to {@code parts}). */
    private Expr holes(Expr e, List<Lowered> parts) {
        if (e instanceof Expr.Call c && (AGGREGATES.contains(c.name()) || CONTEXT.contains(c.name()) || WINDOWS.contains(c.name())
                || TIME.contains(c.name()) || "measure".equals(c.name()) || "ratio".equals(c.name()) || "convert".equals(c.name()))) {
            parts.add(measure(e));
            return new Expr.Local(Lowered.Shape.local(parts.size() - 1));
        }
        return switch (e) {
            case Expr.Binary b -> new Expr.Binary(b.op(), holes(b.l(), parts), holes(b.r(), parts));
            case Expr.Neg n -> new Expr.Neg(holes(n.e(), parts));
            case Expr.Not n -> new Expr.Not(holes(n.e(), parts));
            case Expr.And a -> new Expr.And(holes(a.l(), parts), holes(a.r(), parts));
            case Expr.Or o -> new Expr.Or(holes(o.l(), parts), holes(o.r(), parts));
            case Expr.Ternary t -> new Expr.Ternary(holes(t.cond(), parts), holes(t.then(), parts), holes(t.otherwise(), parts));
            case Expr.In i -> new Expr.In(holes(i.value(), parts), holes(i.list(), parts), i.negated());
            case Expr.ListLit l -> new Expr.ListLit(l.items().stream().map(x -> holes(x, parts)).toList());
            case Expr.Call c -> new Expr.Call(c.name(), c.fn(), c.args().stream().map(x -> holes(x, parts)).toList(), c.named());
            case Expr.Form f -> f.withArgs(f.args().stream().map(x -> holes(x, parts)).toList());
            case Expr.Lit l -> l;
            case Expr.Local l -> l;
            default -> throw new IllegalArgumentException("a measure combines aggregates and measures; this part is per row: wrap it in an aggregate");
        };
    }

    private Lowered aggregate(String fn, Expr.Call c) {
        List<Lowered.Row> inputs = new ArrayList<>();
        List<Double> numbers = new ArrayList<>();
        List<Expr> args = c.args();
        if ("count".equals(fn) && args.size() == 1 && isTable(args.get(0))) {
            // count(trades): the table's rows
        } else {
            int n = switch (fn) {
                case "wavg", "corr", "covar", "slope", "intercept", "rsq" -> 2;
                default -> 1;
            };
            for (int i = 0; i < args.size(); i++) {
                if (i < n && args.get(i) instanceof Expr.Absent) {
                    continue;                                                // count(where = ...): no row value, the rows
                } else if (i < n) {
                    inputs.add(rows.row(args.get(i)));
                } else if (args.get(i) instanceof Expr.Lit l && l.value() instanceof Number num) {
                    numbers.add(num.doubleValue());
                } else if (!(args.get(i) instanceof Expr.Absent)) {
                    throw new IllegalArgumentException(fn + "(): an option is a number written in the expression");
                }
            }
        }
        Expr where = c.arg("where");
        var unused = Functions.builtIn(fn, inputs.isEmpty() ? List.of() : List.of("x"), numbers)
                .orElseThrow(() -> new IllegalArgumentException(fn + "() is not an aggregate of the engine"));
        return new Lowered.Aggregate(fn, inputs, where == null ? null : rows.row(where), numbers);
    }

    private boolean isTable(Expr e) {
        List<String> parts = Checker.parts(e);
        return parts != null && parts.size() == 1 && columns.table(parts.get(0));
    }

    private Lowered context(String fn, Expr.Call c) {
        Lowered inner = measure(c.args().get(0));
        return switch (fn) {
            case "calc" -> {
                Expr ignore = c.arg("ignore");
                Expr where = c.arg("where");
                boolean all = isAll(ignore);
                FilterContext.Edit edit = new FilterContext.Edit(FilterContext.Kind.EDIT, all, all ? Set.of() : fieldSet(ignore),
                        c.arg("keep") == null ? null : fieldSet(c.arg("keep")), c.arg("via") == null ? null : text(c.arg("via")), List.of(), null);
                yield new Lowered.Context(fn, edit, inner, where == null ? null : rows.row(where), null, null);
            }
            case "share_of" -> {
                // share_of(agg, over = f) is agg / calc(agg, ignore = f) (BI_DESIGN 3.3.5)
                Expr over = c.arg("over");
                boolean all = isAll(over);
                FilterContext.Edit edit = new FilterContext.Edit(FilterContext.Kind.EDIT, all, all ? Set.of() : fieldSet(over), null, null, List.of(), null);
                Lowered total = new Lowered.Context("calc", edit, inner, null, null, null);
                yield shape(new Expr.Binary("/", new Expr.Local(Lowered.Shape.local(0)), new Expr.Local(Lowered.Shape.local(1))), List.of(inner, total));
            }
            case "parent_of" -> new Lowered.Context(fn, new FilterContext.Edit(FilterContext.Kind.EDIT, false, Set.of(), null, null, List.of(), null),
                    inner, null, null, text(c.arg("hierarchy")));
            case "avg_by", "sum_by", "min_by", "max_by" -> new Lowered.Context(fn, new FilterContext.Edit(FilterContext.Kind.AVERAGE, false, Set.of(), null, null,
                    new ArrayList<>(fieldSet(c.arg("by"))), null, false, fn.substring(0, 3), null, null), inner, null, null, null);
            case "avg_over" -> new Lowered.Context(fn, new FilterContext.Edit(FilterContext.Kind.AVERAGE, false, Set.of(), null, null,
                    List.of(field(c.args().get(1))), null), inner, null, null, null);
            default -> {
                Expr date = c.args().get(1);
                if (Checker.parts(date) != null || mentionsField(date)) {
                    throw new IllegalArgumentException("as_of() needs a date it can work out before the query (DRS-9166): date('2026-09-30'), asof(), "
                            + "or shift_date(asof(), -1)");
                }
                yield new Lowered.Context(fn, new FilterContext.Edit(FilterContext.Kind.DATED, false, Set.of(), null, null, List.of(),
                        rows.constant(date)), inner, null, date, null);
            }
        };
    }

    private Lowered window(String fn, Expr.Call c) {
        Lowered inner = measure(c.args().get(0));
        List<String> by = c.arg("by") == null ? List.of() : new ArrayList<>(fieldSet(c.arg("by")));
        boolean ranks = fn.endsWith("rank");
        Integer n = ranks ? null : c.args().get(1) instanceof Expr.Lit l && l.value() instanceof Number x ? x.intValue() : null;
        String order = ranks ? null : field(c.args().get(2));
        boolean desc = !(c.arg("desc") instanceof Expr.Lit l && Boolean.FALSE.equals(l.value()));
        return new Lowered.Window(fn, inner, n, order, by, desc);
    }

    private static boolean isAll(Expr e) {
        List<String> parts = e == null ? null : Checker.parts(e);
        return parts != null && parts.equals(List.of("ALL"));
    }

    private static Set<String> fieldSet(Expr e) {
        Set<String> out = new LinkedHashSet<>();
        if (e instanceof Expr.ListLit l) {
            for (Expr item : l.items()) {
                out.add(field(item));
            }
        } else if (e != null) {
            out.add(field(e));
        }
        return out;
    }

    private static String field(Expr e) {
        List<String> parts = Checker.parts(e);
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("a field (alias.COLUMN) is needed here");
        }
        return String.join(".", parts);
    }

    private static boolean mentionsField(Expr e) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        e.paths(p -> seen.merge(p, 1, Integer::sum));
        return switch (e) {
            case Expr.Call c -> c.args().stream().anyMatch(a -> Checker.parts(a) != null || mentionsField(a));
            case Expr.Binary b -> Checker.parts(b.l()) != null || Checker.parts(b.r()) != null || mentionsField(b.l()) || mentionsField(b.r());
            default -> !seen.isEmpty();
        };
    }

    private static String text(Expr e) {
        if (e instanceof Expr.Lit l && l.value() instanceof String s) {
            return s;
        }
        if (e == null) {
            return null;
        }
        throw new IllegalArgumentException("a text written in the expression is needed here");
    }
}
