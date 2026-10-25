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
package com.ash.drishti.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.compute.arrow.ArrowTable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step C1's acceptance (docs/architecture/PYTHON_COMPUTE.md section 16): an engine computes over a million Arrow rows in a worker; a crashing
 * or looping engine is stopped and the pool carries on; the scratch folder is gone after every request; requests never share a process or
 * see each other's state; limits and the sandbox refuse with clear errors. Fork mode (the Linux default).
 */
class ComputePoolTest {

    @TempDir
    Path dir;
    ComputeService compute;
    ComputeSettings settings;

    @BeforeEach
    void start() {
        assumeTrue(TestCompute.python() != null, "no Python 3.10+ interpreter to run workers");
        assumeTrue(!TestCompute.WINDOWS, "fork mode: Linux and macOS (WarmModeTest covers Windows)");
        settings = TestCompute.settings(dir, TestCompute.onePool("p", 0, 4, "fork", "      timeout: 20s\n"));
        compute = TestCompute.service(settings, dir);
    }

    @AfterEach
    void stop() {
        if (compute != null) {
            compute.close();
        }
    }

    private ComputeRequest.Builder req(String kind, String user) {
        return ComputeRequest.on("p", kind, user).engine(TestCompute.FAULTS);
    }

    @Test
    void anEngineSumsAMillionArrowRowsInAWorker() throws Exception {
        int n = 1_000_000;
        double[] x = new double[n];
        long[] k = new long[n];
        for (int i = 0; i < n; i++) {
            x[i] = i * 0.5;
            k[i] = i;
        }
        ArrowTable t = ArrowTable.builder().doubles("X", x).longs("K", k).build();
        ComputeResult r = compute.run(ComputeRequest.on("p", "sum", "asha").engine(TestCompute.VECTORS).input("values", t).build());
        assertThat(r.rows()).hasSize(2);
        assertThat(((Number) r.rows().get(0).get("sum")).doubleValue()).isEqualTo(0.5 * (n - 1.0) * n / 2.0);
        assertThat(((Number) r.rows().get(1).get("sum")).longValue()).isEqualTo((long) (n - 1) * n / 2);
        assertThat(((Number) r.rows().get(0).get("count")).intValue()).isEqualTo(n);
        assertThat(r.worker()).startsWith("test/p#");
        assertThat(TestCompute.scratchLeft(settings)).isEmpty();
    }

    @Test
    void echoRoundTripsEveryColumnTypeThroughPythonAndBack() throws Exception {
        ArrowTable t = ArrowTable.builder().longs("I", 1, 2, 3).doubles("F", 1.5, Double.NaN, -2).bools("B", true, false, true)
                .strings("S", "a", null, "héllo").vectors("V", 2, new double[] {1, 2, 3, 4, 5, 6}).build();
        ComputeResult r = compute.run(ComputeRequest.on("p", "echo", "asha").engine(TestCompute.VECTORS).param("n", 7).input("values", t).build());
        ArrowTable back = r.table("values");
        assertThat(back.names()).containsExactly("I", "F", "B", "S", "V");
        assertThat(back.longs("I")).containsExactly(1, 2, 3);
        assertThat(back.doubles("F")[0]).isEqualTo(1.5);
        assertThat(back.doubles("F")[1]).isNaN();
        assertThat(back.strings("S")).containsExactly("a", null, "héllo");
        assertThat(back.vector("V", 2)).containsExactly(5, 6);
        assertThat(r.rows().get(0)).containsEntry("user", "asha").containsEntry("params", Map.of("n", 7));
        assertThat(r.meta()).containsEntry("engine", "vectors");
    }

    @Test
    void sumVectorsAddsThePathsOfEveryRow() {
        double[] v = new double[3 * 509];
        for (int i = 0; i < v.length; i++) {
            v[i] = (i % 509) + 1;
        }
        ComputeResult r = compute.run(ComputeRequest.on("p", "sum-vectors", "asha").engine(TestCompute.VECTORS)
                .input("values", ArrowTable.builder().vectors("PNL", 509, v).build()).build());
        double[] total = r.table("total").vector("PNL", 0);
        assertThat(total).hasSize(509);
        assertThat(total[0]).isEqualTo(3.0);
        assertThat(total[508]).isEqualTo(3.0 * 509);
    }

    @Test
    void aCrashingEngineFailsItsRequestAndThePoolCarriesOn() {
        assertThatThrownBy(() -> compute.run(req("crash", "asha").build()))
                .isInstanceOfSatisfying(ComputeException.class, e -> assertThat(e.code()).isEqualTo(ComputeException.Code.CRASHED));
        ComputeResult ok = compute.run(req("who", "asha").build());
        assertThat(ok.rows().get(0)).containsEntry("user", "asha");
    }

    @Test
    void aLoopingEngineIsStoppedAtItsBudgetAndThePoolCarriesOn() throws Exception {
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> compute.run(req("loop", "asha").timeout(Duration.ofMillis(1500)).build()))
                .isInstanceOfSatisfying(ComputeException.class, e -> assertThat(e.code()).isEqualTo(ComputeException.Code.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(10));
        assertThat(compute.run(req("who", "asha").build()).rows()).hasSize(1);
        assertThat(TestCompute.scratchLeft(settings)).isEmpty();
    }

    @Test
    void stateSetByOneRequestIsNotSeenByTheNext() {
        compute.run(req("leak", "asha").param("value", "asha's secret").build());
        for (int i = 0; i < 5; i++) {
            Map<String, Object> who = compute.run(req("who", "ben").build()).rows().get(0);
            assertThat(who.get("leak")).isNull();
            assertThat(who.get("user")).isEqualTo("ben");
        }
    }

    @Test
    void concurrentRequestsOfTwoUsersEachGetTheirOwnAnswerFromTheirOwnProcess() {
        List<ComputeHandle> hs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String user = i % 2 == 0 ? "asha" : "ben";
            hs.add(compute.submit(req("sleep", user).param("seconds", 0.3).build(), ProgressSink.NONE));
        }
        List<Long> pids = new ArrayList<>();
        for (int i = 0; i < hs.size(); i++) {
            ComputeResult r = hs.get(i).await(Duration.ofSeconds(30));
            assertThat(r.requestId()).isEqualTo(hs.get(i).id());
            assertThat(r.rows().get(0)).containsEntry("user", i % 2 == 0 ? "asha" : "ben");
            pids.add(((Number) r.rows().get(0).get("pid")).longValue());
        }
        assertThat(pids).doesNotHaveDuplicates();                              // a fresh child per request
    }

    @Test
    void progressEventsReachTheRequestThatAskedOnly() {
        List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
        List<String> ids = new CopyOnWriteArrayList<>();
        ComputeHandle h = compute.submit(req("progress", "asha").build(), (id, data) -> {
            ids.add(id);
            seen.add(data);
        });
        h.await(Duration.ofSeconds(20));
        assertThat(seen).hasSize(3);
        assertThat(seen.get(2)).containsEntry("done", 3).containsEntry("of", 3).containsEntry("estimate", 30);
        assertThat(ids).allMatch(h.id()::equals);
    }

    @Test
    void anEnginesOwnRefusalIsShownAndItsInternalsAreNot() {
        assertThatThrownBy(() -> compute.run(req("refuse", "asha").build()))
                .isInstanceOfSatisfying(ComputeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ComputeException.Code.ENGINE);
                    assertThat(e.getMessage()).isEqualTo("the scope is empty");
                });
        assertThatThrownBy(() -> compute.run(req("boom", "asha").build()))
                .isInstanceOfSatisfying(ComputeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ComputeException.Code.FAILED);
                    assertThat(e.getMessage()).doesNotContain("secret").contains(e.requestId());
                });
    }

    @Test
    void theSandboxRefusesSocketsProcessesAndWritesOutsideTheScratchFolder() {
        Path outside = dir.resolve("escaped.txt");
        for (ComputeRequest r : List.of(req("socket", "asha").build(), req("subprocess", "asha").build(),
                req("write-outside", "asha").param("path", outside.toString()).build())) {
            assertThatThrownBy(() -> compute.run(r))
                    .isInstanceOfSatisfying(ComputeException.class, e -> {
                        assertThat(e.code()).isEqualTo(ComputeException.Code.LIMIT);
                        assertThat(e.getMessage()).contains("sandbox refused");
                    });
        }
        assertThat(outside).doesNotExist();
    }

    @Test
    void aRunningRequestCanBeCancelledAndTheWorkerIsKept() throws Exception {
        ComputeHandle h = compute.submit(req("sleep", "asha").param("seconds", 30).build(), ProgressSink.NONE);
        Thread.sleep(800);
        assertThat(h.state()).isEqualTo("running");
        assertThat(compute.cancel(h.id())).isTrue();
        assertThatThrownBy(() -> h.await(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ComputeException.class, e -> assertThat(e.code()).isEqualTo(ComputeException.Code.CANCELLED));
        assertThat(compute.run(req("who", "asha").build()).rows()).hasSize(1);
        Thread.sleep(200);
        assertThat(TestCompute.scratchLeft(settings)).isEmpty();
    }

    @Test
    void anUnknownPoolOrComputeOffIsRefusedInWords() {
        assertThatThrownBy(() -> compute.run(ComputeRequest.on("nope", "echo", "asha").build()))
                .isInstanceOfSatisfying(ComputeException.class, e -> assertThat(e.code()).isEqualTo(ComputeException.Code.REFUSED));
        try (ComputeService off = TestCompute.service(settings.withEnabled(false), dir)) {
            assertThatThrownBy(() -> off.run(ComputeRequest.on("p", "echo", "asha").build()))
                    .isInstanceOfSatisfying(ComputeException.class, e -> assertThat(e.code()).isEqualTo(ComputeException.Code.OFF));
        }
    }

    @Test
    void theCpuLimitStopsALoopBeforeItsWallBudget() {
        try (ComputeService c = TestCompute.service(TestCompute.settings(dir.resolve("cpu"),
                TestCompute.onePool("p", 0, 1, "fork", "      cpu-seconds: 1\n")), dir)) {
            assertThatThrownBy(() -> c.run(req("loop", "asha").timeout(Duration.ofSeconds(30)).build()))
                    .isInstanceOfSatisfying(ComputeException.class, e -> {
                        assertThat(e.code()).isEqualTo(ComputeException.Code.LIMIT);
                        assertThat(e.getMessage()).contains("CPU");
                    });
        }
    }

    @Test
    void theMemoryLimitRefusesAnEngineThatAsksForTooMuch() {
        try (ComputeService c = TestCompute.service(TestCompute.settings(dir.resolve("mem"),
                TestCompute.onePool("p", 0, 1, "fork", "      memory: 512MB\n")), dir)) {
            assertThatThrownBy(() -> c.run(req("memory", "asha").param("bytes", 2L << 30).build()))
                    .isInstanceOfSatisfying(ComputeException.class, e -> assertThat(e.code()).isEqualTo(ComputeException.Code.LIMIT));
            assertThat(c.run(req("who", "asha").build()).rows()).hasSize(1);
        }
    }
}
