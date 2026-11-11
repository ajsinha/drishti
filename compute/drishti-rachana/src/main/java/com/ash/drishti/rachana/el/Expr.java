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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A compiled Rachana-EL expression: an immutable tree of closures, safe to share across threads.
 * {@link #paths} reports the document paths the expression reads, so live updates can re-evaluate only
 * what a change touches.
 */
public interface Expr {

    Object eval(EvalContext c);

    /** Reports each document path ({@code $.legs[0].rate}) this expression reads. */
    default void paths(Consumer<String> sink) {}

    record Lit(Object value) implements Expr {
        public Object eval(EvalContext c) {
            return value;
        }
    }

    record Root() implements Expr {
        public Object eval(EvalContext c) {
            return c.root();
        }

        public void paths(Consumer<String> sink) {
            sink.accept("$");
        }
    }

    record Row() implements Expr {
        public Object eval(EvalContext c) {
            return c.row();
        }
    }

    /** A bare identifier: a field of the row inside a row, otherwise of the document. */
    record RowOrRoot() implements Expr {
        public Object eval(EvalContext c) {
            return c.row() != null ? c.row() : c.root();
        }
    }

    /** {@code target[*]}: the elements of a list (a field after it, {@code $.legs[*].id}, is that field of each). */
    record Each(Expr target) implements Expr {
        public Object eval(EvalContext c) {
            Object t = target.eval(c);
            return Values.masked(t) ? Values.mask() : t instanceof DataNode.Arr ? t : DataNode.missing();
        }

        public void paths(Consumer<String> sink) {
            target.paths(sink);
        }
    }

    /** {@code list[*].name}: the field of every element, as a list. */
    record Pluck(Expr list, String name) implements Expr {
        public Object eval(EvalContext c) {
            Object t = list.eval(c);
            if (Values.masked(t)) {
                return Values.mask();
            }
            if (!(t instanceof DataNode.Arr a)) {
                return DataNode.missing();
            }
            List<DataNode> out = new ArrayList<>(a.size());
            for (int i = 0; i < a.size(); i++) {
                out.add(a.get(i).get(name));
            }
            return new DataNode.Arr(out);
        }

        public void paths(Consumer<String> sink) {
            list.paths(sink);
        }
    }

    /** {@code ctl.<id>}: the current value of a control of the Sutra (null when the context carries none). */
    record Ctl(String name) implements Expr {
        public Object eval(EvalContext c) {
            return c == null ? null : c.ctl().get(name);
        }
    }

    /**
     * {@code @<panel>}: what a panel of the screen emitted, a mapping of event name to payload ({@code @trades.open.trade});
     * {@code @parent} is the parent panel's. Nothing (missing) until the event fires.
     */
    record Event(String panel) implements Expr {
        public Object eval(EvalContext c) {
            return c == null ? DataNode.missing() : c.event(panel);
        }
    }

    /** {@code $src}: the record (or the records) the panel's {@code source:} names; nothing outside a sourced panel. */
    record Src() implements Expr {
        public Object eval(EvalContext c) {
            return c == null || c.src() == null ? DataNode.missing() : c.src();
        }
    }

    record Index() implements Expr {
        public Object eval(EvalContext c) {
            return (long) c.index();
        }
    }

    /**
     * A field access.
     *
     * @param target the object
     * @param name the field
     * @param rootPath the full document path when the chain starts at {@code $}, else null
     */
    record Field(Expr target, String name, String rootPath) implements Expr {
        public Object eval(EvalContext c) {
            Object t = c == null ? null : target.eval(c);
            return t instanceof DataNode n ? n.get(name) : Values.masked(t) ? Values.mask() : DataNode.missing();
        }

        public void paths(Consumer<String> sink) {
            if (rootPath != null) {
                sink.accept(rootPath);
            } else {
                target.paths(sink);
            }
        }
    }

    record At(Expr target, Expr index, String rootPath) implements Expr {
        public Object eval(EvalContext c) {
            Object t = target.eval(c);
            if (Values.masked(t)) {
                return Values.mask();
            }
            if (!(t instanceof DataNode n)) {
                return DataNode.missing();
            }
            Object i = Values.simplify(index.eval(c));
            if (Values.masked(i)) {
                return Values.mask();
            }
            return i instanceof Number num ? n.get(num.intValue()) : n.get(Values.text(i));
        }

        public void paths(Consumer<String> sink) {
            if (rootPath != null) {
                sink.accept(rootPath);
            } else {
                target.paths(sink);
            }
            index.paths(sink);
        }
    }

    /** {@code target[?predicate]}: the elements for which the predicate, with {@code @} bound to each, is true. */
    record Filter(Expr target, Expr predicate) implements Expr {
        public Object eval(EvalContext c) {
            Object t = target.eval(c);
            if (Values.masked(t)) {
                return Values.mask();
            }
            if (!(t instanceof DataNode.Arr a)) {
                return DataNode.missing();
            }
            List<DataNode> kept = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                if (Values.truthy(predicate.eval(c.withRow(a.get(i), i)))) {
                    kept.add(a.get(i));
                }
            }
            return new DataNode.Arr(kept);
        }

        public void paths(Consumer<String> sink) {
            target.paths(sink);
        }
    }

    record Not(Expr e) implements Expr {
        public Object eval(EvalContext c) {
            Object v = e.eval(c);
            return Values.masked(v) ? Values.mask() : (Object) !Values.truthy(v);
        }

        public void paths(Consumer<String> sink) {
            e.paths(sink);
        }
    }

    record Neg(Expr e) implements Expr {
        public Object eval(EvalContext c) {
            Object v = e.eval(c);
            return Values.masked(v) ? Values.mask() : Values.isNull(v) ? null : Values.normalise(-Values.number(v));
        }

        public void paths(Consumer<String> sink) {
            e.paths(sink);
        }
    }

    record And(Expr l, Expr r) implements Expr {
        public Object eval(EvalContext c) {
            Object a = l.eval(c);
            if (Values.masked(a)) {
                return Values.mask();
            }
            if (!Values.truthy(a)) {
                return false;
            }
            Object b = r.eval(c);
            return Values.masked(b) ? Values.mask() : (Object) Values.truthy(b);
        }

        public void paths(Consumer<String> sink) {
            l.paths(sink);
            r.paths(sink);
        }
    }

    record Or(Expr l, Expr r) implements Expr {
        public Object eval(EvalContext c) {
            Object a = l.eval(c);
            if (Values.truthy(a)) {
                return true;
            }
            Object b = r.eval(c);
            if (Values.truthy(b)) {
                return true;
            }
            return Values.masked(a) || Values.masked(b) ? Values.mask() : (Object) false;   // unknown, never true
        }

        public void paths(Consumer<String> sink) {
            l.paths(sink);
            r.paths(sink);
        }
    }

    record Ternary(Expr cond, Expr then, Expr otherwise) implements Expr {
        public Object eval(EvalContext c) {
            Object v = cond.eval(c);
            if (Values.masked(v)) {
                return Values.mask();                                 // either branch would tell what the value is
            }
            return Values.truthy(v) ? then.eval(c) : otherwise.eval(c);
        }

        public void paths(Consumer<String> sink) {
            cond.paths(sink);
            then.paths(sink);
            otherwise.paths(sink);
        }
    }

    record Binary(String op, Expr l, Expr r) implements Expr {
        public Object eval(EvalContext c) {
            Object a = l.eval(c);
            Object b = r.eval(c);
            if (Values.masked(a) || Values.masked(b)) {
                return Values.mask();                                 // derived from a masked field: masked, and never true
            }
            switch (op) {
                case "==":
                    return Values.equal(a, b);
                case "!=":
                    return !Values.equal(a, b);
                case "+":
                    if (Values.isNull(a) && (Values.isNull(b) || Values.isNumber(b)) || Values.isNull(b) && Values.isNumber(a)) {
                        return null;                                  // arithmetic on nothing is nothing; '+' still joins a text with nothing
                    }
                    if (!(Values.isNumber(a) && Values.isNumber(b))) {
                        return Values.text(a) + Values.text(b);
                    }
                    return Values.normalise(Values.number(a) + Values.number(b));
                case "-", "*", "/", "%":
                    if (Values.isNull(a) || Values.isNull(b)) {
                        return null;                                  // null in, null out (docs/architecture/ARCHITECTURE.md §28 2.6: as SQL computes it)
                    }
                    if ("-".equals(op) && Values.simplify(a) instanceof String sa && Values.simplify(b) instanceof String sb) {
                        java.time.LocalDate x = Args.parseDate(sa);
                        java.time.LocalDate y = Args.parseDate(sb);
                        if (x != null && y != null) {
                            return java.time.temporal.ChronoUnit.DAYS.between(y, x);    // date - date: the days between (DEL's rule)
                        }
                    }
                    break;
                default:
                    break;
            }
            double x = Values.number(a);
            double y = Values.number(b);
            if ((Double.isNaN(x) || Double.isNaN(y)) && Values.simplify(a) instanceof String sa && Values.simplify(b) instanceof String sb
                    && java.util.Set.of("<", "<=", ">", ">=").contains(op)) {
                int order = sa.compareTo(sb);                     // two texts that are not numbers order as text: ISO dates order
                return switch (op) {
                    case "<" -> order < 0;
                    case "<=" -> order <= 0;
                    case ">" -> order > 0;
                    default -> order >= 0;
                };
            }
            return switch (op) {
                case "-" -> Values.normalise(x - y);
                case "*" -> Values.normalise(x * y);
                case "/" -> y == 0 ? null : Values.normalise(x / y);
                case "%" -> y == 0 ? null : Values.normalise(x % y);
                case "<" -> x < y;
                case "<=" -> x <= y;
                case ">" -> x > y;
                case ">=" -> x >= y;
                default -> throw new ElException(ErrorCode.EL_EVAL, "unknown operator " + op);
            };
        }

        public void paths(Consumer<String> sink) {
            l.paths(sink);
            r.paths(sink);
        }
    }

    /**
     * A call of an eager function. {@code named} holds the named arguments a function keeps by name ({@code where = ...},
     * {@code by = [...]}, {@code ignore = ALL}: the keyword-only parameters of its signature, read by the engine's compiler); a named
     * argument of a positional parameter is bound to its place by the parser. Empty for most calls.
     */
    record Call(String name, ElFunction fn, List<Expr> args, java.util.Map<String, Expr> named) implements Expr {
        public Call {
            named = named == null || named.isEmpty() ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(named));
        }

        public Call(String name, ElFunction fn, List<Expr> args) {
            this(name, fn, args, null);
        }

        /** The named argument {@code key}, or null when the call does not name it. */
        public Expr arg(String key) {
            return named.get(key);
        }

        public Object eval(EvalContext c) {
            if (!named.isEmpty()) {
                throw new ElException(ErrorCode.EL_EVAL, name + ": " + String.join(", ", named.keySet().stream().map(k -> k + " =").toList())
                        + " belongs to a measure of a model or a column of a table, computed by the pivot engine; a list has no such argument");
            }
            if (fn == null) {
                throw new ElException(ErrorCode.EL_EVAL, name + " is not a function of Rachana");
            }
            List<Object> values = new ArrayList<>(args.size());
            boolean masked = false;
            for (Expr a : args) {
                Object v = a.eval(c);
                masked |= Values.masked(v);
                values.add(v);
            }
            // a function of a masked value is masked (coalesce only passes it on when it is the first value it has)
            return masked && !Functions.passesMask(name) ? Values.mask() : fn.apply(values, c);
        }

        public void paths(Consumer<String> sink) {
            args.forEach(a -> a.paths(sink));
            named.values().forEach(a -> a.paths(sink));
        }
    }

    /** A call of a form ({@link ElForm}): its arguments handed over unevaluated ({@code map}, {@code iferror} …). */
    record Form(String name, ElForm fn, List<Expr> args) implements Expr {
        public Object eval(EvalContext c) {
            return fn.apply(args, c);
        }

        /** The same form over other arguments (a compiler rewriting a tree). */
        public Form withArgs(List<Expr> next) {
            return new Form(name, fn, List.copyOf(next));
        }

        public void paths(Consumer<String> sink) {
            args.forEach(a -> a.paths(sink));
        }
    }

    /** {@code let(n = expr, ..., result)}: each name bound in turn (a later value may read an earlier name), then the result. */
    record Let(List<String> names, List<Expr> values, Expr body) implements Expr {
        public Object eval(EvalContext c) {
            EvalContext inner = c;
            for (int i = 0; i < names.size(); i++) {
                inner = inner.withLocal(names.get(i), values.get(i).eval(inner));
            }
            return body.eval(inner);
        }

        public void paths(Consumer<String> sink) {
            values.forEach(v -> v.paths(sink));
            body.paths(sink);
        }
    }

    /** A name bound by {@code let}, or {@code @acc} inside {@code reduce}. */
    record Local(String name) implements Expr {
        public Object eval(EvalContext c) {
            return c.local(name);
        }
    }

    /**
     * {@code [a, b, c]}: a list written in the expression (the right side of {@code in}, a {@code by = [...]} or {@code ignore = [...]},
     * an argument of a list function); a masked item stays masked in its place.
     */
    record ListLit(List<Expr> items) implements Expr {
        public ListLit {
            items = List.copyOf(items);
        }

        public Object eval(EvalContext c) {
            List<Object> values = new ArrayList<>(items.size());
            for (Expr e : items) {
                values.add(e.eval(c));
            }
            return Args.list(values);
        }

        public void paths(Consumer<String> sink) {
            items.forEach(i -> i.paths(sink));
        }
    }

    /**
     * {@code x in list}, {@code x not in list}: strict membership (docs/architecture/ARCHITECTURE.md §28 Q3). An empty list, an empty value or nothing
     * at all holds nothing, so {@code x in []} is false and {@code x not in []} true (unlike {@code one_of}, whose empty choice holds
     * everything, for controls). Equality is the null rule's: {@code null} equals only {@code null}. A value that is not a list is a list
     * of one. A masked value or list gives the mask (a filter on it is never true).
     */
    record In(Expr value, Expr list, boolean negated) implements Expr {
        public Object eval(EvalContext c) {
            Object v = value.eval(c);
            Object l = list.eval(c);
            if (Values.masked(v) || Values.masked(l)) {
                return Values.mask();
            }
            return holds(v, Values.simplify(l)) != negated;
        }

        /** True when {@code list} holds {@code v}. */
        static boolean holds(Object v, Object list) {
            if (list instanceof DataNode.Arr n) {
                for (int i = 0; i < n.size(); i++) {
                    if (!Values.masked(n.get(i)) && Values.equal(v, n.get(i))) {
                        return true;
                    }
                }
                return false;
            }
            if (list == null || list instanceof String s && s.isEmpty() || list instanceof DataNode) {
                return false;
            }
            return Values.equal(v, list);
        }

        public void paths(Consumer<String> sink) {
            value.paths(sink);
            list.paths(sink);
        }
    }

    /**
     * {@code ^}: the parent of the current element ({@code ^^} its parent, and so on): in a filter over a leg's cash flows,
     * {@code ^.LEG_TYPE} is the leg's; for a row of a table, the document. Missing when there is no such parent (docs/architecture/ARCHITECTURE.md §28
     * Q8).
     *
     * @param up 1 for {@code ^}, 2 for {@code ^^}
     */
    record Parent(int up) implements Expr {
        public Object eval(EvalContext c) {
            return c == null ? DataNode.missing() : c.parent(up);
        }
    }

    /** An optional positional argument left out before a named one: evaluates to nothing, read by a function as its default. */
    record Absent() implements Expr {
        public Object eval(EvalContext c) {
            return null;
        }
    }
}
