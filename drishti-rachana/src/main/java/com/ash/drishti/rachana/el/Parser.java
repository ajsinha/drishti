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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for Rachana-EL. Grammar (EBNF):
 *
 * <pre>
 * expr     = or [ "?" expr ":" expr ] ;
 * or       = and { "||" and } ;
 * and      = equality { "&amp;&amp;" equality } ;
 * equality = compare { ("==" | "!=") compare } ;
 * compare  = sum { ("&lt;" | "&lt;=" | "&gt;" | "&gt;=") sum } ;
 * sum      = product { ("+" | "-") product } ;
 * product  = unary { ("*" | "/" | "%") unary } ;
 * unary    = ("!" | "-") unary | postfix ;
 * postfix  = primary { "." ident | "[" expr "]" | "[?" expr "]" } ;
 * primary  = number | string | "true" | "false" | "null" | "$" | "@" | "#index"
 *          | ident "(" [ expr { "," expr } ] ")" | ident | "(" expr ")" ;
 * </pre>
 *
 * A bare identifier is a field of the current row inside a row, otherwise of the document.
 *
 * <p>Both the parse and the tree it builds are bounded by {@link ElLimits}: the parser counts how deep it has
 * recursed, and every node records how deep its subtree is (a chain {@code a + b + c} parses in a loop but builds a
 * tree as deep as it is long, and evaluation walks that tree recursively). Past either bound the expression is a
 * compile error ({@code DRS-2101}) at the offending position, never a {@link StackOverflowError}.
 */
final class Parser {

    private final List<Token> tokens;
    private final ElLimits limits;
    /** Depth of each composite node built so far (leaves are 1); identity, since equal records may differ in place. */
    private final Map<Expr, Integer> depths = new IdentityHashMap<>();
    private int p;
    private int nesting;

    private Parser(List<Token> tokens, ElLimits limits) {
        this.tokens = tokens;
        this.limits = limits;
    }

    static Expr parse(String src) {
        return parse(src, ElLimits.DEFAULTS);
    }

    static Expr parse(String src, ElLimits limits) {
        if (src.length() > limits.maxLength()) {
            throw new ElException("expression is longer than " + limits.maxLength()
                    + " characters (drishti.rachana.max-expression-length)", limits.maxLength());
        }
        Parser parser = new Parser(Lexer.tokens(src), limits);
        Expr e = parser.expr();
        if (parser.peek().type() != Type.EOF) {
            throw new ElException("unexpected '" + parser.peek().text() + "'", parser.peek().pos());
        }
        return e;
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
            throw new ElException("expected " + what + " but found '" + peek().text() + "'", peek().pos());
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

    private Expr expr() {
        descend();
        try {
            Expr c = or();
            if (peek().type() == Type.QUESTION) {
                next();
                Expr a = expr();
                expect(Type.COLON, "':'");
                Expr b = expr();
                return node(new Expr.Ternary(c, a, b), c, a, b);
            }
            return c;
        } finally {
            nesting--;
        }
    }

    private Expr or() {
        Expr l = and();
        while (accept(Type.OP, "||")) {
            Expr r = and();
            l = node(new Expr.Or(l, r), l, r);
        }
        return l;
    }

    private Expr and() {
        Expr l = equality();
        while (accept(Type.OP, "&&")) {
            Expr r = equality();
            l = node(new Expr.And(l, r), l, r);
        }
        return l;
    }

    private Expr equality() {
        Expr l = compare();
        while (peek().is(Type.OP, "==") || peek().is(Type.OP, "!=")) {
            String op = next().text();
            Expr r = compare();
            l = node(new Expr.Binary(op, l, r), l, r);
        }
        return l;
    }

    private Expr compare() {
        Expr l = sum();
        while (peek().type() == Type.OP && List.of("<", "<=", ">", ">=").contains(peek().text())) {
            String op = next().text();
            Expr r = sum();
            l = node(new Expr.Binary(op, l, r), l, r);
        }
        return l;
    }

    private Expr sum() {
        Expr l = product();
        while (peek().is(Type.OP, "+") || peek().is(Type.OP, "-")) {
            String op = next().text();
            Expr r = product();
            l = node(new Expr.Binary(op, l, r), l, r);
        }
        return l;
    }

    private Expr product() {
        Expr l = unary();
        while (peek().is(Type.OP, "*") || peek().is(Type.OP, "/") || peek().is(Type.OP, "%")) {
            String op = next().text();
            Expr r = unary();
            l = node(new Expr.Binary(op, l, r), l, r);
        }
        return l;
    }

    private Expr unary() {
        boolean not = accept(Type.OP, "!");
        if (!not && !accept(Type.OP, "-")) {
            return postfix();
        }
        descend();
        try {
            Expr e = unary();
            return node(not ? new Expr.Not(e) : new Expr.Neg(e), e);
        } finally {
            nesting--;
        }
    }

    private Expr postfix() {
        Expr e = primary();
        String path = e instanceof Expr.Root ? "$" : null;
        while (true) {
            Token t = peek();
            if (t.type() == Type.DOT) {
                next();
                String name = expect(Type.IDENT, "a field name").text();
                path = path == null ? null : path + "." + name;
                e = node(new Expr.Field(e, name, path), e);
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
        }
    }

    private Expr primary() {
        Token t = next();
        switch (t.type()) {
            case NUMBER:
                try {
                    double d = Double.parseDouble(t.text());
                    return new Expr.Lit(Values.normalise(d));
                } catch (NumberFormatException e) {
                    throw new ElException("bad number '" + t.text() + "'", t.pos());
                }
            case STRING:
                return new Expr.Lit(t.text());
            case DOLLAR:
                return new Expr.Root();
            case AT:
                return new Expr.Row();
            case HASH_INDEX:
                return new Expr.Index();
            case LPAREN: {
                Expr e = expr();
                expect(Type.RPAREN, "')'");
                return e;
            }
            case IDENT:
                return identifier(t);
            default:
                throw new ElException("unexpected '" + t.text() + "'", t.pos());
        }
    }

    private Expr identifier(Token t) {
        switch (t.text()) {
            case "true":
                return new Expr.Lit(Boolean.TRUE);
            case "false":
                return new Expr.Lit(Boolean.FALSE);
            case "null":
                return new Expr.Lit(null);
            default:
                break;
        }
        if (peek().type() != Type.LPAREN) {
            return new Expr.Field(new Expr.RowOrRoot(), t.text(), null);
        }
        next();
        ElFunction fn = Functions.get(t.text());
        if (fn == null) {
            throw new ElException("unknown function '" + t.text() + "'; known: " + Functions.ARITY.keySet(), t.pos());
        }
        List<Expr> args = new ArrayList<>();
        if (peek().type() != Type.RPAREN) {
            do {
                args.add(expr());
            } while (peek().type() == Type.COMMA && next() != null);
        }
        expect(Type.RPAREN, "')'");
        int[] arity = Functions.ARITY.get(t.text());
        if (args.size() < arity[0] || args.size() > arity[1]) {
            throw new ElException(t.text() + " takes " + arity[0] + (arity[0] == arity[1] ? "" : "-" + arity[1])
                    + " argument(s), got " + args.size(), t.pos());
        }
        return node(new Expr.Call(t.text(), fn, List.copyOf(args)), args.toArray(Expr[]::new));
    }
}
