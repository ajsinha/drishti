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
package com.ash.drishti.server.bi.expr;

import com.ash.drishti.server.bi.dataset.Node;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Prints a compiler tree as DEL v2 text: the converter's printer (docs/architecture/BI_DESIGN.md section 3.3.7). The old syntax is parsed by the W1 parser
 * and its tree printed here, never rewritten as text, so {@code [P&L 1d]} becomes {@code measure('P&L 1d')}, {@code c ? a : b} and {@code if(c, a, b)}
 * become {@code a if c else b}, {@code sum(x, c)} becomes {@code sum(x, where=c)}, {@code &&} {@code and}, {@code x = null} {@code x is None}. Brackets are
 * written only where Python's precedence needs them. Pure.
 */
public final class DelPrinter {

    private static final Set<String> CONDITIONAL_AGGREGATES = Set.of("sum", "avg", "min", "max", "count", "distinct");
    private static final Set<String> RANKS = Set.of("rank", "dense_rank", "percent_rank");
    private static final Set<String> ORDERED = Set.of("lag", "lead", "moving_avg", "moving_sum", "moving_min", "moving_max");

    private static final int IF = 0;
    private static final int OR = 1;
    private static final int AND = 2;
    private static final int NOT = 3;
    private static final int CMP = 4;
    private static final int ARITH = 5;
    private static final int TERM = 6;
    private static final int UNARY = 7;
    private static final int ATOM = 9;

    private final FunctionCatalog catalog;

    private DelPrinter(FunctionCatalog catalog) {
        this.catalog = catalog;
    }

    public static String print(Node n) {
        return new DelPrinter(FunctionCatalog.get()).p(n, IF);
    }

    private String p(Node n, int min) {
        int prec = precedence(n);
        String s = text(n);
        return prec < min ? "(" + s + ")" : s;
    }

    private static int precedence(Node n) {
        return switch (n) {
            case Node.Cond c -> IF;
            case Node.Call c when "if".equals(c.function()) -> IF;
            case Node.Bin b when "||".equals(b.op()) -> OR;
            case Node.Bin b when "&&".equals(b.op()) -> AND;
            case Node.Un u when "!".equals(u.op()) -> NOT;
            case Node.Bin b when Set.of("==", "!=", "<", "<=", ">", ">=").contains(b.op()) -> CMP;
            case Node.In i -> CMP;
            case Node.Bin b when Set.of("+", "-").contains(b.op()) -> ARITH;
            case Node.Bin b -> TERM;
            case Node.Un u -> UNARY;
            case Node.Num x when x.value() < 0 -> UNARY;
            default -> ATOM;
        };
    }

    private String text(Node n) {
        return switch (n) {
            case Node.Num x -> number(x);
            case Node.Str s -> quote(s.value());
            case Node.Bool b -> b.value() ? "True" : "False";
            case Node.Null x -> "None";
            case Node.Ref r -> r.text();
            case Node.UserRef u -> "user." + u.attribute();
            case Node.MeasureRef m -> "measure(" + quote(m.name()) + ")";
            case Node.Cond c -> p(c.yes(), OR) + " if " + p(c.test(), OR) + " else " + p(c.no(), IF);
            case Node.Un u -> "!".equals(u.op()) ? "not " + p(u.operand(), NOT) : "-" + p(u.operand(), UNARY);
            case Node.In i -> p(i.value(), ARITH) + (i.negated() ? " not in " : " in ") + items(i.items());
            case Node.Bin b -> bin(b);
            case Node.Call c -> call(c);
        };
    }

    private String bin(Node.Bin b) {
        return switch (b.op()) {
            case "&&" -> p(b.left(), AND) + " and " + p(b.right(), NOT);
            case "||" -> p(b.left(), OR) + " or " + p(b.right(), AND);
            case "==", "!=" -> {
                if (b.right() instanceof Node.Null || b.left() instanceof Node.Null) {
                    Node other = b.right() instanceof Node.Null ? b.left() : b.right();
                    yield p(other, ARITH) + ("==".equals(b.op()) ? " is None" : " is not None");
                }
                yield p(b.left(), ARITH) + " " + b.op() + " " + p(b.right(), ARITH);
            }
            case "<", "<=", ">", ">=" -> p(b.left(), ARITH) + " " + b.op() + " " + p(b.right(), ARITH);
            case "+", "-" -> p(b.left(), ARITH) + " " + b.op() + " " + p(b.right(), TERM);
            default -> p(b.left(), TERM) + " " + b.op() + " " + p(b.right(), UNARY);
        };
    }

    private String items(List<Node> items) {
        if (items.size() == 1 && items.get(0) instanceof Node.UserRef u) {
            return "user." + u.attribute();
        }
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(p(i, IF)));
        return "[" + String.join(", ", out) + "]";
    }

    private String call(Node.Call c) {
        String f = c.function();
        List<Node> a = c.args();
        if ("if".equals(f) && a.size() == 3) {
            return p(a.get(1), OR) + " if " + p(a.get(0), OR) + " else " + p(a.get(2), IF);
        }
        String name = catalog.fromLegacy(f);
        List<String> out = new ArrayList<>();
        if (CONDITIONAL_AGGREGATES.contains(f) && a.size() == 2) {
            out.add(p(a.get(0), IF));
            out.add("where=" + p(a.get(1), IF));
        } else if ("percentile".equals(f) && a.size() == 3) {
            out.add(p(a.get(0), IF));
            out.add(p(a.get(1), IF));
            out.add("where=" + p(a.get(2), IF));
        } else if (RANKS.contains(f) && a.size() >= 2) {
            out.add(p(a.get(0), IF));
            boolean desc = !(a.get(1) instanceof Node.Str s) || !"asc".equals(s.value().toLowerCase(Locale.ROOT));
            out.add("desc=" + (desc ? "True" : "False"));
            if (a.size() > 2) {
                out.add("by=" + list(a.subList(2, a.size())));
            }
        } else if (ORDERED.contains(f) && a.size() > 3) {
            a.subList(0, 3).forEach(x -> out.add(p(x, IF)));
            out.add("by=" + list(a.subList(3, a.size())));
        } else {
            a.forEach(x -> out.add(p(x, IF)));
        }
        return name + "(" + String.join(", ", out) + ")";
    }

    private String list(List<Node> items) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(p(i, IF)));
        return "[" + String.join(", ", out) + "]";
    }

    static String number(Node.Num x) {
        double v = x.value();
        if (x.integral() && Math.abs(v) < 9e15) {
            return Long.toString((long) v);
        }
        String s = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        return s.contains(".") ? s : s + ".0";
    }

    /** A DEL text literal: single quotes unless the text has one and no double quote. */
    public static String quote(String s) {
        char q = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder b = new StringBuilder().append(q);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c == q) {
                        b.append('\\');
                    }
                    b.append(c);
                }
            }
        }
        return b.append(q).toString();
    }
}
