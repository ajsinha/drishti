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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * SOAK3-01: a node that has just rejoined never says "no source holds" ({@code 404 DRS-1001}) for an entity only its peers hold. A holds the
 * Delta test lake (trade T-1); C has an empty lake and reads every trade from A. C is stopped and started again, and from the moment its
 * port opens it is asked, as fast as it answers, for T-1's view and history: before it has its peers' digests it answers {@code 502
 * DRS-5052} "still joining" (or holds the answer until the digests are in), never 404. Before the fix C answered 404 DRS-1001 in the half
 * second between opening its port and taking in A's digest.
 */
class NetRejoinMissTest {

    static NetNodes cluster;

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private static boolean open(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(NetNodes.HOST, port), 100);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static boolean hasDigestOf(String node, String peerId) {
        try {
            return cluster.node(node) != null && cluster.node(node).bean(DigestService.class).peers().containsKey(peerId);
        } catch (RuntimeException e) {
            return false;                                                // the context is still starting
        }
    }

    @Test
    void aRejoiningNodeNeverAnswersNotFoundForWhatOnlyAPeerHolds() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "rejoin-miss-" + System.nanoTime()));
        Path lakeA = NetPeerDownTest.copy(root.resolve("lake-a"));
        Path empty = Files.createDirectories(root.resolve("empty-lake"));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:emea"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        String domain = "--drishti.sources.connectors.finance-lake.settings.domain=desk";
        String lake = "--drishti.sources.connectors.finance-lake.settings.root=";
        String[] argsC = NetForwardTest.secure(lake + empty, domain, "--drishti.sources.plugins.demo.enabled=false", NetPeerDownTest.NOCACHE);
        cluster.start("a", p[0], NetForwardTest.secure(lake + lakeA, domain, "--drishti.sources.plugins.demo.enabled=false", NetPeerDownTest.NOCACHE));
        // A has no seeds and no saved members: it is a member at once, never "joining" (a node alone answers as before)
        assertThat(cluster.node("a").bean(DigestService.class).joining()).isFalse();
        assertThat(cluster.node("a").bean(NetHealth.class).summary()).containsEntry("state", "member");
        cluster.start("c", p[2], argsC);
        String aid = cluster.node("a").net().nodeId();
        assertThat(NetNodes.await(20_000, () -> hasDigestOf("c", aid))).as("C has A's digest").isTrue();
        String c = cluster.node("c").url();
        String token = NetForwardTest.bearer("u-risk", "risk");
        HttpResponse<String> first = cluster.call("GET", c + "/api/v1/views/trade/T-1?asOf=2026-09-30", null, "Authorization", token);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);

        // C goes down and comes back: from its first open port it is asked without pause until it has A's digest, and a second more
        String cid = cluster.node("c").net().nodeId();
        cluster.stop("c");
        assertThat(NetNodes.await(15_000, () -> !open(p[2]))).isTrue();
        AtomicBoolean done = new AtomicBoolean();
        Map<String, AtomicInteger> answers = new ConcurrentHashMap<>();
        List<String> notFound = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable asker = () -> {
            while (!done.get()) {
                if (!open(p[2])) {
                    Thread.onSpinWait();
                    continue;
                }
                for (String path : List.of("/api/v1/views/trade/T-1?asOf=2026-09-30", "/api/v1/history/trade/T-1?from=2026-09-28&to=2026-09-30")) {
                    try {
                        HttpResponse<String> r = cluster.call("GET", c + path, null, "Authorization", token);
                        String code = r.statusCode() == 200 ? "200" : r.statusCode() + " " + (r.body().contains("DRS-") ? r.body()
                                .replaceAll("(?s).*?(DRS-\\d{4}).*", "$1") : "?");
                        answers.computeIfAbsent(code, k -> new AtomicInteger()).incrementAndGet();
                        if (r.statusCode() == 404) {
                            notFound.add(path + " -> " + r.body());
                        }
                    } catch (java.io.IOException | InterruptedException e) {
                        answers.computeIfAbsent("io", k -> new AtomicInteger()).incrementAndGet();
                    }
                }
            }
        };
        List<CompletableFuture<Void>> askers = List.of(CompletableFuture.runAsync(asker), CompletableFuture.runAsync(asker),
                CompletableFuture.runAsync(asker));
        cluster.start("c", p[2], argsC);
        assertThat(cluster.node("c").net().nodeId()).isEqualTo(cid);
        assertThat(NetNodes.await(20_000, () -> hasDigestOf("c", aid))).as("C has A's digest again").isTrue();
        Thread.sleep(1_000);
        done.set(true);
        CompletableFuture.allOf(askers.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        System.out.println("SOAK3-01 answers while C rejoined: " + answers);
        assertThat(cluster.node("c").bean(NetHealth.class).summary()).containsEntry("state", "member");
        assertThat(notFound).as("answers while rejoining: " + answers).isEmpty();
        assertThat(answers.getOrDefault("200", new AtomicInteger()).get()).as("answers: " + answers).isPositive();
        answers.keySet().forEach(k -> assertThat(k).as("answers: " + answers).isIn("200", "502 DRS-5052", "io"));
        // once the digests are in, C answers from A
        assertThat(cluster.call("GET", c + "/api/v1/views/trade/T-1?asOf=2026-09-30", null, "Authorization", token).statusCode()).isEqualTo(200);
    }
}
