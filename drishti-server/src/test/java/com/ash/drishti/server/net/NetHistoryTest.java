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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * History queries across Drishti*net (HISTORY_QUERIES.md section 12) on three real servers in one JVM: A holds the Delta test lake (trade
 * T-1 on 2026-09-28, 29 and 30); B has the finance pack and an empty lake, so its history of T-1 comes from A in one {@code /net/history}
 * call, masked by B's own rules; C has no finance pack, so its whole history request is forwarded to a node that renders trades.
 */
class NetHistoryTest {

    static NetNodes cluster;
    static final String LAKE = "--drishti.sources.connectors.finance-lake.settings.root=../plugins/drishti-plugin-delta/src/test/resources/lake";
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void start() throws Exception {
        Path root = Path.of("target", "net-it", "history-" + System.nanoTime()).toAbsolutePath();
        Path empty = Files.createDirectories(root.resolve("empty-lake"));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:emea"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        String domain = "--drishti.sources.connectors.finance-lake.settings.domain=desk";
        cluster.start("a", p[0], NetForwardTest.secure(LAKE, domain, "--drishti.sources.plugins.demo.enabled=false"));
        cluster.start("b", p[1], NetForwardTest.secure("--drishti.sources.connectors.finance-lake.settings.root=" + empty, domain,
                "--drishti.sources.plugins.demo.enabled=false", "--drishti.security.redact=nettingSet"));
        cluster.start("c", p[2], NetForwardTest.secure("--drishti.packs.enabled=logistics", "--drishti.sources.plugins.demo.enabled=false"));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 3)))
                .as("every node has every digest").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static long forwarded(String node, String what) {
        return cluster.node(node).bean(ForwardLog.class).recent().stream().filter(c -> c.what().equals(what) && c.outcome().equals("ok")).count();
    }

    private static JsonNode history(String node, String role) throws Exception {
        var r = cluster.call("GET", cluster.node(node).url() + "/api/v1/history/trade/T-1?from=2026-09-28&to=2026-09-30", null,
                "Authorization", NetForwardTest.bearer("u-" + role, role));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static List<Double> mtm(JsonNode a) {
        List<Double> out = new java.util.ArrayList<>();
        a.path("rows").forEach(r -> out.add(r.path("values").path("mtm").asDouble()));
        return out;
    }

    @Test
    void aNodeWithoutTheDataReadsItsHistoryFromThePeerInOneCall() throws Exception {
        JsonNode onA = history("a", "risk");
        assertThat(mtm(onA)).containsExactly(100.0, 110.0, 125.0);
        long before = forwarded("b", "history");
        JsonNode onB = history("b", "risk");
        assertThat(mtm(onB)).containsExactly(100.0, 110.0, 125.0);
        assertThat(onB.at("/rows/1/changed").toString()).contains("mtm");
        assertThat(forwarded("b", "history")).isEqualTo(before + 1);                  // one call for the three days, not one per day
        assertThat(onB.at("/rows/0/values/nettingSet").asText()).isEqualTo("NS-A");
        // B masks nettingSet for a role without raw, although A sent it in clear
        JsonNode masked = history("b", "trader");
        masked.path("rows").forEach(r -> assertThat(r.at("/values/nettingSet").asText()).isEqualTo("•••"));
    }

    @Test
    void aNodeWithoutThePackForwardsTheWholeRequest() throws Exception {
        long before = forwarded("c", "view");                          // a forwarded request of any area is logged as "view"
        JsonNode onC = history("c", "risk");
        assertThat(mtm(onC)).containsExactly(100.0, 110.0, 125.0);
        assertThat(forwarded("c", "view")).isGreaterThan(before);
    }
}
