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
package com.ash.drishti.engine.pivot;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.model.PivotSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The aggregation behind every pivot: groups at every level, the six aggregations, filters, key order and caps. */
class PivotCubeTest {

    static final PivotSpec FIELDS = PivotSpec.parse(true, PivotSpec.Mode.KIND, PivotSpec.fromPaths(List.of("book", "ccy", "mtm", "id", "date"))).spec();

    static PivotSpec arrange(Map<String, Object> a) {
        var p = PivotSpec.arrangement(a, FIELDS);
        assertThat(p.problems()).isEmpty();
        return p.spec();
    }

    static final List<Map<String, Object>> ROWS = List.of(
            Map.of("book", "B2", "ccy", "USD", "mtm", 10.0, "id", "t1", "date", "2027-01-01"),
            Map.of("book", "B2", "ccy", "EUR", "mtm", -4.0, "id", "t2", "date", "2028-06-30"),
            Map.of("book", "B10", "ccy", "USD", "mtm", 5.5, "id", "t3", "date", "2030-01-01"),
            Map.of("book", "B10", "ccy", "USD", "id", "t4", "date", "2031-01-01"));

    private static Map<String, Object> run(PivotSpec a, int maxRows, int maxCols) {
        PivotCube c = new PivotCube(a, Map.of("mtm", "MTM"), maxRows, maxCols);
        for (Map<String, Object> r : ROWS) {
            PivotCube.Row row = r::get;
            if (c.accepts(row)) {
                c.add(row);
            }
        }
        return c.result("records", ROWS.size(), false, List.of(), 0);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> cell(Map<String, Object> cube, String rows, String cols) {
        return (List<Object>) ((Map<String, Object>) cube.get("cells")).get(rows + "\u001e" + cols);
    }

    @Test
    void everyLevelHasItsTotalsAndEveryAggregation() {
        PivotSpec a = arrange(Map.of("rows", List.of("book"), "columns", List.of("ccy"), "values", List.of(
                Map.of("field", "mtm", "agg", "sum"), Map.of("field", "mtm", "agg", "count"), Map.of("field", "mtm", "agg", "avg"),
                Map.of("field", "mtm", "agg", "min"), Map.of("field", "mtm", "agg", "max"), Map.of("field", "id", "agg", "distinct"))));
        var cube = run(a, 100, 100);
        assertThat(cube.get("rowKeys")).isEqualTo(List.of(List.of("B2"), List.of("B10")));     // natural order: B2 before B10
        assertThat(cube.get("columnKeys")).isEqualTo(List.of(List.of("EUR"), List.of("USD")));
        assertThat(cell(cube, "", "")).containsExactly(11.5, 3L, 11.5 / 3, -4L, 10L, 4L);         // the grand total
        assertThat(cell(cube, "B2", "")).containsExactly(6L, 2L, 3L, -4L, 10L, 2L);               // a row's total
        assertThat(cell(cube, "", "USD")).containsExactly(15.5, 2L, 7.75, 5.5, 10L, 3L);          // a column's total
        assertThat(cell(cube, "B10", "USD")).containsExactly(5.5, 1L, 5.5, 5.5, 5.5, 2L);         // a row without mtm counts in distinct only
        assertThat(cell(cube, "B10", "EUR")).isNull();                                            // no rows: no cell
        assertThat(((List<?>) cube.get("values")).get(0)).isEqualTo(Map.of("field", "mtm", "agg", "sum", "show", "value", "label", "Sum of MTM"));
    }

    @Test
    void filtersKeepValuesOrRanges() {
        var only = run(arrange(Map.of("rows", List.of("ccy"), "values", List.of(Map.of("field", "mtm")),
                "filters", List.of(Map.of("field", "book", "values", List.of("B2"))))), 100, 100);
        assertThat(cell(only, "", "")).containsExactly(6L);
        var range = run(arrange(Map.of("rows", List.of("book"), "values", List.of(Map.of("field", "mtm")),
                "filters", List.of(Map.of("field", "date", "min", "2028-01-01", "max", "2030-12-31")))), 100, 100);
        assertThat(cell(range, "", "")).containsExactly(1.5);
        var numbers = run(arrange(Map.of("rows", List.of("book"), "values", List.of(Map.of("field", "mtm", "agg", "count")),
                "filters", List.of(Map.of("field", "mtm", "min", 0)))), 100, 100);
        assertThat(cell(numbers, "", "")).containsExactly(2L);                                    // a range keeps only rows with a value
    }

    @Test
    void groupsBeyondTheLimitAreLeftOutButCounted() {
        var cut = run(arrange(Map.of("rows", List.of("id"), "values", List.of(Map.of("field", "mtm", "agg", "count")))), 2, 100);
        assertThat((List<?>) cut.get("rowKeys")).hasSize(2);
        assertThat(cut.get("moreRows")).isEqualTo(true);
        assertThat(cell(cut, "", "")).containsExactly(3L);
    }

    @Test
    void keysSortNumbersByValueAndBlanksLast() {
        assertThat(PivotCube.compareKey("9", "10")).isNegative();
        assertThat(PivotCube.compareKey("(blank)", "a")).isPositive();
        assertThat(PivotCube.compareKey("2", "a")).isNegative();
        assertThat(PivotCube.compareKey("2-5Y", "10Y+")).isNegative();                           // natural order of buckets
        assertThat(PivotCube.compareKey("0-1Y", "1-2Y")).isNegative();
        assertThat(PivotCube.compareKey("BOOK-9", "book-10")).isNegative();
        assertThat(PivotCube.key(3.0)).isEqualTo("3");
        assertThat(PivotCube.key(null)).isEqualTo("(blank)");
        assertThat(PivotCube.key("")).isEqualTo("(blank)");
    }

    /** UX-09: a notional as a row key read "4.240872601E7"; keys are plain numbers, as the client engine shows them. */
    @Test
    void numericKeysArePlainWithoutExponentOrFloatNoise() {
        assertThat(PivotCube.key(4.240872601E7)).isEqualTo("42408726.01");
        assertThat(PivotCube.key(-2838229.57)).isEqualTo("-2838229.57");
        assertThat(PivotCube.key(6.0585997E7)).isEqualTo("60585997");
        assertThat(PivotCube.key(1.5E16)).isEqualTo("15000000000000000");
        assertThat(PivotCube.key(1.0E-7)).isEqualTo("0.0000001");
        assertThat(PivotCube.key(0.1 + 0.2)).isEqualTo("0.3");
        assertThat(PivotCube.key(1.1f)).isEqualTo("1.1");
        assertThat(PivotCube.key(-0.0)).isEqualTo("0");
        assertThat(PivotCube.key(new java.math.BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(PivotCube.key(Double.NaN)).isEqualTo("(blank)");
        assertThat(PivotCube.key(42L)).isEqualTo("42");
        PivotSpec a = arrange(Map.of("rows", List.of("mtm"), "values", List.of(Map.of("field", "id", "agg", "count"))));
        PivotCube c = new PivotCube(a, Map.of(), 100, 100);
        PivotCube.Row big = Map.<String, Object>of("mtm", 4.240872601E7, "id", "t9")::get;
        c.add(big);
        assertThat(c.result("records", 1, false, List.of(), 0).toString()).contains("42408726.01").doesNotContain("E7");
    }
}
