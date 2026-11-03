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

import java.util.StringJoiner;

/**
 * Prints a {@link Node} as canonical Rachana text: single-quoted texts, one space around binary operators, {@code name = value} for named
 * arguments, and parentheses only where precedence needs them. Reading what it prints gives the same tree, and printing that gives the
 * same text (the migration's idempotence rests on it).
 */
public final class Printer {

    private Printer() {}

    public static String print(Node n) {
        StringBuilder sb = new StringBuilder();
        write(n, sb);
        return sb.toString();
    }

    /** Precedence, higher binds tighter. */
    static int level(Node n) {
        return switch (n) {
            case Node.Cond c -> 1;
            case Node.Binary b -> switch (b.op()) {
                case "||" -> 2;
                case "&&" -> 3;
                case "+", "-" -> 5;
                case "*", "/", "%" -> 6;
                default -> 4;                                               // comparisons
            };
            case Node.Unary u -> 7;
            default -> 8;
        };
    }

    private static void write(Node n, StringBuilder sb) {
        switch (n) {
            case Node.Lit l -> sb.append(l.value() instanceof String s && l.text() == null ? quote(s) : l.text());
            case Node.Path p -> sb.append(p.text());
            case Node.Name x -> sb.append(x.name());
            case Node.ListOf l -> {
                StringJoiner j = new StringJoiner(", ", "[", "]");
                l.items().forEach(i -> j.add(print(i)));
                sb.append(j);
            }
            case Node.Call c -> {
                StringJoiner j = new StringJoiner(", ", c.function() + "(", ")");
                for (Node.Arg a : c.args()) {
                    j.add(a.name() == null ? print(a.value()) : a.name() + " = " + print(a.value()));
                }
                sb.append(j);
            }
            case Node.Unary u -> {
                sb.append(u.op());
                sub(u.operand(), level(u.operand()) < 7, sb);
            }
            case Node.Binary b -> {
                int me = level(b);
                boolean cmp = me == 4;
                sub(b.left(), cmp ? level(b.left()) <= me : level(b.left()) < me, sb);
                sb.append(' ').append(b.op().equals("not-in") ? "not in" : b.op()).append(' ');
                sub(b.right(), level(b.right()) <= me, sb);
            }
            case Node.Cond c -> {
                sub(c.test(), level(c.test()) <= 1, sb);
                sb.append(" ? ");
                write(c.then(), sb);
                sb.append(" : ");
                write(c.otherwise(), sb);
            }
        }
    }

    private static void sub(Node n, boolean parens, StringBuilder sb) {
        if (parens) {
            sb.append('(');
        }
        write(n, sb);
        if (parens) {
            sb.append(')');
        }
    }

    /** {@code 'text'} with {@code \} before a quote or a backslash, as Rachana reads it. */
    public static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
