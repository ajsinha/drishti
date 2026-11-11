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
package com.ash.drishti.tables.migrate;

import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Printer;
import com.ash.drishti.tables.lang.Trees;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The layer's own rewrites of a Rachana expression during the migration, on Rachana's tree (never on text), after the DEL translator:
 *
 * <ol>
 *   <li><b>Columns to paths</b> (HOW_IT_FITS.md §A28 section 3.2): in a table's row and rule scope, a bare column name becomes that
 *       column's path ({@code MTM / 1000000} is {@code $.MTM / 1000000}), so a column's meaning never depends on the order columns are read.</li>
 *   <li><b>Date parts</b> (section 2.4): in a model's expression, {@code trades.TRADE_DATE.month} is {@code month(trades.TRADE_DATE)}.</li>
 *   <li><b>The null rule</b> (Q1, decided: Rachana's rule everywhere): DEL, like SQL, makes a comparison with a missing value false; Rachana
 *       makes {@code null != 'X'} true and {@code null == null} true. Each comparison whose meaning would change is rewritten so every BI
 *       figure stays the same: {@code a != b} becomes {@code a != b && a != null} where {@code a} may be missing (and {@code b != null} where
 *       {@code b} may be), {@code a == b} gains a guard only when both sides may be missing, {@code a not in L} gains one; a negation is pushed
 *       inward first ({@code !(a == b)} is {@code a != b}), and a negated value that may be missing gains one. Comparisons with the
 *       {@code null} literal are null tests and mean the same in both. Idempotent: a guard already among the conjuncts is not added again.</li>
 * </ol>
 * Each returns a tree the evaluator can run ({@link Trees#normalise}).
 */
public final class Rewrites {

    private Rewrites() {}

    /** Bare column names replaced by their paths ({@code columns}: name to path). */
    public static Expr columnsToPaths(Expr tree, Map<String, String> columns, List<String> notes) {
        return Trees.normalise(Trees.map(tree, n -> {
            if (n instanceof Expr.Field f && f.target() instanceof Expr.RowOrRoot && columns.containsKey(f.name())) {
                String path = columns.get(f.name());
                notes.add(f.name() + " is " + path + " (an expression reads the source)");
                return Trees.parse(path);
            }
            return n;
        }));
    }

    /** {@code alias.COLUMN.month} as {@code month(alias.COLUMN)} where {@code alias.COLUMN} is a column ({@code columns}). */
    public static Expr dateParts(Expr tree, Set<String> columns, List<String> notes) {
        return Trees.normalise(Trees.map(tree, n -> {
            if (n instanceof Expr.Field f && List.of("year", "quarter", "month", "week", "day").contains(f.name())) {
                String base = Trees.name(f.target());
                if (base != null && columns.contains(base)) {
                    notes.add(base + "." + f.name() + " is " + f.name() + "(" + base + ")");
                    return new Expr.Call(f.name(), null, List.of(f.target()));
                }
            }
            return n;
        }));
    }

    /**
     * The null rule (Q1).
     *
     * @param tree the expression
     * @param mayBeMissing whether an operand may be missing (a literal never is; a key column never is)
     * @param notes the report's words, one per guard added
     */
    public static Expr nullRule(Expr tree, Predicate<Expr> mayBeMissing, List<String> notes) {
        return Trees.normalise(new NullRule(mayBeMissing, notes).rewrite(tree));
    }

    /** The null rule's walk. */
    private record NullRule(Predicate<Expr> mayBeMissing, List<String> notes) {

        Expr rewrite(Expr n) {
            return switch (n) {
                case Expr.And a -> conjunction(a);
                case Expr.Or o -> new Expr.Or(rewrite(o.l()), rewrite(o.r()));
                case Expr.Binary b when comparison(b.op()) -> guarded(b.op(), b.l(), b.r());
                case Expr.In i -> guardedIn(i);
                case Expr.Not x -> negation(x.e());
                case Expr.Ternary t -> new Expr.Ternary(rewrite(t.cond()), rewrite(t.then()), rewrite(t.otherwise()));
                case Expr.Call c -> {
                    Map<String, Expr> named = new LinkedHashMap<>();
                    c.named().forEach((k, v) -> named.put(k, rewrite(v)));
                    yield new Expr.Call(c.name(), c.fn(), c.args().stream().map(this::rewrite).toList(), named);
                }
                case Expr.ListLit l -> new Expr.ListLit(l.items().stream().map(this::rewrite).toList());
                case Expr.Binary b -> new Expr.Binary(b.op(), rewrite(b.l()), rewrite(b.r()));
                case Expr.Neg x -> new Expr.Neg(rewrite(x.e()));
                default -> n;
            };
        }

        private static boolean comparison(String op) {
            return List.of("==", "!=", "<", "<=", ">", ">=").contains(op);
        }

        /** {@code x && y && ...}: each conjunct rewritten, flattened, and a conjunct written twice kept once. */
        private Expr conjunction(Expr.And a) {
            List<Expr> parts = new ArrayList<>();
            flatten(a, parts);
            Map<String, Expr> kept = new LinkedHashMap<>();
            for (Expr p : parts) {
                List<Expr> flat = new ArrayList<>();
                flatten(rewrite(p), flat);
                flat.forEach(f -> kept.putIfAbsent(Printer.print(f), f));
            }
            return and(new ArrayList<>(kept.values()));
        }

        private static void flatten(Expr n, List<Expr> into) {
            if (n instanceof Expr.And a) {
                flatten(a.l(), into);
                flatten(a.r(), into);
            } else {
                into.add(n);
            }
        }

        private static Expr and(List<Expr> parts) {
            Expr out = parts.get(0);
            for (int i = 1; i < parts.size(); i++) {
                out = new Expr.And(out, parts.get(i));
            }
            return out;
        }

        private Expr guarded(String op, Expr l, Expr r) {
            Expr left = rewrite(l);
            Expr right = rewrite(r);
            Expr cmp = new Expr.Binary(op, left, right);
            if (Trees.isNull(left) || Trees.isNull(right)) {
                return cmp;                                                 // a null test: the same in both languages
            }
            List<Expr> guards = new ArrayList<>();
            if (op.equals("!=")) {
                if (mayBeMissing.test(left)) {
                    guards.add(notNull(left));
                }
                if (mayBeMissing.test(right)) {
                    guards.add(notNull(right));
                }
            } else if (op.equals("==") && mayBeMissing.test(left) && mayBeMissing.test(right)) {
                guards.add(notNull(left));
            }                                                               // <, <=, >, >=: false on a missing value in both
            return withGuards(cmp, guards);
        }

        private Expr guardedIn(Expr.In i) {
            Expr v = rewrite(i.value());
            Expr in = new Expr.In(v, rewrite(i.list()), i.negated());
            return i.negated() && !Trees.isNull(v) && mayBeMissing.test(v) ? withGuards(in, List.of(notNull(v))) : in;
        }

        private Expr withGuards(Expr cmp, List<Expr> guards) {
            if (guards.isEmpty()) {
                return cmp;
            }
            guards.forEach(g -> notes.add("kept DEL's meaning for a missing value: " + Printer.print(cmp) + " && " + Printer.print(g) + " (Q1)"));
            List<Expr> parts = new ArrayList<>();
            parts.add(cmp);
            parts.addAll(guards);
            return and(parts);
        }

        /** {@code !x}, pushed inward so each comparison keeps DEL's meaning; a negated value that may be missing is guarded. */
        private Expr negation(Expr x) {
            if (x instanceof Expr.Binary b && comparison(b.op())) {
                String flipped = switch (b.op()) {
                    case "==" -> "!=";
                    case "!=" -> "==";
                    case "<" -> ">=";
                    case "<=" -> ">";
                    case ">" -> "<=";
                    default -> "<";
                };
                return rewrite(new Expr.Binary(flipped, b.l(), b.r()));     // NOT(a = b) is a <> b in SQL's logic too
            }
            if (x instanceof Expr.In i) {
                return rewrite(new Expr.In(i.value(), i.list(), !i.negated()));
            }
            if (x instanceof Expr.And a) {
                return new Expr.Or(negation(a.l()), negation(a.r()));
            }
            if (x instanceof Expr.Or o) {
                return conjunction(new Expr.And(new Expr.Not(o.l()), new Expr.Not(o.r())));
            }
            if (x instanceof Expr.Not n) {
                return rewrite(n.e());
            }
            Expr inner = rewrite(x);
            if (mayBeMissing.test(inner) && !(inner instanceof Expr.Call)) {
                notes.add("kept DEL's meaning for a missing value: !" + Printer.print(inner) + " && " + Printer.print(notNull(inner)) + " (Q1)");
                return and(List.of(new Expr.Not(inner), notNull(inner)));
            }
            return new Expr.Not(inner);
        }

        private static Expr notNull(Expr n) {
            return new Expr.Binary("!=", n, new Expr.Lit(null));
        }
    }

    /**
     * The usual {@code mayBeMissing}: a literal, a list, {@code count(...)}, {@code coalesce(...)} and the names in {@code neverMissing}
     * (key columns, built-ins, as {@code alias.COLUMN} or as a path) are never missing; {@code user.x} and {@code param.x} neither;
     * anything else may be.
     */
    public static Predicate<Expr> missingUnless(Set<String> neverMissing) {
        return new Predicate<>() {
            @Override
            public boolean test(Expr n) {
                return switch (n) {
                    case Expr.Lit l -> l.value() == null;
                    case Expr.ListLit l -> false;
                    case Expr.Call c -> !c.name().equals("count") && !c.name().equals("coalesce");
                    case Expr.Binary b -> test(b.l()) || test(b.r());
                    case Expr.Neg x -> test(x.e());
                    case Expr.Ternary t -> test(t.then()) || test(t.otherwise());
                    default -> {
                        String name = Trees.name(n);
                        yield name == null || !neverMissing.contains(name) && !name.startsWith("user.") && !name.startsWith("param.");
                    }
                };
            }
        };
    }
}
