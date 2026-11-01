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
package com.ash.drishti.server.playground;

import com.ash.drishti.server.dist.XlsxWriter;
import java.util.List;

/**
 * Where a playground's items (positions) and points (scenarios) sit on a sheet (VAR_PLAYGROUND.md section 3): <b>items as columns</b>
 * (scenarios down, positions across, as the screen shows them) while the columns fit a spreadsheet, <b>items as rows</b> (positions down,
 * scenarios across) beyond that. Every formula is written through this, so the same generator serves both. Besides the items a sheet has
 * <b>series</b>: one value per point (the portfolio, its rank, the 10-day sums), after the items (columns) or above them (rows).
 *
 * <pre>
 *   items as columns                         items as rows
 *        A        B..         series         A..(heads)   points ->
 *   1    title    item heads  series titles  1  titles    point labels
 *   ..   (head lines)                        2  series    series values
 *   h+1  point 0  values      values         .. items     values
 * </pre>
 */
public final class SeriesLayout {

    /** The two orientations. */
    public enum Orientation { ITEMS_AS_COLUMNS, ITEMS_AS_ROWS }

    /** What a sheet holds at each place; the layout calls these in row order, left to right. */
    public interface Cells {
        /** The title of head line {@code line} (Position, Currency, Input form). */
        void headTitle(SheetStream s, int col, int line);

        /** Item {@code item}'s head line {@code line}. */
        void itemHead(SheetStream s, int col, int item, int line);

        /** Point {@code point}'s label (its index and date). */
        void pointLabel(SheetStream s, int col, int point);

        void seriesTitle(SheetStream s, int col, String series);

        void item(SheetStream s, int col, int item, int point);

        void series(SheetStream s, int col, String series, int point);
    }

    private final Orientation o;
    private final int items;
    private final int points;
    private final int heads;
    private final List<String> series;

    public SeriesLayout(Orientation o, int items, int points, int heads, List<String> series) {
        this.o = o;
        this.items = items;
        this.points = points;
        this.heads = Math.max(1, heads);
        this.series = List.copyOf(series);
    }

    public Orientation orientation() {
        return o;
    }

    public boolean itemsAsColumns() {
        return o == Orientation.ITEMS_AS_COLUMNS;
    }

    public int items() {
        return items;
    }

    public int points() {
        return points;
    }

    public List<String> series() {
        return series;
    }

    /** The sheet's columns and rows: what the applications' limits are checked against. */
    public int columns() {
        return itemsAsColumns() ? 1 + items + series.size() : heads + points;
    }

    public int rows() {
        return itemsAsColumns() ? heads + points : 1 + series.size() + items;
    }

    /** Zero-based column and one-based row of an item's value at a point. */
    public int itemCol(int item, int point) {
        return itemsAsColumns() ? 1 + item : heads + point;
    }

    public int itemRow(int item, int point) {
        return itemsAsColumns() ? heads + 1 + point : 2 + series.size() + item;
    }

    private int seriesIndex(String s) {
        int i = series.indexOf(s);
        if (i < 0) {
            throw new IllegalArgumentException("no series " + s);
        }
        return i;
    }

    public int seriesCol(String s, int point) {
        return itemsAsColumns() ? 1 + items + seriesIndex(s) : heads + point;
    }

    public int seriesRow(String s, int point) {
        return itemsAsColumns() ? heads + 1 + point : 2 + seriesIndex(s);
    }

    public String cell(int item, int point) {
        return XlsxWriter.col(itemCol(item, point)) + itemRow(item, point);
    }

    public String seriesCell(String s, int point) {
        return XlsxWriter.col(seriesCol(s, point)) + seriesRow(s, point);
    }

    /** An item's values over points {@code from..to}, absolute ({@code $C$4:$C$512}). */
    public String itemRange(int item, int from, int to) {
        return abs(itemCol(item, from), itemRow(item, from)) + ":" + abs(itemCol(item, to), itemRow(item, to));
    }

    public String itemRange(int item) {
        return itemRange(item, 0, points - 1);
    }

    /** One point's values across items {@code from..to} (relative: the cell's own row or column moves with it). */
    public String across(int point, int from, int to) {
        return XlsxWriter.col(itemCol(from, point)) + itemRow(from, point) + ":" + XlsxWriter.col(itemCol(to, point)) + itemRow(to, point);
    }

    /** A series over points {@code from..to}, absolute. */
    public String seriesRange(String s, int from, int to) {
        return abs(seriesCol(s, from), seriesRow(s, from)) + ":" + abs(seriesCol(s, to), seriesRow(s, to));
    }

    public String seriesRange(String s) {
        return seriesRange(s, 0, points - 1);
    }

    /** {@code $C$4}. */
    public static String abs(int col, int row) {
        return "$" + XlsxWriter.col(col) + "$" + row;
    }

    /** Writes the sheet's cells in row order. */
    public void emit(SheetStream s, Cells c) {
        if (itemsAsColumns()) {
            for (int line = 0; line < heads; line++) {
                s.row(line + 1);
                c.headTitle(s, 0, line);
                for (int i = 0; i < items; i++) {
                    c.itemHead(s, 1 + i, i, line);
                }
                if (line == 0) {
                    for (int k = 0; k < series.size(); k++) {
                        c.seriesTitle(s, 1 + items + k, series.get(k));
                    }
                }
            }
            for (int j = 0; j < points; j++) {
                s.row(heads + 1 + j);
                c.pointLabel(s, 0, j);
                for (int i = 0; i < items; i++) {
                    c.item(s, 1 + i, i, j);
                }
                for (int k = 0; k < series.size(); k++) {
                    c.series(s, 1 + items + k, series.get(k), j);
                }
            }
            return;
        }
        s.row(1);
        for (int line = 0; line < heads; line++) {
            c.headTitle(s, line, line);
        }
        for (int j = 0; j < points; j++) {
            c.pointLabel(s, heads + j, j);
        }
        for (int k = 0; k < series.size(); k++) {
            s.row(2 + k);
            c.seriesTitle(s, 0, series.get(k));
            for (int j = 0; j < points; j++) {
                c.series(s, heads + j, series.get(k), j);
            }
        }
        for (int i = 0; i < items; i++) {
            s.row(2 + series.size() + i);
            for (int line = 0; line < heads; line++) {
                c.itemHead(s, line, i, line);
            }
            for (int j = 0; j < points; j++) {
                c.item(s, heads + j, i, j);
            }
        }
    }
}
