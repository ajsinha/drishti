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
 * History read through a peer stays fresh (QA1-01) and a peer that is gone is named (QA1-02), on two real servers in one JVM. A holds a
 * copy of the Delta test lake at its first commit (trade T-1's MTM 120 on 2026-09-30); B holds no trade, so it reads T-1's history from A.
 * The second commit (MTM 125) is then written into A's lake, as a load does: B's next history shows it, not its cached answer. Then A
 * stops: B names it (DRS-5052), never "no data" (DRS-1013), and keeps no such answer.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetHistoryFreshTest {

    static NetNodes cluster;
    static Path lake;
    static final Path SOURCE = Path.of("..", "plugins", "drishti-plugin-delta", "src", "test", "resources", "lake").toAbsolutePath();
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void start() throws Exception {
        Path root = Path.of("target", "net-it", "history-fresh-" + System.nanoTime()).toAbsolutePath();
        lake = root.resolve("lake");
        try (Stream<Path> all = Files.walk(SOURCE)) {
            for (Path p : all.toList()) {
                Path to = lake.resolve(SOURCE.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else if (!p.getFileName().toString().equals("00000000000000000001.json")) {
                    Files.copy(p, to);                                            // the first commit only: the "load" comes later
                }
            }
        }
        Path empty = Files.createDirectories(root.resolve("empty-lake"));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        String domain = "--drishti.sources.connectors.finance-lake.settings.domain=desk";
        cluster.start("a", p[0], NetForwardTest.secure("--drishti.sources.connectors.finance-lake.settings.root=" + lake, domain,
                "--drishti.sources.plugins.demo.enabled=false"));
        cluster.start("b", p[1], NetForwardTest.secure("--drishti.sources.connectors.finance-lake.settings.root=" + empty, domain,
                "--drishti.sources.plugins.demo.enabled=false"));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 2)))
                .as("every node has every digest").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private static HttpResponse<String> history(String id) throws Exception {
        return cluster.call("GET", cluster.node("b").url() + "/api/v1/history/trade/" + id + "?from=2026-09-28&to=2026-09-30", null,
                "Authorization", NetForwardTest.bearer("u-risk", "risk"));
    }

    private static JsonNode ok(String id) throws Exception {
        HttpResponse<String> r = history(id);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static double lastMtm(JsonNode a) {
        JsonNode rows = a.path("rows");
        return rows.get(rows.size() - 1).path("values").path("mtm").asDouble();
    }

    @Test
    @Order(1)
    void aLoadOnThePeerShowsInTheHistoryReadThroughIt() throws Exception {
        JsonNode first = ok("T-1");
        assertThat(lastMtm(first)).isEqualTo(120.0);
        assertThat(first.path("cache").asText()).isEqualTo("miss");
        assertThat(ok("T-1").path("cache").asText()).isEqualTo("hit");
        Path log = lake.resolve("desk/trade/_delta_log/00000000000000000001.json");
        Files.copy(SOURCE.resolve("desk/trade/_delta_log/00000000000000000001.json"), log);
        // A's digest carries its new data version within a gossip interval or two; B's cached answer is no longer used
        boolean fresh = NetNodes.await(15_000, () -> {
            try {
                return lastMtm(ok("T-1")) == 125.0;
            } catch (Exception e) {
                return false;
            }
        });
        assertThat(fresh).as("the restated day read through the peer after the load").isTrue();
    }

    @Test
    @Order(2)
    void aPeerThatIsGoneIsNamedNotNoData() throws Exception {
        String aid = cluster.node("a").net().nodeId();
        cluster.stop("a");
        assertThat(NetNodes.await(5_000, () -> cluster.node("b").net().node().orElseThrow().table().known(aid)
                .map(k -> !k.member().routable()).orElse(false))).isTrue();
        for (int i = 0; i < 2; i++) {                                              // and the second time too: nothing was cached
            HttpResponse<String> r = history("T-2");
            assertThat(r.statusCode()).as(r.body()).isEqualTo(502);
            assertThat(r.body()).contains("DRS-5052").contains("cannot be reached now").doesNotContain("DRS-1013");
        }
    }
}
