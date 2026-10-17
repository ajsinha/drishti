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

import com.ash.drishti.api.ArrowScan;
import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnScans;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.TestLake;
import com.ash.drishti.server.bi.plan.PlanProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wave B task 2 through a model: its calendar ({@code calendars: { holidays, fiscal-year-start }}), currency conversion from its FX rates ({@code fx},
 * {@code convert} at the as-of date and at each trade's date; the currency guard lifted), a measure along an inactive relationship ({@code via:}), and the
 * semi-additive rule (a balance function is not added across dates).
 */
class TimeAndCurrencyTest {

    static final String RATES = """
            rachana: 2
            dataset: fx-rates
            version: 1.0.0
            source: { connector: fx-lake, kind: fxrate }
            columns:
              base:  { type: string }
              quote: { type: string }
              rate:  { type: decimal }
              day:   { type: date }
            """;
    static final String MODEL = """
            rachana: 2
            model: desk-fx
            version: 1.0.0
            datasets:
              trades: { dataset: trades, role: fact }
              desks:  { dataset: desks, role: dimension }
              rates:  { dataset: fx-rates, role: dimension }
            relationships:
              - { id: r_1, from: trades.desk, to: desks.desk }
            measures:
              Notional:    { expr: "sum(trades.notional)" }
              Notional USD: { expr: "convert(sum(trades.notional), to='USD')" }
              Notional EUR at trade: { expr: "convert(sum(trades.notional), to='EUR', at='trade-date')" }
              Rated:       { expr: "sum(trades.notional * fx(trades.currency, 'USD', at=date('2026-10-01')))" }
              Fytd:        { expr: "fiscal_ytd(sum(trades.mtm), trades.tradeDate)" }
            calendars: { holidays: USNY, fiscal-year-start: 4 }
            fx: { rates: rates, from: base, to: quote, rate: rate, date: day }
            """;

    /** EUR/USD 1.10 from 2026-09-01, 1.20 from 2026-10-05; GBP has no rate. */
    static final String[] BASE = {"EUR", "EUR"};
    static final String[] QUOTE = {"USD", "USD"};
    static final double[] RATE = {1.10, 1.20};
    static final String[] DAY = {"2026-09-01", "2026-10-05"};

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    MqService mq;
    MqHarness h;
    ModelView model;

    @BeforeEach
    void setUp() {
        SourcePlugin fxLake = new SourcePlugin() {
            @Override
            public PluginManifest manifest() {
                return new PluginManifest("fx-lake", "1", Set.of("fxrate"), new SourceCapabilities(false, false, false, false));
            }

            @Override
            public void start(SourceContext context) {}

            @Override
            public Optional<EntityDocument> fetch(EntityRef ref) {
                return Optional.empty();
            }

            @Override
            public Set<String> columnar(String kind) {
                return Set.of("base", "quote", "rate", "day");
            }

            @Override
            public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
                return Optional.of(new ColumnSet(new String[] {"F1", "F2"}, Map.of("rate", RATE), Map.of("base", BASE, "quote", QUOTE, "day", DAY), null));
            }

            @Override
            public Optional<ArrowScan> scan(ScanRequest request) {
                return ColumnScans.over(this, request);
            }

            @Override
            public void close() {}
        };
        lake = new TestLake(true) {
            @Override
            public Optional<SourcePlugin> plugin(String connector, String kind) {
                return "fx-lake".equals(connector) || "fxrate".equals(kind) ? Optional.of(fxLake) : super.plugin(connector, kind);
            }
        };
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        mq = new MqService(MqProperties.defaults(), PlanProperties.defaults());
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(MODEL, MqEngineTest.TRADES, MqEngineTest.DESKS, RATES);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    Map<String, Object> ask(String yaml) {
        return mq.ask(h.bind(model, MqHarness.RITA, "m"), MqParser.parse(yaml), false).body();
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Map<String, Object> a) {
        return (List<List<Object>>) a.get("rows");
    }

    @SuppressWarnings("unchecked")
    static List<String> warnings(Map<String, Object> a) {
        return (List<String>) ((Map<String, Object>) a.get("meta")).get("warnings");
    }

    @Test
    void convertMultipliesEachAmountByItsCurrencysRateAndLiftsTheGuard() {
        List<TestLake.Trade> day = lake.trades.get(TestLake.DAY2);
        assertThatThrownBy(() -> ask("{ mq: 1, model: desk-fx, select: { measures: [Notional] }, time: { as-of: 2026-10-09 } }")).hasMessageContaining("DRS-9158");
        Map<String, Object> usd = ask("{ mq: 1, model: desk-fx, select: { measures: [Notional USD, Rated] }, time: { as-of: 2026-10-09 } }");
        double asOf = day.stream().mapToDouble(t -> t.notional() * ("EUR".equals(t.ccy()) ? 1.20 : 1.0)).sum();       // the rate on or before 9 October
        double first = day.stream().mapToDouble(t -> t.notional() * ("EUR".equals(t.ccy()) ? 1.10 : 1.0)).sum();      // at 1 October
        assertThat(((Number) rows(usd).get(0).get(0)).doubleValue()).isCloseTo(asOf, Offset.offset(1e-6));
        assertThat(((Number) rows(usd).get(0).get(1)).doubleValue()).isCloseTo(first, Offset.offset(1e-6));
        assertThat(warnings(usd)).noneMatch(w -> w.startsWith("DRS-9158"));
        // to EUR at each trade's date (9 October for every trade of the day): USD through the inverse of 1.20
        Map<String, Object> eur = ask("{ mq: 1, model: desk-fx, select: { measures: [Notional EUR at trade] }, time: { as-of: 2026-10-09 } }");
        double inEur = day.stream().mapToDouble(t -> t.notional() * ("USD".equals(t.ccy()) ? 1 / 1.20 : 1.0)).sum();
        assertThat(((Number) rows(eur).get(0).get(0)).doubleValue()).isCloseTo(inEur, Offset.offset(1e-6));
    }

    @Test
    void theModelsCalendarSetsTheFiscalYearAndTheBusinessDays() {
        Map<String, Object> a = ask("{ mq: 1, model: desk-fx, select: { measures: [Fytd] }, time: { as-of: 2026-10-09 } }");
        double all = lake.trades.get(TestLake.DAY2).stream().mapToDouble(TestLake.Trade::mtm).sum();      // every trade is dated 9 October: in the fiscal year from 1 April
        assertThat(((Number) rows(a).get(0).get(0)).doubleValue()).isEqualTo(all);
        assertThat(model.flat().model().holidays()).isEqualTo("USNY");
        assertThat(model.flat().model().fiscalYearStart()).isEqualTo(4);
        @SuppressWarnings("unchecked")
        Map<String, Object> describe = MqDescribe.calculation(model.def());
        assertThat(describe.get("calendar")).isEqualTo(Map.of("holidays", "USNY", "fiscalYearStart", 4));
    }

    @Test
    void aConversionWithoutRatesIsRefusedWithTheFix() {
        ModelView none = MqHarness.model(MODEL.replace("fx: { rates: rates, from: base, to: quote, rate: rate, date: day }\n", ""), MqEngineTest.TRADES, MqEngineTest.DESKS, RATES);
        assertThatThrownBy(() -> mq.ask(h.bind(none, MqHarness.RITA, "n"), MqParser.parse("{ mq: 1, model: desk-fx, select: { measures: [Notional USD] } }"), false))
                .hasMessageContaining("fx: { rates");
    }
}
