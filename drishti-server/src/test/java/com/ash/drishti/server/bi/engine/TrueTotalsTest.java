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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.bi.pivot.BiAggregate;
import com.ash.drishti.server.bi.pivot.BiMeasures;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * True totals on the pivot engine (BI_DESIGN.md, totals; ARCHITECTURE.md section 23.12): every level of a matrix and a table, for every measure that is a
 * pivot function, in one grouping-sets statement over the model's rows as the reader sees them, checked here against a brute-force computation over the
 * same trades. The shown figures could not give them: the average of averages is not the average.
 */
class TrueTotalsTest {

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;
    Dataset dataset;

    static final String REPORT = """
            report: t
            dataset: lake-test
            pages:
              - id: p
                visuals:
                  - { id: m, type: matrix, at: { x: 0, y: 0, w: 12, h: 6 }, wells: { rows: [trades.desk, trades.currency], columns: [trades.cpty], values: [Notional, AvgMTM, MTMperM, Names, P50, Biggest] }, format: { subtotals: true } }
                  - { id: plain, type: matrix, at: { x: 0, y: 6, w: 12, h: 6 }, wells: { rows: [trades.desk], values: [AvgMTM] } }
                  - { id: t, type: table, at: { x: 0, y: 12, w: 12, h: 6 }, wells: { columns: [trades.desk, Trades, AvgMTM, MTMperM] }, format: { totals: true } }
            """;

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        DatasetParser.Result r = DatasetParser.parse(TestLake.yaml());
        dataset = r.dataset();
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), dataset, "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    QueryService.Caller risk() {
        return new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
    }

    Report.Visual visual(String id) {
        return ReportParser.parse(REPORT).report().page("p").visual(id);
    }

    QueryRequest base(Report.Visual v, List<String> fields, List<String> measures) {
        return new QueryRequest(fields, measures, List.of(), TestLake.DAY2, TestLake.DAY2, List.of(), 5000, "auto");
    }

    static double mean(List<TestLake.Trade> t) {
        return t.stream().mapToDouble(TestLake.Trade::mtm).average().orElse(Double.NaN);
    }

    static double median(List<TestLake.Trade> t) {
        double[] v = t.stream().mapToDouble(TestLake.Trade::mtm).sorted().toArray();
        double pos = (v.length - 1) * 0.5;
        int i = (int) Math.floor(pos);
        return i + 1 < v.length ? v[i] + (pos - i) * (v[i + 1] - v[i]) : v[i];
    }

    BiAggregate.Totals totals(List<List<String>> levels, List<String> measures, QueryService.Caller who) {
        return BiAggregate.totals(dataset, levels, measures, base(null, List.of(), List.of()), 2000, q -> service.query(ds, q, who, false));
    }

    @Test
    void theEngineSaysWhichMeasuresAddUpAndWhichAreItsFunctions() {
        assertThat(BiMeasures.additive(dataset, "Notional")).isTrue();
        assertThat(BiMeasures.additive(dataset, "Trades")).isTrue();
        for (String m : List.of("AvgMTM", "MTMperM", "Names", "P50", "Biggest", "Share")) {
            assertThat(BiMeasures.additive(dataset, m)).as(m).isFalse();
            assertThat(BiMeasures.of(dataset, m)).as(m + " is a pivot function").isPresent();
        }
        Report.Visual m = visual("m");
        assertThat(BiAggregate.matrixLevels(m.well("rows"), m.well("columns"))).containsExactly(List.of(), List.of("trades.desk"),
                List.of("trades.cpty"), List.of("trades.desk", "trades.cpty"), List.of("trades.desk", "trades.currency"));
    }

    @Test
    void theSameTotalsAskedAgainAreTheSameNotACachedAnswerWithoutItsPlan() {
        Report.Visual m = visual("m");
        List<List<String>> levels = BiAggregate.matrixLevels(m.well("rows"), m.well("columns"));
        BiAggregate.Totals first = totals(levels, m.well("values"), risk());
        BiAggregate.Totals again = totals(levels, m.well("values"), risk());      // a report asked twice: the statement is made again
        assertThat(again.levels()).hasSize(first.levels().size());
        for (int i = 0; i < first.levels().size(); i++) {
            assertThat(again.levels().get(i).rows()).isEqualTo(first.levels().get(i).rows());
        }
    }

    @Test
    void everyLevelOfTheMatrixIsTheMeasureOverTheRowsOfItsGroup() {
        List<TestLake.Trade> all = lake.trades.get(TestLake.DAY2);
        Report.Visual m = visual("m");
        BiAggregate.Totals t = totals(BiAggregate.matrixLevels(m.well("rows"), m.well("columns")), m.well("values"), risk());
        assertThat(t.notTotalled()).isEmpty();
        assertThat(t.levels()).hasSize(5);
        for (BiAggregate.Level level : t.levels()) {
            List<String> by = level.by();
            Function<TestLake.Trade, List<String>> key = tr -> by.stream().map(f -> switch (f) {
                case "trades.desk" -> tr.desk();
                case "trades.currency" -> tr.ccy();
                default -> tr.cpty();
            }).toList();
            Map<List<String>, List<TestLake.Trade>> groups = all.stream().collect(Collectors.groupingBy(key));
            assertThat(level.rows()).as(by.toString()).hasSize(groups.size());
            List<String> names = new ArrayList<>(by);
            names.addAll(level.measures());
            for (List<Object> row : level.rows()) {
                List<String> k = new ArrayList<>();
                for (int c = 0; c < by.size(); c++) {
                    k.add(String.valueOf(row.get(c)));
                }
                List<TestLake.Trade> g = groups.get(k);
                assertThat(g).as(k.toString()).isNotNull();
                double notional = g.stream().mapToDouble(TestLake.Trade::notional).sum();
                double mtm = g.stream().mapToDouble(TestLake.Trade::mtm).sum();
                java.util.function.Function<String, Double> val = n -> ((Number) row.get(names.indexOf(n))).doubleValue();
                assertThat(val.apply("Notional")).isEqualTo(notional, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(val.apply("AvgMTM")).isEqualTo(mean(g), org.assertj.core.data.Offset.offset(1e-9));
                assertThat(val.apply("MTMperM")).isEqualTo(mtm / notional * 1_000_000, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(val.apply("Names")).isEqualTo((double) g.stream().map(TestLake.Trade::cpty).distinct().count());
                assertThat(val.apply("P50")).isEqualTo(median(g), org.assertj.core.data.Offset.offset(1e-9));
                assertThat(val.apply("Biggest")).isEqualTo(g.stream().mapToDouble(TestLake.Trade::notional).max().orElse(0));
            }
        }
    }

    @Test
    void theAverageOfTheShownAveragesIsNotTheAverageSoTheTotalCannotBeSummed() {
        List<TestLake.Trade> all = lake.trades.get(TestLake.DAY2);
        QueryService.Result byDesk = service.query(ds, base(null, List.of("trades.desk"), List.of("AvgMTM")), risk(), false);
        double averageOfAverages = Arrays.stream(byDesk.columns().get(1).values()).mapToDouble(v -> ((Number) v).doubleValue()).average().orElse(0);
        QueryService.Result total = service.query(ds, base(null, List.of(), List.of("AvgMTM")), risk(), false);
        double truth = ((Number) total.columns().get(0).values()[0]).doubleValue();
        assertThat(truth).isEqualTo(mean(all), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Math.abs(averageOfAverages - truth)).as("40 trades in groups of 14, 13 and 13: the means differ").isGreaterThan(1e-6);
    }

    @Test
    void aTableTotalsEveryMeasureOverAllTheRowsNotJustTheOnesShown() {
        BiAggregate.Totals t = totals(List.of(List.of()), List.of("Trades", "AvgMTM", "MTMperM"), risk());
        assertThat(t.levels()).hasSize(1);
        List<Object> row = t.levels().get(0).rows().get(0);
        List<TestLake.Trade> all = lake.trades.get(TestLake.DAY2);
        assertThat(((Number) row.get(0)).longValue()).isEqualTo(all.size());
        assertThat(((Number) row.get(1)).doubleValue()).isEqualTo(mean(all), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aMaskedMeasureHasNoTotalEither() {
        QueryService.Caller analyst = new QueryService.Caller("ann", Set.of("risk"), true, UserAttributes.of("ann", List.of("risk"), "", null), (t, c) -> c.equals("mtm"));
        QueryService.Result r = service.query(ds, base(null, List.of(), List.of("AvgMTM")), analyst, false);
        assertThat(r.meta().masked()).contains("AvgMTM");
        assertThat(r.columns().get(0).values()[0]).isNull();
        BiAggregate.Totals t = totals(List.of(List.of(), List.of("trades.desk")), List.of("AvgMTM", "Notional"), analyst);
        assertThat(t.levels().get(0).masked()).containsExactly("AvgMTM");
        assertThat(t.levels().get(0).rows().get(0).get(0)).isNull();
        assertThat(t.levels().get(0).rows().get(0).get(1)).isNotNull();
    }
}
