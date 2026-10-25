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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Phase N5 on three real servers in one JVM: trades split three ways (each node's own files, one trade on two nodes), a search on B across
 * them equals the union with the duplicate counted once (in the rows, matched and scanned), ordered and cut as one node would; type-ahead
 * finds every node's trades; with C stopped the answer is partial and names C.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetScatterTest {

    static NetNodes cluster;
    static final ObjectMapper JSON = new ObjectMapper();
    /** id to notional of every trade, on whichever node: what the merged answer must equal. */
    static final Map<String, Long> ALL = new TreeMap<>();

    static void trades(Path root, String node, String system, int n, long base, boolean shared) throws Exception {
        Files.createDirectories(root);
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String id = system.substring(0, 3) + "-" + node.toUpperCase() + "-" + i;
            long notional = base + i * 1_000;
            lines.add("{\"TRADE_ID\":\"" + id + "\",\"NOTIONAL\":" + notional + ",\"BOOKING_SYSTEM\":\"" + system + "\",\"LEGAL_ENTITY\":\"LE-" + node.toUpperCase() + "\"}");
            ALL.put(id, notional);
        }
        if (shared) {
            lines.add("{\"TRADE_ID\":\"SHARED-1\",\"NOTIONAL\":7777777,\"BOOKING_SYSTEM\":\"MUREX\",\"LEGAL_ENTITY\":\"LE-SHARED\"}");
            ALL.put("SHARED-1", 7_777_777L);
        }
        Files.write(root.resolve("trade.jsonl"), lines);
    }

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "scatter-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        trades(root.resolve("a/feeds"), "a", "ENDUR", 6, 1_000_000, true);
        trades(root.resolve("b/feeds"), "b", "MUREX", 3, 3_000_000, false);
        trades(root.resolve("c/feeds"), "c", "CALYPSO", 6, 2_000_000, true);
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of(), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        for (int i = 0; i < 3; i++) {
            String n = String.valueOf((char) ('a' + i));
            cluster.start(n, p[i], "--drishti.sources.plugins.demo.enabled=false", "--drishti.sources.plugins.file.settings.root=" + root.resolve(n + "/feeds"),
                    "--drishti.sources.plugins.file.settings.id-field=TRADE_ID");
        }
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 3)))
                .isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static JsonNode search(String node, String q) throws Exception {
        var r = cluster.call("GET", cluster.node(node).url() + "/api/v1/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    static List<String> ids(JsonNode answer) {
        List<String> out = new ArrayList<>();
        answer.path("rows").forEach(r -> out.add(r.path("ref").path("id").asText()));
        return out;
    }

    @Test
    @Order(1)
    void aSearchOnBEqualsTheUnionWithTheDuplicateOnce() throws Exception {
        JsonNode all = search("b", "TRD limit 100");
        assertThat(ids(all)).containsExactlyElementsOf(ALL.keySet());           // every trade once, ordered by id as one node orders
        assertThat(ids(search("a", "TRD limit 100"))).as("the same from any node").isEqualTo(ids(all));
        assertThat(ids(search("c", "TRD limit 100"))).isEqualTo(ids(all));
        assertThat(all.path("partial").asBoolean()).isFalse();
        assertThat(all.path("failed")).isEmpty();
        assertThat(all.path("scanned").asLong()).isGreaterThanOrEqualTo(ALL.size());
        assertThat(all.has("nodes")).as("who answered what is for administrators (security is off: everyone is)").isTrue();
        // ordered by notional and cut: the same as sorting the union
        JsonNode top = search("b", "TRD where NOTIONAL > 0 order by NOTIONAL desc limit 5");
        List<String> expected = ALL.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey())).limit(5).map(Map.Entry::getKey).toList();
        assertThat(ids(top)).containsExactlyElementsOf(expected);
        assertThat(top.path("matched").asLong()).as("SHARED-1, matched on A and on C, counts once").isEqualTo(ALL.size());
        assertThat(all.path("matched").asLong()).isEqualTo(ALL.size());
        assertThat(all.path("scanned").asLong()).as("read on A and on C, one trade").isEqualTo(ALL.size());
        assertThat(all.has("matchedIds")).as("the ids are for the merge only").isFalse();
    }

    @Test
    @Order(2)
    void typeAheadFindsEveryNodesTrades() throws Exception {
        var r = cluster.call("GET", cluster.node("b").url() + "/api/v1/command/suggest?q=CAL-C", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("CAL-C-1");
        var e = cluster.call("GET", cluster.node("b").url() + "/api/v1/command/suggest?q=END-A", null);
        assertThat(e.body()).contains("END-A-1");
    }

    @Test
    @Order(3)
    void withCStoppedTheAnswerIsPartialAndNamesC() throws Exception {
        String cid = cluster.node("c").net().nodeId();
        cluster.stop("c");
        assertThat(NetNodes.await(3_000, () -> cluster.node("b").net().node().orElseThrow().table().known(cid)
                .map(k -> k.member().state().gone()).orElse(false))).isTrue();
        JsonNode answer = search("b", "TRD limit 100");
        assertThat(answer.path("partial").asBoolean()).isTrue();
        assertThat(answer.path("failed").toString()).contains(cid);
        assertThat(ids(answer)).contains("END-A-1", "MUR-B-1", "SHARED-1").doesNotContain("CAL-C-1");
    }
}
