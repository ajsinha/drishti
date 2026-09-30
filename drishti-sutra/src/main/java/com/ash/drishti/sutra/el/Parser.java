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
package com.ash.drishti.sutra.el;

import com.ash.drishti.sutra.el.Token.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for Sutra-EL. Grammar (EBNF):
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
 */
final class Parser {

    private final List<Token> tokens;
    private int p;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static Expr parse(String src) {
        Parser parser = new Parser(Lexer.tokens(src));
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

    private Expr expr() {
        Expr c = or();
        if (peek().type() == Type.QUESTION) {
            next();
            Expr a = expr();
            expect(Type.COLON, "':'");
            return new Expr.Ternary(c, a, expr());
        }
        return c;
    }

    private Expr or() {
        Expr l = and();
        while (accept(Type.OP, "||")) {
            l = new Expr.Or(l, and());
        }
        return l;
    }

    private Expr and() {
        Expr l = equality();
        while (accept(Type.OP, "&&")) {
            l = new Expr.And(l, equality());
        }
        return l;
    }

    private Expr equality() {
        Expr l = compare();
        while (peek().is(Type.OP, "==") || peek().is(Type.OP, "!=")) {
            l = new Expr.Binary(next().text(), l, compare());
        }
        return l;
    }

    private Expr compare() {
        Expr l = sum();
        while (peek().type() == Type.OP && List.of("<", "<=", ">", ">=").contains(peek().text())) {
            l = new Expr.Binary(next().text(), l, sum());
        }
        return l;
    }

    private Expr sum() {
        Expr l = product();
        while (peek().is(Type.OP, "+") || peek().is(Type.OP, "-")) {
            l = new Expr.Binary(next().text(), l, product());
        }
        return l;
    }

    private Expr product() {
        Expr l = unary();
        while (peek().is(Type.OP, "*") || peek().is(Type.OP, "/") || peek().is(Type.OP, "%")) {
            l = new Expr.Binary(next().text(), l, unary());
        }
        return l;
    }

    private Expr unary() {
        if (accept(Type.OP, "!")) {
            return new Expr.Not(unary());
        }
        if (accept(Type.OP, "-")) {
            return new Expr.Neg(unary());
        }
        return postfix();
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
                e = new Expr.Field(e, name, path);
            } else if (t.type() == Type.LBRACKET) {
                next();
                Expr idx = expr();
                expect(Type.RBRACKET, "']'");
                path = path != null && idx instanceof Expr.Lit lit ? path + "[" + Values.text(lit.value()) + "]" : null;
                e = new Expr.At(e, idx, path);
            } else if (t.type() == Type.FILTER) {
                next();
                Expr pred = expr();
                expect(Type.RBRACKET, "']'");
                e = new Expr.Filter(e, pred);
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
        return new Expr.Call(t.text(), fn, List.copyOf(args));
    }
}
