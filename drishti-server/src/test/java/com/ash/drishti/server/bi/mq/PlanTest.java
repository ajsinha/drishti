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
import com.ash.drishti.server.bi.plan.LogicalPlan;
import com.ash.drishti.server.bi.plan.Lowering;
import com.ash.drishti.server.bi.plan.PlanNode;
import com.ash.drishti.server.bi.plan.PlanProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The logical plan (docs/architecture/BI_DESIGN.md sections 3.6, 4 and 5): one plan hash for one question however it is written, the security rewriter's
 * scans and semi-joins, the signature (same security, same id; never the predicates), lowering to the engine's request, the result cache shared by
 * signature and invalidated by generation, the guards that refuse; and two property tests on the engine: ANDing a rule never adds a row, and a rollup answers
 * exactly as the columns layer.
 */
class PlanTest {

    static final String ROLLUP_MODEL = MqEngineTest.MODEL.replace("hierarchies:", """
            rollups:
              - { name: by_desk_ccy, table: trades, by: [desk, currency], measures: [MTM, Notional, Trades] }
            hierarchies:""");

    /** Trades and desks, a rule on trades only (a dimension with rules joins every query, so no one-table rollup could answer). */
    static final String TRADES_ONLY = """
            rachana: 2
            model: desk-risk
            version: 1.0.0
            datasets:
              trades: { dataset: trades, role: fact }
              desks:  { dataset: desks, role: dimension }
            relationships:
              - { id: r_1, from: trades.desk, to: desks.desk }
            measures:
              MTM:      { id: m_mtm, expr: "sum(trades.mtm)" }
              Notional: { id: m_notional, expr: "sum(trades.notional)" }
              Trades:   { id: m_trades, expr: "count(trades)" }
            rollups:
              - { name: by_desk_ccy, table: trades, by: [desk, currency], measures: [MTM, Notional, Trades] }
            security:
              rows:
                trades:
                  - { role: desk-head, where: "trades.desk in user.desks" }
                  - { role: [risk, analyst], where: "True" }
              may-see-all: { desks: [desk-head] }
            """;

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    MqHarness h;
    ModelView model;

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(ROLLUP_MODEL, MqEngineTest.TRADES, MqEngineTest.DESKS, MqEngineTest.LIMITS);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    static final MqHarness.Reader EMEA = new MqHarness.Reader("eve", true, "", List.of("emea"));

    LogicalPlan plan(MqHarness.Reader who, String yaml) {
        MqService.Bound b = h.bind(model, who, "m");
        ModelQuery q = MqParser.parse(yaml);
        MqValidator.Checked c = MqValidator.validate(b.view(), q, b.context(), MqProperties.defaults());
        MqCompiler.Compiled compiled = MqCompiler.compile(b.view(), c, b.context(), MqProperties.defaults(), tq -> b.runner().run(c.dataset(), tq, false));
        return MqPlans.plan(b.view(), c, compiled, b.security());
    }

    @Test
    void oneQuestionOnePlanHashHoweverItIsWritten() {
        String a = plan(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [MTM, Trades] }, where: [{ field: trades.currency, op: eq, value: USD }] }")
                .hash();
        String byIds = plan(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [m_mtm, m_trades] }, where: [{ field: trades.currency, op: eq, value: USD }] }")
                .hash();
        String json = plan(MqHarness.RITA, "{\"where\":[{\"value\":\"USD\",\"op\":\"eq\",\"field\":\"trades.currency\"}],\"select\":{\"measures\":[\"MTM\",\"Trades\"],\"fields\":[\"desks.region\"]},\"model\":\"desk-risk\",\"mq\":1}")
                .hash();
        assertThat(a).startsWith("p_").isEqualTo(byIds).isEqualTo(json);
        String hana = plan(MqHarness.deskHead("DESK-A"), "{ mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [MTM, Trades] }, where: [{ field: trades.currency, op: eq, value: USD }] }")
                .hash();
        assertThat(hana).as("security is not in the plan hash").isEqualTo(a);
        assertThat(plan(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [MTM] } }").hash()).isNotEqualTo(a);
        assertThat(plan(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [MTM, Trades] }, where: [{ field: trades.currency, op: eq, value: USD }], time: { as-of: 2026-10-08 } }")
                .hash()).as("the time is").isNotEqualTo(a);
    }

    @Test
    void scansCarryTheReadersSecurityAndADimensionsRulesReachTheFactAsASemiJoin() {
        LogicalPlan emea = plan(EMEA, "{ mq: 1, model: desk-risk, select: { fields: [trades.currency], measures: [Notional] } }");
        assertThat(PlanNode.scans(emea.root())).extracting(PlanNode.SecureScan::alias).contains("trades", "desks");
        String tree = emea.tree().toString();
        assertThat(tree).contains("op=SemiJoin").contains("relationship=trades -> desks");
        PlanNode.SecureScan desks = emea.scans().stream().filter(s -> s.alias().equals("desks")).findFirst().orElseThrow();
        assertThat(desks.rules()).containsExactly("desks.region == 'EMEA'");
        LogicalPlan hana = plan(MqHarness.deskHead("DESK-A"), "{ mq: 1, model: desk-risk, select: { fields: [trades.currency], measures: [Notional] }, where: [{ field: trades.desk, op: ne, value: DESK-Z }] }");
        PlanNode.SecureScan trades = hana.scans().stream().filter(s -> s.alias().equals("trades")).findFirst().orElseThrow();
        assertThat(trades.rules()).containsExactly("trades.desk in ['DESK-A']");
        assertThat(trades.pushed()).extracting(PlanNode.Cond::field).containsExactly("trades.desk");           // pushed down into the scan
        assertThat(trades.columns()).contains("currency", "notional", "desk");                            // pruned to what is used, rule columns kept
        assertThat(trades.columns()).doesNotContain("mtm", "tradeDate");
        assertThat(hana.tree().toString()).as("explain shows that a rule applied, never its text").contains("rules=1").doesNotContain("DESK-A");
    }

    @Test
    void theSignatureIsTheSameForTheSameSecurityAndOpaque() {
        String hanaA = h.bind(model, MqHarness.deskHead("DESK-A"), "m").signature();
        String otherA = h.bind(model, new MqHarness.Reader("otto", true, "DESK-A", List.of("desk-head")), "m").signature();
        String hanaB = h.bind(model, MqHarness.deskHead("DESK-B"), "m").signature();
        String rita = h.bind(model, MqHarness.RITA, "m").signature();
        String ann = h.bind(model, MqHarness.ANN, "m").signature();
        assertThat(hanaA).startsWith("s_").isEqualTo(otherA).isNotEqualTo(hanaB).doesNotContain("DESK");
        assertThat(rita).as("masks differ").isNotEqualTo(ann);
    }

    @Test
    void loweringGivesTheEngineTheLanguagesRequest() {
        List<String> queries = List.of("{ mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [MTM, Trades] } }",
                "{ mq: 1, model: desk-risk, select: { fields: [trades.desk, trades.currency], measures: [Notional] }, sort: [{ by: Notional, desc: true }], limit: 2 }",
                "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [MTM] }, having: [{ measure: MTM, op: gt, value: 0 }] }",
                "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Notional] }, where: [{ field: trades.desk, op: top, n: 2, by: Notional }] }",
                "{ mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [MTM, Limit] } }",
                "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Sum of notional] } }");
        for (String y : queries) {
            MqService.Bound b = h.bind(model, MqHarness.RITA, "m");
            MqValidator.Checked c = MqValidator.validate(b.view(), MqParser.parse(y), b.context(), MqProperties.defaults());
            MqCompiler.Compiled compiled = MqCompiler.compile(b.view(), c, b.context(), MqProperties.defaults(), tq -> b.runner().run(c.dataset(), tq, false));
            LogicalPlan p = MqPlans.plan(b.view(), c, compiled, b.security());
            assertThat(Lowering.request(p, compiled.main())).as(y).isEqualTo(compiled.main());
        }
        LogicalPlan top = plan(MqHarness.RITA, queries.get(3));
        assertThat(top.tree().toString()).as("top-N is its own grouped sub-plan").contains("relationship=top 2 trades.desk by Notional");
        LogicalPlan two = plan(MqHarness.RITA, queries.get(4));
        assertThat(two.tree().toString()).contains("op=Meet");
    }

    @Test
    void theRollupIsMatchedOnlyWhenItHoldsTheRuleColumns() {
        model = MqHarness.model(TRADES_ONLY, MqEngineTest.TRADES, MqEngineTest.DESKS);
        assertThat(plan(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.currency], measures: [Notional] } }").tree().toString()).contains("rollup=by_desk_ccy");
        assertThat(plan(MqHarness.deskHead("DESK-A"), "{ mq: 1, model: desk-risk, select: { fields: [trades.currency], measures: [Notional] } }").tree().toString())
                .contains("rollup=by_desk_ccy");                                // the rule's column (desk) is in the rollup's by
        assertThat(plan(MqHarness.RITA, "{ mq: 1, model: desk-risk, select: { fields: [trades.tradeDate], measures: [Notional] } }").tree().toString()).doesNotContain("rollup=");
    }

    @Test
    void theResultCacheIsSharedBySignatureAndMovesWithTheGeneration() {
        MqService cached = new MqService(MqProperties.defaults(), new PlanProperties(100, null, null, "warn"));
        AtomicLong generation = new AtomicLong(1);
        String q = "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Notional] } }";
        MqService.Ran first = cached.run(bound(MqHarness.deskHead("DESK-A"), generation), MqParser.parse(q), false, false);
        MqService.Ran sameSecurity = cached.run(bound(new MqHarness.Reader("otto", true, "DESK-A", List.of("desk-head")), generation), MqParser.parse(q), false, false);
        MqService.Ran otherDesk = cached.run(bound(MqHarness.deskHead("DESK-B"), generation), MqParser.parse(q), false, false);
        assertThat(first.fromCache()).isFalse();
        assertThat(sameSecurity.fromCache()).as("identical effective security shares the entry").isTrue();
        assertThat(otherDesk.fromCache()).as("different security never does").isFalse();
        assertThat(MqService.rows(otherDesk.shown())).isNotEqualTo(MqService.rows(first.shown()));
        assertThat(cached.results().size()).isEqualTo(2);
        generation.incrementAndGet();
        assertThat(cached.run(bound(MqHarness.deskHead("DESK-A"), generation), MqParser.parse(q), false, false).fromCache()).as("a new generation is a new key").isFalse();
        Map<String, Object> answer = cached.answer(bound(MqHarness.deskHead("DESK-A"), generation), cached.run(bound(MqHarness.deskHead("DESK-A"), generation),
                MqParser.parse(q), false, false)).body();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) answer.get("meta");
        assertThat(meta.get("cached")).isEqualTo(true);
        assertThat((String) meta.get("plan")).startsWith("p_");
        assertThat((String) meta.get("signature")).startsWith("s_");
    }

    MqService.Bound bound(MqHarness.Reader who, AtomicLong generation) {
        MqService.Bound b = h.bind(model, who, "m");
        return new MqService.Bound(b.view(), b.runner(), b.context(), b.security(), generation::get);
    }

    @Test
    void theGuardsRefuseFromWaveA() {
        MqService refusing = new MqService(MqProperties.defaults(), new PlanProperties(0, null, null, "refuse"));
        MqService.Bound b = h.bind(model, MqHarness.RITA, "m");
        assertThatThrownBy(() -> refusing.ask(b, MqParser.parse("{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Sum of notional] } }"), false))
                .isInstanceOf(MqException.class).satisfies(e -> assertThat(((MqException) e).problems()).extracting(MqException.Problem::code).containsExactly("DRS-9158"));
        assertThat(refusing.ask(b, MqParser.parse("{ mq: 1, model: desk-risk, select: { fields: [trades.desk, trades.currency], measures: [Sum of notional] } }"), false)
                .body().get("rows")).isNotNull();
        Map<String, Object> balance = refusing.ask(b, MqParser.parse("{ mq: 1, model: desk-risk, select: { measures: [Sum of amount] }, time: { window: { from: 2026-10-08, to: 2026-10-09 } } }"),
                false).body();
        assertThat(balance.get("rows")).as("a balance alone over a window is its last date's value: right, so answered").isNotNull();
    }

    // ---- property tests on the engine ------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Double> byKey(Map<String, Object> answer) {
        Map<String, Double> out = new TreeMap<>();
        for (List<Object> r : (List<List<Object>>) answer.get("rows")) {
            out.put(String.valueOf(r.subList(0, r.size() - 1)), r.get(r.size() - 1) == null ? null : ((Number) r.get(r.size() - 1)).doubleValue());
        }
        return out;
    }

    @Test
    void andingARuleNeverAddsARow() {
        Random rnd = new Random(20261012);
        MqService mq = new MqService(MqProperties.defaults());
        List<String> predicates = List.of("trades.notional > 1500", "trades.currency == 'EUR'", "trades.desk in ['DESK-A', 'DESK-C']", "trades.mtm < 0",
                "trades.tradeDate >= date('2026-10-09')", "not trades.desk == 'DESK-B'", "trades.notional % 3 == 0");
        List<List<String>> groupings = List.of(List.of(), List.of("trades.desk"), List.of("trades.currency"), List.of("desks.region"), List.of("trades.desk", "trades.currency"));
        int compared = 0;
        for (int i = 0; i < 40; i++) {
            String p1 = predicates.get(rnd.nextInt(predicates.size()));
            String p2 = predicates.get(rnd.nextInt(predicates.size()));
            String base = MqEngineTest.MODEL;
            String one = base.replace("- { role: [risk, analyst, emea], where: \"true\" }", "- { role: [risk, analyst, emea], where: \"" + p1 + "\" }");
            String both = one.replace("              may-see-all:", "              may-see-all:").replace("    rows:\n      trades:", "    rows:\n      trades:");
            ModelView m1 = MqHarness.model(one, MqEngineTest.TRADES, MqEngineTest.DESKS, MqEngineTest.LIMITS);
            ModelView m2 = MqHarness.model(both.replace("    rows:\n", "    rows:\n").replace("where: \"" + p1 + "\" }", "where: \"(" + p1 + ") and (" + p2 + ")\" }"),
                    MqEngineTest.TRADES, MqEngineTest.DESKS, MqEngineTest.LIMITS);
            List<String> by = groupings.get(rnd.nextInt(groupings.size()));
            String q = "{ mq: 1, model: desk-risk, select: { fields: " + EquivalenceTest.list(by) + ", measures: [Trades] } }";
            Map<String, Double> wide = byKey(mq.ask(h.bind(m1, MqHarness.RITA, "p1-" + i), MqParser.parse(q), false).body());
            Map<String, Double> narrow = byKey(mq.ask(h.bind(m2, MqHarness.RITA, "p2-" + i), MqParser.parse(q), false).body());
            assertThat(wide.keySet()).as(p1 + " AND " + p2 + " by " + by).containsAll(narrow.keySet());
            narrow.forEach((k, v) -> assertThat(v == null ? 0 : v).as(p1 + " AND " + p2 + " group " + k).isLessThanOrEqualTo(wide.get(k) == null ? 0 : wide.get(k)));
            compared++;
        }
        assertThat(compared).isEqualTo(40);
    }

    @Test
    void aRollupAnswersExactlyAsTheColumnsLayer() {
        model = MqHarness.model(TRADES_ONLY, MqEngineTest.TRADES, MqEngineTest.DESKS);
        Random rnd = new Random(7);
        MqService mq = new MqService(MqProperties.defaults());
        List<List<String>> groupings = List.of(List.of(), List.of("trades.desk"), List.of("trades.currency"), List.of("trades.desk", "trades.currency"));
        List<String> measures = List.of("MTM", "Notional", "Trades");
        List<String> filters = List.of("", ", where: [{ field: trades.currency, op: eq, value: EUR }]", ", where: [{ field: trades.desk, op: in, values: [DESK-A, DESK-B] }]");
        List<MqHarness.Reader> readers = List.of(MqHarness.RITA, MqHarness.deskHead("DESK-A"), MqHarness.deskHead("DESK-C"));
        int compared = 0;
        int rolled = 0;
        for (int i = 0; i < 30; i++) {
            List<String> by = groupings.get(rnd.nextInt(groupings.size()));
            List<String> ms = new ArrayList<>(measures.subList(0, 1 + rnd.nextInt(measures.size())));
            String f = filters.get(rnd.nextInt(filters.size()));
            MqHarness.Reader who = readers.get(rnd.nextInt(readers.size()));
            String q = "{ mq: 1, model: desk-risk, select: { fields: " + EquivalenceTest.list(by) + ", measures: " + EquivalenceTest.list(ms) + " }" + f + ", options: { layer: %s } }";
            Map<String, Object> auto = mq.ask(h.bind(model, who, "rollup"), MqParser.parse(q.formatted("auto")), false).body();
            Map<String, Object> columns = mq.ask(h.bind(model, who, "rollup"), MqParser.parse(q.formatted("columns")), false).body();
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) auto.get("meta");
            if ("rollup".equals(meta.get("layer"))) {
                rolled++;
            }
            assertThat(normal(auto.get("rows"))).as(q + " as " + who.user()).isEqualTo(normal(columns.get("rows")));
            compared++;
        }
        assertThat(compared).isEqualTo(30);
        assertThat(rolled).as("the rollup answered most of them").isGreaterThan(10);
    }

    @SuppressWarnings("unchecked")
    static List<String> normal(Object rows) {
        List<String> out = new ArrayList<>();
        for (List<Object> r : (List<List<Object>>) rows) {
            out.add(r.stream().map(v -> v instanceof Number n ? String.format("%.6f", n.doubleValue()) : String.valueOf(v)).toList().toString());
        }
        out.sort(null);
        return out;
    }
}
