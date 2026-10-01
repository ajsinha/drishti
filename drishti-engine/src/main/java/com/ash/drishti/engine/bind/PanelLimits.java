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
package com.ash.drishti.engine.bind;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much of a document the chart and aggregate panel kinds read, so one very long list cannot make a view slow
 * or a page heavy. A panel that stops short says so ({@code more}, {@code dropped}).
 *
 * @param maxValues numbers a {@code histogram} bins, and rows a {@code pivot} aggregates (default 100,000)
 * @param maxPoints points a {@code scatter} draws, and bars a {@code candlestick} or {@code waterfall} draws (default 5,000;
 *     a candlestick keeps the latest)
 * @param maxNodes nodes a {@code graph} draws (default 300); edges are capped at twice that
 * @param maxEvents events a {@code timeline} lists (default 500, the latest)
 * @param pivotRows row keys a {@code pivot} shows (default 200); the rest are counted in {@code more}
 * @param pivotColumns column keys a {@code pivot} shows (default 40); values under further keys are left out of the grid
 *     but still counted in the row totals
 */
@ConfigurationProperties("drishti.panels")
public record PanelLimits(Integer maxValues, Integer maxPoints, Integer maxNodes, Integer maxEvents, Integer pivotRows,
        Integer pivotColumns) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public PanelLimits {
        maxValues = positive(maxValues, 100_000);
        maxPoints = positive(maxPoints, 5_000);
        maxNodes = positive(maxNodes, 300);
        maxEvents = positive(maxEvents, 500);
        pivotRows = positive(pivotRows, 200);
        pivotColumns = positive(pivotColumns, 40);
    }

    /** The defaults. */
    public static PanelLimits defaults() {
        return new PanelLimits(null, null, null, null, null, null);
    }

    private static int positive(Integer v, int fallback) {
        return v == null || v <= 0 ? fallback : v;
    }
}
