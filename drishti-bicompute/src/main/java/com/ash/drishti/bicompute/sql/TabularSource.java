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
package com.ash.drishti.bicompute.sql;

import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.List;
import java.util.Map;

/**
 * A table the tabular provider pivots (ARCHITECTURE.md section 23.6.2), opened for one person on one date: its fields as that person may see
 * them (masked ones left out), and the relation DuckDB reads (a table staged in the executor from the connector's read, a Parquet scan, an
 * Arrow view of memory-only data), with the person's row rules as SQL applied before any grouping.
 */
public interface TabularSource {

    /** The source's id ({@code trade}). */
    String id();

    String title();

    /** The fields this person may use: name and type ({@code string}, {@code date}, {@code number}); masked ones are not here. */
    List<PivotProvider.Field> fields();

    /** How many fields are hidden from this person. */
    int masked();

    /** The business date read (null: undated). */
    String date();

    /** The dates the source holds, oldest first. */
    List<String> dates();

    /** The data version read, with whatever else changes the rows (the date, the reader's mask signature): part of every cache key. */
    String version();

    /** The rows (−1: unknown) and bytes (−1: unknown) the relation holds, for the scan estimate; nothing is read. */
    long rows();

    long bytes();

    /** What every cell shares in its provenance (the route, the connector, the row rules in words). */
    Map<String, Object> notes();

    /**
     * The note that says whether user code (Python custom functions) may read the source's rows ({@code false} for memory-only data,
     * ARCHITECTURE.md section 23 decision 4); absent: allowed.
     */
    String USER_CODE = "userCode";

    /** The relation the plan reads, made ready in the executor if it must be (a staged table is read once and kept). */
    Relation relation(DuckDbExecutor exec);

    /**
     * The relation one plan reads: a document source unnests the lists the plan's grain steps into (one row per leg) and extracts its
     * path fields; every other source reads the same relation for every plan.
     */
    default Relation relation(DuckDbExecutor exec, com.ash.drishti.bicompute.core.AggregationPlan plan) {
        return relation(exec);
    }

    /** The view as this source answers it ({@link PivotProvider#prepare}); the default: as it is. */
    default com.ash.drishti.bicompute.core.PivotView prepare(com.ash.drishti.bicompute.core.PivotView view) {
        return view;
    }

    /**
     * A relation: a FROM item and its bound parameters, and the person's row rules (a SQL condition over its columns and its parameters;
     * null: none) applied before grouping.
     */
    record Relation(String from, List<Object> params, String rowRules, List<Object> ruleParams) {
        public Relation {
            params = List.copyOf(params);
            ruleParams = ruleParams == null ? List.of() : List.copyOf(ruleParams);
        }
    }

    /** Finds and opens sources for a person. */
    interface Resolver {

        /** The sources this person may pivot: id to title. */
        Map<String, String> sources(Viewer v);

        /** True when it answers {@code source}. */
        boolean serves(String source);

        /** The source on a date (null: its latest), as the person sees it; refused in words when the person may not open it. */
        TabularSource open(Viewer v, String source, String date);
    }
}
