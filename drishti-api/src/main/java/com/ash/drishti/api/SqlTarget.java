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
package com.ash.drishti.api;

import java.sql.Connection;
import java.util.function.Function;

/**
 * Where a SQL store keeps a kind, so the analytic engine can push a compiled query into the source (RUPAKA.md section 6.1,
 * {@code direct} tables). The engine compiles one SELECT over {@link Relation#from()} and runs it on a connection from {@link #with}; values
 * are bound parameters, names come from the relation. A source with no SQL store returns no target.
 */
public interface SqlTarget {

    /** The SQL dialect: {@code duckdb} or {@code postgresql}. */
    String dialect();

    /** How the kind is stored; null when this target does not hold it. */
    Relation relation(String kind);

    /** Work on one connection of the source's own pool. */
    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    /** Runs {@code work} on a connection borrowed from the source's pool and returns it afterwards; the work must not keep the connection. */
    <T> T with(Work<T> work) throws Exception;

    /**
     * A kind's relation.
     *
     * @param from a table expression ({@code "drishti".trading.entities}), already quoted
     * @param kindColumn the column holding the kind name, or null when the relation holds one kind
     * @param dateColumn the business-date column, or null when undated
     * @param idColumn the entity id column
     * @param columnOf the quoted column of a document path, or null when the path is not a column of the relation
     */
    record Relation(String from, String kindColumn, String dateColumn, String idColumn, Function<String, String> columnOf) {}
}
