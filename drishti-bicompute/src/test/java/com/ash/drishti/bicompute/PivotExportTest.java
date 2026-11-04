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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.export.PivotExport;
import com.ash.drishti.bicompute.spi.Viewer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * The exports (PIVOT_ENGINE.md section 15): the xlsx keeps the outline (row outline levels, summaries below), writes a SUM formula only for a
 * measure that adds up (sum, count) and the engine's values for everything else (a median), and has a Provenance sheet; the CSV has one row
 * per cell and measure; the definition is YAML with no figures.
 */
class PivotExportTest {

    @Test
    void xlsxCsvAndDefinition() throws Exception {
        try (PivotFixture fx = new PivotFixture(1_000, StateCache.none(), PivotCore.Settings.defaults())) {
            PivotView v = PivotView.parse(Map.of("source", "trades", "rows", List.of("REGION", "DESK"), "expandTo", Map.of("rows", 2),
                    "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "Notional"), Map.of("measure", "median(PNL)", "as", "Median"))));
            Map<String, Object> a = fx.core.query(Viewer.of("rita"), v);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PivotExport.xlsx(a, out, "rita", "2026-11-02T18:00:00Z");
            Map<String, String> parts = unzip(out.toByteArray());
            assertThat(parts).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml",
                    "xl/worksheets/sheet2.xml");
            String pivot = parts.get("xl/worksheets/sheet1.xml");
            assertThat(pivot).contains("outlineLevel=\"2\"").contains("summaryBelow=\"1\"");
            // the Notional column (B) of total rows is a SUM of its children; the Median column (C) is never a formula
            assertThat(pivot).containsPattern("<c r=\"B[0-9]+\" s=\"3\"><f>SUM\\(B");
            assertThat(pivot).doesNotContainPattern("<c r=\"C[0-9]+\"[^>]*><f>");
            assertThat(parts.get("xl/workbook.xml")).contains("name=\"Provenance\"");
            assertThat(parts.get("xl/worksheets/sheet2.xml")).contains("every figure a value computed by the engine").contains("rita");
            ByteArrayOutputStream csv = new ByteArrayOutputStream();
            PivotExport.csv(a, csv, "cells", ',', false);
            String[] lines = csv.toString(StandardCharsets.UTF_8).split("\r\n");
            assertThat(lines[0]).isEqualTo("ROW,COLUMN,REGION,DESK,MEASURE,VALUE,EXACT,MEMBERS,KIND");
            assertThat(lines).hasSize(1 + 2 * ((Map<?, ?>) a.get("cells")).size());
            ByteArrayOutputStream grid = new ByteArrayOutputStream();
            PivotExport.csv(a, grid, "grid", ';', true);
            assertThat(grid.toString(StandardCharsets.UTF_8)).startsWith("﻿REGION > DESK;Grand total | Notional;Grand total | Median");
            assertThat(PivotExport.definition(v.toMap())).contains("source: \"trades\"").doesNotContain("cells");
        }
    }

    @Test
    void jsonLinesHasOneObjectACellAndMeasureEqualToTheCsv() throws Exception {
        try (PivotFixture fx = new PivotFixture(1_000, StateCache.none(), PivotCore.Settings.defaults())) {
            PivotView v = PivotView.parse(Map.of("source", "trades", "rows", List.of("REGION", "DESK"), "expandTo", Map.of("rows", 2),
                    "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "Notional"), Map.of("measure", "median(PNL)", "as", "Median"))));
            Map<String, Object> a = fx.core.query(Viewer.of("rita"), v);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PivotExport.jsonl(a, out);
            String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
            assertThat(lines).hasSize(2 * ((Map<?, ?>) a.get("cells")).size());
            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            ByteArrayOutputStream csv = new ByteArrayOutputStream();
            PivotExport.csv(a, csv, "cells", ',', false);
            String[] rows = csv.toString(StandardCharsets.UTF_8).split("\r\n");
            for (int i = 0; i < lines.length; i++) {
                Map<?, ?> o = json.readValue(lines[i], Map.class);
                assertThat(new java.util.ArrayList<Object>(o.keySet())).containsExactly("row", "column", "coords", "measure", "value", "exact", "members", "kind");
                assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) o.get("coords")).keySet())).containsExactly("REGION", "DESK");
                String[] c = rows[i + 1].split(",", -1);
                assertThat(String.valueOf(o.get("measure"))).isEqualTo(c[4]);
                if (!c[5].isEmpty()) {
                    assertThat(((Number) o.get("value")).doubleValue()).isCloseTo(Double.parseDouble(c[5]), org.assertj.core.data.Offset.offset(1e-6));
                }
            }
        }
    }

    static Map<String, String> unzip(byte[] b) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
