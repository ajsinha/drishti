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
 * CSV of an export: UTF-8 with a byte-order mark (spreadsheets pick the encoding), CRLF line ends, quotes where needed. Text a spreadsheet
 * would run as a formula ({@code =}, {@code +}, {@code -}, {@code @} first) gets a leading apostrophe so it stays text. A document with
 * several sections writes each under its title, separated by a blank line.
 */
public final class CsvWriter {

    private CsvWriter() {}

    public static byte[] write(ExportDoc doc) {
        StringBuilder b = new StringBuilder("﻿");
        boolean several = doc.sections().size() > 1;
        for (int i = 0; i < doc.sections().size(); i++) {
            ExportDoc.Section s = doc.sections().get(i);
            if (i > 0) {
                b.append("\r\n");
            }
            if (several) {
                line(b, List.of(s.title()));
            }
            line(b, s.columns());
            for (List<Object> row : s.rows()) {
                line(b, row.stream().map(CsvWriter::cell).toList());
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

    private static void line(StringBuilder b, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(quote(String.valueOf(cells.get(i))));
        }
        b.append("\r\n");
    }

    static String quote(String raw) {
        String s = raw;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !isNumber(s)) {
            s = "'" + s;
        }
        return s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
