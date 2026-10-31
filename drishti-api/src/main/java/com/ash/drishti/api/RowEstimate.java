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

/**
 * A source's count of one business date's rows of a kind, read from its metadata only (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 4.1): a Delta
 * table's add-action statistics, an Iceberg snapshot's manifests. The BI runs planner sizes a question with it before anything is read.
 *
 * @param rows  the rows the date holds (0 when the source holds none)
 * @param basis where the count came from, as the run's estimate names it: {@code delta-stats}, {@code iceberg-manifests}, {@code store-stats}
 */
public record RowEstimate(long rows, String basis) {

    public static final String DELTA_STATS = "delta-stats";
    public static final String ICEBERG_MANIFESTS = "iceberg-manifests";
    public static final String STORE_STATS = "store-stats";

    public RowEstimate {
        if (rows < 0) {
            throw new IllegalArgumentException("rows are zero or more");
        }
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException("a row estimate names its basis");
        }
    }
}
