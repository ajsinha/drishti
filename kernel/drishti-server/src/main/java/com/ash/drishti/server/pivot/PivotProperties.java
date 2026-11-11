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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The pivot engine's settings (PIVOT_GUIDE.md, configuration), {@code drishti.pivot}: the same section as the Pivot tab's (whose keys this
 * record leaves alone); {@code enabled} switches both.
 *
 * @param enabled the engine (and the Pivot tab): off, {@code /api/v1/pivot} answers {@code 404 DRS-5703} in words
 * @param maxCells visible cells a view may have, totals included
 * @param maxHolisticCells the same for a view with a holistic measure
 * @param busyCells a view of more cells asks busy protection before it opens deeper levels
 * @param cacheMb the partial-state cache's own limit in MB (in the one memory budget)
 * @param provenanceKept answers whose per-cell provenance is kept
 * @param localShards parts a mergeable plan is split into on this node, merged in a fixed order
 * @param stageMaxRows the most rows of one table read for a pivot
 * @param stageKeep tables read for pivots kept in the executor
 * @param duckdb the executor's DuckDB
 * @param estimate when an estimate asks first
 * @param aggregateOnly the minimum members per cell
 * @param export export limits
 */
@ConfigurationProperties("drishti.pivot")
public record PivotProperties(Boolean enabled, Integer maxCells, Integer maxHolisticCells, Integer busyCells, Integer cacheMb, Integer provenanceKept,
        Integer localShards, Long stageMaxRows, Integer stageKeep, Duck duckdb, Estimate estimate, AggregateOnly aggregateOnly, Export export) {

    /** {@code drishti.pivot.duckdb}. */
    public record Duck(Integer connections, Integer threads, Integer memoryLimitMb, String tempDirectory) {}

    /** {@code drishti.pivot.estimate}. */
    public record Estimate(Double confirmAboveSeconds, Long confirmAboveRows) {}

    /** {@code drishti.pivot.aggregate-only}. */
    public record AggregateOnly(Integer minMembersPerCell) {}

    /** {@code drishti.pivot.export}. */
    public record Export(Integer xlsxMaxRows, Integer rowsMax) {}

    public PivotProperties {
        enabled = enabled == null || enabled;
        maxCells = pos(maxCells, 20_000);
        maxHolisticCells = pos(maxHolisticCells, 5_000);
        busyCells = pos(busyCells, 2_000);
        cacheMb = pos(cacheMb, 512);
        provenanceKept = pos(provenanceKept, 500);
        localShards = pos(localShards, 1);
        stageMaxRows = stageMaxRows == null || stageMaxRows <= 0 ? 5_000_000L : stageMaxRows;
        stageKeep = pos(stageKeep, 16);
        duckdb = duckdb == null ? new Duck(0, 0, 4096, "") : duckdb;
        estimate = estimate == null ? new Estimate(60.0, 500_000_000L) : estimate;
        aggregateOnly = aggregateOnly == null ? new AggregateOnly(1) : aggregateOnly;
        export = export == null ? new Export(1_000_000, 1_000_000) : export;
    }

    private static int pos(Integer v, int d) {
        return v == null || v <= 0 ? d : v;
    }

    /** The core's settings. */
    public PivotCore.Settings core() {
        return new PivotCore.Settings(maxCells, maxHolisticCells, busyCells, Math.max(1, aggregateOnly.minMembersPerCell() == null ? 1
                : aggregateOnly.minMembersPerCell()), provenanceKept, localShards, estimate.confirmAboveSeconds() == null ? 60
                        : estimate.confirmAboveSeconds(), estimate.confirmAboveRows() == null ? 500_000_000L : estimate.confirmAboveRows());
    }

    /** The executor's settings. */
    public DuckDbExecutor.Settings executor() {
        return new DuckDbExecutor.Settings(duckdb.connections() == null ? 0 : duckdb.connections(), duckdb.threads() == null ? 0 : duckdb.threads(),
                duckdb.memoryLimitMb() == null ? 4096 : duckdb.memoryLimitMb(), duckdb.tempDirectory());
    }

    public int xlsxMaxRows() {
        return export.xlsxMaxRows() == null ? 1_000_000 : export.xlsxMaxRows();
    }

    public int rowsMax() {
        return export.rowsMax() == null ? 1_000_000 : export.rowsMax();
    }
}
