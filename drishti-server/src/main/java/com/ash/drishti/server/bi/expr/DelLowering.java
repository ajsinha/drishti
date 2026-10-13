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
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a checked DEL v2 tree into the compiler's expression tree ({@link Node}), the one the SQL emitter writes with bound parameters. Keyword arguments
 * become the compiler's positional arguments ({@code sum(x, where=c)} is {@code sum(x, c)}; {@code rank(agg, desc=False, by=[f])} is
 * {@code rank(agg, 'asc', f)}), renamed functions their compiler names ({@code len} is {@code length}, {@code startswith} is {@code startsWith}),
 * {@code a if c else b} a conditional, {@code x is None} an {@code IS NULL} test, {@code measure('name')} a measure reference, a chained comparison an
 * {@code and} of comparisons. So the same question in the old syntax and in DEL v2 compiles to the same SQL. Pure.
 */
public final class DelLowering {

    private final FunctionCatalog catalog;

    private DelLowering(FunctionCatalog catalog) {
        this.catalog = catalog;
    }

    public static Node lower(DelAst n) {
        return new DelLowering(FunctionCatalog.get()).node(n);
    }

    Node node(DelAst n) {
        return switch (n) {
            case DelAst.Num x -> new Node.Num(x.value(), x.integral());
            case DelAst.Str x -> new Node.Str(x.value());
            case DelAst.Bool x -> new Node.Bool(x.value());
            case DelAst.NoneLit x -> new Node.Null();
            case DelAst.AllLit x -> new Node.Ref(List.of("ALL"));
            case DelAst.ListLit x -> throw new DelException(DelParser.CODE, "a list is only the right side of in", null, x.start(), x.end());
            case DelAst.Field f -> f.parts().size() == 2 && "user".equals(f.parts().get(0)) ? new Node.UserRef(f.parts().get(1)) : new Node.Ref(f.parts());
            case DelAst.Call c -> call(c);
            case DelAst.Unary u -> switch (u.op()) {
                case "not" -> new Node.Un("!", node(u.operand()));
                case "-" -> new Node.Un("-", node(u.operand()));
                default -> node(u.operand());
            };
            case DelAst.Binary b -> "**".equals(b.op()) ? new Node.Call("power", List.of(node(b.left()), node(b.right()))) : new Node.Bin(b.op(), node(b.left()), node(b.right()));
            case DelAst.BoolOp b -> {
                String op = "and".equals(b.op()) ? "&&" : "||";
                Node acc = node(b.values().get(0));
                for (int i = 1; i < b.values().size(); i++) {
                    acc = new Node.Bin(op, acc, node(b.values().get(i)));
                }
                yield acc;
            }
            case DelAst.Compare c -> compare(c);
            case DelAst.IfExp i -> new Node.Cond(node(i.test()), node(i.body()), node(i.orElse()));
        };
    }

    private Node compare(DelAst.Compare c) {
        Node acc = null;
        DelAst left = c.first();
        for (int i = 0; i < c.ops().size(); i++) {
            DelAst right = c.rest().get(i);
            Node one = switch (c.ops().get(i)) {
                case "is" -> new Node.Bin("==", node(left), new Node.Null());
                case "is not" -> new Node.Bin("!=", node(left), new Node.Null());
                case "in", "not in" -> new Node.In(node(left), items(right), "not in".equals(c.ops().get(i)));
                default -> new Node.Bin(c.ops().get(i), node(left), node(right));
            };
            acc = acc == null ? one : new Node.Bin("&&", acc, one);
            left = right;
        }
        return acc;
    }

    private List<Node> items(DelAst right) {
        if (right instanceof DelAst.ListLit l) {
            return l.items().stream().map(this::node).toList();
        }
        return List.of(node(right));                                           // user.desks
    }

    private Node call(DelAst.Call c) {
        String name = c.name();
        if ("measure".equals(name)) {
            return new Node.MeasureRef(((DelAst.Str) c.args().get(0)).value());
        }
        FunctionCatalog.Fn f = catalog.function(name).orElseThrow(() -> new DelException(DelParser.CODE, "unknown function '" + name + "'", null, c.start(), c.end()));
        List<DelAst> positional = new ArrayList<>(c.args());
        String compiled = catalog.toLegacy(name);
        List<Node> args = new ArrayList<>();
        switch (f.family()) {
            case "windows" -> {
                if (List.of("rank", "dense_rank", "percent_rank").contains(name)) {
                    args.add(node(positional.get(0)));
                    DelAst desc = c.keyword("desc");
                    DelAst by = c.keyword("by");
                    List<Node> partition = fields(by);
                    if (desc != null || !partition.isEmpty()) {
                        boolean d = !(desc instanceof DelAst.Bool b) || b.value();
                        args.add(new Node.Str(d ? "desc" : "asc"));
                    }
                    args.addAll(partition);
                    return new Node.Call(compiled, args);
                }
                if (List.of("running", "cumulative").contains(name)) {
                    positional.forEach(a -> args.add(node(a)));
                    return new Node.Call(compiled, args);
                }
                positional.forEach(a -> args.add(node(a)));                    // lag, lead, moving_*: agg, n, order, then by=
                args.addAll(fields(c.keyword("by")));
                return new Node.Call(compiled, args);
            }
            default -> {
                for (int i = 0; i < f.params().size(); i++) {                  // positional and keyword arguments in parameter order
                    FunctionCatalog.Param p = f.params().get(i);
                    if (p.variadic()) {
                        positional.subList(Math.min(i, positional.size()), positional.size()).forEach(a -> args.add(node(a)));
                        break;
                    }
                    DelAst value = i < positional.size() && !p.keywordOnly() ? positional.get(i) : c.keyword(p.name());
                    if (value == null) {
                        if ("round".equals(name)) {
                            continue;                                          // round(x): the compiler's one-argument round
                        }
                        break;                                                 // a trailing default: the compiler's shorter call
                    }
                    args.add(node(value));
                }
                return new Node.Call(compiled, args);
            }
        }
    }

    private List<Node> fields(DelAst list) {
        if (list instanceof DelAst.ListLit l) {
            return l.items().stream().map(this::node).toList();
        }
        return List.of();
    }
}
