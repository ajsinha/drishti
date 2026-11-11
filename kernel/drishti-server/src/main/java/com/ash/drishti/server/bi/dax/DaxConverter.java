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
package com.ash.drishti.server.bi.dax;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts common DAX measure patterns to Rachana (the cookbook of docs/guides/RACHANA_REFERENCE.md). There is no DAX engine: this only rewrites text into the one
 * grammar, and names exactly what it did not convert rather than guessing.
 */
public final class DaxConverter {

    /** The Rachana text ({@code null} when nothing could be written), what could not be converted, and what the author should check. */
    public record Result(String rachana, List<String> notConverted, List<String> notes) {}

    private static final Pattern TOKEN = Pattern.compile("\\s*(?:'([^']+)'\\[([^\\]]+)\\]|([A-Za-z_][\\w]*)\\[([^\\]]+)\\]|\\[([^\\]]+)\\]|\"((?:[^\"]|\"\")*)\"|(\\d+(?:\\.\\d+)?)"
            + "|([A-Za-z_][\\w.]*)|(<>|<=|>=|&&|\\|\\||[-+*/=<>(),]))");

    private static final Set<String> AGG = Set.of("SUM", "AVERAGE", "MIN", "MAX", "COUNT", "DISTINCTCOUNT", "COUNTROWS", "COUNTA");
    private static final Set<String> ITER = Set.of("SUMX", "AVERAGEX", "MINX", "MAXX");

    private final List<String> tokens = new ArrayList<>();
    private final Set<String> notConverted = new LinkedHashSet<>();
    private final Set<String> notes = new LinkedHashSet<>();
    private int p;

    private DaxConverter() {}

    /** Converts one DAX expression, or a {@code Name = expression} measure definition. */
    public static Result convert(String dax) {
        DaxConverter c = new DaxConverter();
        String body = dax == null ? "" : dax.strip();
        int eq = body.indexOf('=');
        if (eq > 0 && body.substring(0, eq).matches("\\s*[\\w '\\[\\]]+\\s*") && !body.substring(0, eq).strip().contains("(")) {
            body = body.substring(eq + 1).strip();
        }
        if (body.toUpperCase(Locale.ROOT).startsWith("VAR ") || body.toUpperCase(Locale.ROOT).contains("\nVAR ")) {
            return new Result(null, List.of("VAR ... RETURN: write it as named measures"), List.of());
        }
        c.lex(body);
        String out;
        try {
            out = c.expr();
            if (c.p < c.tokens.size()) {
                c.notConverted.add("text after the expression: " + String.join(" ", c.tokens.subList(c.p, c.tokens.size())));
            }
        } catch (RuntimeException e) {
            return new Result(null, List.copyOf(c.notConverted.isEmpty() ? Set.of("could not read: " + e.getMessage()) : c.notConverted), List.copyOf(c.notes));
        }
        return new Result(c.notConverted.isEmpty() ? out : null, List.copyOf(c.notConverted), List.copyOf(c.notes));
    }

    private void lex(String s) {
        Matcher m = TOKEN.matcher(s);
        int at = 0;
        while (at < s.length() && m.find(at) && m.start() == at) {
            at = m.end();
            String col = m.group(1) != null ? m.group(1) + "." + m.group(2) : m.group(3) != null ? m.group(3) + "." + m.group(4) : null;
            tokens.add(col != null ? "@" + col : m.group(5) != null ? "#" + m.group(5) : m.group(6) != null ? "\"" + m.group(6).replace("\"\"", "\"") : m.group(7) != null ? m.group(7) : m.group(8) != null ? m.group(8) : m.group(9));
        }
        if (at < s.strip().length()) {
            notConverted.add("text from: " + s.substring(at));
        }
    }

    private String peek() {
        return p < tokens.size() ? tokens.get(p) : "";
    }

    private String next() {
        return tokens.get(p++);
    }

    private boolean take(String t) {
        if (peek().equalsIgnoreCase(t)) {
            p++;
            return true;
        }
        return false;
    }

    private String expr() {
        return logic();
    }

    private String logic() {
        String l = compare();
        while (peek().equals("&&") || peek().equals("||")) {
            String op = next();
            l = l + " " + op + " " + compare();
        }
        return l;
    }

    private String compare() {
        String l = sum();
        if (Set.of("=", "<>", "<", ">", "<=", ">=").contains(peek())) {
            String op = next();
            op = "=".equals(op) ? "==" : "<>".equals(op) ? "!=" : op;
            return l + " " + op + " " + sum();
        }
        return l;
    }

    private String sum() {
        String l = term();
        while (peek().equals("+") || peek().equals("-")) {
            String op = next();
            l = l + " " + op + " " + term();
        }
        return l;
    }

    private String term() {
        String l = unary();
        while (peek().equals("*") || peek().equals("/")) {
            String op = next();
            l = l + " " + op + " " + unary();
        }
        return l;
    }

    private String unary() {
        if (take("-")) {
            return "-" + unary();
        }
        return atom();
    }

    private String atom() {
        if (p >= tokens.size()) {
            throw new IllegalStateException("unexpected end of expression");
        }
        String t = next();
        if ("(".equals(t)) {
            String inner = expr();
            expect(")");
            return "(" + inner + ")";
        }
        if (t.startsWith("@")) {
            return t.substring(1);
        }
        if (t.startsWith("#")) {
            return "measure('" + t.substring(1).replace("'", "\\'") + "')";
        }
        if (t.startsWith("\"")) {
            return "'" + t.substring(1).replace("'", "\\'") + "'";
        }
        if (t.matches("\\d+(\\.\\d+)?")) {
            return t;
        }
        if (t.equalsIgnoreCase("TRUE") || t.equalsIgnoreCase("FALSE") || t.equalsIgnoreCase("BLANK")) {
            if (take("(")) {
                expect(")");
            }
            return t.equalsIgnoreCase("BLANK") ? "null" : t.toLowerCase(Locale.ROOT);
        }
        String fn = t.toUpperCase(Locale.ROOT);
        expect("(");
        return call(fn);
    }

    private void expect(String t) {
        if (!take(t)) {
            throw new IllegalStateException("expected '" + t + "' near '" + peek() + "'");
        }
    }

    private String call(String fn) {
        if (AGG.contains(fn)) {
            String arg = peek().startsWith("@") ? next().substring(1) : next();
            expect(")");
            return switch (fn) {
                case "SUM" -> "sum(" + arg + ")";
                case "AVERAGE" -> "avg(" + arg + ")";
                case "MIN" -> "min(" + arg + ")";
                case "MAX" -> "max(" + arg + ")";
                case "DISTINCTCOUNT" -> "distinct(" + arg + ")";
                case "COUNTROWS" -> "count(" + arg + ")";
                default -> "count(" + arg + ")";
            };
        }
        if (ITER.contains(fn)) {
            return iterator(fn);
        }
        return switch (fn) {
            case "CALCULATE" -> calculate();
            case "DIVIDE" -> {
                String a = expr();
                expect(",");
                String b = expr();
                if (take(",")) {
                    expr();
                    notes.add("DIVIDE's alternate result is dropped: Rachana gives null when dividing by zero");
                }
                expect(")");
                yield "ratio(" + a + ", " + b + ")";
            }
            case "IF" -> {
                String c = expr();
                expect(",");
                String a = expr();
                String b = take(",") ? expr() : "null";
                expect(")");
                yield "if(" + c + ", " + a + ", " + b + ")";
            }
            default -> {
                notConverted.add(fn + ": no Rachana pattern (" + skipCall() + ")");
                yield "null";
            }
        };
    }

    private String skipCall() {
        StringBuilder b = new StringBuilder();
        int depth = 1;
        while (p < tokens.size() && depth > 0) {
            String t = next();
            depth += "(".equals(t) ? 1 : ")".equals(t) ? -1 : 0;
            if (depth > 0) {
                b.append(t.startsWith("@") || t.startsWith("#") || t.startsWith("\"") ? t.substring(1) : t).append(' ');
            }
        }
        return b.toString().strip();
    }

    private String iterator(String fn) {
        String outer = switch (fn) {
            case "SUMX" -> "sum_by";
            case "AVERAGEX" -> "avg_by";
            case "MINX" -> "min_by";
            default -> "max_by";
        };
        String by = null;
        if (take("VALUES") && take("(")) {
            by = columnName(next());
            expect(")");
        } else if (peek().startsWith("@") || peek().startsWith("#")) {
            by = columnName(next());
        }
        if (by == null) {
            notConverted.add(fn + ": only an iterator over VALUES(table[column]) or a column converts; " + fn + " over a table has no written grain (" + skipCall() + ")");
            return "null";
        }
        expect(",");
        String inner = expr();
        expect(")");
        return outer + "(" + inner + ", by = [" + by + "])";
    }

    private static String columnName(String t) {
        return t.startsWith("@") ? t.substring(1) : t.startsWith("#") ? t.substring(1) : t;
    }

    private String calculate() {
        String inner = expr();
        List<String> ignore = new ArrayList<>();
        List<String> keep = new ArrayList<>();
        List<String> where = new ArrayList<>();
        String via = null;
        String filter = null;
        String having = null;
        List<String> by = new ArrayList<>();
        while (take(",")) {
            String t = peek().toUpperCase(Locale.ROOT);
            switch (t) {
                case "ALLSELECTED" -> {
                    next();
                    expect("(");
                    if (!take(")")) {
                        notConverted.add("ALLSELECTED with arguments (" + skipCall() + ")");
                    } else {
                        ignore.add("GROUP");
                    }
                }
                case "REMOVEFILTERS", "ALL" -> {
                    next();
                    expect("(");
                    if (take(")")) {
                        ignore.add("ALL");
                    } else {
                        do {
                            String one = next();
                            ignore.add(one.startsWith("@") ? one.substring(1) : one);
                        } while (take(","));
                        expect(")");
                    }
                }
                case "ALLEXCEPT" -> {
                    next();
                    expect("(");
                    ignore.add(next());
                    while (take(",")) {
                        String k = next();
                        keep.add(k.startsWith("@") ? k.substring(1) : k);
                    }
                    expect(")");
                }
                case "KEEPFILTERS" -> {
                    next();
                    expect("(");
                    where.add(expr());
                    expect(")");
                }
                case "USERELATIONSHIP" -> {
                    next();
                    expect("(");
                    String a = next();
                    expect(",");
                    next();
                    expect(")");
                    via = "name this relationship (" + (a.startsWith("@") ? a.substring(1) : a) + ")";
                    notes.add("USERELATIONSHIP: name the relationship in the model and write its name in via =");
                }
                case "CROSSFILTER" -> {
                    next();
                    expect("(");
                    String a = next();
                    expect(",");
                    next();
                    expect(",");
                    String d = next().toUpperCase(Locale.ROOT);
                    expect(")");
                    via = via == null ? "name this relationship (" + (a.startsWith("@") ? a.substring(1) : a) + ")" : via;
                    filter = "ONEWAY".equals(d) || "NONE".equals(d) ? "one_way" : "both";
                    notes.add("CROSSFILTER: name the relationship in the model and write its name in via =");
                }
                case "FILTER" -> {
                    next();
                    expect("(");
                    if (take("ALL") && take("(")) {
                        String one = next();
                        String f = one.startsWith("@") ? one.substring(1) : one;
                        ignore.add(f);
                        by.add(f);
                        expect(")");
                        expect(",");
                        having = expr();
                        expect(")");
                    } else {
                        notConverted.add("FILTER over a table: only FILTER(ALL(table[column]), condition) converts (" + skipCall() + ")");
                    }
                }
                default -> {
                    int start = p;
                    String cond = expr();
                    Matcher column = Pattern.compile("^([\\w.]+) == ").matcher(cond);
                    if (column.find() && !cond.contains("&&") && !cond.contains("||") && p > start) {
                        ignore.add(column.group(1));                                 // a filter argument replaces the filters on its column; say so with ignore
                    }
                    where.add(cond);
                }
            }
        }
        expect(")");
        StringBuilder b = new StringBuilder("calc(").append(inner);
        if (ignore.contains("ALL")) {
            b.append(", ignore = ALL");
        } else if (ignore.contains("GROUP")) {
            b.append(", ignore = GROUP");
        } else if (!ignore.isEmpty()) {
            b.append(", ignore = [").append(String.join(", ", ignore)).append("]");
        }
        if (!keep.isEmpty()) {
            b.append(", keep = [").append(String.join(", ", keep)).append("]");
        }
        if (!where.isEmpty()) {
            b.append(", where = ").append(String.join(" && ", where));
        }
        if (via != null) {
            b.append(", via = '").append(via).append("'");
        }
        if (filter != null) {
            b.append(", filter = '").append(filter).append("'");
        }
        if (having != null) {
            b.append(", by = [").append(String.join(", ", by)).append("], having = ").append(having);
        }
        return b.append(")").toString();
    }
}
