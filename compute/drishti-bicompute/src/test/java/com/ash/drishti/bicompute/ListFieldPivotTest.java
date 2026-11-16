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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.rows.RowsProvider;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.GroupingSetsCompiler;
import com.ash.drishti.kernel.api.pivot.Dim;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.Viewer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * A list attribute ({@code TAGS: ["fx", "rates"]}) as a row field: exploded, each value is a member and a row counts under each of its
 * values (the answer says totals may count a row twice); joined ({@code TAGS:join}), one label. The same in memory and in DuckDB's SQL
 * (a native list or its JSON text).
 */
class ListFieldPivotTest {

    private static final List<PivotProvider.Field> FIELDS = List.of(new PivotProvider.Field("DESK", "string", "", ""),
            new PivotProvider.Field("TAGS", "list", "", ""), new PivotProvider.Field("PV", "number", "", ""));

    private static List<Object[]> rows() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {"RATES", List.of("fx", "rates"), 10.0});
        rows.add(new Object[] {"RATES", List.of("rates"), 20.0});
        rows.add(new Object[] {"FX", List.of("fx", "fx"), 5.0});      // a value twice: counted once
        rows.add(new Object[] {"FX", List.of(), 1.0});                 // no value: the blank member
        rows.add(new Object[] {"FX", "[\"credit\",\"fx\"]", 2.0});    // JSON text, as a promoted column hands it over
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> query(String rowField, Map<String, Object> extra) {
        PivotCore core = new PivotCore(List.of(new RowsProvider("t", "T", FIELDS, rows())), StateCache.none(), PivotCore.Settings.defaults());
        Map<String, Object> view = new java.util.LinkedHashMap<>(Map.of("source", "t", "rows", List.of(rowField),
                "values", List.of(Map.of("measure", "count()", "as", "N"), Map.of("measure", "sum(PV)", "as", "S"))));
        view.putAll(extra);
        return core.query(Viewer.of("rita"), PivotView.parse(view));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> counts(Map<String, Object> answer, String measure) {
        Map<String, Double> out = new TreeMap<>();
        ((Map<String, Map<String, Object>>) answer.get("cells")).forEach((k, c) -> {
            Object v = ((Map<String, Object>) c.get("values")).get(measure);
            out.put(k, v == null ? null : ((Number) v).doubleValue());
        });
        return out;
    }

    @Test
    void aListFieldCountsEachValueAndSaysTotalsMayCountARowTwice() {
        Map<String, Object> a = query("TAGS", Map.of());
        Map<String, Double> n = counts(a, "N");
        assertThat(n).containsEntry("TAGS=fx|*", 3.0).containsEntry("TAGS=rates|*", 2.0).containsEntry("TAGS=credit|*", 1.0);
        assertThat(counts(a, "S")).containsEntry("TAGS=fx|*", 17.0).containsEntry("TAGS=rates|*", 30.0);
        assertThat(n.entrySet().stream().filter(e -> e.getKey().startsWith("TAGS=") && !e.getKey().startsWith("TAGS=fx")
                && !e.getKey().startsWith("TAGS=rates") && !e.getKey().startsWith("TAGS=credit")).map(Map.Entry::getValue).toList())
                .containsExactly(1.0);                                  // the row without tags: the blank member
        assertThat(n).containsEntry("*|*", 5.0);                        // the grand total counts each row once
        assertThat((List<String>) a.get("notes")).anyMatch(s -> s.contains("TAGS is a list") && s.contains("more than once"));
    }

    @Test
    void joinedAListIsOneLabelAndNoNote() {
        Map<String, Object> a = query("TAGS:join", Map.of());
        Map<String, Double> n = counts(a, "N");
        assertThat(n).containsEntry("TAGS:join=fx, rates|*", 1.0).containsEntry("TAGS:join=rates|*", 1.0)
                .containsEntry("TAGS:join=credit, fx|*", 1.0).containsEntry("TAGS:join=fx, fx|*", 1.0);
        Object notes = a.get("notes");
        assertThat(notes == null ? List.of() : (List<String>) notes).noneMatch(s -> s.contains("is a list"));
    }

    @Test
    void aFilterOnAListKeepsTheRowsWithAnyChosenValue() {
        Map<String, Object> a = query("DESK", Map.of("filters", Map.of("TAGS", Map.of("in", List.of("credit", "rates")))));
        assertThat(counts(a, "N")).containsEntry("DESK=RATES|*", 2.0).containsEntry("DESK=FX|*", 1.0).containsEntry("*|*", 3.0);
    }

    @Test
    void twoListFieldsAreNotExplodedTogether() {
        List<PivotProvider.Field> two = new ArrayList<>(FIELDS);
        two.add(new PivotProvider.Field("BOOKS", "list", "", ""));
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {"FX", List.of("a"), 1.0, List.of("b1", "b2")});
        PivotCore core = new PivotCore(List.of(new RowsProvider("t", "T", two, rows)), StateCache.none(), PivotCore.Settings.defaults());
        assertThatThrownBy(() -> core.query(Viewer.of("rita"), PivotView.parse(Map.of("source", "t", "rows", List.of("TAGS"),
                "columns", List.of("BOOKS"), "values", List.of(Map.of("measure", "count()", "as", "N"))))))
                .hasMessageContaining("one list field is exploded at a time");
    }

    @Test
    void duckDbExplodesANativeListAndItsJsonTextAlike() {
        try (DuckDbExecutor exec = new DuckDbExecutor(DuckDbExecutor.Settings.small())) {
            for (String from : List.of("(SELECT * FROM (VALUES (['fx', 'rates']), (['rates']), (['fx', 'fx']), ([]::VARCHAR[]), (NULL::VARCHAR[])) t(TAGS))",
                    "(SELECT * FROM (VALUES ('[\"fx\",\"rates\"]'), ('[\"rates\"]'), ('[\"fx\",\"fx\"]'), ('[]'), (NULL)) t(TAGS))")) {
                String explode = GroupingSetsCompiler.dimExpr(new Dim("TAGS", null), "list");
                List<String> members = exec.query("SELECT d, count(*) FROM (SELECT " + explode + " AS d FROM " + from + " AS t) GROUP BY d ORDER BY d",
                        List.of(), rs -> {
                            List<String> out = new ArrayList<>();
                            while (rs.next()) {
                                out.add(rs.getString(1) + "=" + rs.getLong(2));
                            }
                            return out;
                        });
                assertThat(members).as(from).contains("fx=2", "rates=2").hasSize(3);    // and the blank member: 2 rows
                String join = GroupingSetsCompiler.dimExpr(new Dim("TAGS", "join"), "list");
                List<String> labels = exec.query("SELECT " + join + " FROM " + from + " AS t", List.of(), rs -> {
                    List<String> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                    return out;
                });
                assertThat(labels).as(from).contains("fx, rates", "rates", "fx, fx");
            }
        }
    }
}
