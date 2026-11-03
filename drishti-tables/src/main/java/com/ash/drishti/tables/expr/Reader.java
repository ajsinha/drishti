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
package com.ash.drishti.tables.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads Rachana expression text into a {@link Node} tree (the grammar of {@link Node}). Precedence, lowest first: {@code ?:},
 * {@code ||}, {@code &&}, comparisons ({@code == != < <= > >= in not in}), {@code + -}, {@code * / %}, unary {@code ! -}. Bounded as
 * Rachana is: 10,000 characters, 200 deep. This is the table layer's reader for the checks and the migration; the one parser that
 * evaluates is Rachana's ({@code ElCompiler}), and part A's checked mode replaces this reader's use in the checks when it lands.
 */
public final class Reader {

    /** The comparison operators ({@code not-in} is {@code not in}). */
    public static final Set<String> COMPARISONS = Set.of("==", "!=", "<", "<=", ">", ">=", "in", "not-in");

    private static final int MAX_LENGTH = 10_000;
    private static final int MAX_DEPTH = 200;

    /** A problem reading the text, with the offset where it was found. */
    public static final class ReadException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int position;

        public ReadException(String message, int position) {
            super(message + " at " + position);
            this.position = position;
        }

        public int position() {
            return position;
        }
    }

    /** One token: its kind ({@code num}, {@code str}, {@code id}, {@code root}, {@code op}, {@code end}), its text and where it starts. */
    public record Token(String kind, String text, Object value, int at) {}

    private final List<Token> tokens;
    private int i;
    private int depth;

    private Reader(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Reads {@code text}. */
    public static Node read(String text) {
        if (text == null || text.isBlank()) {
            throw new ReadException("an empty expression", 0);
        }
        if (text.length() > MAX_LENGTH) {
            throw new ReadException("longer than " + MAX_LENGTH + " characters", MAX_LENGTH);
        }
        Reader r = new Reader(tokens(text));
        Node n = r.cond();
        if (!r.peek().kind().equals("end")) {
            throw new ReadException("unexpected '" + r.peek().text() + "'", r.peek().at());
        }
        return n;
    }

    /** True when {@code text} reads. */
    public static boolean reads(String text) {
        try {
            read(text);
            return true;
        } catch (ReadException e) {
            return false;
        }
    }

    // ---- the lexer -------------------------------------------------------------------------------------------------------------------

    private static final List<String> OPS = List.of("&&", "||", "==", "!=", "<=", ">=", "^^", "<", ">", "!", "+", "-", "*", "/", "%", "(", ")",
            "[", "]", ",", ".", "?", ":", "=", "$", "@", "^");

    /** The tokens of {@code text}, ending with an {@code end} token. Also used by the migration to map words token by token. */
    public static List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        int p = 0;
        int n = text.length();
        while (p < n) {
            char c = text.charAt(p);
            if (Character.isWhitespace(c)) {
                p++;
                continue;
            }
            int start = p;
            if (Character.isDigit(c) || (c == '.' && p + 1 < n && Character.isDigit(text.charAt(p + 1)) && !afterValue(out))) {
                while (p < n && (Character.isDigit(text.charAt(p)) || text.charAt(p) == '_' || text.charAt(p) == '.')) {
                    if (text.charAt(p) == '.' && (p + 1 >= n || !Character.isDigit(text.charAt(p + 1)))) {
                        break;
                    }
                    p++;
                }
                if (p < n && (text.charAt(p) == 'e' || text.charAt(p) == 'E')) {
                    int q = p + 1;
                    if (q < n && (text.charAt(q) == '+' || text.charAt(q) == '-')) {
                        q++;
                    }
                    if (q < n && Character.isDigit(text.charAt(q))) {
                        p = q;
                        while (p < n && Character.isDigit(text.charAt(p))) {
                            p++;
                        }
                    }
                }
                String t = text.substring(start, p);
                out.add(new Token("num", t, t.replace("_", ""), start));
            } else if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                p++;
                boolean closed = false;
                while (p < n) {
                    char d = text.charAt(p);
                    if (d == '\\' && p + 1 < n) {
                        sb.append(text.charAt(p + 1));
                        p += 2;
                    } else if (d == c) {
                        closed = true;
                        p++;
                        break;
                    } else {
                        sb.append(d);
                        p++;
                    }
                }
                if (!closed) {
                    throw new ReadException("a text with no closing quote", start);
                }
                out.add(new Token("str", text.substring(start, p), sb.toString(), start));
            } else if (Character.isLetter(c) || c == '_') {
                while (p < n && (Character.isLetterOrDigit(text.charAt(p)) || text.charAt(p) == '_')) {
                    p++;
                }
                out.add(new Token("id", text.substring(start, p), null, start));
            } else {
                String op = null;
                for (String o : OPS) {
                    if (text.startsWith(o, p)) {
                        op = o;
                        break;
                    }
                }
                if (op == null) {
                    throw new ReadException("unexpected '" + c + "'", p);
                }
                p += op.length();
                out.add(new Token(Set.of("$", "@", "^", "^^").contains(op) ? "root" : "op", op, null, start));
            }
        }
        out.add(new Token("end", "", null, n));
        return out;
    }

    private static boolean afterValue(List<Token> out) {
        if (out.isEmpty()) {
            return false;
        }
        Token t = out.get(out.size() - 1);
        return Set.of("id", "num", "str", "root").contains(t.kind()) || t.text().equals(")") || t.text().equals("]");
    }

    // ---- the parser ------------------------------------------------------------------------------------------------------------------

    private Token peek() {
        return tokens.get(i);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(i + ahead, tokens.size() - 1));
    }

    private boolean at(String text) {
        Token t = peek();
        return (t.kind().equals("op") || t.kind().equals("id")) && t.text().equals(text);
    }

    private Token next() {
        return tokens.get(i++);
    }

    private void expect(String text) {
        if (!at(text)) {
            throw new ReadException("expected '" + text + "' but found '" + peek().text() + "'", peek().at());
        }
        i++;
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new ReadException("nested deeper than " + MAX_DEPTH, peek().at());
        }
    }

    private Node cond() {
        enter();
        Node test = or();
        if (at("?")) {
            next();
            Node then = cond();
            expect(":");
            Node otherwise = cond();
            depth--;
            return new Node.Cond(test, then, otherwise);
        }
        depth--;
        return test;
    }

    private Node or() {
        Node left = and();
        while (at("||")) {
            next();
            left = new Node.Binary("||", left, and());
        }
        return left;
    }

    private Node and() {
        Node left = comparison();
        while (at("&&")) {
            next();
            left = new Node.Binary("&&", left, comparison());
        }
        return left;
    }

    private Node comparison() {
        Node left = additive();
        while (true) {
            String op = null;
            if (peek().kind().equals("op") && COMPARISONS.contains(peek().text())) {
                op = next().text();
            } else if (at("in")) {
                next();
                op = "in";
            } else if (at("not") && peek(1).kind().equals("id") && peek(1).text().equals("in")) {
                i += 2;
                op = "not-in";
            }
            if (op == null) {
                return left;
            }
            left = new Node.Binary(op, left, additive());
        }
    }

    private Node additive() {
        Node left = multiplicative();
        while (at("+") || at("-")) {
            String op = next().text();
            left = new Node.Binary(op, left, multiplicative());
        }
        return left;
    }

    private Node multiplicative() {
        Node left = unary();
        while (at("*") || at("/") || at("%")) {
            String op = next().text();
            left = new Node.Binary(op, left, unary());
        }
        return left;
    }

    private Node unary() {
        if (at("!") || at("-") || at("+")) {
            String op = next().text();
            enter();
            Node operand = unary();
            depth--;
            if (op.equals("+")) {
                return operand;
            }
            if (op.equals("-") && operand instanceof Node.Lit l && l.value() instanceof java.math.BigDecimal d) {
                return new Node.Lit(d.negate(), "-" + l.text());            // a negative number is one literal
            }
            return new Node.Unary(op, operand);
        }
        return primary();
    }

    private Node primary() {
        Token t = peek();
        switch (t.kind()) {
            case "num" -> {
                next();
                try {
                    return new Node.Lit(new java.math.BigDecimal((String) t.value()), (String) t.value());
                } catch (NumberFormatException e) {
                    throw new ReadException("not a number: " + t.text(), t.at());
                }
            }
            case "str" -> {
                next();
                return Node.Lit.text((String) t.value());
            }
            case "root" -> {
                next();
                return new Node.Path(t.text(), pathRest());
            }
            case "id" -> {
                return word();
            }
            default -> { }
        }
        if (at("(")) {
            next();
            enter();
            Node n = cond();
            depth--;
            expect(")");
            return n;
        }
        if (at("[")) {
            next();
            List<Node> items = new ArrayList<>();
            if (!at("]")) {
                items.add(cond());
                while (at(",")) {
                    next();
                    items.add(cond());
                }
            }
            expect("]");
            return new Node.ListOf(items);
        }
        throw new ReadException(t.kind().equals("end") ? "the expression ends too soon" : "unexpected '" + t.text() + "'", t.at());
    }

    /** The fields and indexes after a root, as text ({@code .LEGS[0].NOTIONAL}, {@code [*]}). */
    private String pathRest() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (at(".") && peek(1).kind().equals("id")) {
                next();
                sb.append('.').append(next().text());
            } else if (at("[")) {
                next();
                Token x = next();
                if (x.text().equals("*")) {
                    sb.append("[*]");
                } else if (x.kind().equals("num")) {
                    sb.append('[').append(x.value()).append(']');
                } else if (x.kind().equals("str")) {
                    sb.append("['").append(x.value()).append("']");
                } else {
                    throw new ReadException("a path index is a number, * or a quoted key", x.at());
                }
                expect("]");
            } else {
                return sb.toString();
            }
        }
    }

    private Node word() {
        Token t = next();
        switch (t.text()) {
            case "true" -> {
                return Node.Lit.TRUE;
            }
            case "false" -> {
                return Node.Lit.FALSE;
            }
            case "null" -> {
                return Node.Lit.NULL;
            }
            default -> { }
        }
        StringBuilder name = new StringBuilder(t.text());
        while (at(".") && peek(1).kind().equals("id")) {
            next();
            name.append('.').append(next().text());
        }
        if (at("(")) {
            next();
            List<Node.Arg> args = new ArrayList<>();
            if (!at(")")) {
                args.add(arg());
                while (at(",")) {
                    next();
                    args.add(arg());
                }
            }
            expect(")");
            return new Node.Call(name.toString(), args);
        }
        return new Node.Name(name.toString());
    }

    private Node.Arg arg() {
        if (peek().kind().equals("id") && peek(1).kind().equals("op") && peek(1).text().equals("=")) {
            String n = next().text();
            next();
            return new Node.Arg(n, cond());
        }
        return new Node.Arg(null, cond());
    }
}
