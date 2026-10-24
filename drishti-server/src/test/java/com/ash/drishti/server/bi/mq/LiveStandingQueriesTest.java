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

import com.ash.drishti.server.bi.engine.BiLiveProperties;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.LiveTable;
import com.ash.drishti.server.bi.engine.LiveTablesTest;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.TestLake;
import com.ash.drishti.server.bi.plan.PlanProperties;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Standing queries pushed by the generation bus and kept by their maintainer (wave B task 3; acceptance (2): "a board, a report and an alert over one model
 * tick live ... and equal a full recompute after random update streams"). A model over a live trades table; subscriptions delivering rows (a board), a
 * difference (a report) and one value (an alert), for readers of different security; random streams of updates and deletions; after each burst every
 * subscription's state equals the same query asked now. One evaluation serves every subscriber of a (plan, signature) group.
 */
class LiveStandingQueriesTest {

    static final String TRADES = MqEngineTest.TRADES.replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade }\ntime: { mode: live }")
            .replace("security:\n  masked: [cpty]\n", "");
    static final String MODEL = """
            rachana: 2
            model: live-desks
            version: 1.0.0
            datasets:
              trades: { dataset: trades, role: fact }
              desks:  { dataset: desks, role: dimension }
            relationships:
              - { id: r_1, from: trades.desk, to: desks.desk }
            measures:
              MTM:     { expr: "sum(trades.mtm)" }
              Trades:  { expr: "count(trades)" }
              AvgMTM:  { expr: "avg(trades.mtm)" }
              PerTrade: { expr: "measure('MTM') / measure('Trades')" }
              Biggest: { expr: "max(trades.notional)" }
              Pct:     { expr: "share_of(measure('MTM'), over=ALL)" }
              Big MTM: { expr: "sum(trades.mtm, where=trades.notional > 1000)" }
            security:
              rows:
                trades:
                  - { role: desk-head, where: "trades.desk in user.desks" }
                  - { role: [risk, analyst], where: "true" }
            """;

    @TempDir Path store;
    LiveTablesTest.LiveLake lake;
    QueryService service;
    MqService mq;
    MqHarness h;
    ModelView model;
    StandingQueries standing;
    final Map<String, MqHarness.Reader> readers = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        lake = new LiveTablesTest.LiveLake();
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = BiLiveProperties.defaults().fast(Duration.ofMillis(20), Duration.ofHours(1));
        service = new QueryService(props, live, new DuckDbEngine(props), lake, Clock.systemUTC());
        mq = new MqService(MqProperties.defaults(), PlanProperties.off());
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(MODEL, TRADES, MqEngineTest.DESKS);
        readers.put("rita", MqHarness.RITA);
        readers.put("hana", MqHarness.deskHead("DESK-A"));
        readers.put("hugo", new MqHarness.Reader("hugo", true, "DESK-A", List.of("desk-head")));
        StandingQueries.Models models = new StandingQueries.Models() {
            @Override
            public MqService.Bound bind(Principal p, String m) {
                return h.bind(model, readers.get(p.user()), model.key());
            }

            @Override
            public long generation(Principal p, ModelView v) {
                return service.layers().generation(v.key()) * 1_000_003L + service.live().generation(v.key());
            }

            @Override
            public long liveGeneration(String key, String table) {
                return service.live().find(key, table).map(LiveTable::generation).orElse(0L);
            }
        };
        MqProperties mqp = new MqProperties(null, null, null, null, null, null, Duration.ofMillis(1), null, null, null, Duration.ofSeconds(30), Duration.ofMillis(50));
        standing = new StandingQueries(models, mq, mqp, Clock.systemUTC());
        standing.attach(service.bus());
    }

    @AfterEach
    void tearDown() {
        standing.close();
        service.close();
    }

    static Principal who(String user) {
        return new Principal(user, List.of());
    }

    @SuppressWarnings("unchecked")
    List<List<Object>> asked(String user, String yaml) {
        return (List<List<Object>>) mq.ask(h.bind(model, readers.get(user), model.key()), MqParser.parse(yaml), false).body().get("rows");
    }

    /** What a subscription holds now: the last rows it delivered (rows), the rows patched by every difference (diff), the value (alert). */
    static final class Held {
        List<List<Object>> rows;
        Object value;
        String failure;

        @SuppressWarnings("unchecked")
        void apply(StandingQueries.Event e, int keys) {
            if ("status".equals(e.name())) {
                failure = String.valueOf(e.data().get("detail"));
            }
            if (!"answer".equals(e.name())) {
                return;
            }
            if (e.data().containsKey("rows")) {
                rows = (List<List<Object>>) e.data().get("rows");
            }
            if (e.data().containsKey("value")) {
                value = e.data().get("value");
            }
            if (e.data().get("diff") instanceof Map<?, ?> d) {
                Map<List<Object>, List<Object>> byKey = new LinkedHashMap<>();
                rows.forEach(r -> byKey.put(r.subList(0, keys), r));
                ((List<List<Object>>) d.get("delete")).forEach(byKey::remove);
                ((List<List<Object>>) d.get("upsert")).forEach(r -> byKey.put(r.subList(0, keys), r));
                rows = new ArrayList<>(byKey.values());
            }
        }
    }

    @Test
    void aBoardAReportAndAnAlertEqualAFullRecomputeAfterRandomUpdateStreams() throws Exception {
        String board = "{ mq: 1, model: live-desks, select: { fields: [trades.desk], measures: [MTM, Trades, AvgMTM, PerTrade, Big MTM] } }";
        String report = "{ mq: 1, model: live-desks, select: { fields: [trades.desk, trades.currency], measures: [Biggest, MTM] } }";
        String shares = "{ mq: 1, model: live-desks, select: { fields: [trades.currency], measures: [Pct] } }";
        String alert = "{ mq: 1, model: live-desks, select: { measures: [MTM] } }";
        Map<String, String> queries = Map.of("board", board, "report", report, "shares", shares, "alert", alert);
        Map<String, String> deliver = Map.of("board", "rows", "report", "diff", "shares", "rows", "alert", "value");
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, Held> held = new LinkedHashMap<>();
        for (String user : readers.keySet()) {
            for (String name : queries.keySet()) {
                Map<String, Object> sub = standing.subscribe(who(user), MqParser.parse(queries.get(name)), Duration.ofMillis(1), deliver.get(name));
                ids.put(user + "/" + name, (String) sub.get("subscription"));
                Held x = new Held();
                @SuppressWarnings("unchecked")
                List<List<Object>> first = (List<List<Object>>) ((Map<String, Object>) sub.get("answer")).get("rows");
                x.rows = first;
                x.value = first.isEmpty() ? null : first.get(0).get(first.get(0).size() - 1);
                held.put(user + "/" + name, x);
            }
        }
        // hana and hugo hold one signature (desk-head of DESK-A): their groups are shared
        assertThat(standing.stats().get("groups")).isEqualTo(8);
        Random rnd = new Random(7);
        List<String> live = new ArrayList<>(lake.docs.keySet().stream().map(r -> r.id()).sorted().toList());
        for (int burst = 0; burst < 12; burst++) {
            int n = 1 + rnd.nextInt(4);
            for (int i = 0; i < n && !live.isEmpty(); i++) {
                String id = live.get(rnd.nextInt(live.size()));
                switch (rnd.nextInt(5)) {
                    case 0 -> lake.set(id, "desk", TestLake.DESKS.get(rnd.nextInt(3)));
                    case 1 -> lake.set(id, "notional", 500.0 + 100 * rnd.nextInt(20));
                    case 2 -> {
                        if (live.size() > 5) {
                            lake.remove(id);
                            live.remove(id);
                        }
                    }
                    default -> lake.set(id, "mtm", (double) (rnd.nextInt(20001) - 10000));
                }
            }
            quiesce();
            for (Map.Entry<String, String> e : ids.entrySet()) {
                String user = e.getKey().split("/")[0];
                String name = e.getKey().split("/")[1];
                int keys = name.equals("alert") ? 0 : name.equals("report") ? 2 : 1;
                for (StandingQueries.Event ev : standing.poll(List.of(e.getValue()))) {
                    held.get(e.getKey()).apply(ev, keys);
                }
                List<List<Object>> now = asked(user, queries.get(name));
                Held x = held.get(e.getKey());
                if ("alert".equals(name)) {
                    Object want = now.isEmpty() ? null : now.get(0).get(now.get(0).size() - 1);
                    assertThat(StandingQueries.sameRow(java.util.Collections.singletonList(x.value), java.util.Collections.singletonList(want)))
                            .as("burst %d %s: %s vs %s", burst, e.getKey(), x.value, want).isTrue();
                } else {
                    List<List<Object>> mine = new ArrayList<>(x.rows);
                    if ("report".equals(name)) {
                        mine.sort(Maintainer.order(new com.ash.drishti.server.bi.engine.QueryRequest(List.of("trades.desk", "trades.currency"), List.of("Biggest", "MTM"),
                                List.of(), null, null, List.of(), null, "auto")));
                    }
                    assertThat(StandingQueries.sameRows(mine, now)).as("burst %d %s:%n held %s%n asked %s%n failure %s", burst, e.getKey(), mine, now, x.failure).isTrue();
                }
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Long> modes = (Map<String, Long>) standing.stats().get("modes");
        assertThat(modes).containsKey("incremental");
        assertThat((Long) standing.stats().get("incremental")).isPositive();
    }

    /**
     * Waits until the feed applied every pushed document (none waits for the next batch, none is being applied), the bus delivered every notice and the
     * groups applied them. The generation alone is not enough: a loaded machine can run the feed's next batch later than any fixed pause.
     */
    void quiesce() throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < until) {
            long g1 = service.live().generation(model.key());
            Thread.sleep(20);
            long g2 = service.live().generation(model.key());
            if (g1 == g2 && service.live().idle(model.key()) && service.bus().drained(5_000) && standing.settled(10_000) && service.live().generation(model.key()) == g2) {
                return;
            }
        }
        throw new AssertionError("the live table did not settle");
    }

    @Test
    void aSubscriberOfAnExistingGroupGetsItsAnswerWithoutAnEvaluationAndAPastDateIsRefused() {
        String q = "{ mq: 1, model: live-desks, select: { fields: [trades.desk], measures: [MTM] } }";
        standing.subscribe(who("hana"), MqParser.parse(q), Duration.ofMillis(1), "rows");
        long before = (Long) standing.stats().get("evaluations");
        Map<String, Object> second = standing.subscribe(who("hugo"), MqParser.parse(q), Duration.ofMillis(1), "rows");
        assertThat(standing.stats().get("evaluations")).isEqualTo(before);
        assertThat(second.get("subscribers")).isEqualTo(2);
        assertThatThrownBy(() -> standing.subscribe(who("rita"), MqParser.parse("{ mq: 1, model: live-desks, select: { measures: [MTM] }, time: { as-of: 2026-10-01 } }"),
                Duration.ofMillis(1), "rows")).hasMessageContaining("DRS-9183");
    }
}
