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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * QA4-02: a map panel on a node that reads its kind only from a peer. A holds the Delta test lake (trades T-1 and T-2 on 2026-09-30, with an MTM);
 * B has the pack but its trade connector switched off, as QA ran it. On B, a map that links to the trades and shades them by MTM listed the kind on B's own sources only, so it was empty
 * and said no entity matched. Now the listing asks the peers as other listings do, for the caller: the links and the shade come back
 * on B, the shade masked for a role without raw and shown for one with it, whoever asks first, again within the cache's life.
 */
class NetMapPanelTest {

    static NetNodes cluster;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String MAP = """
            rachana: 1
            sutra: qa-map
            version: 1
            match: { kind: trade }
            title: { pill: Trade, id: $.tradeId }
            panels:
              - { id: m, kind: map, title: Trades, link: { kind: trade, id: '{code}' }, shade: { kind: trade, field: $.mtm, by: $.tradeId, label: MTM } }
            """;

    @BeforeAll
    static void start() throws Exception {
        Path root = Path.of("target", "net-it", "map-panel-" + System.nanoTime()).toAbsolutePath();
        Path empty = Files.createDirectories(root.resolve("empty-lake"));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        String off = "--drishti.sources.plugins.demo.enabled=false";
        cluster.start("a", p[0], NetForwardTest.secure(NetHistoryMasksTest.LAKE, NetHistoryMasksTest.DOMAIN, off, NetHistoryMasksTest.MASK));
        cluster.start("b", p[1], NetForwardTest.secure("--drishti.sources.connectors.finance-lake.enabled=false", "--drishti.sources.connectors.finance-lake.settings.root=" + empty,
                off, NetHistoryMasksTest.MASK));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 2)))
                .as("both nodes have both digests").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private static JsonNode map(String node, String role) throws Exception {
        // a stored trade (read from A on B), as of the lake's newest date: the map reads the kind as of that date too
        ObjectNode body = JSON.createObjectNode().put("yaml", MAP).put("kind", "trade").put("id", "T-1");
        var r = cluster.call("POST", cluster.node(node).url() + "/api/v1/studio/preview?asOf=2026-09-30", body.toString(), "Authorization",
                NetForwardTest.bearer("u-" + role, role), "Content-Type", "application/json");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).path("panels").get(0).path("data");
    }

    private static List<String> codes(JsonNode map) {
        List<String> out = new ArrayList<>();
        map.path("link").path("codes").forEach(c -> out.add(c.asText()));
        return out;
    }

    private static void raw(JsonNode map) {
        assertThat(codes(map)).as(map.toString()).containsExactlyInAnyOrder("T-1", "T-2");
        assertThat(map.path("link").path("note").isNull() || map.path("link").path("note").isMissingNode()).as(map.toString()).isTrue();
        List<String> shaded = new ArrayList<>();
        map.path("shade").path("items").forEach(i -> shaded.add(i.path("code").asText() + "=" + i.path("value").asDouble()));
        assertThat(shaded).as(map.toString()).hasSize(2).contains("T-1=" + 125.0);
    }

    private static void masked(JsonNode map) {
        assertThat(codes(map)).as(map.toString()).containsExactlyInAnyOrder("T-1", "T-2");
        assertThat(map.path("shade").path("items")).isEmpty();
        assertThat(map.path("shade").path("note").asText()).isEqualTo("the field is masked for your role");
        assertThat(map.toString()).doesNotContain("125");
    }

    @Test
    void aMapOnANodeThatReadsTheKindOnlyFromAPeerListsAndShadesItForEachCaller() throws Exception {
        masked(map("b", "trader"));
        raw(map("b", "risk"));
        for (int i = 0; i < 2; i++) {                                            // within the cache's life, in either order
            masked(map("b", "trader"));
            raw(map("b", "risk"));
        }
        raw(map("a", "risk"));                                                  // the holding node, as before
        masked(map("a", "trader"));
    }
}
