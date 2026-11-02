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
package com.ash.drishti.bicompute.rollup;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.List;

/**
 * What a provider offers rollups (PIVOT_ENGINE.md section 9.6): reads as the system (every row; a rollup is built once for everyone), its data
 * version, its parts (the files of a Delta or Iceberg version, so a load that appends files adds only theirs), and whether a person reads
 * every row and field: a rollup answers only such a person (row rules and masks apply before aggregation, and a rollup has aggregated
 * already). VaR is never rollup material (its own per-node sums are its rollup).
 */
public interface RollupSource {

    /** The provider whose sources these are. */
    PivotProvider provider();

    /** True when this person reads every row and every field of the source on the date (no row rule, no mask). */
    boolean unrestricted(Viewer v, String source, String date);

    /** The source's data version on a date, as the system reads it (a new load gives a new version). */
    String dataVersion(String source, String date);

    /** The date the source's latest data is on (null: undated). */
    String latestDate(String source);

    /** The source's parts on a date (files); empty when it is not parted (a refresh then rebuilds the whole rollup). */
    default List<Part> parts(String source, String date) {
        return List.of();
    }

    /** The plan's partial states as the system reads them, over {@code parts} only (null: every row). */
    Partials evaluate(AggregationPlan plan, List<String> parts);

    /** The plan's partial states as {@code v} reads them (row rules and masks applied), over {@code parts} only (null: every row). */
    default Partials evaluateAs(Viewer v, AggregationPlan plan, List<String> parts) {
        if (parts != null) {
            throw new UnsupportedOperationException(plan.source() + " is not read by parts");
        }
        return provider().evaluate(v, plan);
    }

    /**
     * A part of a source.
     *
     * @param id its id (a file path)
     * @param partition its partition (a business date, a month); a restatement rebuilds only the partitions it touched
     * @param rows its rows (-1: unknown)
     * @param bytes its bytes (-1: unknown)
     */
    record Part(String id, String partition, long rows, long bytes) {}
}
