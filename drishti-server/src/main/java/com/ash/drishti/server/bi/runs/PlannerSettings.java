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
package com.ash.drishti.server.bi.runs;

/**
 * The planner's thresholds and seed rates (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 15, {@code config/runs/runs.yaml}): the interactive bounds, the
 * confirmation thresholds, the local engine's read limit, the estimate's range factors and the seed rates that measured rates replace. A plain
 * value: {@code RunsProperties} (bound from the file when the pipeline is wired) builds one.
 *
 * @param interactiveMaxSeconds    the estimate's high end an interactive question may reach
 * @param interactiveMaxRows       rows read an interactive question may reach
 * @param interactiveMaxBuildDates partitions an interactive question may build from the source
 * @param confirmAboveSeconds      an ad-hoc heavy run whose high end passes this asks first
 * @param confirmAboveRows         an ad-hoc heavy run reading more rows asks first
 * @param confirmUnknown           an estimate with basis unknown asks first
 * @param engineMaxReadRows        the local engine's limit: past it, with no split or external engine, DRS-9086
 * @param lowFactor                the range's low factor before {@code minRatioSamples} runs narrow it
 * @param highFactor               the range's high factor
 * @param minRatioSamples          finished runs of an engine before their actual ÷ estimate ratios set the factors
 * @param scanRowsPerSecond        seed: DuckDB over Parquet layers
 * @param buildRowsPerSecond       seed: a connector's column scan into a layer
 * @param recordRowsPerSecond      seed: the record-by-record path
 * @param overheadSeconds          the fixed overhead of any question
 * @param defaultBytesPerRow       bytes per row read when neither Parquet footers nor type widths are given
 */
public record PlannerSettings(double interactiveMaxSeconds, long interactiveMaxRows, int interactiveMaxBuildDates, double confirmAboveSeconds,
                              long confirmAboveRows, boolean confirmUnknown, long engineMaxReadRows, double lowFactor, double highFactor, int minRatioSamples,
                              double scanRowsPerSecond, double buildRowsPerSecond, double recordRowsPerSecond, double overheadSeconds,
                              int defaultBytesPerRow) {

    public PlannerSettings {
        if (lowFactor <= 0 || highFactor < lowFactor) {
            throw new IllegalArgumentException("estimate factors: 0 < low-factor <= high-factor");
        }
        if (scanRowsPerSecond <= 0 || buildRowsPerSecond <= 0 || recordRowsPerSecond <= 0) {
            throw new IllegalArgumentException("estimate rates are above zero");
        }
    }

    /** The shipped defaults of {@code config/runs/runs.yaml}. */
    public static PlannerSettings defaults() {
        return new PlannerSettings(10, 50_000_000L, 2, 60, 500_000_000L, true, 5_000_000_000L, 0.5, 2.0, 20, 50_000_000, 300_000, 2_000, 0.05, 32);
    }
}
