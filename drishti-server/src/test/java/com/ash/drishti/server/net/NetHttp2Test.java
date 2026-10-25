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

import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.security.NetHeaders;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.net.transport.PeerHttp;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The transport between nodes (DRISHTI_NET.md section 5 and "HTTP/2 for peer data", 2.6.7), on three real servers in one JVM. A holds the
 * demo trades; B forwards to it over HTTP/2 (routing.yaml's default); C runs with {@code server.http2.enabled=false}, a server of before
 * 2.6.7 for HTTP, and with {@code peer-protocol: http1}. Each node has its gossip port.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetHttp2Test {

    static NetNodes cluster;

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "http2-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:emea"), List.of(), List.of(p[0]));
        Files.writeString(root.resolve("c/net/routing.yaml"), "peer-protocol: http1\n");
        cluster = new NetNodes(root);
        cluster.start("a", p[0], NetForwardTest.secure());
        cluster.start("b", p[1], NetForwardTest.secure("--drishti.sources.plugins.demo.enabled=false"));
        cluster.start("c", p[2], NetForwardTest.secure("--drishti.sources.plugins.demo.enabled=false", "--server.http2.enabled=false"));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table()
                .nodes().size() == 3))).as("every node has every digest").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private static PeerHttp.Answer view(String from, String to, String id) throws Exception {
        NetService f = cluster.node(from).net();
        String toId = cluster.node(to).net().nodeId();
        String obo = OnBehalfOf.mint(f.key(), "rita", List.of("risk"), null, f.nodeId(), "r-h2", toId);
        return f.http().send(cluster.node(to).url(), "GET", "/api/v1/views/trade/" + id, Map.of(NetHeaders.ON_BEHALF_OF, obo, NetHeaders.HOP, "1"),
                null, Duration.ofSeconds(20));
    }

    @Test
    @Order(1)
    void twoNodesNegotiateHttp2ForData() throws Exception {
        PeerHttp.Answer first = view("b", "a", "IRS-48213");
        assertThat(first.status()).as(first.text()).isEqualTo(200);
        assertThat(first.version()).as("h2c by the upgrade on the first call").isEqualTo(HttpClient.Version.HTTP_2);
        for (int i = 0; i < 5; i++) {
            PeerHttp.Answer again = view("b", "a", "IRS-48213");
            assertThat(again.status()).isEqualTo(200);
            assertThat(again.version()).isEqualTo(HttpClient.Version.HTTP_2);
        }
        // a user's view on B, forwarded to A, answers as before
        var onB = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization",
                NetForwardTest.bearer("rita", "risk"));
        assertThat(onB.statusCode()).as(onB.body()).isEqualTo(200);
        assertThat(onB.body()).contains("IRS-48213");
    }

    @Test
    @Order(2)
    void aPeerThatDoesNotSpeakHttp2IsAnsweredInHttp1() throws Exception {
        // C's server is HTTP/1.1 only (a node of before 2.6.7): B asks for HTTP/2 and is answered in HTTP/1.1 on the same call
        PeerHttp.Answer toC = view("b", "c", "IRS-48213");
        assertThat(toC.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(toC.status()).as("C holds no trade; it answers, in HTTP/1.1").isIn(200, 404, 502);
        // C sends HTTP/1.1 (peer-protocol: http1) to A, which speaks both
        PeerHttp.Answer fromC = view("c", "a", "IRS-48213");
        assertThat(fromC.status()).as(fromC.text()).isEqualTo(200);
        assertThat(fromC.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        var onC = cluster.call("GET", cluster.node("c").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization",
                NetForwardTest.bearer("rita", "risk"));
        assertThat(onC.statusCode()).as(onC.body()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void everyNodeGossipsOnItsOwnPortAndThatPortServesNothingElse() throws Exception {
        for (String n : List.of("a", "b", "c")) {
            Member self = cluster.node(n).net().node().orElseThrow().table().self();
            assertThat(self.gossipPort()).as(n + " has a gossip port").isPositive().isNotEqualTo(cluster.node(n).port());
            assertThat(self.gossipUrl()).startsWith("http://" + NetNodes.HOST + ":");
            // every peer has heard of it
            for (String o : List.of("a", "b", "c").stream().filter(o -> !o.equals(n)).toList()) {
                assertThat(cluster.node(o).net().node().orElseThrow().table().known(self.nodeId()).map(k -> k.member().gossipPort()).orElse(0))
                        .as(o + " knows " + n + "'s gossip port").isEqualTo(self.gossipPort());
            }
        }
        Member a = cluster.node("a").net().node().orElseThrow().table().self();
        // the gossip port answers a signed probe, and nothing else
        PeerHttp.Answer ping = cluster.node("b").net().http().control(a.gossipUrl(), "GET", "/api/v1/net/ping", Map.of(), null, Duration.ofSeconds(2));
        assertThat(ping.status()).as(ping.text()).isEqualTo(200);
        var other = cluster.call("GET", a.gossipUrl() + "/api/v1/views/trade/IRS-48213", null, "Authorization", NetForwardTest.bearer("rita", "risk"));
        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(other.body()).contains("gossip");
        var unsigned = cluster.call("GET", a.gossipUrl() + "/api/v1/net/ping", null);
        assertThat(unsigned.statusCode()).isEqualTo(401);
        // the server's port still answers gossip (a node of before 2.6.7, a seed), on the same priority path
        PeerHttp.Answer onServerPort = cluster.node("b").net().http().send(cluster.node("a").url(), "GET", "/api/v1/net/ping", Map.of(), null,
                Duration.ofSeconds(2));
        assertThat(onServerPort.status()).isEqualTo(200);
    }
}
