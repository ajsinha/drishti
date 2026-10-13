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
import com.ash.drishti.server.bi.sql.DsqlException;
import com.ash.drishti.server.bi.sql.DsqlParser;
import com.ash.drishti.server.bi.sql.SqlProperties;
import com.ash.drishti.server.bi.sql.SqlService;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Drishti SQL over the engine (docs/architecture/BI_DESIGN.md section 3.4): an MQ-shaped statement is one model query (same answer, same plan hash, one
 * cache entry per signature as the MQ), inline aggregates are local measures, the time clause is the MQ's time; composite statements (WITH, joins of answers,
 * UNION, windows over the answer) run over secured leaves and refuse a join or sort on a masked column; and every refusal of the dialect has its code.
 */
class SqlEngineTest {

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    MqHarness h;
    ModelView model;
    MqService mq;
    SqlService sql;

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(MqEngineTest.MODEL, MqEngineTest.TRADES, MqEngineTest.DESKS, MqEngineTest.LIMITS);
        mq = new MqService(MqProperties.defaults(), new PlanProperties(100, null, null, "warn"));
        sql = new SqlService(mq, SqlProperties.defaults());
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    final AtomicLong generation = new AtomicLong(1);

    MqService.Bound bound(MqHarness.Reader who) {
        MqService.Bound b = h.bind(model, who, "m");
        return new MqService.Bound(b.view(), b.runner(), b.context(), b.security(), generation::get);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> ask(MqHarness.Reader who, String statement) {
        MqService.Bound b = bound(who);
        SqlService.Compiled c = sql.compile(b, "desk-risk", statement);
        return c.explain() ? sql.explain(b, c) : sql.run(b, c).body();
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Map<String, Object> a) {
        return (List<List<Object>>) a.get("rows");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> meta(Map<String, Object> a) {
        return (Map<String, Object>) a.get("meta");
    }

    @SuppressWarnings("unchecked")
    static List<String> names(Map<String, Object> a) {
        List<String> out = new ArrayList<>();
        ((List<Map<String, Object>>) a.get("columns")).forEach(c -> out.add(String.valueOf(c.get("name"))));
        return out;
    }

    @Test
    void anMqShapedStatementIsTheSameQuestionAsTheMq() {
        Map<String, Object> viaSql = ask(MqHarness.deskHead("DESK-A"), """
                -- sql: 1
                SELECT desks.region AS region, MEASURE('MTM') AS mtm, MEASURE('m_trades') AS trades
                FROM   trades JOIN desks
                WHERE  trades.currency IN ('USD', 'EUR') AND trades.notional >= 0
                GROUP  BY desks.region
                ORDER  BY mtm DESC
                LIMIT  10""");
        Map<String, Object> viaMq = mq.ask(bound(MqHarness.deskHead("DESK-A")), MqParser.parse("""
                { mq: 1, model: desk-risk, select: { fields: [desks.region], measures: [MTM, Trades] },
                  where: [{ field: trades.currency, op: in, values: [USD, EUR] }, { field: trades.notional, op: ge, value: 0 }], sort: [{ by: MTM, desc: true }], limit: 10 }"""),
                false).body();
        assertThat(names(viaSql)).containsExactly("region", "mtm", "trades");
        assertThat(rows(viaSql)).isEqualTo(rows(viaMq));
        assertThat(meta(viaSql).get("shape")).isEqualTo("mq");
        assertThat(meta(viaSql).get("plan")).as("one plan hash, any language").isEqualTo(meta(viaMq).get("plan"));
        assertThat(meta(viaMq).get("cached")).as("and one cache entry: the MQ found the SQL's answer").isEqualTo(true);
    }

    @Test
    void inlineAggregatesAreLocalMeasuresAndRowsAreAnExtract() {
        Map<String, Object> a = ask(MqHarness.RITA, "SELECT trades.desk, SUM(trades.notional) AS notional, COUNT(*) AS n, COUNT(DISTINCT trades.currency) AS ccys, "
                + "SUM(CASE WHEN trades.mtm < 0 THEN trades.mtm ELSE 0 END) AS losses FROM trades GROUP BY 1 ORDER BY 1");
        Map<String, Object> m = mq.ask(bound(MqHarness.RITA), MqParser.parse("{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Notional, Trades] }, sort: [{ by: trades.desk }] }"),
                false).body();
        for (int i = 0; i < rows(a).size(); i++) {
            assertThat(rows(a).get(i).subList(0, 3)).isEqualTo(rows(m).get(i));
        }
        assertThat(rows(a).get(0).get(3)).isEqualTo(2L);
        Map<String, Object> extract = ask(MqHarness.RITA, "SELECT trades.desk, trades.notional FROM trades WHERE trades.desk = 'DESK-A' AS OF DATE '2026-10-08'");
        assertThat(meta(extract).get("query").toString()).contains("shape=rows").contains("as-of=2026-10-08");
        assertThat(rows(extract)).hasSize(14).allSatisfy(r -> assertThat(r.get(0)).isEqualTo("DESK-A"));
        assertThat(rows(ask(MqHarness.RITA, "SELECT trades.desk FROM trades GROUP BY trades.desk LIMIT 2 OFFSET 1"))).hasSize(2);
    }

    @Test
    void compositeStatementsRunOverSecuredLeaves() {
        Map<String, Object> joined = ask(MqHarness.RITA, """
                WITH m AS (SELECT trades.desk AS desk, MEASURE('MTM') AS mtm FROM trades GROUP BY trades.desk),
                     l AS (SELECT limits.desk AS desk, MEASURE('Limit') AS lim FROM limits GROUP BY limits.desk)
                SELECT m.desk, m.mtm, l.lim, m.mtm / l.lim AS usage FROM m LEFT JOIN l ON m.desk = l.desk ORDER BY m.desk""");
        assertThat(meta(joined).get("shape")).isEqualTo("composite");
        assertThat(names(joined)).containsExactly("desk", "mtm", "lim", "usage");
        assertThat(rows(joined)).hasSize(3);
        Map<String, Object> ranked = ask(MqHarness.RITA, "SELECT trades.desk AS desk, MEASURE('Notional') AS notional, RANK() OVER (ORDER BY notional DESC) AS r "
                + "FROM trades GROUP BY trades.desk ORDER BY r");
        assertThat(rows(ranked)).extracting(r -> ((Number) r.get(2)).intValue()).containsExactly(1, 2, 3);
        Map<String, Object> union = ask(MqHarness.deskHead("DESK-B"), "SELECT trades.desk, MEASURE('Trades') FROM trades GROUP BY trades.desk "
                + "UNION ALL SELECT trades.currency, MEASURE('Trades') FROM trades GROUP BY trades.currency");
        assertThat(rows(union)).as("the desk-head's rows only, in both leaves").hasSize(3).anySatisfy(r -> assertThat(r.get(0)).isEqualTo("DESK-B"));
        // ann: cpty is masked; joining or sorting on it is refused, selecting it reads ••• (as in the leaf)
        assertThatThrownBy(() -> ask(MqHarness.ANN, "WITH a AS (SELECT trades.cpty AS c, MEASURE('Trades') AS n FROM trades GROUP BY trades.cpty) SELECT a.c, a.n FROM a ORDER BY a.c"))
                .isInstanceOf(MqException.class).satisfies(e -> assertThat(((MqException) e).problems().get(0).code()).isEqualTo("DRS-9161"));
        assertThat(rows(ask(MqHarness.RITA, "WITH a AS (SELECT trades.cpty AS c, MEASURE('Trades') AS n FROM trades GROUP BY trades.cpty) SELECT a.c, a.n FROM a ORDER BY a.c")))
                .hasSize(4);
        Map<String, Object> plan = ask(MqHarness.RITA, "EXPLAIN SELECT trades.desk, MEASURE('MTM') FROM trades GROUP BY trades.desk");
        assertThat(plan.get("text").toString()).contains("SecureScan trades").contains("Aggregate trades");
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", value = {
        "DELETE FROM trades => DRS-9170",
        "SELECT 1; SELECT 2 => DRS-9170",
        "SELECT x.desk FROM pack.trading.trade x => DRS-9176",
        "SELECT trades.desk, MEASURE('MTM') FROM trades JOIN desks ON trades.notional = desks.desk GROUP BY trades.desk => DRS-9172",
        "SELECT trades.desk, SUM(MEASURE('MTM')) FROM trades GROUP BY trades.desk => DRS-9174",
        "SELECT trades.desk FROM trades WHERE MEASURE('MTM') > 0 => DRS-9174",
        "SELECT DISTINCT trades.desk FROM trades => DRS-9178",
        "SELECT * FROM read_parquet('/etc/passwd') => DRS-9173",
        "SELECT trades.desk, random() FROM trades => DRS-9173",
        "SELECT trades.nope FROM trades => DRS-9171",
        "SELECT trades.desk, MEASURE('MTM') FROM trades WHERE trades.desk = 'A' OR trades.currency = 'B' GROUP BY trades.desk => DRS-9179",
        "WITH a AS (SELECT trades.desk AS d, MEASURE('MTM') AS m FROM trades GROUP BY trades.desk AS OF DATE '2026-10-08'), b AS (SELECT trades.desk AS d, MEASURE('MTM') AS m FROM trades GROUP BY trades.desk AS OF DATE '2026-10-09') SELECT a.d FROM a JOIN b ON a.d = b.d => DRS-9177",
    })
    void theDialectRefusesWithItsCodes(String statement, String code) {
        assertThatThrownBy(() -> ask(MqHarness.RITA, statement)).isInstanceOf(DsqlException.class).satisfies(e -> assertThat(((DsqlException) e).code()).isEqualTo(code));
    }

    @Test
    void theParserKeepsPositions() {
        assertThatThrownBy(() -> DsqlParser.parse("SELECT trades.desk FROM trades WHERE")).isInstanceOf(DsqlException.class)
                .satisfies(e -> assertThat(((DsqlException) e).start()).isEqualTo(36));
        assertThat(DsqlParser.parse("EXPLAIN SELECT \"trades\".\"desk\", MEASURE(\"P&L 1d\") FROM trades GROUP BY 1").explain()).isTrue();
    }
}
