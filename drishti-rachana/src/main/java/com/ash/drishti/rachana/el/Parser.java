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
package com.ash.drishti.rachana.el;

import com.ash.drishti.rachana.el.Token.Type;
import com.ash.drishti.rachana.el.check.Signatures;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive-descent parser for Rachana-EL. Grammar (EBNF):
 *
 * <pre>
 * expr     = or [ "?" expr ":" expr ] ;
 * or       = and { "||" and } ;
 * and      = equality { "&amp;&amp;" equality } ;
 * equality = compare { ("==" | "!=") compare } ;
 * compare  = sum { ("&lt;" | "&lt;=" | "&gt;" | "&gt;=") sum | ["not"] "in" sum } ;
 * sum      = product { ("+" | "-") product } ;
 * product  = unary { ("*" | "/" | "%") unary } ;
 * unary    = ("!" | "-") unary | postfix ;
 * postfix  = primary { "." ident | "[" expr "]" | "[?" expr "]" } ;
 * primary  = number | string | "true" | "false" | "null" | "$" | "@" | "^" { "^" } | "#index"
 *          | "[" [ expr { "," expr } [","] ] "]"
 *          | ident "(" [ arg { "," arg } ] ")" | ident | "(" expr ")" ;
 * arg      = [ ident "=" ] expr ;
 * </pre>
 *
 * A bare identifier is a field of the current row inside a row, otherwise of the document. {@code ^} is the current element's parent
 * ({@code ^^} its parent). {@code [a, b]} is a list; {@code x in list} and {@code x not in list} test membership strictly (an empty
 * list holds nothing). A named argument {@code name = expr} is bound by the function's signature: to its place for a positional
 * parameter, kept by name for a keyword-only one ({@code where}, {@code by}, {@code ignore} ...) (docs/architecture/ARCHITECTURE.md §28 2.1).
 *
 * <p>Both the parse and the tree it builds are bounded by {@link ElLimits}: the parser counts how deep it has recursed, and every node
 * records how deep its subtree is (a chain {@code a + b + c} parses in a loop but builds a tree as deep as it is long, and evaluation
 * walks that tree recursively). Past either bound the expression is a compile error ({@code DRS-2101}) at the offending position, never
 * a {@link StackOverflowError}. Every node records where it was written ({@link #spans}), so the checker can point at it.
 */
final class Parser {

    private final List<Token> tokens;
    private final ElLimits limits;
    private final String src;
    /** Depth of each composite node built so far (leaves are 1); identity, since equal records may differ in place. */
    private final Map<Expr, Integer> depths = new IdentityHashMap<>();
    /** Where each node was written: {start, end} character offsets. */
    private final Map<Expr, int[]> spans = new IdentityHashMap<>();
    private int p;
    private int nesting;
    /** The names {@code let} has bound around the part being parsed, innermost first. */
    private final java.util.ArrayDeque<String> letNames = new java.util.ArrayDeque<>();
    /** Above zero inside {@code reduce}'s expression, where {@code @acc} is the value so far. */
    private int accDepth;

    private Parser(String src, List<Token> tokens, ElLimits limits) {
        this.src = src;
        this.tokens = tokens;
        this.limits = limits;
    }

    /** The most values a list written in an expression holds. */
    static final int MAX_LIST_ITEMS = 1_000;

    /** The message when the expression stops where a value is still needed ({@code $.mtm >}). */
    static final String ENDS_EARLY = "the expression ends early: a value is missing";

    /** A parsed expression and where each of its nodes was written. */
    record Parsed(Expr expr, Map<Expr, int[]> spans) {}

    static Expr parse(String src) {
        return parse(src, ElLimits.DEFAULTS);
    }

    static Expr parse(String src, ElLimits limits) {
        return parseWithSpans(src, limits).expr();
    }

    /** Parses with extra function names (measure functions) that are later expanded: arguments are parsed, nothing else is checked. */
    static Expr parse(String src, Set<String> macros) {
        Parser parser = new Parser(src, Lexer.tokens(src), ElLimits.DEFAULTS);
        parser.macros = macros;
        Expr e = parser.expr();
        return e;
    }

    private Set<String> macros = Set.of();

    static Parsed parseWithSpans(String src, ElLimits limits) {
        if (src.length() > limits.maxLength()) {
            throw new ElException("expression is longer than " + limits.maxLength()
                    + " characters (drishti.rachana.max-expression-length)", limits.maxLength());
        }
        Parser parser = new Parser(src, Lexer.tokens(src), limits);
        Expr e = parser.expr();
        if (parser.peek().type() != Type.EOF) {
            throw new ElException("unexpected '" + parser.peek().text() + "'", parser.peek().pos());
        }
        return new Parsed(e, parser.spans);
    }

    private Token peek() {
        return tokens.get(p);
    }

    private Token next() {
        return tokens.get(p++);
    }

    private boolean accept(Type t, String text) {
        if (peek().is(t, text)) {
            p++;
            return true;
        }
        return false;
    }

    private Token expect(Type t, String what) {
        if (peek().type() != t) {
            throw new ElException("expected " + what + (peek().type() == Type.EOF ? " but the expression ends" : " but found '" + peek().text() + "'"),
                    peek().pos());
        }
        return next();
    }

    /** Enters one level of recursion; past the bound, a located compile error instead of a stack overflow. */
    private void descend() {
        if (++nesting > limits.maxDepth()) {
            throw tooDeep();
        }
    }

    private ElException tooDeep() {
        return new ElException("expression nested deeper than " + limits.maxDepth()
                + " levels (drishti.rachana.max-expression-depth)", peek().pos());
    }

    /** Records {@code e}'s subtree depth (one more than its deepest part), refusing a tree deeper than the bound. */
    private Expr node(Expr e, Expr... parts) {
        int d = 0;
        for (Expr part : parts) {
            d = Math.max(d, depths.getOrDefault(part, 1));
        }
        if (d + 1 > limits.maxDepth()) {
            throw tooDeep();
        }
        depths.put(e, d + 1);
        return e;
    }

    /** Records that {@code e} was written from {@code start} to where the parser now is (trailing blanks left out). */
    private Expr at(Expr e, int start) {
        int end = p > 0 ? Math.min(src.length(), peek().type() == Type.EOF ? src.length() : peek().pos()) : start;
        while (end > start && Character.isWhitespace(src.charAt(end - 1))) {
            end--;
        }
        spans.putIfAbsent(e, new int[] {start, Math.max(start, end)});
        return e;
    }

    private Expr expr() {
        descend();
        int s = peek().pos();
        try {
            Expr c = or();
            if (peek().type() == Type.QUESTION) {
                next();
                Expr a = expr();
                expect(Type.COLON, "':'");
                Expr b = expr();
                return at(node(new Expr.Ternary(c, a, b), c, a, b), s);
            }
            return c;
        } finally {
            nesting--;
        }
    }

    private Expr or() {
        int s = peek().pos();
        Expr l = and();
        while (accept(Type.OP, "||")) {
            Expr r = and();
            l = at(node(new Expr.Or(l, r), l, r), s);
        }
        return l;
    }

    private Expr and() {
        int s = peek().pos();
        Expr l = equality();
        while (accept(Type.OP, "&&")) {
            Expr r = equality();
            l = at(node(new Expr.And(l, r), l, r), s);
        }
        return l;
    }

    private Expr equality() {
        int s = peek().pos();
        Expr l = compare();
        while (peek().is(Type.OP, "==") || peek().is(Type.OP, "!=")) {
            String op = next().text();
            Expr r = compare();
            l = at(node(new Expr.Binary(op, l, r), l, r), s);
        }
        return l;
    }

    private Expr compare() {
        int s = peek().pos();
        Expr l = sum();
        while (true) {
            if (peek().type() == Type.OP && List.of("<", "<=", ">", ">=").contains(peek().text())) {
                String op = next().text();
                Expr r = sum();
                l = at(node(new Expr.Binary(op, l, r), l, r), s);
            } else if (peek().is(Type.IDENT, "in")) {
                next();
                Expr r = sum();
                l = at(node(new Expr.In(l, r, false), l, r), s);
            } else if (peek().is(Type.IDENT, "not") && tokens.get(p + 1).is(Type.IDENT, "in")) {
                p += 2;
                Expr r = sum();
                l = at(node(new Expr.In(l, r, true), l, r), s);
            } else {
                return l;
            }
        }
    }

    private Expr sum() {
        int s = peek().pos();
        Expr l = product();
        while (peek().is(Type.OP, "+") || peek().is(Type.OP, "-")) {
            String op = next().text();
            Expr r = product();
            l = at(node(new Expr.Binary(op, l, r), l, r), s);
        }
        return l;
    }

    private Expr product() {
        int s = peek().pos();
        Expr l = unary();
        while (peek().is(Type.OP, "*") || peek().is(Type.OP, "/") || peek().is(Type.OP, "%")) {
            String op = next().text();
            Expr r = unary();
            l = at(node(new Expr.Binary(op, l, r), l, r), s);
        }
        return l;
    }

    private Expr unary() {
        int s = peek().pos();
        boolean not = accept(Type.OP, "!");
        if (!not && !accept(Type.OP, "-")) {
            return postfix();
        }
        descend();
        try {
            Expr e = unary();
            return at(node(not ? new Expr.Not(e) : new Expr.Neg(e), e), s);
        } finally {
            nesting--;
        }
    }

    private Expr postfix() {
        int s = peek().pos();
        Expr e = primary();
        String path = e instanceof Expr.Root ? "$" : null;
        while (true) {
            Token t = peek();
            if (t.type() == Type.DOT) {
                next();
                String name = expect(Type.IDENT, "a field name").text();
                path = path == null ? null : path + "." + name;
                e = e instanceof Expr.Each || e instanceof Expr.Pluck ? node(new Expr.Pluck(e, name), e) : node(new Expr.Field(e, name, path), e);
            } else if (t.type() == Type.LBRACKET && tokens.get(p + 1).is(Type.OP, "*") && tokens.get(p + 2).type() == Type.RBRACKET) {
                p += 3;                                          // [*]: every element; a field after it is that field of each
                e = node(new Expr.Each(e), e);
                path = null;
            } else if (t.type() == Type.LBRACKET) {
                next();
                Expr idx = expr();
                expect(Type.RBRACKET, "']'");
                path = path != null && idx instanceof Expr.Lit lit ? path + "[" + Values.text(lit.value()) + "]" : null;
                e = node(new Expr.At(e, idx, path), e, idx);
            } else if (t.type() == Type.FILTER) {
                next();
                Expr pred = expr();
                expect(Type.RBRACKET, "']'");
                e = node(new Expr.Filter(e, pred), e, pred);
                path = null;
            } else {
                return e;
            }
            at(e, s);
        }
    }

    private Expr primary() {
        Token t = next();
        int s = t.pos();
        switch (t.type()) {
            case NUMBER:
                try {
                    double d = Double.parseDouble(t.text());
                    return at(new Expr.Lit(Values.normalise(d)), s);
                } catch (NumberFormatException e) {
                    throw new ElException("bad number '" + t.text() + "'", t.pos());
                }
            case STRING:
                return at(new Expr.Lit(t.text()), s);
            case DOLLAR:
                return at(new Expr.Root(), s);
            case AT:
                return at(new Expr.Row(), s);
            case CARET: {
                int up = 1;
                while (peek().type() == Type.CARET && peek().pos() == s + up) {
                    next();
                    up++;
                }
                return at(new Expr.Parent(up), s);
            }
            case EVENT:
                if ("acc".equals(t.text()) && accDepth > 0) {
                    return at(new Expr.Local(ListFunctions.ACC), s);    // @acc inside reduce: the value so far, never a panel's events
                }
                return at(new Expr.Event(t.text()), s);
            case SRC:
                return at(new Expr.Src(), s);
            case HASH_INDEX:
                return at(new Expr.Index(), s);
            case LBRACKET:
                return list(s);
            case LPAREN: {
                Expr e = expr();
                expect(Type.RPAREN, "')'");
                return e;
            }
            case IDENT:
                return identifier(t);
            case EOF:
                throw new ElException(ENDS_EARLY, t.pos());
            default:
                throw new ElException("unexpected '" + t.text() + "'", t.pos());
        }
    }

    /** {@code [a, b, c]} (the opening bracket read): a list literal; a trailing comma is allowed, {@code []} is the empty list. */
    private Expr list(int s) {
        List<Expr> items = new ArrayList<>();
        while (peek().type() != Type.RBRACKET) {
            items.add(expr());
            if (peek().type() != Type.COMMA) {
                break;
            }
            next();
        }
        expect(Type.RBRACKET, "']' to close the list");
        if (items.size() > MAX_LIST_ITEMS) {
            throw new ElException("a list written in an expression holds at most " + MAX_LIST_ITEMS + " values", s);
        }
        return at(node(new Expr.ListLit(items), items.toArray(Expr[]::new)), s);
    }

    private Expr identifier(Token t) {
        int s = t.pos();
        switch (t.text()) {
            case "true":
                return at(new Expr.Lit(Boolean.TRUE), s);
            case "false":
                return at(new Expr.Lit(Boolean.FALSE), s);
            case "null":
                return at(new Expr.Lit(null), s);
            default:
                break;
        }
        if ("ctl".equals(t.text()) && peek().type() == Type.DOT && tokens.get(p + 1).type() == Type.IDENT
                && tokens.get(p + 2).type() != Type.LPAREN) {
            next();                                              // ctl.<id>: the value of a control, never a field of the data
            return at(new Expr.Ctl(next().text()), s);
        }
        if (peek().type() != Type.LPAREN) {
            if (letNames.contains(t.text())) {
                return at(new Expr.Local(t.text()), s);            // a name let(...) bound: it hides a field of the same name
            }
            Expr row = at(new Expr.RowOrRoot(), s);
            return at(new Expr.Field(row, t.text(), null), s);
        }
        next();
        String name = t.text();
        int[] arity = macros.contains(name) ? new int[] {0, 64} : Functions.ARITY.get(name);
        if (arity == null) {
            throw new ElException("unknown function '" + name + "'; known: " + String.join(", ", new java.util.TreeSet<>(Functions.ARITY.keySet())),
                    t.pos());
        }
        if ("let".equals(name)) {
            return at(let(t, arity), s);
        }
        List<Expr> args = new ArrayList<>();
        Map<String, Expr> named = new LinkedHashMap<>();
        List<Token> names = new ArrayList<>();
        if (peek().type() != Type.RPAREN) {
            do {
                if (peek().type() == Type.RPAREN) {
                    break;                                        // a trailing comma
                }
                if (peek().type() == Type.IDENT && tokens.get(p + 1).is(Type.OP, "=")) {
                    Token n = next();
                    next();
                    if (named.containsKey(n.text())) {
                        throw new ElException(name + ": " + n.text() + " is named twice", n.pos());
                    }
                    named.put(n.text(), expr());
                    names.add(n);
                    continue;
                }
                if (!named.isEmpty()) {
                    throw new ElException(name + ": a value without a name follows a named one; write the named arguments last", peek().pos());
                }
                boolean accumulator = "reduce".equals(name) && args.size() == 2;
                accDepth += accumulator ? 1 : 0;
                try {
                    args.add(expr());
                } finally {
                    accDepth -= accumulator ? 1 : 0;
                }
            } while (peek().type() == Type.COMMA && next() != null);
        }
        expect(Type.RPAREN, "')'");
        if (macros.contains(name)) {
            return at(new Expr.Call(name, null, args, named), s);
        }
        if (!named.isEmpty()) {
            bind(name, args, named, names);
        }
        if (args.size() < arity[0] || args.size() > arity[1]) {
            throw new ElException(name + " takes " + arity[0] + (arity[0] == arity[1] ? "" : "-" + arity[1])
                    + " argument(s), got " + args.size(), t.pos());
        }
        String problem = Functions.check(name, args);
        if (problem != null) {
            throw new ElException(name + ": " + problem, t.pos());
        }
        List<Expr> all = new ArrayList<>(args);
        all.addAll(named.values());
        Expr[] parts = all.toArray(Expr[]::new);
        switch (name) {
            case "if":
                return at(node(new Expr.Ternary(args.get(0), args.get(1), args.size() > 2 ? args.get(2) : new Expr.Lit(null)), parts), s);
            case "ifs":
                return ifs(args, s);
            case "reduce":
                if (addsUp(args.get(2))) {
                    throw new ElException("reduce does not add up a list: use sum(...) for a total (aggregates run in the pivot engine), "
                            + "concat(...) to join text", t.pos());
                }
                break;
            default:
                break;
        }
        ElForm form = Functions.form(name);
        if (form != null) {
            if (!named.isEmpty()) {
                throw new ElException(name + " takes no named arguments", t.pos());
            }
            return at(node(new Expr.Form(name, form, List.copyOf(args)), parts), s);
        }
        return at(node(new Expr.Call(name, Functions.get(name), List.copyOf(args), named), parts), s);
    }

    /**
     * Binds the named arguments by the function's signature: a positional parameter's value moves to its place ({@link Expr.Absent}
     * filling the optional places before it); a keyword-only one stays in {@code named}.
     */
    private static void bind(String fn, List<Expr> args, Map<String, Expr> named, List<Token> names) {
        Signatures.Signature sig = Signatures.get().function(Functions.oneName(fn)).orElse(null);
        if (sig == null) {
            throw new ElException(fn + " takes no named arguments", names.get(0).pos());
        }
        // Rachana's own functions (runs R) take every argument by place, in the signature's order; the engine's keep their keyword-only ones by name
        boolean rachana = "R".equals(sig.runs());
        List<Signatures.Param> positional = rachana ? sig.params() : sig.params().stream().filter(x -> !x.keywordOnly()).toList();
        int k = 0;
        for (Map.Entry<String, Expr> e : List.copyOf(named.entrySet())) {
            Token at = names.get(k++);
            Signatures.Param param = sig.param(e.getKey()).orElse(null);
            if (param == null) {
                List<String> known = sig.params().stream().map(Signatures.Param::name).toList();
                throw new ElException(fn + " has no argument named " + e.getKey() + (known.isEmpty() ? "" : "; its arguments: " + known), at.pos());
            }
            if (param.keywordOnly() && !rachana) {
                continue;
            }
            int place = positional.indexOf(param);
            if (place < args.size() && !(args.get(place) instanceof Expr.Absent)) {
                throw new ElException(fn + ": " + e.getKey() + " is given twice (by place and by name)", at.pos());
            }
            while (args.size() <= place) {
                args.add(new Expr.Absent());
            }
            args.set(place, e.getValue());
            named.remove(e.getKey());
        }
    }

    /** {@code ifs(c1, a1, c2, a2, ..., [else])}: the conditional {@code c1 ? a1 : c2 ? a2 : ... : else} (else empty). */
    private Expr ifs(List<Expr> args, int s) {
        int pairs = args.size() / 2;
        Expr out = args.size() % 2 == 1 ? args.get(args.size() - 1) : new Expr.Lit(null);
        for (int i = pairs - 1; i >= 0; i--) {
            Expr c = args.get(2 * i);
            Expr a = args.get(2 * i + 1);
            out = at(node(new Expr.Ternary(c, a, out), c, a, out), s);
        }
        return out;
    }

    /** True when {@code e} adds or subtracts {@code @acc} (directly, or in a branch of a conditional): a sum written by hand. */
    private static boolean addsUp(Expr e) {
        if (e instanceof Expr.Binary b && ("+".equals(b.op()) || "-".equals(b.op()))) {
            return isAcc(b.l()) || isAcc(b.r()) || addsUp(b.l()) || addsUp(b.r());
        }
        if (e instanceof Expr.Ternary t) {
            return addsUp(t.then()) || addsUp(t.otherwise());
        }
        return false;
    }

    private static boolean isAcc(Expr e) {
        return e instanceof Expr.Local l && ListFunctions.ACC.equals(l.name());
    }

    private static final java.util.Set<String> RESERVED = java.util.Set.of("true", "false", "null", "ctl", "in", "not");

    /**
     * {@code let(name = expr, ..., result)}: each {@code name = expr} binds a name the later values and the result read as a
     * bare word (hiding a field of that name); the last argument is the result.
     */
    private Expr let(Token t, int[] arity) {
        List<String> names = new ArrayList<>();
        List<Expr> values = new ArrayList<>();
        int bound = 0;
        try {
            while (peek().type() == Type.IDENT && tokens.get(p + 1).is(Type.OP, "=")) {
                Token n = next();
                next();
                if (RESERVED.contains(n.text())) {
                    throw new ElException("let: '" + n.text() + "' cannot be a name", n.pos());
                }
                if (names.contains(n.text())) {
                    throw new ElException("let: '" + n.text() + "' is named twice", n.pos());
                }
                if (names.size() + 1 >= arity[1]) {
                    throw new ElException("let takes at most " + (arity[1] - 1) + " names", n.pos());
                }
                values.add(expr());
                names.add(n.text());
                letNames.push(n.text());
                bound++;
                expect(Type.COMMA, "',' and then the result");
            }
            if (names.isEmpty()) {
                throw new ElException("let needs a name = value before its result, as in let(q = $.QUANTITY, q * 2)", t.pos());
            }
            Expr body = expr();
            if (peek().type() == Type.COMMA) {
                throw new ElException("let: only the last argument is the result; each before it is written name = value", peek().pos());
            }
            expect(Type.RPAREN, "')'");
            List<Expr> parts = new ArrayList<>(values);
            parts.add(body);
            return node(new Expr.Let(List.copyOf(names), List.copyOf(values), body), parts.toArray(Expr[]::new));
        } finally {
            for (int i = 0; i < bound; i++) {
                letNames.pop();
            }
        }
    }
}
