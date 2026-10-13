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
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model query language over a model of {@code rachana: 2} datasets on the engine's small lake: answers, every refusal of the validation with its code
 * and fix, the compiler's relative windows, top-N, drill, having and totals, security bound per dataset (a desk-head's rule cuts trades only; a dimension's
 * rule reaches its facts; a model's rules only ever remove rows), and the W1 guards (currency, semi-additive).
 */
class MqEngineTest {

    static final String TRADES = """
            rachana: 2
            dataset: trades
            version: 1.0.0
            source: { connector: trading-lake, kind: trade }
            grain: { keys: [id] }
            columns:
              desk:      { type: string, references: desk }
              currency:  { type: string, path: ccy }
              cpty:      { type: string, path: counterparty.id }
              notional:  { type: decimal, unit: { currency: currency }, aggregate: sum }
              mtm:       { type: decimal, unit: { currency: USD }, aggregate: sum }
              tradeDate: { type: date }
            security:
              masked: [cpty]
            """;
    static final String DESKS = """
            rachana: 2
            dataset: desks
            version: 1.0.0
            source: { connector: desk-lake, kind: desk }
            grain: { keys: [desk] }
            columns:
              desk:   { type: string, role: key }
              region: { type: string }
            """;
    static final String LIMITS = """
            rachana: 2
            dataset: limits
            version: 1.0.0
            source: { connector: limit-lake, kind: limit }
            columns:
              desk:   { type: string }
              amount: { type: decimal, aggregate: sum, additive: { over-time: last } }
            """;
    static final String MODEL = """
            rachana: 2
            model: desk-risk
            version: 1.0.0
            datasets:
              trades: { dataset: trades, role: fact }
              limits: { dataset: limits, role: fact }
              desks:  { dataset: desks, role: dimension }
            relationships:
              - { id: r_1, from: trades.desk, to: desks.desk }
              - { id: r_2, from: limits.desk, to: desks.desk }
            measures:
              MTM:      { id: m_mtm, expr: "sum(trades.mtm)" }
              Notional: { id: m_notional, expr: "sum(trades.notional)" }
              Trades:   { id: m_trades, expr: "count(trades)" }
              AvgMTM:   { id: m_avg, expr: "avg(trades.mtm)" }
              Limit:    { id: m_limit, expr: "sum(limits.amount)" }
              Usage:    { id: m_usage, expr: "measure('Notional') / measure('Limit')" }
              Vol:      { id: m_vol, python: desk-vol, inputs: [trades.mtm], by: [trades.desk] }
            hierarchies:
              Desk: [desks.region, desks.desk]
            security:
              rows:
                trades:
                  - { role: desk-head, where: "trades.desk in user.desks" }
                  - { role: [risk, analyst, emea], where: "true" }
                desks:
                  - { role: emea, where: "desks.region == 'EMEA'" }
                  - { role: [risk, analyst, desk-head], where: "true" }
              may-see-all: { limits: [desk-head, emea] }
            """;

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    MqService mq;
    MqHarness h;
    ModelView model;

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        mq = new MqService(MqProperties.defaults());
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(MODEL, TRADES, DESKS, LIMITS);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    Map<String, Object> ask(MqHarness.Reader who, String yaml) {
        return mq.ask(h.bind(model, who, "m"), MqParser.parse(yaml), false).body();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> byFirst(Map<String, Object> answer, int column) {
        Map<String, Object> out = new TreeMap<>();
        ((List<List<Object>>) answer.get("rows")).forEach(r -> out.put(String.valueOf(r.get(0)), r.get(column)));
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Map<String, Object> answer) {
        return (List<List<Object>>) answer.get("rows");
    }

    @SuppressWarnings("unchecked")
    static List<String> warnings(Map<String, Object> answer) {
        return (List<String>) ((Map<String, Object>) answer.get("meta")).get("warnings");
    }

    double sum(String desk, boolean mtm) {
        return lake.trades.get(TestLake.DAY2).stream().filter(t -> desk == null || t.desk().equals(desk)).mapToDouble(t -> mtm ? t.mtm() : t.notional()).sum();
    }

    MqException refused(MqHarness.Reader who, String yaml) {
        try {
            ask(who, yaml);
        } catch (MqException e) {
            return e;
        }
        throw new AssertionError("not refused: " + yaml);
    }

    // ---- answers -----------------------------------------------------------------------------------------------------

    @Test
    void aGroupedQueryAnswersTheDataWithTheAnswerShapeOfTheSpec() {
        Map<String, Object> a = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [MTM, Trades] } }");
        assertThat((double) byFirst(a, 1).get("DESK-A")).isEqualTo(sum("DESK-A", true));
        assertThat(a.get("model")).isEqualTo(Map.of("id", "m:desk-risk", "name", "desk-risk", "version", "1.0.0", "implicit", false, "compat", false));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) a.get("columns");
        assertThat(cols.get(0)).containsEntry("kind", "field").containsEntry("type", "string");
        assertThat(cols.get(1)).containsEntry("kind", "measure").containsEntry("id", "m_mtm").containsEntry("additive", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) a.get("meta");
        assertThat(meta).containsKeys("asOf", "dates", "layer", "tables", "masked", "warnings", "cached", "millis", "generation", "query");
        assertThat(String.valueOf(meta.get("generation"))).startsWith("g-");
    }

    @Test
    void stableIdsAnswerLikeNames() {
        Map<String, Object> byName = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [MTM] } }");
        Map<String, Object> byId = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [m_mtm] } }");
        assertThat(rows(byId)).isEqualTo(rows(byName));
    }

    @Test
    void twoFactsMeetOnTheSharedDimensionEachAtItsGrain() {
        Map<String, Object> a = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [desks.desk], measures: [MTM, Limit, Usage] } }");
        assertThat((double) byFirst(a, 2).get("DESK-A")).isEqualTo(100.0);                         // 10+20+30+40, never multiplied by the trades
        assertThat((double) byFirst(a, 1).get("DESK-B")).isEqualTo(sum("DESK-B", true));
        assertThat((double) byFirst(a, 3).get("DESK-C")).isEqualTo(sum("DESK-C", false) / 7.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    // ---- security per dataset ------------------------------------------------------------------------------------------

    @Test
    void aDeskHeadsRuleCutsTradesBeforeGroupingAndLeavesTheLimitsWhole() {
        Map<String, Object> a = ask(MqHarness.deskHead("DESK-A"), "{ mq: 1, model: desk-risk, select: { fields: [desks.desk], measures: [MTM, Limit] } }");
        assertThat(byFirst(a, 1).get("DESK-B")).isNull();
        assertThat((double) byFirst(a, 1).get("DESK-A")).isEqualTo(sum("DESK-A", true));
        assertThat((double) byFirst(a, 2).get("DESK-B")).isEqualTo(11.0);                           // the limits carry no rule (may-see-all): every desk
        Map<String, Object> total = ask(MqHarness.deskHead("DESK-A"), "{ mq: 1, model: desk-risk, select: { measures: [Trades] } }");
        assertThat(((Number) rows(total).get(0).get(0)).longValue()).isEqualTo(lake.trades.get(TestLake.DAY2).stream().filter(t -> t.desk().equals("DESK-A")).count());
    }

    @Test
    void aDimensionsRuleReachesTheFactsThatReferenceIt() {
        MqHarness.Reader emea = new MqHarness.Reader("eve", true, "", List.of("emea"));
        Map<String, Object> a = ask(emea, "{ mq: 1, model: desk-risk, select: { fields: [trades.currency], measures: [MTM] } }");
        double mine = lake.trades.get(TestLake.DAY2).stream().filter(t -> t.desk().equals("DESK-A")).mapToDouble(TestLake.Trade::mtm).sum();
        double shown = rows(a).stream().mapToDouble(r -> ((Number) r.get(1)).doubleValue()).sum();
        assertThat(shown).isEqualTo(mine);                                                           // only the EMEA desk's trades, though the query never names desks
        Map<String, Object> limits = ask(emea, "{ mq: 1, model: desk-risk, select: { measures: [Limit] } }");
        assertThat(((Number) rows(limits).get(0).get(0)).doubleValue()).isEqualTo(100.0);           // the limits of the EMEA desk only
    }

    @Test
    void aModelsRulesOnlyEverRemoveRows() {
        Random random = new Random(7);
        long all = ((Number) rows(ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { measures: [Trades] } }")).get(0).get(0)).longValue();
        for (int i = 0; i < 12; i++) {
            int a = random.nextInt(3);
            int b = random.nextInt(4);
            String rule = "trades.notional > " + (a * 1000) + (b == 0 ? "" : " or trades.desk == 'DESK-" + (char) ('A' + b - 1) + "'");
            ModelView tighter = MqHarness.model(MODEL.replace("- { role: [risk, analyst, emea], where: \"true\" }", "- { role: [risk, analyst, emea], where: \"" + rule + "\" }"),
                    TRADES, DESKS, LIMITS);
            Map<String, Object> got = mq.ask(h.bind(tighter, MqHarness.RITA, "t" + i), MqParser.parse("{ mq: 1, model: x, select: { measures: [Trades] } }"), false).body();
            assertThat(((Number) rows(got).get(0).get(0)).longValue()).as(rule).isLessThanOrEqualTo(all);
        }
    }

    @Test
    void masksApplyBeforeAggregationAndAMaskedFieldCannotBeFilteredOrRanked() {
        Map<String, Object> a = ask(MqHarness.ANN, "{ mq: 1, model: desk-risk, select: { fields: [trades.cpty], measures: [Trades] } }");
        assertThat(rows(a)).allSatisfy(r -> assertThat(r.get(0)).isEqualTo(com.ash.drishti.api.DataNode.MASK));
        MqException e = refused(MqHarness.ANN, "{ mq: 1, model: desk-risk, select: { measures: [MTM] }, where: [{ field: trades.cpty, op: eq, value: CP-1 }] }");
        assertThat(e.problems().get(0).code()).isEqualTo("DRS-9161");
        assertThat(e.problems().get(0).fix()).containsEntry("with", List.of("trades.id"));
        assertThat(refused(MqHarness.ANN, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [MTM] }, where: [{ field: trades.cpty, op: top, n: 2, by: MTM }] }")
                .problems().get(0).code()).isEqualTo("DRS-9161");
    }

    // ---- the compiler --------------------------------------------------------------------------------------------------

    @Test
    void relativeTopDrillHavingAndSort() {
        Map<String, Object> rel = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { measures: [Trades] }, where: [{ field: trades.tradeDate, op: relative, window: last-7-days }] }");
        assertThat(((Number) rows(rel).get(0).get(0)).longValue()).isEqualTo(40);
        Map<String, Object> top = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Notional] }, where: [{ field: trades.desk, op: top, n: 2, by: Notional }] }");
        assertThat(rows(top)).hasSize(2);
        Map<String, Object> drill = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [MTM] }, drill: { hierarchy: Desk, path: [AMER] } }");
        assertThat(byFirst(drill, 1).keySet()).containsExactly("DESK-B", "DESK-C");
        Map<String, Object> having = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [MTM] }, having: [{ measure: MTM, op: gt, value: 0 }],"
                + " sort: [{ by: MTM, desc: true }] }");
        assertThat(rows(having)).allSatisfy(r -> assertThat(((Number) r.get(1)).doubleValue()).isPositive());
        assertThatThrownBy(() -> ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [MTM] }, drill: { hierarchy: Desk, path: [AMER, DESK-B] } }"))
                .isInstanceOfSatisfying(MqException.class, x -> assertThat(x.problems().get(0).code()).isEqualTo("DRS-9155"));
    }

    @Test
    void totalsAreAskedOnlyForTheMeasuresThatDoNotAddUp() {
        Map<String, Object> a = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk, trades.currency], measures: [MTM, AvgMTM] }, totals: subtotals }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> totals = (List<Map<String, Object>>) a.get("totals");
        assertThat(totals).extracting(t -> t.get("by")).containsExactly(List.of(), List.of("trades.desk"));
        assertThat(totals.get(0).get("measures")).isEqualTo(List.of("AvgMTM"));
        @SuppressWarnings("unchecked")
        List<List<Object>> grand = (List<List<Object>>) totals.get(0).get("rows");
        assertThat(((Number) grand.get(0).get(0)).doubleValue()).isEqualTo(sum(null, true) / 40, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aLocalMeasureTravelsInsideTheQuery() {
        Map<String, Object> a = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, define: { Share: \"measure('MTM') / measure('Notional')\" }, select: { measures: [Share] } }");
        assertThat(((Number) rows(a).get(0).get(0)).doubleValue()).isEqualTo(sum(null, true) / sum(null, false), org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void theCurrencyGuardWarnsInW1WhenAGroupAddsSeveralCurrencies() {
        Map<String, Object> mixed = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Sum of notional] } }");
        assertThat(warnings(mixed)).anyMatch(w -> w.startsWith("DRS-9158") && w.contains("DESK-A"));
        assertThat(rows(mixed).get(0)).hasSize(2);                                                     // the guard's column is not in the answer
        Map<String, Object> split = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk, trades.currency], measures: [Sum of notional] } }");
        assertThat(warnings(split)).noneMatch(w -> w.startsWith("DRS-9158"));
        Map<String, Object> pinned = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { measures: [Sum of notional] }, where: [{ field: trades.currency, op: eq, value: USD }] }");
        assertThat(warnings(pinned)).noneMatch(w -> w.startsWith("DRS-9158"));
        Map<String, Object> usd = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Sum of mtm] } }");
        assertThat(warnings(usd)).noneMatch(w -> w.startsWith("DRS-9158"));                            // a fixed currency always adds
    }

    @Test
    void aSemiAdditiveMeasureOverAWindowAnswersTheLastDateWithAWarning() {
        Map<String, Object> a = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { measures: [Sum of amount] }, time: { window: { from: 2026-10-08, to: 2026-10-09 } } }");
        assertThat(warnings(a)).anyMatch(w -> w.startsWith("DRS-9159"));
        assertThat(((Number) rows(a).get(0).get(0)).doubleValue()).isEqualTo(118.0);                  // one date's limits, not two
    }

    // ---- refusals, one per step -------------------------------------------------------------------------------------------

    @Test
    void everyStepRefusesWithItsCodeAndAFixWhereThereIsOne() {
        MqException names = refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.Currency], measures: [mtm] } }");
        assertThat(names.problems()).extracting(MqException.Problem::code).containsOnly("DRS-9152");
        assertThat(names.problems()).hasSize(2);
        assertThat(names.problems().get(0).fix()).containsEntry("with", List.of("trades.currency"));
        assertThat(refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, define: { MTM: \"sum(trades.mtm)\" }, select: { measures: [MTM] } }").problems().get(0).code())
                .isEqualTo("DRS-9154");
        assertThat(refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, define: { Bad: \"sum(trades.price)\" }, select: { measures: [Bad] } }").problems().get(0).code())
                .isEqualTo("DRS-9154");
        assertThat(refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { measures: [MTM] }, where: [{ field: trades.notional, op: contains, value: x }] }")
                .problems().get(0).code()).isEqualTo("DRS-9155");
        assertThat(refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { measures: [MTM] }, where: [{ field: trades.tradeDate, op: relative, window: last-fortnight }] }")
                .problems().get(0).fix()).containsKey("with");
        assertThat(refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { measures: [MTM] }, where: [{ field: trades.notional, op: gt, value: lots }] }")
                .problems().get(0).code()).isEqualTo("DRS-9155");
        MqException reach = refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.currency], measures: [Limit] } }");
        assertThat(reach.problems().get(0).code()).isEqualTo("DRS-9156");
        assertThat(reach.problems().get(0).path()).isEqualTo("select.fields[0]");
        assertThat(reach.problems().get(0).fix()).containsEntry("replace", "select.fields[0]");
        assertThat(refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { measures: [MTM] }, time: { as-of: 2027-01-01 } }").problems().get(0).code()).isEqualTo("DRS-9157");
        assertThat(refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { measures: [MTM] }, time: { live: true } }").problems().get(0).code()).isEqualTo("DRS-9157");
        assertThat(refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk, limits.desk] }, shape: rows }").problems().get(0).code())
                .isEqualTo("DRS-9160");
        assertThat(refused(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Vol] } }").problems().get(0).code()).isEqualTo("DRS-9162");
        MqService long1 = new MqService(new MqProperties(1, null, null, null, null, null, null, null, null, null));
        assertThatThrownBy(() -> long1.ask(h.bind(model, MqHarness.RITA, "m"), MqParser.parse("{ mq: 1, model: x, select: { measures: [MTM] }, time: { window: { from: 2026-10-08, to: 2026-10-09 } } }"), false))
                .isInstanceOfSatisfying(MqException.class, x -> assertThat(x.problems().get(0).code()).isEqualTo("DRS-9157"));
    }

    @Test
    void theContractRefusesWhatItDoesNotExpose9153() {
        model = MqHarness.model(MODEL + "contract: { exposes: listed, fields: [trades.desk], measures: [MTM], deprecated: { Trades: { since: 1.1.0, use: MTM } } }\n", TRADES, DESKS, LIMITS);
        assertThat(rows(ask(MqHarness.RITA, "{ mq: 1, model: x, select: { fields: [trades.desk], measures: [MTM] } }"))).hasSize(3);
        MqException e = refused(MqHarness.RITA, "{ mq: 1, model: x, select: { fields: [trades.currency], measures: [Trades] } }");
        assertThat(e.problems()).extracting(MqException.Problem::code).containsOnly("DRS-9153");
        assertThat(e.problems().get(0).detail()).contains("deprecated since 1.1.0, use MTM");
    }

    @Test
    void aRowExtractListsTheRowsOfOneFact() {
        Map<String, Object> a = ask(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.id, trades.desk, desks.region] }, shape: rows }");
        assertThat(rows(a)).hasSize(40);
    }

    @Test
    void validateRunsWithoutReadingAndShowsTheRequest() {
        Map<String, Object> v = mq.validate(h.bind(model, MqHarness.RITA, "m"), MqParser.parse("{ mq: 1, model: x, select: { fields: [desks.region], measures: [MTM] }, drill: { hierarchy: Desk, path: [EMEA] } }"));
        assertThat(v).containsEntry("valid", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) v.get("request");
        assertThat(request.get("fields")).isEqualTo(List.of("desks.desk"));
        assertThat(lake.scans.get()).isZero();
    }
}
