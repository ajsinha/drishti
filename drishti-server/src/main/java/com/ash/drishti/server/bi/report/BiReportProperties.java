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
package com.ash.drishti.server.bi.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reports, {@code drishti.bi.report.*} (docs/admin/CONFIGURATION.md): how many rows each kind of visual asks the engine for. The engine's own limits
 * ({@code drishti.bi.query.*}) still apply on top.
 *
 * @param chartRows most categories one chart draws (a chart asks for this many and says when it was cut)
 * @param tableRows most rows a table or matrix shows
 * @param slicerValues most distinct values a slicer lists
 * @param showRows most rows "Show the rows" returns
 * @param topCandidates most values a top-N filter ranks before it keeps its N
 */
@ConfigurationProperties("drishti.bi.report")
public record BiReportProperties(Integer chartRows, Integer tableRows, Integer slicerValues, Integer showRows, Integer topCandidates) {

    public BiReportProperties {
        chartRows = chartRows == null || chartRows <= 0 ? 500 : chartRows;
        tableRows = tableRows == null || tableRows <= 0 ? 1000 : tableRows;
        slicerValues = slicerValues == null || slicerValues <= 0 ? 1000 : slicerValues;
        showRows = showRows == null || showRows <= 0 ? 1000 : showRows;
        topCandidates = topCandidates == null || topCandidates <= 0 ? 10_000 : topCandidates;
    }

    public static BiReportProperties defaults() {
        return new BiReportProperties(null, null, null, null, null);
    }
}
