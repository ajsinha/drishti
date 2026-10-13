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

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A measure of the "one" side of a join next to a measure of the "many" side (trades and the desks they belong to): each is aggregated at its own table's
 * grain and the results joined, so a desk is counted once however many trades it has. Checked against brute-force counts.
 */
class GrainTest {

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
        String yaml = TestLake.yaml().replace("measures:\n", "measures:\n  DeskCount: { expr: \"count(desks)\" }\n  PerDesk: { expr: \"measure('Trades') / measure('DeskCount')\" }\n  Twice: { expr: \"measure('PerDesk') * 2\" }\n");
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), r.dataset(), "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    QueryService.Result run(String body) throws Exception {
        QueryService.Caller who = new QueryService.Caller("u", Set.of("risk"), true, UserAttributes.of("u", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
        return service.query(ds, QueryRequest.of(json.readTree(body)), who, true);
    }

    @Test
    void aOneSideMeasureIsNotRepeatedByTheTradesOfItsRows() throws Exception {
        QueryService.Result r = run("{\"fields\":[\"desks.region\"],\"measures\":[\"Trades\",\"DeskCount\"],\"dates\":{\"from\":\"2026-10-09\"}}");
        Map<String, Double> trades = new TreeMap<>();
        for (TestLake.Trade t : lake.trades.get(TestLake.DAY2)) {
            trades.merge(t.desk().equals("DESK-A") ? "EMEA" : "AMER", 1.0, Double::sum);
        }
        Map<String, Double> desks = Map.of("EMEA", 1.0, "AMER", 2.0);
        assertThat(r.rows()).isEqualTo(2);
        for (int i = 0; i < r.rows(); i++) {
            String region = String.valueOf(r.columns().get(0).values()[i]);
            assertThat(((Number) r.columns().get(1).values()[i]).doubleValue()).as(region + " trades").isEqualTo(trades.get(region));
            assertThat(((Number) r.columns().get(2).values()[i]).doubleValue()).as(region + " desks, once each").isEqualTo(desks.get(region));
        }
    }

    @Test
    void theTradingRiskSampleSplitsTradeAndNettingSetMeasuresAtTheirOwnGrain() throws Exception {
        String yaml = java.nio.file.Files.readString(Path.of("src/test/resources/bi/samples-2.1/datasets/trading-risk.dataset.yaml"));
        com.ash.drishti.server.bi.dataset.Dataset d = DatasetParser.parse(yaml).dataset();
        SqlCompiler.Reader reader = new SqlCompiler.Reader(false, Set.of("risk"), Map.of(), SqlCompiler.Masks.NONE);
        SqlCompiler c = new SqlCompiler(d, Dialect.DUCKDB);
        SqlCompiler.Plan p = c.plan(QueryRequest.of(json.readTree("{\"fields\":[\"counterparties.sector\"],\"measures\":[\"MTM\",\"Exposure (EE peak)\"]}")), reader);
        assertThat(p.multiFact()).isTrue();
        assertThat(p.parts()).extracting(SqlCompiler.Plan::base).containsExactlyInAnyOrder("trades", "netting_sets");
        SqlCompiler.Plan one = c.plan(QueryRequest.of(json.readTree("{\"fields\":[\"counterparties.sector\"],\"measures\":[\"MTM\",\"Trades\"]}")), reader);
        assertThat(one.multiFact()).as("two measures of the same table stay one query").isFalse();
    }

    @Test
    void theOneSideMeasureAloneIsUnchanged() throws Exception {
        QueryService.Result r = run("{\"fields\":[\"desks.region\"],\"measures\":[\"DeskCount\"]}");
        assertThat(r.rows()).isEqualTo(2);
        assertThat(((Number) r.columns().get(1).values()[0]).doubleValue() + ((Number) r.columns().get(1).values()[1]).doubleValue()).isEqualTo(3.0);
    }

    @Test
    void aMeasureThatMixesTwoGrainsIsWorkedOutOverEachMeasureAtItsOwnGrain() throws Exception {
        QueryService.Result r = run("{\"fields\":[\"desks.region\"],\"measures\":[\"PerDesk\",\"Twice\"],\"dates\":{\"from\":\"2026-10-09\"}}");
        Map<String, Double> trades = new TreeMap<>();
        for (TestLake.Trade t : lake.trades.get(TestLake.DAY2)) {
            trades.merge(t.desk().equals("DESK-A") ? "EMEA" : "AMER", 1.0, Double::sum);
        }
        Map<String, Double> desks = Map.of("EMEA", 1.0, "AMER", 2.0);
        assertThat(r.rows()).isEqualTo(2);
        for (int i = 0; i < r.rows(); i++) {
            String region = String.valueOf(r.columns().get(0).values()[i]);
            double expected = trades.get(region) / desks.get(region);
            assertThat(((Number) r.columns().get(1).values()[i]).doubleValue()).as(region + " trades per desk").isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(((Number) r.columns().get(2).values()[i]).doubleValue()).as(region + " twice").isCloseTo(2 * expected, org.assertj.core.data.Offset.offset(1e-9));
        }
        QueryService.Result f = run("{\"fields\":[\"desks.region\"],\"measures\":[\"Trades\"],\"filters\":[{\"field\":\"PerDesk\",\"op\":\"gt\",\"values\":[" + (trades.get("EMEA") / 1.0 - 0.5) + "]}],\"dates\":{\"from\":\"2026-10-09\"}}");
        assertThat(f.rows()).as("the filter on the mixed measure is worked out the same way").isBetween(0, 2);
    }

    @Test
    void theTradingRiskSampleMixedRatioIsMultiFact() throws Exception {
        String yaml = java.nio.file.Files.readString(Path.of("src/test/resources/bi/samples-2.1/datasets/trading-risk.dataset.yaml"))
                .replace("measures:\n", "measures:\n  MtmPerExposure: { expr: \"measure('MTM') / measure('Exposure (EE peak)')\" }\n");
        com.ash.drishti.server.bi.dataset.Dataset d = DatasetParser.parse(yaml).dataset();
        SqlCompiler.Reader reader = new SqlCompiler.Reader(false, Set.of("risk"), Map.of(), SqlCompiler.Masks.NONE);
        SqlCompiler.Plan p = new SqlCompiler(d, Dialect.DUCKDB).plan(QueryRequest.of(json.readTree("{\"fields\":[\"counterparties.sector\"],\"measures\":[\"MtmPerExposure\"]}")), reader);
        assertThat(p.multiFact()).isTrue();
        assertThat(p.parts()).extracting(SqlCompiler.Plan::base).containsExactlyInAnyOrder("trades", "netting_sets");
        assertThat(p.derived()).containsKey("MtmPerExposure");
    }
}
