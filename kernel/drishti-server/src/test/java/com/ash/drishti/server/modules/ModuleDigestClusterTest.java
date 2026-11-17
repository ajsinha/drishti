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
package com.ash.drishti.server.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.net.digest.Digest;
import com.ash.drishti.server.net.DigestService;
import com.ash.drishti.server.net.NetNodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The cluster's module digest on a three-node test cluster (KERNEL_AND_MODULES.md sections 4.2.1 and 4.2.2): each node publishes what it
 * booted (per module the content hash, origin, state, parts, problems; the boot's fingerprint); one node that booted other trading
 * content is named on every node's Admin → Modules ({@code GET /api/v1/admin/modules}: the cell differs from the majority, the node's
 * fingerprint differs), in the cluster's health ("nodes disagree on module trading") and in Admin → Net's warnings. The nodes run in
 * one process, so each node's boot is given to its digest as a module boot would (the boot itself is {@code ModuleBoot}'s, tested there).
 */
class ModuleDigestClusterTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int[] P = NetNodes.PORTS;
    static final String BANKING = "1".repeat(64);
    static final String TRADING = "5b0d77e8" + "0".repeat(56);
    static final String OTHER_TRADING = "9c13aa40" + "0".repeat(56);
    static NetNodes cluster;

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "module-digest-" + System.nanoTime()));
        NetNodes.folder(root.resolve("a/net"), P[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), P[1], List.of(), List.of(), List.of(P[0]));
        NetNodes.folder(root.resolve("c/net"), P[2], List.of(), List.of(), List.of(P[0]));
        cluster = new NetNodes(root);
        for (int i = 0; i < 3; i++) {
            cluster.start(String.valueOf((char) ('a' + i)), P[i]);
        }
        assertThat(NetNodes.await(30_000, () -> List.of("a", "b", "c").stream().allMatch(n -> alive(n) == 2))).as("three members").isTrue();
        boot("a", TRADING, "fa");
        boot("b", TRADING, "fa");
        boot("c", OTHER_TRADING, "fc");                    // c booted another trading package of the same version
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static long alive(String node) {
        return cluster.node(node).net().node().map(n -> n.table().alive().size()).orElse(0);
    }

    static String id(String node) {
        return cluster.node(node).net().nodeId();
    }

    /** What a node's module boot would publish: banking and trading (of this content), a stale package, a library copy warned of. */
    static void boot(String node, String trading, String fingerprint) {
        List<Digest.ModuleEntry> modules = List.of(
                new Digest.ModuleEntry("banking", "2.9.11.i9", BANKING, "first-party", "loaded", List.of("api", "compute"),
                        List.of(new Digest.Problem("library", "warning", "banking: lib/guava-33.jar: the kernel ships guava 33.3; the kernel's is used"))),
                new Digest.ModuleEntry("capitalmarkets", null, null, null, "stale", List.of(),
                        List.of(new Digest.Problem("stale", "problem", "capitalmarkets 2.9.11.i6: built for kernel 2.9.11.i6, this is 2.9.11.i9: install the matching module"))),
                new Digest.ModuleEntry("trading", "2.9.11.i9", trading, "first-party", "loaded", List.of("api", "console"), List.of()));
        String fp = fingerprint.repeat(32);
        cluster.node(node).bean(DigestService.class).modules(() -> modules, () -> fp);
    }

    static JsonNode get(String node, String path) throws Exception {
        HttpResponse<String> r = cluster.call("GET", cluster.node(node).url() + path, null, "X-Drishti-User", "admin-one");
        assertThat(r.statusCode()).as(path + ": " + r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    static boolean seesAll(String node) {
        try {
            JsonNode m = get(node, "/api/v1/admin/modules");
            return m.path("nodes").size() == 3 && m.path("nodes").findValues("reported").stream().allMatch(JsonNode::asBoolean)
                    && !m.path("disagree").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void theNodeThatBootedOtherTradingContentIsNamedEverywhere() throws Exception {
        for (String n : List.of("a", "b", "c")) {
            assertThat(NetNodes.await(20_000, () -> seesAll(n))).as(n + " has every node's modules").isTrue();
        }
        for (String via : List.of("a", "c")) {
            JsonNode m = get(via, "/api/v1/admin/modules");
            assertThat(m.path("contract").asText()).isEqualTo(ModuleClusterView.CONTRACT);
            assertThat(m.path("self").asText()).isEqualTo(id(via));
            // the nodes: a and b agree on the fingerprint, c differs
            for (JsonNode n : m.path("nodes")) {
                assertThat(n.path("agrees").asBoolean()).as(n.toString()).isEqualTo(!n.path("id").asText().equals(id("c")));
            }
            assertThat(m.path("counts").path("nodesAgree").asInt()).isEqualTo(2);
            assertThat(m.path("counts").path("nodesDiffer").asInt()).isEqualTo(1);
            assertThat(m.path("counts").path("stale").asInt()).isEqualTo(1);
            // the matrix: trading's cell on c differs from the majority; banking agrees everywhere
            JsonNode trading = row(m, "trading");
            assertThat(trading.path("differs").asBoolean()).isTrue();
            assertThat(trading.path("majority").asText()).isEqualTo(TRADING);
            for (JsonNode c : trading.path("cells")) {
                boolean onC = c.path("node").asText().equals(id("c"));
                assertThat(c.path("differs").asBoolean()).as(c.toString()).isEqualTo(onC);
                assertThat(c.path("short").asText()).isEqualTo(onC ? "9c13aa40" : "5b0d77e8");
            }
            assertThat(row(m, "banking").path("differs").asBoolean()).isFalse();
            assertThat(m.path("disagree")).singleElement().satisfies(d -> {
                assertThat(d.path("module").asText()).isEqualTo("trading");
                assertThat(d.path("differing").toString()).contains(id("c"));
            });
            // the problems: the disagreement, the stale package on every node, the library copy as a warning
            assertThat(m.path("problems").findValues("kind").stream().map(JsonNode::asText)).contains("differs", "stale", "library");
            assertThat(m.path("install").path("steps").toString()).contains("module verify", "a module is a file and a restart");
        }
        // the cluster's health: "nodes disagree on modules", naming the module and the node
        JsonNode health = get("b", "/api/v1/admin/health").path("net").path("modules");
        assertThat(health.path("agree").asBoolean()).isFalse();
        assertThat(health.path("disagree")).singleElement().satisfies(d -> {
            assertThat(d.path("module").asText()).isEqualTo("trading");
            assertThat(d.path("text").asText()).contains("nodes disagree on module trading", id("c") + " booted 9c13aa40");
        });
        // Admin -> Net's warnings
        JsonNode caps = get("a", "/api/v1/net/capabilities");
        assertThat(caps.path("warnings").findValues("what").stream().map(JsonNode::asText)).contains("modules");

        // c boots the majority's content again: every node agrees
        boot("c", TRADING, "fa");
        assertThat(NetNodes.await(20_000, () -> {
            try {
                return get("a", "/api/v1/admin/modules").path("disagree").isEmpty()
                        && get("a", "/api/v1/admin/health").path("net").path("modules").path("agree").asBoolean();
            } catch (Exception e) {
                return false;
            }
        })).as("the cluster agrees again").isTrue();
    }

    static JsonNode row(JsonNode answer, String id) {
        for (JsonNode r : answer.path("modules")) {
            if (r.path("id").asText().equals(id)) {
                return r;
            }
        }
        throw new AssertionError("no row " + id + " in " + answer);
    }
}
