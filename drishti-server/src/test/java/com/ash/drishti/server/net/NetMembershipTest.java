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
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.security.ClusterKey;
import com.ash.drishti.net.transport.PeerHttp;
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
 * Three real servers in one JVM gossiping over signed HTTP (phase N1, and N3's "a node without the key is refused"): they find
 * each other from one seed, a drain and a leave spread, peers.json is written, a call without the key or with another key is
 * refused and listed, and the files are edited through the API with their version check.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetMembershipTest {

    static NetNodes cluster;
    static Path root;

    @BeforeAll
    static void start() throws Exception {
        root = Path.of("target", "net-it", "membership-" + System.nanoTime()).toAbsolutePath();
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:apac"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0]);
        cluster.start("b", p[1]);
        cluster.start("c", p[2]);
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private static List<String> alive(String node) {
        return cluster.node(node).net().node().map(n -> n.table().alive().stream().map(Member::nodeId).toList()).orElse(List.of());
    }

    @Test
    @Order(1)
    void threeServersFromOneSeedListEachOtherWithinSeconds() throws Exception {
        long t0 = System.currentTimeMillis();
        assertThat(NetNodes.await(10_000, () -> alive("a").size() == 2 && alive("b").size() == 2 && alive("c").size() == 2)).isTrue();
        System.out.println("net: three servers converged in " + (System.currentTimeMillis() - t0) + " ms");
        var r = cluster.call("GET", cluster.node("b").url() + "/api/v1/net/members", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"running\":true").contains("region:apac").doesNotContain(NetNodes.KEY);
        assertThat(NetNodes.await(5_000, () -> Files.exists(root.resolve("b/data/net/peers.json")))).isTrue();
        assertThat(Files.readString(root.resolve("b/data/net/node-id")).trim()).isEqualTo(cluster.node("b").net().nodeId());
    }

    @Test
    @Order(2)
    void aCallWithoutTheKeyOrWithAnotherKeyIsRefusedAndListed() throws Exception {
        String a = cluster.node("a").url();
        var plain = cluster.call("POST", a + "/api/v1/net/gossip", "{\"members\":[]}");
        assertThat(plain.statusCode()).isEqualTo(401);
        assertThat(plain.body()).contains("DRS-5051");
        ClusterKey other = new ClusterKey("someone-elses-cluster-key-32-bytes-long", "", System::currentTimeMillis);
        PeerHttp stranger = new PeerHttp(() -> other, () -> "n-stranger", () -> Duration.ofSeconds(2), u -> Map.of());
        assertThat(stranger.ping(a)).isFalse();
        assertThat(cluster.node("a").net().refusals()).anySatisfy(x -> assertThat(x.reason()).contains("bad signature"));
        // the right key, but a replay of an old signature
        ClusterKey right = new ClusterKey(NetNodes.KEY, "", () -> System.currentTimeMillis() - 120_000);
        PeerHttp late = new PeerHttp(() -> right, () -> "n-late", () -> Duration.ofSeconds(2), u -> Map.of());
        assertThat(late.ping(a)).isFalse();
        assertThat(cluster.node("a").net().refusals()).anySatisfy(x -> assertThat(x.reason()).contains("60 s"));
        PeerHttp member = new PeerHttp(() -> new ClusterKey(NetNodes.KEY, "", System::currentTimeMillis), () -> "n-friend",
                () -> Duration.ofSeconds(2), u -> Map.of());
        assertThat(member.ping(a)).isTrue();
    }

    private static boolean cHasFinanceIn(String node) {
        String cid = cluster.node("c").net().nodeId();
        return cluster.node(node).bean(DigestService.class).table().node(cid).map(n -> n.digest().pack("finance").isPresent()).orElse(false);
    }

    @Test
    @Order(2)
    void aPackSwitchedOnAtOneNodeShowsInEveryNodesTableWithinTwoSeconds() throws Exception {
        assertThat(NetNodes.await(5_000, () -> cHasFinanceIn("a") && cHasFinanceIn("b"))).isTrue();
        var packs = cluster.node("c").bean(com.ash.drishti.server.security.PackAccess.class);
        packs.setEnabled("finance", false, "test");
        long t0 = System.currentTimeMillis();
        assertThat(NetNodes.await(2_000, () -> !cHasFinanceIn("a") && !cHasFinanceIn("b"))).isTrue();
        packs.setEnabled("finance", true, "test");
        t0 = System.currentTimeMillis();
        assertThat(NetNodes.await(2_000, () -> cHasFinanceIn("a") && cHasFinanceIn("b"))).isTrue();
        System.out.println("net: a pack switched on at c was in a's and b's tables " + (System.currentTimeMillis() - t0) + " ms later");
        // the digest names the pack's kinds with their mnemonics and the sources that hold them, and the map shows them
        var d = cluster.node("a").bean(DigestService.class).table().node(cluster.node("c").net().nodeId()).orElseThrow().digest();
        assertThat(d.kind("trade")).hasValueSatisfying(k -> {
            assertThat(k.mnemonic()).isNotBlank();
            assertThat(k.sources()).isNotEmpty();
        });
        var map = cluster.call("GET", cluster.node("a").url() + "/api/v1/net/capabilities", null);
        assertThat(map.statusCode()).isEqualTo(200);
        assertThat(map.body()).contains("\"kind\":\"trade\"").contains("\"renderers\"").contains("\"holders\"");
    }

    @Test
    @Order(3)
    void aDrainSpreadsAndTheFilesAreEditedWithTheirVersion() throws Exception {
        String b = cluster.node("b").url();
        assertThat(cluster.call("POST", b + "/api/v1/net/drain", null).statusCode()).isEqualTo(200);
        String bid = cluster.node("b").net().nodeId();
        assertThat(NetNodes.await(5_000, () -> cluster.node("a").net().node().orElseThrow().table().known(bid)
                .map(k -> k.member().drained()).orElse(false))).isTrue();
        assertThat(cluster.call("POST", b + "/api/v1/net/undrain", null).statusCode()).isEqualTo(200);
        var file = cluster.call("GET", b + "/api/v1/net/file?id=gossip.yaml", null);
        assertThat(file.statusCode()).isEqualTo(200);
        String etag = file.body().replaceAll("(?s).*\"etag\":\"([0-9a-f]+)\".*", "$1");
        var refused = cluster.call("PUT", b + "/api/v1/net/file?id=gossip.yaml", "{\"text\":\"fanout: lots\\n\"}", "If-Match", etag);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("fanout");
        var stale = cluster.call("PUT", b + "/api/v1/net/file?id=gossip.yaml", "{\"text\":\"interval: 300ms\\nsuspect-after: 2s\\ndead-after: 4s\\n\"}",
                "If-Match", "0000000000000000");
        assertThat(stale.statusCode()).isEqualTo(409);
        var clear = cluster.call("PUT", b + "/api/v1/net/file?id=node.yaml", "{\"text\":\"enabled: true\\nsecret: " + NetNodes.KEY + "\\n\"}");
        assertThat(clear.statusCode()).isEqualTo(409);
        assertThat(Files.readString(root.resolve("b/net/node.yaml"))).doesNotContain("secret: a-test");
    }

    @Test
    @Order(4)
    void aServerStoppingLeavesCleanlyAndTheOthersDropItAtOnce() throws Exception {
        String cid = cluster.node("c").net().nodeId();
        cluster.stop("c");
        assertThat(NetNodes.await(3_000, () -> cluster.node("a").net().node().orElseThrow().table().known(cid)
                .map(k -> k.member().state() == MemberState.LEFT).orElse(false))).isTrue();
        assertThat(alive("a")).hasSize(1);
        assertThat(Files.readString(root.resolve("c/data/net/peers.json"))).contains("\"state\" : \"left\"");
    }
}
