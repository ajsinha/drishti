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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A BI run's stored answer exported through {@link BiExporter#ofAnswer} as CSV and Excel (docs/architecture/BI_RUNS.md 14.1): masks kept, cut with a note. */
class RunExportTest {

    static final Map<String, Object> ANSWER = Map.of(
            "columns", List.of(Map.of("name", "trades.desk", "kind", "field", "masked", false), Map.of("name", "trades.cpty", "kind", "field", "masked", true),
                    Map.of("name", "MTM", "kind", "measure", "masked", false)),
            "rows", List.of(List.of("DESK-A", "•••", 10.5), List.of("DESK-B", "•••", 3)), "rowCount", 2);

    BiExporter exporter(int maxRows) {
        return new BiExporter(null, new DistProperties.Export(true, maxRows, null, null, null, null, null, null, null, null));
    }

    @Test
    void csvCarriesTheRowsAndTheMaskNeverAValue() {
        ExportDoc doc = exporter(1000).ofAnswer("MTM by desk", "Run r-1", ANSWER);
        String csv = new String(CsvWriter.write(doc), StandardCharsets.UTF_8);
        assertThat(csv).contains("desk").contains("cpty").contains("DESK-A").contains("DESK-B").contains("•••");
        assertThat(doc.sections().get(0).masked()).containsExactly("cpty");
        assertThat(doc.sections().get(0).note()).contains("Masked for you: cpty");
    }

    @Test
    void excelIsAWorkbookAndALongResultIsCutWithANote() {
        ExportDoc doc = exporter(1).ofAnswer("MTM by desk", "Run r-1", ANSWER);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(0).note()).startsWith("Cut at 1 of 2 rows");
        byte[] xlsx = XlsxWriter.write(doc);
        assertThat(xlsx).hasSizeGreaterThan(100);
        assertThat(new String(xlsx, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");                 // a zip: the xlsx container
    }
}
