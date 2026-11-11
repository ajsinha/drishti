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

import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Printer;
import com.ash.drishti.rachana.el.check.Signatures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BI's compiler tree written back as Rachana text, the inverse of {@link NodeLowering}: what BI writes when it composes an expression (a filter
 * context's measure, a bound parameter, a measure along an inactive relationship, a canonical text) so that reading it again gives the same
 * tree. It builds Rachana's tree and prints it with Rachana's one printer ({@link Printer}); a decimal written as a whole number keeps its point.
 * Pure.
 */
public final class NodeText {

    private static final Set<String> CONDITIONAL_AGGREGATES = Set.of("sum", "avg", "min", "max", "count", "distinct");
    private static final Set<String> RANKS = Set.of("rank", "dense_rank", "percent_rank");
    private static final Set<String> ORDERED = Set.of("lag", "lead", "moving_avg", "moving_sum", "moving_min", "moving_max");
    /** The compiler's names Rachana spells otherwise. */
    private static final Map<String, String> RACHANA = Map.of("length", "len", "startsWith", "starts_with", "endswith", "ends_with", "last", "latest",
            "first", "earliest", "cumulative", "running");

    private NodeText() {}

    /** The Rachana text of a compiler tree. */
    public static String print(Node n) {
        return Printer.print(tree(n));
    }

    /** A text literal as Rachana writes it. */
    public static String quote(String s) {
        return Printer.print(new Expr.Lit(s));
    }

    /** The Rachana tree of a compiler tree. */
    public static Expr tree(Node n) {
        return switch (n) {
            case Node.Num x -> number(x);
            case Node.Str s -> new Expr.Lit(s.value());
            case Node.Bool b -> new Expr.Lit(b.value());
            case Node.Null x -> new Expr.Lit(null);
            case Node.Ref r -> field(r.parts());
            case Node.UserRef u -> field(List.of("user", u.attribute()));
            case Node.MeasureRef m -> new Expr.Call("measure", null, List.of(new Expr.Lit(m.name())));
            case Node.Cond c -> new Expr.Ternary(tree(c.test()), tree(c.yes()), tree(c.no()));
            case Node.Un u -> "!".equals(u.op()) ? new Expr.Not(tree(u.operand())) : new Expr.Neg(tree(u.operand()));
            case Node.In i -> new Expr.In(tree(i.value()), i.items().size() == 1 && i.items().get(0) instanceof Node.UserRef ? tree(i.items().get(0))
                    : list(i.items()), i.negated());
            case Node.Bin b -> switch (b.op()) {
                case "&&" -> new Expr.And(tree(b.left()), tree(b.right()));
                case "||" -> new Expr.Or(tree(b.left()), tree(b.right()));
                default -> new Expr.Binary(b.op(), tree(b.left()), tree(b.right()));
            };
            case Node.Call c -> call(c);
        };
    }

    private static Expr number(Node.Num x) {
        double v = x.value();
        if (x.integral() && Math.abs(v) < 9e15) {
            return new Expr.Lit((long) v);
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return new Expr.Lit(BigDecimal.valueOf((long) v).setScale(1));     // 1.0 stays a decimal when read again
        }
        return new Expr.Lit(v);
    }

    private static Expr field(List<String> parts) {
        Expr e = new Expr.RowOrRoot();
        for (String p : parts) {
            e = new Expr.Field(e, p, null);
        }
        return e;
    }

    private static Expr list(List<Node> items) {
        return new Expr.ListLit(items.stream().map(NodeText::tree).toList());
    }

    private static Expr call(Node.Call c) {
        String f = c.function();
        List<Node> a = c.args();
        if ("if".equals(f) && a.size() == 3) {
            return new Expr.Ternary(tree(a.get(0)), tree(a.get(1)), tree(a.get(2)));
        }
        String name = RACHANA.getOrDefault(f, f);
        List<Expr> args = new ArrayList<>();
        Map<String, Expr> named = new LinkedHashMap<>();
        if (Node.CONTEXT.contains(f) || Node.SCALARS_B.contains(f)) {
            Signatures.Signature sig = Signatures.get().function(name).orElse(null);
            for (Node x : a) {
                if (x instanceof Node.Call k && k.function().startsWith(Node.KEYWORD)) {
                    String kw = k.function().substring(Node.KEYWORD.length());
                    boolean fields = sig != null && sig.param(kw).map(p -> p.type().startsWith("fields")).orElse(false);
                    boolean all = k.args().size() == 1 && k.args().get(0) instanceof Node.Ref r && r.parts().equals(List.of("ALL"));
                    named.put(kw, fields && !all ? list(k.args()) : k.args().isEmpty() ? new Expr.Lit(null) : tree(k.args().get(0)));
                } else {
                    args.add(tree(x));
                }
            }
            return new Expr.Call(name, null, args, named);
        }
        if (CONDITIONAL_AGGREGATES.contains(f) && a.size() == 2) {
            args.add(tree(a.get(0)));
            named.put("where", tree(a.get(1)));
        } else if ("percentile".equals(f) && a.size() == 3) {
            args.add(tree(a.get(0)));
            args.add(tree(a.get(1)));
            named.put("where", tree(a.get(2)));
        } else if (RANKS.contains(f) && a.size() >= 2) {
            args.add(tree(a.get(0)));
            boolean desc = !(a.get(1) instanceof Node.Str s) || !"asc".equals(s.value().toLowerCase(Locale.ROOT));
            named.put("desc", new Expr.Lit(desc));
            if (a.size() > 2) {
                named.put("by", list(a.subList(2, a.size())));
            }
        } else if (ORDERED.contains(f) && a.size() > 3) {
            a.subList(0, 3).forEach(x -> args.add(tree(x)));
            named.put("by", list(a.subList(3, a.size())));
        } else {
            a.forEach(x -> args.add(tree(x)));
        }
        return new Expr.Call(name, null, args, named);
    }
}
