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

import com.ash.drishti.engine.history.HistoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The history cache never serves one caller's masks to another (SOAK2-01), on three real servers in one JVM. A holds the Delta test lake
 * (trade T-1: MTM 100, 110, 125 on 2026-09-28, 29 and 30) and B and C hold no trade, so they read T-1's days from A, which masks them
 * there for the caller. Every node masks {@code mtm} for a role without raw ({@code trader}); {@code risk} sees it. Whoever asks first,
 * each caller gets their own view: the trader masked, risk the real MTM with its changes flagged, again and again within the cache's
 * life, on both reading nodes.
 */
class NetHistoryMasksTest {

    static NetNodes cluster;
    static final String LAKE = "--drishti.sources.connectors.finance-lake.settings.root=../plugins/drishti-plugin-delta/src/test/resources/lake";
    static final String DOMAIN = "--drishti.sources.connectors.finance-lake.settings.domain=desk";
    static final String MASK = "--drishti.security.redact=mtm";
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void start() throws Exception {
        Path root = Path.of("target", "net-it", "history-masks-" + System.nanoTime()).toAbsolutePath();
        Path empty = Files.createDirectories(root.resolve("empty-lake"));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:emea"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        String off = "--drishti.sources.plugins.demo.enabled=false";
        String none = "--drishti.sources.connectors.finance-lake.settings.root=" + empty;
        cluster.start("a", p[0], NetForwardTest.secure(LAKE, DOMAIN, off, MASK));
        cluster.start("b", p[1], NetForwardTest.secure(none, DOMAIN, off, MASK));
        cluster.start("c", p[2], NetForwardTest.secure(none, DOMAIN, off, MASK));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 3)))
                .as("every node has every digest").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @BeforeEach
    void cold() {
        List.of("a", "b", "c").forEach(n -> cluster.node(n).bean(HistoryService.class).cache().clear());
    }

    private static JsonNode history(String node, String role) throws Exception {
        var r = cluster.call("GET", cluster.node(node).url() + "/api/v1/history/trade/T-1?from=2026-09-28&to=2026-09-30", null,
                "Authorization", NetForwardTest.bearer("u-" + role, role));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static List<String> mtm(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.path("rows").forEach(r -> {
            JsonNode v = r.path("values").path("mtm");
            out.add(v.isNumber() ? String.valueOf(v.asDouble()) : v.asText());
        });
        return out;
    }

    private static boolean columnMasked(JsonNode a, String key) {
        for (JsonNode c : a.path("columns")) {
            if (key.equals(c.path("path").asText())) {
                return c.path("masked").asBoolean();
            }
        }
        throw new AssertionError("no column " + key + " in " + a.path("columns"));
    }

    private static boolean mtmChangedOnAnyRow(JsonNode a) {
        for (JsonNode r : a.path("rows")) {
            for (JsonNode c : r.path("changed")) {
                if ("mtm".equals(c.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void raw(JsonNode a) {
        assertThat(mtm(a)).containsExactly("100.0", "110.0", "125.0");
        assertThat(columnMasked(a, "mtm")).isFalse();
        assertThat(a.at("/rows/1/changed").toString()).contains("mtm");
        assertThat(a.at("/rows/2/changed").toString()).contains("mtm");
    }

    private static void masked(JsonNode a) {
        assertThat(mtm(a)).containsOnly("•••").hasSize(3);
        assertThat(columnMasked(a, "mtm")).isTrue();
        assertThat(mtmChangedOnAnyRow(a)).as("a masked field never changes").isFalse();
        assertThat(a.toString()).doesNotContain("110.0").doesNotContain("125.0");
    }

    @Test
    void aViewerFirstThenRiskSeesTheRealValuesOnEveryReadingNode() throws Exception {
        for (String node : List.of("b", "c")) {
            masked(history(node, "trader"));
            JsonNode risk = history(node, "risk");
            raw(risk);
            for (int i = 0; i < 3; i++) {                                     // within the cache's life, in either order
                JsonNode again = history(node, "risk");
                raw(again);
                assertThat(again.path("cache").asText()).isEqualTo("hit");
                masked(history(node, "trader"));
            }
        }
    }

    @Test
    void riskFirstThenAViewerStaysMaskedOnEveryReadingNode() throws Exception {
        for (String node : List.of("c", "b")) {
            raw(history(node, "risk"));
            JsonNode viewer = history(node, "trader");
            masked(viewer);
            for (int i = 0; i < 3; i++) {
                JsonNode again = history(node, "trader");
                masked(again);
                assertThat(again.path("cache").asText()).isEqualTo("hit");
                raw(history(node, "risk"));
            }
        }
    }

    @Test
    void theHoldingNodeSharesItsOwnUnmaskedDaysAndMasksPerAnswer() throws Exception {
        masked(history("a", "trader"));
        JsonNode risk = history("a", "risk");
        raw(risk);
        assertThat(risk.path("cache").asText()).as("local days are cached unmasked, one entry for every caller").isEqualTo("hit");
        masked(history("a", "trader"));
    }
}
