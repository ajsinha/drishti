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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Two servers reading one folder of files (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 10) on three real servers in one JVM: A and B read the same lake with the
 * {@code file} connector, C its own files, one trade in both. The file connector names its table (the folder and the fingerprint of the
 * kind's files), so A and B are replicas: a search asks one of them, and the answer counts every trade once in its rows, {@code matched}
 * and {@code scanned}, from any node.
 */
class NetSharedLakeTest {

    static NetNodes cluster;
    static final Map<String, Long> ALL = new java.util.TreeMap<>();

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "lake-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        NetScatterTest.ALL.clear();
        NetScatterTest.trades(root.resolve("lake"), "l", "ENDUR", 6, 1_000_000, true);
        NetScatterTest.trades(root.resolve("c/feeds"), "c", "CALYPSO", 3, 2_000_000, true);
        ALL.putAll(NetScatterTest.ALL);
        NetScatterTest.ALL.clear();                       // that class's own, filled again by its own start
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of(), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        String[] feeds = {root.resolve("lake").toString(), root.resolve("lake").toString(), root.resolve("c/feeds").toString()};
        for (int i = 0; i < 3; i++) {
            String n = String.valueOf((char) ('a' + i));
            cluster.start(n, p[i], "--drishti.sources.plugins.demo.enabled=false", "--drishti.sources.plugins.file.settings.root=" + feeds[i],
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

    /** The table identities of the trade sources of {@code node}'s digest, as {@code on} sees them. */
    static List<String> tables(String on, String node) {
        String id = cluster.node(node).net().nodeId();
        List<String> out = new ArrayList<>();
        cluster.node(on).bean(DigestService.class).table().node(id).flatMap(n -> n.digest().kind("trade"))
                .ifPresent(k -> k.sources().forEach(s -> out.add(s.connector() + "=" + s.table())));
        return out;
    }

    static JsonNode search(String node, String q) throws Exception {
        var r = cluster.call("GET", cluster.node(node).url() + "/api/v1/search?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8),
                null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return NetScatterTest.JSON.readTree(r.body());
    }

    @Test
    void theTwoReadersOfOneLakeAreReplicasAndEveryCountIsOnce() throws Exception {
        List<String> a = tables("c", "a");
        assertThat(a).anyMatch(t -> t.contains("=file:") && t.contains("lake#"));
        assertThat(tables("c", "b")).as("B reads the same files: the same identity").isEqualTo(a);
        assertThat(tables("c", "c")).isNotEqualTo(a);
        for (String node : List.of("a", "b", "c")) {
            JsonNode all = search(node, "TRD limit 100");
            assertThat(NetScatterTest.ids(all)).as("on " + node).containsExactlyElementsOf(ALL.keySet());
            assertThat(all.path("matched").asLong()).as("on " + node + ": " + all.path("nodes")).isEqualTo(ALL.size());
            assertThat(all.path("scanned").asLong()).as("on " + node + ": " + all.path("nodes")).isEqualTo(ALL.size());
            assertThat(all.path("nodes")).as("on " + node + ": this node and one other, never both readers of the lake " + a).hasSize(2);
            assertThat(all.path("partial").asBoolean()).isFalse();
            assertThat(all.has("matchedIds")).isFalse();
        }
        JsonNode top = search("c", "TRD where NOTIONAL > 0 order by NOTIONAL desc limit 3");
        assertThat(top.path("matched").asLong()).isEqualTo(ALL.size());
        assertThat(NetScatterTest.ids(top)).hasSize(3);
    }
}
