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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.ComputeSettings;
import com.ash.drishti.compute.RequestClass;
import com.ash.drishti.compute.TestCompute;
import com.ash.drishti.compute.reactive.samples.VectorSumEngine;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker bridge: the sample Python engine computes what the Java twin computes, one worker or fanned out over all of a pool's workers
 * (gathered and added up), with progress on the request's events, and a failure in the engine is a failed answer in words.
 */
class PythonBridgeTest {

    static final String PATHS = "drishti_compute.samples.paths:PathSumEngine";

    static String spec(String engine) {
        return """
                {"mode":"reactive",
                 "kinds":{"sum-vectors":{"params":{"delay-ms":{"type":"int","default":0}}}},
                 "reads":[{"kind":"strip","as":"values","all":true}],
                 "engine":%s,
                 "limits":{"wait":"20s"}}
                """.formatted(engine);
    }

    @TempDir
    Path dir;

    @Test
    void pythonAndJavaGiveTheSameAnswerOnOneWorkerAndFannedOut() throws Exception {
        assumeTrue(TestCompute.python() != null, "no Python 3.10+ to run workers with");
        String yaml = "compute:\n  enabled: true\n  pools:\n    sample:\n      engines: [" + PATHS + "]\n      workers: { min: 0, max: 3 }\n      mode: auto\n";
        ComputeSettings s = TestCompute.settings(dir, yaml);
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
        try (ComputeService compute = TestCompute.service(s, dir)) {
            FakeHost host = new FakeHost();
            host.compute = compute;
            host.versions.put("strip", "v1");
            int n = 2_000;
            double[] expect = new double[509];
            for (int i = 0; i < n; i++) {
                List<Double> path = new ArrayList<>(509);
                for (int d = 0; d < 509; d++) {
                    double v = ((i * 31 + d * 7) % 101) - 50.0;
                    path.add(v);
                    expect[d] += v;
                }
                host.put("strip", String.format("P%05d", i), Map.of("BOOK", "B1", "PNL", path, "SECRET", "x"));
            }
            ReactiveHub hub = new ReactiveHub();
            hub.install(host, dir);
            Viewer v = new Viewer("asha", List.of("risk"), "vs-1");
            ReactiveSpec one = ReactiveSpec.parse(spec("{\"type\":\"python\",\"entry\":\"" + PATHS + "\",\"pool\":\"sample\"}"), "p.yaml", x -> x);
            ReactiveSpec fan = ReactiveSpec.parse(spec("{\"type\":\"python\",\"entry\":\"" + PATHS + "\",\"pool\":\"sample\",\"split\":\"values\","
                    + "\"chunk-rows\":500,\"reduce\":{\"sum\":[\"ROWS\",\"SUM\"],\"tables\":{\"total\":[\"PNL\"]}}}"), "f.yaml", x -> x);
            ReactiveSpec jspec = ReactiveSpec.parse(spec("{\"type\":\"java\",\"class\":\"" + VectorSumEngine.class.getName() + "\"}"), "j.yaml", x -> x);
            Map<String, Object> py = answer(hub, sched, "py", one, new PythonRunner(() -> compute, one.engine(), one.engine().reduce()), v);
            Map<String, Object> fanned = answer(hub, sched, "fan", fan, new PythonRunner(() -> compute, fan.engine(), fan.engine().reduce()), v);
            Map<String, Object> jv = answer(hub, sched, "jv", jspec, JavaRunner.of(jspec.engine().entry(), false, "jv"), v);
            for (Map<String, Object> doc : List.of(py, fanned, jv)) {
                assertThat(((Number) doc.get("ROWS")).intValue()).isEqualTo(n);
                assertThat(((Number) doc.get("SUM")).doubleValue()).isCloseTo(java.util.Arrays.stream(expect).sum(), org.assertj.core.data.Offset.offset(1e-6));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> total = ((Map<String, List<Map<String, Object>>>) doc.get("TABLES")).get("total");
                assertThat(total).hasSize(509);
                assertThat(((Number) total.get(17).get("PNL")).doubleValue()).isCloseTo(expect[17], org.assertj.core.data.Offset.offset(1e-6));
            }
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void anEngineThatRefusesIsAFailedAnswerInWords() throws Exception {
        assumeTrue(TestCompute.python() != null, "no Python 3.10+ to run workers with");
        String yaml = "compute:\n  enabled: true\n  pools:\n    sample:\n      engines: [" + PATHS + "]\n      workers: { min: 0, max: 1 }\n";
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);
        try (ComputeService compute = TestCompute.service(TestCompute.settings(dir, yaml), dir)) {
            FakeHost host = new FakeHost();
            host.compute = compute;
            host.put("strip", "A", Map.of("BOOK", "B1", "NAME", "no path"));
            ReactiveHub hub = new ReactiveHub();
            hub.install(host, dir);
            ReactiveSpec one = ReactiveSpec.parse(spec("{\"type\":\"python\",\"entry\":\"" + PATHS + "\",\"pool\":\"sample\"}"), "p.yaml", x -> x);
            ReactiveConnector c = new ReactiveConnector("py", one, new PythonRunner(() -> compute, one.engine(), null), hub, sched, Clock.systemUTC()).start();
            Answer a = c.answer(new ReactiveConnector.Ask("sum-vectors", "B1", AsOf.LATEST, new Viewer("asha", List.of(), "vs"), RequestClass.INTERACTIVE,
                    Duration.ofSeconds(20)));
            assertThat(a.state()).isEqualTo(Answer.FAILED);
            assertThat(a.message()).isEqualTo("no record of values has a PNL path to add up");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    private static Map<String, Object> answer(ReactiveHub hub, ScheduledExecutorService sched, String name, ReactiveSpec spec, EngineRunner runner, Viewer v)
            throws Exception {
        ReactiveConnector c = new ReactiveConnector(name, spec, runner, hub, sched, Clock.systemUTC()).start();
        try {
            List<Object> progress = new CopyOnWriteArrayList<>();
            Answer a = c.answer(new ReactiveConnector.Ask("sum-vectors", "B1", AsOf.LATEST, v, RequestClass.INTERACTIVE, Duration.ofSeconds(30)));
            assertThat(a.state()).as(name + ": " + a.message()).isEqualTo(Answer.READY);
            try (AutoCloseable l = hub.events().listen(a.requestId(), "asha", e -> progress.add(e.type()))) {
                assertThat(l).isNotNull();
            }
            return a.document();
        } finally {
            c.close();
        }
    }
}
