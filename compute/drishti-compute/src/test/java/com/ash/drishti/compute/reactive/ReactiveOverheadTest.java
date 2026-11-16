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
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineManifest;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.compute.RequestClass;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a reactive connector adds to a read (DATA_ARCHITECTURE.md, the UI must stay nimble): a cache hit (authorise, check the parameters,
 * pin, key, look up) and a miss with an engine that does nothing (the same plus single flight, admission, a virtual thread, validation,
 * the stamp and the result's hash, audit). The numbers are printed; the bounds are loose, so a slow machine does not fail it.
 */
class ReactiveOverheadTest {

    static final String SPEC = """
            {"mode":"reactive","kinds":{"k":{"params":{"n":{"type":"int","default":0},"c":{"type":"decimal","default":0.99,"allowed":[0.95,0.99]}}}},
             "reads":[{"kind":"pos","as":"values","id":"{id}"}],"engine":{"type":"java","class":"t"},"cache":{"results":200000},
             "limits":{"wait":"5s","max-inflight-per-user":64}}
            """;

    /** An engine that answers at once. */
    static final class Trivial implements ComputeEngine {
        @Override
        public EngineManifest manifest() {
            return new EngineManifest("trivial", "1", List.of("k"));
        }

        @Override
        public EngineResult compute(EngineRequest r, EngineInputs in, EngineProgress p) {
            return EngineResult.of(Map.of("V", 1));
        }
    }

    @TempDir
    Path dir;

    @Test
    void theOverheadOfAHitAndOfAMissWithATrivialEngine() {
        FakeHost host = new FakeHost();
        host.versions.put("pos", "v1");
        host.put("pos", "P", Map.of("X", 1));
        ReactiveHub hub = new ReactiveHub();
        hub.install(host, dir);
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);
        ReactiveConnector c = new ReactiveConnector("bench", ReactiveSpec.parse(SPEC, "bench.yaml", v -> v), JavaRunner.of(new Trivial()), hub, sched,
                Clock.systemUTC()).start();
        Viewer v = new Viewer("asha", List.of("risk"), "vs-1");
        try {
            int misses = 3_000;
            int hits = 30_000;
            for (int i = 0; i < 500; i++) {                                          // warm up the JIT
                ask(c, v, "P~n=" + (1_000_000 + i));
                ask(c, v, "P~n=" + (1_000_000 + i));
            }
            long[] miss = new long[misses];
            for (int i = 0; i < misses; i++) {
                long t = System.nanoTime();
                Answer a = ask(c, v, "P~n=" + i);
                miss[i] = System.nanoTime() - t;
                assertThat(a.state()).isEqualTo(Answer.READY);
            }
            long[] hit = new long[hits];
            for (int i = 0; i < hits; i++) {
                long t = System.nanoTime();
                Answer a = ask(c, v, "P~n=" + (i % misses));
                hit[i] = System.nanoTime() - t;
                assertThat(a.state()).isEqualTo(Answer.READY);
            }
            String m = summary(miss);
            String h = summary(hit);
            System.out.println("reactive overhead: cache hit " + h + "; miss with a trivial engine " + m);
            assertThat(pct(hit, 50)).isLessThan(2_000_000L);                         // well under a frame: 2 ms at p50 on a slow machine
            assertThat(pct(miss, 50)).isLessThan(20_000_000L);
        } finally {
            c.close();
            sched.shutdownNow();
        }
    }

    private static Answer ask(ReactiveConnector c, Viewer v, String ref) {
        return c.answer(new ReactiveConnector.Ask("k", ref, AsOf.LATEST, v, RequestClass.INTERACTIVE, Duration.ofSeconds(5)));
    }

    private static long pct(long[] ns, int p) {
        long[] s = ns.clone();
        Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) Math.ceil(p / 100.0 * s.length) - 1)];
    }

    private static String summary(long[] ns) {
        return String.format("p50 %.1f µs, p95 %.1f µs, p99 %.1f µs (%d reads)", pct(ns, 50) / 1e3, pct(ns, 95) / 1e3, pct(ns, 99) / 1e3, ns.length);
    }
}
