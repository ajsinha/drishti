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

import com.ash.drishti.api.SqlTarget;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The FROM item of a dataset table: the table as a derived table named after it, so every column is {@code "table"."column"} in the compiled
 * query. Calculated columns are added here, in a wrapping select, so a measure sees them like stored ones. Two kinds: over the columns layer's
 * Parquet files, and over a SQL store's table (pushdown).
 */
public final class TableRelations {

    private TableRelations() {}

    static String sqlType(String type, Dialect d) {
        return switch (type) {
            case "integer" -> "BIGINT";
            case "decimal" -> d.doubleType();
            case "boolean" -> "BOOLEAN";
            case "date" -> "DATE";
            case "timestamp" -> "TIMESTAMP";
            default -> d.textType();
        };
    }

    /** The layer's files as the table; no files means no rows (an empty table of the right columns), never an error. */
    public static SqlCompiler.Source cached(Dataset ds, Dataset.Table t, List<Path> files, Dialect d) {
        String inner;
        if (files.isEmpty()) {
            List<String> cols = new ArrayList<>();
            cols.add("CAST(NULL AS " + d.textType() + ") AS \"id\"");
            cols.add("CAST(NULL AS DATE) AS \"businessDate\"");
            if (t.each() != null) {
                cols.add("CAST(NULL AS BIGINT) AS \"index\"");
            }
            t.physical().forEach(c -> cols.add("CAST(NULL AS " + sqlType(c.type(), d) + ") AS " + Dialect.quote(c.name())));
            inner = "SELECT " + String.join(", ", cols) + " WHERE 1 = 0";
        } else {
            inner = "SELECT * FROM read_parquet([" + String.join(", ", files.stream().map(f -> "'" + TableBuilder.literal(f) + "'").toList()) + "], union_by_name = true)";
        }
        return wrap(ds, t, inner, new ArrayList<>(), d);
    }

    /** A live table's in-memory table: the declared columns by their names, the two live columns ({@code updateSeq}, {@code updatedAt}), calculated columns added. */
    public static SqlCompiler.Source live(Dataset ds, Dataset.Table t, String sqlTable, Dialect d) {
        List<String> cols = new ArrayList<>();
        cols.add("\"id\"");
        cols.add("\"businessDate\"");
        t.physical().forEach(c -> cols.add(Dialect.quote(c.name())));
        cols.add("\"updateSeq\"");
        cols.add("\"updatedAt\"");
        return wrap(ds, t, "SELECT " + String.join(", ", cols) + " FROM " + sqlTable, new ArrayList<>(), d);
    }

    /** A live table's history: {@code id} (the sample time as text), {@code businessDate} (its day), then the declared columns ({@code ts}, the groups, the sampled measures). */
    public static SqlCompiler.Source history(Dataset ds, Dataset.Table t, String sqlTable, Dialect d) {
        List<String> cols = new ArrayList<>();
        cols.add("CAST(\"ts\" AS " + d.textType() + ") AS \"id\"");
        cols.add("CAST(\"ts\" AS DATE) AS \"businessDate\"");
        t.physical().forEach(c -> cols.add(Dialect.quote(c.name())));
        return wrap(ds, t, "SELECT " + String.join(", ", cols) + " FROM " + sqlTable, new ArrayList<>(), d);
    }

    /** A SQL store's table: the declared columns by their promoted names, the kind and the dates selected by bound parameters. */
    static SqlCompiler.Source direct(Dataset ds, Dataset.Table t, SqlTarget target, Dialect d, LocalDate from, LocalDate to) {
        if (t.each() != null) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "table '" + t.name() + "' is over an array (each: " + t.each() + ") and cannot be direct: use mode: cached");
        }
        SqlTarget.Relation rel = target.relation(t.kind());
        if (rel == null) {
            throw new DrishtiException(ErrorCode.QUERY_SOURCE, "the store does not hold kind '" + t.kind() + "'");
        }
        List<String> cols = new ArrayList<>();
        cols.add(rel.idColumn() + " AS \"id\"");
        if (rel.dateColumn() != null) {
            cols.add(rel.dateColumn() + " AS \"businessDate\"");
        } else {
            cols.add("CAST(NULL AS DATE) AS \"businessDate\"");
        }
        for (Dataset.Column c : t.physical()) {
            String col = rel.columnOf().apply(c.path());
            if (col == null) {
                throw new DrishtiException(ErrorCode.QUERY_SOURCE, "column '" + c.name() + "' (path " + c.path() + ") is not a column of the store for kind " + t.kind()
                        + ": the connector must promote it (layout." + t.kind() + ".columns), or use mode: cached");
            }
            cols.add("CAST(" + col + " AS " + sqlType(c.type(), d) + ") AS " + Dialect.quote(c.name()));
        }
        List<Object> params = new ArrayList<>();
        List<String> where = new ArrayList<>();
        if (rel.kindColumn() != null) {
            where.add(rel.kindColumn() + " = ?");
            params.add(t.kind());
        }
        if (rel.dateColumn() != null) {
            if (from == null) {
                where.add(rel.dateColumn() + " = (SELECT max(" + rel.dateColumn() + ") FROM " + rel.from()
                        + (rel.kindColumn() != null ? " WHERE " + rel.kindColumn() + " = ?" : "") + ")");
                if (rel.kindColumn() != null) {
                    params.add(t.kind());
                }
            } else {
                where.add(rel.dateColumn() + " BETWEEN CAST(? AS DATE) AND CAST(? AS DATE)");
                params.add(from.toString());
                params.add((to == null ? from : to).toString());
            }
        }
        String inner = "SELECT " + String.join(", ", cols) + " FROM " + rel.from() + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where));
        return wrap(ds, t, inner, params, d);
    }

    private static SqlCompiler.Source wrap(Dataset ds, Dataset.Table t, String inner, List<Object> params, Dialect d) {
        List<Dataset.Column> calc = t.columns().values().stream().filter(c -> c.expr() != null).toList();
        if (calc.isEmpty()) {
            return new SqlCompiler.Source("(" + inner + ") AS " + Dialect.quote(t.name()), params);
        }
        SqlEmitter e = new SqlEmitter(ds, d, t.name(), null, java.util.Map.of());
        List<String> extra = new ArrayList<>();
        for (Dataset.Column c : calc) {
            extra.add(e.calculated(t, c.expr()) + " AS " + Dialect.quote(c.name()));
        }
        List<Object> all = new ArrayList<>(e.params());
        all.addAll(params);
        // the calculated columns' parameters come first in the text (the select list), then the inner query's
        return new SqlCompiler.Source("(SELECT t.*, " + String.join(", ", extra) + " FROM (" + inner + ") AS t) AS " + Dialect.quote(t.name()), all);
    }
}
