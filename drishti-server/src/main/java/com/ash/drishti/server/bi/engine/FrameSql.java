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

import com.ash.drishti.server.bi.dataset.Dataset;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * Writes the SQL of a composed dataset's joined table (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.10): each source's answer, already masked and row-ruled for the reader, is loaded
 * into a staging table of the engine, and the table the dataset's measures read is the join of those staging tables. Names are the dataset's own and are quoted;
 * no value is ever written into the SQL text.
 */
public final class FrameSql {

    private static final AtomicLong STAGE = new AtomicLong();

    private FrameSql() {}

    /** The request a source's {@code Input} asks of its dataset: the grouping columns (or the fields of a row extract) then the measures, in the order of the frame. */
    public static QueryRequest requestOf(Dataset.Input in, List<QueryRequest.Filter> extra, java.time.LocalDate from, java.time.LocalDate to, Integer limit) {
        List<String> fields = new ArrayList<>();
        (in.rows() ? in.fields() : in.by()).forEach(n -> fields.add(n.source()));
        List<String> measures = in.measures().stream().map(Dataset.Named::source).toList();
        List<QueryRequest.Filter> filters = new ArrayList<>();
        in.filters().forEach(f -> filters.add(new QueryRequest.Filter(f.field(), f.op(), f.values())));
        filters.addAll(extra);
        return new QueryRequest(fields, measures, filters, from, to, List.of(), limit, "auto");
    }

    /** The name an answer's column has in the frame: {@code as}, else the last segment of the field, else the measure's name. */
    public static List<String> frameNames(Dataset.Input in) {
        List<String> names = new ArrayList<>();
        for (Dataset.Named n : in.rows() ? in.fields() : in.by()) {
            names.add(n.as() != null ? n.as() : n.source().substring(n.source().lastIndexOf('.') + 1));
        }
        for (Dataset.Named n : in.measures()) {
            names.add(n.as() != null ? n.as() : n.source());
        }
        return names;
    }

    /** The staging table of one source's answer: columns named as the frame names them, filled by the appender. Returns its name. */
    static String stage(Connection c, String alias, Dataset.Input in, QueryService.Result answer) throws SQLException {
        String table = "bi_frame_" + STAGE.incrementAndGet() + "_" + alias.replaceAll("[^A-Za-z0-9_]", "_");
        List<String> names = frameNames(in);
        if (names.size() != answer.columns().size()) {
            throw new SQLException("the answer of '" + alias + "' has " + answer.columns().size() + " columns, the question " + names.size());
        }
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(Dialect.quote(table)).append(" (");
        for (int i = 0; i < names.size(); i++) {
            ddl.append(i == 0 ? "" : ", ").append(Dialect.quote(names.get(i))).append(' ').append(switch (answer.columns().get(i).kind()) {
                case TEXT -> "VARCHAR";
                case INT64 -> "BIGINT";
                case FLOAT64 -> "DOUBLE";
            });
        }
        try (Statement s = c.createStatement()) {
            s.execute(ddl.append(')').toString());
        }
        DuckDBConnection duck = c.unwrap(DuckDBConnection.class);
        try (DuckDBAppender app = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, table)) {
            for (int r = 0; r < answer.rows(); r++) {
                app.beginRow();
                for (ArrowIpcWriter.Column col : answer.columns()) {
                    Object v = col.values()[r];
                    switch (col.kind()) {
                        case TEXT -> app.append(v == null ? null : v.toString());
                        case INT64 -> {
                            if (v == null) {
                                app.append((Long) null);
                            } else {
                                app.append(((Number) v).longValue());
                            }
                        }
                        case FLOAT64 -> {
                            if (v == null) {
                                app.append((Double) null);
                            } else {
                                app.append(((Number) v).doubleValue());
                            }
                        }
                    }
                }
                app.endRow();
            }
        }
        return table;
    }

    /** Stages every answer; the map is alias to staging table. The caller drops them with {@link #drop}. */
    static Map<String, String> stageAll(Connection c, Dataset.Compose compose, Map<String, QueryService.Result> answers) throws SQLException {
        Map<String, String> tables = new LinkedHashMap<>();
        for (Dataset.Input in : compose.from().values()) {
            QueryService.Result a = answers.get(in.alias());
            if (a == null) {
                throw new SQLException("no answer for source '" + in.alias() + "'");
            }
            tables.put(in.alias(), stage(c, in.alias(), in, a));
        }
        return tables;
    }

    static void drop(Connection c, Iterable<String> tables) {
        try (Statement s = c.createStatement()) {
            for (String t : tables) {
                s.execute("DROP TABLE IF EXISTS " + Dialect.quote(t));
            }
        } catch (SQLException ignored) {
            // the staging tables are scratch; a failure to drop one is not the query's failure
        }
    }

    /** The joined table: one column per column of the frame (a folded key once, as the coalesce of both sides), the staging tables joined as the dataset says. */
    static String joined(Dataset ds, Map<String, String> staged) {
        Dataset.Compose c = ds.compose();
        List<String> names = new ArrayList<>(ds.tables().get(Dataset.JOINED).columns().keySet());
        List<String> select = new ArrayList<>();
        for (int i = 0; i < c.frame().size(); i++) {
            Dataset.FrameColumn fc = c.frame().get(i);
            String col = Dialect.quote(fc.name());
            String expr = fc.merged() != null ? "COALESCE(" + Dialect.quote(fc.alias()) + "." + col + ", " + Dialect.quote(fc.merged()) + "." + col + ")" : Dialect.quote(fc.alias()) + "." + col;
            String type = ds.tables().get(Dataset.JOINED).columns().get(names.get(i)).type();
            select.add("CAST(" + expr + " AS " + TableRelations.sqlType(type, Dialect.DUCKDB) + ") AS " + Dialect.quote(names.get(i)));
        }
        StringBuilder from = new StringBuilder();
        String first = c.joins().isEmpty() ? c.from().keySet().iterator().next() : c.joins().get(0).left();
        from.append(Dialect.quote(staged.get(first))).append(" AS ").append(Dialect.quote(first));
        for (Dataset.Join j : c.joins()) {
            from.append(switch (j.type()) {
                case "left" -> " LEFT JOIN ";
                case "full" -> " FULL OUTER JOIN ";
                default -> " INNER JOIN ";
            }).append(Dialect.quote(staged.get(j.right()))).append(" AS ").append(Dialect.quote(j.right())).append(" ON ");
            List<String> on = new ArrayList<>();
            for (String pair : j.on()) {
                String[] sides = pair.contains("=") ? pair.split("=", 2) : new String[] {pair, pair};
                on.add(Dialect.quote(j.left()) + "." + Dialect.quote(sides[0].trim()) + " = " + Dialect.quote(j.right()) + "." + Dialect.quote(sides[1].trim()));
            }
            from.append(String.join(" AND ", on));
        }
        return "SELECT " + String.join(", ", select) + " FROM " + from;
    }

    /** The Transform's result file as the dataset's one table, each declared column cast to its type. A column the file lacks is null. */
    static SqlCompiler.Source parquet(Dataset ds, java.nio.file.Path file, java.util.Set<String> present) {
        Dataset.Table t = ds.tables().get(Dataset.RESULT);
        List<String> cols = new ArrayList<>();
        for (Dataset.Column c : t.columns().values()) {
            String type = TableRelations.sqlType(c.type(), Dialect.DUCKDB);
            cols.add(present.contains(c.name()) ? "TRY_CAST(" + Dialect.quote(c.name()) + " AS " + type + ") AS " + Dialect.quote(c.name())
                    : "CAST(NULL AS " + type + ") AS " + Dialect.quote(c.name()));
        }
        String inner = "SELECT " + (cols.isEmpty() ? "1 AS \"_\"" : String.join(", ", cols)) + " FROM read_parquet('" + TableBuilder.literal(file) + "')";
        return new SqlCompiler.Source("(" + inner + ") AS " + Dialect.quote(Dataset.RESULT), List.of());
    }
}
