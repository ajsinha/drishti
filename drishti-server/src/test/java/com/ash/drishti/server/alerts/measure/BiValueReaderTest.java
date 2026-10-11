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
package com.ash.drishti.server.alerts.measure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.alerts.measure.AlertLogic.Reading;
import com.ash.drishti.server.bi.board.Board;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How an engine answer (columns, rows, masked flags) becomes one number, or a masked/no-data reading. */
class BiValueReaderTest {

    static Map<String, Object> answer(List<Map<String, Object>> cols, List<List<Object>> rows, String asOf) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("columns", cols);
        a.put("rows", rows);
        a.put("meta", Map.of("dates", List.of(asOf)));
        return a;
    }

    static Map<String, Object> col(String name, String type, boolean masked) {
        return Map.of("name", name, "type", type, "masked", masked);
    }

    static List<Object> row(Object... cells) {
        return new ArrayList<>(java.util.Arrays.asList(cells));
    }

    final List<Map<String, Object>> cols = List.of(col("trade.desk", "string", false), col("trade.exposure", "decimal", false));
    final List<List<Object>> rows = List.of(row("FX", 10.0), row("Rates", 30.0), row("Credit", 20.0));

    @Test
    void aVisualWithManyRowsIsReducedToOneNumber() {
        Map<String, Object> a = answer(cols, rows, "2026-11-25");
        assertThat(BiValueReader.reduce(a, "", "first").value()).isEqualTo(10.0);
        assertThat(BiValueReader.reduce(a, "exposure", "sum").value()).isEqualTo(60.0);       // the table prefix does not matter
        assertThat(BiValueReader.reduce(a, "trade.exposure", "max").value()).isEqualTo(30.0);
        assertThat(BiValueReader.reduce(a, "", "min").value()).isEqualTo(10.0);
        assertThat(BiValueReader.reduce(a, "", "avg").value()).isEqualTo(20.0);
        assertThat(BiValueReader.reduce(a, "", "last").value()).isEqualTo(20.0);
        assertThat(BiValueReader.reduce(a, "", "count").value()).isEqualTo(3.0);
        assertThat(BiValueReader.reduce(a, "", "first").asOf()).hasToString("2026-11-25");
    }

    @Test
    void aMaskedMeasureIsAMaskedReadingWhateverTheRowsHold() {
        List<Map<String, Object>> masked = List.of(col("trade.desk", "string", false), col("trade.exposure", "decimal", true));
        Reading r = BiValueReader.reduce(answer(masked, rows, "2026-11-25"), "exposure", "sum");
        assertThat(r.status()).isEqualTo(Reading.Status.MASKED);
        assertThat(Double.isNaN(r.value())).isTrue();                                       // the value is not copied out, even if the engine sent one
    }

    @Test
    void noRowsAndMissingColumnsAreReadAsSuch() {
        assertThat(BiValueReader.reduce(answer(cols, List.of(), "2026-11-25"), "", "first").status()).isEqualTo(Reading.Status.NO_DATA);
        assertThat(BiValueReader.reduce(answer(cols, List.of(row("FX", null)), "2026-11-25"), "", "first").status()).isEqualTo(Reading.Status.NO_DATA);
        Reading missing = BiValueReader.reduce(answer(cols, rows, "2026-11-25"), "mtm", "first");
        assertThat(missing.status()).isEqualTo(Reading.Status.ERROR);
        assertThat(missing.note()).contains("no column 'mtm'");
        assertThat(BiValueReader.reduce(answer(List.of(col("desk", "string", false)), List.of(row("FX")), "2026-11-25"), "", "first").status()).isEqualTo(Reading.Status.ERROR);
    }

    @Test
    void aTileLevelIsTheWorstOfItsRowsAndMaskedStaysMasked() {
        BiValueReader reader = new BiValueReader(null, null, null);
        Board.Alert alert = new Board.Alert("exposure", new Board.Rule("gt", 15), new Board.Rule("gt", 25));
        assertThat(reader.level(answer(cols, rows, "2026-11-25"), alert).value()).isEqualTo(2.0);          // 30 is red, 20 amber
        assertThat(reader.level(answer(cols, List.of(row("FX", 10.0)), "2026-11-25"), alert).value()).isEqualTo(0.0);
        List<Map<String, Object>> masked = List.of(col("trade.desk", "string", false), col("trade.exposure", "decimal", true));
        assertThat(reader.level(answer(masked, rows, "2026-11-25"), alert).status()).isEqualTo(Reading.Status.MASKED);
        assertThat(reader.level(answer(cols, List.of(), "2026-11-25"), alert).status()).isEqualTo(Reading.Status.NO_DATA);
    }

    @Test
    void aFieldIsAPathOrAnExpression() {
        assertThat(FieldValueReader.expression("mtm")).isEqualTo("$.mtm");
        assertThat(FieldValueReader.expression("limits.utilisation")).isEqualTo("$.limits.utilisation");
        assertThat(FieldValueReader.expression("$.mtm * 2")).isEqualTo("$.mtm * 2");
        assertThat(FieldValueReader.expression("mtm / notional")).isEqualTo("mtm / notional");
    }
}
