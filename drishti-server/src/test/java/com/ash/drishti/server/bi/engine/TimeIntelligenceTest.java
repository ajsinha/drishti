package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Time intelligence ({@code ytd}, {@code mtd}, {@code qtd}, {@code prior_period}, {@code same_period_last_year}, {@code running}) over a lake whose
 * book holds one trade per weekday for a year: every figure is checked against a brute-force sum over the same trades, with the plain weekend calendar
 * and with USNY, whose Independence Day (observed on Friday 3 July 2026) moves the prior period.
 */
class TimeIntelligenceTest {

    static final LocalDate EMPTY_DAY = LocalDate.of(2026, 7, 6);
    static final LocalDate DAY = LocalDate.of(2026, 7, 7);

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;
    List<TestLake.Trade> book = new ArrayList<>();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        start(BusinessCalendar.of("WEEKENDS"));
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    void start(BusinessCalendar calendar) {
        if (service != null) {
            service.close();
        }
        lake = new TestLake(true) {
            @Override
            public BusinessCalendar calendar() {
                return calendar;
            }
        };
        book.clear();
        int n = 0;
        for (LocalDate d = LocalDate.of(2025, 6, 1); !d.isAfter(DAY); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
                book.add(new TestLake.Trade("B-" + n, TestLake.DESKS.get(n % 3), "USD", "CP-1", 1000.0, (n * 37) % 101 + 1.0, d.toString(), List.of()));
            }
        }
        lake.trades.put(EMPTY_DAY, List.of());
        lake.trades.put(DAY, book);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        String yaml = TestLake.yaml().replace("measures:\n", """
                measures:
                  Ytd: { expr: "ytd(sum(trades.mtm), trades.tradeDate)" }
                  Mtd: { expr: "mtd(sum(trades.mtm), trades.tradeDate)" }
                  Qtd: { expr: "qtd(sum(trades.mtm), trades.tradeDate)" }
                  Prior: { expr: "prior_period(sum(trades.mtm), trades.tradeDate)" }
                  LastYear: { expr: "same_period_last_year(sum(trades.mtm), trades.tradeDate)" }
                  YtdTrades: { expr: "ytd(count(trades), trades.tradeDate)" }
                  YtdRatio: { expr: "ytd(ratio(sum(trades.mtm), sum(trades.notional)), trades.tradeDate)" }
                  YtdVsAll: { expr: "ratio(ytd(sum(trades.mtm), trades.tradeDate), sum(trades.mtm))" }
                  Running: { expr: "running(sum(trades.mtm), trades.tradeDate)" }
                  RunningTrades: { expr: "running(count(trades), trades.tradeDate)" }
                """);
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), r.dataset(), "h1");
    }

    QueryService.Caller risk() {
        return new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
    }

    QueryService.Result run(String body, QueryService.Caller who) {
        try {
            return service.query(ds, QueryRequest.of(json.readTree(body)), who, true);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    double sum(LocalDate lo, LocalDate hi) {
        return book.stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(lo) && !LocalDate.parse(t.tradeDate()).isAfter(hi)).mapToDouble(TestLake.Trade::mtm).sum();
    }

    double one(String measure, String from, String to) {
        QueryService.Result r = run("{\"measures\":[\"" + measure + "\"],\"dates\":{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}}", risk());
        assertThat(r.rows()).isEqualTo(1);
        return ((Number) r.columns().get(0).values()[0]).doubleValue();
    }

    @Test
    void yearMonthAndQuarterToDateEndAtTheAsOfDate() {
        assertThat(one("Ytd", "2026-07-07", "2026-07-07")).isEqualTo(sum(LocalDate.of(2026, 1, 1), DAY));
        assertThat(one("Mtd", "2026-07-07", "2026-07-07")).isEqualTo(sum(LocalDate.of(2026, 7, 1), DAY));
        assertThat(one("Qtd", "2026-07-07", "2026-07-07")).isEqualTo(sum(LocalDate.of(2026, 7, 1), DAY));
        QueryService.Result r = run("{\"measures\":[\"Ytd\",\"YtdTrades\"],\"dates\":{\"from\":\"2026-07-07\",\"to\":\"2026-07-07\"}}", risk());
        long weekdays = book.stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(LocalDate.of(2026, 1, 1))).count();
        assertThat(r.columns().get(1).values()[0]).isEqualTo(weekdays);
    }

    @Test
    void theAsOfDateIsTheEndOfTheQueryNotTheEndOfTheData() {
        QueryService.Result r = run("{\"measures\":[\"Ytd\"],\"dates\":{\"from\":\"2026-07-07\",\"to\":\"2026-07-07\"}}", risk());
        assertThat(r.meta().sql()).contains("FILTER (WHERE");
        lake.trades.put(LocalDate.of(2026, 7, 8), book);                       // a later partition exists but the query ends on the 7th
        assertThat(one("Ytd", "2026-07-07", "2026-07-07")).isEqualTo(sum(LocalDate.of(2026, 1, 1), DAY));
    }

    @Test
    void aRatioInsideATimeFunctionIsRestrictedToo() {
        QueryService.Result r = run("{\"measures\":[\"YtdRatio\"],\"dates\":{\"from\":\"2026-07-07\",\"to\":\"2026-07-07\"}}", risk());
        LocalDate lo = LocalDate.of(2026, 1, 1);
        double notional = book.stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(lo)).mapToDouble(TestLake.Trade::notional).sum();
        assertThat((double) r.columns().get(0).values()[0]).isEqualTo(sum(lo, DAY) / notional, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void aTimeMeasureInsideAMeasureOfMeasuresAndByDesk() {
        QueryService.Result r = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Ytd\",\"YtdVsAll\"],\"dates\":{\"from\":\"2026-07-07\",\"to\":\"2026-07-07\"}}", risk());
        assertThat(r.rows()).isEqualTo(3);
        Map<String, Double> byDesk = new TreeMap<>();
        for (TestLake.Trade t : book) {
            if (!LocalDate.parse(t.tradeDate()).isBefore(LocalDate.of(2026, 1, 1))) {
                byDesk.merge(t.desk(), t.mtm(), Double::sum);
            }
        }
        for (int i = 0; i < r.rows(); i++) {
            assertThat((double) r.columns().get(1).values()[i]).isEqualTo(byDesk.get(String.valueOf(r.columns().get(0).values()[i])));
        }
    }

    @Test
    void thePriorPeriodCountsBusinessDaysAndSkipsHolidays() {
        // the query's dates are the 6th and 7th of July 2026: two business days. Weekends only: the two days before are Fri 3 and Thu 2.
        assertThat(one("Prior", "2026-07-06", "2026-07-07")).isEqualTo(sum(LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3)));
        start(BusinessCalendar.of("USNY"));
        // USNY keeps Independence Day on Friday 3 July: the two days before are Thu 2 and Wed 1
        assertThat(one("Prior", "2026-07-06", "2026-07-07")).isEqualTo(sum(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2)));
        assertThat(sum(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2))).isNotEqualTo(sum(LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3)));
    }

    @Test
    void theSamePeriodLastYearPullsAWeekendEndInToTheBusinessDays() {
        // 6 and 7 July 2025 are Sunday and Monday: the window last year is Monday the 7th only
        assertThat(one("LastYear", "2026-07-06", "2026-07-07")).isEqualTo(sum(LocalDate.of(2025, 7, 7), LocalDate.of(2025, 7, 7)));
        assertThat(one("LastYear", "2026-07-07", "2026-07-07")).isEqualTo(sum(LocalDate.of(2025, 7, 7), LocalDate.of(2025, 7, 7)));
    }

    @Test
    void withoutDatesTheAsOfDateIsTheNewestDateReadAndAPeriodOfOneDayHasTheDayBeforeAsItsPrior() {
        QueryService.Result r = run("{\"measures\":[\"Mtd\"]}", risk());
        double october = lake.trades.get(TestLake.DAY2).stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(LocalDate.of(2026, 10, 1))).mapToDouble(TestLake.Trade::mtm).sum();
        assertThat((double) r.columns().get(0).values()[0]).isEqualTo(october);
        assertThat(one("Prior", "2026-07-07", "2026-07-07")).isEqualTo(sum(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6)));
    }

    @Test
    void aRunningTotalRunsAlongTheDateFieldAndRestartsWithACoarserPartOfTheSameDate() {
        QueryService.Result r = run("{\"fields\":[\"trades.tradeDate\"],\"measures\":[\"Running\",\"RunningTrades\"],\"filters\":[{\"field\":\"trades.tradeDate\",\"op\":\"between\","
                + "\"values\":[\"2026-06-29\",\"2026-07-07\"]}],\"orderBy\":[{\"by\":\"trades.tradeDate\",\"desc\":false}],\"dates\":{\"from\":\"2026-07-07\",\"to\":\"2026-07-07\"}}", risk());
        double run = 0;
        int days = 0;
        for (int i = 0; i < r.rows(); i++) {
            LocalDate d = LocalDate.parse(String.valueOf(r.columns().get(0).values()[i]));
            run += sum(d, d);
            days++;
            assertThat((double) r.columns().get(1).values()[i]).isEqualTo(run);
            assertThat(r.columns().get(2).values()[i]).isEqualTo((long) days);
        }
        assertThat(r.rows()).isEqualTo(7);
        QueryService.Result byMonth = run("{\"fields\":[\"trades.tradeDate.year\",\"trades.tradeDate.month\"],\"measures\":[\"Running\"],\"dates\":{\"from\":\"2026-07-07\",\"to\":\"2026-07-07\"},"
                + "\"orderBy\":[{\"by\":\"trades.tradeDate.year\"},{\"by\":\"trades.tradeDate.month\"}]}", risk());
        double acc = 0;
        long lastYear = 0;
        for (int i = 0; i < byMonth.rows(); i++) {
            long year = ((Number) byMonth.columns().get(0).values()[i]).longValue();
            long month = ((Number) byMonth.columns().get(1).values()[i]).longValue();
            if (year != lastYear) {
                acc = 0;
                lastYear = year;
            }
            acc += sum(LocalDate.of((int) year, (int) month, 1), LocalDate.of((int) year, (int) month, 1).plusMonths(1).minusDays(1));
            assertThat((double) byMonth.columns().get(2).values()[i]).isEqualTo(acc);
        }
    }

    @Test
    void aRunningTotalWithoutItsDateFieldOrAsAFilterIsRefused() {
        assertThatThrownBy(() -> run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Running\"],\"dates\":{\"from\":\"2026-07-07\"}}", risk()))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("needs that date as a field");
        assertThatThrownBy(() -> run("{\"fields\":[\"trades.tradeDate\"],\"measures\":[\"Running\"],\"filters\":[{\"field\":\"Running\",\"op\":\"gt\",\"values\":[1]}],\"dates\":{\"from\":\"2026-07-07\"}}", risk()))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("cannot be filtered on");
    }

    @Test
    void theValidatorHoldsTimeFunctionsToTheirShape() {
        String base = TestLake.yaml();
        for (String bad : List.of("ytd(trades.mtm, trades.tradeDate)", "ytd(sum(trades.mtm))", "ytd(sum(trades.mtm), trades.desk)", "running(avg(trades.mtm), trades.tradeDate)",
                "ytd(ytd(sum(trades.mtm), trades.tradeDate), trades.tradeDate)", "mtd(sum(trades.mtm), trades.nope)")) {
            DatasetParser.Result r = DatasetParser.parse(base.replace("measures:\n", "measures:\n  Bad: { expr: \"" + bad + "\" }\n"));
            assertThat(r.problems()).as(bad).anyMatch(DatasetParser.Problem::error);
        }
        assertThat(DatasetParser.parse(base.replace("measures:\n", "measures:\n  Bad: { expr: \"lag(sum(trades.mtm))\" }\n")).problems()).anyMatch(p -> p.code().equals("DRS-9013"));
    }

    @Test
    void timeMeasuresAreNotRollupMaterial() {
        String yaml = TestLake.yaml().replace("measures:\n", "measures:\n  Ytd: { expr: \"ytd(sum(trades.mtm), trades.tradeDate)\" }\n")
                .replace("measures: [Notional,", "measures: [Ytd, Notional,");
        assertThat(DatasetParser.parse(yaml).problems()).anyMatch(p -> p.code().equals("DRS-9012") && p.message().contains("Ytd"));
    }
}
