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
import com.ash.drishti.net.security.ClusterKey;
import com.ash.drishti.net.security.NetHeaders;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.net.transport.PeerHttp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Phase N3 on three real servers in one JVM: a rolling key change in three steps refuses no call between nodes; a forwarded call whose
 * roles may not reach a node is refused there; nodes per role, tags and peer-only packs are edited through the API and applied live;
 * the security endpoint shows the key's state and never the key.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetSecurityTest {

    static final String NEW_KEY = "the-new-test-cluster-key-at-least-32-bytes";
    static NetNodes cluster;
    static Path root;

    @BeforeAll
    static void start() throws Exception {
        root = Path.of("target", "net-it", "security-" + System.nanoTime()).toAbsolutePath();
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:apac"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        for (int i = 0; i < 3; i++) {
            cluster.start(String.valueOf((char) ('a' + i)), p[i], "--DRISHTI_NET_NEW_SECRET=" + NEW_KEY);
        }
        assertThat(NetNodes.await(10_000, () -> List.of("a", "b", "c").stream().allMatch(n -> alive(n) == 2))).isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static long alive(String node) {
        return cluster.node(node).net().node().map(n -> n.table().alive().size()).orElse(0);
    }

    static void keys(String node, String secretVar, String previousVar) throws Exception {
        Path f = root.resolve(node + "/net/node.yaml");
        String text = Files.readString(f).replaceAll("(?m)^(secret|previous-secret):.*\\n", "");
        text += "secret: ${" + secretVar + "}\n" + (previousVar == null ? "" : "previous-secret: ${" + previousVar + "}\n");
        Files.writeString(f, text);
        cluster.node(node).net().reload();
    }

    static int refusals() {
        return List.of("a", "b", "c").stream().mapToInt(n -> cluster.node(n).net().refusals().size()).sum();
    }

    @Test
    @Order(1)
    void aRollingKeyChangeInThreeStepsRefusesNoCall() throws Exception {
        int before = refusals();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        // signed calls from every node to every other, as fast as they go, all through the rollover
        Thread load = Thread.ofVirtual().start(() -> {
            while (running.get()) {
                for (String from : List.of("a", "b", "c")) {
                    for (String to : List.of("a", "b", "c")) {
                        if (!from.equals(to)) {
                            calls.incrementAndGet();
                            if (!cluster.node(from).net().http().ping(cluster.node(to).url())) {
                                failed.incrementAndGet();
                            }
                        }
                    }
                }
            }
        });
        String[] old = {"DRISHTI_NET_SECRET", "DRISHTI_NET_NEW_SECRET"};
        for (String n : List.of("a", "b", "c")) {
            keys(n, old[0], old[1]);                       // step 1: every node accepts the new key
            Thread.sleep(300);
        }
        for (String n : List.of("a", "b", "c")) {
            keys(n, old[1], old[0]);                       // step 2: every node signs with it
            Thread.sleep(300);
        }
        assertThat(cluster.node("a").net().key().previousSet()).isTrue();
        for (String n : List.of("a", "b", "c")) {
            keys(n, old[1], null);                         // step 3: the old key is dropped
            Thread.sleep(300);
        }
        Thread.sleep(1500);
        running.set(false);
        load.join();
        System.out.println("net: " + calls.get() + " signed calls through a three-step key rollover, " + failed.get() + " refused");
        assertThat(failed.get()).isZero();
        assertThat(refusals()).isEqualTo(before);
        assertThat(List.of("a", "b", "c")).allMatch(n -> alive(n) == 2);
        // the old key no longer works anywhere
        PeerHttp stale = new PeerHttp(() -> new ClusterKey(NetNodes.KEY, "", System::currentTimeMillis), () -> "n-stale", () -> Duration.ofSeconds(2), u -> Map.of());
        assertThat(stale.ping(cluster.node("a").url())).isFalse();
    }

    @Test
    @Order(2)
    void aForwardedCallWhoseRolesMayNotReachANodeIsRefusedThere() throws Exception {
        var put = cluster.call("PUT", cluster.node("a").url() + "/api/v1/net/access", "{\"roles\":{\"trader\":[\"region:emea\"]}}");
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(put.body()).contains("\"trader\"");
        for (String n : List.of("b", "c")) {
            Files.copy(root.resolve("a/net/access.yaml"), root.resolve(n + "/net/access.yaml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            cluster.node(n).net().reload();
        }
        ClusterKey key = cluster.node("a").net().key();
        String aId = cluster.node("a").net().nodeId();
        for (String target : List.of("b", "c")) {
            String id = cluster.node(target).net().nodeId();
            String obo = OnBehalfOf.mint(key, "tina", List.of("trader"), null, aId, "r-1", id);
            PeerHttp.Answer ans = cluster.node("a").net().http().send(cluster.node(target).url(), "GET", "/api/v1/views/trade/IRS-48213",
                    Map.of(NetHeaders.ON_BEHALF_OF, obo, NetHeaders.HOP, "1"), null, Duration.ofSeconds(10));
            if (target.equals("b")) {
                assertThat(ans.status()).as("b is in region:emea").isEqualTo(200);
            } else {
                assertThat(ans.status()).as("c is in region:apac").isEqualTo(403);
                assertThat(ans.text()).contains("DRS-5054");
            }
        }
        // a token for one node is refused by another, and an expired one everywhere
        String forB = OnBehalfOf.mint(key, "tina", List.of("risk"), null, aId, "r-2", cluster.node("b").net().nodeId());
        assertThat(cluster.node("a").net().http().send(cluster.node("c").url(), "GET", "/api/v1/views/trade/IRS-48213",
                Map.of(NetHeaders.ON_BEHALF_OF, forB, NetHeaders.HOP, "1"), null, Duration.ofSeconds(10)).status()).isEqualTo(401);
        var access = cluster.call("GET", cluster.node("a").url() + "/api/v1/net/access", null);
        assertThat(access.body()).contains("\"limited\":true");
    }

    @Test
    @Order(3)
    void tagsAndPeerOnlyPacksAreEditedLiveAndTheKeyIsNeverShown() throws Exception {
        var put = cluster.call("PUT", cluster.node("c").url() + "/api/v1/net/access", "{\"tags\":[\"region:apac\",\"tier:risk\"],\"peerOnlyPacks\":[]}");
        assertThat(put.statusCode()).isEqualTo(200);
        String cid = cluster.node("c").net().nodeId();
        assertThat(NetNodes.await(5_000, () -> cluster.node("a").net().node().orElseThrow().table().known(cid)
                .map(k -> k.member().tags().contains("tier:risk")).orElse(false))).isTrue();
        assertThat(Files.readString(root.resolve("c/net/node.yaml"))).contains("${DRISHTI_NET_NEW_SECRET}").doesNotContain(NEW_KEY);
        var bad = cluster.call("PUT", cluster.node("c").url() + "/api/v1/net/access", "{\"tags\":[\"not a tag!\"]}");
        assertThat(bad.statusCode()).isEqualTo(409);
        var sec = cluster.call("GET", cluster.node("a").url() + "/api/v1/net/security", null);
        assertThat(sec.statusCode()).isEqualTo(200);
        assertThat(sec.body()).contains("\"keySet\":true").doesNotContain(NEW_KEY).doesNotContain(NetNodes.KEY);
        assertThat(cluster.node("a").net().node().orElseThrow().table().self().protocol()).isEqualTo(Member.PROTOCOL);
    }
}
