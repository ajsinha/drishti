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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.Resolver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Currency conversion (wave B, docs/architecture/BI_DESIGN.md section 3.3.4), from the model's FX rates ({@code fx: { rates, from, to, rate, date, base }}):
 * <ul>
 * <li>{@code fx(ccy, 'USD', at=d)}: the rate of one unit of {@code ccy} in USD, the latest on or before {@code d} (default: the row's business date) — a
 * pair as stored, its inverse, or through the base currency; 1 for the same currency; None when no rate is known;</li>
 * <li>{@code convert(agg, to='USD', at='as-of' | 'trade-date' | '<column>')}: every amount inside the aggregate multiplied by {@code fx} of its own currency
 * (the column's {@code unit: { currency: <column> }}, or its fixed code) at the as-of date, at each row's trade date, or at another date column of the row;
 * the result has one currency, so the currency guard is lifted.</li>
 * </ul>
 * The rates are read at the query's dates as reference data (the rates table's row rules do not filter a conversion); a lookup is a correlated sub-query on the rates, each rate
 * stored both ways.
 */
final class Conversion {

    private static final Set<String> CONVERTED = Set.of("sum", "avg", "min", "max");

    private Conversion() {}

    static String emit(SqlEmitter e, Node.Call c, boolean inAgg) {
        List<Node> pos = Node.positional(c);
        if (e.isPlanning()) {
            return e.emit(pos.get(0), inAgg);                                  // while planning: the tables the amount (or the currency) reads
        }
        Dataset.Fx fx = e.dataset().model().fx();
        if (fx == null) {
            throw new DrishtiException(ErrorCode.MQ_CONTEXT, c.function() + "() needs the model's FX rates: add `fx: { rates: <alias>, from, to, rate, date }` to the model");
        }
        if ("fx".equals(c.function())) {
            String to = text(pos.get(1), "fx(): to");
            Node.Call at = Node.keyword(c, "at");
            Node date = at == null || at.args().isEmpty() ? rowDate(e, pos.get(0)) : at.args().get(0);
            return rate(e, fx, pos.get(0), to, date, inAgg);
        }
        Node.Call toK = Node.keyword(c, "to");
        Node.Call atK = Node.keyword(c, "at");
        String to = toK == null || toK.args().isEmpty() ? "USD" : text(toK.args().get(0), "convert(): to");
        String at = atK == null || atK.args().isEmpty() ? "as-of" : text(atK.args().get(0), "convert(): at");
        return e.emit(converted(e, pos.get(0), to, at), inAgg);
    }

    private static String text(Node n, String what) {
        if (n instanceof Node.Str s) {
            return s.value();
        }
        throw new DrishtiException(ErrorCode.QUERY_INVALID, what + " is a text written in the expression");
    }

    /** The aggregate with each amount it adds multiplied by its currency's rate into {@code to}. */
    private static Node converted(SqlEmitter e, Node n, String to, String at) {
        return switch (n) {
            case Node.Call c when CONVERTED.contains(c.function()) && !c.args().isEmpty() -> {
                List<Node> args = new ArrayList<>(c.args());
                Node amount = args.get(0);
                Node ccy = currencyOf(e, amount);
                Node date = switch (at) {
                    case "as-of" -> new Node.Call("asof", List.of());
                    case "trade-date" -> dateColumn(e, amount, "tradeDate");
                    default -> dateColumn(e, amount, at);
                };
                args.set(0, new Node.Bin("*", amount, new Node.Call("fx", List.of(ccy, new Node.Str(to), new Node.Call(Node.KEYWORD + "at", List.of(date))))));
                yield new Node.Call(c.function(), args);
            }
            case Node.MeasureRef m -> {
                Dataset.Measure def = e.dataset().measures().get(m.name());
                if (def == null || def.expr() == null) {
                    throw new DrishtiException(ErrorCode.QUERY_INVALID, "convert(): no measure '" + m.name() + "' with an expression");
                }
                yield converted(e, com.ash.drishti.server.bi.expr.Expressions.parse(def.expr()), to, at);
            }
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(a -> converted(e, a, to, at)).toList());
            case Node.Bin b -> new Node.Bin(b.op(), converted(e, b.left(), to, at), converted(e, b.right(), to, at));
            case Node.Un u -> new Node.Un(u.op(), converted(e, u.operand(), to, at));
            case Node.Cond c -> new Node.Cond(c.test(), converted(e, c.yes(), to, at), converted(e, c.no(), to, at));
            default -> n;
        };
    }

    /** The currency of an amount: its column's per-row currency column, or its fixed code (from the model's columns). */
    private static Node currencyOf(SqlEmitter e, Node amount) {
        Set<String> found = new LinkedHashSet<>();
        Node.walk(amount, x -> {
            if (x instanceof Node.Ref r && r.parts().size() >= 2) {
                String ccy = e.dataset().model().currencyOf().get(r.parts().get(0) + "." + r.parts().get(1));
                if (ccy != null) {
                    found.add(ccy);
                }
            }
        });
        if (found.size() != 1) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "convert(): " + (found.isEmpty() ? "the amount has no currency (give its column unit: { currency: <column or code> })"
                    : "the amount mixes currencies " + found + ": convert each amount in its own aggregate"));
        }
        String c = found.iterator().next();
        return c.contains(".") ? new Node.Ref(List.of(c.split("\\."))) : new Node.Str(c);
    }

    private static Node dateColumn(SqlEmitter e, Node amount, String column) {
        String table = null;
        for (Node x : refs(amount)) {
            table = ((Node.Ref) x).parts().get(0);
        }
        if (table == null) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "convert(): at='" + column + "' needs an amount column");
        }
        Dataset.Table t = e.dataset().tables().get(table);
        if (t != null && e.resolver().column(table, column) != null) {
            return new Node.Ref(List.of(table, column));
        }
        if ("tradeDate".equals(column)) {
            return new Node.Ref(List.of(table, Dataset.DATE));                       // no trade date: the row's business date
        }
        throw new DrishtiException(ErrorCode.QUERY_INVALID, "convert(): at='" + column + "' is not a date column of " + table);
    }

    private static List<Node> refs(Node n) {
        List<Node> out = new ArrayList<>();
        Node.walk(n, x -> {
            if (x instanceof Node.Ref r && r.parts().size() >= 2) {
                out.add(x);
            }
        });
        return out;
    }

    /** The date a row's rate is taken at: its table's business date, else the as-of date. */
    private static Node rowDate(SqlEmitter e, Node ccy) {
        if (ccy instanceof Node.Ref r && r.parts().size() >= 2 && e.dataset().tables().containsKey(r.parts().get(0))) {
            return new Node.Ref(List.of(r.parts().get(0), Dataset.DATE));
        }
        return new Node.Call("asof", List.of());
    }

    /**
     * {@code CASE WHEN ccy = to THEN 1 ELSE coalesce(direct, through the base) END}, each lookup the latest rate on or before the date in the rates stored
     * both ways ({@code from, to, rate} and {@code to, from, 1 / rate}).
     */
    private static String rate(SqlEmitter e, Dataset.Fx fx, Node ccy, String to, Node date, boolean inAgg) {
        Contexts ctx = e.contexts();
        if (ctx == null) {
            throw new DrishtiException(ErrorCode.MQ_CONTEXT, "fx() is worked out in a query of the model (a measure), not in a dataset's derived column");
        }
        StringBuilder b = new StringBuilder("(CASE WHEN ");
        String c1 = e.emit(ccy, true);
        b.append("CAST(").append(c1).append(" AS VARCHAR) = ?");
        e.params().add(to);
        b.append(" THEN 1.0 ELSE coalesce(");
        b.append(lookup(e, ctx, fx, ccy, new Node.Str(to), date));
        if (fx.base() != null && !fx.base().equals(to)) {
            b.append(", ").append(lookup(e, ctx, fx, ccy, new Node.Str(fx.base()), date)).append(" * ").append(lookup(e, ctx, fx, new Node.Str(fx.base()), new Node.Str(to), date));
        }
        b.append(") END)");
        return b.toString();
    }

    private static String lookup(SqlEmitter e, Contexts ctx, Dataset.Fx fx, Node from, Node to, Node date) {
        SqlCompiler.Source src = ctx.source(fx.rates());
        String f = Dialect.quote(fx.from());
        String t = Dialect.quote(fx.to());
        String r = Dialect.quote(fx.rate());
        String d = Dialect.quote(fx.date());
        // the rates both ways, in text order: the source's parameters first
        e.params().addAll(src.params());
        e.params().addAll(src.params());
        String both = "(SELECT " + f + " AS f, " + t + " AS t, CAST(" + r + " AS DOUBLE) AS r, CAST(" + d + " AS DATE) AS d FROM " + src.sql()
                + " UNION ALL SELECT " + t + ", " + f + ", 1.0 / NULLIF(CAST(" + r + " AS DOUBLE), 0), CAST(" + d + " AS DATE) FROM " + src.sql() + ")";
        String fromSql = e.emit(from, true);
        String toSql = e.emit(to, true);
        String dateSql = e.emit(date, true);
        return "(SELECT x.r FROM " + both + " AS x WHERE x.f = CAST(" + fromSql + " AS VARCHAR) AND x.t = CAST(" + toSql + " AS VARCHAR) AND x.d <= CAST(" + dateSql
                + " AS DATE) ORDER BY x.d DESC LIMIT 1)";
    }

    static boolean known(Resolver r, String table, String column) {
        return r.column(table, column) != null;
    }
}
