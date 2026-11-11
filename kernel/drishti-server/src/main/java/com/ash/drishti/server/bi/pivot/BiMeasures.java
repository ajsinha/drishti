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
package com.ash.drishti.server.bi.pivot;

import com.ash.drishti.bicompute.formula.FormulaFunction;
import com.ash.drishti.bicompute.formula.FormulaLowering;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Functions;
import com.ash.drishti.server.bi.expr.Expressions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A model's measures as pivot functions (HOW_IT_FITS.md section A26.9: a measure defined once). A measure that is an expression of
 * aggregates over one fact's rows ({@code sum}, {@code avg}, {@code min}, {@code max}, {@code count}, {@code distinct}, {@code percentile},
 * {@code ratio}, {@code where=} conditions, arithmetic, row functions, other measures by {@code measure('Name')}) becomes a formula of the
 * engine's built-ins: its kind, its states, its totals and whether it adds up come from the engine. A measure that reads beyond the rows of
 * its group (time intelligence, running and analytic functions, filter contexts, balances, Python) is not a pivot function: the model's own
 * query computes it, and its totals are not made (a year-to-date or a running figure has no total that means anything).
 */
public final class BiMeasures {

    /** The model's aggregates and their pivot spelling. */
    private static final Map<String, String> AGGREGATES = Map.of("sum", "sum", "avg", "avg", "min", "min", "max", "max", "count", "count",
            "distinct", "distinct", "percentile", "percentile", "median", "median");

    /**
     * A measure as a pivot function.
     *
     * @param measure the measure's name
     * @param formula the engine's formula over the model's fields
     * @param fields the model fields it reads
     * @param tables the tables those fields are of
     * @param additive true when a total is the sum of its parts (the engine's {@code plainSum}: sums and counts added and subtracted)
     */
    public record Translated(String measure, String formula, Set<String> fields, Set<String> tables, boolean additive) {}

    /** Why a measure is not a pivot function (an unchecked refusal carrying the reason). */
    public static final class NotPivot extends RuntimeException {
        public NotPivot(String why) {
            super(why);
        }
    }

    private BiMeasures() {}

    /** The measure as a pivot function, or empty (its reason from {@link #why}). */
    public static Optional<Translated> of(Dataset ds, String measure) {
        try {
            return Optional.of(translate(ds, measure));
        } catch (NotPivot e) {
            return Optional.empty();
        }
    }

    /** Why {@code measure} is not a pivot function (null when it is). */
    public static String why(Dataset ds, String measure) {
        try {
            translate(ds, measure);
            return null;
        } catch (NotPivot e) {
            return e.getMessage();
        }
    }

    /** True when the measure's total is the sum of its parts, as the engine finds it (a measure that is not a pivot function: false). */
    public static boolean additive(Dataset ds, String measure) {
        return of(ds, measure).map(Translated::additive).orElse(false);
    }

    /** The measure translated; refused with {@link NotPivot}. */
    public static Translated translate(Dataset ds, String measure) {
        Set<String> fields = new LinkedHashSet<>();
        Set<String> tables = new LinkedHashSet<>();
        String formula = text(ds, measure, fields, tables, new ArrayList<>());
        if (tables.size() > 1) {
            throw new NotPivot(measure + " reads " + tables + ": a pivot function reads one fact's rows");
        }
        try {
            FormulaFunction f = FormulaFunction.of(measure, formula, new ArrayList<>(fields));
            return new Translated(measure, formula, fields, tables, f.plainSum());
        } catch (FormulaLowering.FormulaException e) {
            throw new NotPivot(measure + ": " + e.getMessage());
        }
    }

    private static String text(Dataset ds, String measure, Set<String> fields, Set<String> tables, List<String> path) {
        Dataset.Measure m = ds.measures().get(measure);
        if (m == null) {
            throw new NotPivot("no measure '" + measure + "'");
        }
        if (m.python() != null && !m.python().isBlank()) {
            throw new NotPivot(measure + " is a Python measure: the model's Python runner computes it per group");
        }
        if (path.contains(measure) || path.size() > 16) {
            throw new NotPivot(measure + " refers to itself");
        }
        path.add(measure);
        Expr tree;
        try {
            tree = Expressions.read(m.expr()).rachana().expr();
        } catch (RuntimeException e) {
            throw new NotPivot(measure + ": " + e.getMessage());
        }
        String out = print(ds, tree, fields, tables, path, false);
        path.remove(path.size() - 1);
        return out;
    }

    private static String print(Dataset ds, Expr n, Set<String> fields, Set<String> tables, List<String> path, boolean inAgg) {
        return switch (n) {
            case Expr.Lit x when x.value() == null -> "null";
            case Expr.Lit x when x.value() instanceof Boolean b -> b ? "true" : "false";
            case Expr.Lit x when x.value() instanceof String v -> "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'";
            case Expr.Lit x -> x.value() instanceof Double d ? Double.toString(d) : String.valueOf(x.value());
            case Expr.ListLit l -> "[" + String.join(", ", l.items().stream().map(i -> print(ds, i, fields, tables, path, inAgg)).toList()) + "]";
            case Expr.Field f -> field(ds, f, fields, tables, inAgg);
            case Expr.Not u -> "(!" + print(ds, u.e(), fields, tables, path, inAgg) + ")";
            case Expr.Neg u -> "(-" + print(ds, u.e(), fields, tables, path, inAgg) + ")";
            case Expr.And a -> "(" + print(ds, a.l(), fields, tables, path, inAgg) + " && " + print(ds, a.r(), fields, tables, path, inAgg) + ")";
            case Expr.Or o -> "(" + print(ds, o.l(), fields, tables, path, inAgg) + " || " + print(ds, o.r(), fields, tables, path, inAgg) + ")";
            case Expr.Binary b -> "(" + print(ds, b.l(), fields, tables, path, inAgg) + " " + b.op() + " " + print(ds, b.r(), fields, tables, path, inAgg) + ")";
            case Expr.In i -> "(" + print(ds, i.value(), fields, tables, path, inAgg) + (i.negated() ? " not in " : " in ") + print(ds, i.list(), fields, tables, path, inAgg) + ")";
            case Expr.Ternary t -> "(" + print(ds, t.cond(), fields, tables, path, inAgg) + " ? " + print(ds, t.then(), fields, tables, path, inAgg)
                    + " : " + print(ds, t.otherwise(), fields, tables, path, inAgg) + ")";
            case Expr.Call c -> call(ds, c, fields, tables, path, inAgg);
            default -> throw new NotPivot(com.ash.drishti.rachana.el.Printer.print(n) + " is not a pivot input");
        };
    }

    /** The dotted name of a chain of fields from the row ({@code trades.MTM}), or null. */
    private static List<String> parts(Expr e) {
        List<String> out = new ArrayList<>();
        while (e instanceof Expr.Field f) {
            out.add(0, f.name());
            e = f.target();
        }
        return e instanceof Expr.RowOrRoot ? out : null;
    }

    private static String field(Dataset ds, Expr.Field f, Set<String> fields, Set<String> tables, boolean inAgg) {
        List<String> parts = parts(f);
        String text = com.ash.drishti.rachana.el.Printer.print(f);
        if ("ALL".equals(text)) {
            throw new NotPivot("ALL is a filter context: not a pivot function");
        }
        if (!inAgg) {
            throw new NotPivot(text + " outside an aggregate");
        }
        if (parts == null || parts.size() < 2 || !ds.tables().containsKey(parts.get(0))) {
            throw new NotPivot(text + " is not a field of a table of the model (a parameter or the reader's attribute is not a pivot input)");
        }
        tables.add(parts.get(0));
        fields.add(text);
        return text;
    }

    private static String call(Dataset ds, Expr.Call c, Set<String> fields, Set<String> tables, List<String> path, boolean inAgg) {
        String name = Functions.oneName(c.name()).toLowerCase(Locale.ROOT);
        List<Expr> positional = c.args().stream().filter(a -> !(a instanceof Expr.Absent)).toList();
        if (name.equals("measure")) {
            if (inAgg || positional.size() != 1 || !(positional.get(0) instanceof Expr.Lit l) || !(l.value() instanceof String s)) {
                throw new NotPivot("measure('Name') names one measure");
            }
            return "(" + text(ds, s, fields, tables, path) + ")";
        }
        if (name.equals("ratio") && !inAgg) {
            if (positional.size() != 2 || !c.named().isEmpty()) {
                throw new NotPivot("ratio(a, b) takes two aggregates");
            }
            return "(" + print(ds, positional.get(0), fields, tables, path, false) + " / " + print(ds, positional.get(1), fields, tables, path, false) + ")";
        }
        String agg = AGGREGATES.get(name);
        if (agg != null && !inAgg) {
            List<String> args = new ArrayList<>();
            List<String> parts = positional.size() == 1 ? parts(positional.get(0)) : null;
            if (name.equals("count") && parts != null && parts.size() == 1 && ds.tables().containsKey(parts.get(0))) {
                tables.add(parts.get(0));                       // count(trades): the rows of the table
            } else {
                for (int i = 0; i < positional.size(); i++) {
                    boolean param = name.equals("percentile") && i == 1;
                    args.add(print(ds, positional.get(i), fields, tables, path, !param));
                }
            }
            for (Map.Entry<String, Expr> k : c.named().entrySet()) {
                if (!k.getKey().equals("where")) {
                    throw new NotPivot(name + "(" + k.getKey() + "=...) reads beyond its group's rows (a filter context or a time window)");
                }
                args.add("where = " + print(ds, k.getValue(), fields, tables, path, true));
            }
            return agg + "(" + String.join(", ", args) + ")";
        }
        if (FormulaLowering.SCALARS.containsKey(name)) {
            List<String> args = new ArrayList<>();
            for (Expr a : positional) {
                args.add(print(ds, a, fields, tables, path, inAgg));
            }
            if (!c.named().isEmpty()) {
                throw new NotPivot(name + " takes no keywords here");
            }
            return name + "(" + String.join(", ", args) + ")";
        }
        throw new NotPivot(c.name() + "(...) is not an aggregate of its group's rows (time intelligence, running, analytic and context functions "
                + "are the model's own)");
    }
}
