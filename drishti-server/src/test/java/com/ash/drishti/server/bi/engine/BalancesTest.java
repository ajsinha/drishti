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

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Balances and time travel (wave B task 2) on the engine: {@code closing} per month across a holiday (the business day before a month end that is a holiday
 * or a weekend, on the pack's calendar), {@code last}, {@code first}, {@code opening}, {@code avg_over}, {@code as_of} with {@code shift_date(asof(), -1)},
 * and {@code fiscal_ytd}; each against an independent computation over the same rows.
 */
class BalancesTest {

    static final LocalDate AS_OF = LocalDate.of(2027, 6, 30);

    @TempDir Path store;
    QueryService service;
    TestLake lake;
    QueryService.Loaded ds;
    final List<TestLake.Trade> book = new ArrayList<>();

    void start(BusinessCalendar calendar) {
        lake = new TestLake(true) {
            @Override
            public BusinessCalendar calendar() {
                return calendar;
            }
        };
        int n = 0;
        for (LocalDate d = LocalDate.of(2026, 11, 2); !d.isAfter(AS_OF); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
                book.add(new TestLake.Trade("B-" + n, TestLake.DESKS.get(n % 3), "USD", "CP-1", 10.0 * n, (n * 37) % 101 + 1.0, d.toString(), List.of()));
            }
        }
        lake.trades.put(AS_OF, book);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        String yaml = TestLake.yaml().replace("measures:\n", """
                measures:
                  Closing: { expr: "closing(sum(trades.mtm), trades.tradeDate)" }
                  QuarterEnd: { expr: "closing(sum(trades.mtm), trades.tradeDate, period='quarter')" }
                  Last: { expr: "last(sum(trades.mtm), trades.tradeDate)" }
                  First: { expr: "first(sum(trades.mtm), trades.tradeDate)" }
                  Opening: { expr: "opening(sum(trades.mtm), trades.tradeDate)" }
                  Daily: { expr: "avg_over(sum(trades.mtm), trades.tradeDate)" }
                  Fytd: { expr: "fiscal_ytd(sum(trades.mtm), trades.tradeDate)" }
                  Age: { expr: "max(days_between(trades.tradeDate, asof()))" }
                  Busy: { expr: "sum(business_days_between(trades.tradeDate, asof()))" }
                  DayOnDay: { expr: "measure('MTM') - as_of(measure('MTM'), shift_date(asof(), -1))" }
                  OpeningSnap: { expr: "opening(sum(trades.mtm), trades.businessDate)" }
                """);
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        ds = new QueryService.Loaded("file:dataset:lake-bal", Layers.key("file:dataset:lake-bal"), r.dataset(), "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    QueryService.Caller risk() {
        return new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
    }

    QueryService.Result run(List<String> fields, List<String> measures, LocalDate day) {
        return service.query(ds, new QueryRequest(fields, measures, List.of(), day, day, List.of(new QueryRequest.Sort(fields.isEmpty() ? measures.get(0) : fields.get(0), false)),
                null, "auto"), risk(), true);
    }

    double mtmOn(LocalDate d) {
        return book.stream().filter(t -> t.tradeDate().equals(d.toString())).mapToDouble(TestLake.Trade::mtm).sum();
    }

    @Test
    void theClosingBalanceOfEachMonthIsItsLastBusinessDayAcrossAHoliday() {
        start(BusinessCalendar.of("USNY"));
        BusinessCalendar cal = BusinessCalendar.of("USNY");
        QueryService.Result r = run(List.of("trades.tradeDate.month"), List.of("Closing"), AS_OF);
        Map<Integer, Double> byMonth = new LinkedHashMap<>();
        for (int i = 0; i < r.rows(); i++) {
            byMonth.put(((Number) r.columns().get(0).values()[i]).intValue(), r.columns().get(1).values()[i] == null ? null : ((Number) r.columns().get(1).values()[i]).doubleValue());
        }
        // May 2027 ends on Memorial Day (Monday 31): its close is Friday the 28th; January 2027 ends on a Sunday: Friday the 29th
        assertThat(cal.isBusinessDay(LocalDate.of(2027, 5, 31))).isFalse();
        assertThat(byMonth.get(5)).isEqualTo(mtmOn(LocalDate.of(2027, 5, 28)));
        assertThat(byMonth.get(5)).isNotEqualTo(mtmOn(LocalDate.of(2027, 5, 27)));
        for (Map.Entry<Integer, Double> e : byMonth.entrySet()) {
            int month = e.getKey();
            LocalDate end = YearMonth.of(month >= 11 ? 2026 : 2027, month).atEndOfMonth();
            LocalDate close = cal.onOrBefore(end.isAfter(AS_OF) ? AS_OF : end);
            assertThat(e.getValue()).as("month %d closes on %s", month, close).isEqualTo(mtmOn(close));
        }
    }

    @Test
    void lastFirstOpeningAndTheAverageOverDatesPerGroup() {
        start(BusinessCalendar.of("WEEKENDS"));
        QueryService.Result r = run(List.of("trades.desk"), List.of("Last", "First", "Daily", "Opening"), AS_OF);
        Map<String, List<TestLake.Trade>> byDesk = book.stream().collect(Collectors.groupingBy(TestLake.Trade::desk));
        for (int i = 0; i < r.rows(); i++) {
            List<TestLake.Trade> ts = byDesk.get((String) r.columns().get(0).values()[i]);
            String lastDay = ts.stream().map(TestLake.Trade::tradeDate).max(String::compareTo).orElseThrow();
            String firstDay = ts.stream().map(TestLake.Trade::tradeDate).min(String::compareTo).orElseThrow();
            assertThat((double) r.columns().get(1).values()[i]).isEqualTo(ts.stream().filter(t -> t.tradeDate().equals(lastDay)).mapToDouble(TestLake.Trade::mtm).sum());
            assertThat((double) r.columns().get(2).values()[i]).isEqualTo(ts.stream().filter(t -> t.tradeDate().equals(firstDay)).mapToDouble(TestLake.Trade::mtm).sum());
            double avg = ts.stream().collect(Collectors.groupingBy(TestLake.Trade::tradeDate, Collectors.summingDouble(TestLake.Trade::mtm))).values().stream()
                    .mapToDouble(x -> x).average().orElseThrow();
            assertThat((double) r.columns().get(3).values()[i]).isCloseTo(avg, Offset.offset(1e-9));
            // opening of June 2027 = the close of the business day before 1 June (Monday 31 May on weekends only)
            double opening = ts.stream().filter(t -> t.tradeDate().equals("2027-05-31")).mapToDouble(TestLake.Trade::mtm).sum();
            Object got = r.columns().get(4).values()[i];
            if (ts.stream().anyMatch(t -> t.tradeDate().equals("2027-05-31"))) {
                assertThat((double) got).isEqualTo(opening);
            } else {
                assertThat(got).isNull();
            }
        }
        // along the month: each month's opening is the closing of the month before
        QueryService.Result m = run(List.of("trades.tradeDate.month"), List.of("Opening", "Closing"), AS_OF);
        for (int i = 1; i < m.rows(); i++) {
            assertThat(m.columns().get(1).values()[i]).isEqualTo(m.columns().get(2).values()[i - 1] == null ? null : m.columns().get(2).values()[i - 1]);
        }
    }

    @Test
    void theFiscalYearToDateAndTheAsOfDateInRowExpressions() {
        start(BusinessCalendar.of("WEEKENDS"));
        // a model without a calendar: the fiscal year is the calendar year
        QueryService.Result r = run(List.of(), List.of("Fytd", "Age", "Busy"), AS_OF);
        double fytd = book.stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(LocalDate.of(2027, 1, 1))).mapToDouble(TestLake.Trade::mtm).sum();
        assertThat((double) r.columns().get(0).values()[0]).isEqualTo(fytd);
        assertThat(((Number) r.columns().get(1).values()[0]).longValue()).isEqualTo(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2026, 11, 2), AS_OF));
        assertThat(TimeWindows.fiscalStart(LocalDate.of(2027, 3, 31), 4)).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(TimeWindows.fiscalStart(LocalDate.of(2027, 4, 1), 4)).isEqualTo(LocalDate.of(2027, 4, 1));
    }

    @Test
    void businessDaysBetweenSubtractTheCalendarsHolidays() {
        start(BusinessCalendar.of("USNY"));
        BusinessCalendar cal = BusinessCalendar.of("USNY");
        QueryService.Result r = run(List.of(), List.of("Busy"), AS_OF);
        long expected = 0;
        for (TestLake.Trade t : book) {
            int n = 0;
            for (LocalDate d = LocalDate.parse(t.tradeDate()).plusDays(1); !d.isAfter(AS_OF); d = d.plusDays(1)) {
                n += cal.isBusinessDay(d) ? 1 : 0;
            }
            expected += n;
        }
        assertThat(((Number) r.columns().get(0).values()[0]).longValue()).isEqualTo(expected);
    }

    @Test
    void asOfReadsAnotherBusinessDateAndAnOpeningOnTheSnapshotDateReadsTheDayBefore() {
        start(BusinessCalendar.of("WEEKENDS"));
        lake.trades.put(LocalDate.of(2027, 6, 29), book.subList(0, 100));
        lake.trades.put(LocalDate.of(2027, 5, 31), book.subList(0, 7));
        QueryService.Result r = run(List.of(), List.of("DayOnDay", "OpeningSnap"), AS_OF);
        double today = book.stream().mapToDouble(TestLake.Trade::mtm).sum();
        double yesterday = book.subList(0, 100).stream().mapToDouble(TestLake.Trade::mtm).sum();
        assertThat((double) r.columns().get(0).values()[0]).isCloseTo(today - yesterday, Offset.offset(1e-9));
        assertThat((double) r.columns().get(1).values()[0]).isEqualTo(book.subList(0, 7).stream().mapToDouble(TestLake.Trade::mtm).sum());
        assertThatThrownBy(() -> service.query(new QueryService.Loaded("x", Layers.key("x"), DatasetParser.parse(TestLake.yaml().replace("measures:\n",
                "measures:\n  Bad: { expr: \"as_of(measure('MTM'), trades.tradeDate)\" }\n")).dataset(), "h2"), new QueryRequest(List.of(), List.of("Bad"), List.of(), AS_OF, AS_OF,
                List.of(), null, "auto"), risk(), false)).isInstanceOf(DrishtiException.class).hasMessageContaining("work out before the query");
    }
}
