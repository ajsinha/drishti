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

import com.ash.drishti.server.bi.engine.BiLiveProperties;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.GenerationBus;
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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two Java nodes serving one model (wave B task 5; acceptance (4), in process): asked queries are stateless (either node answers, the same rows); the
 * model's shard (its live tables' standing queries) is owned by one node by rendezvous hashing, a subscription made on the other node is held on the owner
 * and its answers reach the viewer through the node it asked; when the owner is lost the subscription is held again on the surviving node under the same id
 * and keeps equalling the asked answer after more changes.
 */
class TwoNodesTest {

    @TempDir Path store;
    LiveTablesTest.LiveLake lake;
    final Map<String, Node> nodes = new ConcurrentHashMap<>();
    final Map<String, Boolean> alive = new ConcurrentHashMap<>();
    ModelView model;

    /** One Java node: its engine, model query service, standing queries and harness. */
    final class Node {
        final String name;
        final QueryService service;
        final MqService mq;
        final MqHarness h;
        final StandingQueries standing;

        Node(String name) {
            this.name = name;
            QueryProperties props = QueryProperties.defaults(store.resolve(name).toString());
            BiLiveProperties live = BiLiveProperties.defaults().fast(Duration.ofMillis(20), Duration.ofHours(1));
            service = new QueryService(props, live, new DuckDbEngine(props), lake, Clock.systemUTC());
            mq = new MqService(MqProperties.defaults(), PlanProperties.off());
            h = new MqHarness(service, TestLake.DAY2);
            StandingQueries.Models models = new StandingQueries.Models() {
                @Override
                public MqService.Bound bind(Principal p, String m) {
                    return h.bind(model, MqHarness.RITA, model.key());
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

        @SuppressWarnings("unchecked")
        List<List<Object>> ask(String yaml) {
            return (List<List<Object>>) mq.ask(h.bind(model, MqHarness.RITA, model.key()), MqParser.parse(yaml), false).body().get("rows");
        }

        void close() {
            standing.close();
            service.close();
        }
    }

    /** A peer as another node reaches it (in process here). */
    ShardDirectory.Peer peer(String name) {
        return new ShardDirectory.Peer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean alive() {
                return alive.getOrDefault(name, false);
            }

            @Override
            public Map<String, Object> subscribe(Principal p, ModelQuery q, Duration throttle, String deliver, String id) {
                return nodes.get(name).standing.subscribe(p, q, throttle, deliver, id);
            }

            @Override
            public List<StandingQueries.Event> poll(List<String> ids) {
                return nodes.get(name).standing.poll(ids);
            }

            @Override
            public List<StandingQueries.Event> status(List<String> ids) {
                return nodes.get(name).standing.status(ids);
            }

            @Override
            public void unsubscribe(Principal p, String id) {
                nodes.get(name).standing.unsubscribe(p, id);
            }
        };
    }

    @BeforeEach
    void setUp() {
        lake = new LiveTablesTest.LiveLake();
        model = MqHarness.model(LiveStandingQueriesTest.MODEL, LiveStandingQueriesTest.TRADES, MqEngineTest.DESKS);
        for (String n : List.of("java-1", "java-2")) {
            alive.put(n, true);
            nodes.put(n, new Node(n));
        }
        for (Node n : nodes.values()) {
            ShardDirectory dir = new ShardDirectory(n.name, List.of("java-1", "java-2"), x -> alive.getOrDefault(x, false));
            n.standing.cluster(dir, Map.of("java-1", peer("java-1"), "java-2", peer("java-2")));
        }
        // the generation bus between the nodes: each one's notices reach the other
        GenerationBus a = nodes.get("java-1").service.bus();
        GenerationBus b = nodes.get("java-2").service.bus();
        a.bridge(n -> b.receive(new GenerationBus.Notice(n.key(), n.table(), n.cause(), n.date(), n.generation(), n.change(), "java-1")));
        b.bridge(n -> a.receive(new GenerationBus.Notice(n.key(), n.table(), n.cause(), n.date(), n.generation(), n.change(), "java-2")));
    }

    @AfterEach
    void tearDown() {
        nodes.values().forEach(Node::close);
    }

    void quiesce() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Long> g1 = nodes.values().stream().filter(n -> alive.get(n.name)).map(n -> n.service.live().generation(model.key())).toList();
            Thread.sleep(60);
            List<Long> g2 = nodes.values().stream().filter(n -> alive.get(n.name)).map(n -> n.service.live().generation(model.key())).toList();
            boolean quiet = g1.equals(g2) && nodes.values().stream().filter(n -> alive.get(n.name)).allMatch(n -> n.service.bus().drained(5_000) && n.standing.settled(10_000));
            if (quiet) {
                return;
            }
        }
        throw new AssertionError("the nodes did not settle");
    }

    @Test
    @SuppressWarnings("unchecked")
    void standingQueriesHeldOnTheShardOwnerSurviveTheLossOfThatNode() throws Exception {
        String q = "{ mq: 1, model: live-desks, select: { fields: [trades.desk], measures: [MTM, Trades] } }";
        ShardDirectory any = new ShardDirectory("java-1", List.of("java-1", "java-2"), x -> true);
        String owner = any.owner(model.key());
        String entry = owner.equals("java-1") ? "java-2" : "java-1";
        Node in = nodes.get(entry);
        assertThat(in.ask(q)).isEqualTo(nodes.get(owner).ask(q));                                 // asked queries: any node, the same answer
        Principal rita = new Principal("rita", List.of("risk"));
        Map<String, Object> sub = in.standing.subscribe(rita, MqParser.parse(q), Duration.ofMillis(1), "rows");
        String id = (String) sub.get("subscription");
        assertThat(sub.get("node")).isEqualTo(owner);
        assertThat(nodes.get(owner).standing.stats().get("subscriptions")).isEqualTo(1);
        assertThat(in.standing.stats().get("subscriptions")).isEqualTo(0);
        List<List<Object>> held = (List<List<Object>>) ((Map<String, Object>) sub.get("answer")).get("rows");
        Random rnd = new Random(3);
        List<String> ids = new ArrayList<>(lake.docs.keySet().stream().map(r -> r.id()).sorted().toList());
        for (int round = 0; round < 6; round++) {
            if (round == 3) {
                alive.put(owner, false);                                                            // the owner is lost
                nodes.get(owner).standing.close();
            }
            lake.set(ids.get(rnd.nextInt(ids.size())), "mtm", (double) (rnd.nextInt(20001) - 10000));
            lake.set(ids.get(rnd.nextInt(ids.size())), "desk", TestLake.DESKS.get(rnd.nextInt(3)));
            quiesce();
            for (StandingQueries.Event e : in.standing.poll(List.of(id))) {
                if ("answer".equals(e.name()) && e.data().get("rows") != null) {
                    held = (List<List<Object>>) e.data().get("rows");
                }
            }
            if (round >= 3) {
                quiesce();
                for (StandingQueries.Event e : in.standing.poll(List.of(id))) {
                    if ("answer".equals(e.name()) && e.data().get("rows") != null) {
                        held = (List<List<Object>>) e.data().get("rows");
                    }
                }
            }
            assertThat(StandingQueries.sameRows(held, in.ask(q))).as("round %d (owner %s, lost from round 3)%n held %s%n asked %s", round, owner, held, in.ask(q)).isTrue();
        }
        assertThat(in.standing.stats().get("subscriptions")).as("held again on the surviving node, same id").isEqualTo(1);
        assertThat(in.standing.mine(rita, List.of())).containsExactly(id);
    }
}
