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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Rachana tree as text, in the one spelling (HOW_IT_FITS.md §A28 Q4): {@code &&}, {@code ||}, {@code !}, {@code c ? a : b},
 * {@code true}, {@code false}, {@code null}, named arguments {@code name = value}, lists {@code [a, b]}, {@code in} and {@code not in}.
 * Parentheses only where the grammar needs them, so printing then parsing gives the same tree. Used by the migration of part B to write
 * the expressions it rewrites, and by the tests that read the shipped DEL expressions as Rachana.
 */
public final class Printer {

    private Printer() {}

    // precedence, loosest first: ternary 1, || 2, && 3, == != 4, < <= > >= in 5, + - 6, * / % 7, unary 8, postfix 9
    public static String print(Expr e) {
        return p(e, 0);
    }

    private static String p(Expr e, int outer) {
        return switch (e) {
            case Expr.Lit l -> literal(l.value());
            case Expr.Absent a -> "null";
            case Expr.Root r -> "$";
            case Expr.Row r -> "@";
            case Expr.Parent r -> "^".repeat(r.up());
            case Expr.Src r -> "$src";
            case Expr.Index r -> "#index";
            case Expr.Ctl c -> "ctl." + c.name();
            case Expr.Event v -> "@" + v.panel();
            case Expr.Local l -> l.name().equals(ListFunctions.ACC) ? "@acc" : l.name();
            case Expr.RowOrRoot r -> "";
            case Expr.Field f -> f.target() instanceof Expr.RowOrRoot ? f.name() : p(f.target(), 9) + "." + f.name();
            case Expr.Pluck f -> p(f.list(), 9) + "." + f.name();
            case Expr.Each x -> p(x.target(), 9) + "[*]";
            case Expr.At a -> p(a.target(), 9) + "[" + p(a.index(), 0) + "]";
            case Expr.Filter f -> p(f.target(), 9) + "[?" + p(f.predicate(), 0) + "]";
            case Expr.ListLit l -> "[" + String.join(", ", l.items().stream().map(x -> p(x, 0)).toList()) + "]";
            case Expr.Not n -> wrap("!" + p(n.e(), 8), 8, outer);
            case Expr.Neg n -> wrap("-" + p(n.e(), 8), 8, outer);
            case Expr.And a -> wrap(p(a.l(), 3) + " && " + p(a.r(), 4), 3, outer);
            case Expr.Or o -> wrap(p(o.l(), 2) + " || " + p(o.r(), 3), 2, outer);
            case Expr.Ternary t -> wrap(p(t.cond(), 2) + " ? " + p(t.then(), 1) + " : " + p(t.otherwise(), 1), 1, outer);
            case Expr.In i -> wrap(p(i.value(), 6) + (i.negated() ? " not in " : " in ") + p(i.list(), 6), 5, outer);
            case Expr.Binary b -> {
                int level = level(b.op());
                yield wrap(p(b.l(), level) + " " + b.op() + " " + p(b.r(), level + 1), level, outer);
            }
            case Expr.Call c -> call(c.name(), c.args(), c.named());
            case Expr.Form f -> call(f.name(), f.args(), Map.of());
            case Expr.Let l -> {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < l.names().size(); i++) {
                    parts.add(l.names().get(i) + " = " + p(l.values().get(i), 0));
                }
                parts.add(p(l.body(), 0));
                yield "let(" + String.join(", ", parts) + ")";
            }
            default -> throw new IllegalArgumentException("cannot print " + e.getClass().getSimpleName());
        };
    }

    private static String call(String name, List<Expr> args, Map<String, Expr> named) {
        List<String> parts = new ArrayList<>();
        args.forEach(a -> parts.add(p(a, 0)));
        named.forEach((k, v) -> parts.add(k + " = " + p(v, 0)));
        return name + "(" + String.join(", ", parts) + ")";
    }

    private static int level(String op) {
        return switch (op) {
            case "==", "!=" -> 4;
            case "<", "<=", ">", ">=" -> 5;
            case "+", "-" -> 6;
            default -> 7;
        };
    }

    private static String wrap(String s, int level, int outer) {
        return level < outer ? "(" + s + ")" : s;
    }

    static String literal(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        if (v instanceof Double d) {
            String s = new java.math.BigDecimal(Double.toString(d)).toPlainString();
            return s;
        }
        return v.toString();
    }
}
