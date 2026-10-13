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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The query engine over a small generated lake: compilation per feature, joins, calculated columns, row rules and masks for several readers,
 * layer routing (rollup, columns, direct, live), caching and its invalidation on a data load, and the limits. Injection attempts are values and
 * names in the query; none may change the SQL's shape.
 */
class QueryEngineTest {

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        start(QueryProperties.defaults(store.toString()));
    }

    void start(QueryProperties props) {
        if (service != null) {
            service.close();
        }
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        DatasetParser.Result r = DatasetParser.parse(TestLake.yaml());
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), r.dataset(), "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    // ---- readers -----------------------------------------------------------------------------------------------------

    /** A reader: roles, the desks on their profile, and whether they hold {@code raw} (no masks). */
    QueryService.Caller who(String user, boolean raw, String desk, String... roles) {
        SqlCompiler.Masks masks = (t, c) -> !raw && (c.equals("cpty") || t.equals("trades") && c.equals("mtm"));
        return new QueryService.Caller(user, Set.of(roles), true, UserAttributes.of(user, List.of(roles), desk, null), masks);
    }

    QueryService.Caller risk() {
        return who("rita", true, "", "risk");
    }

    QueryRequest q(String body) {
        try {
            return QueryRequest.of(json.readTree(body));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    QueryService.Result run(String body, QueryService.Caller who) {
        return service.query(ds, q(body), who, true);
    }

    static Map<String, Object> rows(QueryService.Result r, String keyColumn, String valueColumn) {
        Map<String, Object> out = new java.util.TreeMap<>();
        ArrowIpcWriter.Column k = r.columns().stream().filter(c -> c.name().equals(keyColumn)).findFirst().orElseThrow();
        ArrowIpcWriter.Column v = r.columns().stream().filter(c -> c.name().equals(valueColumn)).findFirst().orElseThrow();
        for (int i = 0; i < r.rows(); i++) {
            out.put(String.valueOf(k.values()[i]), v.values()[i]);
        }
        return out;
    }

    double expectedSum(String desk, boolean mtm, java.time.LocalDate day) {
        return lake.trades.get(day).stream().filter(t -> desk == null || t.desk().equals(desk)).mapToDouble(t -> mtm ? t.mtm() : t.notional()).sum();
    }

    // ---- measures and fields -----------------------------------------------------------------------------------------

    @Test
    void sumsCountsAndDistinctByDeskMatchTheData() {
        QueryService.Result r = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\",\"MTM\",\"Trades\",\"Names\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        assertThat(r.rows()).isEqualTo(3);
        Map<String, Object> notional = rows(r, "trades.desk", "Notional");
        assertThat((double) notional.get("DESK-A")).isEqualTo(expectedSum("DESK-A", false, TestLake.DAY2));
        assertThat((double) rows(r, "trades.desk", "MTM").get("DESK-B")).isEqualTo(expectedSum("DESK-B", true, TestLake.DAY2));
        assertThat(rows(r, "trades.desk", "Trades").values()).allMatch(v -> ((Long) v) >= 13);
        assertThat(rows(r, "trades.desk", "Names").get("DESK-A")).isEqualTo(4L);
        assertThat(r.meta().layer()).isEqualTo("columns");
    }

    @Test
    void averageRatioPercentileMaxAndConditionalAggregates() {
        QueryService.Result r = run("{\"measures\":[\"AvgMTM\",\"MTMperM\",\"P50\",\"Biggest\",\"EurMTM\",\"Share\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        assertThat(r.rows()).isEqualTo(1);
        double mtm = expectedSum(null, true, TestLake.DAY2);
        double notional = expectedSum(null, false, TestLake.DAY2);
        assertThat((double) rows(r, "AvgMTM", "AvgMTM").get(String.valueOf(r.columns().get(0).values()[0]))).isEqualTo(mtm / 40, org.assertj.core.data.Offset.offset(1e-9));
        assertThat((double) r.columns().get(1).values()[0]).isEqualTo(mtm / notional * 1_000_000, org.assertj.core.data.Offset.offset(1e-6));
        assertThat((double) r.columns().get(3).values()[0]).isEqualTo(4001.0);                                        // 100 * 40 + 1
        double eur = lake.trades.get(TestLake.DAY2).stream().filter(t -> t.ccy().equals("EUR")).mapToDouble(TestLake.Trade::mtm).sum();
        assertThat((double) r.columns().get(4).values()[0]).isEqualTo(eur);
        assertThat((double) r.columns().get(5).values()[0]).isEqualTo(mtm / notional, org.assertj.core.data.Offset.offset(1e-12));    // a measure of measures
    }

    @Test
    void aCalculatedColumnAndDatePartsAreFields() {
        QueryService.Result r = run("{\"fields\":[\"trades.tradeDate.month\"],\"measures\":[\"MTM\"],\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-09\"}}", risk());
        assertThat(r.rows()).isEqualTo(1);
        assertThat(r.columns().get(0).values()[0]).isEqualTo(10L);
        QueryService.Result calc = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"MTM\"],\"filters\":[{\"field\":\"trades.mtmK\",\"op\":\"ge\",\"values\":[2]}],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        assertThat(calc.rows()).isGreaterThan(0);
        assertThat((double) calc.columns().get(1).values()[0]).isGreaterThan(0);
    }

    @Test
    void joinsAManyToOneLookupAndCountsNothingTwice() {
        QueryService.Result r = run("{\"fields\":[\"desks.region\"],\"measures\":[\"Trades\",\"Notional\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        Map<String, Object> trades = rows(r, "desks.region", "Trades");
        assertThat(((Long) trades.get("EMEA")) + ((Long) trades.get("AMER"))).isEqualTo(40L);
        assertThat((double) rows(r, "desks.region", "Notional").get("EMEA")).isEqualTo(expectedSum("DESK-A", false, TestLake.DAY2));
        assertThat(r.meta().tables()).contains("trades", "desks");
    }

    @Test
    void aListOfValuesWithNoMeasureIsDistinct() {
        QueryService.Result r = run("{\"fields\":[\"trades.desk\",\"desks.region\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        assertThat(r.rows()).isEqualTo(3);
    }

    @Test
    void filtersFilterAndOrderingOrders() {
        QueryService.Result r = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\"],\"filters\":[{\"field\":\"trades.currency\",\"op\":\"in\",\"values\":[\"USD\"]},"
                + "{\"field\":\"trades.notional\",\"op\":\"between\",\"values\":[500,3000]}],\"orderBy\":[{\"by\":\"Notional\",\"desc\":true}],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        double expected = lake.trades.get(TestLake.DAY2).stream().filter(t -> t.ccy().equals("USD") && t.notional() >= 500 && t.notional() <= 3000).mapToDouble(TestLake.Trade::notional).sum();
        double total = 0;
        double last = Double.MAX_VALUE;
        for (Object v : r.columns().get(1).values()) {
            assertThat((double) v).isLessThanOrEqualTo(last);
            last = (double) v;
            total += (double) v;
        }
        assertThat(total).isEqualTo(expected);
        QueryService.Result having = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\"],\"filters\":[{\"field\":\"Notional\",\"op\":\"gt\",\"values\":[1e12]}],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        assertThat(having.rows()).isZero();
    }

    @Test
    void measuresFromTwoUnconnectedTablesAreAskedSeparatelyAndShownSideBySide() {
        DatasetParser.Result two = DatasetParser.parse(TestLake.yaml().replace("relationships:", "  curves:\n    source: { connector: desk-lake, kind: desk }\n    columns: { point: string }\nrelationships:")
                .replace("  Names:", "  RegionCount: { expr: \"count(curves)\" }\n  Names:"));
        assertThat(two.ok()).as(two.firstError()).isTrue();
        QueryService.Loaded d2 = new QueryService.Loaded("x", "x", two.dataset(), "h");
        QueryService.Result both = service.query(d2, q("{\"measures\":[\"Trades\",\"RegionCount\"]}"), risk(), false);
        assertThat(both.rows()).isEqualTo(1);                                                    // two totals side by side: neither table is repeated by the other
        assertThat(((Number) both.columns().get(0).values()[0]).longValue()).isEqualTo(lake.trades.get(TestLake.DAY2).size());
        assertThatThrownBy(() -> service.query(d2, q("{\"fields\":[\"trades.desk\"],\"measures\":[\"Trades\",\"RegionCount\"]}"), risk(), false))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("no many-to-one path");
    }

    // ---- row rules and masks -----------------------------------------------------------------------------------------

    @Test
    void threeRolesSeeDifferentRowsFromTheSameQuery() {
        String body = "{\"fields\":[\"trades.desk\"],\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-09\"}}";
        assertThat(rows(run(body, who("hana", true, "DESK-A", "desk-head")), "trades.desk", "Trades").keySet()).containsExactly("DESK-A");
        assertThat(rows(run(body, who("hugo", true, "DESK-B; DESK-C", "desk-head")), "trades.desk", "Trades").keySet()).containsExactly("DESK-B", "DESK-C");
        assertThat(rows(run(body, risk()), "trades.desk", "Trades").keySet()).containsExactly("DESK-A", "DESK-B", "DESK-C");
        assertThat(rows(run(body, who("aud", true, "", "auditor")), "trades.desk", "Trades").keySet()).containsExactly("DESK-B", "DESK-C");
        QueryService.Result none = run(body, who("vic", true, "", "viewer"));
        assertThat(none.rows()).isZero();
        assertThat(none.meta().warnings()).anyMatch(w -> w.contains("no row rule"));
    }

    @Test
    void aReaderCannotRemoveTheRowRuleByFilteringOrJoining() {
        String body = "{\"fields\":[\"desks.region\"],\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.desk\",\"op\":\"in\",\"values\":[\"DESK-B\",\"DESK-C\"]}],\"dates\":{\"from\":\"2026-10-09\"}}";
        QueryService.Result r = run(body, who("hana", true, "DESK-A", "desk-head"));
        assertThat(r.rows()).isZero();                                        // DESK-A only, and the filter asks for B and C: nothing
        QueryService.Result sql = run("{\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.desk\",\"op\":\"eq\",\"values\":[\"x' OR 'a'='a\"]}]}", who("hana", true, "DESK-A", "desk-head"));
        assertThat(sql.meta().sql()).contains("(\"trades\".\"desk\" IN (?))").doesNotContain("OR 'a'");
        assertThat(sql.columns().get(0).values()[0]).isEqualTo(0L);
    }

    @Test
    void masksReplaceDimensionsNullMeasuresAndRefuseProbing() {
        QueryService.Caller analyst = who("ann", false, "", "risk");
        QueryService.Result r = run("{\"fields\":[\"trades.cpty\"],\"measures\":[\"MTM\",\"Notional\"],\"dates\":{\"from\":\"2026-10-09\"}}", analyst);
        assertThat(r.rows()).isEqualTo(1);                                    // not grouped on: one row, every trade in it
        assertThat(r.columns().get(0).values()[0]).isEqualTo(DataNode.MASK);
        assertThat(r.columns().get(1).values()[0]).isNull();                  // the measure over a masked column reads null
        assertThat((double) r.columns().get(2).values()[0]).isEqualTo(expectedSum(null, false, TestLake.DAY2));
        assertThat(r.meta().masked()).containsExactlyInAnyOrder("trades.cpty", "MTM");
        assertThatThrownBy(() -> run("{\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.cpty\",\"op\":\"eq\",\"values\":[\"CP-1\"]}]}", analyst))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("masked for you");
        assertThatThrownBy(() -> run("{\"fields\":[\"trades.cpty\"],\"orderBy\":[\"trades.cpty\"]}", analyst)).isInstanceOf(DrishtiException.class);
        QueryService.Result raw = run("{\"fields\":[\"trades.cpty\"],\"measures\":[\"MTM\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        assertThat(raw.rows()).isEqualTo(4);
    }

    @Test
    void threeReadersThreeAnswersForRowsAndMasksTogether() {
        String body = "{\"fields\":[\"trades.desk\",\"trades.cpty\"],\"measures\":[\"MTM\"],\"dates\":{\"from\":\"2026-10-09\"}}";
        QueryService.Result head = run(body, who("hana", false, "DESK-A", "desk-head"));
        assertThat(head.rows()).isEqualTo(1);
        assertThat(head.columns().get(0).values()[0]).isEqualTo("DESK-A");
        assertThat(head.columns().get(1).values()[0]).isEqualTo(DataNode.MASK);
        assertThat(head.columns().get(2).values()[0]).isNull();
        QueryService.Result rk = run(body, risk());
        assertThat(rk.rows()).isGreaterThan(3);
        assertThat(rk.columns().get(2).values()[0]).isNotNull();
        assertThat(run(body, who("vic", false, "", "viewer")).rows()).isZero();
    }

    // ---- injection ---------------------------------------------------------------------------------------------------

    @Test
    void namesAndValuesFromTheRequestNeverChangeTheSql() {
        for (String bad : List.of("trades.desk\"; DROP TABLE x; --", "trades.desk) OR (1=1", "1; select 1", "trades.desk' --", "\"trades\".\"desk\"", "trades.desk.year")) {
            assertThatThrownBy(() -> run("{\"fields\":[" + json.valueToTree(bad) + "],\"measures\":[\"Trades\"]}", risk())).as(bad).isInstanceOf(DrishtiException.class);
            assertThatThrownBy(() -> run("{\"measures\":[" + json.valueToTree(bad) + "]}", risk())).as(bad).isInstanceOf(DrishtiException.class);
            assertThatThrownBy(() -> run("{\"fields\":[\"trades.desk\"],\"orderBy\":[" + json.valueToTree(bad) + "]}", risk())).as(bad).isInstanceOf(DrishtiException.class);
        }
        for (String evil : List.of("USD' OR '1'='1", "'; DROP TABLE trades; --", "USD\") OR (\"1\"=\"1", "%", "\\")) {
            QueryService.Result r = run("{\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.currency\",\"op\":\"eq\",\"values\":[" + json.valueToTree(evil) + "]}]}", risk());
            assertThat(r.columns().get(0).values()[0]).as(evil).isEqualTo(0L);
            assertThat(r.meta().sql()).doesNotContain("DROP").doesNotContain("1'='1");
        }
        assertThatThrownBy(() -> run("{\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.notional\",\"op\":\"gt\",\"values\":[\"1 OR 1=1\"]}]}", risk()))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("not a number");
        assertThatThrownBy(() -> run("{\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"trades.tradeDate\",\"op\":\"eq\",\"values\":[\"2026-01-01' OR 1=1\"]}]}", risk()))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("not a date");
        assertThatThrownBy(() -> run("{\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-09' OR 1=1\"}}", risk())).isInstanceOf(DrishtiException.class);
        // the explain text carries placeholders for every value
        QueryService.Result r = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"EurMTM\"],\"filters\":[{\"field\":\"trades.desk\",\"op\":\"eq\",\"values\":[\"DESK-A\"]}]}", risk());
        assertThat(r.meta().sql()).doesNotContain("DESK-A").doesNotContain("EUR").contains("?");
    }

    // ---- layers and routing ------------------------------------------------------------------------------------------

    @Test
    void aRollupAnswersWhatItCoversAndColumnsAnswerTheRest() {
        QueryService.Result r = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\",\"MTM\",\"AvgMTM\",\"MTMperM\",\"Biggest\",\"Share\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        assertThat(r.meta().layer()).isEqualTo("rollup");
        assertThat(r.meta().rollup()).isEqualTo("byDesk");
        assertThat((double) rows(r, "trades.desk", "Notional").get("DESK-A")).isEqualTo(expectedSum("DESK-A", false, TestLake.DAY2));
        assertThat((double) rows(r, "trades.desk", "MTM").get("DESK-C")).isEqualTo(expectedSum("DESK-C", true, TestLake.DAY2));
        double avgA = lake.trades.get(TestLake.DAY2).stream().filter(t -> t.desk().equals("DESK-A")).mapToDouble(TestLake.Trade::mtm).average().orElseThrow();
        assertThat((double) rows(r, "trades.desk", "AvgMTM").get("DESK-A")).isEqualTo(avgA, org.assertj.core.data.Offset.offset(1e-9));
        // the same numbers from the columns layer
        QueryService.Result cols = run("{\"layer\":\"columns\",\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\",\"MTM\",\"AvgMTM\",\"MTMperM\",\"Biggest\",\"Share\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        assertThat(cols.meta().layer()).isEqualTo("columns");
        for (String m : List.of("Notional", "MTM", "AvgMTM", "MTMperM", "Biggest", "Share")) {
            Map<String, Object> a = rows(r, "trades.desk", m);
            Map<String, Object> b = rows(cols, "trades.desk", m);
            for (String desk : a.keySet()) {
                assertThat((double) a.get(desk)).as(m + " " + desk).isEqualTo((double) b.get(desk), org.assertj.core.data.Offset.offset(1e-9));
            }
        }
        // distinct counts and percentiles are not additive: the columns layer answers
        assertThat(run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Names\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk()).meta().layer()).isEqualTo("columns");
        // a column the rollup does not group by
        assertThat(run("{\"fields\":[\"trades.cpty\"],\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk()).meta().layer()).isEqualTo("columns");
        // a join to another table
        assertThat(run("{\"fields\":[\"desks.region\"],\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk()).meta().layer()).isEqualTo("columns");
        // a row rule on a column the rollup groups by keeps the rollup
        assertThat(run("{\"fields\":[\"trades.currency\"],\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-09\"}}", who("hana", true, "DESK-A", "desk-head")).meta().layer()).isEqualTo("rollup");
    }

    @Test
    void theColumnsLayerIsBuiltOncePerDateAndReusedAcrossQueries() {
        assertThat(lake.scans.get()).isZero();
        run("{\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        int afterFirst = lake.scans.get();
        assertThat(afterFirst).isEqualTo(1);                                  // one scan of the trades connector for the one date
        run("{\"fields\":[\"trades.desk\"],\"measures\":[\"MTM\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk());
        run("{\"fields\":[\"trades.currency\"],\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-09\"},\"layer\":\"columns\"}", risk());
        assertThat(lake.scans.get()).isEqualTo(1);                            // the layer is on disk: other queries over the date read no source
        run("{\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-08\"}}", risk());
        assertThat(lake.scans.get()).isEqualTo(2);                            // another date reads the source once more
    }

    @Test
    void theLatestDateIsTheCurrentBusinessDate() {
        QueryService.Result r = run("{\"fields\":[\"trades.tradeDate\"],\"measures\":[\"Trades\"]}", risk());
        assertThat(r.meta().dates()).containsExactly("2026-10-09");
        assertThat(r.columns().get(0).values()[0]).isEqualTo("2026-10-09");
    }

    @Test
    void aDateRangeReadsEveryDateAndADateWithNoDataIsEmptyNotAnError() {
        QueryService.Result r = run("{\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-07\",\"to\":\"2026-10-10\"}}", risk());
        assertThat(r.columns().get(0).values()[0]).isEqualTo(80L);
        assertThat(r.meta().dates()).containsExactly("2026-10-08", "2026-10-09");
        assertThat(run("{\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-01-01\"}}", risk()).columns().get(0).values()[0]).isEqualTo(0L);
    }

    @Test
    void aConnectorThatCannotScanIsReadRecordByRecordWithAWarning() {
        lake = new TestLake(false);
        start(QueryProperties.defaults(store.toString()));
        QueryService.Result r = run("{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-09\"},\"layer\":\"columns\"}", risk());
        assertThat((double) rows(r, "trades.desk", "Notional").get("DESK-A")).isEqualTo(expectedSum("DESK-A", false, TestLake.DAY2));
        assertThat(r.meta().warnings()).anyMatch(w -> w.contains("cannot scan trade") && w.contains("slow"));
        assertThat(lake.recordReads.get()).isEqualTo(40);
    }

    @Test
    void aTableOverAnArrayHasOneRowPerElementAndJoinsToItsParent() {
        String yaml = """
                dataset: legs
                tables:
                  trades:
                    source: { connector: trading-lake, kind: trade }
                    columns: { desk: string }
                  legs:
                    source: { connector: trading-lake, kind: trade, each: legs }
                    columns:
                      payReceive: string
                      notional: decimal
                      currency: string
                relationships:
                  - { from: legs.id, to: trades.id, cardinality: many-to-one }
                measures:
                  LegNotional: { expr: "sum(legs.notional)" }
                  Legs: { expr: "count(legs)" }
                """;
        QueryService.Loaded legs = new QueryService.Loaded("legs", "legs", DatasetParser.parse(yaml).dataset(), "h");
        QueryService.Result r = service.query(legs, q("{\"fields\":[\"trades.desk\",\"legs.payReceive\"],\"measures\":[\"Legs\",\"LegNotional\"],\"dates\":{\"from\":\"2026-10-09\"}}"), risk(), true);
        assertThat(r.rows()).isEqualTo(6);
        assertThat(rows(r, "trades.desk", "Legs").values()).allMatch(v -> ((Long) v) >= 13);
        double total = 0;
        for (Object v : r.columns().get(3).values()) {
            total += (double) v;
        }
        assertThat(total).isEqualTo(lake.trades.get(TestLake.DAY2).stream().mapToDouble(t -> 2000.0 * (Integer.parseInt(t.id().substring(5)) + 1)).sum());
        assertThat(r.meta().warnings()).anyMatch(w -> w.contains("read once per business date"));
    }

    // ---- cache and data loads ----------------------------------------------------------------------------------------

    @Test
    void identicalQueriesAreCachedAndADataLoadInvalidatesExactlyWhatItChanged() throws Exception {
        String body = "{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-09\"}}";
        QueryService.Result first = service.query(ds, q(body), risk(), false);
        assertThat(first.meta().cached()).isFalse();
        QueryService.Result second = service.query(ds, q(body), risk(), false);
        assertThat(second.meta().cached()).isTrue();
        assertThat(rows(second, "trades.desk", "Notional")).isEqualTo(rows(first, "trades.desk", "Notional"));
        // a different reader is a different cache entry (row rules)
        assertThat(service.query(ds, q(body), who("hana", true, "DESK-A", "desk-head"), false).meta().cached()).isFalse();
        // the data changes and a load signal arrives for another date: this answer is still served
        lake.trades.put(TestLake.DAY2, lake.trades.get(TestLake.DAY2).stream().map(t -> new TestLake.Trade(t.id(), t.desk(), t.ccy(), t.cpty(), t.notional() * 2, t.mtm(), t.tradeDate(), t.legs())).toList());
        service.onLoad("trade", TestLake.DAY1, List.of(ds));
        assertThat(service.query(ds, q(body), risk(), false).meta().cached()).isFalse();   // the dataset's generation moved: nothing stale is served
        // the load for the date: rebuilt from the source, new numbers
        service.onLoad("trade", TestLake.DAY2, List.of(ds));
        Thread.sleep(200);                                                                    // the eager rebuild runs in the background
        QueryService.Result third = service.query(ds, q(body), risk(), false);
        assertThat((double) rows(third, "trades.desk", "Notional").get("DESK-A")).isEqualTo(expectedSum("DESK-A", false, TestLake.DAY2));
        assertThat(third.meta().cached()).isFalse();
        // a load for a kind the dataset does not read changes nothing
        long gen = service.layers().generation(ds.key());
        assertThat(service.onLoad("curve", TestLake.DAY2, List.of(ds))).isEmpty();
        assertThat(service.layers().generation(ds.key())).isEqualTo(gen);
        assertThat(service.query(ds, q(body), risk(), false).meta().cached()).isTrue();
    }

    @Test
    void aChangedTableDefinitionRebuildsTheLayer() {
        String body = "{\"fields\":[\"trades.desk\"],\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-09\"}}";
        run(body, risk());
        int before = lake.scans.get();
        run(body, risk());
        assertThat(lake.scans.get()).isEqualTo(before);                       // reused from disk
        String changed = TestLake.yaml().replace("notional: decimal\n", "notional: { path: notional, type: integer }\n");
        QueryService.Loaded d2 = new QueryService.Loaded(ds.id(), ds.key(), DatasetParser.parse(changed).dataset(), "h2");
        service.query(d2, q(body), risk(), true);
        assertThat(lake.scans.get()).isGreaterThan(before);                   // a different signature: read again
    }

    // ---- limits ------------------------------------------------------------------------------------------------------

    @Test
    void rowLimitsAreEnforcedWithTheirCode() {
        QueryProperties small = new QueryProperties(store.toString(), null, 5, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        start(small);
        assertThatThrownBy(() -> run("{\"fields\":[\"trades.id\"],\"dates\":{\"from\":\"2026-10-09\"}}", risk()))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("more than 5 rows").extracting(e -> ((DrishtiException) e).errorCode().code()).isEqualTo("DRS-9022");
        assertThat(run("{\"fields\":[\"trades.id\"],\"limit\":3,\"dates\":{\"from\":\"2026-10-09\"}}", risk()).rows()).isEqualTo(3);
    }

    @Test
    void theEngineStopsAQueryAtTheTimeLimit() {
        AnalyticEngine engine = new DuckDbEngine(QueryProperties.defaults(store.toString()));
        assertThatThrownBy(() -> engine.query("SELECT count(*) FROM range(100000000000) a, range(1000000) b", List.of(), 10, java.time.Duration.ofMillis(300)))
                .isInstanceOf(DrishtiException.class).extracting(e -> ((DrishtiException) e).errorCode().code()).isEqualTo("DRS-9024");
        engine.close();
    }

    @Test
    void concurrencyIsLimitedPerUserAndPerServerWithDrs9025() throws Exception {
        QueryProperties tight = new QueryProperties(store.toString(), null, null, null, 1, 1, null, null, null, null, null, null, null, null, null, null, null, null, java.time.Duration.ofMillis(100), null, null);
        start(tight);
        lake.scanDelayMillis = 700;                                           // the first query holds its slot while it builds the layer
        String body = "{\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-09\"}}";
        CompletableFuture<QueryService.Result> slow = CompletableFuture.supplyAsync(() -> service.query(ds, q(body), who("u1", true, "", "risk"), false));
        Thread.sleep(150);
        assertThatThrownBy(() -> service.query(ds, q(body), who("u1", true, "", "risk"), false)).isInstanceOf(DrishtiException.class)
                .extracting(e -> ((DrishtiException) e).errorCode().code()).isEqualTo("DRS-9025");           // the same user: the per-user limit
        assertThatThrownBy(() -> service.query(ds, q("{\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-09\"}}"), who("u2", true, "", "risk"), false))
                .isInstanceOf(DrishtiException.class).extracting(e -> ((DrishtiException) e).errorCode().code()).isEqualTo("DRS-9025");    // another user: the server limit
        assertThat(slow.get().rows()).isEqualTo(1);
        assertThat(service.stats().get("rejectedBusy")).isEqualTo(2L);
    }

    // ---- live and direct ---------------------------------------------------------------------------------------------

    @Test
    void aLiveTableIsASnapshotOfTheCurrentStateServedForTheTtl() throws Exception {
        DatasetParser.Result live = DatasetParser.parse(TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade }\n    mode: live"));
        QueryService.Loaded l = new QueryService.Loaded("live", "live", live.dataset(), "h");
        QueryService.Result a = service.query(l, q("{\"measures\":[\"Trades\"]}"), risk(), true);
        assertThat(a.meta().layer()).isEqualTo("live");
        assertThat(a.columns().get(0).values()[0]).isEqualTo(40L);
        int scans = lake.scans.get();
        service.query(l, q("{\"measures\":[\"Notional\"]}"), risk(), true);
        assertThat(lake.scans.get()).isEqualTo(scans);                        // inside the ttl: the snapshot is reused
    }

    @Test
    void aDirectTableIsPushedDownToTheStoreWithBoundValues() {
        // a SQL store reachable through a SqlTarget: here the engine's own DuckDB holds an entities table the way the DuckDB connector lays it out
        AnalyticEngine engine = new DuckDbEngine(QueryProperties.defaults(store.toString()));
        engine.execute("CREATE TABLE entities (kind VARCHAR, id VARCHAR, business_date DATE, doc VARCHAR, \"desk\" VARCHAR, \"ccy\" VARCHAR, \"notional\" DOUBLE, \"mtm\" DOUBLE)", List.of());
        for (TestLake.Trade t : lake.trades.get(TestLake.DAY2)) {
            engine.execute("INSERT INTO entities VALUES ('trade', ?, DATE '2026-10-09', '{}', ?, ?, ?, ?)", List.of(t.id(), t.desk(), t.ccy(), t.notional(), t.mtm()));
        }
        com.ash.drishti.api.SqlTarget target = new com.ash.drishti.api.SqlTarget() {
            @Override
            public String dialect() {
                return "duckdb";
            }

            @Override
            public Relation relation(String kind) {
                Set<String> cols = Set.of("desk", "ccy", "notional", "mtm");
                return new Relation("entities", "kind", "business_date", "id", p -> cols.contains(p) ? "\"" + p + "\"" : null);
            }

            @Override
            public <T> T with(Work<T> work) throws Exception {
                return engine.with(c -> work.run(c));
            }
        };
        TestLake direct = new TestLake(true) {
            @Override
            public java.util.Optional<com.ash.drishti.api.SourcePlugin> plugin(String connector, String kind) {
                return super.plugin(connector, kind).map(p -> p == tradingLake ? new SqlStore(p, target) : p);
            }
        };
        lake = direct;
        start(QueryProperties.defaults(store.toString()));
        String yaml = """
                dataset: direct
                tables:
                  trades:
                    source: { connector: trading-lake, kind: trade }
                    mode: direct
                    columns: { desk: string, currency: { path: ccy, type: string }, notional: decimal, mtm: decimal }
                measures:
                  Notional: { expr: "sum(trades.notional)" }
                  MTM: { expr: "sum(trades.mtm, where=trades.currency == 'EUR')" }
                security:
                  rows: [ { role: desk-head, where: "trades.desk in user.desks" } ]
                """;
        QueryService.Loaded d = new QueryService.Loaded("direct", "direct", DatasetParser.parse(yaml).dataset(), "h");
        QueryService.Result r = service.query(d, q("{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\",\"MTM\"],\"filters\":[{\"field\":\"trades.currency\",\"op\":\"in\",\"values\":[\"USD\",\"EUR\"]}]}"),
                who("hana", true, "DESK-A", "desk-head"), true);
        assertThat(r.meta().layer()).isEqualTo("direct");
        assertThat(rows(r, "trades.desk", "Notional").keySet()).containsExactly("DESK-A");
        assertThat((double) rows(r, "trades.desk", "Notional").get("DESK-A")).isEqualTo(expectedSum("DESK-A", false, TestLake.DAY2));
        assertThat(r.meta().sql()).contains("FROM entities").contains("\"kind\"".replace("\"", "")).doesNotContain("USD");
        assertThat(lake.scans.get()).isZero();                                // the source was never scanned: the store ran the query
        // a connector that is not a SQL store cannot serve a direct table
        QueryService.Loaded bad = new QueryService.Loaded("bad", "bad", DatasetParser.parse(yaml.replace("connector: trading-lake", "connector: desk-lake").replace("kind: trade", "kind: desk")
                .replace("columns: { desk: string, currency: { path: ccy, type: string }, notional: decimal, mtm: decimal }", "columns: { desk: string }")
                .replace("sum(trades.notional)", "count(trades)").replace("sum(trades.mtm, where=trades.currency == 'EUR')", "count(trades)")).dataset(), "h");
        assertThatThrownBy(() -> service.query(bad, q("{\"measures\":[\"Notional\"]}"), risk(), false)).isInstanceOf(DrishtiException.class).hasMessageContaining("not a SQL store");
        engine.close();
    }

    /** A connector that is also a SQL store. */
    static final class SqlStore implements com.ash.drishti.api.SourcePlugin {
        private final com.ash.drishti.api.SourcePlugin inner;
        private final com.ash.drishti.api.SqlTarget target;

        SqlStore(com.ash.drishti.api.SourcePlugin inner, com.ash.drishti.api.SqlTarget target) {
            this.inner = inner;
            this.target = target;
        }

        @Override
        public com.ash.drishti.api.PluginManifest manifest() {
            return inner.manifest();
        }

        @Override
        public void start(com.ash.drishti.api.SourceContext context) {}

        @Override
        public java.util.Optional<com.ash.drishti.api.EntityDocument> fetch(com.ash.drishti.api.EntityRef ref) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.ash.drishti.api.SqlTarget> sqlTarget() {
            return java.util.Optional.of(target);
        }
    }

    @Test
    void layerFilesAreTypedParquetBesideEachOtherPerDate() throws Exception {
        run("{\"measures\":[\"Trades\"],\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-09\"}}", risk());
        Path dir = store.resolve("columns").resolve(ds.key()).resolve("trades");
        List<String> names = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.forEach(p -> names.add(p.getFileName().toString()));
        }
        assertThat(names).containsExactlyInAnyOrder("date=2026-10-08", "date=2026-10-09");
        assertThat(dir.resolve("date=2026-10-09").resolve("part.parquet")).exists();
        assertThat(dir.resolve("date=2026-10-09").resolve("meta.json")).exists();
        AnalyticEngine engine = new DuckDbEngine(QueryProperties.defaults(store.toString()));
        AnalyticEngine.Answer a = engine.query("SELECT typeof(\"notional\") AS n, typeof(\"tradeDate\") AS d, typeof(\"businessDate\") AS b FROM read_parquet('"
                + dir.resolve("date=2026-10-09").resolve("part.parquet") + "') LIMIT 1", List.of(), 1, java.time.Duration.ofSeconds(10));
        assertThat(a.columns().get(0).values()[0]).isEqualTo("DOUBLE");
        assertThat(a.columns().get(1).values()[0]).isEqualTo("DATE");
        assertThat(a.columns().get(2).values()[0]).isEqualTo("DATE");
        engine.close();
    }
}
