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

import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Functions;
import com.ash.drishti.rachana.el.check.Signatures;
import com.ash.drishti.server.bi.expr.DelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rachana's tree as BI's compiler tree ({@link Node}), the one the SQL emitter writes with bound parameters and the live tables evaluate
 * (BI_FOUNDATION_2911E.md section 2.6: one parser, Rachana's; BI's compiler keeps its plan). Named arguments become the compiler's
 * arguments ({@code sum(x, where = c)} is {@code sum(x, c)}; {@code rank(agg, desc = false, by = [f])} is {@code rank(agg, 'asc', f)}); the
 * filter-context, balance and time-travel functions keep theirs as pseudo-calls ({@link Node#keyword}); Rachana's names take the
 * compiler's ({@code len} is {@code length}, {@code starts_with} {@code startsWith}, {@code latest} {@code last}, {@code earliest}
 * {@code first}); {@code c ? a : b} is a conditional, {@code x == null} an {@code IS NULL} test, {@code measure('name')} a measure reference.
 * A number written with a point or an exponent stays a decimal ({@code 1.0}), as the compiler binds it. Pure.
 */
public final class NodeLowering {

    /** The code of an expression the compiler refuses (as checked mode's language problems). */
    public static final String CODE = "DRS-9127";

    /** Rachana's names the compiler spells otherwise. */
    static final Map<String, String> COMPILER = Map.of("len", "length", "starts_with", "startsWith", "ends_with", "endswith", "latest", "last",
            "earliest", "first");
    private static final Set<String> RANKS = Set.of("rank", "dense_rank", "percent_rank");

    private final CheckedMode.Parsed parsed;

    private NodeLowering(CheckedMode.Parsed parsed) {
        this.parsed = parsed;
    }

    /** The compiler's tree of a Rachana expression; a text that does not parse, or that the compiler cannot run, is a {@link DelException}. */
    public static Node lower(String text) {
        CheckedMode.Parsed p;
        try {
            p = CheckedMode.parse(text);
        } catch (ElException e) {
            int at = Math.max(0, e.position());
            throw new DelException(CODE, e.getMessage(), null, at, Math.min(text.length(), at + 1));
        }
        return new NodeLowering(p).node(p.expr());
    }

    /** The compiler's tree of a parsed Rachana tree (written {@code text}). */
    public static Node lower(CheckedMode.Parsed parsed) {
        return new NodeLowering(parsed).node(parsed.expr());
    }

    private DelException refused(Expr e, String why) {
        int[] s = parsed.span(e);
        return new DelException(CODE, why, null, s[0], s[1]);
    }

    Node node(Expr e) {
        return switch (e) {
            case Expr.Lit l -> literal(l);
            case Expr.Field f -> field(f);
            case Expr.Not n -> new Node.Un("!", node(n.e()));
            case Expr.Neg n -> new Node.Un("-", node(n.e()));
            case Expr.And a -> new Node.Bin("&&", node(a.l()), node(a.r()));
            case Expr.Or o -> new Node.Bin("||", node(o.l()), node(o.r()));
            case Expr.Ternary t -> new Node.Cond(node(t.cond()), node(t.then()), node(t.otherwise()));
            case Expr.Binary b -> new Node.Bin(b.op(), node(b.l()), node(b.r()));
            case Expr.In i -> new Node.In(node(i.value()), items(i.list()), i.negated());
            case Expr.Call c -> call(c);
            case Expr.Absent a -> new Node.Null();
            case Expr.ListLit l -> throw refused(e, "a list is only the right side of in, or a named argument's value (by = [...])");
            default -> throw refused(e, "'" + com.ash.drishti.rachana.el.Printer.print(e) + "' is not an expression BI runs: a table's column reads a path; a measure "
                    + "names alias.COLUMN inside an aggregate");
        };
    }

    private Node literal(Expr.Lit l) {
        Object v = l.value();
        if (v == null) {
            return new Node.Null();
        }
        if (v instanceof Boolean b) {
            return new Node.Bool(b);
        }
        if (v instanceof String s) {
            return new Node.Str(s);
        }
        if (v instanceof Number n) {
            int[] s = parsed.span(l);
            String written = s[1] <= parsed.text().length() && s[0] < s[1] ? parsed.text().substring(s[0], s[1]) : "";
            boolean decimal = written.contains(".") || written.contains("e") || written.contains("E");
            boolean integral = !decimal && (v instanceof Long || v instanceof Integer || n.doubleValue() == Math.rint(n.doubleValue()));
            return new Node.Num(n.doubleValue(), integral);
        }
        return new Node.Str(String.valueOf(v));
    }

    /** {@code trades.MTM}, {@code MTM} (a column of the table in context), {@code user.desks}, {@code param.x}, {@code ALL}. */
    private Node field(Expr.Field f) {
        List<String> parts = new ArrayList<>();
        Expr t = f;
        while (t instanceof Expr.Field x) {
            parts.add(0, x.name());
            t = x.target();
        }
        if (!(t instanceof Expr.RowOrRoot)) {
            throw refused(f, "'" + com.ash.drishti.rachana.el.Printer.print(f) + "' reads a path of the source: a path belongs in a table's column (path:), and an "
                    + "expression names that column");
        }
        return parts.size() == 2 && "user".equals(parts.get(0)) ? new Node.UserRef(parts.get(1)) : new Node.Ref(List.copyOf(parts));
    }

    private List<Node> items(Expr list) {
        if (list instanceof Expr.ListLit l) {
            return l.items().stream().map(this::node).toList();
        }
        return List.of(node(list));                                            // user.desks
    }

    private Node call(Expr.Call c) {
        String name = Functions.oneName(c.name());
        if ("measure".equals(name)) {
            if (c.args().size() != 1 || !(c.args().get(0) instanceof Expr.Lit l) || !(l.value() instanceof String s)) {
                throw refused(c, "measure('name') names one measure of the model");
            }
            return new Node.MeasureRef(s);
        }
        if ("asof".equals(name) || "today".equals(c.name())) {
            return new Node.Call("asof", List.of());
        }
        Signatures.Signature f = Signatures.get().function(name).orElseThrow(() -> refused(c, "unknown function '" + c.name() + "'"));
        String compiled = COMPILER.getOrDefault(name, name);
        List<Signatures.Param> params = f.params();
        if (Node.CONTEXT.contains(compiled) || Node.SCALARS_B.contains(compiled)) {
            return keywords(c, compiled, params);
        }
        List<Node> args = new ArrayList<>();
        if ("windows".equals(f.family())) {
            if (RANKS.contains(name)) {
                args.add(node(value(c, params, 0)));
                Expr desc = value(c, params, 1);
                List<Node> partition = fields(value(c, params, 2));
                if (desc != null || !partition.isEmpty()) {
                    boolean d = !(desc instanceof Expr.Lit l && l.value() instanceof Boolean b) || b;
                    args.add(new Node.Str(d ? "desc" : "asc"));
                }
                args.addAll(partition);
                return new Node.Call(compiled, args);
            }
            for (int i = 0; i < params.size(); i++) {                         // lag, lead, moving_*: agg, n, order, then by=
                Signatures.Param p = params.get(i);
                Expr v = value(c, params, i);
                if (p.keywordOnly()) {
                    args.addAll(fields(v));
                } else if (v != null) {
                    args.add(node(v));
                }
            }
            return new Node.Call(compiled, args);
        }
        for (int i = 0; i < params.size(); i++) {                              // positional and named arguments in parameter order
            Signatures.Param p = params.get(i);
            if (p.variadic()) {
                c.args().subList(Math.min(i, c.args().size()), c.args().size()).forEach(a -> args.add(node(a)));
                break;
            }
            Expr v = value(c, params, i);
            if (v == null) {
                if ("round".equals(name)) {
                    continue;                                                  // round(x): the compiler's one-argument round
                }
                break;                                                         // a trailing default: the compiler's shorter call
            }
            args.add(node(v));
        }
        return new Node.Call(compiled, args);
    }

    /** The argument given for parameter {@code i}: by place (Rachana binds its own functions' names to places), else by name; null when absent. */
    private static Expr value(Expr.Call c, List<Signatures.Param> params, int i) {
        if (i >= params.size()) {
            return null;
        }
        Expr v = i < c.args().size() ? c.args().get(i) : null;
        if (v == null || v instanceof Expr.Absent) {
            v = c.named().get(params.get(i).name());
        }
        return v instanceof Expr.Absent ? null : v;
    }

    /**
     * A filter-context, balance or time-travel function keeps its named arguments in the tree (docs/architecture/BI_DESIGN.md section 3.3.5): the
     * required ones in order, then one pseudo-call {@code =name} per named one, whose arguments are the value (a list's items; {@code ALL} the
     * reference {@code ALL}).
     */
    private Node keywords(Expr.Call c, String compiled, List<Signatures.Param> params) {
        List<Node> args = new ArrayList<>();
        List<Node> named = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            Signatures.Param p = params.get(i);
            Expr v = value(c, params, i);
            if (v == null) {
                continue;
            }
            if (p.keywordOnly()) {
                List<Node> items = v instanceof Expr.ListLit l ? l.items().stream().map(this::node).toList() : List.of(node(v));
                named.add(new Node.Call(Node.KEYWORD + p.name(), items));
            } else {
                args.add(node(v));
            }
        }
        args.addAll(named);
        return new Node.Call(compiled, args);
    }

    private List<Node> fields(Expr list) {
        if (list instanceof Expr.ListLit l) {
            return l.items().stream().map(this::node).toList();
        }
        return list == null ? List.of() : List.of(node(list));
    }
}
