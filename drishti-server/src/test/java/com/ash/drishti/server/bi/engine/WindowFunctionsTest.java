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
 * The window functions of the measure language ({@code lag}, {@code lead}, {@code moving_*}, {@code cumulative}, {@code rank}, {@code dense_rank},
 * {@code percent_rank}) over a book of one trade per weekday: every figure is checked against a brute-force computation over the same trades, and the
 * row rules and masks are shown to be applied before the window.
 */
class WindowFunctionsTest {

    static final LocalDate DAY = LocalDate.of(2026, 7, 7);

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;
    List<TestLake.Trade> book = new ArrayList<>();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lake = new TestLake(true) {
            @Override
            public BusinessCalendar calendar() {
                return BusinessCalendar.of("WEEKENDS");
            }
        };
        int n = 0;
        for (LocalDate d = LocalDate.of(2026, 5, 1); !d.isAfter(DAY); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
                // two trades on some days, so that desks hold different counts (ties and no ties)
                book.add(new TestLake.Trade("B-" + n, TestLake.DESKS.get(n % 3), "USD", "CP-1", 1000.0, (n * 37) % 101 + 1.0, d.toString(), List.of()));
                if (n % 5 == 0) {
                    book.add(new TestLake.Trade("C-" + n, TestLake.DESKS.get((n + 1) % 3), "EUR", "CP-1", 1000.0, (n * 53) % 97 + 1.0, d.toString(), List.of()));
                }
            }
        }
        lake.trades.put(DAY, book);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        String yaml = TestLake.yaml().replace("measures:\n", """
                measures:
                  Lag1: { expr: "lag(sum(trades.mtm), 1, trades.tradeDate)" }
                  Lead2: { expr: "lead(sum(trades.mtm), 2, trades.tradeDate)" }
                  MovAvg3: { expr: "moving_avg(sum(trades.mtm), 3, trades.tradeDate)" }
                  MovSum4: { expr: "moving_sum(sum(trades.mtm), 4, trades.tradeDate)" }
                  MovMin3: { expr: "moving_min(sum(trades.mtm), 3, trades.tradeDate)" }
                  MovMax3: { expr: "moving_max(sum(trades.mtm), 3, trades.tradeDate)" }
                  Cum: { expr: "cumulative(sum(trades.mtm), trades.tradeDate)" }
                  Rank: { expr: "rank(sum(trades.mtm))" }
                  RankCount: { expr: "rank(count(trades))" }
                  DenseCount: { expr: "dense_rank(count(trades))" }
                  PercentCount: { expr: "percent_rank(count(trades), desc=False)" }
                  RankInDesk: { expr: "rank(sum(trades.mtm), desc=True, by=[trades.desk])" }
                """);
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), r.dataset(), "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
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

    static final String WINDOW = "\"filters\":[{\"field\":\"trades.tradeDate\",\"op\":\"between\",\"values\":[\"2026-06-01\",\"2026-07-07\"]}],\"dates\":{\"from\":\"2026-07-07\",\"to\":\"2026-07-07\"}";

    Map<LocalDate, Double> perDay() {
        Map<LocalDate, Double> m = new TreeMap<>();
        book.stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(LocalDate.of(2026, 6, 1))).forEach(t -> m.merge(LocalDate.parse(t.tradeDate()), t.mtm(), Double::sum));
        return m;
    }

    double num(QueryService.Result r, int col, int row) {
        return ((Number) r.columns().get(col).values()[row]).doubleValue();
    }

    @Test
    void lagLeadAndMovingWindowsMatchABruteForceOverTheDays() {
        QueryService.Result r = run("{\"fields\":[\"trades.tradeDate\"],\"measures\":[\"Lag1\",\"Lead2\",\"MovAvg3\",\"MovSum4\",\"MovMin3\",\"MovMax3\",\"Cum\"],"
                + "\"orderBy\":[{\"by\":\"trades.tradeDate\"}]," + WINDOW + "}", risk());
        List<Double> v = new ArrayList<>(perDay().values());
        assertThat(r.rows()).isEqualTo(v.size());
        double cum = 0;
        for (int i = 0; i < v.size(); i++) {
            cum += v.get(i);
            if (i == 0) {
                assertThat(r.columns().get(1).values()[0]).isNull();
            } else {
                assertThat(num(r, 1, i)).isEqualTo(v.get(i - 1));
            }
            if (i + 2 < v.size()) {
                assertThat(num(r, 2, i)).isEqualTo(v.get(i + 2));
            } else {
                assertThat(r.columns().get(2).values()[i]).isNull();
            }
            assertThat(num(r, 3, i)).isCloseTo(v.subList(Math.max(0, i - 2), i + 1).stream().mapToDouble(x -> x).average().orElseThrow(), org.assertj.core.data.Offset.offset(1e-9));
            assertThat(num(r, 4, i)).isEqualTo(v.subList(Math.max(0, i - 3), i + 1).stream().mapToDouble(x -> x).sum());
            assertThat(num(r, 5, i)).isEqualTo(v.subList(Math.max(0, i - 2), i + 1).stream().mapToDouble(x -> x).min().orElseThrow());
            assertThat(num(r, 6, i)).isEqualTo(v.subList(Math.max(0, i - 2), i + 1).stream().mapToDouble(x -> x).max().orElseThrow());
            assertThat(num(r, 7, i)).isEqualTo(cum);
        }
    }

    @Test
    void aWindowRestartsInEachGroupOfTheOtherFields() {
        QueryService.Result r = run("{\"fields\":[\"trades.desk\",\"trades.tradeDate\"],\"measures\":[\"Lag1\",\"MovSum4\"],\"orderBy\":[{\"by\":\"trades.desk\"},{\"by\":\"trades.tradeDate\"}]," + WINDOW + "}", risk());
        Map<String, List<Double>> byDesk = new TreeMap<>();
        Map<String, Map<LocalDate, Double>> days = new TreeMap<>();
        book.stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(LocalDate.of(2026, 6, 1)))
                .forEach(t -> days.computeIfAbsent(t.desk(), k -> new TreeMap<>()).merge(LocalDate.parse(t.tradeDate()), t.mtm(), Double::sum));
        days.forEach((d, m) -> byDesk.put(d, new ArrayList<>(m.values())));
        int row = 0;
        for (var e : byDesk.entrySet()) {
            List<Double> v = e.getValue();
            for (int i = 0; i < v.size(); i++, row++) {
                assertThat(r.columns().get(0).values()[row]).isEqualTo(e.getKey());
                if (i == 0) {
                    assertThat(r.columns().get(2).values()[row]).as("first row of " + e.getKey()).isNull();
                } else {
                    assertThat(num(r, 2, row)).isEqualTo(v.get(i - 1));
                }
                assertThat(num(r, 3, row)).isEqualTo(v.subList(Math.max(0, i - 3), i + 1).stream().mapToDouble(x -> x).sum());
            }
        }
        assertThat(r.rows()).isEqualTo(row);
    }

    @Test
    void rankDenseRankAndPercentRankMatchABruteForceIncludingTies() {
        QueryService.Result r = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Rank\",\"RankCount\",\"DenseCount\",\"PercentCount\"],\"orderBy\":[{\"by\":\"trades.desk\"}]," + WINDOW + "}", risk());
        Map<String, Double> mtm = new TreeMap<>();
        Map<String, Long> count = new TreeMap<>();
        book.stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(LocalDate.of(2026, 6, 1))).forEach(t -> {
            mtm.merge(t.desk(), t.mtm(), Double::sum);
            count.merge(t.desk(), 1L, Long::sum);
        });
        assertThat(r.rows()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            String desk = (String) r.columns().get(0).values()[i];
            double m = mtm.get(desk);
            long c = count.get(desk);
            assertThat(((Number) r.columns().get(1).values()[i]).longValue()).isEqualTo(1 + mtm.values().stream().filter(x -> x > m).count());
            assertThat(((Number) r.columns().get(2).values()[i]).longValue()).isEqualTo(1 + count.values().stream().filter(x -> x > c).count());
            assertThat(((Number) r.columns().get(3).values()[i]).longValue()).isEqualTo(1 + count.values().stream().filter(x -> x > c).distinct().count());
            long below = count.values().stream().filter(x -> x < c).count();
            assertThat(num(r, 4, i)).isCloseTo(below / 2.0, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void aRankPartitionedByAFieldRanksWithinEachValueOfIt() {
        QueryService.Result r = run("{\"fields\":[\"trades.desk\",\"trades.currency\"],\"measures\":[\"RankInDesk\"],\"orderBy\":[{\"by\":\"trades.desk\"},{\"by\":\"trades.currency\"}]," + WINDOW + "}", risk());
        Map<String, Map<String, Double>> m = new TreeMap<>();
        book.stream().filter(t -> !LocalDate.parse(t.tradeDate()).isBefore(LocalDate.of(2026, 6, 1)))
                .forEach(t -> m.computeIfAbsent(t.desk(), k -> new TreeMap<>()).merge(t.ccy(), t.mtm(), Double::sum));
        for (int i = 0; i < r.rows(); i++) {
            String desk = (String) r.columns().get(0).values()[i];
            String ccy = (String) r.columns().get(1).values()[i];
            double mine = m.get(desk).get(ccy);
            assertThat(((Number) r.columns().get(2).values()[i]).longValue()).isEqualTo(1 + m.get(desk).values().stream().filter(x -> x > mine).count());
        }
    }

    @Test
    void theRowRulesApplyBeforeTheWindowSoARankIsAmongTheRowsTheReaderMaySee() {
        QueryService.Caller auditor = new QueryService.Caller("ann", Set.of("auditor"), true, UserAttributes.of("ann", List.of("auditor"), "", null), SqlCompiler.Masks.NONE);
        QueryService.Result r = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Rank\"],\"orderBy\":[{\"by\":\"Rank\"}]," + WINDOW + "}", auditor);
        assertThat(r.rows()).isEqualTo(2);                                          // an auditor sees DESK-B and DESK-C: ranks 1 and 2, never 3
        assertThat(num(r, 1, 0)).isEqualTo(1.0);
        assertThat(num(r, 1, 1)).isEqualTo(2.0);
        assertThat(r.columns().get(0).values()).doesNotContain("DESK-A");
    }

    @Test
    void aWindowOverAMaskedColumnReadsNull() {
        QueryService.Caller masked = new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null),
                (t, c) -> c.equals("tradeDate"));
        QueryService.Result along = run("{\"fields\":[\"trades.tradeDate\"],\"measures\":[\"Lag1\"],\"dates\":{\"from\":\"2026-07-07\",\"to\":\"2026-07-07\"}}", masked);
        assertThat(along.columns().get(1).values()).containsOnlyNulls();            // the measure reads a column the reader may not see: the whole measure is masked
        QueryService.Caller hidden = new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), (t, c) -> c.equals("mtm"));
        QueryService.Result r = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Rank\"],\"dates\":{\"from\":\"2026-07-07\",\"to\":\"2026-07-07\"}}", hidden);
        assertThat(r.columns().get(1).values()).containsOnlyNulls();
    }

    @Test
    void aWindowOverAFieldTheQueryDoesNotHaveIsRefusedAndAWindowMeasureCannotBeFilteredOn() {
        assertThatThrownBy(() -> run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Lag1\"],\"dates\":{\"from\":\"2026-07-07\"}}", risk()))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("needs that column as a field");
        assertThatThrownBy(() -> run("{\"fields\":[\"trades.tradeDate\"],\"measures\":[\"MTM\"],\"filters\":[{\"field\":\"Rank\",\"op\":\"gt\",\"values\":[1]}],\"dates\":{\"from\":\"2026-07-07\"}}", risk()))
                .isInstanceOf(DrishtiException.class);
    }

    @Test
    void theValidatorHoldsWindowFunctionsToTheirShape() {
        String base = TestLake.yaml();
        for (String bad : List.of("lag(sum(trades.mtm))", "lag(trades.mtm, 1, trades.tradeDate)", "lag(sum(trades.mtm), 0, trades.tradeDate)", "lag(sum(trades.mtm), 1.5, trades.tradeDate)",
                "lag(sum(trades.mtm), 1, trades)", "lead(sum(trades.mtm), 1, trades.nope)", "moving_avg(sum(trades.mtm), 2000, trades.tradeDate)", "rank(trades.mtm)",
                "rank(sum(trades.mtm), 'sideways')", "rank(sum(trades.mtm), desc=False, by=[3])", "rank(lag(sum(trades.mtm), 1, trades.tradeDate))", "rank()")) {
            DatasetParser.Result r = DatasetParser.parse(base.replace("measures:\n", "measures:\n  Bad: { expr: \"" + bad + "\" }\n"));
            assertThat(r.problems()).as(bad).anyMatch(DatasetParser.Problem::error);
        }
        String good = "measures:\n  Ok: { expr: \"lag(sum(trades.mtm), 1, trades.tradeDate, by=[trades.desk])\" }\n  Ok2: { expr: \"measure('Ok') + 1\" }\n";
        assertThat(DatasetParser.parse(base.replace("measures:\n", good)).problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
    }
}
