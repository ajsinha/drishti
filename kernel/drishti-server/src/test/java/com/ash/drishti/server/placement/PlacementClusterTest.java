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
package com.ash.drishti.server.placement;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.module.runtime.placement.ModuleParts;
import com.ash.drishti.module.runtime.placement.PartClass;
import com.ash.drishti.server.net.NetNodes;
import com.ash.drishti.server.net.NetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module placement on a three-node test cluster (KERNEL_AND_MODULES.md section 4.11.9): nothing placed runs nowhere; a plan is refused
 * when heavy work would go everywhere, applied only by a second administrator, refused when the cluster changed, and reaches every member
 * without a restart; heavy requests through every node compute only on the placed node; the singleton owner is agreed and fails over only
 * inside its placement; with no placed node up the part is unavailable, never run elsewhere. Today's in-process modules stay on every node.
 */
class PlacementClusterTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static NetNodes cluster;
    static final int[] P = NetNodes.PORTS;

    /** The sample module's compute endpoint, on every node: it counts what it ran. */
    @RestController
    public static class SampleCompute {
        final AtomicInteger ran = new AtomicInteger();
        final NetService net;

        public SampleCompute(NetService net) {
            this.net = net;
        }

        @GetMapping("/api/v1/sample/compute/{x}")
        public Map<String, Object> compute(@PathVariable String x) {
            ran.incrementAndGet();
            return Map.of("ranOn", net.nodeId(), "x", x);
        }
    }

    static final ModuleParts SAMPLE = ModuleParts.of("sample", "1.0.0", "sample", List.of(
            new ModuleParts.Part("api", PartClass.LIGHT, 0, List.of()), new ModuleParts.Part("compute", PartClass.HEAVY, 1024, List.of("compute")),
            new ModuleParts.Part("feeds", PartClass.FEED, 0, List.of("feeds"))));

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "placement-" + System.nanoTime()));
        NetNodes.folder(root.resolve("a/net"), P[0], List.of("web"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), P[1], List.of("heavy", "web"), List.of(), List.of(P[0]));
        NetNodes.folder(root.resolve("c/net"), P[2], List.of(), List.of(), List.of(P[0]));
        cluster = new NetNodes(root);
        for (int i = 0; i < 3; i++) {
            cluster.start(String.valueOf((char) ('a' + i)), P[i], List.of(SampleCompute.class), "--drishti.placement.refresh-seconds=1");
        }
        assertThat(NetNodes.await(30_000, () -> List.of("a", "b", "c").stream().allMatch(n -> alive(n) == 2))).as("three members").isTrue();
        List.of("a", "b", "c").forEach(n -> placement(n).declare(SAMPLE));
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

    static PlacementService placement(String node) {
        return cluster.node(node).bean(PlacementService.class);
    }

    static String id(String node) {
        return cluster.node(node).net().nodeId();
    }

    static HttpResponse<String> call(String node, String method, String path, Object body, String user) throws Exception {
        return cluster.call(method, cluster.node(node).url() + path, body == null ? null : JSON.writeValueAsString(body), "X-Drishti-User", user);
    }

    static String owner(String node, String part) {
        return placement(node).matrix().part("sample", part).orElseThrow().owner();
    }

    static boolean call503(String node) {
        try {
            HttpResponse<String> r = call(node, "GET", "/api/v1/sample/compute/9", null, "reader");
            return r.statusCode() == 503 && r.body().contains("DRS-1032") && r.body().contains("unavailable");
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void placementHonouredHeavyWorkStaysPutAndTheSingletonFailsOverInsideItsPlacement() throws Exception {
        // no all-nodes default: nothing placed runs nowhere, and the matrix says so
        JsonNode m = JSON.readTree(call("a", "GET", "/api/v1/admin/cluster/modules", null, "admin-one").body());
        assertThat(m.path("problems").toString()).contains("sample is not placed");
        assertThat(m.path("parts").findValues("status").stream().map(JsonNode::asText)).contains("not-placed");
        assertThat(m.path("nodes")).hasSize(3);
        HttpResponse<String> nowhere = call("c", "GET", "/api/v1/sample/compute/1", null, "reader");
        assertThat(nowhere.statusCode()).isEqualTo(503);
        assertThat(nowhere.body()).contains("DRS-1032");
        // today's in-process modules: on every node
        if (m.path("parts").findValues("status").stream().anyMatch(s -> s.asText().equals("in-process"))) {
            assertThat(m.path("parts").findParents("status").stream().filter(r -> r.path("status").asText().equals("in-process"))
                    .allMatch(r -> r.path("placed").size() == 3)).isTrue();
        }

        // heavy work on every node is refused without allow-all
        HttpResponse<String> wide = call("a", "POST", "/api/v1/admin/cluster/placement/plans",
                Map.of("placement", "modules: { sample: { parts: { compute: { roles: [all] } } } }", "reason", "too wide"), "admin-one");
        assertThat(wide.statusCode()).isEqualTo(422);
        assertThat(wide.body()).contains("DRS-1030", "allow-all");

        // plan, four eyes, apply: every member has it within seconds, no restart
        String yaml = "modules:\n  sample:\n    parts:\n      api: { roles: [web] }\n      compute: { roles: [heavy], max-nodes: 1 }\n"
                + "      feeds: { nodes: [" + id("a") + ", " + id("c") + "] }\n";
        JsonNode plan = JSON.readTree(call("a", "POST", "/api/v1/admin/cluster/placement/plans", Map.of("placement", yaml, "reason", "first placement"),
                "admin-one").body());
        String pid = plan.path("id").asText();
        assertThat(plan.path("state").asText()).isEqualTo("proposed");
        HttpResponse<String> self = call("a", "POST", "/api/v1/admin/cluster/placement/plans/" + pid + "/apply", Map.of(), "admin-one");
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(self.body()).contains("DRS-2007");
        assertThat(call("a", "POST", "/api/v1/admin/cluster/placement/plans/" + pid + "/apply", Map.of("comment", "ok"), "admin-two").statusCode())
                .isEqualTo(200);
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream().allMatch(n -> placement(n).current().version() == 1)))
                .as("every member adopts version 1").isTrue();

        // heavy requests through each node compute on b only; a and c forward, never compute
        for (String via : List.of("a", "b", "c")) {
            for (int i = 0; i < 5; i++) {
                HttpResponse<String> r = call(via, "GET", "/api/v1/sample/compute/" + i, null, "reader");
                assertThat(r.statusCode()).as(via + ": " + r.body()).isEqualTo(200);
                assertThat(JSON.readTree(r.body()).path("ranOn").asText()).isEqualTo(id("b"));
                assertThat(r.headers().firstValue(PlacementGuardFilter.HEADER)).contains(id("b"));
            }
        }
        assertThat(cluster.node("b").bean(SampleCompute.class).ran.get()).isEqualTo(15);
        assertThat(cluster.node("a").bean(SampleCompute.class).ran.get()).isZero();
        assertThat(cluster.node("c").bean(SampleCompute.class).ran.get()).isZero();

        // the matrix through the API: c holds neither the API nor the compute of sample
        JsonNode after = JSON.readTree(call("c", "GET", "/api/v1/admin/cluster/modules", null, "admin-one").body());
        assertThat(after.path("parts").findParents("part").stream().filter(r -> r.path("module").asText().equals("sample") && !r.path("part").asText().equals("feeds"))
                .allMatch(r -> r.path("cells").path(id("c")).asText().equals("NOT_PLACED"))).isTrue();

        // one singleton owner, agreed by every node: the first live placed node by name
        String first = id("a").compareTo(id("c")) < 0 ? "a" : "c";
        String second = first.equals("a") ? "c" : "a";
        assertThat(List.of("a", "b", "c").stream().map(n -> owner(n, "feeds")).distinct().toList()).containsExactly(id(first));

        // a plan computed before the cluster changed is refused
        String stale = JSON.readTree(call("a", "POST", "/api/v1/admin/cluster/placement/plans", Map.of("placement", yaml, "reason", "again"),
                "admin-one").body()).path("id").asText();
        placement("a").declare(ModuleParts.of("other", "0.1.0", "other", List.of(new ModuleParts.Part("api", PartClass.LIGHT, 0, List.of()))));
        HttpResponse<String> refused = call("a", "POST", "/api/v1/admin/cluster/placement/plans/" + stale + "/apply", Map.of(), "admin-two");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("DRS-1031");
        placement("a").forget("other");

        // the feeds owner stops: the other placed node takes over on every node, never b (up, but not placed)
        String next = id(second);
        cluster.stop(first);
        assertThat(NetNodes.await(20_000, () -> next.equals(owner(second, "feeds")) && next.equals(owner("b", "feeds"))))
                .as(second + " owns the feeds, as b and " + second + " agree").isTrue();
        // b stops too: compute is unavailable, never run on the node left
        cluster.stop("b");
        assertThat(NetNodes.await(20_000, () -> call503(second))).as("compute unavailable through " + second).isTrue();
        assertThat(cluster.node(second).bean(SampleCompute.class).ran.get()).isZero();
    }
}
