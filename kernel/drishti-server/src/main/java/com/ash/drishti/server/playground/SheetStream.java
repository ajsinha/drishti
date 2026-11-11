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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One worksheet written row by row (rows in order, cells in order within a row): the frozen panes and column widths first, then the rows,
 * then the check cells' conditional formats, the print set-up (landscape, fit to width) and the footer: the copyright and product name
 * from the branding configuration and the template's name and version on every tab.
 */
public final class SheetStream {

    private final Writer out;
    private final String name;
    private final String tab;
    private final PlaygroundTemplate template;
    private final TreeMap<Integer, Double> widths = new TreeMap<>();
    private final List<String> conditional = new ArrayList<>();
    private final List<String> merges = new ArrayList<>();
    private final List<String> validations = new ArrayList<>();
    private int freezeRows;
    private int freezeCols;
    private boolean begun;
    private final StringBuilder row = new StringBuilder(4096);
    private int rowNo;
    private long cells;

    SheetStream(Writer out, String name, String tab, PlaygroundTemplate template) {
        this.out = out;
        this.name = name;
        this.tab = tab;
        this.template = template;
    }

    public String name() {
        return name;
    }

    /** The cells written so far. */
    public long cells() {
        return cells;
    }

    /** Rows and columns kept in view while scrolling (before the first row). */
    public SheetStream freeze(int rows, int cols) {
        this.freezeRows = rows;
        this.freezeCols = cols;
        return this;
    }

    /** A column's width in characters ({@code col} 0 is A; before the first row). */
    public SheetStream width(int col, double w) {
        widths.put(col, w);
        return this;
    }

    /** Columns {@code from} to {@code to} (inclusive) all {@code w} wide. */
    public SheetStream widths(int from, int to, double w) {
        for (int c = from; c <= to; c++) {
            widths.put(c, w);
        }
        return this;
    }

    /** A drop-down list on {@code sqref} (a switch cell): only {@code options} may be typed. */
    public void list(String sqref, List<String> options) {
        validations.add("<dataValidation type=\"list\" allowBlank=\"0\" showErrorMessage=\"1\" sqref=\"" + sqref + "\"><formula1>"
                + XlsxWriter.esc("\"" + String.join(",", options) + "\"") + "</formula1></dataValidation>");
    }

    /** A merged range ({@code A1:H1}). */
    public void merge(String range) {
        merges.add(range);
    }

    /** The check cells of {@code sqref} turn green when they equal {@code pass} and red otherwise (text compared). */
    public void checks(String sqref, String pass) {
        String q = "\"" + pass.replace("\"", "\"\"") + "\"";
        conditional.add("<conditionalFormatting sqref=\"" + sqref + "\"><cfRule type=\"cellIs\" dxfId=\"" + template.dxf("pass") + "\" priority=\""
                + (conditional.size() * 2 + 1) + "\" operator=\"equal\"><formula>" + XlsxWriter.esc(q) + "</formula></cfRule><cfRule type=\"cellIs\" dxfId=\""
                + template.dxf("fail") + "\" priority=\"" + (conditional.size() * 2 + 2) + "\" operator=\"notEqual\"><formula>" + XlsxWriter.esc(q)
                + "</formula></cfRule></conditionalFormatting>");
    }

    private void begin() {
        if (begun) {
            return;
        }
        begun = true;
        StringBuilder b = new StringBuilder(WorkbookStream.XML).append("<worksheet ").append(WorkbookStream.MAIN).append("><sheetPr>");
        if (tab != null) {
            b.append("<tabColor rgb=\"FF").append(tab).append("\"/>");
        }
        b.append("<pageSetUpPr fitToPage=\"1\"/></sheetPr><sheetViews><sheetView workbookViewId=\"0\"");
        b.append(name.equals("Main") ? " tabSelected=\"1\"" : "").append('>');
        if (freezeRows > 0 || freezeCols > 0) {
            String top = XlsxWriter.col(freezeCols) + (freezeRows + 1);
            b.append("<pane").append(freezeCols > 0 ? " xSplit=\"" + freezeCols + "\"" : "").append(freezeRows > 0 ? " ySplit=\"" + freezeRows + "\"" : "")
                    .append(" topLeftCell=\"").append(top).append("\" activePane=\"").append(freezeRows > 0 && freezeCols > 0 ? "bottomRight"
                            : freezeRows > 0 ? "bottomLeft" : "topRight").append("\" state=\"frozen\"/>");
        }
        b.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"14\"/>");
        if (!widths.isEmpty()) {
            b.append("<cols>");
            widths.forEach((c, w) -> b.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(w)
                    .append("\" customWidth=\"1\"/>"));
            b.append("</cols>");
        }
        b.append("<sheetData>");
        write(b);
    }

    /** Starts row {@code r} (1-based, after the previous one); the cells follow, left to right. */
    public SheetStream row(int r) {
        begin();
        endRow();
        if (r <= rowNo) {
            throw new IllegalStateException(name + ": row " + r + " after row " + rowNo);
        }
        rowNo = r;
        row.append("<row r=\"").append(r).append("\">");
        return this;
    }

    private void endRow() {
        if (row.length() > 0) {
            row.append("</row>");
            write(row);
            row.setLength(0);
        }
    }

    public SheetStream text(int col, String s, String style) {
        if (s == null) {
            return this;
        }
        cells++;
        row.append("<c r=\"").append(XlsxWriter.col(col)).append(rowNo).append("\" t=\"inlineStr\"").append(st(style)).append("><is><t xml:space=\"preserve\">")
                .append(XlsxWriter.esc(s)).append("</t></is></c>");
        return this;
    }

    public SheetStream number(int col, double v, String style) {
        cells++;
        String ref = XlsxWriter.col(col) + rowNo;
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            row.append("<c r=\"").append(ref).append('"').append(st(style)).append("/>");
            return this;
        }
        row.append("<c r=\"").append(ref).append('"').append(st(style)).append("><v>").append(XlsxWriter.num(v)).append("</v></c>");
        return this;
    }

    /** A formula and its cached result; {@code cached} null writes no result (the reader must recalculate: the tests' own check). */
    public SheetStream formula(int col, String f, Double cached, String style) {
        cells++;
        String ref = XlsxWriter.col(col) + rowNo;
        if (cached == null) {
            row.append("<c r=\"").append(ref).append('"').append(st(style)).append("><f>").append(XlsxWriter.esc(com.ash.drishti.server.dist.XlsxFormulas.check(f)))
                    .append("</f></c>");
        } else {
            row.append(XlsxWriter.formula(ref, f, cached, template.style(style)));
        }
        return this;
    }

    /** A formula whose result is text, with its cached text (null: none). */
    public SheetStream formulaText(int col, String f, String cached, String style) {
        cells++;
        String ref = XlsxWriter.col(col) + rowNo;
        if (cached == null) {
            row.append("<c r=\"").append(ref).append('"').append(st(style)).append(" t=\"str\"><f>")
                    .append(XlsxWriter.esc(com.ash.drishti.server.dist.XlsxFormulas.check(f))).append("</f></c>");
        } else {
            row.append(XlsxWriter.formulaText(ref, f, cached, template.style(style)));
        }
        return this;
    }

    /** A styled empty cell (a band's fill across). */
    public SheetStream blank(int col, String style) {
        row.append("<c r=\"").append(XlsxWriter.col(col)).append(rowNo).append('"').append(st(style)).append("/>");
        return this;
    }

    private String st(String style) {
        int s = style == null ? 0 : template.style(style);
        return s == 0 ? "" : " s=\"" + s + "\"";
    }

    void finish(Map<String, String> values) {
        begin();
        endRow();
        StringBuilder b = new StringBuilder("</sheetData>");
        if (!merges.isEmpty()) {
            b.append("<mergeCells count=\"").append(merges.size()).append("\">");
            merges.forEach(m -> b.append("<mergeCell ref=\"").append(m).append("\"/>"));
            b.append("</mergeCells>");
        }
        conditional.forEach(b::append);
        if (!validations.isEmpty()) {
            b.append("<dataValidations count=\"").append(validations.size()).append("\">");
            validations.forEach(b::append);
            b.append("</dataValidations>");
        }
        String copyright = footerText(values.getOrDefault("COPYRIGHT", ""));
        String product = footerText(values.getOrDefault("PRODUCT", ""));
        String tpl = footerText(values.getOrDefault("TEMPLATE", "") + " " + values.getOrDefault("TEMPLATE_VERSION", ""));
        b.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.6\" header=\"0.2\" footer=\"0.25\"/>")
                .append("<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
                .append("<headerFooter><oddFooter>").append(XlsxWriter.esc("&L&8" + copyright + "&C&8" + product + " · " + footerText(name)
                        + "&R&8Template " + tpl + " · page &P of &N")).append("</oddFooter></headerFooter></worksheet>");
        write(b);
    }

    /** A footer's text with Excel's codes neutralised ({@code &} doubled). */
    private static String footerText(String s) {
        return s.replace("&", "&&");
    }

    private void write(CharSequence s) {
        try {
            out.append(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
