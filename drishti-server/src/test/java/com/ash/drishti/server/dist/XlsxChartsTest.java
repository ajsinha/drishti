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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/** Native Excel charts in the .xlsx: the parts exist and are well-formed XML, point at the data sheet, and never plot a masked column. */
class XlsxChartsTest {

    private static ExportDoc.Section section(String kind, List<String> cols, List<List<Object>> rows, List<String> masked) {
        return new ExportDoc.Section("v", "Notional by desk", ExportDoc.CHART, cols, rows, null, masked, kind);
    }

    private static List<List<Object>> rows() {
        List<List<Object>> r = new ArrayList<>();
        r.add(java.util.Arrays.asList("DESK-A", 1500L, 2.5));
        r.add(java.util.Arrays.asList("DESK-B", 900L, 1.25));
        return r;
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

    private static void wellFormed(Map<String, String> parts) throws Exception {
        for (Map.Entry<String, String> e : parts.entrySet()) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)));      // throws if a part is not XML
        }
    }

    private static Map<String, String> write(ExportDoc.Section s) throws Exception {
        Map<String, String> parts = unzip(XlsxWriter.write(new ExportDoc("Risk", "as of today", List.of(), List.of(s))));
        wellFormed(parts);
        return parts;
    }

    @Test
    void everyChartKindGetsDrawingAndChartPartsNextToTheData() throws Exception {
        for (String kind : List.of("bar", "column", "line", "area", "pie", "donut", "treemap")) {
            Map<String, String> parts = write(section(kind, List.of("Desk", "Notional", "Rate"), rows(), List.of()));
            assertThat(parts).containsKeys("xl/worksheets/_rels/sheet2.xml.rels", "xl/drawings/drawing2.xml", "xl/drawings/_rels/drawing2.xml.rels", "xl/charts/chart2.xml");
            assertThat(parts.get("[Content_Types].xml")).contains("drawingml.chart+xml").contains("officedocument.drawing+xml");
            assertThat(parts.get("xl/worksheets/sheet2.xml")).contains("<drawing r:id=\"rId1\"/>");
            String chart = parts.get("xl/charts/chart2.xml");
            assertThat(chart).contains("<c:f>'Notional by desk'!$B$2:$B$3</c:f>").contains("<c:f>'Notional by desk'!$A$2:$A$3</c:f>").contains("Notional by desk");
        }
        assertThat(write(section("bar", List.of("Desk", "Notional"), rows(), List.of())).get("xl/charts/chart2.xml")).contains("<c:barDir val=\"bar\"/>");
        assertThat(write(section("column", List.of("Desk", "Notional"), rows(), List.of())).get("xl/charts/chart2.xml")).contains("<c:barDir val=\"col\"/>");
        assertThat(write(section("line", List.of("Desk", "Notional"), rows(), List.of())).get("xl/charts/chart2.xml")).contains("<c:lineChart>");
        assertThat(write(section("pie", List.of("Desk", "Notional", "Rate"), rows(), List.of())).get("xl/charts/chart2.xml")).contains("<c:pieChart>").doesNotContain("$C$");
        assertThat(write(section("treemap", List.of("Desk", "Notional"), rows(), List.of())).get("xl/charts/chart2.xml")).contains("<c:barChart>");     // other kinds draw as columns
    }

    @Test
    void severalMeasuresAreSeveralSeriesAndDatesAreNumericCategories() throws Exception {
        String chart = write(section("column", List.of("Desk", "Notional", "Rate"), rows(), List.of())).get("xl/charts/chart2.xml");
        assertThat(chart).contains("$B$1</c:f>").contains("$C$1</c:f>").contains("<c:legend>");
        List<List<Object>> byDate = new ArrayList<>();
        byDate.add(java.util.Arrays.asList(LocalDate.of(2026, 10, 8), 10L));
        byDate.add(java.util.Arrays.asList(LocalDate.of(2026, 10, 9), 12L));
        assertThat(write(section("line", List.of("Date", "Notional"), byDate, List.of())).get("xl/charts/chart2.xml")).contains("<c:cat><c:numRef>");
    }

    @Test
    void aMaskedColumnIsNeverAChartSeriesAndATableOnlySectionHasNoChart() throws Exception {
        List<List<Object>> r = new ArrayList<>();
        r.add(java.util.Arrays.asList("DESK-A", "•••", 4L));
        r.add(java.util.Arrays.asList("DESK-B", "•••", 6L));
        String chart = write(section("column", List.of("Desk", "MTM", "Trades"), r, List.of("MTM"))).get("xl/charts/chart2.xml");
        assertThat(chart).doesNotContain("$B$").contains("$C$1</c:f>");
        // nothing numeric left to plot: no chart parts at all, the data sheet is still there
        List<List<Object>> allMasked = new ArrayList<>();
        allMasked.add(java.util.Arrays.asList("DESK-A", "•••"));
        Map<String, String> parts = write(section("column", List.of("Desk", "MTM"), allMasked, List.of("MTM")));
        assertThat(parts).containsKey("xl/worksheets/sheet2.xml").doesNotContainKey("xl/charts/chart2.xml");
        Map<String, String> table = unzip(XlsxWriter.write(new ExportDoc("Risk", "", List.of(),
                List.of(new ExportDoc.Section("t", "Trades", ExportDoc.TABLE, List.of("Desk", "N"), rows(), null, List.of(), "column")))));
        assertThat(table).doesNotContainKey("xl/charts/chart2.xml");
    }

    @Test
    void chartsSurviveLibreOfficeWhenItIsInstalled() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("drishti.test.soffice") && Files.isExecutable(Path.of("/usr/bin/soffice")));
        Path dir = com.ash.drishti.common.TempFiles.root().resolve("xlsx-charts-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("charts.xlsx");
        Files.write(file, XlsxWriter.write(new ExportDoc("Risk", "", List.of(), List.of(section("pie", List.of("Desk", "Notional"), rows(), List.of())))));
        Process p = new ProcessBuilder("/usr/bin/soffice", "--headless", "-env:UserInstallation=file://" + dir.resolve("profile"), "--convert-to", "pdf", "--outdir", dir.toString(),
                file.toString()).redirectErrorStream(true).start();
        assertThat(p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(dir.resolve("charts.pdf")).exists();
    }
}
