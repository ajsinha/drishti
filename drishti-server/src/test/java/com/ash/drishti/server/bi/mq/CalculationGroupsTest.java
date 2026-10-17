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
package com.ash.drishti.server.bi.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.TestLake;
import com.ash.drishti.server.bi.plan.PlanProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Calculation groups, what-if and field parameters, perspectives and {@code mq: 2} (docs/architecture/BI_DESIGN.md sections 3.3.5 and 3.6; wave B
 * acceptance (1): "a YTD calculation group over ten measures and a what-if shock equal an independent computation"). The trades of the as-of date are spread
 * over a year and a half of trade dates, so year-to-date is a real cut.
 */
class CalculationGroupsTest {

    static final String MODEL = """
            rachana: 2
            model: desk-calc
            version: 1.0.0
            datasets:
              trades: { dataset: trades, role: fact }
              desks:  { dataset: desks, role: dimension }
            relationships:
              - { id: r_1, from: trades.desk, to: desks.desk }
            measures:
              MTM:      { expr: "sum(trades.mtm)" }
              Notional: { expr: "sum(trades.notional)" }
              Trades:   { expr: "count(trades)" }
              AvgMTM:   { expr: "avg(trades.mtm)" }
              Biggest:  { expr: "max(trades.notional)" }
              Worst:    { expr: "min(trades.mtm)" }
              EurMTM:   { expr: "sum(trades.mtm, where=trades.currency == 'EUR')" }
              Names:    { expr: "distinct(trades.cpty)" }
              PerM:     { expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000" }
              Share:    { expr: "measure('MTM') / measure('Notional')" }
              Shocked:  { expr: "measure('MTM') * (1 + param.shock)" }
              Pct:      { expr: "share_of(measure('MTM'), over=ALL)" }
            calc-groups:
              Time:
                precedence: 10
                items:
                  Current: "SELECTED"
                  YTD: "ytd(SELECTED, trades.tradeDate)"
                  MTD: "mtd(SELECTED, trades.tradeDate)"
            parameters:
              shock:     { kind: number, default: 0, min: -0.5, max: 0.5, step: 0.01 }
              breakdown: { kind: field, fields: [desks.region, trades.currency], default: desks.region }
            perspectives:
              desk-view: { title: Desk view, fields: [desks.*], measures: [MTM, Trades] }
            """;
    static final String TRADES = MqEngineTest.TRADES.replace("security:\n  masked: [cpty]\n", "");
    static final List<String> TEN = List.of("MTM", "Notional", "Trades", "AvgMTM", "Biggest", "Worst", "EurMTM", "Names", "PerM", "Share");

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    MqService mq;
    MqHarness h;
    ModelView model;
    List<TestLake.Trade> book = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        int n = 0;
        for (LocalDate d = LocalDate.of(2025, 5, 1); !d.isAfter(TestLake.DAY2); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
                book.add(new TestLake.Trade("B-" + n, TestLake.DESKS.get(n % 3), n % 4 == 0 ? "EUR" : "USD", "CP-" + (n % 5), 1000.0 + n, (n * 37) % 101 - 40.0, d.toString(),
                        List.of()));
            }
        }
        lake.trades.put(TestLake.DAY2, book);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        mq = new MqService(MqProperties.defaults(), new PlanProperties(null, null, null, "warn"));   // the cache on, the guards warning: the figures are what is compared
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(MODEL, TRADES, MqEngineTest.DESKS);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    MqService.Bound rita() {
        return h.bind(model, MqHarness.RITA, "m");
    }

    Map<String, Object> ask(String yaml) {
        return mq.ask(rita(), MqParser.parse(yaml), false).body();
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Map<String, Object> answer) {
        return (List<List<Object>>) answer.get("rows");
    }

    /** Each of the ten measures worked out in Java over the trades. */
    static Map<String, Double> independent(List<TestLake.Trade> ts) {
        Map<String, Double> m = new LinkedHashMap<>();
        double mtm = ts.stream().mapToDouble(TestLake.Trade::mtm).sum();
        double notional = ts.stream().mapToDouble(TestLake.Trade::notional).sum();
        m.put("MTM", mtm);
        m.put("Notional", notional);
        m.put("Trades", (double) ts.size());
        m.put("AvgMTM", ts.stream().mapToDouble(TestLake.Trade::mtm).average().orElse(Double.NaN));
        m.put("Biggest", ts.stream().mapToDouble(TestLake.Trade::notional).max().orElse(Double.NaN));
        m.put("Worst", ts.stream().mapToDouble(TestLake.Trade::mtm).min().orElse(Double.NaN));
        List<TestLake.Trade> eur = ts.stream().filter(t -> t.ccy().equals("EUR")).toList();
        m.put("EurMTM", eur.isEmpty() ? null : eur.stream().mapToDouble(TestLake.Trade::mtm).sum());
        m.put("Names", (double) ts.stream().map(TestLake.Trade::cpty).distinct().count());
        m.put("PerM", mtm / notional * 1_000_000);
        m.put("Share", mtm / notional);
        return m;
    }

    @Test
    void aYearToDateCalculationGroupOverTenMeasuresEqualsAnIndependentComputationPerDesk() {
        String measures = String.join(", ", TEN);
        Map<String, Object> a = ask("{ mq: 2, model: desk-calc, select: { fields: [trades.desk], measures: [" + measures + "] }, calc: { Time: YTD }, time: { as-of: 2026-10-09 } }");
        Map<String, Object> m = ask("{ mq: 2, model: desk-calc, select: { fields: [trades.desk], measures: [" + measures + "] }, calc: { Time: MTD }, time: { as-of: 2026-10-09 } }");
        Map<String, Object> all = ask("{ mq: 2, model: desk-calc, select: { fields: [trades.desk], measures: [" + measures + "] }, calc: { Time: Current }, time: { as-of: 2026-10-09 } }");
        Function<LocalDate, Map<String, Map<String, Double>>> since = from -> book.stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(from))
                .collect(Collectors.groupingBy(TestLake.Trade::desk, Collectors.collectingAndThen(Collectors.toList(), CalculationGroupsTest::independent)));
        check(a, since.apply(LocalDate.of(2026, 1, 1)));
        check(m, since.apply(LocalDate.of(2026, 10, 1)));
        check(all, since.apply(LocalDate.of(2000, 1, 1)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) a.get("columns");
        assertThat(cols.stream().map(c -> c.get("name")).toList()).containsExactlyElementsOf(java.util.stream.Stream.concat(java.util.stream.Stream.of("trades.desk"), TEN.stream())
                .toList());                                                    // the answer keeps the measures' names
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) a.get("meta");
        @SuppressWarnings("unchecked")
        Map<String, Object> metaAll = (Map<String, Object>) all.get("meta");
        assertThat(meta.get("plan")).isNotEqualTo(metaAll.get("plan"));          // another item is another question
    }

    private void check(Map<String, Object> answer, Map<String, Map<String, Double>> expected) {
        assertThat(rows(answer)).hasSize(expected.size());
        for (List<Object> row : rows(answer)) {
            Map<String, Double> want = expected.get((String) row.get(0));
            for (int i = 0; i < TEN.size(); i++) {
                Double w = want.get(TEN.get(i));
                if (w == null) {
                    assertThat(row.get(i + 1)).as(row.get(0) + " " + TEN.get(i)).isNull();
                    continue;
                }
                assertThat(((Number) row.get(i + 1)).doubleValue()).as(row.get(0) + " " + TEN.get(i)).isCloseTo(w, org.assertj.core.data.Offset.offset(1e-6 * Math.max(1, Math.abs(w))));
            }
        }
    }

    @Test
    void aWhatIfShockEqualsTheIndependentComputationAndIsPartOfTheQuestion() {
        double mtm = book.stream().mapToDouble(TestLake.Trade::mtm).sum();
        Map<String, Object> base = ask("{ mq: 2, model: desk-calc, select: { measures: [Shocked] } }");
        Map<String, Object> shocked = ask("{ mq: 2, model: desk-calc, select: { measures: [Shocked] }, params: { shock: -0.1 } }");
        assertThat(((Number) rows(base).get(0).get(0)).doubleValue()).isCloseTo(mtm, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(((Number) rows(shocked).get(0).get(0)).doubleValue()).isCloseTo(mtm * 0.9, org.assertj.core.data.Offset.offset(1e-6));
        @SuppressWarnings("unchecked")
        Object p1 = ((Map<String, Object>) base.get("meta")).get("plan");
        @SuppressWarnings("unchecked")
        Object p2 = ((Map<String, Object>) shocked.get("meta")).get("plan");
        assertThat(p1).isNotEqualTo(p2);
        assertThatThrownBy(() -> ask("{ mq: 2, model: desk-calc, select: { measures: [Shocked] }, params: { shock: 0.9 } }")).isInstanceOf(MqException.class)
                .hasMessageContaining("DRS-9164").hasMessageContaining("from -0.5 to 0.5");
        assertThatThrownBy(() -> ask("{ mq: 2, model: desk-calc, select: { measures: [MTM] }, params: { nope: 1 } }")).hasMessageContaining("DRS-9164");
        assertThatThrownBy(() -> ask("{ mq: 1, model: desk-calc, select: { measures: [Shocked] }, params: { shock: -0.1 } }")).hasMessageContaining("mq: 2");
    }

    @Test
    void aFieldParameterChoosesTheFieldAndAPerspectiveLimitsTheNames() {
        Map<String, Object> byCcy = ask("{ mq: 2, model: desk-calc, select: { fields: [param.breakdown], measures: [Trades] }, params: { breakdown: trades.currency } }");
        assertThat(rows(byCcy).stream().map(r -> r.get(0)).toList()).containsExactly("EUR", "USD");
        Map<String, Object> byRegion = ask("{ mq: 2, model: desk-calc, select: { fields: [param.breakdown], measures: [Trades] } }");
        assertThat(rows(byRegion).stream().map(r -> r.get(0)).toList()).containsExactly("AMER", "EMEA");
        assertThatThrownBy(() -> ask("{ mq: 2, model: desk-calc, select: { fields: [param.breakdown], measures: [Trades] }, params: { breakdown: trades.cpty } }"))
                .hasMessageContaining("DRS-9164");
        assertThat(rows(ask("{ mq: 2, model: desk-calc, perspective: desk-view, select: { fields: [desks.region], measures: [MTM] } }"))).hasSize(2);
        assertThatThrownBy(() -> ask("{ mq: 2, model: desk-calc, perspective: desk-view, select: { fields: [trades.currency], measures: [Notional] } }"))
                .hasMessageContaining("DRS-9153");
        assertThatThrownBy(() -> ask("{ mq: 2, model: desk-calc, calc: { Time: QTD }, select: { measures: [MTM] } }")).hasMessageContaining("DRS-9165");
    }

    @Test
    void aShareOfTheTotalAskedThroughTheModelIsOfWhatTheReaderMaySee() {
        Map<String, Object> a = mq.ask(h.bind(model, MqHarness.RITA, "m"), MqParser.parse("{ mq: 1, model: desk-calc, select: { fields: [trades.desk], measures: [MTM, Pct] } }"), false)
                .body();
        double total = rows(a).stream().mapToDouble(r -> ((Number) r.get(1)).doubleValue()).sum();
        for (List<Object> r : rows(a)) {
            assertThat(((Number) r.get(2)).doubleValue()).isCloseTo(((Number) r.get(1)).doubleValue() / total, org.assertj.core.data.Offset.offset(1e-9));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> explain = (Map<String, Object>) mq.validate(rita(), MqParser.parse("{ mq: 1, model: desk-calc, select: { fields: [trades.desk], measures: [Pct] } }"))
                .get("explain");
        assertThat(String.valueOf(explain)).contains("Context").contains("share_of");
    }
}
