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

import com.ash.drishti.tables.expr.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The layer's own rewrites of a Rachana expression during the migration, on the tree (never on text), after the DEL translator:
 *
 * <ol>
 *   <li><b>Columns to paths</b> (BI_FOUNDATION_2911E.md section 3.2): in a table's row and rule scope, a bare column name becomes that
 *       column's path ({@code MTM / 1000000} is {@code $.MTM / 1000000}), so a column's meaning never depends on the order columns are read.</li>
 *   <li><b>Date parts</b> (section 2.4): in a model's expression, {@code trades.TRADE_DATE.month} is {@code month(trades.TRADE_DATE)}.</li>
 *   <li><b>The null rule</b> (Q1, decided: Rachana's rule everywhere): DEL, like SQL, makes a comparison with a missing value false; Rachana
 *       makes {@code null != 'X'} true and {@code null == null} true. Each comparison whose meaning would change is rewritten so every BI
 *       figure stays the same: {@code a != b} becomes {@code a != b && a != null} where {@code a} may be missing (and {@code b != null} where
 *       {@code b} may be), {@code a == b} gains a guard only when both sides may be missing, {@code a not in L} gains one; a negation is pushed
 *       inward first ({@code !(a == b)} is {@code a != b}), and a negated value that may be missing gains one. Comparisons with the
 *       {@code null} literal are null tests and mean the same in both. The rewrite is idempotent: a guard already among the conjuncts is not
 *       added again.</li>
 * </ol>
 */
public final class Rewrites {

    private Rewrites() {}

    /** Bare column names replaced by their paths ({@code columns}: name to path). */
    public static Node columnsToPaths(Node tree, Map<String, String> columns, List<String> notes) {
        return tree.map(n -> {
            if (n instanceof Node.Name x && columns.containsKey(x.name())) {
                String path = columns.get(x.name());
                notes.add(x.name() + " is " + path + " (an expression reads the source)");
                Node p = com.ash.drishti.tables.expr.Reader.read(path);
                return p;
            }
            return n;
        });
    }

    /** {@code alias.COLUMN.month} as {@code month(alias.COLUMN)} where {@code alias.COLUMN} is a column ({@code columns}). */
    public static Node dateParts(Node tree, Set<String> columns, List<String> notes) {
        return tree.map(n -> {
            if (n instanceof Node.Name x) {
                for (String part : List.of("year", "quarter", "month", "week", "day")) {
                    String base = x.name().endsWith("." + part) ? x.name().substring(0, x.name().length() - part.length() - 1) : null;
                    if (base != null && columns.contains(base)) {
                        notes.add(x.name() + " is " + part + "(" + base + ")");
                        return new Node.Call(part, List.of(new Node.Arg(null, new Node.Name(base))));
                    }
                }
            }
            return n;
        });
    }

    /**
     * The null rule (Q1).
     *
     * @param tree the expression
     * @param mayBeMissing whether an operand may be missing (a literal never is; a key column never is)
     * @param notes the report's words, one per guard added
     */
    public static Node nullRule(Node tree, Predicate<Node> mayBeMissing, List<String> notes) {
        return new NullRule(mayBeMissing, notes).rewrite(tree);
    }

    /** The null rule's walk. */
    private record NullRule(Predicate<Node> mayBeMissing, List<String> notes) {

        Node rewrite(Node n) {
            return switch (n) {
                case Node.Binary b when b.op().equals("&&") -> conjunction(b);
                case Node.Binary b when b.op().equals("||") -> new Node.Binary("||", rewrite(b.left()), rewrite(b.right()));
                case Node.Binary b when b.comparison() -> guarded(b);
                case Node.Unary u when u.op().equals("!") -> negation(u.operand());
                case Node.Cond c -> new Node.Cond(rewrite(c.test()), rewrite(c.then()), rewrite(c.otherwise()));
                case Node.Call c -> {
                    List<Node.Arg> args = new ArrayList<>();
                    c.args().forEach(a -> args.add(new Node.Arg(a.name(), rewrite(a.value()))));
                    yield new Node.Call(c.function(), args);
                }
                case Node.ListOf l -> new Node.ListOf(l.items().stream().map(this::rewrite).toList());
                case Node.Binary b -> new Node.Binary(b.op(), rewrite(b.left()), rewrite(b.right()));
                case Node.Unary u -> new Node.Unary(u.op(), rewrite(u.operand()));
                default -> n;
            };
        }

        /** {@code x && y && ...}: each conjunct rewritten, flattened, and a conjunct written twice kept once. */
        private Node conjunction(Node.Binary b) {
            List<Node> parts = new ArrayList<>();
            flatten(b, parts);
            Map<String, Node> kept = new LinkedHashMap<>();
            for (Node p : parts) {
                List<Node> flat = new ArrayList<>();
                flatten(rewrite(p), flat);
                flat.forEach(f -> kept.putIfAbsent(f.print(), f));
            }
            return and(new ArrayList<>(kept.values()));
        }

        private static void flatten(Node n, List<Node> into) {
            if (n instanceof Node.Binary b && b.op().equals("&&")) {
                flatten(b.left(), into);
                flatten(b.right(), into);
            } else {
                into.add(n);
            }
        }

        private static Node and(List<Node> parts) {
            Node out = parts.get(0);
            for (int i = 1; i < parts.size(); i++) {
                out = new Node.Binary("&&", out, parts.get(i));
            }
            return out;
        }

        private Node guarded(Node.Binary b) {
            Node left = rewrite(b.left());
            Node right = rewrite(b.right());
            Node cmp = new Node.Binary(b.op(), left, right);
            if (isNull(left) || isNull(right)) {
                return cmp;                                                 // a null test: the same in both languages
            }
            List<Node> guards = new ArrayList<>();
            switch (b.op()) {
                case "!=" -> {
                    if (mayBeMissing.test(left)) {
                        guards.add(notNull(left));
                    }
                    if (mayBeMissing.test(right)) {
                        guards.add(notNull(right));
                    }
                }
                case "==" -> {
                    if (mayBeMissing.test(left) && mayBeMissing.test(right)) {
                        guards.add(notNull(left));
                    }
                }
                case "not-in" -> {
                    if (mayBeMissing.test(left)) {
                        guards.add(notNull(left));
                    }
                }
                default -> { }                                              // in, <, <=, >, >=: false on a missing value in both
            }
            if (guards.isEmpty()) {
                return cmp;
            }
            guards.forEach(g -> notes.add("kept DEL's meaning for a missing value: " + cmp.print() + " && " + g.print() + " (Q1)"));
            List<Node> parts = new ArrayList<>();
            parts.add(cmp);
            parts.addAll(guards);
            return and(parts);
        }

        /** {@code !x}, pushed inward so each comparison keeps DEL's meaning; a negated value that may be missing is guarded. */
        private Node negation(Node x) {
            if (x instanceof Node.Binary b) {
                String flipped = switch (b.op()) {
                    case "==" -> "!=";
                    case "!=" -> "==";
                    case "in" -> "not-in";
                    case "not-in" -> "in";
                    case "<" -> ">=";
                    case "<=" -> ">";
                    case ">" -> "<=";
                    case ">=" -> "<";
                    default -> null;
                };
                if (flipped != null) {
                    return rewrite(new Node.Binary(flipped, b.left(), b.right()));     // NOT(a = b) is a <> b in SQL's logic too
                }
                if (b.op().equals("&&")) {
                    return new Node.Binary("||", negation(b.left()), negation(b.right()));
                }
                if (b.op().equals("||")) {
                    return conjunction(new Node.Binary("&&", new Node.Unary("!", b.left()), new Node.Unary("!", b.right())));
                }
            }
            if (x instanceof Node.Unary u && u.op().equals("!")) {
                return rewrite(u.operand());
            }
            Node inner = rewrite(x);
            if (mayBeMissing.test(inner) && !(inner instanceof Node.Call)) {
                notes.add("kept DEL's meaning for a missing value: !" + inner.print() + " && " + notNull(inner).print() + " (Q1)");
                return and(List.of(new Node.Unary("!", inner), notNull(inner)));
            }
            return new Node.Unary("!", inner);
        }

        private static Node notNull(Node n) {
            return new Node.Binary("!=", n, Node.Lit.NULL);
        }

        private static boolean isNull(Node n) {
            return n instanceof Node.Lit l && l.isNull();
        }
    }

    /**
     * The usual {@code mayBeMissing}: a literal, a list, an arithmetic of literals, {@code count(...)} and the names in {@code neverMissing}
     * (key columns, built-ins) are never missing; anything else may be.
     */
    public static Predicate<Node> missingUnless(Set<String> neverMissing) {
        return new Predicate<>() {
            @Override
            public boolean test(Node n) {
                return switch (n) {
                    case Node.Lit l -> l.isNull();
                    case Node.ListOf l -> false;
                    case Node.Name x -> !neverMissing.contains(x.name()) && !x.head().equals("user") && !x.head().equals("param");
                    case Node.Path p -> !neverMissing.contains(p.text());
                    case Node.Call c -> !c.function().equals("count") && !c.function().equals("coalesce");
                    case Node.Binary b -> test(b.left()) || test(b.right());
                    case Node.Unary u -> test(u.operand());
                    case Node.Cond c -> test(c.then()) || test(c.otherwise());
                };
            }
        };
    }
}
