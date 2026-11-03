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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The tree of a Rachana expression as the table layer reads it: enough to find names, paths and functions, to substitute and to rewrite
 * (the migration's column substitution and the null rule), and to print the text back. It never evaluates: Rachana evaluates and the
 * pivot engine aggregates (ONE_GRAMMAR.md). The grammar read is Rachana's with what checked mode adds (BI_FOUNDATION_2911E.md 2.1): list
 * literals, named arguments, {@code in} and {@code not in}, and {@code ^}.
 */
public sealed interface Node {

    /** A literal: a number (as written), a text, {@code true}, {@code false} or {@code null}. */
    record Lit(Object value, String text) implements Node {
        public static final Lit NULL = new Lit(null, "null");
        public static final Lit TRUE = new Lit(Boolean.TRUE, "true");
        public static final Lit FALSE = new Lit(Boolean.FALSE, "false");

        public static Lit text(String s) {
            return new Lit(s, null);
        }

        public boolean isNull() {
            return value == null && "null".equals(text);
        }
    }

    /**
     * A path from a root: {@code $} (the entity or source row), {@code @} (the grain's element), {@code ^} (its parent), {@code ^^} (the
     * parent's parent); then fields and indexes, as written after the root ({@code .LEGS[0].NOTIONAL}, {@code [*]}).
     */
    record Path(String root, String rest) implements Node {
        public String text() {
            return root + rest;
        }
    }

    /** A name, dotted: a column of a model ({@code trades.MTM}), {@code user.desks}, {@code param.x}, a bare column, {@code ALL}. */
    record Name(String name) implements Node {
        /** The first part ({@code trades} of {@code trades.MTM}). */
        public String head() {
            int dot = name.indexOf('.');
            return dot < 0 ? name : name.substring(0, dot);
        }
    }

    /** A list literal {@code [a, b]}. */
    record ListOf(List<Node> items) implements Node {
        public ListOf {
            items = List.copyOf(items);
        }
    }

    /** An argument of a call: positional ({@code name} null) or named ({@code where = cond}). */
    record Arg(String name, Node value) {}

    /** A function call. */
    record Call(String function, List<Arg> args) implements Node {
        public Call {
            args = List.copyOf(args);
        }

        /** The named argument's value, or null. */
        public Node named(String n) {
            return args.stream().filter(a -> n.equals(a.name())).map(Arg::value).findFirst().orElse(null);
        }

        /** The positional arguments in order. */
        public List<Node> positional() {
            return args.stream().filter(a -> a.name() == null).map(Arg::value).toList();
        }
    }

    /** {@code !x}, {@code -x}. */
    record Unary(String op, Node operand) implements Node {}

    /** A binary operation; {@code op} is one of {@code || && == != < <= > >= in not-in + - * / %}. */
    record Binary(String op, Node left, Node right) implements Node {
        public boolean comparison() {
            return Reader.COMPARISONS.contains(op);
        }
    }

    /** {@code c ? a : b}. */
    record Cond(Node test, Node then, Node otherwise) implements Node {}

    /** Calls {@code sink} with this node and every node under it, parents first. */
    default void walk(Consumer<Node> sink) {
        sink.accept(this);
        for (Node c : children()) {
            c.walk(sink);
        }
    }

    /** The direct children. */
    default List<Node> children() {
        return switch (this) {
            case Lit l -> List.of();
            case Path p -> List.of();
            case Name n -> List.of();
            case ListOf l -> l.items();
            case Call c -> c.args().stream().map(Arg::value).toList();
            case Unary u -> List.of(u.operand());
            case Binary b -> List.of(b.left(), b.right());
            case Cond c -> List.of(c.test(), c.then(), c.otherwise());
        };
    }

    /** The tree with {@code f} applied bottom-up to every node (children first, then the node rebuilt from them). */
    default Node map(UnaryOperator<Node> f) {
        Node rebuilt = switch (this) {
            case Lit l -> l;
            case Path p -> p;
            case Name n -> n;
            case ListOf l -> new ListOf(l.items().stream().map(x -> x.map(f)).toList());
            case Call c -> {
                List<Arg> args = new ArrayList<>();
                for (Arg a : c.args()) {
                    args.add(new Arg(a.name(), a.value().map(f)));
                }
                yield new Call(c.function(), args);
            }
            case Unary u -> new Unary(u.op(), u.operand().map(f));
            case Binary b -> new Binary(b.op(), b.left().map(f), b.right().map(f));
            case Cond c -> new Cond(c.test().map(f), c.then().map(f), c.otherwise().map(f));
        };
        return f.apply(rebuilt);
    }

    /** The canonical text ({@link Printer}). */
    default String print() {
        return Printer.print(this);
    }
}
