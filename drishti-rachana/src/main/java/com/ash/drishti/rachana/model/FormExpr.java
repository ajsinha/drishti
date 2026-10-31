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
package com.ash.drishti.rachana.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The small expression language of a form's rules and hints (RACHANA_REFERENCE.md 10): the same text is evaluated in the browser
 * ({@code static/js/form-expr.js}) for speed and here, on the server, as the authority. It reads the form's fields by id
 * ({@code notional}), a lookup's carried values ({@code counterparty.LIMIT}), numbers, 'text', {@code true}, {@code false},
 * {@code null} and {@code today} (an ISO date); it has {@code + - * /}, {@code == != < <= > >=} (numbers as numbers, anything else as
 * text, so ISO dates compare), {@code and or not}, brackets and the functions {@code empty(x) len(x) abs(x) min(a, b) max(a, b)}.
 * A value that is empty makes an arithmetic or a comparison empty, and a rule that comes out empty holds (required says what is
 * missing, not every rule).
 */
public final class FormExpr {

    /** The most characters of one expression. */
    public static final int MAX_LENGTH = 400;

    private final String text;
    private final Node root;

    private FormExpr(String text, Node root) {
        this.text = text;
        this.root = root;
    }

    /** Parses {@code text}; an {@link IllegalArgumentException} says what is wrong, in words. */
    public static FormExpr parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("an expression is empty");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("an expression is at most " + MAX_LENGTH + " characters");
        }
        Parser p = new Parser(tokens(text));
        Node n = p.or();
        if (p.i < p.toks.size()) {
            throw new IllegalArgumentException("'" + p.toks.get(p.i).text + "' is not expected in '" + text + "'");
        }
        return new FormExpr(text, n);
    }

    public String text() {
        return text;
    }

    /** The names it reads: {@code notional}, {@code counterparty} for {@code counterparty.LIMIT}. */
    public Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        root.names(out);
        return out;
    }

    /** Its value over the form's values (a lookup's value is a map of what it carries); null when it cannot be told. */
    public Object eval(Map<String, Object> values, LocalDate today) {
        return root.eval(values, today);
    }

    /** Whether a rule holds: true, or empty (it cannot be told yet). */
    public boolean holds(Map<String, Object> values, LocalDate today) {
        Object v = eval(values, today);
        return v == null || truthy(v);
    }

    // ---- values ---------------------------------------------------------------------------------------------------------------

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof BigDecimal d) {
            return d.signum() != 0;
        }
        return !v.toString().isEmpty();
    }

    /** A number when it reads as one (a Number, or text like 25,000,000), else null. */
    public static BigDecimal number(Object v) {
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.replace(",", "").trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean empty(Object v) {
        return v == null || v instanceof String s && s.isEmpty() || v instanceof Collection<?> c && c.isEmpty();
    }

    // ---- tokens ---------------------------------------------------------------------------------------------------------------

    private record Tok(char type, String text) {}          // n number, s string, i name, o operator

    private static List<Tok> tokens(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c)) {
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
                out.add(new Tok('n', s.substring(i, j).replace("_", "")));
                i = j;
            } else if (c == '\'' || c == '"') {
                int j = s.indexOf(c, i + 1);
                if (j < 0) {
                    throw new IllegalArgumentException("a text in '" + s + "' is not closed");
                }
                out.add(new Tok('s', s.substring(i + 1, j)));
                i = j + 1;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Tok('i', s.substring(i, j)));
                i = j;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (List.of("==", "!=", "<=", ">=").contains(two)) {
                    out.add(new Tok('o', two));
                    i += 2;
                } else if ("+-*/()<>,".indexOf(c) >= 0) {
                    out.add(new Tok('o', String.valueOf(c)));
                    i++;
                } else {
                    throw new IllegalArgumentException("'" + c + "' is not part of the form expressions (in '" + s + "')");
                }
            }
        }
        return out;
    }

    // ---- the tree -------------------------------------------------------------------------------------------------------------

    private interface Node {
        Object eval(Map<String, Object> v, LocalDate today);

        default void names(Set<String> out) {}
    }

    private record Lit(Object value) implements Node {
        public Object eval(Map<String, Object> v, LocalDate today) {
            return value;
        }
    }

    private record Today() implements Node {
        public Object eval(Map<String, Object> v, LocalDate today) {
            return today == null ? null : today.toString();
        }
    }

    private record Name(String path) implements Node {
        public Object eval(Map<String, Object> v, LocalDate today) {
            int dot = path.indexOf('.');
            if (dot < 0) {
                return norm(v.get(path));
            }
            Object head = v.get(path.substring(0, dot));
            return head instanceof Map<?, ?> m ? norm(m.get(path.substring(dot + 1))) : null;
        }

        public void names(Set<String> out) {
            int dot = path.indexOf('.');
            out.add(dot < 0 ? path : path.substring(0, dot));
        }

        private static Object norm(Object o) {
            return o instanceof Number n ? new BigDecimal(n.toString()) : o;
        }
    }

    private record Not(Node a) implements Node {
        public Object eval(Map<String, Object> v, LocalDate today) {
            Object x = a.eval(v, today);
            return x == null ? null : !truthy(x);
        }

        public void names(Set<String> out) {
            a.names(out);
        }
    }

    private record Neg(Node a) implements Node {
        public Object eval(Map<String, Object> v, LocalDate today) {
            BigDecimal x = number(a.eval(v, today));
            return x == null ? null : x.negate();
        }

        public void names(Set<String> out) {
            a.names(out);
        }
    }

    private record Bin(String op, Node a, Node b) implements Node {
        public Object eval(Map<String, Object> v, LocalDate today) {
            Object x = a.eval(v, today);
            Object y = b.eval(v, today);
            if (op.equals("and") || op.equals("or")) {
                boolean and = op.equals("and");
                if (x == null || y == null) {           // and: false wins; or: true wins; else it cannot be told
                    boolean decides = x != null && truthy(x) != and || y != null && truthy(y) != and;
                    return decides ? (Boolean) !and : null;
                }
                return and ? truthy(x) && truthy(y) : truthy(x) || truthy(y);
            }
            if (empty(x) || empty(y)) {
                return switch (op) {
                    case "==" -> (Boolean) (empty(x) && empty(y));
                    case "!=" -> (Boolean) (empty(x) != empty(y));
                    default -> null;
                };
            }
            BigDecimal nx = number(x);
            BigDecimal ny = number(y);
            if ("+-*/".contains(op)) {
                if (nx == null || ny == null) {
                    return op.equals("+") ? x.toString() + y : null;
                }
                return switch (op) {
                    case "+" -> nx.add(ny);
                    case "-" -> nx.subtract(ny);
                    case "*" -> nx.multiply(ny);
                    default -> ny.signum() == 0 ? null : nx.divide(ny, java.math.MathContext.DECIMAL64);
                };
            }
            int c = nx != null && ny != null ? nx.compareTo(ny) : x.toString().compareTo(y.toString());
            return switch (op) {
                case "==" -> c == 0;
                case "!=" -> c != 0;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                default -> c >= 0;
            };
        }

        public void names(Set<String> out) {
            a.names(out);
            b.names(out);
        }
    }

    private record Call(String fn, List<Node> args) implements Node {
        public Object eval(Map<String, Object> v, LocalDate today) {
            List<Object> xs = new ArrayList<>();
            args.forEach(n -> xs.add(n.eval(v, today)));
            switch (fn) {
                case "empty" -> {
                    return empty(xs.get(0));
                }
                case "len" -> {
                    Object x = xs.get(0);
                    return x == null ? BigDecimal.ZERO : BigDecimal.valueOf(x instanceof Collection<?> c ? c.size() : x.toString().length());
                }
                case "abs" -> {
                    BigDecimal x = number(xs.get(0));
                    return x == null ? null : x.abs();
                }
                default -> {
                    BigDecimal x = number(xs.get(0));
                    BigDecimal y = number(xs.get(1));
                    return x == null || y == null ? null : fn.equals("min") ? x.min(y) : x.max(y);
                }
            }
        }

        public void names(Set<String> out) {
            args.forEach(a -> a.names(out));
        }
    }

    private static final Map<String, Integer> FUNCTIONS = Map.of("empty", 1, "len", 1, "abs", 1, "min", 2, "max", 2);

    private static final class Parser {
        final List<Tok> toks;
        int i;

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        boolean at(String word) {
            return i < toks.size() && toks.get(i).text.equals(word) && toks.get(i).type != 's';
        }

        Node or() {
            Node n = and();
            while (at("or")) {
                i++;
                n = new Bin("or", n, and());
            }
            return n;
        }

        Node and() {
            Node n = not();
            while (at("and")) {
                i++;
                n = new Bin("and", n, not());
            }
            return n;
        }

        Node not() {
            if (at("not")) {
                i++;
                return new Not(not());
            }
            return cmp();
        }

        Node cmp() {
            Node n = sum();
            for (String op : List.of("==", "!=", "<=", ">=", "<", ">")) {
                if (at(op)) {
                    i++;
                    return new Bin(op, n, sum());
                }
            }
            return n;
        }

        Node sum() {
            Node n = prod();
            while (at("+") || at("-")) {
                String op = toks.get(i++).text;
                n = new Bin(op, n, prod());
            }
            return n;
        }

        Node prod() {
            Node n = unary();
            while (at("*") || at("/")) {
                String op = toks.get(i++).text;
                n = new Bin(op, n, unary());
            }
            return n;
        }

        Node unary() {
            if (at("-")) {
                i++;
                return new Neg(unary());
            }
            return atom();
        }

        Node atom() {
            if (i >= toks.size()) {
                throw new IllegalArgumentException("the expression ends too soon");
            }
            Tok t = toks.get(i++);
            if (t.type == 'n') {
                try {
                    return new Lit(new BigDecimal(t.text));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + t.text + "' is not a number");
                }
            }
            if (t.type == 's') {
                return new Lit(t.text);
            }
            if (t.type == 'o') {
                if (t.text.equals("(")) {
                    Node n = or();
                    close();
                    return n;
                }
                throw new IllegalArgumentException("'" + t.text + "' is not expected here");
            }
            switch (t.text) {
                case "true":
                    return new Lit(Boolean.TRUE);
                case "false":
                    return new Lit(Boolean.FALSE);
                case "null":
                    return new Lit(null);
                case "today":
                    return new Today();
                case "and", "or", "not":
                    throw new IllegalArgumentException("'" + t.text + "' needs a value on each side");
                default:
                    break;
            }
            if (at("(")) {
                Integer arity = FUNCTIONS.get(t.text);
                if (arity == null) {
                    throw new IllegalArgumentException("'" + t.text + "' is not a function here (empty, len, abs, min, max)");
                }
                i++;
                List<Node> args = new ArrayList<>();
                if (!at(")")) {
                    args.add(or());
                    while (at(",")) {
                        i++;
                        args.add(or());
                    }
                }
                close();
                if (args.size() != arity) {
                    throw new IllegalArgumentException(t.text + "() takes " + arity + (arity == 1 ? " value" : " values"));
                }
                return new Call(t.text, args);
            }
            if (t.text.endsWith(".") || t.text.chars().filter(c -> c == '.').count() > 1) {
                throw new IllegalArgumentException("'" + t.text + "' is a field (notional) or a lookup's value (counterparty.LIMIT)");
            }
            return new Name(t.text);
        }

        void close() {
            if (!at(")")) {
                throw new IllegalArgumentException("a ')' is missing");
            }
            i++;
        }
    }
}
