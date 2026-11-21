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
package com.ash.drishti.engine.rachana;

import com.ash.rachana.bind.PanelLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much of a document the chart and aggregate panel kinds read, so one very long list cannot make a view slow
 * or a page heavy. A panel that stops short says so ({@code more}, {@code dropped}). Drishti's settings for Rachana's {@link PanelLimits}
 * (which carries no Spring annotation): the same fields, defaults and names.
 *
 * @param maxValues numbers a {@code histogram} bins, and rows a {@code pivot} aggregates (default 100,000)
 * @param maxPoints points a {@code scatter} draws, and bars a {@code candlestick} or {@code waterfall} draws (default 5,000;
 *     a candlestick keeps the latest)
 * @param maxNodes nodes a {@code graph} draws (default 300); edges are capped at twice that
 * @param maxEvents events a {@code timeline} lists (default 500, the latest)
 * @param pivotRows row keys a {@code pivot} shows (default 200); the rest are counted in {@code more}
 * @param treeDepth levels a table or ladder with {@code children} descends (default 12); deeper rows are left out
 * @param treeRows rows in all (every level) one such table builds (default 10,000); further ones are left out
 * @param mapEntities entities of a kind a {@code map} panel reads to shade or to know which countries have a page (default 1,000)
 * @param mapCacheSeconds how long those entities are kept for the next map (default 60)
 * @param mapBudgetMillis the time a map gives its entities to arrive (default 5,000); a slower read leaves the map unshaded
 * @param pivotColumns column keys a {@code pivot} shows (default 40); values under further keys are left out of the grid
 *     but still counted in the row totals
 */
@ConfigurationProperties("drishti.panels")
public record PanelSettings(Integer maxValues, Integer maxPoints, Integer maxNodes, Integer maxEvents, Integer pivotRows,
        Integer pivotColumns, Integer treeDepth, Integer treeRows, Integer mapEntities, Integer mapCacheSeconds, Integer mapBudgetMillis) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public PanelSettings {
    }

    /** Rachana's limits (its defaults where a setting is absent or not positive). */
    public PanelLimits limits() {
        return new PanelLimits(maxValues, maxPoints, maxNodes, maxEvents, pivotRows, pivotColumns, treeDepth, treeRows, mapEntities,
                mapCacheSeconds, mapBudgetMillis);
    }
}
