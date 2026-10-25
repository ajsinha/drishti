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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A view's linked entities held on a peer (DRISHTI_NET.md section 7) on three real servers in one JVM: A holds the finance pack's data,
 * B has the pack and no data, C has no data either and a local link budget of 3 ms (a call to a peer takes longer). A trade opened on B or C shows every linked entity as
 * itself (resolved, with its badge), exactly as on A, not as pending: the peers' answers wait routing.yaml's link-budget, not the 40 ms
 * of this node's own sources. A user still cannot tell which server answered.
 */
class NetLinksTest {

    static final String TOKEN_SECRET = "a-test-token-secret-at-least-32-bytes!!";
    static final ObjectMapper JSON = new ObjectMapper();
    static NetNodes cluster;

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "links-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of(), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0], NetForwardTest.secure());
        cluster.start("b", p[1], NetForwardTest.secure("--drishti.sources.plugins.demo.enabled=false"));
        cluster.start("c", p[2], NetForwardTest.secure("--drishti.sources.plugins.demo.enabled=false", "--drishti.graph.link-budget=3ms"));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 3)))
                .as("every node has every digest").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static String bearer(String user, String... roles) {
        return NetForwardTest.bearer(user, roles);
    }

    static JsonNode view(String node, String user, String... roles) throws Exception {
        var r = cluster.call("GET", cluster.node(node).url() + "/api/v1/views/trade/IRS-48213", null, "Authorization", bearer(user, roles));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().map().keySet()).noneMatch(h -> h.toLowerCase().startsWith("x-drishti-net"));
        return JSON.readTree(r.body());
    }

    /** Every linked entity of the view's link panels: its text to its status and badge. */
    static Map<String, String> links(JsonNode view) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode p : view.path("panels")) {
            for (JsonNode l : p.path("data").path("links")) {
                out.put(l.path("text").asText(), l.path("status").asText() + " " + l.path("badge").asText());
            }
        }
        return out;
    }

    @Test
    void linkedEntitiesHeldOnAPeerShowAsThemselvesNotPending() throws Exception {
        Map<String, String> onA = links(view("a", "rita", "risk"));
        assertThat(onA).as("the trade links to entities A holds").isNotEmpty();
        assertThat(onA.values()).allMatch(s -> s.startsWith("resolved"));
        for (String node : List.of("b", "c")) {
            Map<String, String> there = links(view(node, "rita", "risk"));
            assertThat(there).as("on " + node + ": every link as on A, badge and all").isEqualTo(onA);
            assertThat(there.values()).noneMatch(s -> s.startsWith("pending"));
        }
        // a user cannot tell which server answered; an administrator can
        assertThat(NetForwardTest.nodeLine(view("b", "rita", "risk").toString())).isNull();
        assertThat(NetForwardTest.nodeLine(view("b", "ada", "admin").toString())).contains("data from " + cluster.node("a").net().nodeId());
    }

    @Test
    void aReadThatGoesToThePeersWaitsThePeerBudgetAndAForwardedOneTheLocalOne() {
        var rita = new com.ash.drishti.server.security.Principal("rita", List.of("risk"));
        var local = java.time.Duration.ofMillis(40);
        var trade = com.ash.drishti.api.EntityRef.of("trade", "IRS-48213");
        var latest = com.ash.drishti.api.AsOf.LATEST;
        PeerSources peers = cluster.node("b").bean(PeerSources.class);
        java.util.List<java.time.Duration> got = new java.util.ArrayList<>();
        NetCaller.with(new NetCaller.Caller(rita, null, "r-links"), () -> got.add(peers.budget(trade, latest, local)));
        NetCaller.with(new NetCaller.Caller(rita, cluster.node("a").net().nodeId(), "r-hop"), () -> got.add(peers.budget(trade, latest, local)));
        java.time.Duration peer = cluster.node("b").net().config().routing().linkBudget();
        assertThat(peer).isEqualTo(java.time.Duration.ofSeconds(1));
        assertThat(got).containsExactly(peer, local);
    }
}
