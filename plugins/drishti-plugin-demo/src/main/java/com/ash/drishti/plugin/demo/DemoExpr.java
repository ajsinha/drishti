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
package com.ash.drishti.plugin.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;

/**
 * The small expression language of {@code _meta.walk.derive} and {@code append}: numbers, {@code 'strings'}, {@code + - * /},
 * comparisons, {@code a ? b : c}, field paths and a few functions. A bare name is looked up in the element a derive is
 * working on (the point of {@code points.*.df}) and then in the document; {@code $.name} is always the document, and
 * {@code last.name} is the previous row of a series being appended to. A path with {@code *} is a list, for
 * {@code sum(legs.*.pv)}. Functions: exp ln sqrt abs pow round min max sum sumpos sumneg avg count clamp tenor noise.
 * Compiled once per text and cached; thread-safe.
 */
final class DemoExpr {

    /** What an expression is evaluated against. */
    record Ctx(Object root, Map<String, Object> elem, Map<String, Object> last, DoubleUnaryOperator noise) {
    }

    private interface Node {
        Object eval(Ctx c);
    }

    private static final Map<String, Node> CACHE = new ConcurrentHashMap<>();

    private final String text;
    private int at;

    private DemoExpr(String text) {
        this.text = text;
    }

    /** The value of {@code expr}: a Double or a String. Throws IllegalArgumentException on text that does not parse or names something missing. */
    static Object eval(String expr, Ctx ctx) {
        Object v = CACHE.computeIfAbsent(expr, DemoExpr::compile).eval(ctx);
        if (v instanceof List<?>) {
            throw new IllegalArgumentException("a list where a value was expected: " + expr);
        }
        return v;
    }

    /** Parses the text (so a test can reject a broken spec without ticking). */
    static void check(String expr) {
        CACHE.computeIfAbsent(expr, DemoExpr::compile);
    }

    private static Node compile(String expr) {
        DemoExpr p = new DemoExpr(expr);
        Node n = p.ternary();
        p.ws();
        if (p.at < expr.length()) {
            throw new IllegalArgumentException("unexpected '" + expr.charAt(p.at) + "' at " + p.at + " in: " + expr);
        }
        return n;
    }

    // ---- grammar ---------------------------------------------------------------------------------------------
    private Node ternary() {
        Node cond = comparison();
        if (eat('?')) {
            Node yes = ternary();
            if (!eat(':')) {
                throw new IllegalArgumentException("':' expected in: " + text);
            }
            Node no = ternary();
            return c -> num(cond.eval(c)) != 0 ? yes.eval(c) : no.eval(c);
        }
        return cond;
    }

    private Node comparison() {
        Node left = additive();
        ws();
        for (String op : new String[] {">=", "<=", "==", "!=", ">", "<"}) {
            if (text.startsWith(op, at)) {
                at += op.length();
                Node right = additive();
                return c -> compare(op, left.eval(c), right.eval(c)) ? 1.0 : 0.0;
            }
        }
        return left;
    }

    private Node additive() {
        Node left = multiplicative();
        while (true) {
            ws();
            if (eat('+')) {
                Node l = left;
                Node r = multiplicative();
                left = c -> num(l.eval(c)) + num(r.eval(c));
            } else if (eat('-')) {
                Node l = left;
                Node r = multiplicative();
                left = c -> num(l.eval(c)) - num(r.eval(c));
            } else {
                return left;
            }
        }
    }

    private Node multiplicative() {
        Node left = unary();
        while (true) {
            ws();
            if (eat('*')) {
                Node l = left;
                Node r = unary();
                left = c -> num(l.eval(c)) * num(r.eval(c));
            } else if (eat('/')) {
                Node l = left;
                Node r = unary();
                left = c -> num(l.eval(c)) / num(r.eval(c));
            } else {
                return left;
            }
        }
    }

    private Node unary() {
        ws();
        if (eat('-')) {
            Node n = unary();
            return c -> -num(n.eval(c));
        }
        return primary();
    }

    private Node primary() {
        ws();
        if (at >= text.length()) {
            throw new IllegalArgumentException("expression ends early: " + text);
        }
        char ch = text.charAt(at);
        if (ch == '(') {
            at++;
            Node n = ternary();
            if (!eat(')')) {
                throw new IllegalArgumentException("')' expected in: " + text);
            }
            return n;
        }
        if (ch == '\'') {
            int end = text.indexOf('\'', at + 1);
            if (end < 0) {
                throw new IllegalArgumentException("unterminated string in: " + text);
            }
            String s = text.substring(at + 1, end);
            at = end + 1;
            return c -> s;
        }
        if (Character.isDigit(ch) || ch == '.') {
            int start = at;
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.'
                    || ((text.charAt(at) == 'e' || text.charAt(at) == 'E') && at + 1 < text.length() && (Character.isDigit(text.charAt(at + 1)) || text.charAt(at + 1) == '-')
                    ) || (text.charAt(at) == '-' && (text.charAt(at - 1) == 'e' || text.charAt(at - 1) == 'E')))) {
                at++;
            }
            double v = Double.parseDouble(text.substring(start, at));
            return c -> v;
        }
        String name = ident();
        ws();
        if (at < text.length() && text.charAt(at) == '(') {
            at++;
            List<Node> args = new ArrayList<>();
            ws();
            if (!eat(')')) {
                do {
                    args.add(ternary());
                } while (eat(','));
                if (!eat(')')) {
                    throw new IllegalArgumentException("')' expected in: " + text);
                }
            }
            return function(name, args);
        }
        return path(name);
    }

    private String ident() {
        int start = at;
        while (at < text.length()) {
            char ch = text.charAt(at);
            boolean part = Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch == '.'
                    || (ch == '*' && at > start && text.charAt(at - 1) == '.')
                    || (ch == '-' && at > start && text.charAt(at - 1) == '.' && at + 1 < text.length() && Character.isDigit(text.charAt(at + 1)))
                    || (ch == '=' && at > start && at + 1 < text.length() && text.charAt(at + 1) != '=');
            if (!part) {
                break;
            }
            at++;
        }
        if (at == start) {
            throw new IllegalArgumentException("a name or number expected at " + at + " in: " + text);
        }
        return text.substring(start, at);
    }

    private boolean eat(char ch) {
        ws();
        if (at < text.length() && text.charAt(at) == ch) {
            at++;
            return true;
        }
        return false;
    }

    private void ws() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    // ---- paths and functions ----------------------------------------------------------------------------------
    private static Node path(String name) {
        boolean list = name.contains("*");
        return c -> {
            Object scope;
            String rest;
            if (name.startsWith("$.")) {
                scope = c.root();
                rest = name.substring(2);
            } else if (name.startsWith("last.")) {
                scope = c.last();
                rest = name.substring(5);
            } else {
                String head = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
                boolean inElem = c.elem() != null && c.elem().containsKey(head);
                scope = inElem ? c.elem() : c.root();
                rest = name;
            }
            List<DemoPaths.Slot> slots = scope == null ? List.of() : DemoPaths.find(scope, rest);
            if (list) {
                return slots.stream().map(s -> s.get()).toList();
            }
            if (slots.isEmpty()) {
                throw new IllegalArgumentException("no value at " + name);
            }
            Object v = slots.get(0).get();
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof String s) {
                return s;
            }
            throw new IllegalArgumentException("no number or text at " + name);
        };
    }

    private Node function(String name, List<Node> args) {
        return switch (name) {
            case "exp" -> unaryFn(args, Math::exp);
            case "ln" -> unaryFn(args, Math::log);
            case "sqrt" -> unaryFn(args, Math::sqrt);
            case "abs" -> unaryFn(args, Math::abs);
            case "pow" -> c -> Math.pow(num(args.get(0).eval(c)), num(args.get(1).eval(c)));
            case "round" -> c -> {
                double f = Math.pow(10, args.size() > 1 ? num(args.get(1).eval(c)) : 0);
                return Math.round(num(args.get(0).eval(c)) * f) / f;
            };
            case "clamp" -> c -> Math.max(num(args.get(1).eval(c)), Math.min(num(args.get(2).eval(c)), num(args.get(0).eval(c))));
            case "min" -> c -> flatten(args, c).stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            case "max" -> c -> flatten(args, c).stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            case "sum" -> c -> flatten(args, c).stream().mapToDouble(Double::doubleValue).sum();
            case "avg" -> c -> flatten(args, c).stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            case "sumneg" -> c -> flatten(args, c).stream().filter(x -> x < 0).mapToDouble(Double::doubleValue).sum();
            case "sumpos" -> c -> flatten(args, c).stream().filter(x -> x > 0).mapToDouble(Double::doubleValue).sum();
            case "count" -> c -> (double) flatten(args, c).size();
            case "noise" -> c -> c.noise().applyAsDouble(num(args.get(0).eval(c)));
            case "tenor" -> c -> tenorYears(String.valueOf(args.get(0).eval(c)));
            default -> throw new IllegalArgumentException("unknown function " + name + " in: " + text);
        };
    }

    private static Node unaryFn(List<Node> args, java.util.function.DoubleUnaryOperator f) {
        return c -> f.applyAsDouble(num(args.get(0).eval(c)));
    }

    private static List<Double> flatten(List<Node> args, Ctx c) {
        List<Double> out = new ArrayList<>();
        for (Node a : args) {
            Object v = a.eval(c);
            if (v instanceof List<?> l) {
                l.forEach(x -> {
                    if (x instanceof Number n) {
                        out.add(n.doubleValue());
                    }
                });
            } else {
                out.add(num(v));
            }
        }
        return out;
    }

    /** A tenor label as years: 1M is 1/12, 6M is 0.5, 10Y is 10, 2W is 2/52, ON is 1/365. */
    static double tenorYears(String t) {
        if ("ON".equals(t)) {
            return 1.0 / 365;
        }
        double n = Double.parseDouble(t.substring(0, t.length() - 1));
        return switch (t.charAt(t.length() - 1)) {
            case 'D' -> n / 365;
            case 'W' -> n / 52;
            case 'M' -> n / 12;
            case 'Y' -> n;
            default -> throw new IllegalArgumentException("not a tenor: " + t);
        };
    }

    private static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("a number expected, got " + v);
    }

    private static boolean compare(String op, Object a, Object b) {
        if (a instanceof String || b instanceof String) {
            boolean eq = String.valueOf(a).equals(String.valueOf(b));
            return "==".equals(op) ? eq : "!=".equals(op) && !eq;
        }
        double x = num(a);
        double y = num(b);
        return switch (op) {
            case ">=" -> x >= y;
            case "<=" -> x <= y;
            case "==" -> x == y;
            case "!=" -> x != y;
            case ">" -> x > y;
            default -> x < y;
        };
    }
}
