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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a playground export of a given size is before it is made (VAR_GUIDE.md section 3.10): its sheets' largest column and row count,
 * its cells and formulas, an estimated file size and recalculation time, and the applications that can open it. A workbook no
 * application can open is refused with the reason; so is one larger than the server builds in one export.
 */
public final class ExportSizing {

    /**
     * A spreadsheet application's limits.
     *
     * @param maxCells the most cells in a whole file (0: no such limit)
     */
    public record App(String name, int maxColumns, int maxRows, long maxCells) {}

    /** The applications the export is checked against, from their published limits. */
    public static final List<App> APPS = List.of(new App("Excel", 16_384, 1_048_576, 0), new App("LibreOffice Calc 7.4 or later", 16_384, 1_048_576, 0),
            new App("LibreOffice Calc before 7.4", 1_024, 1_048_576, 0), new App("Google Sheets", 18_278, 1_048_576, 10_000_000));

    /**
     * Calibrated on headless LibreOffice runs of the VaR workbook (VarPlaygroundWorkbookTest: 500 positions, 509 scenarios, about 12 MB
     * and 10 s): compressed bytes per value and per formula cell.
     */
    static final double BYTES_PER_VALUE = 11;
    static final double BYTES_PER_FORMULA = 30;
    /** Seconds per formula cell and per comparison of a rank or window formula (a rank compares every scenario). */
    static final double SECONDS_PER_FORMULA = 3e-5;
    static final double SECONDS_PER_COMPARISON = 2e-8;

    /**
     * One export's size.
     *
     * @param items the positions as their own columns (or rows)
     * @param orientation {@code columns} or {@code rows}: how positions are laid out
     * @param apps the applications that can open it
     * @param refused why it cannot be made (null: it can)
     */
    public record Estimate(int items, String orientation, int columns, int rows, long cells, long formulas, long bytes, double recalcSeconds,
            List<String> apps, String refused) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("positions", items);
            m.put("layout", orientation.equals("columns") ? "positions as columns" : "positions as rows");
            m.put("columns", columns);
            m.put("rows", rows);
            m.put("cells", cells);
            m.put("formulas", formulas);
            m.put("bytes", bytes);
            m.put("size", size(bytes));
            m.put("recalcSeconds", Math.round(recalcSeconds * 10) / 10.0);
            m.put("recalc", recalcSeconds < 1 ? "under a second" : recalcSeconds < 90 ? Math.round(recalcSeconds) + " s" : Math.round(recalcSeconds / 60) + " min");
            m.put("apps", apps);
            m.put("refused", refused);
            return m;
        }
    }

    private ExportSizing() {}

    /**
     * @param items positions shown
     * @param columns the widest sheet's columns
     * @param rows the longest sheet's rows
     * @param values value cells (data, inputs)
     * @param formulas formula cells
     * @param comparisons the comparisons the rank and window formulas make
     * @param maxCells the most cells the server builds in one export (0: no cap)
     */
    public static Estimate estimate(int items, boolean itemsAsColumns, int columns, int rows, long values, long formulas, long comparisons,
            long maxCells) {
        long cells = values + formulas;
        long bytes = Math.round(values * BYTES_PER_VALUE + formulas * BYTES_PER_FORMULA) + 8_192;
        double secs = formulas * SECONDS_PER_FORMULA + comparisons * SECONDS_PER_COMPARISON;
        List<String> apps = new ArrayList<>();
        for (App a : APPS) {
            if (columns <= a.maxColumns() && rows <= a.maxRows() && (a.maxCells() == 0 || cells <= a.maxCells())) {
                apps.add(a.name());
            }
        }
        String refused = null;
        if (maxCells > 0 && cells > maxCells) {
            refused = String.format("%,d cells: more than the %,d one export may hold on this server (drishti.var.playground.export-max-cells)", cells, maxCells);
        } else if (apps.isEmpty()) {
            refused = String.format("%,d columns by %,d rows, %,d cells: no spreadsheet application opens it (Excel and LibreOffice: 16,384 columns "
                    + "and 1,048,576 rows; Google Sheets: 10 million cells)", columns, rows, cells);
        }
        return new Estimate(items, itemsAsColumns ? "columns" : "rows", columns, rows, cells, formulas, bytes, secs, List.copyOf(apps), refused);
    }

    /** {@code 1.2 MB}. */
    public static String size(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        }
        double mb = bytes / (1024.0 * 1024.0);
        return (mb < 10 ? String.format("%.1f", mb) : String.valueOf(Math.round(mb))) + " MB";
    }
}
