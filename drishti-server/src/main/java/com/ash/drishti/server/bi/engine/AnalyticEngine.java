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
import java.time.Duration;
import java.util.List;

/**
 * What the query compiler writes SQL for (RUPAKA.md section 7.1, "Engine interface"). The compiler emits a parameterised SELECT in the engine's
 * {@link Dialect}; the engine runs it under a time limit and returns typed columns. {@link DuckDbEngine} is the default and the only one built;
 * a distributed engine (Trino, Spark, ClickHouse) would be another implementation reached through a connector's SQL target, with Drishti still
 * adding row rules and masks before the SQL is written.
 */
public interface AnalyticEngine extends AutoCloseable {

    /** The engine's name as a dataset's {@code engine:} says it. */
    String name();

    Dialect dialect();

    /** Work on one connection of the engine. */
    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    /** A query's answer in columns. */
    record Answer(List<ArrowIpcWriter.Column> columns, int rows, double millis) {}

    /**
     * Runs a SELECT with bound parameters. Reads at most {@code maxRows} rows and stops (DRS-9022 is the caller's decision from {@code rows});
     * past {@code timeout} the statement is cancelled and the call fails with DRS-9024.
     */
    Answer query(String sql, List<Object> params, int maxRows, Duration timeout);

    /** Runs a statement that returns nothing (a layer being written); no row limit. */
    void execute(String sql, List<Object> params);

    /** Runs work on a connection of its own (a staging table that must live as long as the work). */
    <T> T with(Work<T> work);

    @Override
    void close();
}
