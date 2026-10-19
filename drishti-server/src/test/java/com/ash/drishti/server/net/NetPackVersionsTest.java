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

import com.ash.drishti.net.security.NetHeaders;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.net.transport.PeerHttp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase N4: the newest version of a pack is served (A has the finance pack at 9.9.9, B at its shipped version: B's users get A's
 * pages), and a pack a node serves to its peers only is invisible to that node's own users (C) yet answers its peers.
 */
class NetPackVersionsTest {

    static NetNodes cluster;

    @BeforeAll
    static void start() throws Exception {
        Path root = Path.of("target", "net-it", "packs-" + System.nanoTime()).toAbsolutePath();
        Path newer = root.resolve("packs-a");
        Path from = Path.of("..", "examples", "packs", "finance").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path to = newer.resolve("finance").resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        Path manifest = newer.resolve("finance/pack.yaml");
        Files.writeString(manifest, Files.readString(manifest).replaceFirst("(?m)^version: .*$", "version: 9.9.9"));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of(), List.of("finance"), List.of(p[0]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0], "--drishti.packs.extra-dirs=" + newer);
        cluster.start("b", p[1]);
        cluster.start("c", p[2]);
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 3)))
                .isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static long views(String node, String to) {
        String id = cluster.node(to).net().nodeId();
        return cluster.node(node).bean(ForwardLog.class).recent().stream().filter(c -> c.what().equals("view") && c.peer().equals(id) && c.outcome().equals("ok")).count();
    }

    @Test
    void theNewestVersionIsServedAndAPeerOnlyPackIsServedToPeersOnly() throws Exception {
        // B has finance too, older: its users get A's pages
        assertThat(cluster.node("b").bean(NetRouting.class).view("trade", com.ash.drishti.server.security.Principal.anonymous("x"), false, "IRS-48213").reason())
                .contains("9.9.9");
        var onB = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/trade/IRS-48213", null);
        assertThat(onB.statusCode()).as(onB.body()).isEqualTo(200);
        assertThat(views("b", "a")).isEqualTo(1);
        var map = cluster.call("GET", cluster.node("b").url() + "/api/v1/net/capabilities", null).body();
        assertThat(map).contains("\"version\":\"9.9.9\",\"newest\":true");

        // C serves finance to its peers only: its own users do not see the pack, its kinds or its entities
        assertThat(cluster.call("GET", cluster.node("c").url() + "/api/v1/packs", null).body()).doesNotContain("\"name\":\"finance\"");
        assertThat(cluster.call("GET", cluster.node("c").url() + "/api/v1/command/suggest?q=IRS-482", null).body()).doesNotContain("IRS-48213");
        var onC = cluster.call("GET", cluster.node("c").url() + "/api/v1/views/trade/IRS-48213", null);
        assertThat(onC.statusCode()).as(onC.body()).isEqualTo(200);
        assertThat(views("c", "a")).as("C's own user is answered by the node with the newest finance").isEqualTo(1);
        // ... but a peer asking C is answered by C
        var a = cluster.node("a").net();
        String cid = cluster.node("c").net().nodeId();
        String obo = OnBehalfOf.mint(a.key(), "x", List.of("*"), null, a.nodeId(), "r-peer", cid);
        PeerHttp.Answer ans = a.http().send(cluster.node("c").url(), "GET", "/api/v1/views/trade/IRS-48213",
                Map.of(NetHeaders.ON_BEHALF_OF, obo, NetHeaders.HOP, "1"), null, Duration.ofSeconds(10));
        assertThat(ans.status()).isEqualTo(200);
        assertThat(ans.text()).contains("IRS-48213");
        // the digest marks the pack peer-only, and the map shows it
        assertThat(cluster.node("a").bean(DigestService.class).table().node(cid).orElseThrow().digest().pack("finance").orElseThrow().peerOnly()).isTrue();
    }
}
