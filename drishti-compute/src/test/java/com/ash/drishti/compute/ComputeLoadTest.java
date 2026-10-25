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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.compute.sched.Latencies;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The load test the product owner asked for ("I can run as many Python workers as I want"): N trivial workers (configurable), a burst of
 * small requests from several users through the fair queue, and one fan-out over all N workers; prints throughput and latency. Not part of
 * the normal build (tag {@code load}); run it with
 *
 * <pre>
 * ./mvnw test -pl drishti-compute -Dcompute.excluded.groups=none -Dgroups=load -Dtest=ComputeLoadTest \
 *     -Ddrishti.compute.load.workers=32 -Ddrishti.compute.load.requests=4000
 * </pre>
 */
@Tag("load")
class ComputeLoadTest {

    @TempDir
    Path dir;

    @Test
    void manyWorkersServeABurstAndAFanOut() {
        assumeTrue(TestCompute.python() != null, "no Python 3.10+ interpreter to run workers");
        int workers = Integer.getInteger("drishti.compute.load.workers", 16);
        int requests = Integer.getInteger("drishti.compute.load.requests", 2000);
        int users = Integer.getInteger("drishti.compute.load.users", 8);
        String mode = System.getProperty("drishti.compute.load.mode", TestCompute.WINDOWS ? "warm" : "fork");
        ComputeSettings s = TestCompute.settings(dir, TestCompute.onePool("load", workers, workers, mode,
                "      timeout: 120s\n      recycle-after: 100000\n      batch-share: 1.0\n").replace("  enabled: true\n",
                "  enabled: true\n  scheduling:\n    max-inflight-per-user: " + workers + "\n    queue: { interactive: " + (requests + 10) + " }\n"));
        try (ComputeService c = TestCompute.service(s, dir)) {
            waitForWorkers(c, workers);
            ArrowTable small = ArrowTable.builder().doubles("X", 1, 2, 3).build();
            // warm-up: every worker has served once
            List<CompletableFuture<ComputeResult>> warm = new ArrayList<>();
            for (int i = 0; i < workers * 2; i++) {
                warm.add(c.submit(ComputeRequest.on("load", "sum", "warm").engine(TestCompute.VECTORS).input("values", small).build(), null).result());
            }
            warm.forEach(CompletableFuture::join);

            Latencies latency = new Latencies(requests);
            List<CompletableFuture<ComputeResult>> all = new ArrayList<>(requests);
            long t0 = System.nanoTime();
            for (int i = 0; i < requests; i++) {
                long sent = System.nanoTime();
                all.add(c.submit(ComputeRequest.on("load", "sum", "user" + (i % users)).engine(TestCompute.VECTORS).input("values", small).build(), null)
                        .result().whenComplete((r, e) -> latency.add((System.nanoTime() - sent) / 1_000_000)));
            }
            all.forEach(CompletableFuture::join);
            double seconds = (System.nanoTime() - t0) / 1e9;

            // closed loop: as many clients as workers, each sending its next request when the last is answered (latency without queueing)
            Latencies closed = new Latencies(requests);
            int per = Math.max(1, requests / workers);
            long c0 = System.nanoTime();
            List<Thread> clients = new ArrayList<>();
            for (int k = 0; k < workers; k++) {
                String user = "user" + (k % users);
                clients.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < per; i++) {
                        long sent = System.nanoTime();
                        c.run(ComputeRequest.on("load", "sum", user).engine(TestCompute.VECTORS).input("values", small).build());
                        closed.add((System.nanoTime() - sent) / 1_000_000);
                    }
                }));
            }
            for (Thread t : clients) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            double closedSeconds = (System.nanoTime() - c0) / 1e9;

            int trades = Integer.getInteger("drishti.compute.load.trades", 20_000);
            double[] pnl = new double[trades * 509];
            long[] ids = new long[trades];
            for (int i = 0; i < trades; i++) {
                ids[i] = i;
                pnl[i * 509 + (i % 509)] = 1.0;
            }
            ArrowTable batch = ArrowTable.builder().longs("TRADE_ID", ids).vectors("PNL", 509, pnl).build();
            long f0 = System.nanoTime();
            ComputeResult fan = c.fanOut(ComputeRequest.on("load", "sum-vectors", "batch").engine(TestCompute.VECTORS).input("values", batch)
                    .param("column", "PNL").cls(RequestClass.BATCH).build(), "values", Math.max(1, trades / (workers * 4)), null).await(Duration.ofMinutes(10));
            double fanSeconds = (System.nanoTime() - f0) / 1e9;

            @SuppressWarnings("unchecked")
            Map<String, Object> pool = ((List<Map<String, Object>>) c.overview().get("pools")).get(0);
            String report = String.format("%n=== compute load test (%s mode) ===%n workers: %d, users: %d, requests: %d%n throughput: %.0f requests/s"
                    + " (%.2f s)%n end-to-end latency ms: p50 %d, p95 %d, p99 %d, max %d%n pool queue ms: %s; run ms: %s%n fan-out: %d trades x 509 in %s chunks"
                    + " on %s workers: %.2f s (%.0f trades/s)%n closed loop (%d clients): %.0f requests/s, latency ms p50 %d, p95 %d, p99 %d%n", mode, workers, users, requests, requests / seconds, seconds, latency.percentile(50),
                    latency.percentile(95), latency.percentile(99), latency.percentile(100), pool.get("queueMs"), pool.get("runMs"), trades,
                    fan.meta().get("chunks"), fan.meta().get("workers"), fanSeconds, trades / fanSeconds, workers, per * workers / closedSeconds,
                    closed.percentile(50), closed.percentile(95), closed.percentile(99));
            System.out.println(report);
            assertThat(fan.rows()).hasSize(((Number) fan.meta().get("chunks")).intValue());
            assertThat(((Number) fan.meta().get("workers")).intValue()).isGreaterThanOrEqualTo(Math.min(workers, ((Number) fan.meta().get("chunks")).intValue()) / 2);
            assertThat(all).allMatch(f -> !f.isCompletedExceptionally());
        }
    }

    private static void waitForWorkers(ComputeService c, int n) {
        long until = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < until) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pool = ((List<Map<String, Object>>) c.overview().get("pools")).get(0);
            if (((Number) pool.get("idle")).intValue() >= n) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
