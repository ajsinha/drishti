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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * Tables of a model whose rows are another query's answer (docs/architecture/BI_MODEL_SPEC.md sections 1.2 D and 5.4: a dataset whose source is a model):
 * whoever binds the model asks the inner query as the reader, and the engine stages the answer as one partition of the table for the length of one query,
 * so the table joins, filters and aggregates like any other. The answers are asked lazily, only for the tables a query plans to read.
 */
public interface StagedTables {

    /** What the inner answers are right now (the reader, the inner models' versions and data generations): cheap; part of the result-cache key. */
    String fingerprint();

    /** Whether the rows of this table are staged. */
    boolean has(String table);

    /** Asks the inner query of the table as the reader. */
    Answer answer(String table);

    /**
     * An inner answer: the result, for each physical column of the table the answer's column that fills it (absent: null), and the business date the rows
     * are as of (null: none).
     */
    record Answer(QueryService.Result result, Map<String, String> columns, LocalDate date) {}

    /** Writes an answer as a parquet file named by the table's columns ({@code id} the row number, {@code businessDate} the answer's date), cast to their types. */
    static long write(Connection c, java.nio.file.Path file, com.ash.drishti.server.bi.dataset.Dataset.Table t, Answer a) throws SQLException {
        String stage = "bi_staged_" + Counter.NEXT.incrementAndGet();
        QueryService.Result r = a.result();
        StringBuilder ddl = new StringBuilder("CREATE TEMP TABLE ").append(Dialect.quote(stage)).append(" (");
        for (int i = 0; i < r.columns().size(); i++) {
            ddl.append(i == 0 ? "" : ", ").append(Dialect.quote("c" + i)).append(' ').append(switch (r.columns().get(i).kind()) {
                case TEXT -> "VARCHAR";
                case INT64 -> "BIGINT";
                case FLOAT64 -> "DOUBLE";
            });
        }
        try (Statement s = c.createStatement()) {
            s.execute(ddl.append(')').toString());
        }
        try {
            try (DuckDBAppender app = c.unwrap(DuckDBConnection.class).createAppender(DuckDBConnection.DEFAULT_SCHEMA, stage)) {
                for (int row = 0; row < r.rows(); row++) {
                    app.beginRow();
                    for (ArrowIpcWriter.Column col : r.columns()) {
                        Object v = col.values()[row];
                        switch (col.kind()) {
                            case TEXT -> app.append(v == null ? null : v.toString());
                            case INT64 -> app.append(v == null ? null : (Long) ((Number) v).longValue());
                            case FLOAT64 -> app.append(v == null ? null : (Double) ((Number) v).doubleValue());
                        }
                    }
                    app.endRow();
                }
            }
            List<String> select = new ArrayList<>();
            select.add("CAST(row_number() OVER () AS VARCHAR) AS \"id\"");
            select.add((a.date() == null ? "CAST(NULL AS DATE)" : "DATE '" + a.date() + "'") + " AS \"businessDate\"");
            List<String> names = r.columns().stream().map(ArrowIpcWriter.Column::name).toList();
            for (com.ash.drishti.server.bi.dataset.Dataset.Column col : t.physical()) {
                String from = a.columns().get(col.name());
                int at = from == null ? -1 : names.indexOf(from);
                String type = TableRelations.sqlType(col.type(), Dialect.DUCKDB);
                select.add((at < 0 ? "CAST(NULL AS " + type + ")" : "TRY_CAST(" + Dialect.quote("c" + at) + " AS " + type + ")") + " AS " + Dialect.quote(col.name()));
            }
            try (Statement s = c.createStatement()) {
                s.execute("COPY (SELECT " + String.join(", ", select) + " FROM " + Dialect.quote(stage) + ") TO '" + TableBuilder.literal(file) + "' (FORMAT PARQUET)");
            }
            return r.rows();
        } finally {
            try (Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS " + Dialect.quote(stage));
            }
        }
    }

    /** Names the staging tables apart. */
    final class Counter {
        static final AtomicLong NEXT = new AtomicLong();

        private Counter() {}
    }
}
