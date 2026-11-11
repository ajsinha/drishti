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
import com.ash.drishti.server.security.TokenVerifier;
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
 * Phase N4 on three real servers in one JVM, sign-in on: A holds the finance pack's data (the demo source) and masks little; B has the
 * pack but no data and masks the trader's name; C has no finance pack at all. B opens a trade only A holds (data forwarding) and a
 * field B masks stays masked although A sent it in clear; C opens a trade only A's and B's pack renders (view forwarding) with the same
 * page; a forwarded request is never forwarded again; with A gone, B and C say so clearly.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetForwardTest {

    static final String TOKEN_SECRET = "a-test-token-secret-at-least-32-bytes!!";
    static NetNodes cluster;

    static String[] secure(String... more) {
        List<String> a = new java.util.ArrayList<>(List.of("--drishti.security.enabled=true", "--drishti.security.secret=" + TOKEN_SECRET,
                "--drishti.security.registered-users-only=false"));
        a.addAll(List.of(more));
        return a.toArray(String[]::new);
    }

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "forward-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:emea"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0], secure("--drishti.security.redact=patientName"));
        cluster.start("b", p[1], secure("--drishti.sources.plugins.demo.enabled=false"));
        cluster.start("c", p[2], secure("--drishti.packs.enabled=logistics", "--drishti.sources.plugins.demo.enabled=false"));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 3)))
                .as("every node has every digest").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static final TokenVerifier TOKENS = new TokenVerifier(TOKEN_SECRET, 30);

    static String bearer(String user, String... roles) {
        return "Bearer " + TOKENS.mint(user, List.of(roles), 300);
    }

    static long forwarded(String node, String what) {
        return cluster.node(node).bean(ForwardLog.class).recent().stream().filter(c -> c.what().equals(what) && c.outcome().equals("ok")).count();
    }

    @Test
    @Order(1)
    void bOpensATradeOnlyAHoldsAndTheSamePageComesBack() throws Exception {
        var onA = cluster.call("GET", cluster.node("a").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization", bearer("rita", "risk"));
        var onB = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization", bearer("rita", "risk"));
        assertThat(onA.statusCode()).isEqualTo(200);
        assertThat(onB.statusCode()).as(onB.body()).isEqualTo(200);
        assertThat(onB.body()).contains("IRS-48213");
        assertThat(forwarded("b", "data")).isPositive();
        assertThat(onB.headers().map().keySet()).noneMatch(h -> h.toLowerCase().startsWith("x-drishti-net"));   // nothing says which node held it
        // the same panels on both: B renders A's document with the same pack
        assertThat(panels(onB.body())).isEqualTo(panels(onA.body()));
    }

    static List<String> panels(String json) throws Exception {
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        List<String> ids = new java.util.ArrayList<>();
        tree.path("panels").forEach(p -> ids.add(p.path("id").asText()));
        return ids;
    }

    /** The "Node" line of the view's "How this view was built" panel, or null when it has none. */
    static String nodeLine(String json) throws Exception {
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        for (var p : tree.path("panels")) {
            if (p.path("kind").asText().equals("provenance")) {
                for (var f : p.path("data").path("fields")) {
                    if (f.path("label").asText().equals(NetServedBy.LABEL)) {
                        return f.path("text").asText();
                    }
                }
            }
        }
        return null;
    }

    @Test
    @Order(2)
    void aFieldBMasksStaysMaskedThoughAMasksLess() throws Exception {
        // A does not mask the trader's name for anyone; B does for roles without raw: B masks it again on what A sent
        var aTina = cluster.call("GET", cluster.node("a").url() + "/api/v1/entities/trade/FXS-20931/raw", null, "Authorization", bearer("tina", "trader"));
        assertThat(aTina.body()).contains("L. Moreau");
        var bTina = cluster.call("GET", cluster.node("b").url() + "/api/v1/entities/trade/FXS-20931/raw", null, "Authorization", bearer("tina", "trader"));
        assertThat(bTina.statusCode()).as(bTina.body()).isEqualTo(200);
        assertThat(bTina.body()).contains("•••").doesNotContain("L. Moreau");
        var bRita = cluster.call("GET", cluster.node("b").url() + "/api/v1/entities/trade/FXS-20931/raw", null, "Authorization", bearer("rita", "risk"));
        assertThat(bRita.body()).contains("L. Moreau");
    }

    @Test
    @Order(3)
    void cOpensAKindOnlyItsPeersRenderWithNoVisibleDifference() throws Exception {
        long before = forwarded("c", "view");
        var onC = cluster.call("GET", cluster.node("c").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization", bearer("rita", "risk"));
        assertThat(onC.statusCode()).as(onC.body()).isEqualTo(200);
        var onA = cluster.call("GET", cluster.node("a").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization", bearer("rita", "risk"));
        assertThat(panels(onC.body())).isEqualTo(panels(onA.body()));
        assertThat(forwarded("c", "view")).isEqualTo(before + 1);
        // "How this view was built" names the node that answered for an administrator only: forwarded from C; local on A
        assertThat(nodeLine(onC.body())).as("a user cannot tell which server answered").isNull();
        String cId = cluster.node("c").net().nodeId();
        var adminOnC = cluster.call("GET", cluster.node("c").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization", bearer("ada", "admin"));
        assertThat(nodeLine(adminOnC.body())).as(adminOnC.body()).endsWith(" · forwarded from " + cId).doesNotStartWith(cId);
        var adminOnA = cluster.call("GET", cluster.node("a").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization", bearer("ada", "admin"));
        assertThat(nodeLine(adminOnA.body())).isEqualTo(cluster.node("a").net().nodeId() + " · local");
        // a search and the raw document of the kind go there too
        var search = cluster.call("GET", cluster.node("c").url() + "/api/v1/search?q=" + java.net.URLEncoder.encode("TRD IRS-48213", "UTF-8"), null,
                "Authorization", bearer("rita", "risk"));
        assertThat(search.statusCode()).as(search.body()).isEqualTo(200);
        assertThat(search.body()).contains("IRS-48213");
        // Explain says where it went, and the traffic lists it
        String admin = bearer("ada", "admin");
        var explain = cluster.call("GET", cluster.node("c").url() + "/api/v1/net/explain?kind=trade&id=IRS-48213&role=risk", null, "Authorization", admin);
        assertThat(explain.statusCode()).as(explain.body()).isEqualTo(200);
        String aId = cluster.node("a").net().nodeId();
        assertThat(explain.body()).contains("\"route\":\"view\"").contains("\"forwardTo\":[\"" + aId + "\"");
        var onB = cluster.call("GET", cluster.node("b").url() + "/api/v1/net/explain?kind=trade&id=IRS-48213&role=risk", null, "Authorization", admin);
        assertThat(onB.body()).contains("\"route\":\"local\"").contains("\"dataPeers\":[\"" + aId + "\"]");
        var traffic = cluster.call("GET", cluster.node("c").url() + "/api/v1/net/traffic", null, "Authorization", admin);
        assertThat(traffic.body()).contains("\"what\":\"view\"").contains("\"outcome\":\"ok\"");
        var health = cluster.call("GET", cluster.node("c").url() + "/api/v1/admin/health", null, "Authorization", admin);
        assertThat(health.body()).contains("\"net\":{\"enabled\":true,\"running\":true");
        // a role that may not open trades here is refused here, before any call
        var refused = cluster.call("GET", cluster.node("c").url() + "/api/v1/views/netting-set/NS-HARB-02", null, "Authorization", bearer("tina", "trader"));
        assertThat(refused.statusCode()).isEqualTo(403);
    }

    @Test
    @Order(4)
    void aForwardedRequestIsNeverForwardedAgain() throws Exception {
        long before = forwarded("c", "view");
        var a = cluster.node("a").net();
        String cid = cluster.node("c").net().nodeId();
        String obo = OnBehalfOf.mint(a.key(), "rita", List.of("risk"), null, a.nodeId(), "r-hop", cid);
        PeerHttp.Answer ans = a.http().send(cluster.node("c").url(), "GET", "/api/v1/views/trade/IRS-48213",
                Map.of(NetHeaders.ON_BEHALF_OF, obo, NetHeaders.HOP, "1"), null, Duration.ofSeconds(10));
        assertThat(ans.status()).as("C answers from itself only: it has no trade").isNotEqualTo(200);
        assertThat(forwarded("c", "view")).isEqualTo(before);
    }

    @Test
    @Order(5)
    void withAGoneBAndCSaySoClearly() throws Exception {
        String aid = cluster.node("a").net().nodeId();
        cluster.stop("a");
        assertThat(NetNodes.await(3_000, () -> cluster.node("b").net().node().orElseThrow().table().known(aid)
                .map(k -> k.member().state() == com.ash.drishti.net.member.MemberState.LEFT).orElse(false))).isTrue();
        var onB = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization", bearer("rita", "risk"));
        assertThat(onB.statusCode()).isEqualTo(502);
        assertThat(onB.body()).contains("DRS-5052").contains("cannot be reached now").contains("left");
        var onC = cluster.call("GET", cluster.node("c").url() + "/api/v1/views/trade/IRS-48213", null, "Authorization", bearer("rita", "risk"));
        // B renders trades still (it has the pack), so C forwards there, and B says the data is out of reach
        assertThat(onC.statusCode()).isEqualTo(502);
        assertThat(onC.body()).contains("DRS-5052");
        // asked by a peer for the data, B says "not here" (404), not "failed" (502): the asking node names the holder that is down itself
        var c = cluster.node("c").net();
        String bid = cluster.node("b").net().nodeId();
        String obo = OnBehalfOf.mint(c.key(), "rita", List.of("risk"), null, c.nodeId(), "r-fetch", bid);
        PeerHttp.Answer fetched = c.http().send(cluster.node("b").url(), "GET", "/api/v1/net/fetch/trade/~?id=IRS-48213",
                Map.of(NetHeaders.ON_BEHALF_OF, obo), null, Duration.ofSeconds(10));
        assertThat(fetched.status()).as(fetched.text()).isEqualTo(404);
    }
}
