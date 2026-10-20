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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * SOAK-03: a node joins Drishti*net only once its web server answers. B starts late and slowly (a bean that takes 4 s, made after the net
 * service, holds its port closed as slow connectors do); A watches the whole start: B must never be routable on A while B's port refuses
 * connections. Before, B gossiped itself alive as soon as its net service was made, 12 to 19 s before its port opened in the soak.
 */
class NetJoinWhenReadyTest {

    static NetNodes cluster;

    /** A slow start-up after the net service exists (connectors loading a lake): the web server starts only after it. */
    public static final class SlowStart {
        public SlowStart(NetService net) throws InterruptedException {
            Thread.sleep(4_000);
        }
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private static boolean open(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(NetNodes.HOST, port), 200);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Test
    void aStartingNodeIsRoutableOnlyOnceItsPortAnswers() throws Exception {
        Path root = Path.of("target", "net-it", "join-ready-" + System.nanoTime()).toAbsolutePath();
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        String demoOff = "--drishti.sources.plugins.demo.enabled=false";
        cluster.start("a", p[0], NetForwardTest.secure(demoOff));
        NetService a = cluster.node("a").net();
        CompletableFuture<NetNodes.Node> b = CompletableFuture.supplyAsync(() -> cluster.start("b", p[1], List.of(SlowStart.class),
                NetForwardTest.secure(demoOff)));
        int routableWhileClosed = 0;
        int routable = 0;
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end && (routable < 10 || !b.isDone())) {
            boolean seen = a.node().orElseThrow().table().others().stream().anyMatch(k -> k.member().url().endsWith(":" + p[1])
                    && k.member().routable());
            if (seen) {
                routable++;
                if (!open(p[1])) {                                   // asked after: a port that opened in between is no violation
                    routableWhileClosed++;
                }
            }
            Thread.sleep(50);
        }
        b.get(30, TimeUnit.SECONDS);
        assertThat(routable).as("B joined").isGreaterThanOrEqualTo(10);
        assertThat(routableWhileClosed).as("polls where A routed to B while B's port was closed").isZero();
    }
}
