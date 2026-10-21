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

import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.member.MemberTable;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Gossip off the busy path (DRISHTI_NET.md section 5, 2.6.7), on three real servers in one JVM whose request handling is saturated: each
 * runs a platform request pool of six threads (virtual threads off, as an operator may run it) and a test hook that holds any request to
 * {@code /api/v1/test/slow} for two seconds. Forty slow requests per node are kept in flight for twelve seconds: users' requests from
 * outside, and B's own forwarded reads to A and C over its data lane (HTTP/2). The server's port answers nothing in that window.
 * <ul>
 * <li>With the gossip port (the default): no node marks another <i>suspect</i>, and every heartbeat keeps rising.</li>
 * <li>With {@code gossip-port: off} (gossip on the server's port, as before 2.6.7) the same load makes healthy nodes suspect: the failure
 * the change removes.</li>
 * <li>A node that truly dies is still suspected and declared dead within the configured times (suspect-after 3 s, dead-after 4 s).</li>
 * </ul>
 */
class NetGossipBusyTest {

    /** Holds a request to /api/v1/test/slow for {@code ms} (default 2,000): a data handler that blocks its request thread. */
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public static class SlowDataHook implements Filter {
        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
            if (req instanceof HttpServletRequest h && h.getRequestURI().equals("/api/v1/test/slow")) {
                String ms = h.getParameter("ms");
                try {
                    Thread.sleep(ms == null ? 2_000 : Long.parseLong(ms));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                res.setContentType("text/plain");
                res.getWriter().write("slow");
                return;
            }
            chain.doFilter(req, res);
        }
    }

    static final String[] BUSY = {"--spring.threads.virtual.enabled=false", "--server.tomcat.threads.max=6", "--server.tomcat.threads.min-spare=2"};
    static final List<String> NAMES = List.of("a", "b", "c");

    private static NetNodes cluster(String label, boolean gossipPort) throws Exception {
        Path root = Path.of("target", "net-it", "busy-" + label + "-" + System.nanoTime()).toAbsolutePath();
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:emea"), List.of(), List.of(p[0]));
        if (!gossipPort) {
            for (String n : NAMES) {
                Files.writeString(root.resolve(n + "/net/node.yaml"), "gossip-port: off\n", java.nio.file.StandardOpenOption.APPEND);
            }
        }
        NetNodes c = new NetNodes(root);
        try {
            for (int i = 0; i < 3; i++) {
                c.start(NAMES.get(i), p[i], List.of(SlowDataHook.class), BUSY);
            }
            assertThat(NetNodes.await(20_000, () -> NAMES.stream().allMatch(n -> c.node(n).net().node().map(x -> x.table().alive().size() == 2)
                    .orElse(false)))).as("every node sees its two peers alive").isTrue();
            return c;
        } catch (Exception | AssertionError e) {
            c.close();
            throw e;
        }
    }

    /** What the window saw: suspicions (observer -> suspected ids) and each observer's view of each peer's heartbeat, first and last. */
    record Seen(Map<String, Set<String>> suspected, Map<String, Long> beatsFirst, Map<String, Long> beatsLast, boolean serverPortBlocked) {}

    /** Keeps forty slow requests per node in flight for {@code seconds}, watching the member tables every 100 ms. */
    private static Seen saturate(NetNodes cluster, int seconds) throws Exception {
        AtomicBoolean on = new AtomicBoolean(true);
        ExecutorService load = Executors.newFixedThreadPool(120, Thread.ofPlatform().daemon().name("busy-load-", 0).factory());
        HttpClient outside = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        NetService b = cluster.node("b").net();
        AtomicLong sent = new AtomicLong();
        for (String n : NAMES) {
            for (int i = 0; i < 40; i++) {
                boolean forwarded = !n.equals("b") && i % 2 == 0;          // half of A's and C's load is B's forwarded reads (its data lane)
                String url = cluster.node(n).url();
                load.submit(() -> {
                    while (on.get()) {
                        try {
                            if (forwarded) {
                                b.http().send(url, "GET", "/api/v1/test/slow", Map.of(), null, Duration.ofSeconds(60));
                            } else {
                                outside.send(HttpRequest.newBuilder(URI.create(url + "/api/v1/test/slow")).timeout(Duration.ofSeconds(60)).build(),
                                        HttpResponse.BodyHandlers.discarding());
                            }
                            sent.incrementAndGet();
                        } catch (Exception e) {
                            // the load goes on
                        }
                    }
                    return null;
                });
            }
        }
        Thread.sleep(1_500);                                                // the pools are full
        // the server's port answers nothing now: a plain read waits behind the slow ones
        boolean blocked;
        try {
            outside.send(HttpRequest.newBuilder(URI.create(cluster.node("b").url() + "/actuator/health")).timeout(Duration.ofSeconds(1)).build(),
                    HttpResponse.BodyHandlers.discarding());
            blocked = false;
        } catch (java.net.http.HttpTimeoutException e) {
            blocked = true;
        }
        Map<String, Set<String>> suspected = new ConcurrentHashMap<>();
        Map<String, Long> first = beats(cluster);
        long end = System.currentTimeMillis() + seconds * 1_000L;
        while (System.currentTimeMillis() < end) {
            for (String o : NAMES) {
                for (MemberTable.Known k : cluster.node(o).net().node().orElseThrow().table().others()) {
                    if (k.member().state() == MemberState.SUSPECT || k.member().state() == MemberState.DEAD) {
                        suspected.computeIfAbsent(o, x -> ConcurrentHashMap.newKeySet()).add(k.member().nodeId());
                    }
                }
            }
            Thread.sleep(100);
        }
        Map<String, Long> last = beats(cluster);
        on.set(false);
        load.shutdownNow();
        load.awaitTermination(10, TimeUnit.SECONDS);
        return new Seen(suspected, first, last, blocked);
    }

    /** "observer>peer" -> the heartbeat the observer last heard from the peer. */
    private static Map<String, Long> beats(NetNodes cluster) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String o : NAMES) {
            for (MemberTable.Known k : cluster.node(o).net().node().orElseThrow().table().others()) {
                out.put(o + ">" + k.member().nodeId(), k.member().heartbeat());
            }
        }
        return out;
    }

    @Test
    void aSaturatedServerIsNeverSuspectedWhileGossipFlowsAndADeadOneStillIs() throws Exception {
        try (NetNodes cluster = cluster("port", true)) {
            Seen seen = saturate(cluster, 12);
            assertThat(seen.serverPortBlocked()).as("the server's port answers nothing while the load runs").isTrue();
            assertThat(seen.suspected()).as("no node suspected another while its request handling was saturated").isEmpty();
            for (var e : seen.beatsLast().entrySet()) {
                assertThat(e.getValue() - seen.beatsFirst().getOrDefault(e.getKey(), 0L)).as("heartbeats rose for " + e.getKey())
                        .isGreaterThanOrEqualTo(6);
            }
            // a node that dies is still found: C stops gossiping and both its ports close, with no word to anyone
            NetNodes.Node c = cluster.node("c");
            String cid = c.net().nodeId();
            long t0 = System.currentTimeMillis();
            c.net().halt();
            c.bean(GossipConnector.class).close();
            cluster.closePort("c");
            AtomicLong suspectAt = new AtomicLong();
            assertThat(NetNodes.await(15_000, () -> {
                boolean all = List.of("a", "b").stream().allMatch(o -> cluster.node(o).net().node().orElseThrow().table().known(cid)
                        .map(k -> k.member().state() != MemberState.ALIVE).orElse(false));
                if (all) {
                    suspectAt.compareAndSet(0, System.currentTimeMillis() - t0);
                }
                return List.of("a", "b").stream().allMatch(o -> cluster.node(o).net().node().orElseThrow().table().known(cid)
                        .map(k -> k.member().state() == MemberState.DEAD).orElse(false));
            })).as("C is dead everywhere").isTrue();
            long deadAt = System.currentTimeMillis() - t0;
            System.out.println("net-busy: C suspected everywhere after " + suspectAt.get() + " ms, dead after " + deadAt + " ms");
            assertThat(suspectAt.get()).as("suspected within suspect-after (3 s) plus two rounds and a probe").isLessThanOrEqualTo(7_000);
            assertThat(deadAt).as("dead within suspect-after + dead-after (7 s) plus two rounds and a probe").isLessThanOrEqualTo(11_000);
        }
    }

    @Test
    void withGossipOnTheServersPortTheSameLoadMakesHealthyNodesSuspect() throws Exception {
        try (NetNodes cluster = cluster("noport", false)) {
            for (String n : NAMES) {
                assertThat(cluster.node(n).net().node().orElseThrow().table().self().gossipPort()).isZero();
            }
            Seen seen = saturate(cluster, 12);
            assertThat(seen.serverPortBlocked()).isTrue();
            System.out.println("net-busy (gossip-port off): suspicions " + seen.suspected());
            assertThat(seen.suspected()).as("gossip waiting behind the data requests on the server's port: false suspicions").isNotEmpty();
            // they come back once the load is gone
            assertThat(NetNodes.await(15_000, () -> NAMES.stream().allMatch(n -> cluster.node(n).net().node().orElseThrow().table().alive().size() == 2)))
                    .isTrue();
        }
    }
}
