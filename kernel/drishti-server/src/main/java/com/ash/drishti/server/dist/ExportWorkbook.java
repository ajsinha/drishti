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
package com.ash.drishti.server.dist;

import com.ash.tools.xlsx.Cells;
import com.ash.tools.xlsx.ChartSpec;
import com.ash.tools.xlsx.PrintSetup;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * An export ({@link ExportDoc}) as an Excel workbook: a sheet "About" (title, as-of, the filters, who it was made for) and one sheet per
 * section, with a bold frozen header row, numbers as numbers (whole and decimal formats), dates as dates, booleans as booleans and text as
 * inline strings (never a formula, whatever it starts with). A chart section also gets a native chart next to its data (bar, column, line,
 * area, pie and doughnut; any other chart visual is drawn as columns). The chart reads the sheet's own cells, so it shows exactly what the
 * sheet shows: a column that is masked for the reader is text in the sheet, so it is never a series; a section with no category column or no
 * numeric column gets no chart, and the data table is always there. The file itself is written by the shared workbook writer
 * ({@code tools-xlsx}); this class only lays an export document out on it. Sheet names are made valid and unique there.
 */
public final class ExportWorkbook {
    /** At most this many categories are plotted; a chart of more says nothing a table does not. */
    static final int MAX_POINTS = 500;
    /** At most this many series (numeric columns). */
    static final int MAX_SERIES = 6;

    private ExportWorkbook() {}

    public static byte[] write(ExportDoc doc) {
        return write(doc, null);
    }

    /** As {@link #write(ExportDoc)}, the data sheets set to print as {@code print} says (null: Excel's own defaults). */
    public static byte[] write(ExportDoc doc, PrintSetup print) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        try (Workbook wb = Workbook.to(out)) {
            about(wb, doc);
            for (ExportDoc.Section sec : doc.sections()) {
                section(wb, sec, print);
            }
        }
        return out.toByteArray();
    }

    private static void about(Workbook wb, ExportDoc doc) {
        Sheet s = wb.sheet("About").width(0, 60).width(2, 80);
        s.next().text(0, doc.title(), "title");
        if (doc.subtitle() != null && !doc.subtitle().isBlank()) {
            s.next().text(0, doc.subtitle(), "note");
        }
        for (String n : doc.notes()) {
            s.next().text(0, n, null);
        }
        s.row(s.rowNumber() + 2).text(0, "Sheet", "header").text(1, "Rows", "header").text(2, "Notes", "header");
        for (ExportDoc.Section sec : doc.sections()) {
            s.next().text(0, sec.title(), null).number(1, (long) sec.rows().size(), "int");
            if (sec.note() != null && !sec.note().isBlank()) {                   // every note the PDF draws by the band (QA3B-10)
                s.text(2, sec.note(), null);
            }
        }
    }

    private static void section(Workbook wb, ExportDoc.Section s, PrintSetup print) {
        Plan plan = plan(s);
        boolean chart = plan != null;
        Sheet sh = wb.sheet(s.title());
        sh.freeze(1, 0);
        for (int c = 0; c < s.columns().size(); c++) {
            sh.width(c, width(s, c));
        }
        // the section's note (rows left out, masked for you, not sorted) next to the table, as the PDF draws it by the band (QA3B-10): to the right of
        // the column titles, or under the data when a chart sits to the right
        String note = s.note() == null || s.note().isBlank() ? null : s.note();
        int noteCol = s.columns().size() + 1;
        if (note != null && !chart) {
            sh.width(noteCol, 80);
        }
        if (print != null) {                                                      // printed or saved as PDF, a chart and its table share one page's width
            sh.print(new PrintSetup(print.landscape(), print.paperSize(), withDistances(print.margins()), print.header(), print.footer(), chart));
            sh.repeatRows(1, 1);                                                  // each data sheet prints its column titles at the top of every page
        } else if (chart) {
            sh.print(new PrintSetup(true, 0, null, null, null, true));
        }
        sh.row(1);
        for (int c = 0; c < s.columns().size(); c++) {
            sh.text(c, s.columns().get(c), "header");
        }
        if (note != null && !chart) {
            sh.text(noteCol, note, "note");
        }
        int r = 2;
        for (List<Object> values : s.rows()) {
            sh.row(r++);
            for (int c = 0; c < values.size(); c++) {
                cell(sh, c, s.shown(values, c));
            }
        }
        if (note != null && chart) {
            sh.row(r + 1).text(0, note, "note");
        }
        if (chart) {
            ChartSpec spec = ChartSpec.of(ChartSpec.Kind.of(plan.kind()), s.title(), 0, plan.series(), 1, plan.points() + 1)
                    .at(s.columns().size() + 1, 0, 9, 20);
            sh.chart(plan.dateCategories() ? spec.dates() : spec);
        }
    }

    /** The statement's margins (top, right, bottom, left) with the header's and the footer's distance from the edge. */
    private static double[] withDistances(double[] m) {
        return new double[] {m[0], m[1], m[2], m[3], 0.2, 0.2};
    }

    private static double width(ExportDoc.Section s, int c) {
        int w = s.columns().get(c).length();
        for (int i = 0; i < Math.min(s.rows().size(), 200); i++) {
            List<Object> row = s.rows().get(i);
            if (c < row.size() && row.get(c) != null) {
                w = Math.max(w, String.valueOf(row.get(c)).length());
            }
        }
        return Math.max(8, Math.min(60, w + 2));
    }

    private static void cell(Sheet sh, int c, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof Long || v instanceof Integer) {
            sh.number(c, ((Number) v).longValue(), "int");
        } else if (v instanceof Number n) {
            sh.number(c, n.doubleValue(), "decimal");
        } else if (v instanceof Boolean b) {
            sh.bool(c, b, null);
        } else if (v instanceof LocalDate d) {
            sh.date(c, d, "date");
        } else {
            sh.text(c, v.toString(), null);
        }
    }

    /** What to draw for a section: the kind, the numeric columns (the category column is always the first) and how many points. */
    record Plan(String kind, List<Integer> series, boolean dateCategories, int points) {}

    /** The chart plan for a section, or null when it has no chart (not a chart section, no rows, no plottable column). */
    static Plan plan(ExportDoc.Section s) {
        if (!ExportDoc.CHART.equals(s.type()) || s.rows().isEmpty() || s.columns().size() < 2) {
            return null;
        }
        String kind = ChartSpec.Kind.of(s.kind()).name().toLowerCase(java.util.Locale.ROOT);
        boolean round = "pie".equals(kind) || "doughnut".equals(kind);
        List<Integer> series = new ArrayList<>();
        for (int c = 1; c < s.columns().size() && series.size() < (round ? 1 : MAX_SERIES); c++) {
            if (!s.masked().contains(s.columns().get(c)) && s.isNumeric(c)) {
                series.add(c);
            }
        }
        Object first = firstOf(s, 0);
        if (series.isEmpty() || s.isNumeric(0) && !(first instanceof LocalDate)) {
            return null;
        }
        return new Plan(kind, series, first instanceof LocalDate, Math.min(s.rows().size(), MAX_POINTS));
    }

    private static Object firstOf(ExportDoc.Section s, int column) {
        for (List<Object> r : s.rows()) {
            if (column < r.size() && r.get(column) != null) {
                return r.get(column);
            }
        }
        return null;
    }
}
