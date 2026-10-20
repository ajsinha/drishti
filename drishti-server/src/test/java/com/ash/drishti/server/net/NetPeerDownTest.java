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
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Histories while a node is down or refuses connections (the 2-hour soak's SOAK-01 and SOAK-02), on three real servers in one JVM. A and B
 * each hold a copy of the Delta test lake (trade T-1 on 2026-09-28 to 30; two tables, so neither is the other's replica); C has the finance
 * pack and an empty lake, so it reads every history from A or B.
 * <ol>
 * <li>B's port refuses connections while its gossip goes on (a node that just died, or one not serving yet): a history A answers itself
 * over a calendar window from 09-01 is complete, since B's data starts on 09-28; C's histories come from A, complete.</li>
 * <li>B is gone: the same calendar window on A is still complete ({@code partial: false}, no DRS-5052): B could hold only 09-28 to 30.</li>
 * <li>A's port refuses connections too: C answers 502 DRS-5052 naming the nodes, never DRS-1003.</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetPeerDownTest {

    static NetNodes cluster;
    static final Path SOURCE = Path.of("..", "plugins", "drishti-plugin-delta", "src", "test", "resources", "lake").toAbsolutePath();
    static final ObjectMapper JSON = new ObjectMapper();
    /** Every answer is computed afresh: a cached complete answer would hide what the read does now. */
    static final String NOCACHE = "--drishti.history.cache-mb=0";

    static Path copy(Path to) throws Exception {
        try (Stream<Path> all = Files.walk(SOURCE)) {
            for (Path p : all.toList()) {
                Path dest = to.resolve(SOURCE.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest);
                }
            }
        }
        return to;
    }

    @BeforeAll
    static void start() throws Exception {
        Path root = Path.of("target", "net-it", "peer-down-" + System.nanoTime()).toAbsolutePath();
        Path lakeA = copy(root.resolve("lake-a"));
        Path lakeB = copy(root.resolve("lake-b"));
        Path empty = Files.createDirectories(root.resolve("empty-lake"));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:emea"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        String domain = "--drishti.sources.connectors.finance-lake.settings.domain=desk";
        String lake = "--drishti.sources.connectors.finance-lake.settings.root=";
        cluster.start("a", p[0], NetForwardTest.secure(lake + lakeA, domain, "--drishti.sources.plugins.demo.enabled=false", NOCACHE));
        cluster.start("b", p[1], NetForwardTest.secure(lake + lakeB, domain, "--drishti.sources.plugins.demo.enabled=false", NOCACHE));
        cluster.start("c", p[2], NetForwardTest.secure(lake + empty, domain, "--drishti.sources.plugins.demo.enabled=false", NOCACHE));
        assertThat(NetNodes.await(20_000, () -> List.of("a", "b", "c").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table()
                .nodes().size() == 3))).as("every node has every digest").isTrue();
        // B's digest says from when to when it holds trades (SOAK-01): what a node that goes down could hold
        String bid = cluster.node("b").net().nodeId();
        assertThat(NetNodes.await(10_000, () -> cluster.node("a").bean(DigestService.class).table().node(bid)
                .flatMap(n -> n.digest().kind("trade")).map(k -> k.sources().stream().anyMatch(s -> s.earliestDate().equals("2026-09-28")))
                .orElse(false))).as("B's digest carries its first business date").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private static HttpResponse<String> history(String node, String id, String from) throws Exception {
        return cluster.call("GET", cluster.node(node).url() + "/api/v1/history/trade/" + id + "?from=" + from + "&to=2026-09-30", null,
                "Authorization", NetForwardTest.bearer("u-risk", "risk"));
    }

    private static JsonNode complete(String node, String id, String from) throws Exception {
        HttpResponse<String> r = history(node, id, from);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode a = JSON.readTree(r.body());
        assertThat(a.path("partial").asBoolean()).as(r.body()).isFalse();
        assertThat(a.path("problems").size()).as(r.body()).isZero();
        assertThat(a.path("rows").size()).as(r.body()).isEqualTo(3);
        return a;
    }

    @Test
    @Order(1)
    void aPeerThatRefusesConnectionsCountsOnlyForItsDaysAndTheNextHolderAnswers() throws Exception {
        complete("a", "T-1", "2026-09-01");                                     // all up: the reference answer
        cluster.closePort("b");
        String bid = cluster.node("b").net().nodeId();
        // B still gossips (alive to its peers) but refuses connections: SOAK-02 and SOAK-03's window
        assertThat(cluster.node("a").net().node().orElseThrow().table().known(bid).map(k -> k.member().routable()).orElse(false)).isTrue();
        for (int i = 0; i < 3; i++) {
            complete("a", "T-1", "2026-09-01");          // A asks B for 09-01 to 09-25: refused, but B holds only 09-28 to 30
            complete("c", "T-1", "2026-09-28");          // C asks B or A: a refusal passes to the next holder
            complete("c", "T-2", "2026-09-28");
        }
    }

    @Test
    @Order(2)
    void aCompleteCalendarHistoryIsNotMarkedPartialWhileANodeIsDown() throws Exception {
        String bid = cluster.node("b").net().nodeId();
        cluster.stop("b");
        assertThat(NetNodes.await(10_000, () -> cluster.node("a").net().node().orElseThrow().table().known(bid)
                .map(k -> !k.member().routable()).orElse(true))).isTrue();
        // SOAK-01: before, partial with "DRS-5052 … b (dead): 19 days of the window may be held there", all before B's first day
        complete("a", "T-1", "2026-09-01");
        complete("c", "T-1", "2026-09-28");
    }

    @Test
    @Order(3)
    void aHistoryOnlyARefusingPeerHoldsIsDrs5052NamingItNeverDrs1003() throws Exception {
        String aid = cluster.node("a").net().nodeId();
        cluster.closePort("a");
        HttpResponse<String> r = history("c", "T-1", "2026-09-28");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(502);
        assertThat(r.body()).contains("DRS-5052").contains(aid).doesNotContain("DRS-1003").doesNotContain("ConnectException");
    }
}
