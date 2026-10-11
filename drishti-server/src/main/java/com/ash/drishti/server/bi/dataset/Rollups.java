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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a rollup can hold. A rollup stores, per business date and group, the <em>components</em> of its measures: sums, counts, minimums and
 * maximums of expressions over stored columns of its table ({@code avg(x)} is stored as {@code sum(x)} and {@code count(x)}). A measure built
 * from those (a ratio of sums, a sum minus a sum) is then answered from the rollup by re-aggregating the stored components: a sum of sums, a
 * sum of counts, a minimum of minimums. Distinct counts, percentiles, aggregates with a condition, and anything on another table are not
 * additive over a coarser grouping, so a measure using them is answered from the columns layer instead.
 */
public final class Rollups {

    private Rollups() {}

    /** True when the measure can be answered from a rollup of {@code table}. */
    public static boolean eligible(Dataset ds, Dataset.Measure m, String table) {
        return m.expr() != null && components(ds, m, table) != null;
    }

    /**
     * The stored components of a measure, by canonical key ({@code sum:trades.notional}) in order, or null when the measure cannot be answered
     * from a rollup of {@code table}.
     */
    public static Map<String, Node.Call> components(Dataset ds, Dataset.Measure m, String table) {
        Map<String, Node.Call> out = new LinkedHashMap<>();
        try {
            return collect(ds, ExprParser.parse(m.expr()), table, out, 0) ? out : null;
        } catch (ExprParser.ExprException e) {
            return null;
        }
    }

    private static boolean collect(Dataset ds, Node n, String table, Map<String, Node.Call> out, int depth) {
        if (depth > 10) {
            return false;
        }
        switch (n) {
            case Node.Call c when Node.AGGREGATES.contains(c.function()) -> {
                switch (c.function()) {
                    case "ratio" -> {
                        return c.args().stream().allMatch(a -> collect(ds, a, table, out, depth + 1));
                    }
                    case "sum", "min", "max", "count" -> {
                        if (c.args().size() != 1 || !onTable(ds, c.args().get(0), table)) {
                            return false;
                        }
                        out.putIfAbsent(key(c), c);
                        return true;
                    }
                    case "avg" -> {
                        if (c.args().size() != 1 || !onTable(ds, c.args().get(0), table)) {
                            return false;
                        }
                        Node.Call s = new Node.Call("sum", c.args());
                        Node.Call k = new Node.Call("count", c.args());
                        out.putIfAbsent(key(s), s);
                        out.putIfAbsent(key(k), k);
                        return true;
                    }
                    default -> {
                        return false;
                    }
                }
            }
            case Node.MeasureRef r -> {
                Dataset.Measure other = ds.measures().get(r.name());
                return other != null && other.expr() != null && collect(ds, ExprParser.parse(other.expr()), table, out, depth + 1);
            }
            case Node.Call c -> {
                return c.args().stream().allMatch(a -> collect(ds, a, table, out, depth + 1));
            }
            case Node.Bin b -> {
                return collect(ds, b.left(), table, out, depth + 1) && collect(ds, b.right(), table, out, depth + 1);
            }
            case Node.Un u -> {
                return collect(ds, u.operand(), table, out, depth + 1);
            }
            case Node.Cond c -> {
                return collect(ds, c.test(), table, out, depth + 1) && collect(ds, c.yes(), table, out, depth + 1) && collect(ds, c.no(), table, out, depth + 1);
            }
            case Node.Num x -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Every name in the expression is a stored column of {@code table} (a bare table name, as in count(trades), counts rows). */
    private static boolean onTable(Dataset ds, Node arg, String table) {
        Resolver r = new Resolver(ds);
        List<Boolean> ok = new ArrayList<>();
        Node.walk(arg, x -> {
            if (x instanceof Node.Ref ref) {
                try {
                    Resolver.ColRef c = r.resolve(ref.parts(), table);
                    ok.add(c.table().equals(table) && (c.column() == null || r.column(table, c.column()).expr() == null));
                } catch (IllegalArgumentException e) {
                    ok.add(false);
                }
            } else if (x instanceof Node.MeasureRef || x instanceof Node.UserRef || x instanceof Node.Call c && Node.AGGREGATES.contains(c.function())) {
                ok.add(false);
            }
        });
        return !ok.contains(false);
    }

    /** A canonical text for an aggregate call: {@code sum:trades.notional}. */
    public static String key(Node.Call c) {
        return c.function() + ":" + text(c.args().get(0));
    }

    static String text(Node n) {
        return switch (n) {
            case Node.Ref r -> r.text();
            case Node.Num x -> x.integral() ? Long.toString((long) x.value()) : Double.toString(x.value());
            case Node.Str s -> "'" + s.value() + "'";
            case Node.Bool b -> Boolean.toString(b.value());
            case Node.Null x -> "null";
            case Node.UserRef u -> "user." + u.attribute();
            case Node.MeasureRef m -> "[" + m.name() + "]";
            case Node.Call c -> c.function() + "(" + String.join(",", c.args().stream().map(Rollups::text).toList()) + ")";
            case Node.Bin b -> "(" + text(b.left()) + b.op() + text(b.right()) + ")";
            case Node.Un u -> u.op() + text(u.operand());
            case Node.In i -> text(i.value()) + (i.negated() ? " not in " : " in ") + i.items().stream().map(Rollups::text).toList();
            case Node.Cond c -> "(" + text(c.test()) + "?" + text(c.yes()) + ":" + text(c.no()) + ")";
        };
    }
}
