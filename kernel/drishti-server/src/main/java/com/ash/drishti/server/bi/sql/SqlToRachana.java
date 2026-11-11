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
package com.ash.drishti.server.bi.sql;

import com.ash.drishti.common.Branding;
import com.ash.drishti.server.bi.dataset.NodeText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Writes a Drishti SQL expression as Rachana (docs/architecture/ARCHITECTURE.md §28 section 3.4: "date/text functions mapped to DEL"): an inline aggregate of a
 * {@code SELECT} becomes a local measure of the model query in Rachana, so it is checked, secured and compiled like any measure. {@code SUM(x)} is
 * {@code sum(x)}, {@code COUNT(*)} is {@code count(fact)}, {@code COUNT(DISTINCT x)} is {@code distinct(x)}, {@code PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY
 * x)} is {@code percentile(x, p)}, {@code CASE WHEN c THEN a ELSE b END} is {@code c ? a : b}, {@code MEASURE('m')} is {@code measure('m')}, {@code =} is
 * {@code ==}, {@code IS NULL} is {@code == null}. What has no Rachana form is refused with the code and a fix.
 */
final class SqlToRachana {

    private final Function<SqlAst.Column, String> columns;
    private final String fact;

    /**
     * @param columns the model field a column names ({@code trades.mtm}), refusing a name that is not one
     * @param fact the alias {@code COUNT(*)} counts
     */
    SqlToRachana(Function<SqlAst.Column, String> columns, String fact) {
        this.columns = columns;
        this.fact = fact;
    }

    String del(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.Column c -> columns.apply(c);
            case SqlAst.Literal l -> literal(l);
            case SqlAst.Measure m -> "measure(" + NodeText.quote(m.name()) + ")";
            case SqlAst.Unary u -> "NOT".equals(u.op()) ? "!(" + del(u.operand()) + ")" : "-(" + del(u.operand()) + ")";
            case SqlAst.Binary b -> binary(b);
            case SqlAst.In i -> {
                List<String> items = new ArrayList<>();
                for (SqlAst.Expr x : i.items()) {
                    if (x instanceof SqlAst.SubqueryExpr s) {
                        throw new DsqlException(DsqlException.SHAPE, "IN (SELECT ...) is not " + Branding.sqlName() + ": a semi-join is a relationship of the model", null, s.at());
                    }
                    items.add(del(x));
                }
                yield "(" + del(i.value()) + (i.negated() ? " not in [" : " in [") + String.join(", ", items) + "])";
            }
            case SqlAst.Between b -> (b.negated() ? "!(" : "(") + del(b.low()) + " <= " + del(b.value()) + " && " + del(b.value()) + " <= " + del(b.high()) + ")";
            case SqlAst.IsNull n -> "(" + del(n.value()) + (n.negated() ? " != null)" : " == null)");
            case SqlAst.Like l -> like(l);
            case SqlAst.Case c -> caseExpr(c);
            case SqlAst.Cast c -> cast(c);
            case SqlAst.Call c -> call(c);
            case SqlAst.SubqueryExpr s -> throw new DsqlException(DsqlException.SHAPE, "a subquery as a value is not " + Branding.sqlName(), null, s.at());
        };
    }

    private static String literal(SqlAst.Literal l) {
        return switch (l.type()) {
            case "null" -> "null";
            case "boolean" -> Boolean.TRUE.equals(l.value()) ? "true" : "false";
            case "string" -> NodeText.quote(String.valueOf(l.value()));
            case "date" -> "date(" + NodeText.quote(String.valueOf(l.value())) + ")";
            case "timestamp" -> "timestamp(" + NodeText.quote(String.valueOf(l.value()).replace(' ', 'T')) + ")";
            default -> String.valueOf(l.value());
        };
    }

    private String binary(SqlAst.Binary b) {
        String op = switch (b.op()) {
            case "=" -> "==";
            case "<>" -> "!=";
            case "AND" -> "&&";
            case "OR" -> "||";
            case "||" -> throw new DsqlException(DsqlException.SHAPE, "text concatenation (||) is not in a measure", null, b.at());
            default -> b.op();
        };
        return "(" + del(b.left()) + " " + op + " " + del(b.right()) + ")";
    }

    private String like(SqlAst.Like l) {
        if (!(l.pattern() instanceof SqlAst.Literal p) || !(p.value() instanceof String s)) {
            throw new DsqlException(DsqlException.SHAPE, "LIKE takes a pattern in quotes", null, l.at());
        }
        String inner = s;
        String fn;
        if (s.startsWith("%") && s.endsWith("%") && s.length() >= 2) {
            fn = "contains";
            inner = s.substring(1, s.length() - 1);
        } else if (s.endsWith("%")) {
            fn = "starts_with";
            inner = s.substring(0, s.length() - 1);
        } else if (s.startsWith("%")) {
            fn = "ends_with";
            inner = s.substring(1);
        } else {
            return "(" + del(l.value()) + (l.negated() ? " != " : " == ") + NodeText.quote(s) + ")";
        }
        if (inner.contains("%") || inner.contains("_")) {
            throw new DsqlException(DsqlException.SHAPE, "LIKE patterns are 'abc%', '%abc' or '%abc%' (no _ and no inner %)", null, l.at());
        }
        String t = fn + "(" + del(l.value()) + ", " + NodeText.quote(inner) + ")";
        return l.negated() ? "!" + t : t;
    }

    private String caseExpr(SqlAst.Case c) {
        String out = c.orElse() == null ? "null" : del(c.orElse());
        for (int i = c.whens().size() - 1; i >= 0; i--) {
            String test = c.operand() == null ? del(c.whens().get(i)) : "(" + del(c.operand()) + " == " + del(c.whens().get(i)) + ")";
            out = "(" + test + " ? " + del(c.thens().get(i)) + " : " + out + ")";
        }
        return out;
    }

    private String cast(SqlAst.Cast c) {
        return switch (c.type()) {
            case "DOUBLE", "DECIMAL", "NUMERIC", "FLOAT", "REAL" -> "(" + del(c.value()) + " * 1.0)";
            case "DATE" -> c.value() instanceof SqlAst.Literal l ? "date(" + NodeText.quote(String.valueOf(l.value())) + ")" : del(c.value());
            default -> throw new DsqlException(DsqlException.SHAPE, "CAST(... AS " + c.type() + ") is not in a measure", "measures compute numbers: CAST to DOUBLE or DECIMAL only", c.at());
        };
    }

    private String call(SqlAst.Call c) {
        if (c.over() != null) {
            throw new DsqlException(DsqlException.WINDOW, "a window function inside a measure is not " + Branding.sqlName(), "rank the answer's rows: put it in the SELECT list", c.at());
        }
        String f = c.name();
        List<String> args = new ArrayList<>();
        for (SqlAst.Expr a : c.args()) {
            args.add(del(a));
        }
        boolean aggregate = SqlFunctions.AGGREGATES.contains(f);
        if (aggregate && c.args().stream().anyMatch(a -> SqlAst.any(a, x -> x instanceof SqlAst.Measure))) {
            throw new DsqlException(DsqlException.MEASURE, f + "(MEASURE(...)) aggregates a measure, which is already an aggregate", "write the measure in Rachana in the model (or use the measure as it is)",
                    c.at());
        }
        return switch (f) {
            case "SUM", "AVG", "MIN", "MAX" -> f.toLowerCase(Locale.ROOT) + "(" + one(c, args) + ")";
            case "COUNT" -> c.star() ? "count(" + fact + ")" : c.distinct() ? "distinct(" + one(c, args) + ")" : "count(" + one(c, args) + ")";
            case "PERCENTILE_CONT", "PERCENTILE_DISC", "QUANTILE_CONT" -> {
                if (c.within() == null || c.args().size() != 1) {
                    throw new DsqlException(DsqlException.SHAPE, "PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x)", null, c.at());
                }
                yield "percentile(" + del(c.within()) + ", " + args.get(0) + ")";
            }
            case "COALESCE" -> "coalesce(" + String.join(", ", args) + ")";
            case "ABS" -> "abs(" + one(c, args) + ")";
            case "ROUND" -> "round(" + String.join(", ", args) + ")";
            case "UPPER" -> "upper(" + one(c, args) + ")";
            case "LOWER" -> "lower(" + one(c, args) + ")";
            case "LENGTH", "CHAR_LENGTH" -> "len(" + one(c, args) + ")";
            case "YEAR", "QUARTER", "MONTH", "WEEK", "DAY" -> f.toLowerCase(Locale.ROOT) + "(" + one(c, args) + ")";
            case "NULLIF" -> "(" + args.get(0) + " == " + args.get(1) + " ? null : " + args.get(0) + ")";
            default -> throw SqlFunctions.refused(f, c.at());
        };
    }

    private static String one(SqlAst.Call c, List<String> args) {
        if (args.size() != 1) {
            throw new DsqlException(DsqlException.SYNTAX, c.name() + " takes one argument", null, c.at());
        }
        return args.get(0);
    }
}
