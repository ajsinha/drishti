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

import com.ash.drishti.rachana.el.Expr;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A DEL tree as Rachana's (docs/architecture/ARCHITECTURE.md §28 2.4), for the migration's DEL reader ({@link DelTablesTranslator}, kept until 2.9.14) and the
 * migration of part B has its mapping: {@code and}/{@code or}/{@code not} are {@code &&}/{@code ||}/{@code !}, {@code a if c else b}
 * is {@code c ? a : b}, a chained comparison is its comparisons joined by {@code &&}, {@code x is None} is {@code x == null},
 * {@code **} and {@code //} are {@code power} and {@code floor(x / y)}, keywords are named arguments, and the renamed functions take
 * their Rachana names ({@code startswith} → {@code starts_with}, {@code last} → {@code latest}, {@code first} → {@code earliest},
 * {@code cumulative} → {@code running}). Every node keeps where it was written in the DEL text. The null rule's rewrite (Q1) is the
 * migration's, not this mapping's: a type is the same under either rule.
 */
public final class DelToRachana {

    /** DEL's names that Rachana spells otherwise. */
    public static final Map<String, String> NAMES = Map.of("startswith", "starts_with", "endswith", "ends_with", "last", "latest", "first",
            "earliest", "cumulative", "running");

    /** The Rachana tree and where each node was written in the DEL text. */
    public record Mapped(Expr expr, Map<Expr, int[]> spans) {}

    private final Map<Expr, int[]> spans = new IdentityHashMap<>();

    private DelToRachana() {}

    public static Mapped map(DelAst tree) {
        DelToRachana m = new DelToRachana();
        return new Mapped(m.node(tree), m.spans);
    }

    private Expr at(Expr e, DelAst n) {
        spans.putIfAbsent(e, new int[] {n.start(), n.end()});
        return e;
    }

    private Expr node(DelAst n) {
        return switch (n) {
            case DelAst.Num x -> at(new Expr.Lit(x.integral() && Math.abs(x.value()) < 9e15 ? (Object) (long) x.value() : (Object) x.value()), n);
            case DelAst.Str x -> at(new Expr.Lit(x.value()), n);
            case DelAst.Bool x -> at(new Expr.Lit(x.value()), n);
            case DelAst.NoneLit x -> at(new Expr.Lit(null), n);
            case DelAst.AllLit x -> field(List.of("ALL"), n);
            case DelAst.ListLit x -> at(new Expr.ListLit(x.items().stream().map(this::node).toList()), n);
            case DelAst.Field f -> field(f.parts(), n);
            case DelAst.Call c -> call(c);
            case DelAst.Unary u -> switch (u.op()) {
                case "not" -> at(new Expr.Not(node(u.operand())), n);
                case "-" -> at(new Expr.Neg(node(u.operand())), n);
                default -> node(u.operand());
            };
            case DelAst.Binary b -> switch (b.op()) {
                case "**" -> at(new Expr.Call("power", null, List.of(node(b.left()), node(b.right()))), n);
                case "//" -> at(new Expr.Call("floor", null, List.of(at(new Expr.Binary("/", node(b.left()), node(b.right())), n))), n);
                default -> at(new Expr.Binary(b.op(), node(b.left()), node(b.right())), n);
            };
            case DelAst.BoolOp b -> {
                Expr out = node(b.values().get(0));
                for (int i = 1; i < b.values().size(); i++) {
                    Expr next = node(b.values().get(i));
                    out = at("and".equals(b.op()) ? new Expr.And(out, next) : new Expr.Or(out, next), n);
                }
                yield out;
            }
            case DelAst.Compare c -> compare(c);
            case DelAst.IfExp i -> at(new Expr.Ternary(node(i.test()), node(i.body()), node(i.orElse())), n);
        };
    }

    private Expr field(List<String> parts, DelAst n) {
        Expr e = at(new Expr.RowOrRoot(), n);
        for (String p : parts) {
            e = at(new Expr.Field(e, p, null), n);
        }
        return e;
    }

    private Expr call(DelAst.Call c) {
        String name = NAMES.getOrDefault(c.name(), c.name());
        List<Expr> args = new ArrayList<>();
        c.args().forEach(a -> args.add(node(a)));
        Map<String, Expr> named = new LinkedHashMap<>();
        c.keywords().forEach(k -> named.put(k.name(), node(k.value())));
        return at(new Expr.Call(name, null, args, named), c);
    }

    /** {@code a < b <= c} is {@code a < b && b <= c}; {@code x is None} is {@code x == null}; {@code in} is Rachana's strict {@code in}. */
    private Expr compare(DelAst.Compare c) {
        Expr out = null;
        Expr left = node(c.first());
        for (int i = 0; i < c.ops().size(); i++) {
            String op = c.ops().get(i);
            Expr right = node(c.rest().get(i));
            Expr one = switch (op) {
                case "in" -> new Expr.In(left, right, false);
                case "not in" -> new Expr.In(left, right, true);
                case "is" -> new Expr.Binary("==", left, right);
                case "is not" -> new Expr.Binary("!=", left, right);
                default -> new Expr.Binary(op, left, right);
            };
            at(one, c);
            out = out == null ? one : at(new Expr.And(out, one), c);
            left = right;
        }
        return out;
    }
}
