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

import java.util.List;

/**
 * What an export says, as plain data, before it is turned into a file: a title, the lines that say what the reader is looking at (as-of
 * date, filters, who it was made for), and sections, each a table (typed cells), a row of cards, or a chart's data. The Excel, CSV, PDF,
 * PNG and email writers all read this one shape, so the same masked values reach every format.
 */
public record ExportDoc(String title, String subtitle, List<String> notes, List<Section> sections) {

    /** How a section is drawn: {@code table}, {@code card} (one big value per measure) or {@code chart} (bars of the first measure, then the data). */
    public static final String TABLE = "table";
    public static final String CARD = "card";
    public static final String CHART = "chart";

    public ExportDoc {
        notes = notes == null ? List.of() : List.copyOf(notes);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /**
     * One table. Cells are {@code Long}, {@code Double}, {@code Boolean}, {@code java.time.LocalDate} or {@code String} (null is empty).
     *
     * @param note a line under the title: rows left out, or why the visual has no data
     * @param masked column names whose values are masks for this reader
     * @param kind the visual type a chart section came from ({@code bar}, {@code column}, {@code line}, {@code pie}, ...), blank when unknown; the Excel writer draws a native chart from it
     */
    public record Section(String id, String title, String type, List<String> columns, List<List<Object>> rows, String note, List<String> masked, String kind) {

        /** A section with no chart kind (the writers draw a chart section as bars unless a kind says otherwise). */
        public Section(String id, String title, String type, List<String> columns, List<List<Object>> rows, String note, List<String> masked) {
            this(id, title, type, columns, rows, note, masked, null);
        }

        public Section {
            kind = kind == null ? "" : kind;
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : rows;
            masked = masked == null ? List.of() : List.copyOf(masked);
        }

        /** What a cell shows: an empty cell of a column masked for this reader reads {@link ExportDoc#MASK}, in every format (as on the screen). */
        public Object shown(List<Object> row, int column) {
            Object v = column < row.size() ? row.get(column) : null;
            return (v == null || "".equals(v)) && column < columns.size() && masked.contains(columns.get(column)) ? MASK : v;
        }

        public boolean isNumeric(int column) {
            for (List<Object> r : rows) {
                if (column < r.size() && r.get(column) != null) {
                    return r.get(column) instanceof Number;
                }
            }
            return false;
        }
    }

    /** The text of a masked cell in every format (CSV, Excel, PDF, PNG, email), as the screen shows it. */
    public static final String MASK = "\u2022\u2022\u2022";

    public int rowCount() {
        return sections.stream().mapToInt(s -> s.rows().size()).sum();
    }

    /** Every string the document shows, in order (for tests of what a reader may see). */
    public String allText() {
        StringBuilder b = new StringBuilder(title).append('\n').append(subtitle == null ? "" : subtitle).append('\n');
        notes.forEach(n -> b.append(n).append('\n'));
        for (Section s : sections) {
            b.append(s.title()).append('\n').append(s.note() == null ? "" : s.note()).append('\n').append(String.join("\t", s.columns())).append('\n');
            for (List<Object> r : s.rows()) {
                for (Object c : r) {
                    b.append(c == null ? "" : c).append('\t');
                }
                b.append('\n');
            }
        }
        return b.toString();
    }
}
