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
package com.ash.drishti.server.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/**
 * The benchmark of DRISHTI_NET.md "HTTP/2 for peer data" (2.6.7): many concurrent forwarded reads, peer-protocol http1 then http2. Two
 * servers in one JVM: A holds the demo trades, B none; users ask B for trade views, B forwards each to A. Prints, per protocol, the
 * connections A held from B at most (sampled every 20 ms), and the users' p50, p99 and throughput. Run on demand:
 * {@code -Ddrishti.net.bench=true -Dtest=NetPeerProtocolBench} (optionally {@code -Ddrishti.net.bench.concurrency=64 -Ddrishti.net.bench.requests=4000}).
 */
@EnabledIfSystemProperty(named = "drishti.net.bench", matches = "true")
class NetPeerProtocolBench {

    record Result(String protocol, int requests, int failed, double p50, double p99, double perSecond, int maxConnections) {}

    @Test
    void manyConcurrentForwardedReadsOverHttp1ThenHttp2() throws Exception {
        int concurrency = Integer.getInteger("drishti.net.bench.concurrency", 64);
        int requests = Integer.getInteger("drishti.net.bench.requests", 4000);
        Path root = Path.of("target", "net-it", "bench-" + System.nanoTime()).toAbsolutePath();
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        try (NetNodes cluster = new NetNodes(root)) {
            cluster.start("a", p[0], NetForwardTest.secure());
            cluster.start("b", p[1], NetForwardTest.secure("--drishti.sources.plugins.demo.enabled=false"));
            assertThat(NetNodes.await(15_000, () -> List.of("a", "b").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table()
                    .nodes().size() == 2))).isTrue();
            List<Result> out = new ArrayList<>();
            for (String protocol : List.of("http1", "http2", "http1", "http2")) {
                Files.writeString(root.resolve("b/net/routing.yaml"), "peer-protocol: " + protocol + "\n");
                assertThat(NetNodes.await(5_000, () -> cluster.node("b").net().config().routing().peerProtocol().equals(protocol))).isTrue();
                cluster.node("b").net().http().reset();          // no connection carried over from the previous run
                Thread.sleep(500);
                out.add(run(cluster, protocol, concurrency, requests));
            }
            System.out.println("net-bench: forwarded trade views B -> A, " + concurrency + " users at once, " + requests + " requests per run");
            System.out.println("net-bench: protocol | requests | failed | p50 ms | p99 ms | per second | A's connections from B (max)");
            for (Result r : out) {
                System.out.printf("net-bench: %s | %d | %d | %.1f | %.1f | %.0f | %d%n", r.protocol(), r.requests(), r.failed(), r.p50(), r.p99(),
                        r.perSecond(), r.maxConnections());
                assertThat(r.failed()).isZero();
            }
        }
    }

    private static Result run(NetNodes cluster, String protocol, int concurrency, int requests) throws Exception {
        String url = cluster.node("b").url() + "/api/v1/views/trade/IRS-48213";
        String auth = NetForwardTest.bearer("rita", "risk");
        HttpClient users = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        AbstractProtocol<?> aPort = (AbstractProtocol<?>) ((TomcatWebServer) ((ServletWebServerApplicationContext) cluster.node("a").ctx())
                .getWebServer()).getTomcat().getConnector().getProtocolHandler();
        long base = aPort.getConnectionCount();
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicInteger max = new AtomicInteger();
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (sampling.get()) {
                max.accumulateAndGet((int) (aPort.getConnectionCount() - base), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        List<Double> ms = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger next = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().daemon().name("bench-", 0).factory());
        long t0 = System.nanoTime();
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            all.add(pool.submit(() -> {
                while (next.getAndIncrement() < requests) {
                    long s = System.nanoTime();
                    try {
                        HttpResponse<Void> r = users.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", auth)
                                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.discarding());
                        if (r.statusCode() != 200) {
                            failed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                    ms.add((System.nanoTime() - s) / 1e6);
                }
                return null;
            }));
        }
        for (Future<?> f : all) {
            f.get();
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();
        sampling.set(false);
        sampler.join();
        List<Double> sorted = new ArrayList<>(ms);
        Collections.sort(sorted);
        return new Result(protocol, sorted.size(), failed.get(), pct(sorted, 50), pct(sorted, 99), sorted.size() / seconds, max.get());
    }

    private static double pct(List<Double> sorted, int p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
