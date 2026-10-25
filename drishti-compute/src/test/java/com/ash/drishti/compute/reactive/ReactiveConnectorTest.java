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
package com.ash.drishti.compute.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.compute.ComputeEngine;
import com.ash.drishti.api.compute.ComputeEvent;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineManifest;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.compute.RequestClass;
import com.ash.drishti.compute.reactive.samples.VectorSumEngine;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The request lifecycle, single flight, keys, isolation, triggers, ordering and sandboxes of a reactive connector, with a Java engine. */
class ReactiveConnectorTest {

    static final String SPEC = """
            {"mode":"reactive",
             "kinds":{"sum-vectors":{"params":{"input":{"type":"text","default":"values"},
                                               "column":{"type":"enum","options":["PNL","SECRET_PNL"],"default":"PNL"},
                                               "delay-ms":{"type":"int","default":0,"min":0,"max":1000},
                                               "whatif":{"type":"sandbox-ref"}},
                                    "incremental":true,
                                    "result":{"required":["SUM"],"types":{"SUM":"number"}}}},
             "reads":[{"kind":"strip","as":"values","all":true}],
             "engine":{"type":"java","class":"test"},
             "triggers":[{"on":"write-back","kinds":["strip"],"affects":["${payload.book}"]},
                         {"on":"data-version","kinds":["strip"],"affects":"all"},
                         {"on":"live","kinds":["strip"]}],
             "coalesce":{"quiet":"50ms","max-wait":"300ms"},
             "limits":{"wait":"2s","max-inflight-per-user":2}}
            """;

    /** Counts computations; can hold them until released. */
    static final class Counting implements ComputeEngine {
        final AtomicInteger calls = new AtomicInteger();
        final VectorSumEngine inner = new VectorSumEngine();
        volatile CountDownLatch gate;
        final List<String> users = new CopyOnWriteArrayList<>();

        @Override
        public EngineManifest manifest() {
            return new EngineManifest("counting", "1", List.of("sum-vectors"), Set.of("sum-vectors"));
        }

        @Override
        public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) throws Exception {
            calls.incrementAndGet();
            users.add(req.viewer().user() + ":" + inputs.documents("values").get(0).get("SECRET").asText());
            progress.fraction(1, 2);
            CountDownLatch g = gate;
            if (g != null) {
                g.await(10, TimeUnit.SECONDS);
            }
            return inner.compute(req, inputs, progress);
        }

        @Override
        public Optional<EngineResult> incremental(EngineRequest r, EngineResult prev, List<ComputeEvent> ev, EngineInputs in) {
            return inner.incremental(r, prev, ev, in);
        }
    }

    @TempDir
    Path dir;
    FakeHost host;
    ReactiveHub hub;
    Counting engine;
    ReactiveConnector c;
    ScheduledExecutorService sched;

    static final Viewer ASHA = new Viewer("asha", List.of("risk", "raw"), "vs-raw");
    static final Viewer BEN = new Viewer("ben", List.of("risk"), "vs-plain");
    static final Viewer CARA = new Viewer("cara", List.of("risk"), "vs-plain");

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        host.versions.put("strip", "v1");
        host.put("strip", "A", Map.of("BOOK", "B1", "PNL", List.of(1.0, 2.0, 3.0), "SECRET", "s-a"));
        host.put("strip", "B", Map.of("BOOK", "B1", "PNL", List.of(10.0, -20.0, 30.0), "SECRET", "s-b"));
        hub = new ReactiveHub();
        hub.install(host, dir);
        engine = new Counting();
        sched = Executors.newScheduledThreadPool(2);
        c = new ReactiveConnector("demo-compute", ReactiveSpec.parse(SPEC, "demo-compute.yaml", v -> v), JavaRunner.of(engine), hub, sched,
                Clock.systemUTC()).start();
    }

    @AfterEach
    void tearDown() {
        c.close();
        sched.shutdownNow();
    }

    Answer ask(Viewer v, String ref) {
        return c.answer(new ReactiveConnector.Ask("sum-vectors", ref, AsOf.LATEST, v, RequestClass.INTERACTIVE, Duration.ofSeconds(5)));
    }

    @Test
    void computesStampsCachesAndAudits() {
        Answer a = ask(ASHA, "B1");
        assertThat(a.state()).isEqualTo(Answer.READY);
        assertThat(a.document().get("SUM")).isEqualTo(26.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> stamp = (Map<String, Object>) a.document().get("STAMP");
        assertThat(stamp).containsKeys("pin", "resultHash", "computedAt", "engine").containsEntry("businessDate", "2026-10-23");
        assertThat(ask(ASHA, "B1").document().get("STAMP")).isEqualTo(stamp);              // a cache hit: the same stamp
        assertThat(engine.calls.get()).isEqualTo(1);
        assertThat(host.audits).anyMatch(s -> s.startsWith("asha compute.answer demo-compute/sum-vectors/B1"));
    }

    @Test
    void parametersAreTypedCheckedAndPartOfTheKey() {
        assertThat(ask(ASHA, "B1~column=OTHER").state()).isEqualTo(Answer.REFUSED);
        assertThat(ask(ASHA, "B1~column=OTHER").message()).contains("column OTHER is not allowed for sum-vectors; allowed: PNL, SECRET_PNL");
        assertThat(ask(ASHA, "B1~nope=1").message()).contains("has no parameter 'nope'");
        assertThat(ask(ASHA, "B1~delay-ms=2000").message()).contains("at most 1000");
        ask(ASHA, "B1~delay-ms=0");
        ask(ASHA, "B1");                                                                    // the default: the same key
        assertThat(engine.calls.get()).isEqualTo(1);
        ask(ASHA, "B1~delay-ms=1");
        assertThat(engine.calls.get()).isEqualTo(2);
        assertThat(ComputeRef.parse(ComputeRef.format("A~B|C", Map.of("x", "1=2")))).isEqualTo(new ComputeRef("A~B|C", Map.of("x", "1=2")));
    }

    @Test
    void twoReadsOfOneKeyCauseOneComputation() throws Exception {
        engine.gate = new CountDownLatch(1);
        List<Answer> got = new CopyOnWriteArrayList<>();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ts.add(Thread.ofVirtual().start(() -> got.add(ask(BEN, "B1"))));
        }
        Thread.sleep(300);
        engine.gate.countDown();
        for (Thread t : ts) {
            t.join();
        }
        assertThat(engine.calls.get()).isEqualTo(1);
        assertThat(got).extracting(Answer::state).containsOnly(Answer.READY);
        assertThat(got.get(0).requestId()).isNotEqualTo(got.get(1).requestId());           // each its own correlation id
    }

    @Test
    void aNewDataVersionIsANewKey() {
        ask(ASHA, "B1");
        host.versions.put("strip", "v2");
        ask(ASHA, "B1");
        assertThat(engine.calls.get()).isEqualTo(2);
    }

    @Test
    void viewersWithDifferentRightsNeverShareAnAnswer() {
        Answer a = ask(ASHA, "B1");
        Answer b = ask(BEN, "B1");
        Answer cara = ask(CARA, "B1");                                                      // Ben's rights: shares Ben's answer
        assertThat(engine.calls.get()).isEqualTo(2);
        assertThat(engine.users).containsExactly("asha:s-a", "ben:•••");                     // inputs read and masked as each viewer
        assertThat(b.document().get("STAMP")).isEqualTo(cara.document().get("STAMP"));
        assertThat(a.document().get("STAMP")).isNotEqualTo(b.document().get("STAMP"));
    }

    @Test
    void refusedInWordsBeforeAnyWork() {
        host.denyKind = "sum-vectors";
        assertThat(ask(BEN, "B1").message()).isEqualTo("your role cannot open sum-vectors");
        host.denyKind = null;
        assertThat(c.answer(new ReactiveConnector.Ask("sum-vectors", "B1", AsOf.LATEST, null, RequestClass.INTERACTIVE, Duration.ZERO)).state())
                .isEqualTo(Answer.REFUSED);
        assertThat(ask(BEN, "B1~whatif=sbx-nope").message()).isEqualTo("there is no sandbox sbx-nope");
        assertThat(engine.calls.get()).isZero();
    }

    @Test
    void aSlowComputationAnswersComputingThenStreamsProgressAndDone() throws Exception {
        engine.gate = new CountDownLatch(1);
        Answer a = c.answer(new ReactiveConnector.Ask("sum-vectors", "B1", AsOf.LATEST, BEN, RequestClass.INTERACTIVE, Duration.ofMillis(50)));
        assertThat(a.state()).isEqualTo(Answer.COMPUTING);
        assertThat(a.toDocument("B1").get(Answer.FIELD).get("requestId").asText()).isEqualTo(a.requestId());
        List<String> events = new CopyOnWriteArrayList<>();
        try (AutoCloseable l = hub.events().listen(a.requestId(), "ben", e -> events.add(e.type()))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> hub.events().listen(a.requestId(), "asha", e -> { }))
                    .isInstanceOf(java.util.NoSuchElementException.class);                     // another user's request: unknown
            engine.gate.countDown();
            long until = System.currentTimeMillis() + 5000;
            while (!events.contains("done") && System.currentTimeMillis() < until) {
                Thread.sleep(10);
            }
        }
        assertThat(events).contains("progress", "done");
        assertThat(ask(BEN, "B1").state()).isEqualTo(Answer.READY);
    }

    @Test
    void burstsAreCoalescedRedeliveriesIgnoredAndOnlyWatchedResultsRecompute() throws Exception {
        Answer a = ask(BEN, "B1");
        List<String> events = new CopyOnWriteArrayList<>();
        try (AutoCloseable l = hub.events().listen(a.requestId(), "ben", e -> events.add(e.type()))) {
            int before = engine.calls.get();
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < 200; i++) {
                c.event(new ComputeEvent("chg-" + i, ComputeEvent.WRITE_BACK, "strip", "A", null, Map.of("L17", (long) i), null, "asha", List.of(),
                        Map.of("book", "B1", "delta", List.of(0.0, 0.0, 1.0))));
                Thread.sleep(4);
            }
            long burst = System.currentTimeMillis() - t0;
            assertThat(c.event(new ComputeEvent("chg-5", ComputeEvent.WRITE_BACK, "strip", "A", null, Map.of(), null, "", List.of(),
                    Map.of("book", "B1"))).get("ignored")).isNotNull();
            Thread.sleep(1200);
            int runs = engine.calls.get() - before;
            long allowed = (long) Math.ceil(burst / 300.0) + 1;
            assertThat((long) runs).isLessThanOrEqualTo(allowed);                       // incremental runs need no engine call: fewer still
            assertThat(events).contains("updated");
            Answer now = ask(BEN, "B1");
            @SuppressWarnings("unchecked")
            Map<String, Object> st = (Map<String, Object>) now.document().get("STAMP");
            assertThat(st.get("watermark")).isEqualTo(Map.of("L17", 199L));            // includes every change of the burst
        }
        assertThat(c.event(new ComputeEvent("chg-x", ComputeEvent.WRITE_BACK, "strip", "A", null, Map.of(), null, "", List.of(),
                Map.of("book", "B9"))).get("recomputing")).isEqualTo(0);                // a scope nobody shows: nothing computed
    }

    @Test
    void aLateOlderResultNeverReplacesANewerOne() {
        ResultCache cache = new ResultCache(10, Duration.ofHours(1), Clock.systemUTC());
        Watermark newer = new Watermark(Map.of("L17", 3412L), 2);
        Watermark older = new Watermark(Map.of("L17", 3410L), 3);
        assertThat(cache.put(new ResultCache.Entry("k2", "b", Map.of("v", 2), newer, java.time.Instant.now(), false))).isTrue();
        assertThat(cache.put(new ResultCache.Entry("k1", "b", Map.of("v", 1), older, java.time.Instant.now(), false))).isFalse();
        assertThat(cache.newest("b").orElseThrow().document()).containsEntry("v", 2);
        assertThat(new Watermark(Map.of("L17", 1L, "L42", 9L), 1).olderThan(new Watermark(Map.of("L17", 2L), 2))).isFalse();   // concurrent lanes
    }

    @Test
    void sandboxesArePrivate() {
        Sandboxes.Sandbox s = hub.sandboxes().create("asha", "demo-compute", "what if", List.of(Map.of("PNL", List.of(1, 1, 1))));
        assertThat(ask(ASHA, "B1~whatif=" + s.id()).state()).isEqualTo(Answer.READY);
        assertThat(ask(BEN, "B1~whatif=" + s.id()).message()).isEqualTo("there is no sandbox " + s.id());
        assertThat(hub.sandboxes().list("ben")).isEmpty();
        Sandboxes.Sandbox copy = hub.sandboxes().copyFor("asha", s.id(), "ben");
        assertThat(copy.frozen()).isTrue();
        assertThat(new Sandboxes(dir.resolve("compute").resolve("sandboxes"), Clock.systemUTC()).get("asha", s.id())).isPresent();   // kept on disk
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> hub.sandboxes().update("ben", copy.id(), List.of(), null))
                .hasMessageContaining("frozen");
    }

    @Test
    void liveEntitiesReadByAWatchedComputationTriggerIt() throws Exception {
        Answer a = ask(BEN, "B1");
        try (AutoCloseable l = hub.events().listen(a.requestId(), "ben", e -> { })) {
            assertThat(host.live).isEmpty();                                     // "all" reads: nothing to follow by id
            Map<String, Object> r = c.event(new ComputeEvent("live/strip/A/7", ComputeEvent.LIVE, "strip", "A", null, Map.of(), null, "", List.of(), Map.of()));
            assertThat(r.get("recomputing")).isEqualTo(1);                       // subscribed: the watched result recomputes
        }
    }
}
