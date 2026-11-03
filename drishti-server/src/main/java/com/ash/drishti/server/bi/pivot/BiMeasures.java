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
import com.ash.drishti.bicompute.formula.FormulaParser;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.expr.DelAst;
import com.ash.drishti.server.bi.expr.DelParser;
import com.ash.drishti.server.bi.expr.DelPrinter;
import com.ash.drishti.server.bi.expr.Expressions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A model's measures as pivot functions (PIVOT_ENGINE.md section 12.2: a measure defined once). A measure whose DEL is an expression of
 * aggregates over one fact's rows ({@code sum}, {@code avg}, {@code min}, {@code max}, {@code count}, {@code distinct}, {@code percentile},
 * {@code ratio}, {@code where=} conditions, arithmetic, row functions, other measures by {@code measure('Name')}) becomes a formula of the
 * engine's built-ins: its kind, its states, its totals and whether it adds up come from the engine. A measure that reads beyond the rows of
 * its group (time intelligence, running and analytic functions, filter contexts, balances, Python) is not a pivot function: the model's own
 * query computes it, and its totals are not made (a year-to-date or a running figure has no total that means anything).
 */
public final class BiMeasures {

    /** DEL aggregates and their pivot spelling. */
    private static final Map<String, String> AGGREGATES = Map.of("sum", "sum", "avg", "avg", "min", "min", "max", "max", "count", "count",
            "distinct", "count_distinct", "percentile", "percentile", "median", "median");

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
        } catch (FormulaParser.FormulaException e) {
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
        DelAst tree;
        try {
            Expressions.Parsed p = Expressions.read(m.expr());
            tree = p.del() != null ? p.del() : DelParser.parse(DelPrinter.print(p.node()));
        } catch (RuntimeException e) {
            throw new NotPivot(measure + ": " + e.getMessage());
        }
        String out = print(ds, tree, fields, tables, path, false);
        path.remove(path.size() - 1);
        return out;
    }

    private static String print(Dataset ds, DelAst n, Set<String> fields, Set<String> tables, List<String> path, boolean inAgg) {
        return switch (n) {
            case DelAst.Num x -> x.integral() ? Long.toString((long) x.value()) : Double.toString(x.value());
            case DelAst.Str x -> "'" + x.value().replace("\\", "\\\\").replace("'", "\\'") + "'";
            case DelAst.Bool x -> x.value() ? "True" : "False";
            case DelAst.NoneLit x -> "None";
            case DelAst.AllLit x -> throw new NotPivot("ALL is a filter context: not a pivot function");
            case DelAst.ListLit l -> "[" + String.join(", ", l.items().stream().map(i -> print(ds, i, fields, tables, path, inAgg)).toList()) + "]";
            case DelAst.Field f -> field(ds, f, fields, tables, inAgg);
            case DelAst.Unary u -> u.op().equals("not") ? "(not " + print(ds, u.operand(), fields, tables, path, inAgg) + ")"
                    : "(" + u.op() + print(ds, u.operand(), fields, tables, path, inAgg) + ")";
            case DelAst.Binary b -> "(" + print(ds, b.left(), fields, tables, path, inAgg) + " " + b.op() + " "
                    + print(ds, b.right(), fields, tables, path, inAgg) + ")";
            case DelAst.BoolOp b -> "(" + String.join(" " + b.op() + " ", b.values().stream().map(v -> print(ds, v, fields, tables, path, inAgg))
                    .toList()) + ")";
            case DelAst.Compare c -> {
                StringBuilder s = new StringBuilder("(").append(print(ds, c.first(), fields, tables, path, inAgg));
                for (int i = 0; i < c.ops().size(); i++) {
                    s.append(' ').append(c.ops().get(i)).append(' ').append(print(ds, c.rest().get(i), fields, tables, path, inAgg));
                }
                yield s.append(')').toString();
            }
            case DelAst.IfExp i -> "(" + print(ds, i.body(), fields, tables, path, inAgg) + " if " + print(ds, i.test(), fields, tables, path, inAgg)
                    + " else " + print(ds, i.orElse(), fields, tables, path, inAgg) + ")";
            case DelAst.Call c -> call(ds, c, fields, tables, path, inAgg);
        };
    }

    private static String field(Dataset ds, DelAst.Field f, Set<String> fields, Set<String> tables, boolean inAgg) {
        String text = f.text();
        if (!inAgg) {
            throw new NotPivot(text + " outside an aggregate");
        }
        if (f.parts().size() < 2 || !ds.tables().containsKey(f.parts().get(0))) {
            throw new NotPivot(text + " is not a field of a table of the model (a parameter or the reader's attribute is not a pivot input)");
        }
        tables.add(f.parts().get(0));
        fields.add(text);
        return text;
    }

    private static String call(Dataset ds, DelAst.Call c, Set<String> fields, Set<String> tables, List<String> path, boolean inAgg) {
        String name = c.name().toLowerCase(Locale.ROOT);
        if (name.equals("measure")) {
            if (inAgg || c.args().size() != 1 || !(c.args().get(0) instanceof DelAst.Str s)) {
                throw new NotPivot("measure('Name') names one measure");
            }
            return "(" + text(ds, s.value(), fields, tables, path) + ")";
        }
        if (name.equals("ratio") && !inAgg) {
            if (c.args().size() != 2 || !c.keywords().isEmpty()) {
                throw new NotPivot("ratio(a, b) takes two aggregates");
            }
            return "(" + print(ds, c.args().get(0), fields, tables, path, false) + " / " + print(ds, c.args().get(1), fields, tables, path, false) + ")";
        }
        String agg = AGGREGATES.get(name);
        if (agg != null && !inAgg) {
            List<String> args = new ArrayList<>();
            if (name.equals("count") && c.args().size() == 1 && c.args().get(0) instanceof DelAst.Field f && f.parts().size() == 1
                    && ds.tables().containsKey(f.parts().get(0))) {
                tables.add(f.parts().get(0));                       // count(trades): the rows of the table
            } else {
                for (int i = 0; i < c.args().size(); i++) {
                    DelAst a = c.args().get(i);
                    boolean param = name.equals("percentile") && i == 1;
                    args.add(param ? print(ds, a, fields, tables, path, false) : print(ds, a, fields, tables, path, true));
                }
            }
            for (DelAst.Keyword k : c.keywords()) {
                if (!k.name().equals("where")) {
                    throw new NotPivot(name + "(" + k.name() + "=...) reads beyond its group's rows (a filter context or a time window)");
                }
                args.add("where=" + print(ds, k.value(), fields, tables, path, true));
            }
            return agg + "(" + String.join(", ", args) + ")";
        }
        if (FormulaParser.SCALARS.containsKey(name)) {
            List<String> args = new ArrayList<>();
            for (DelAst a : c.args()) {
                args.add(print(ds, a, fields, tables, path, inAgg));
            }
            if (!c.keywords().isEmpty()) {
                throw new NotPivot(name + " takes no keywords here");
            }
            return name + "(" + String.join(", ", args) + ")";
        }
        throw new NotPivot(c.name() + "(...) is not an aggregate of its group's rows (time intelligence, running, analytic and context functions "
                + "are the model's own)");
    }
}
