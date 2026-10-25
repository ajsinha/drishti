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
package com.ash.drishti.compute.var;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The condition of a RULE portfolio or a {@code WHERE:} scope (VAR_DATA_MODEL.md section 4.2): the small part of DEL evaluated over a
 * position's columns, the same grammar as tools/packgen/var/rules.py (so a rule's members are the same in the demo data and here).
 *
 * <pre>
 *   PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD')
 *   'hedge' in TAGS or not DIRECTION == 'LONG'
 *   MARKET_VALUE &gt; 1000000
 * </pre>
 *
 * Comparisons {@code == != < <= > >=} of a field with a quoted text or a number, {@code FIELD in ('a', 'b')}, {@code 'x' in FIELD} (a
 * list field holds it), {@code and}, {@code or}, {@code not}, parentheses. A missing field compares unequal to everything.
 */
public final class PositionRule {

    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(?:(\\d+(?:\\.\\d+)?)|'((?:[^']|'')*)'|(==|!=|<=|>=|<|>|\\(|\\)|,)|([A-Za-z_][A-Za-z0-9_.]*))");

    private record Tok(char kind, Object value) {}      // l literal, o operator, k keyword, f field

    private sealed interface Node permits Or, AndN, Not, Has, In, Cmp {}

    private record Or(Node a, Node b) implements Node {}

    private record AndN(Node a, Node b) implements Node {}

    private record Not(Node a) implements Node {}

    private record Has(String field, Object value) implements Node {}

    private record In(String field, List<Object> values) implements Node {}

    private record Cmp(String op, String field, Object value) implements Node {}

    private final String text;
    private final List<Tok> toks = new ArrayList<>();
    private final Node tree;
    private final List<String> fields = new ArrayList<>();
    private int at;

    public PositionRule(String text) {
        this.text = text == null ? "" : text.trim();
        Matcher m = TOKEN.matcher(this.text);
        int i = 0;
        while (i < this.text.length()) {
            if (!m.find(i) || m.start() != i || m.end() == i) {
                throw new IllegalArgumentException("cannot read the rule at '" + this.text.substring(i, Math.min(this.text.length(), i + 20)) + "'");
            }
            if (m.group(1) != null) {
                toks.add(new Tok('l', Double.parseDouble(m.group(1))));
            } else if (m.group(2) != null) {
                toks.add(new Tok('l', m.group(2).replace("''", "'")));
            } else if (m.group(3) != null) {
                toks.add(new Tok('o', m.group(3)));
            } else {
                String w = m.group(4);
                String lw = w.toLowerCase(Locale.ROOT);
                toks.add(List.of("and", "or", "not", "in").contains(lw) ? new Tok('k', lw) : new Tok('f', w));
            }
            i = m.end();
            while (i < this.text.length() && Character.isWhitespace(this.text.charAt(i))) {
                i++;
            }
        }
        if (toks.isEmpty()) {
            throw new IllegalArgumentException("an empty rule");
        }
        tree = or();
        if (at != toks.size()) {
            throw new IllegalArgumentException("unexpected '" + toks.get(at).value() + "' in the rule " + this.text);
        }
    }

    public String text() {
        return text;
    }

    /** The fields the rule reads. */
    public List<String> fields() {
        return List.copyOf(fields);
    }

    private boolean peek(char kind, Object value) {
        return at < toks.size() && toks.get(at).kind() == kind && (value == null || value.equals(toks.get(at).value()));
    }

    private Object take(char kind, Object value) {
        if (!peek(kind, value)) {
            throw new IllegalArgumentException("expected " + (value != null ? value : kind == 'f' ? "a field" : kind == 'l' ? "a value" : "an operator")
                    + " in the rule " + text);
        }
        return toks.get(at++).value();
    }

    private Node or() {
        Node n = and();
        while (peek('k', "or")) {
            at++;
            n = new Or(n, and());
        }
        return n;
    }

    private Node and() {
        Node n = not();
        while (peek('k', "and")) {
            at++;
            n = new AndN(n, not());
        }
        return n;
    }

    private Node not() {
        if (peek('k', "not")) {
            at++;
            return new Not(not());
        }
        if (peek('o', "(")) {
            at++;
            Node n = or();
            take('o', ")");
            return n;
        }
        return cmp();
    }

    private Node cmp() {
        if (peek('l', null)) {
            Object lit = take('l', null);
            take('k', "in");
            String f = (String) take('f', null);
            fields.add(f);
            return new Has(f, lit);
        }
        String f = (String) take('f', null);
        fields.add(f);
        if (peek('k', "in")) {
            at++;
            take('o', "(");
            List<Object> vals = new ArrayList<>();
            vals.add(take('l', null));
            while (peek('o', ",")) {
                at++;
                vals.add(take('l', null));
            }
            take('o', ")");
            return new In(f, vals);
        }
        String op = (String) take('o', null);
        if (!List.of("==", "!=", "<", "<=", ">", ">=").contains(op)) {
            throw new IllegalArgumentException("'" + op + "' is not a comparison in the rule " + text);
        }
        return new Cmp(op, f, take('l', null));
    }

    /** Whether a position whose field values {@code get} answers (null when it has none; a List for a list field) matches. */
    public boolean matches(Function<String, Object> get) {
        return eval(tree, get);
    }

    private static boolean eval(Node n, Function<String, Object> get) {
        return switch (n) {
            case Or o -> eval(o.a(), get) || eval(o.b(), get);
            case AndN a -> eval(a.a(), get) && eval(a.b(), get);
            case Not x -> !eval(x.a(), get);
            case Has h -> get.apply(h.field()) instanceof List<?> l && l.stream().anyMatch(v -> same(v, h.value()));
            case In in -> {
                Object v = get.apply(in.field());
                yield v != null && in.values().stream().anyMatch(x -> same(v, x));
            }
            case Cmp c -> compare(c, get.apply(c.field()));
        };
    }

    private static boolean same(Object v, Object lit) {
        if (lit instanceof Double d) {
            Double x = number(v);
            return x != null && x.doubleValue() == d;
        }
        return v != null && String.valueOf(v).equals(lit);
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? null : Double.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean compare(Cmp c, Object v) {
        if (v == null) {
            return c.op().equals("!=");
        }
        int r;
        if (c.value() instanceof Double d) {
            Double x = number(v);
            if (x == null) {
                return c.op().equals("!=");
            }
            r = Double.compare(x, d);
        } else {
            r = String.valueOf(v).compareTo((String) c.value());
        }
        return switch (c.op()) {
            case "==" -> r == 0;
            case "!=" -> r != 0;
            case "<" -> r < 0;
            case "<=" -> r <= 0;
            case ">" -> r > 0;
            default -> r >= 0;
        };
    }
}
