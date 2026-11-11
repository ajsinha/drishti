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
package com.ash.drishti.bicompute.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A small streaming xlsx writer (SpreadsheetML, ECMA-376 Part 1): sheets written row by row straight into the zip being sent, inline
 * strings, numbers, formulas with their cached values, row outline levels (Excel's grouping with + and −) and a few styles. No temporary
 * file; nothing held whole but the current row.
 */
final class Xlsx implements AutoCloseable {

    /** Styles: the index in styles.xml's cellXfs. */
    static final int PLAIN = 0;
    static final int BOLD = 1;
    static final int NUM = 2;
    static final int NUM_BOLD = 3;
    static final int HEAD = 4;
    static final int NOTE = 5;
    static final int PCT = 6;
    static final int TITLE = 7;

    private final ZipOutputStream zip;
    private final Writer w;
    private final List<String> sheets = new ArrayList<>();
    private boolean inSheet;
    private int row;
    private boolean inRow;

    Xlsx(OutputStream out) {
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        this.w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
    }

    /** Starts a sheet ({@code summaryBelow}: outline summaries below their groups, as on screen). */
    void sheet(String name, boolean summaryBelow, double[] widths) throws IOException {
        endSheet();
        sheets.add(name);
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + sheets.size() + ".xml"));
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"http://schemas.openxmlformats.org/"
                + "spreadsheetml/2006/main\"><sheetPr><outlinePr summaryBelow=\"" + (summaryBelow ? 1 : 0) + "\"/></sheetPr>");
        if (widths != null && widths.length > 0) {
            w.write("<cols>");
            for (int i = 0; i < widths.length; i++) {
                w.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + widths[i] + "\" customWidth=\"1\"/>");
            }
            w.write("</cols>");
        }
        w.write("<sheetData>");
        inSheet = true;
        row = 0;
    }

    /** Starts the next row ({@code level}: its outline level, 0 for none). */
    Xlsx row(int level) throws IOException {
        endRow();
        row++;
        w.write("<row r=\"" + row + "\"" + (level > 0 ? " outlineLevel=\"" + Math.min(7, level) + "\"" : "") + ">");
        inRow = true;
        return this;
    }

    int rowNumber() {
        return row;
    }

    Xlsx text(int col, String s, int style) throws IOException {
        if (s == null) {
            return this;
        }
        w.write("<c r=\"" + ref(col, row) + "\" t=\"inlineStr\"" + st(style) + "><is><t xml:space=\"preserve\">" + esc(s) + "</t></is></c>");
        return this;
    }

    Xlsx number(int col, Double v, int style) throws IOException {
        if (v == null || v.isNaN() || v.isInfinite()) {
            return this;
        }
        w.write("<c r=\"" + ref(col, row) + "\"" + st(style) + "><v>" + num(v) + "</v></c>");
        return this;
    }

    /** A formula and its cached value (Excel recomputes it; other readers show the value). */
    Xlsx formula(int col, String f, Double cached, int style) throws IOException {
        w.write("<c r=\"" + ref(col, row) + "\"" + st(style) + "><f>" + esc(f) + "</f>" + (cached == null || cached.isNaN() ? "" : "<v>"
                + num(cached) + "</v>") + "</c>");
        return this;
    }

    private void endRow() throws IOException {
        if (inRow) {
            w.write("</row>");
            inRow = false;
        }
    }

    private void endSheet() throws IOException {
        if (inSheet) {
            endRow();
            w.write("</sheetData></worksheet>");
            w.flush();
            zip.closeEntry();
            inSheet = false;
        }
    }

    private static String st(int style) {
        return style == 0 ? "" : " s=\"" + style + "\"";
    }

    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    /** A1 reference of a zero-based column and a one-based row. */
    static String ref(int col, int row) {
        return col(col) + row;
    }

    /** The letters of a zero-based column. */
    static String col(int c) {
        StringBuilder b = new StringBuilder();
        int n = c + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            b.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return b.toString();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        b.append(ch);
                    }
                }
            }
        }
        return b.toString();
    }

    private void part(String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        w.write(xml);
        w.flush();
        zip.closeEntry();
    }

    @Override
    public void close() throws IOException {
        endSheet();
        StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas."
                + "openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-"
                + "package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" "
                + "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" "
                + "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<workbook xmlns=\"http://schemas."
                + "openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://"
                + "schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            int n = i + 1;
            ct.append("<Override PartName=\"/xl/worksheets/sheet").append(n).append(".xml\" ContentType=\"application/vnd.openxmlformats-"
                    + "officedocument.spreadsheetml.worksheet+xml\"/>");
            wb.append("<sheet name=\"").append(esc(sheets.get(i))).append("\" sheetId=\"").append(n).append("\" r:id=\"rId").append(n).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(n).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                    + "relationships/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");
        }
        rels.append("<Relationship Id=\"rId").append(sheets.size() + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
                + "2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        part("[Content_Types].xml", ct.append("</Types>").toString());
        part("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats."
                + "org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        part("xl/workbook.xml", wb.append("</sheets></workbook>").toString());
        part("xl/_rels/workbook.xml.rels", rels.toString());
        part("xl/styles.xml", STYLES);
        w.flush();
        zip.finish();
        zip.flush();
    }

    private static final String STYLES = String.join("", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n",
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">",
            "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/><numFmt numFmtId=\"165\" formatCode=\"0.00%\"/></numFmts>",
            "<fonts count=\"4\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>",
            "<font><i/><sz val=\"10\"/><color rgb=\"FF555555\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"14\"/><name val=\"Calibri\"/></font></fonts>",
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>",
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCE6F1\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>",
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>",
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>",
            "<cellXfs count=\"8\">",
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>",
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>",
            "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>",
            "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>",
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>",
            "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>",
            "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>",
            "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>",
            "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
