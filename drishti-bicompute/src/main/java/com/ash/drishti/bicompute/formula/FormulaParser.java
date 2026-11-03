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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The parser of a pivot formula (PIVOT_ENGINE.md section 8.2): DEL's syntax (BI_DESIGN.md section 3.3.2; every text it accepts is a valid
 * Python expression) for the measures of a pivot: arithmetic over aggregate calls of the built-ins, each aggregating a row expression with
 * an optional {@code where=} condition.
 *
 * <pre>
 * sum(PNL) / sum(abs(PNL))
 * sum(NOTIONAL_USD, where=DESK == 'RATES') / sum(NOTIONAL_USD)
 * wavg(PRICE, QUANTITY)
 * percentile(PNL_1D, 0.99) - percentile(PNL_1D, 0.01)
 * count(where=PNL &lt; 0) / count()
 * </pre>
 *
 * A field must be inside an aggregate (a measure is a figure of a cell, not of a row); an aggregate inside an aggregate is refused; the
 * text is at most 2,000 characters, nesting at most 64 deep. Pure and thread-safe.
 */
public final class FormulaParser {

    public static final int MAX_LENGTH = 2000;
    public static final int MAX_DEPTH = 64;

    /**
     * The aggregates, under their one name each (FUNCTION_CATALOGUE.md section 4), and their row inputs (-1: none, the rows, or one). Each
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
     * The scalar functions of row values (and, outside an aggregate, of figures), under their one name each (FUNCTION_CATALOGUE.md sections
     * 1 and 4), and how many arguments each takes (-1: one or more).
     */
    public static final Map<String, Integer> SCALARS = Map.ofEntries(Map.entry("abs", 1), Map.entry("sqrt", 1), Map.entry("ln", 1),
            Map.entry("log10", 1), Map.entry("exp", 1), Map.entry("power", 2), Map.entry("floor", 1), Map.entry("ceiling", 1),
            Map.entry("sign", 1), Map.entry("round", -1), Map.entry("coalesce", -1), Map.entry("greatest", -1), Map.entry("least", -1),
            Map.entry("ratio", 2));

    private static final Set<String> REFUSED = Set.of("lambda", "for", "import", "from", "await", "yield", "def", "class", "del", "global",
            "nonlocal", "return", "assert", "with", "try", "while", "raise", "pass", "is");

    /** A formula the parser refuses, with where. */
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

    enum T { NUM, STR, NAME, OP, EOF }

    record Tok(T type, String text, int start, Object value) {}

    private final List<Tok> toks;
    private int p;
    private int depth;
    private boolean inAgg;

    private FormulaParser(List<Tok> toks) {
        this.toks = toks;
    }

    /** The formula's tree; refused in words when it is not a measure formula. */
    public static FormulaAst parse(String text) {
        if (text == null || text.isBlank()) {
            throw new FormulaException("a formula is empty", -1);
        }
        if (text.length() > MAX_LENGTH) {
            throw new FormulaException("a formula is at most " + MAX_LENGTH + " characters", MAX_LENGTH);
        }
        FormulaParser fp = new FormulaParser(lex(text));
        FormulaAst e = fp.expr();
        if (fp.peek().type() != T.EOF) {
            throw new FormulaException("unexpected '" + fp.peek().text() + "'", fp.peek().start());
        }
        boolean[] agg = {false};
        FormulaAst.walk(e, n -> agg[0] |= n instanceof FormulaAst.Agg);
        if (!agg[0]) {
            throw new FormulaException("a measure aggregates: write sum(x), count(), avg(x), ... (no aggregate in the formula)", -1);
        }
        return e;
    }

    /** True when the text reads as a formula (it parses); the plain built-in call {@code sum(X)} is a formula too. */
    public static boolean isFormula(String text) {
        try {
            parse(text);
            return true;
        } catch (FormulaException e) {
            return false;
        }
    }

    // ---- lexer -----------------------------------------------------------------------------------------------------------------------

    static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_')) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                    j++;
                    if (j < s.length() && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                        j++;
                    }
                    while (j < s.length() && Character.isDigit(s.charAt(j))) {
                        j++;
                    }
                }
                String t = s.substring(i, j);
                try {
                    out.add(new Tok(T.NUM, t, i, Double.parseDouble(t.replace("_", ""))));
                } catch (NumberFormatException e) {
                    throw new FormulaException("'" + t + "' is not a number", i);
                }
                i = j;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.'
                        && j + 1 < s.length() && (Character.isLetter(s.charAt(j + 1)) || s.charAt(j + 1) == '_'))) {
                    j++;
                }
                out.add(new Tok(T.NAME, s.substring(i, j), i, null));
                i = j;
            } else if (c == '\'' || c == '"') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && j + 1 < s.length()) {
                        j++;
                    }
                    b.append(s.charAt(j++));
                }
                if (j >= s.length()) {
                    throw new FormulaException("a text is not closed", i);
                }
                out.add(new Tok(T.STR, s.substring(i, j + 1), i, b.toString()));
                i = j + 1;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (List.of("**", "//", "==", "!=", "<=", ">=").contains(two)) {
                    out.add(new Tok(T.OP, two, i, null));
                    i += 2;
                } else if ("+-*/%<>(),=[]".indexOf(c) >= 0) {
                    out.add(new Tok(T.OP, String.valueOf(c), i, null));
                    i++;
                } else {
                    throw new FormulaException("'" + c + "' is not part of a formula", i);
                }
            }
        }
        out.add(new Tok(T.EOF, "end of the formula", s.length(), null));
        return out;
    }

    // ---- parser ----------------------------------------------------------------------------------------------------------------------

    private Tok peek() {
        return toks.get(p);
    }

    private Tok next() {
        return toks.get(p++);
    }

    private boolean op(String o) {
        Tok t = peek();
        if (t.type() == T.OP && t.text().equals(o)) {
            p++;
            return true;
        }
        return false;
    }

    private boolean word(String w) {
        Tok t = peek();
        if (t.type() == T.NAME && t.text().equals(w)) {
            p++;
            return true;
        }
        return false;
    }

    private void expect(String o) {
        if (!op(o)) {
            throw new FormulaException("expected '" + o + "', found '" + peek().text() + "'", peek().start());
        }
    }

    private FormulaAst expr() {
        if (++depth > MAX_DEPTH) {
            throw new FormulaException("the formula nests more than " + MAX_DEPTH + " deep", peek().start());
        }
        try {
            FormulaAst body = orExpr();
            if (word("if")) {
                FormulaAst test = orExpr();
                if (!word("else")) {
                    throw new FormulaException("x if condition else y: the else is missing", peek().start());
                }
                return new FormulaAst.IfExp(body, test, expr());
            }
            return body;
        } finally {
            depth--;
        }
    }

    private FormulaAst orExpr() {
        List<FormulaAst> v = new ArrayList<>(List.of(andExpr()));
        while (word("or")) {
            v.add(andExpr());
        }
        return v.size() == 1 ? v.get(0) : new FormulaAst.BoolOp("or", v);
    }

    private FormulaAst andExpr() {
        List<FormulaAst> v = new ArrayList<>(List.of(notExpr()));
        while (word("and")) {
            v.add(notExpr());
        }
        return v.size() == 1 ? v.get(0) : new FormulaAst.BoolOp("and", v);
    }

    private FormulaAst notExpr() {
        if (word("not")) {
            return new FormulaAst.Unary("not", notExpr());
        }
        return comparison();
    }

    private FormulaAst comparison() {
        FormulaAst first = arith();
        List<String> ops = new ArrayList<>();
        List<FormulaAst> rest = new ArrayList<>();
        while (true) {
            Tok t = peek();
            String o = null;
            if (t.type() == T.OP && List.of("==", "!=", "<", "<=", ">", ">=").contains(t.text())) {
                p++;
                o = t.text();
            } else if (t.type() == T.NAME && t.text().equals("in")) {
                p++;
                o = "in";
            } else if (t.type() == T.NAME && t.text().equals("not") && toks.get(p + 1).type() == T.NAME && toks.get(p + 1).text().equals("in")) {
                p += 2;
                o = "not in";
            }
            if (o == null) {
                break;
            }
            ops.add(o);
            rest.add(arith());
        }
        return ops.isEmpty() ? first : new FormulaAst.Compare(first, ops, rest);
    }

    private FormulaAst arith() {
        FormulaAst l = term();
        while (true) {
            if (op("+")) {
                l = new FormulaAst.Binary("+", l, term());
            } else if (op("-")) {
                l = new FormulaAst.Binary("-", l, term());
            } else {
                return l;
            }
        }
    }

    private FormulaAst term() {
        FormulaAst l = factor();
        while (true) {
            Tok t = peek();
            if (t.type() == T.OP && List.of("*", "/", "//", "%").contains(t.text())) {
                p++;
                l = new FormulaAst.Binary(t.text(), l, factor());
            } else {
                return l;
            }
        }
    }

    private FormulaAst factor() {
        if (op("-")) {
            FormulaAst f = factor();
            return f instanceof FormulaAst.Num n ? new FormulaAst.Num(-n.value()) : new FormulaAst.Unary("-", f);
        }
        if (op("+")) {
            return factor();
        }
        FormulaAst base = primary();
        if (op("**")) {
            return new FormulaAst.Binary("**", base, factor());
        }
        return base;
    }

    private FormulaAst primary() {
        Tok t = next();
        switch (t.type()) {
            case NUM -> {
                return new FormulaAst.Num((Double) t.value());
            }
            case STR -> {
                return new FormulaAst.Str((String) t.value());
            }
            case OP -> {
                if (t.text().equals("(")) {
                    FormulaAst e = expr();
                    expect(")");
                    return e;
                }
                if (t.text().equals("[")) {
                    List<FormulaAst> items = new ArrayList<>();
                    if (!op("]")) {
                        do {
                            if (peek().type() == T.OP && peek().text().equals("]")) {
                                break;
                            }
                            items.add(expr());
                        } while (op(","));
                        expect("]");
                    }
                    return new FormulaAst.ListLit(items);
                }
                throw new FormulaException("unexpected '" + t.text() + "'", t.start());
            }
            case NAME -> {
                return name(t);
            }
            default -> throw new FormulaException("the formula ends too soon", t.start());
        }
    }

    private FormulaAst name(Tok t) {
        String w = t.text();
        if (REFUSED.contains(w)) {
            throw new FormulaException("'" + w + "' is not DEL: a formula is an expression of aggregates", t.start());
        }
        switch (w) {
            case "True" -> {
                return new FormulaAst.Bool(true);
            }
            case "False" -> {
                return new FormulaAst.Bool(false);
            }
            case "None" -> {
                return new FormulaAst.NoneLit();
            }
            case "true", "false", "null" -> throw new FormulaException("DEL writes True, False and None, not " + w, t.start());
            default -> { }
        }
        if (!(peek().type() == T.OP && peek().text().equals("("))) {
            if (!inAgg) {
                throw new FormulaException(w + " is a row value: aggregate it (sum(" + w + "), avg(" + w + "), count(" + w + "))", t.start());
            }
            return new FormulaAst.Field(w);
        }
        p++;
        String fn = w.toLowerCase(Locale.ROOT);
        List<FormulaAst> args = new ArrayList<>();
        List<Double> numbers = new ArrayList<>();
        FormulaAst where = null;
        boolean agg = AGGREGATES.containsKey(fn);
        if (!agg && !SCALARS.containsKey(fn)) {
            throw new FormulaException("no function '" + w + "': aggregates " + new java.util.TreeSet<>(AGGREGATES.keySet()) + "; row functions "
                    + new java.util.TreeSet<>(SCALARS.keySet()), t.start());
        }
        if (agg && inAgg) {
            throw new FormulaException(w + "(...) inside another aggregate: an aggregate takes row values", t.start());
        }
        boolean outer = inAgg;
        inAgg = inAgg || agg;
        try {
            if (!op(")")) {
                do {
                    if (peek().type() == T.OP && peek().text().equals(")")) {
                        break;
                    }
                    if (peek().type() == T.NAME && toks.get(p + 1).type() == T.OP && toks.get(p + 1).text().equals("=")) {
                        Tok k = next();
                        p++;
                        if (!agg || !k.text().equals("where")) {
                            throw new FormulaException("'" + k.text() + "=' is not an argument of " + w + (agg ? " (an aggregate takes where=)" : ""),
                                    k.start());
                        }
                        where = expr();
                    } else if (agg && peek().type() == T.NUM && !args.isEmpty()) {
                        numbers.add((Double) next().value());
                    } else {
                        if (!numbers.isEmpty()) {
                            throw new FormulaException(w + ": the row values come before the numbers", peek().start());
                        }
                        args.add(expr());
                    }
                } while (op(","));
                expect(")");
            }
        } finally {
            inAgg = outer;
        }
        if (!agg) {
            int n = SCALARS.get(fn);
            if (n > 0 && args.size() != n || n < 0 && args.isEmpty()) {
                throw new FormulaException(w + " takes " + (n > 0 ? n + " argument" + (n == 1 ? "" : "s") : "one or more arguments"), t.start());
            }
            return new FormulaAst.Scalar(fn, args);
        }
        int n = AGGREGATES.get(fn);
        if (n >= 0 && args.size() != n || n < 0 && args.size() > 1) {
            throw new FormulaException(w + " takes " + (n < 0 ? "no row value (count of rows) or one" : n + " row value" + (n == 1 ? "" : "s"))
                    + ", not " + args.size(), t.start());
        }
        if (fn.equals("percentile") && numbers.size() != 1) {
            throw new FormulaException(w + "(x, q): q between 0 and 1", t.start());
        }
        return new FormulaAst.Agg(fn, args, numbers, where);
    }
}
