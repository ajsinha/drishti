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
package com.ash.drishti.rachana.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.ui.BoardDoc.Transform;
import com.ash.drishti.rachana.ui.BoardDoc.TransformKind;
import com.ash.drishti.rachana.ui.PinCompatibility.Verdict;
import com.ash.drishti.rachana.ui.PinSpec.ColumnSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The compatibility table of UI_BOARD.md 4.2, row by row. */
class PinCompatibilityTest {

    private static final String K = "trading/trade";
    private static final String J = "trading/book";

    private static TypeRef t(PinType p) {
        return TypeRef.of(p);
    }

    private static Transform tr(TransformKind k) {
        return new Transform(k, k == TransformKind.FILTER_ON ? "rows.trade" : null);
    }

    static Stream<Arguments> table() {
        return Stream.of(
                // row: T to T
                Arguments.of("text to text", t(PinType.TEXT), t(PinType.TEXT), null, Verdict.YES),
                Arguments.of("date to date", t(PinType.DATE), t(PinType.DATE), null, Verdict.YES),
                Arguments.of("id K to id K", TypeRef.of(PinType.ID, K), TypeRef.of(PinType.ID, K), null, Verdict.YES),
                // id of K to id (no of)
                Arguments.of("id K to id", TypeRef.of(PinType.ID, K), t(PinType.ID), null, Verdict.YES),
                // id (no kind) to id of K: warning BC-W1
                Arguments.of("id to id K", t(PinType.ID), TypeRef.of(PinType.ID, K), null, Verdict.WARN),
                // id of K to id of J: no unless subtype
                Arguments.of("id K to id J", TypeRef.of(PinType.ID, K), TypeRef.of(PinType.ID, J), null, Verdict.NO),
                // id to selection
                Arguments.of("id to selection", t(PinType.ID), t(PinType.SELECTION), null, Verdict.YES),
                // selection to id: no, unless transform first
                Arguments.of("selection to id", t(PinType.SELECTION), t(PinType.ID), null, Verdict.NO),
                Arguments.of("selection to id, first", t(PinType.SELECTION), t(PinType.ID), tr(TransformKind.FIRST), Verdict.YES),
                // date to datetime
                Arguments.of("date to datetime", t(PinType.DATE), t(PinType.DATETIME), null, Verdict.YES),
                // datetime to date: no, unless transform date
                Arguments.of("datetime to date", t(PinType.DATETIME), t(PinType.DATE), null, Verdict.NO),
                Arguments.of("datetime to date, date", t(PinType.DATETIME), t(PinType.DATE), tr(TransformKind.DATE), Verdict.YES),
                // date to period
                Arguments.of("date to period", t(PinType.DATE), t(PinType.PERIOD), null, Verdict.YES),
                // measure to number
                Arguments.of("measure to number", TypeRef.measure("USD"), t(PinType.NUMBER), null, Verdict.YES),
                // number to measure: yes; warning when the pin wants a ccy
                Arguments.of("number to measure", t(PinType.NUMBER), t(PinType.MEASURE), null, Verdict.YES),
                Arguments.of("number to measure USD", t(PinType.NUMBER), TypeRef.measure("USD"), null, Verdict.WARN),
                // measure ccy A to measure ccy B: no, unless ccy any
                Arguments.of("measure EUR to measure USD", TypeRef.measure("EUR"), TypeRef.measure("USD"), null, Verdict.NO),
                Arguments.of("measure EUR to measure any", TypeRef.measure("EUR"), TypeRef.measure("any"), null, Verdict.YES),
                // dimension to text
                Arguments.of("dimension to text", t(PinType.DIMENSION), t(PinType.TEXT), null, Verdict.YES),
                // any scalar to dimension
                Arguments.of("text to dimension", t(PinType.TEXT), t(PinType.DIMENSION), null, Verdict.YES),
                Arguments.of("id to dimension", t(PinType.ID), t(PinType.DIMENSION), null, Verdict.YES),
                Arguments.of("date to dimension", t(PinType.DATE), t(PinType.DIMENSION), null, Verdict.YES),
                Arguments.of("measure to dimension", t(PinType.MEASURE), t(PinType.DIMENSION), null, Verdict.YES),
                Arguments.of("bool to dimension", t(PinType.BOOL), t(PinType.DIMENSION), null, Verdict.YES),
                Arguments.of("rowset to dimension", t(PinType.ROWSET), t(PinType.DIMENSION), null, Verdict.NO),
                // id, selection, period to filter: only with filter-on
                Arguments.of("id to filter", t(PinType.ID), t(PinType.FILTER), null, Verdict.NO),
                Arguments.of("id to filter, filter-on", t(PinType.ID), t(PinType.FILTER), tr(TransformKind.FILTER_ON), Verdict.YES),
                Arguments.of("selection to filter, filter-on", t(PinType.SELECTION), t(PinType.FILTER), tr(TransformKind.FILTER_ON), Verdict.YES),
                Arguments.of("period to filter, filter-on", t(PinType.PERIOD), t(PinType.FILTER), tr(TransformKind.FILTER_ON), Verdict.YES),
                Arguments.of("text to filter, filter-on", t(PinType.TEXT), t(PinType.FILTER), tr(TransformKind.FILTER_ON), Verdict.NO),
                Arguments.of("filter to filter", t(PinType.FILTER), t(PinType.FILTER), null, Verdict.YES),
                // document of K to rowset: only through the map
                Arguments.of("document to rowset", TypeRef.of(PinType.DOCUMENT, K), t(PinType.ROWSET), null, Verdict.NO),
                // what the table does not list is refused
                Arguments.of("text to number", t(PinType.TEXT), t(PinType.NUMBER), null, Verdict.NO),
                Arguments.of("id to text", t(PinType.ID), t(PinType.TEXT), null, Verdict.NO));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void theTable(String row, TypeRef source, TypeRef pin, Transform transform, Verdict expected) {
        assertThat(PinCompatibility.check(source, pin, transform, PinCompatibility.NO_SUBTYPES).verdict()).as(row).isEqualTo(expected);
    }

    @ParameterizedTest(name = "a declared subtype: {0}")
    @MethodSource("subtypes")
    void aPackDeclaredSubtypeIsAccepted(String kind, Verdict expected) {
        assertThat(PinCompatibility.check(TypeRef.of(PinType.ID, kind), TypeRef.of(PinType.ID, J), null, (k, j) -> k.equals("trading/fx-book") && j.equals(J))
                .verdict()).isEqualTo(expected);
    }

    static Stream<Arguments> subtypes() {
        return Stream.of(Arguments.of("trading/fx-book", Verdict.YES), Arguments.of(K, Verdict.NO));
    }

    @ParameterizedTest(name = "rowset {0}")
    @MethodSource("shapes")
    void rowsetToRowset(String row, Map<String, TypeRef> source, int problems) {
        Map<String, ColumnSpec> pin = new LinkedHashMap<>();
        pin.put("id", new ColumnSpec("id", t(PinType.ID), true, false, 0));
        pin.put("date", new ColumnSpec("date", t(PinType.DATE), false, false, 0));
        assertThat(PinCompatibility.checkShape(source, pin, PinCompatibility.NO_SUBTYPES)).as(row).hasSize(problems);
    }

    static Stream<Arguments> shapes() {
        return Stream.of(
                Arguments.of("every required column, extra ignored", Map.of("id", t(PinType.ID), "x", t(PinType.TEXT)), 0),
                Arguments.of("optional column absent", Map.of("id", t(PinType.ID)), 0),
                Arguments.of("required column absent", Map.of("date", t(PinType.DATE)), 1),
                Arguments.of("column of an incompatible type", Map.of("id", t(PinType.ID), "date", t(PinType.TEXT)), 1));
    }

    @ParameterizedTest(name = "enum {0}")
    @MethodSource("enums")
    void enumValues(String row, List<String> source, Verdict expected) {
        TypeRef s = new TypeRef(PinType.ENUM, null, null, null, null, null, false, source);
        TypeRef p = new TypeRef(PinType.ENUM, null, null, null, null, null, false, List.of("BUY", "SELL"));
        assertThat(PinCompatibility.check(s, p).verdict()).isEqualTo(expected);
    }

    static Stream<Arguments> enums() {
        return Stream.of(Arguments.of("subset", List.of("BUY"), Verdict.YES), Arguments.of("more values", List.of("BUY", "HOLD"), Verdict.NO));
    }
}
