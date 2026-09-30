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
package com.ash.drishti.engine.view;

import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.LinkView;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Kind-specific panel content. One record per panel kind (some kinds share one). */
public sealed interface PanelData {

    /** Label/value fields for {@code kv}, {@code status} and {@code provenance}. */
    record Fields(List<Cell> fields) implements PanelData {}

    /**
     * {@code table} and {@code ladder}.
     *
     * @param columns header labels
     * @param numeric per column, whether it is right-aligned
     * @param rows the rows
     * @param total total row, or null
     * @param more "N more trades", or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Table(List<String> columns, List<Boolean> numeric, List<Row> rows, Row total, String more) implements PanelData {}

    /**
     * @param cells one per column
     * @param highlight draw this row highlighted
     * @param path document path of the row, for live patches
     */
    record Row(List<Cell> cells, boolean highlight, String path) {}

    /**
     * {@code tabs}.
     *
     * @param layout {@code tabs} or {@code columns}
     * @param tabs one per element
     */
    record Tabs(String layout, List<Tab> tabs) implements PanelData {}

    record Tab(String title, List<Cell> fields) {}

    /**
     * {@code line} and {@code area}.
     *
     * @param x axis labels
     * @param series one or more series
     * @param mark x label to highlight, or null
     * @param markText caption for the mark ({@code 5Y point 3.60%}), or null
     * @param limit horizontal limit line value, or null
     * @param limitLabel caption for the limit
     * @param source the entity the points came from, or null
     * @param unit axis unit
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Chart(List<String> x, List<Series> series, String mark, String markText, Double limit, String limitLabel,
            LinkView source, String unit) implements PanelData {}

    record Series(String label, List<Double> values, String tone) {}

    /** {@code hbar}: bars scaled to the largest absolute value. */
    record Bars(List<Bar> bars) implements PanelData {}

    record Bar(String label, double value, String text, String tone) {}

    /** {@code links}. */
    record Links(List<LinkItem> links) implements PanelData {}

    /**
     * @param label {@code Netting set}
     * @param text the target id or name
     * @param link the target
     * @param badge short summary from the target ({@code EE 4.1m})
     * @param status {@code resolved}, {@code pending} or {@code missing}
     */
    record LinkItem(String label, String text, LinkView link, String badge, String status) {}

    /** {@code markdown}. */
    record Text(String text) implements PanelData {}

    /**
     * {@code surface}: {@code z[row][column]} over {@code x} (the columns' labels) and {@code y} (one per row);
     * null where the document has no number. {@code view} is {@code heatmap} (default) or {@code 3d}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Surface(List<String> x, List<String> y, List<List<Double>> z, Double min, Double max, String fmt, String unit, String view)
            implements PanelData {}

    /** {@code gauge}. */
    record Gauge(double value, double max, String text, String label) implements PanelData {}
}
