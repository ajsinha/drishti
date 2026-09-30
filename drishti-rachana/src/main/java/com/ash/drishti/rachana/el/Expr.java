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
            return t instanceof DataNode n ? n.get(name) : DataNode.missing();
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
            if (!(t instanceof DataNode n)) {
                return DataNode.missing();
            }
            Object i = Values.simplify(index.eval(c));
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
            return !Values.truthy(e.eval(c));
        }

        public void paths(Consumer<String> sink) {
            e.paths(sink);
        }
    }

    record Neg(Expr e) implements Expr {
        public Object eval(EvalContext c) {
            return Values.normalise(-Values.number(e.eval(c)));
        }

        public void paths(Consumer<String> sink) {
            e.paths(sink);
        }
    }

    record And(Expr l, Expr r) implements Expr {
        public Object eval(EvalContext c) {
            return Values.truthy(l.eval(c)) && Values.truthy(r.eval(c));
        }

        public void paths(Consumer<String> sink) {
            l.paths(sink);
            r.paths(sink);
        }
    }

    record Or(Expr l, Expr r) implements Expr {
        public Object eval(EvalContext c) {
            return Values.truthy(l.eval(c)) || Values.truthy(r.eval(c));
        }

        public void paths(Consumer<String> sink) {
            l.paths(sink);
            r.paths(sink);
        }
    }

    record Ternary(Expr cond, Expr then, Expr otherwise) implements Expr {
        public Object eval(EvalContext c) {
            return Values.truthy(cond.eval(c)) ? then.eval(c) : otherwise.eval(c);
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
            switch (op) {
                case "==":
                    return Values.equal(a, b);
                case "!=":
                    return !Values.equal(a, b);
                case "+":
                    if (!(Values.isNumber(a) && Values.isNumber(b))) {
                        return Values.text(a) + Values.text(b);
                    }
                    return Values.normalise(Values.number(a) + Values.number(b));
                default:
                    break;
            }
            double x = Values.number(a);
            double y = Values.number(b);
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

    record Call(String name, ElFunction fn, List<Expr> args) implements Expr {
        public Object eval(EvalContext c) {
            List<Object> values = new ArrayList<>(args.size());
            for (Expr a : args) {
                values.add(a.eval(c));
            }
            return fn.apply(values, c);
        }

        public void paths(Consumer<String> sink) {
            args.forEach(a -> a.paths(sink));
        }
    }
}
