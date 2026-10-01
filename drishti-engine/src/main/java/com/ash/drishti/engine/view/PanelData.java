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
    /** {@code search}: the panel offers a filter box in its heading (Sutra option {@code search: false} turns it off). */
    record Table(List<String> columns, List<Boolean> numeric, List<Row> rows, Row total, String more, boolean search) implements PanelData {

        public Table(List<String> columns, List<Boolean> numeric, List<Row> rows, Row total, String more) {
            this(columns, numeric, rows, total, more, true);
        }
    }

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

    /**
     * {@code waterfall}: ordered steps as floating bars.
     *
     * @param steps in order; a total step is drawn from zero
     * @param unit axis unit, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Waterfall(List<Step> steps, String unit) implements PanelData {}

    /**
     * @param label {@code Carry}
     * @param value the signed contribution, or the level of a total
     * @param from where the bar starts (the running total before a contribution, zero for a total)
     * @param to where the bar ends (the running total after it)
     * @param text the value formatted
     * @param tone {@code pos} (up), {@code neg} (down) or {@code link} (a total)
     * @param total drawn as a full bar from zero
     */
    record Step(String label, double value, double from, double to, String text, String tone, boolean total) {}

    /**
     * {@code histogram}: the distribution of a list of numbers.
     *
     * @param bins equal-width bins from the smallest value to the largest
     * @param markers vertical lines (VaR, expected shortfall, mean)
     * @param count how many numbers were binned
     * @param dropped values left out (not numbers, or beyond the configured maximum)
     * @param fmt the format of bin edges and markers
     * @param unit axis unit
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Histogram(List<Bin> bins, List<Marker> markers, int count, int dropped, String fmt, String unit) implements PanelData {}

    /** One bin: {@code [from, to)}, the last one closed; {@code label} is its range formatted. */
    record Bin(double from, double to, int count, String label) {}

    /** A marker line at {@code value}, {@code text} formatted, {@code tone} its colour. */
    record Marker(String label, double value, String text, String tone) {}

    /**
     * {@code scatter}: one point per row.
     *
     * @param points the points
     * @param groups the distinct groups, in first-seen order (a colour each)
     * @param xLabel horizontal axis title
     * @param yLabel vertical axis title
     * @param sized whether points carry a size
     * @param more points left out beyond the configured maximum
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Scatter(List<Point> points, List<String> groups, String xLabel, String yLabel, boolean sized, int more) implements PanelData {}

    /** A point: its measures and their text, an optional size, label, group and the entity the label names. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Point(double x, double y, Double size, String xText, String yText, String label, String group, LinkView link) {}

    /**
     * {@code candlestick}: open, high, low and close by date, oldest first.
     *
     * @param candles the bars
     * @param volume whether candles carry volume
     * @param last the last close, formatted
     * @param change the change over the last bar, formatted, with its {@code tone}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Candles(List<Candle> candles, boolean volume, String last, String change, String tone, String fmt, String unit)
            implements PanelData {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Candle(String x, double open, double high, double low, double close, Double volume) {}

    /**
     * {@code graph}: nodes and the edges between them.
     *
     * @param nodes the entities
     * @param edges the relations; an edge to a node that is not listed is left out
     * @param layout {@code tree} (layered from the roots) or {@code force}
     * @param more nodes left out beyond the configured maximum
     */
    record Graph(List<Node> nodes, List<Edge> edges, String layout, int more) implements PanelData {}

    /**
     * @param id the node's identifier (the entity id when it names one)
     * @param label what the node shows
     * @param group its category (colour and legend)
     * @param link the entity it opens, or null
     * @param focus the node is the entity this view shows
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Node(String id, String label, String group, LinkView link, boolean focus) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Edge(String from, String to, String label) {}

    /** {@code timeline}: dated events, oldest first; {@code more} events left out beyond the configured maximum. */
    record Timeline(List<Event> events, int more) implements PanelData {}

    /**
     * @param date the date or timestamp as written
     * @param label {@code Confirmed}
     * @param detail a short description, or null
     * @param status the status text, or null
     * @param tone the status tone ({@code ok}, {@code warn}, {@code bad}), or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Event(String date, String label, String detail, String status, String tone) {}

    /**
     * {@code pivot}: an aggregate of the rows by one field (down) and across another.
     *
     * @param by the row field's name (the corner heading)
     * @param columns the column keys, in first-seen order
     * @param rows one per row key, in first-seen order
     * @param totals the column totals and the grand total (last), or null when totals are off
     * @param agg the aggregation
     * @param heat colour cells by value
     * @param min the smallest cell value (heat scale)
     * @param max the largest cell value
     * @param more row keys left out beyond the configured maximum
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Pivot(String by, List<String> columns, List<PivotRow> rows, List<Cell> totals, String agg, boolean heat, Double min, Double max,
            int more) implements PanelData {}

    /**
     * @param label the row key
     * @param cells one per column: the aggregate formatted and toned (text empty where no row falls)
     * @param values the aggregates (null where no row falls), for the heat scale and export
     * @param total the row total, or null when totals are off
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PivotRow(String label, List<Cell> cells, List<Double> values, Cell total) {}
}
