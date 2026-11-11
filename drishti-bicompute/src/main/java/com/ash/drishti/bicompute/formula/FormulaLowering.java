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
package com.ash.drishti.bicompute.formula;

import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Functions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A pivot formula (ARCHITECTURE.md section 26.5) read by Rachana's one parser and lowered to the engine's formula tree ({@link FormulaAst}):
 * arithmetic over aggregate calls of the built-ins, each aggregating a row expression with an optional {@code where =} condition.
 *
 * <pre>
 * sum(PNL) / sum(abs(PNL))
 * sum(NOTIONAL_USD, where = DESK == 'RATES') / sum(NOTIONAL_USD)
 * wavg(PRICE, QUANTITY)
 * percentile(PNL_1D, 0.99) - percentile(PNL_1D, 0.01)
 * count(where = PNL &lt; 0) / count()
 * </pre>
 *
 * Rachana's spelling: {@code &&}, {@code ||}, {@code !}, {@code c ? a : b}, {@code true}, {@code false}, {@code null}, lists {@code [a, b]}
 * with {@code in} and {@code not in}, {@code power(x, y)} and {@code floor(x / y)}. A field must be inside an aggregate (a measure is a figure
 * of a cell, not of a row); an aggregate inside an aggregate is refused; the text is at most 2,000 characters. A comparison with
 * {@code null} tests for a missing value; any other comparison with a missing value is false (SQL's rule, as BI's SQL keeps it). A text in
 * the old syntax is refused here and rewritten by the migration of saved formulas. Pure and thread-safe.
 */
public final class FormulaLowering {

    public static final int MAX_LENGTH = 2000;

    /**
     * The aggregates, under their one name each (RACHANA_REFERENCE.md section F4), and their row inputs (-1: none, the rows, or one). Each
     * maps to the engine's built-in of {@link #ENGINE_NAME}.
     */
    public static final Map<String, Integer> AGGREGATES = Map.ofEntries(Map.entry("sum", 1), Map.entry("count", -1), Map.entry("avg", 1),
            Map.entry("min", 1), Map.entry("max", 1), Map.entry("wavg", 2), Map.entry("variance", 1), Map.entry("variance_pop", 1),
            Map.entry("stddev", 1), Map.entry("stddev_pop", 1), Map.entry("median", 1), Map.entry("percentile", 1), Map.entry("distinct", 1),
            Map.entry("sum_once", 2), Map.entry("count_once", 2), Map.entry("avg_once", 2));

    /** The engine's built-in each catalogue name is computed by. */
    public static final Map<String, String> ENGINE_NAME = Map.of("variance", "var_samp", "variance_pop", "var_pop", "stddev", "stddev_samp",
            "distinct", "count_distinct");

    /**
     * The scalar functions of row values (and, outside an aggregate, of figures), under their one name each (RACHANA_REFERENCE.md sections
     * 1 and 4), and how many arguments each takes (-1: one or more).
     */
    public static final Map<String, Integer> SCALARS = Map.ofEntries(Map.entry("abs", 1), Map.entry("sqrt", 1), Map.entry("ln", 1),
            Map.entry("log10", 1), Map.entry("exp", 1), Map.entry("power", 2), Map.entry("floor", 1), Map.entry("ceiling", 1),
            Map.entry("sign", 1), Map.entry("round", -1), Map.entry("coalesce", -1), Map.entry("greatest", -1), Map.entry("least", -1),
            Map.entry("ratio", 2));

    /** The old spellings of the literals: a field of that name is refused, so a formula in the old syntax is never read with another meaning. */
    private static final Set<String> OLD_WORDS = Set.of("True", "False", "None", "and", "or", "not", "if", "else", "is", "lambda");

    /** A formula refused, with where. */
    public static final class FormulaException extends RuntimeException {
        private final int at;

        public FormulaException(String message, int at) {
            super(message + (at >= 0 ? " (at character " + (at + 1) + ")" : ""));
            this.at = at;
        }

        public int at() {
            return at;
        }
    }

    private final CheckedMode.Parsed parsed;
    private boolean inAgg;

    private FormulaLowering(CheckedMode.Parsed parsed) {
        this.parsed = parsed;
    }

    /** The formula's tree; refused in words when it is not a measure formula. */
    public static FormulaAst lower(String text) {
        if (text == null || text.isBlank()) {
            throw new FormulaException("a formula is empty", -1);
        }
        if (text.length() > MAX_LENGTH) {
            throw new FormulaException("a formula is at most " + MAX_LENGTH + " characters", MAX_LENGTH);
        }
        CheckedMode.Parsed p;
        try {
            p = CheckedMode.parse(text);
        } catch (ElException e) {
            throw new FormulaException(e.getMessage(), Math.max(0, e.position()));
        }
        FormulaAst e = new FormulaLowering(p).node(p.expr());
        boolean[] agg = {false};
        FormulaAst.walk(e, n -> agg[0] |= n instanceof FormulaAst.Agg);
        if (!agg[0]) {
            throw new FormulaException("a measure aggregates: write sum(x), count(), avg(x), ... (no aggregate in the formula)", -1);
        }
        return e;
    }

    /** True when the text reads as a formula; the plain built-in call {@code sum(X)} is a formula too. */
    public static boolean isFormula(String text) {
        try {
            lower(text);
            return true;
        } catch (FormulaException e) {
            return false;
        }
    }

    private int at(Expr e) {
        return parsed.span(e)[0];
    }

    private FormulaAst node(Expr e) {
        return switch (e) {
            case Expr.Lit l when l.value() == null -> new FormulaAst.NoneLit();
            case Expr.Lit l when l.value() instanceof Boolean b -> new FormulaAst.Bool(b);
            case Expr.Lit l when l.value() instanceof String s -> new FormulaAst.Str(s);
            case Expr.Lit l when l.value() instanceof Number n -> new FormulaAst.Num(n.doubleValue());
            case Expr.Neg n -> {
                FormulaAst f = node(n.e());
                yield f instanceof FormulaAst.Num x ? new FormulaAst.Num(-x.value()) : new FormulaAst.Unary("-", f);
            }
            case Expr.Not n -> new FormulaAst.Unary("not", node(n.e()));
            case Expr.And a -> bool("and", a.l(), a.r());
            case Expr.Or o -> bool("or", o.l(), o.r());
            case Expr.Ternary t -> new FormulaAst.IfExp(node(t.then()), node(t.cond()), node(t.otherwise()));
            case Expr.ListLit l -> new FormulaAst.ListLit(l.items().stream().map(this::node).toList());
            case Expr.In i -> new FormulaAst.Compare(operand(i.value()), List.of(i.negated() ? "not in" : "in"), List.of(node(i.list())));
            case Expr.Binary b when List.of("==", "!=", "<", "<=", ">", ">=").contains(b.op()) ->
                    new FormulaAst.Compare(operand(b.l()), List.of(b.op()), List.of(operand(b.r())));
            case Expr.Binary b when List.of("+", "-", "*", "/", "%").contains(b.op()) -> new FormulaAst.Binary(b.op(), node(b.l()), node(b.r()));
            case Expr.Field f -> field(f);
            case Expr.Call c -> call(c);
            default -> throw new FormulaException("'" + com.ash.drishti.rachana.el.Printer.print(e) + "' is not part of a formula: arithmetic, comparisons "
                    + "and functions of aggregates", at(e));
        };
    }

    /** A comparison's side: never itself a comparison ({@code a < b < c} is written {@code a < b && b < c}). */
    private FormulaAst operand(Expr e) {
        FormulaAst f = node(e);
        if (f instanceof FormulaAst.Compare) {
            throw new FormulaException("a comparison of a comparison: write a < b && b < c", at(e));
        }
        return f;
    }

    private FormulaAst bool(String op, Expr l, Expr r) {
        List<FormulaAst> values = new ArrayList<>();
        for (Expr side : List.of(l, r)) {
            FormulaAst f = node(side);
            if (f instanceof FormulaAst.BoolOp b && b.op().equals(op)) {
                values.addAll(b.values());                               // a && b && c is one and of three, as it was
            } else {
                values.add(f);
            }
        }
        return new FormulaAst.BoolOp(op, values);
    }

    private FormulaAst field(Expr.Field f) {
        List<String> parts = new ArrayList<>();
        Expr e = f;
        while (e instanceof Expr.Field x) {
            parts.add(0, x.name());
            e = x.target();
        }
        if (!(e instanceof Expr.RowOrRoot)) {
            throw new FormulaException("'" + com.ash.drishti.rachana.el.Printer.print(f) + "' is not a field of the source", at(f));
        }
        String name = String.join(".", parts);
        if (OLD_WORDS.contains(name)) {
            throw new FormulaException("'" + name + "' is the old syntax: a formula is Rachana (true, false, null, &&, ||, !, c ? a : b)", at(f));
        }
        if (!inAgg) {
            throw new FormulaException(name + " is a row value: aggregate it (sum(" + name + "), avg(" + name + "), count(" + name + "))", at(f));
        }
        return new FormulaAst.Field(name);
    }

    private FormulaAst call(Expr.Call c) {
        String w = c.name();
        String fn = Functions.oneName(w).toLowerCase(Locale.ROOT);
        int at = at(c);
        boolean agg = AGGREGATES.containsKey(fn);
        if (!agg && !SCALARS.containsKey(fn)) {
            throw new FormulaException("no function '" + w + "' in a formula: aggregates " + new java.util.TreeSet<>(AGGREGATES.keySet()) + "; row functions "
                    + new java.util.TreeSet<>(SCALARS.keySet()), at);
        }
        if (agg && inAgg) {
            throw new FormulaException(w + "(...) inside another aggregate: an aggregate takes row values", at);
        }
        List<Expr> positional = c.args().stream().filter(a -> !(a instanceof Expr.Absent)).toList();
        FormulaAst where = null;
        boolean outer = inAgg;
        inAgg = inAgg || agg;
        List<FormulaAst> args = new ArrayList<>();
        List<Double> numbers = new ArrayList<>();
        try {
            for (Map.Entry<String, Expr> k : c.named().entrySet()) {
                if (!agg || !k.getKey().equals("where")) {
                    throw new FormulaException("'" + k.getKey() + " =' is not an argument of " + w + (agg ? " (an aggregate takes where =)" : ""), at);
                }
                where = node(k.getValue());
            }
            for (Expr a : positional) {
                if (agg && !args.isEmpty() && a instanceof Expr.Lit l && l.value() instanceof Number n) {
                    numbers.add(n.doubleValue());                             // a built-in's numeric parameter: a percentile's q
                } else {
                    if (!numbers.isEmpty()) {
                        throw new FormulaException(w + ": the row values come before the numbers", at(a));
                    }
                    args.add(node(a));
                }
            }
        } finally {
            inAgg = outer;
        }
        if (!agg) {
            int n = SCALARS.get(fn);
            if (n > 0 && args.size() != n || n < 0 && args.isEmpty()) {
                throw new FormulaException(w + " takes " + (n > 0 ? n + " argument" + (n == 1 ? "" : "s") : "one or more arguments"), at);
            }
            return new FormulaAst.Scalar(fn, args);
        }
        int n = AGGREGATES.get(fn);
        if (n >= 0 && args.size() != n || n < 0 && args.size() > 1) {
            throw new FormulaException(w + " takes " + (n < 0 ? "no row value (count of rows) or one" : n + " row value" + (n == 1 ? "" : "s"))
                    + ", not " + args.size(), at);
        }
        if (fn.equals("percentile") && numbers.size() != 1) {
            throw new FormulaException(w + "(x, q): q between 0 and 1", at);
        }
        return new FormulaAst.Agg(fn, args, numbers, where);
    }
}
