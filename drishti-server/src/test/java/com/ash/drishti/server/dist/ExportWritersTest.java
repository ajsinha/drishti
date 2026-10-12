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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.collab.snapshot.SnapshotModel;
import com.ash.drishti.server.collab.snapshot.SnapshotPainter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ExportWritersTest {

    private static ExportDoc doc(int rows) {
        List<List<Object>> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(java.util.Arrays.asList("Desk " + i, (long) (i * 1000 + 5), i * 1.5 + 0.25, LocalDate.of(2026, 10, 9), i % 2 == 0, null));
        }
        data.add(java.util.Arrays.asList("=HYPERLINK(\"http://evil\")", -7L, 0.5, null, true, "a & <b>"));
        ExportDoc.Section s = new ExportDoc.Section("t", "Trades: by desk / 1", ExportDoc.CHART, List.of("Desk", "Notional", "Rate", "Date", "Flag", "Note"), data,
                "Masked for you: Note.", List.of("Note"));
        ExportDoc.Section c = new ExportDoc.Section("c", "Total", ExportDoc.CARD, List.of("MTM"), List.of(List.of(-412580.5)), null, List.of());
        return new ExportDoc("Daily risk", "Page: Overview · Data as of 2026-10-09", List.of("Slicer desk: FX, Rates", "Made for alice"), List.of(s, c));
    }

    private static Map<String, String> unzip(byte[] xlsx) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void excelHasAnAboutSheetAndOneTypedSheetPerSection() throws IOException {
        Map<String, String> parts = unzip(XlsxWriter.write(doc(3)));
        assertThat(parts).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml");
        assertThat(parts.get("xl/workbook.xml")).contains("name=\"About\"").contains("name=\"Trades  by desk   1\"").contains("name=\"Total\"");
        String sheet = parts.get("xl/worksheets/sheet2.xml");
        assertThat(sheet).contains("state=\"frozen\"")                               // header row stays in view
                .contains("<c r=\"B2\" s=\"2\"><v>5</v></c>")                         // a whole number is a number
                .contains("<c r=\"C3\" s=\"3\"><v>1.75</v></c>")                      // a decimal is a number
                .contains("<c r=\"D2\" s=\"4\"><v>" + java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.of(2026, 10, 9)) + "</v></c>")  // an Excel date serial
                .contains("<c r=\"E2\" t=\"b\"><v>1</v></c>")                         // a boolean is a boolean
                .doesNotContain("<f>");                                              // never a formula
        assertThat(sheet).contains("t=\"inlineStr\"").contains("=HYPERLINK").contains("a &amp; &lt;b&gt;");
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("Daily risk").contains("Slicer desk: FX, Rates").contains("Trades: by desk / 1");
    }

    @Test
    void sheetNamesAreValidAndUnique() {
        java.util.Set<String> used = new java.util.HashSet<>();
        assertThat(XlsxWriter.unique("A/B:C*D?[E]", used)).isEqualTo("A B C D  E");
        assertThat(XlsxWriter.unique("a b c d  e", used)).isEqualTo("a b c d  e (2)");
        assertThat(XlsxWriter.unique("x".repeat(40), used)).hasSize(31);
        assertThat(XlsxWriter.unique("", used)).isEqualTo("Sheet");
        assertThat(XlsxWriter.col(0)).isEqualTo("A");
        assertThat(XlsxWriter.col(26)).isEqualTo("AA");
    }

    @Test
    void csvKeepsFormulasAsTextAndQuotesWhatNeedsIt() {
        String csv = new String(CsvWriter.write(doc(1)), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿").contains("Trades: by desk / 1\r\n").contains("Desk,Notional,Rate,Date,Flag,Note\r\n")
                .contains("'=HYPERLINK(\"\"http://evil\"\")").contains("-7").contains("a & <b>");
        assertThat(csv).contains("Total\r\nMTM\r\n-412580.5");
    }

    @Test
    void pdfIsPagedWithTheRunningHeaderAndKeepsRowsInsideThePages() throws IOException {
        PdfWriter.Geometry g = PdfWriter.Geometry.of(true, 1000);
        SnapshotModel model = new DocLayout(g.width(), g.slice(), 500, 0).build(doc(160));
        byte[] pdf = PdfWriter.write(model, g, "Daily risk", "Data as of 2026-10-09", "Made for alice");
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(raw).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        int pages = model.height() / g.slice();
        assertThat(pages).isGreaterThan(1);
        assertThat(raw).contains("/Count " + pages);
        String text = pageText(pdf);
        assertThat(text).contains("Daily risk").contains("Data as of 2026-10-09").contains("Made for alice").contains("Page 1 of " + pages).contains("Page " + pages + " of " + pages)
                .contains("Desk 100").contains("Masked for you: Note.");
        // no row is split by a page edge: every text baseline lies inside one slice with room for its height
        for (SnapshotModel.Item i : model.items()) {
            if (i instanceof SnapshotModel.Text t && t.size() <= 13) {
                assertThat(t.y() % g.slice()).as(t.text()).isGreaterThan(t.size() - 1);
            }
        }
        // the table header comes back on the page a table continues onto
        assertThat(text.split("Notional", -1).length - 1).isGreaterThanOrEqualTo(2);
    }

    /** The text of every page's content stream (the strings in Tj operators), inflated. */
    private static String pageText(byte[] pdf) throws IOException {
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();
        int at = 0;
        while ((at = raw.indexOf("stream\n", at)) >= 0) {
            int end = raw.indexOf("\nendstream", at);
            byte[] packed = raw.substring(at + 7, end).getBytes(StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(packed))) {
                in.transferTo(o);
            }
            out.append(o.toString(StandardCharsets.ISO_8859_1));
            at = end + 10;
        }
        return out.toString();
    }

    @Test
    void aLineChartIsDrawnAsLinesOverTheWholeSeriesAndItsTableIsCapped() throws IOException {
        List<List<Object>> series = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            series.add(List.of("2026-01-" + i, Math.sin(i / 20.0) * 100));
        }
        ExportDoc.Section line = new ExportDoc.Section("l", "MTM over time", ExportDoc.CHART, List.of("Day", "MTM"), series, null, List.of(), "line");
        ExportDoc d = new ExportDoc("Daily risk", "", List.of(), List.of(line));
        PdfWriter.Geometry g = PdfWriter.Geometry.of(true, 1000);
        SnapshotModel model = new DocLayout(g.width(), g.slice(), 500, 15, 0).build(d);
        long segments = model.items().stream().filter(i -> i instanceof SnapshotModel.Line).count();
        assertThat(segments).as("a polyline over the sampled series, not a 12-bar chart").isGreaterThan(150);
        assertThat(model.allText()).contains("285 more rows in the Excel/CSV export");
        assertThat(model.height() / g.slice()).as("one page, not fifteen").isEqualTo(1);
    }

    @Test
    void pngIsDrawnAndALongTableIsCutAtTheHeightLimitWithASaying() throws IOException {
        SnapshotModel tall = new DocLayout(1000, 0, 500, 700).build(doc(100));
        assertThat(tall.height()).isLessThanOrEqualTo(700);
        assertThat(tall.allText()).contains("height limit");
        byte[] png = new SnapshotPainter().png(new DocLayout(1000, 0, 5, 12000).build(doc(40)));
        assertThat(png).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        assertThat(new DocLayout(1000, 0, 5, 12000).build(doc(40)).allText()).contains("more rows in the Excel/CSV export");
    }

    @Test
    void anEmailBodyEscapesEverythingAndCutsLongTables() {
        ExportDoc d = new ExportDoc("<b>Title</b>", "Data as of 2026-10-09", List.of("x\"y"),
                List.of(new ExportDoc.Section("s", "T & U", ExportDoc.TABLE, List.of("A<"), List.of(List.of("<script>"), List.of("two"), List.of("three")), null, List.of())));
        String html = DocHtml.html(d, 2);
        assertThat(html).doesNotContain("<script>").doesNotContain("<b>Title").contains("&lt;script&gt;").contains("T &amp; U").contains("1 more rows");
        assertThat(DocHtml.text(d, 2)).contains("1 more rows").doesNotContain("three");
        assertThat(DocHtml.summaryHtml(d, "x.pdf")).contains("attached").contains("x.pdf").contains("3 rows");
    }

    @Test
    void aMaskedCardSaysMaskedInsteadOfShowingAnEmptyValue() {
        ExportDoc d = new ExportDoc("Risk", "Data as of 2026-10-09", List.of(),
                List.of(new ExportDoc.Section("m", "Mark to market", ExportDoc.CARD, List.of("MTM"), List.of(java.util.Collections.singletonList(null)), "Masked for you: MTM.", List.of("MTM")),
                        new ExportDoc.Section("n", "Notional", ExportDoc.CARD, List.of("Notional"), List.of(List.of(234000000L)), null, List.of())));
        assertThat(DocHtml.html(d, 5)).contains(ExportDoc.MASK).contains("234,000,000");
        assertThat(DocHtml.text(d, 5)).contains("MTM: " + ExportDoc.MASK).contains("Notional: 234,000,000").doesNotContain(" | ");
        assertThat(new DocLayout(1000, 0, 50, 5000).build(d).allText()).contains(ExportDoc.MASK);
    }

    @Test
    void shownNumbersBecomeNumbersButIdentifiersKeepTheirZeros() {
        assertThat(ViewExporter.typed("−412,580")).isEqualTo(-412580L);
        assertThat(ViewExporter.typed("4.25%")).isEqualTo(0.0425);
        assertThat(ViewExporter.typed("1,234.50")).isEqualTo(1234.5);
        assertThat(ViewExporter.typed("00123")).isEqualTo("00123");
        assertThat(ViewExporter.typed("T-1001")).isEqualTo("T-1001");
        assertThat(ViewExporter.typed(null)).isNull();
    }

    @Test
    void theFileNameIsTheTitleAndTheDateWithoutOddCharacters() {
        assertThat(ContentExporter.filename("Daily risk / FX: \"all\"", "2026-10-09", ContentExporter.Format.PDF)).isEqualTo("Daily risk - FX- -all- 2026-10-09.pdf");
        assertThat(ContentExporter.filename("", null, ContentExporter.Format.XLSX)).isEqualTo("export.xlsx");
    }
}
