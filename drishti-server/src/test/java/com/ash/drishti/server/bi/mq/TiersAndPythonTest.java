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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wave B task 4 through the model query: a Python measure in any grouped query (R8: the rows of its inputs per group of the answer, run by the Python runner
 * after the SQL measures, in its place in the answer; refused when it is sorted on), the plan's {@code PyAggregate} node on the console tier, and explain
 * annotated with tiers, layers and times (acceptance (6)).
 */
class TiersAndPythonTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    MqService mq;
    MqHarness h;
    ModelView model;
    final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        mq = new MqService(MqProperties.defaults(), new PlanProperties(0, null, null, "warn"));
        // the console's runner, here in Java: the sum of the squares of the input per group
        mq.python((reader, m, groupBy, columns, rows) -> {
            calls.incrementAndGet();
            int input = columns.indexOf("mtm");
            Map<List<Object>, Double> acc = new LinkedHashMap<>();
            for (List<Object> r : rows) {
                List<Object> key = r.subList(0, groupBy.size());
                double v = ((Number) r.get(input)).doubleValue();
                acc.merge(key, v * v, Double::sum);
            }
            ObjectNode out = JSON.createObjectNode().put("ok", true);
            ArrayNode rs = out.putArray("rows");
            acc.forEach((k, v) -> {
                ArrayNode row = rs.addArray();
                k.forEach(x -> row.addPOJO(x));
                row.add(v);
            });
            return out;
        });
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(MqEngineTest.MODEL, MqEngineTest.TRADES, MqEngineTest.DESKS, MqEngineTest.LIMITS);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    Map<String, Object> ask(String yaml) {
        return mq.ask(h.bind(model, MqHarness.RITA, "m"), MqParser.parse(yaml), false).body();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPythonMeasureInAGroupedQueryIsWorkedOutPerGroupAfterTheSqlMeasures() {
        Map<String, Object> a = ask("{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [MTM, Vol, Trades] } }");
        List<List<Object>> rows = (List<List<Object>>) a.get("rows");
        assertThat(rows).hasSize(3);
        for (List<Object> r : rows) {
            String desk = (String) r.get(0);
            double squares = lake.trades.get(TestLake.DAY2).stream().filter(t -> t.desk().equals(desk)).mapToDouble(t -> t.mtm() * t.mtm()).sum();
            assertThat(((Number) r.get(2)).doubleValue()).as(desk).isEqualTo(squares);
            assertThat(r.get(3)).isInstanceOf(Number.class);                                      // Trades after Vol: the order of the question
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(() -> ask("{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Vol] }, sort: [{ by: Vol }] }")).hasMessageContaining("DRS-9162");
        MqService without = new MqService(MqProperties.defaults(), new PlanProperties(0, null, null, "warn"));
        assertThatThrownBy(() -> without.ask(h.bind(model, MqHarness.RITA, "m"), MqParser.parse("{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Vol] } }"),
                false)).hasMessageContaining("DRS-9162");
    }

    @Test
    @SuppressWarnings("unchecked")
    void explainShowsTiersLayersAndTimes() {
        Map<String, Object> a = ask("{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [MTM, Vol] }, options: { explain: true } }");
        Map<String, Object> meta = (Map<String, Object>) a.get("meta");
        String tree = String.valueOf(meta.get("explain"));
        assertThat(tree).contains("tier=java").contains("tier=console").contains("PyAggregate").contains("layer=columns");
        assertThat((Map<String, Object>) meta.get("timings")).containsKeys("engineMillis", "pythonMillis", "layer");
        assertThat((Map<String, Object>) meta.get("tiers")).containsKeys("java", "console");
        assertThat((String) meta.get("explainText")).contains("tier: console").contains("timings:");
    }
}
