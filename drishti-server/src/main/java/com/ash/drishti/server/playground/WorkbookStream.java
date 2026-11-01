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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A playground workbook written as it is made (VAR_PLAYGROUND.md section 5): straight into the zip on the response's stream, sheet after
 * sheet and row after row, so a workbook of ten thousand positions is never held whole on the server. The template gives the look and the
 * document properties; the generator gives each sheet ({@link #sheet}) and the defined names ({@link #name}). Excel is asked to recalculate
 * on open ({@code fullCalcOnLoad}); every formula also carries its cached result, so a viewer that never recalculates shows the numbers.
 */
public final class WorkbookStream implements AutoCloseable {

    static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    static final String MAIN = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    private final ZipOutputStream zip;
    private final Writer writer;
    private final PlaygroundTemplate template;
    private final Map<String, String> values;
    private final List<String> sheets = new ArrayList<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private SheetStream open;
    private boolean closed;

    /**
     * @param out where the .xlsx goes (not closed here)
     * @param values the template's placeholders ({@link PlaygroundTemplate#brandingValues} and the export's own: TITLE, SUBJECT, USER, NOW)
     */
    public WorkbookStream(OutputStream out, PlaygroundTemplate template, Map<String, String> values) {
        this.zip = new ZipOutputStream(new java.io.FilterOutputStream(out) {
            @Override
            public void close() throws IOException {
                flush();                                              // the response's stream stays open for the container
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }
        }, StandardCharsets.UTF_8);
        this.zip.setLevel(Deflater.BEST_SPEED);
        this.writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FilterOutputStream(zip) {
            @Override
            public void close() {
                // the zip is closed by the workbook
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                zip.write(b, off, len);
            }
        }, StandardCharsets.UTF_8), 1 << 16);
        this.template = template;
        this.values = Map.copyOf(values);
    }

    public PlaygroundTemplate template() {
        return template;
    }

    /** The next sheet (the previous one is finished first); {@code tab} a tab colour ({@code 1F3A5F}) or null. */
    public SheetStream sheet(String name, String tab) {
        finishSheet();
        sheets.add(name);
        try {
            writer.flush();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + sheets.size() + ".xml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        open = new SheetStream(writer, name, tab, template);
        return open;
    }

    /** A workbook-level defined name: {@code Confidence} → {@code Main!$B$5}. */
    public void name(String name, String sheet, String ref) {
        names.put(name, quote(sheet) + "!" + ref);
    }

    /** A sheet's name as a reference writes it ({@code Main}, {@code 'Rest of scope'}). */
    public static String quote(String sheet) {
        return sheet.matches("[A-Za-z_][A-Za-z0-9_]*") ? sheet : "'" + sheet.replace("'", "''") + "'";
    }

    private void finishSheet() {
        if (open != null) {
            open.finish(values);
            try {
                writer.flush();
                zip.closeEntry();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            open = null;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        finishSheet();
        try {
            part("[Content_Types].xml", contentTypes());
            part("_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL + "officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"" + REL + "extended-properties\" Target=\"docProps/app.xml\"/></Relationships>");
            part("xl/workbook.xml", workbook());
            part("xl/_rels/workbook.xml.rels", workbookRels());
            part("xl/styles.xml", template.part("xl/styles.xml", values));
            part("docProps/core.xml", template.part("docProps/core.xml", values));
            part("docProps/app.xml", template.part("docProps/app.xml", values));
            writer.flush();
            zip.finish();
            zip.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void part(String name, String body) throws IOException {
        writer.flush();
        zip.putNextEntry(new ZipEntry(name));
        writer.write(body);
        writer.flush();
        zip.closeEntry();
    }

    private String contentTypes() {
        StringBuilder b = new StringBuilder(XML).append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
                .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
                .append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
        for (int i = 1; i <= sheets.size(); i++) {
            b.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return b.append("</Types>").toString();
    }

    private String workbook() {
        StringBuilder b = new StringBuilder(XML).append("<workbook ").append(MAIN)
                .append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            b.append("<sheet name=\"").append(XlsxWriter.esc(sheets.get(i))).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        b.append("</sheets>");
        if (!names.isEmpty()) {
            b.append("<definedNames>");
            names.forEach((n, ref) -> b.append("<definedName name=\"").append(n).append("\">").append(XlsxWriter.esc(ref)).append("</definedName>"));
            b.append("</definedNames>");
        }
        return b.append("<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>").toString();
    }

    private String workbookRels() {
        StringBuilder b = new StringBuilder(XML).append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheets.size(); i++) {
            b.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(REL).append("worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        return b.append("<Relationship Id=\"rId").append(sheets.size() + 1).append("\" Type=\"").append(REL).append("styles\" Target=\"styles.xml\"/></Relationships>")
                .toString();
    }
}
