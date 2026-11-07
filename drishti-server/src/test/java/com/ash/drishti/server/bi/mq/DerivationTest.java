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

import com.ash.drishti.server.bi.Layered;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.TestLake;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.plan.LogicalPlan;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dataset derivation (docs/architecture/BI_DESIGN.md section 3.1): a narrowing dataset keeps a subset of its base's columns, filters, keeps one row per key
 * (the latest by a column, never a silent distinct) and derives columns along a pack reference ({@code related}); each answers what the same
 * arithmetic over the base answers, and the plan shows the derivation above the dataset's scan. Grouping to a coarser grain is a summary table of
 * the layer (BI_FOUNDATION_2911E.md Q13), read through its model's answer, not a derivation.
 */
class DerivationTest {

    static final String TOP_PER_DESK = """
            rachana: 2
            expressions: 2
            dataset: top-per-desk
            version: 1.0.0
            extends: trades@1
            columns: [desk, currency, notional, mtm]
            where: "notional > 0"
            grain: { one-row-per: desk, keep: latest by notional }
            derive:
              notionalK: { expr: "notional / 1000", type: decimal }
              region:    { expr: "related(desk.region)", type: string }
            """;

    static final String DESK_CCY = """
            rachana: 2
            expressions: 2
            dataset: desk-ccy
            version: 1.0.0
            extends: trades@1
            derive:
              notionalK: { expr: "notional / 1000", type: decimal }
            group:
              by: [desk, currency]
              columns: { notional: sum, notionalK: sum, mtm: sum, trades: count }
              grain: { says: One row per desk and currency }
            security:
              rows:
                - { role: desk-head, where: "desk in user.desks" }
                - { role: [risk, analyst], where: "true" }
            """;

    /** A where on a column the grouping drops: it filters the rows that are grouped, never the groups (the console's wizard found it applied after). */
    static final String DESK_USD = """
            rachana: 2
            expressions: 2
            dataset: desk-usd
            version: 1.0.0
            extends: trades@1
            where: "currency == 'USD'"
            group:
              by: [desk]
              columns: { notional: sum, trades: count }
            """;

    static final String MODEL = """
            rachana: 2
            expressions: 2
            model: derived
            version: 1.0.0
            datasets:
              top:    { dataset: top-per-desk, role: fact }
              groups: { dataset: desk-ccy, role: fact }
              trades: { dataset: trades, role: fact }
              usd:    { dataset: desk-usd, role: fact }
            measures:
              Top notional:    { expr: "sum(top.notional)" }
              Top rows:        { expr: "count(top)" }
              Top K:           { expr: "sum(top.notionalK)" }
              Group notional:  { expr: "sum(groups.notional)" }
              Group K:         { expr: "sum(groups.notionalK)" }
              Group trades:    { expr: "sum(groups.trades)" }
              Group rows:      { expr: "count(groups)" }
              Notional:        { expr: "sum(trades.notional)" }
              Trades:          { expr: "count(trades)" }
              Biggest:         { expr: "max(trades.notional)" }
              USD notional:    { expr: "sum(usd.notional)" }
              USD trades:      { expr: "sum(usd.trades)" }
            security:
              may-see-all: { top: [desk-head], trades: [desk-head], usd: [desk-head] }
            """;

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    MqHarness h;
    ModelView model;
    MqService mq = new MqService(MqProperties.defaults());

    static Function<String, Optional<DatasetDef>> kinds() {
        DatasetDef desks = Layered.tableDef(MqEngineTest.DESKS).dataset();
        return k -> "desk".equals(k) ? Optional.of(desks) : Optional.empty();
    }

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(MODEL, kinds(), MqEngineTest.TRADES, TOP_PER_DESK, DESK_CCY, DESK_USD);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @SuppressWarnings("unchecked")
    Map<String, List<Object>> ask(MqHarness.Reader who, String yaml) {
        Map<String, Object> a = mq.ask(h.bind(model, who, "derived"), MqParser.parse(yaml), false).body();
        Map<String, List<Object>> out = new TreeMap<>();
        for (List<Object> r : (List<List<Object>>) a.get("rows")) {
            out.put(String.valueOf(r.get(0)), r.subList(1, r.size()));
        }
        return out;
    }

    static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    @Test
    void keepingOneRowPerKeyIsTheLatestRowOfEachKeyNeverADistinct() {
        Map<String, List<Object>> top = ask(MqHarness.RITA, "{ mq: 1, model: derived, select: { fields: [top.desk], measures: [Top notional, Top rows, Top K] }, time: { as-of: 2026-10-09 } }");
        Map<String, List<Object>> base = ask(MqHarness.RITA, "{ mq: 1, model: derived, select: { fields: [trades.desk], measures: [Biggest] }, time: { as-of: 2026-10-09 } }");
        assertThat(top.keySet()).containsExactly("DESK-A", "DESK-B", "DESK-C");
        top.forEach((desk, v) -> {
            assertThat(d(v.get(1))).as(desk + ": one row").isEqualTo(1.0);
            assertThat(d(v.get(0))).as(desk + ": the latest by notional is the biggest").isEqualTo(d(base.get(desk).get(0)));
            assertThat(d(v.get(2))).as("the derived column on the kept row").isEqualTo(d(v.get(0)) / 1000);
        });
        Map<String, List<Object>> region = ask(MqHarness.RITA, "{ mq: 1, model: derived, select: { fields: [top.region], measures: [Top rows] }, time: { as-of: 2026-10-09 } }");
        assertThat(region).as("related(desk.region): the desk's region along the pack reference").containsOnlyKeys("AMER", "EMEA");
        assertThat(d(region.get("AMER").get(0))).isEqualTo(2.0);
    }

    @Test
    void thePlanShowsTheDerivationAboveTheScan() {
        MqService.Bound b = h.bind(model, MqHarness.RITA, "derived");
        LogicalPlan p = mq.plan(b, MqParser.parse("{ mq: 1, model: derived, select: { fields: [top.region], measures: [Top K] } }"));
        String tree = p.tree().toString();
        assertThat(tree).contains("op=Derive").contains("related(top__desk.region)").contains("op=Dedup").contains("keep=latest by notional");
    }

    @Test
    void checkRefusesWhatADerivationCannotDo() {
        DatasetDef base = Layered.tableDef(MqEngineTest.TRADES).dataset();
        assertThat(codes(TOP_PER_DESK.replace("columns: [desk, currency, notional, mtm]", "columns: [desk, nope]"), base)).as("a column the base lacks").isNotEmpty();
        assertThat(codes(TOP_PER_DESK.replace("keep: latest by notional", "keep: latest by ghost"), base)).contains("DRS-9104");
        assertThat(codes(TOP_PER_DESK.replace("keep: latest by notional", "keep: whichever"), base)).contains("DRS-9104");
        assertThat(codes(TOP_PER_DESK.replace("related(desk.region)", "related(currency.region)"), base)).contains("DRS-9101");
        assertThat(codes(TOP_PER_DESK.replace("related(desk.region)", "related(desk.planet)"), base)).contains("DRS-9101");
        assertThat(codes(DESK_CCY.replace("desk in user.desks", "notional > 0"), base)).contains("DRS-9106");
        assertThat(codes(DESK_CCY.replace("trades: count", "trades: median"), base)).contains("DRS-9101");
        assertThat(codes(TOP_PER_DESK.replace("notionalK: { expr: \"notional / 1000\", type: decimal }", "notionalK: { expr: \"sum(notional)\", type: decimal }"), base))
                .contains("DRS-9127");                                                         // Rachana: an aggregate in a row
        assertThat(Layered.tableDef(DESK_CCY).dataset().columns().keySet())                       // a grouping is a summary table of its rows' model: its own columns
                .containsExactly("desk", "currency", "notional", "notionalK", "mtm", "trades");
    }

    static List<String> codes(String yaml, DatasetDef base) {
        DatasetDefParser.Result r = Layered.tableDef(yaml);
        List<String> out = new java.util.ArrayList<>(r.problems().stream().filter(com.ash.drishti.server.bi.dataset.DatasetParser.Problem::error)
                .map(com.ash.drishti.server.bi.dataset.DatasetParser.Problem::code).toList());
        if (r.dataset() != null) {
            com.ash.drishti.server.bi.layer.Derivations.check(r.dataset(), base, kinds()).forEach(p -> out.add(p.code()));
        }
        return out;
    }
}
