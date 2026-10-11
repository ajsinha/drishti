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
package com.ash.drishti.server.bi.dataset;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for dataset expressions: the operators and literals of Rachana-EL (docs/guides/RACHANA_REFERENCE.md) plus the
 * aggregate functions of section 6, {@code in}, and {@code [Measure name]} references. Grammar:
 *
 * <pre>
 * expr    = or [ "?" expr ":" expr ] ;
 * or      = and { ("||" | "or") and } ;
 * and     = not { ("&amp;&amp;" | "and") not } ;
 * not     = ("!" | "not") not | cmp ;
 * cmp     = sum [ ("==" | "=" | "!=" | "&lt;" | "&lt;=" | "&gt;" | "&gt;=") sum | ["not"] "in" list ] ;
 * sum     = product { ("+" | "-") product } ;
 * product = unary { ("*" | "/" | "%") unary } ;
 * unary   = "-" unary | primary ;
 * primary = number | 'text' | "text" | true | false | null | "[" name "]" | ident "(" [ expr { "," expr } ] ")"
 *         | ident { "." ident } | "(" expr ")" ;
 * list    = "(" expr { "," expr } ")" | ident { "." ident } ;
 * </pre>
 *
 * The parse is bounded: a length cap and a nesting cap, so an expression cannot overflow the stack. Nothing here evaluates; the tree goes to the
 * compiler, which binds every value as a parameter.
 */
public final class ExprParser {

    /** A syntax error with its position in the text. */
    public static final class ExprException extends RuntimeException {
        private final int position;

        ExprException(String message, int position) {
            super(message);
            this.position = position;
        }

        public int position() {
            return position;
        }
    }

    private enum T { NUM, STR, ID, MEASURE, OP, EOF }

    private record Tok(T type, String text, int pos) {}

    public static final int MAX_LENGTH = 2000;
    private static final int MAX_DEPTH = 64;

    private final List<Tok> toks;
    private int p;
    private int depth;

    private ExprParser(List<Tok> toks) {
        this.toks = toks;
    }

    public static Node parse(String src) {
        if (src == null || src.isBlank()) {
            throw new ExprException("the expression is empty", 0);
        }
        if (src.length() > MAX_LENGTH) {
            throw new ExprException("the expression is longer than " + MAX_LENGTH + " characters", MAX_LENGTH);
        }
        ExprParser ps = new ExprParser(lex(src));
        Node n = ps.expr();
        if (ps.peek().type() != T.EOF) {
            throw new ExprException("unexpected '" + ps.peek().text() + "'", ps.peek().pos());
        }
        return n;
    }

    // ---- lexer -------------------------------------------------------------------------------------------------------

    private static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                int j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Tok(T.NUM, s.substring(i, j), i));
                i = j;
            } else if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && j + 1 < n) {
                        j++;
                    }
                    sb.append(s.charAt(j++));
                }
                if (j >= n) {
                    throw new ExprException("the text starting here is not closed", i);
                }
                out.add(new Tok(T.STR, sb.toString(), i));
                i = j + 1;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                out.add(new Tok(T.ID, s.substring(i, j), i));
                i = j;
            } else if (c == '[') {
                int j = s.indexOf(']', i);
                if (j < 0 || j == i + 1) {
                    throw new ExprException("a measure name in [ ] is needed", i);
                }
                out.add(new Tok(T.MEASURE, s.substring(i + 1, j).strip(), i));
                i = j + 1;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (List.of("==", "!=", "<=", ">=", "&&", "||").contains(two)) {
                    out.add(new Tok(T.OP, two, i));
                    i += 2;
                } else if ("+-*/%<>=!?:(),.".indexOf(c) >= 0) {
                    out.add(new Tok(T.OP, String.valueOf(c), i));
                    i++;
                } else {
                    throw new ExprException("unexpected character '" + c + "'", i);
                }
            }
        }
        out.add(new Tok(T.EOF, "", n));
        return out;
    }

    // ---- parser ------------------------------------------------------------------------------------------------------

    private Tok peek() {
        return toks.get(p);
    }

    private boolean op(String text) {
        Tok t = peek();
        if (t.type() == T.OP && t.text().equals(text)) {
            p++;
            return true;
        }
        return false;
    }

    private boolean word(String text) {
        Tok t = peek();
        if (t.type() == T.ID && t.text().equalsIgnoreCase(text)) {
            p++;
            return true;
        }
        return false;
    }

    private void expect(String text) {
        if (!op(text)) {
            Tok t = peek();
            throw new ExprException("expected '" + text + "'" + (t.type() == T.EOF ? " but the expression ends" : " but found '" + t.text() + "'"), t.pos());
        }
    }

    private Node expr() {
        if (++depth > MAX_DEPTH) {
            throw new ExprException("the expression nests deeper than " + MAX_DEPTH + " levels", peek().pos());
        }
        try {
            Node c = or();
            if (op("?")) {
                Node yes = expr();
                expect(":");
                return new Node.Cond(c, yes, expr());
            }
            return c;
        } finally {
            depth--;
        }
    }

    private Node or() {
        Node l = and();
        while (op("||") || word("or")) {
            l = new Node.Bin("||", l, and());
        }
        return l;
    }

    private Node and() {
        Node l = not();
        while (op("&&") || word("and")) {
            l = new Node.Bin("&&", l, not());
        }
        return l;
    }

    private Node not() {
        if (op("!") || word("not")) {
            return new Node.Un("!", not());
        }
        return cmp();
    }

    private Node cmp() {
        Node l = sum();
        if (word("in")) {
            return new Node.In(l, list(), false);
        }
        int save = p;
        if (word("not")) {
            if (word("in")) {
                return new Node.In(l, list(), true);
            }
            p = save;
        }
        for (String o : new String[] {"==", "=", "!=", "<=", ">=", "<", ">"}) {
            if (op(o)) {
                return new Node.Bin("=".equals(o) ? "==" : o, l, sum());
            }
        }
        return l;
    }

    private List<Node> list() {
        List<Node> items = new ArrayList<>();
        if (op("(")) {
            do {
                items.add(expr());
            } while (op(","));
            expect(")");
        } else {
            items.add(primary());
        }
        return items;
    }

    private Node sum() {
        Node l = product();
        while (true) {
            if (op("+")) {
                l = new Node.Bin("+", l, product());
            } else if (op("-")) {
                l = new Node.Bin("-", l, product());
            } else {
                return l;
            }
        }
    }

    private Node product() {
        Node l = unary();
        while (true) {
            if (op("*")) {
                l = new Node.Bin("*", l, unary());
            } else if (op("/")) {
                l = new Node.Bin("/", l, unary());
            } else if (op("%")) {
                l = new Node.Bin("%", l, unary());
            } else {
                return l;
            }
        }
    }

    private Node unary() {
        if (op("-")) {
            return new Node.Un("-", unary());
        }
        return primary();
    }

    private Node primary() {
        Tok t = peek();
        switch (t.type()) {
            case NUM -> {
                p++;
                try {
                    return new Node.Num(Double.parseDouble(t.text()), !t.text().contains("."));
                } catch (NumberFormatException e) {
                    throw new ExprException("'" + t.text() + "' is not a number", t.pos());
                }
            }
            case STR -> {
                p++;
                return new Node.Str(t.text());
            }
            case MEASURE -> {
                p++;
                return new Node.MeasureRef(t.text());
            }
            case ID -> {
                p++;
                switch (t.text().toLowerCase(java.util.Locale.ROOT)) {
                    case "true" -> {
                        return new Node.Bool(true);
                    }
                    case "false" -> {
                        return new Node.Bool(false);
                    }
                    case "null" -> {
                        return new Node.Null();
                    }
                    default -> { }
                }
                if (op("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!op(")")) {
                        do {
                            args.add(expr());
                        } while (op(","));
                        expect(")");
                    }
                    return new Node.Call(t.text(), args);
                }
                List<String> parts = new ArrayList<>(List.of(t.text()));
                while (peek().type() == T.OP && peek().text().equals(".") && toks.get(p + 1).type() == T.ID) {
                    p++;
                    parts.add(toks.get(p++).text());
                }
                if (parts.get(0).equals("user")) {
                    if (parts.size() != 2) {
                        throw new ExprException("a user attribute is user.<name>, for example user.name", t.pos());
                    }
                    return new Node.UserRef(parts.get(1));
                }
                return new Node.Ref(List.copyOf(parts));
            }
            default -> { }
        }
        if (op("(")) {
            Node inner = expr();
            expect(")");
            return inner;
        }
        throw new ExprException(t.type() == T.EOF ? "the expression ends early: a value is missing" : "unexpected '" + t.text() + "'", t.pos());
    }
}
