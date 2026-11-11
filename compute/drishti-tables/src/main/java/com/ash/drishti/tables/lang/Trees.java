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
package com.ash.drishti.tables.lang;

import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Printer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The table layer's view of Rachana's tree (the one parser, {@link CheckedMode#parse}; the one printer, {@link Printer}): walking it,
 * rebuilding it bottom-up for the migration's rewrites, and reading names and paths from it. Nothing here parses or prints text itself.
 */
public final class Trees {

    private Trees() {}

    /** Rachana's tree of {@code text} ({@code DRS-2101} as an {@link ElException} when it does not parse). */
    public static Expr parse(String text) {
        return CheckedMode.parse(text).expr();
    }

    /** True when {@code text} parses. */
    public static boolean parses(String text) {
        try {
            parse(text);
            return true;
        } catch (ElException e) {
            return false;
        }
    }

    /** The one spelling of a tree, read back to a tree the evaluator can run (a rebuilt node may lack what the parser binds). */
    public static Expr normalise(Expr e) {
        return parse(Printer.print(e));
    }

    /** The direct children of a node, in order. */
    public static List<Expr> children(Expr e) {
        return switch (e) {
            case Expr.Field f -> List.of(f.target());
            case Expr.At a -> List.of(a.target(), a.index());
            case Expr.Each x -> List.of(x.target());
            case Expr.Pluck p -> List.of(p.list());
            case Expr.Filter f -> List.of(f.target(), f.predicate());
            case Expr.ListLit l -> l.items();
            case Expr.Not n -> List.of(n.e());
            case Expr.Neg n -> List.of(n.e());
            case Expr.And a -> List.of(a.l(), a.r());
            case Expr.Or o -> List.of(o.l(), o.r());
            case Expr.Ternary t -> List.of(t.cond(), t.then(), t.otherwise());
            case Expr.In i -> List.of(i.value(), i.list());
            case Expr.Binary b -> List.of(b.l(), b.r());
            case Expr.Call c -> {
                List<Expr> out = new ArrayList<>(c.args());
                out.addAll(c.named().values());
                yield out;
            }
            case Expr.Form f -> f.args();
            case Expr.Let l -> {
                List<Expr> out = new ArrayList<>(l.values());
                out.add(l.body());
                yield out;
            }
            default -> List.of();
        };
    }

    /** {@code sink} for the node and every node under it, parents first. */
    public static void walk(Expr e, Consumer<Expr> sink) {
        sink.accept(e);
        for (Expr c : children(e)) {
            walk(c, sink);
        }
    }

    /** The tree with {@code f} applied bottom-up: children first, then the node rebuilt from them. */
    public static Expr map(Expr e, UnaryOperator<Expr> f) {
        Expr rebuilt = switch (e) {
            case Expr.Field x -> new Expr.Field(map(x.target(), f), x.name(), x.rootPath());
            case Expr.At a -> new Expr.At(map(a.target(), f), map(a.index(), f), a.rootPath());
            case Expr.Each x -> new Expr.Each(map(x.target(), f));
            case Expr.Pluck p -> new Expr.Pluck(map(p.list(), f), p.name());
            case Expr.Filter x -> new Expr.Filter(map(x.target(), f), map(x.predicate(), f));
            case Expr.ListLit l -> new Expr.ListLit(l.items().stream().map(i -> map(i, f)).toList());
            case Expr.Not n -> new Expr.Not(map(n.e(), f));
            case Expr.Neg n -> new Expr.Neg(map(n.e(), f));
            case Expr.And a -> new Expr.And(map(a.l(), f), map(a.r(), f));
            case Expr.Or o -> new Expr.Or(map(o.l(), f), map(o.r(), f));
            case Expr.Ternary t -> new Expr.Ternary(map(t.cond(), f), map(t.then(), f), map(t.otherwise(), f));
            case Expr.In i -> new Expr.In(map(i.value(), f), map(i.list(), f), i.negated());
            case Expr.Binary b -> new Expr.Binary(b.op(), map(b.l(), f), map(b.r(), f));
            case Expr.Call c -> {
                Map<String, Expr> named = new LinkedHashMap<>();
                c.named().forEach((k, v) -> named.put(k, map(v, f)));
                yield new Expr.Call(c.name(), c.fn(), c.args().stream().map(a -> map(a, f)).toList(), named);
            }
            case Expr.Form x -> x.withArgs(x.args().stream().map(a -> map(a, f)).toList());
            case Expr.Let l -> new Expr.Let(l.names(), l.values().stream().map(v -> map(v, f)).toList(), map(l.body(), f));
            default -> e;
        };
        return f.apply(rebuilt);
    }

    /**
     * The name a chain of fields spells, or null: {@code trades.MTM} (from the row or document, as a model's names are written),
     * {@code $.MTM}, {@code @.INDEX.NAME}, {@code ^.LEG_ID}, {@code $.LEGS[0].CURRENCY}, {@code $.LEGS[*]}.
     */
    public static String name(Expr e) {
        return switch (e) {
            case Expr.RowOrRoot r -> "";
            case Expr.Root r -> "$";
            case Expr.Row r -> "@";
            case Expr.Parent p -> "^".repeat(p.up());
            case Expr.Field f -> {
                String t = name(f.target());
                yield t == null ? null : t.isEmpty() ? f.name() : t + "." + f.name();
            }
            case Expr.At a -> {
                String t = name(a.target());
                yield t == null || !(a.index() instanceof Expr.Lit l) ? null : t + "[" + Printer.print(l) + "]";
            }
            case Expr.Pluck x -> {
                String t = name(x.list());
                yield t == null ? null : t + "." + x.name();
            }
            case Expr.Each x -> {
                String t = name(x.target());
                yield t == null ? null : t + "[*]";
            }
            default -> null;
        };
    }

    /** The root a path starts at ({@code $}, {@code @}, {@code ^}, {@code ^^}), or {@code ""} for a bare name, or null when not a path. */
    public static String root(Expr e) {
        String n = name(e);
        if (n == null) {
            return null;
        }
        for (String r : List.of("^^^", "^^", "^", "$", "@")) {
            if (n.equals(r) || n.startsWith(r + ".") || n.startsWith(r + "[")) {
                return r;
            }
        }
        return "";
    }

    /** True for {@code null} written in the expression. */
    public static boolean isNull(Expr e) {
        return e instanceof Expr.Lit l && l.value() == null;
    }

    /** The function a call names ({@code sum}), or null. */
    public static String function(Expr e) {
        return e instanceof Expr.Call c ? c.name() : e instanceof Expr.Form f ? f.name() : null;
    }
}
