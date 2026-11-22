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

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV of an export (the records are written by Drishti Tools' tools-csv; this class lays an {@link ExportDoc} out as records): UTF-8 with a byte-order mark (spreadsheets pick the encoding), CRLF line ends, quotes where needed. Text a spreadsheet
 * would run as a formula ({@code =}, {@code +}, {@code -}, {@code @} first) gets a leading apostrophe so it stays text. A document with
 * several sections writes each under its title, separated by a blank line.
 */
public final class ExportCsv {

    private ExportCsv() {}

    public static byte[] write(ExportDoc doc) {
        StringBuilder b = new StringBuilder();
        com.ash.tools.csv.CsvWriter out = com.ash.tools.csv.CsvWriter.to(b).bom().formulaGuard(true);
        boolean several = doc.sections().size() > 1;
        for (int i = 0; i < doc.sections().size(); i++) {
            ExportDoc.Section s = doc.sections().get(i);
            if (i > 0) {
                b.append("\r\n");
            }
            if (several) {
                out.row(s.title());
            }
            out.row(s.columns());
            for (List<Object> row : s.rows()) {
                List<String> cells = new java.util.ArrayList<>();
                for (int c = 0; c < row.size(); c++) {
                    cells.add(cell(s.shown(row, c)));                      // a masked cell reads as on the screen and in Excel
                }
                out.row(cells);
            }
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue()) : d.toString();
        }
        return v.toString();
    }
}
