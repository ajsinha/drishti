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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An Excel workbook (.xlsx, Office Open XML) written with the JDK's zip and nothing else: a sheet "About" (title, as-of, the filters, who
 * it was made for) and one sheet per section, with a bold frozen header row, numbers as numbers (whole and decimal formats), dates as dates,
 * booleans as booleans and text as inline strings (never a formula, whatever it starts with). A chart section also gets a native Excel chart
 * ({@link XlsxCharts}) next to its data. Sheet names are made valid and unique.
 */
public final class XlsxWriter {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String MAIN = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
    // cell styles: 0 normal, 1 header, 2 whole number, 3 decimal, 4 date, 5 title, 6 grey note
    private static final int S_HEAD = 1;
    private static final int S_INT = 2;
    private static final int S_DEC = 3;
    private static final int S_DATE = 4;
    private static final int S_TITLE = 5;
    private static final int S_NOTE = 6;

    private XlsxWriter() {}

    /**
     * How the data sheets print (a statement's paper, 2.6.0): orientation, the Excel paper size code, margins in inches (top, right, bottom, left), the header
     * and footer in Excel's codes ({@code &L}, {@code &C}, {@code &R}, {@code &P} page, {@code &N} pages, {@code &D} date), and the column titles repeated on
     * every printed page.
     */
    public record PrintSetup(boolean landscape, int paperSize, double[] margins, String header, String footer) {

        public PrintSetup {
            margins = margins.clone();
        }

        String xml(boolean chart) {
            return "<pageMargins left=\"" + inch(margins[3]) + "\" right=\"" + inch(margins[1]) + "\" top=\"" + inch(margins[0]) + "\" bottom=\"" + inch(margins[2])
                    + "\" header=\"0.2\" footer=\"0.2\"/><pageSetup paperSize=\"" + paperSize + "\" orientation=\"" + (landscape ? "landscape" : "portrait") + "\""
                    + (chart ? " fitToHeight=\"0\"" : "") + "/><headerFooter><oddHeader>" + esc(header) + "</oddHeader><oddFooter>" + esc(footer) + "</oddFooter></headerFooter>";
        }

        private static String inch(double v) {
            return String.format(java.util.Locale.ROOT, "%.2f", v);
        }
    }

    public static byte[] write(ExportDoc doc) {
        return write(doc, null);
    }

    /** As {@link #write(ExportDoc)}, the data sheets set to print as {@code print} says (null: Excel's own defaults). */
    public static byte[] write(ExportDoc doc, PrintSetup print) {
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        names.add(unique("About", used));
        for (ExportDoc.Section s : doc.sections()) {
            names.add(unique(s.title(), used));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            List<XlsxCharts.Plan> plans = new ArrayList<>();
            for (ExportDoc.Section sec : doc.sections()) {
                plans.add(XlsxCharts.plan(sec));
            }
            entry(z, "[Content_Types].xml", contentTypes(names.size(), plans));
            entry(z, "_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL + "officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(z, "xl/workbook.xml", workbook(names, print != null));
            entry(z, "xl/_rels/workbook.xml.rels", workbookRels(names.size()));
            entry(z, "xl/styles.xml", styles());
            entry(z, "xl/worksheets/sheet1.xml", about(doc));
            for (int i = 0; i < doc.sections().size(); i++) {
                XlsxCharts.Plan plan = plans.get(i);
                int n = i + 2;
                entry(z, "xl/worksheets/sheet" + n + ".xml", sheet(doc.sections().get(i), plan != null, print));
                if (plan != null) {                                       // the chart sits next to the data: sheet -> drawing -> chart
                    entry(z, "xl/worksheets/_rels/sheet" + n + ".xml.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"" + REL + "drawing\" Target=\"../drawings/drawing" + n + ".xml\"/></Relationships>");
                    entry(z, "xl/drawings/drawing" + n + ".xml", XlsxCharts.drawing(doc.sections().get(i).columns().size() + 1));
                    entry(z, "xl/drawings/_rels/drawing" + n + ".xml.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"" + REL + "chart\" Target=\"../charts/chart" + n + ".xml\"/></Relationships>");
                    entry(z, "xl/charts/chart" + n + ".xml", XlsxCharts.chart(doc.sections().get(i), plan, names.get(n - 1)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ---- package parts -------------------------------------------------------------------------------------------

    private static void entry(ZipOutputStream z, String name, String body) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(body.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String contentTypes(int sheets, List<XlsxCharts.Plan> plans) {
        StringBuilder b = new StringBuilder(XML).append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheets; i++) {
            b.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i) != null) {
                b.append("<Override PartName=\"/xl/drawings/drawing").append(i + 2).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>")
                        .append("<Override PartName=\"/xl/charts/chart").append(i + 2).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>");
            }
        }
        return b.append("</Types>").toString();
    }

    private static String workbook(List<String> names, boolean printTitles) {
        StringBuilder b = new StringBuilder(XML).append("<workbook ").append(MAIN).append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < names.size(); i++) {
            b.append("<sheet name=\"").append(esc(names.get(i))).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        b.append("</sheets>");
        if (printTitles && names.size() > 1) {                                  // each data sheet prints its column titles at the top of every page
            b.append("<definedNames>");
            for (int i = 1; i < names.size(); i++) {
                b.append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"").append(i).append("\">'").append(esc(names.get(i).replace("'", "''"))).append("'!$1:$1</definedName>");
            }
            b.append("</definedNames>");
        }
        return b.append("</workbook>").toString();
    }

    private static String workbookRels(int sheets) {
        StringBuilder b = new StringBuilder(XML).append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheets; i++) {
            b.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(REL).append("worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        return b.append("<Relationship Id=\"rId").append(sheets + 1).append("\" Type=\"").append(REL).append("styles\" Target=\"styles.xml\"/></Relationships>").toString();
    }

    private static String styles() {
        return XML + "<styleSheet " + MAIN + ">"
                + "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00##\"/><numFmt numFmtId=\"165\" formatCode=\"yyyy\\-mm\\-dd\"/></numFmts>"
                + "<fonts count=\"4\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"16\"/><name val=\"Calibri\"/></font><font><sz val=\"10\"/><color rgb=\"FF6B7686\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEEF1F5\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"7\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                + "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
    }

    // ---- sheets --------------------------------------------------------------------------------------------------

    private static String about(ExportDoc doc) {
        StringBuilder rows = new StringBuilder();
        int r = 1;
        rows.append(row(r, text("A", r, doc.title(), S_TITLE)));
        r++;
        if (doc.subtitle() != null && !doc.subtitle().isBlank()) {
            rows.append(row(r, text("A", r, doc.subtitle(), S_NOTE)));
            r++;
        }
        for (String n : doc.notes()) {
            rows.append(row(r, text("A", r, n, 0)));
            r++;
        }
        r++;
        rows.append(row(r, text("A", r, "Sheet", S_HEAD) + text("B", r, "Rows", S_HEAD)));
        r++;
        for (ExportDoc.Section s : doc.sections()) {
            rows.append(row(r, text("A", r, s.title(), 0) + number("B", r, s.rows().size(), S_INT)));
            r++;
        }
        return XML + "<worksheet " + MAIN + "><cols><col min=\"1\" max=\"1\" width=\"60\" customWidth=\"1\"/></cols><sheetData>" + rows + "</sheetData></worksheet>";
    }

    private static String sheet(ExportDoc.Section s, boolean chart, PrintSetup print) {
        StringBuilder b = new StringBuilder(XML).append("<worksheet ").append(MAIN).append(chart ? " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" : "").append('>');
        if (chart) {
            b.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");                       // printed or saved as PDF, the table and its chart share one page's width
        }
        b.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        b.append("<cols>");
        for (int c = 0; c < s.columns().size(); c++) {
            b.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(width(s, c)).append("\" customWidth=\"1\"/>");
        }
        b.append("</cols><sheetData>");
        StringBuilder head = new StringBuilder();
        for (int c = 0; c < s.columns().size(); c++) {
            head.append(text(col(c), 1, s.columns().get(c), S_HEAD));
        }
        b.append(row(1, head.toString()));
        int r = 2;
        for (List<Object> values : s.rows()) {
            StringBuilder cells = new StringBuilder();
            for (int c = 0; c < values.size(); c++) {
                cells.append(cell(col(c), r, s.shown(values, c)));
            }
            b.append(row(r++, cells.toString()));
        }
        b.append("</sheetData>");
        if (print != null) {
            b.append(print.xml(chart));
        } else if (chart) {
            b.append("<pageSetup orientation=\"landscape\" fitToHeight=\"0\"/>");
        }
        return b.append(chart ? "<drawing r:id=\"rId1\"/>" : "").append("</worksheet>").toString();
    }

    private static int width(ExportDoc.Section s, int c) {
        int w = s.columns().get(c).length();
        for (int i = 0; i < Math.min(s.rows().size(), 200); i++) {
            List<Object> row = s.rows().get(i);
            if (c < row.size() && row.get(c) != null) {
                w = Math.max(w, String.valueOf(row.get(c)).length());
            }
        }
        return Math.max(8, Math.min(60, w + 2));
    }

    private static String cell(String col, int r, Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Long || v instanceof Integer) {
            return number(col, r, ((Number) v).longValue(), S_INT);
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? "" : "<c r=\"" + col + r + "\" s=\"" + S_DEC + "\"><v>" + d + "</v></c>";
        }
        if (v instanceof Boolean b) {
            return "<c r=\"" + col + r + "\" t=\"b\"><v>" + (b ? 1 : 0) + "</v></c>";
        }
        if (v instanceof LocalDate d) {
            return "<c r=\"" + col + r + "\" s=\"" + S_DATE + "\"><v>" + ChronoUnit.DAYS.between(EPOCH, d) + "</v></c>";
        }
        return text(col, r, v.toString(), 0);
    }

    private static String number(String col, int r, long v, int style) {
        return "<c r=\"" + col + r + "\" s=\"" + style + "\"><v>" + v + "</v></c>";
    }

    private static String text(String col, int r, String s, int style) {
        return "<c r=\"" + col + r + "\" t=\"inlineStr\"" + (style == 0 ? "" : " s=\"" + style + "\"") + "><is><t xml:space=\"preserve\">" + esc(s) + "</t></is></c>";
    }

    private static String row(int r, String cells) {
        return "<row r=\"" + r + "\">" + cells + "</row>";
    }

    /** Column letters: 0 is A, 25 is Z, 26 is AA. */
    static String col(int index) {
        StringBuilder b = new StringBuilder();
        for (int n = index; n >= 0; n = n / 26 - 1) {
            b.insert(0, (char) ('A' + n % 26));
        }
        return b.toString();
    }

    /** A valid, unique sheet name: at most 31 characters, none of {@code [ ] : * ? / \}. */
    static String unique(String wanted, Set<String> used) {
        String base = (wanted == null || wanted.isBlank() ? "Sheet" : wanted).replaceAll("[\\[\\]:*?/\\\\]", " ").strip();
        base = base.isEmpty() ? "Sheet" : base.length() > 31 ? base.substring(0, 31).strip() : base;
        String name = base;
        for (int i = 2; !used.add(name.toLowerCase(Locale.ROOT)); i++) {
            String suffix = " (" + i + ")";
            name = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
        }
        return name;
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        b.append(c);                                  // other control characters are not valid XML
                    }
                }
            }
        }
        return b.toString();
    }
}
