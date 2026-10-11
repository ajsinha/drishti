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
 * @param perspectiveRows most rows a Perspective grid or pivot is sent (the browser pivots them)
 * @param distributionRows most members a histogram or box plot reads (the server bins or summarises them; the picture is a few dozen rows)
 * @param totalsMaxRows most rows one query for a true total (a matrix subtotal level or a table total) may return
 * @param pythonRows most rows a python visual hands the Python runner (the runner has its own size limit as well)
 * @param pythonPackages packages a Python item may list in its manifest (keep it equal to the console's bi.python.packages, which is what the runner enforces)
 * @param pythonMaxSeconds largest timeout a Python manifest may ask for
 * @param pythonMaxMemoryMb largest memory a Python manifest may ask for, in megabytes
 */
@ConfigurationProperties("drishti.bi.report")
public record BiReportProperties(Integer chartRows, Integer tableRows, Integer slicerValues, Integer showRows, Integer topCandidates, Integer perspectiveRows,
        Integer distributionRows, Integer totalsMaxRows, Integer pythonRows, java.util.List<String> pythonPackages, Integer pythonMaxSeconds, Integer pythonMaxMemoryMb) {

    public BiReportProperties {
        chartRows = chartRows == null || chartRows <= 0 ? 500 : chartRows;
        tableRows = tableRows == null || tableRows <= 0 ? 1000 : tableRows;
        slicerValues = slicerValues == null || slicerValues <= 0 ? 1000 : slicerValues;
        showRows = showRows == null || showRows <= 0 ? 1000 : showRows;
        topCandidates = topCandidates == null || topCandidates <= 0 ? 10_000 : topCandidates;
        perspectiveRows = perspectiveRows == null || perspectiveRows <= 0 ? 20_000 : perspectiveRows;
        distributionRows = distributionRows == null || distributionRows <= 0 ? 20_000 : distributionRows;
        totalsMaxRows = totalsMaxRows == null || totalsMaxRows <= 0 ? 2_000 : totalsMaxRows;
        pythonRows = pythonRows == null || pythonRows <= 0 ? 10_000 : pythonRows;
        pythonPackages = pythonPackages == null || pythonPackages.isEmpty() ? java.util.List.of("pandas", "numpy", "pyarrow", "scipy", "statsmodels") : java.util.List.copyOf(pythonPackages);
        pythonMaxSeconds = pythonMaxSeconds == null || pythonMaxSeconds <= 0 ? 60 : pythonMaxSeconds;
        pythonMaxMemoryMb = pythonMaxMemoryMb == null || pythonMaxMemoryMb <= 0 ? 1024 : pythonMaxMemoryMb;
    }

    public static BiReportProperties defaults() {
        return new BiReportProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
