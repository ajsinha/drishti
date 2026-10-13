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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Several unrelated fact tables in one query: the trades (many rows per desk) and the limits (seven rows over the same three desks) share only the desks
 * dimension. Each fact is aggregated alone at the shared grain and the two are joined, so neither is repeated by the other's rows; every figure is checked
 * against a brute-force sum.
 */
class MultiFactTest {

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        String yaml = TestLake.yaml().replace("relationships:", """
                  limits:
                    source: { connector: limit-lake, kind: limit }
                    columns:
                      desk: string
                      amount: decimal
                relationships:
                  - { from: limits.desk, to: desks.desk, cardinality: many-to-one }""")
                .replace("measures:\n", """
                measures:
                  LimitTotal: { expr: "sum(limits.amount)" }
                  Limits: { expr: "count(limits)" }
                  MaxLimit: { expr: "max(limits.amount)" }
                  Mixed: { expr: "measure('MTM') / measure('LimitTotal')" }
                """);
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), r.dataset(), "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    QueryService.Caller who(boolean raw, String desk, String... roles) {
        SqlCompiler.Masks masks = (t, c) -> !raw && t.equals("trades") && c.equals("mtm");
        return new QueryService.Caller("u", Set.of(roles), true, UserAttributes.of("u", List.of(roles), desk, null), masks);
    }

    QueryService.Result run(String body, QueryService.Caller who) {
        try {
            return service.query(ds, QueryRequest.of(json.readTree(body)), who, true);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    Map<String, double[]> byDesk() {
        Map<String, double[]> m = new TreeMap<>();                                       // desk -> {trade count, trade MTM, limit total, limit count, max limit}
        for (TestLake.Trade t : lake.trades.get(TestLake.DAY2)) {
            double[] a = m.computeIfAbsent(t.desk(), k -> new double[5]);
            a[0]++;
            a[1] += t.mtm();
        }
        for (int i = 0; i < TestLake.LIMIT_DESKS.length; i++) {
            double[] a = m.computeIfAbsent(TestLake.LIMIT_DESKS[i], k -> new double[5]);
            a[2] += TestLake.LIMIT_AMOUNTS[i];
            a[3]++;
            a[4] = Math.max(a[4], TestLake.LIMIT_AMOUNTS[i]);
        }
        return m;
    }

    Map<String, Object[]> rows(QueryService.Result r) {
        Map<String, Object[]> out = new LinkedHashMap<>();
        for (int i = 0; i < r.rows(); i++) {
            Object[] row = new Object[r.columns().size() - 1];
            for (int c = 1; c < r.columns().size(); c++) {
                row[c - 1] = r.columns().get(c).values()[i];
            }
            out.put(String.valueOf(r.columns().get(0).values()[i]), row);
        }
        return out;
    }

    static final String DAY = "\"dates\":{\"from\":\"2026-10-09\"}";

    @Test
    void eachFactIsAggregatedAloneAtTheSharedGrainWithNoFanOut() {
        QueryService.Result r = run("{\"fields\":[\"desks.desk\"],\"measures\":[\"MTM\",\"Trades\",\"LimitTotal\",\"Limits\",\"MaxLimit\"]," + DAY + "}", who(true, "", "risk"));
        Map<String, Object[]> got = rows(r);
        Map<String, double[]> want = byDesk();
        assertThat(got.keySet()).containsExactlyElementsOf(want.keySet());
        want.forEach((desk, w) -> {
            Object[] g = got.get(desk);
            assertThat(((Number) g[0]).doubleValue()).as(desk + " MTM").isEqualTo(w[1]);
            assertThat(((Number) g[1]).doubleValue()).as(desk + " trades").isEqualTo(w[0]);
            assertThat(((Number) g[2]).doubleValue()).as(desk + " limit total (not multiplied by the trades)").isEqualTo(w[2]);
            assertThat(((Number) g[3]).doubleValue()).as(desk + " limits (not multiplied by the trades)").isEqualTo(w[3]);
            assertThat(((Number) g[4]).doubleValue()).isEqualTo(w[4]);
        });
        assertThat(r.meta().warnings()).anyMatch(n -> n.contains("fact tables"));
    }

    @Test
    void withNoFieldsTheTotalsSitSideBySide() {
        QueryService.Result r = run("{\"measures\":[\"Trades\",\"LimitTotal\"]," + DAY + "}", who(true, "", "risk"));
        assertThat(r.rows()).isEqualTo(1);
        assertThat(((Number) r.columns().get(0).values()[0]).doubleValue()).isEqualTo(lake.trades.get(TestLake.DAY2).size());
        assertThat(((Number) r.columns().get(1).values()[0]).doubleValue()).isEqualTo(java.util.Arrays.stream(TestLake.LIMIT_AMOUNTS).sum());
    }

    @Test
    void aFilterOnTheSharedDimensionReachesBothFactsAndAMeasureFilterIsAppliedToTheJoinedRows() {
        QueryService.Result amer = run("{\"fields\":[\"desks.desk\"],\"measures\":[\"Trades\",\"LimitTotal\"],\"filters\":[{\"field\":\"desks.region\",\"op\":\"eq\",\"values\":[\"AMER\"]}]," + DAY + "}", who(true, "", "risk"));
        assertThat(rows(amer).keySet()).containsExactly("DESK-B", "DESK-C");
        assertThat(((Number) rows(amer).get("DESK-B")[1]).doubleValue()).isEqualTo(byDesk().get("DESK-B")[2]);
        QueryService.Result big = run("{\"fields\":[\"desks.desk\"],\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"LimitTotal\",\"op\":\"gt\",\"values\":[50]}]," + DAY + "}", who(true, "", "risk"));
        assertThat(rows(big).keySet()).containsExactly("DESK-A");                                // LimitTotal is a hidden column: only the facts' figure decides
        assertThat(big.columns()).hasSize(2);
    }

    @Test
    void sortingAndLimitAreAppliedToTheJoinedRows() {
        QueryService.Result r = run("{\"fields\":[\"desks.desk\"],\"measures\":[\"Trades\",\"LimitTotal\"],\"orderBy\":[{\"by\":\"LimitTotal\",\"desc\":true}],\"limit\":2," + DAY + "}", who(true, "", "risk"));
        assertThat(r.rows()).isEqualTo(2);
        assertThat(r.columns().get(0).values()).containsExactly("DESK-A", "DESK-B");
    }

    @Test
    void aMaskedMeasureStaysMaskedWhileTheOtherFactShows() {
        QueryService.Result r = run("{\"fields\":[\"desks.desk\"],\"measures\":[\"MTM\",\"LimitTotal\"]," + DAY + "}", who(false, "", "risk"));
        assertThat(r.columns().get(1).values()).containsOnlyNulls();
        assertThat(((Number) rows(r).get("DESK-A")[1]).doubleValue()).isEqualTo(100.0);
    }

    @Test
    void aFieldOneFactCannotReachAMixedMeasureAWindowAndARuleOnAFactAreRefused() {
        assertThatThrownBy(() -> run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Trades\",\"LimitTotal\"]," + DAY + "}", who(true, "", "risk")))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("no many-to-one path");
        // a measure that combines the two facts is now worked out over the joined parts (the same figure as the two measures divided)
        Map<String, Object[]> both = rows(run("{\"fields\":[\"desks.desk\"],\"measures\":[\"MTM\",\"LimitTotal\"]," + DAY + "}", who(true, "", "risk")));
        Map<String, Object[]> mixed = rows(run("{\"fields\":[\"desks.desk\"],\"measures\":[\"Mixed\"]," + DAY + "}", who(true, "", "risk")));
        assertThat(mixed.keySet()).isEqualTo(both.keySet());
        both.forEach((desk, v) -> assertThat(((Number) mixed.get(desk)[0]).doubleValue()).isCloseTo(((Number) v[0]).doubleValue() / ((Number) v[1]).doubleValue(), org.assertj.core.data.Offset.offset(1e-9)));
        // the desk-head's row rule reads trades.desk: the limits cannot honour it, so the query is refused rather than shown unrestricted
        assertThatThrownBy(() -> run("{\"fields\":[\"desks.desk\"],\"measures\":[\"Trades\",\"LimitTotal\"]," + DAY + "}", who(true, "DESK-A", "desk-head")))
                .isInstanceOf(DrishtiException.class);
    }
}
