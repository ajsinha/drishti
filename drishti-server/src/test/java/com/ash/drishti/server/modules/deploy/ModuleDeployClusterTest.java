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
package com.ash.drishti.server.modules.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.server.net.NetNodes;
import com.ash.drishti.server.placement.PlacementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Module deployment on a three-node cluster (real addresses, the net fixtures): the test module placed on a and b, nothing on c. Upload once
 * (the fifteen checks), plan (per node, c receives nothing), four eyes, a failure in phase 2 rolled back on every node, the apply, c
 * forwarding, switch-off (404) and a stale plan refused, a rolling upgrade under load with no failed request, removal (410 everywhere,
 * saved work marked, the class loader collected) and the reinstall that restores it all.
 */
class ModuleDeployClusterTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int[] P = NetNodes.PORTS;
    static NetNodes cluster;
    static Path work;
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "deploy-" + System.nanoTime()));
        work = root.resolve("work");
        NetNodes.folder(root.resolve("a/net"), P[0], List.of("web"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), P[1], List.of("web"), List.of(), List.of(P[0]));
        NetNodes.folder(root.resolve("c/net"), P[2], List.of(), List.of(), List.of(P[0]));
        cluster = new NetNodes(root);
        for (int i = 0; i < 3; i++) {
            cluster.start(String.valueOf((char) ('a' + i)), P[i], "--drishti.placement.refresh-seconds=1",
                    "--drishti.module-deploy.conformance-cycles=1", "--drishti.module-deploy.drain-seconds=2");
        }
        assertThat(NetNodes.await(30_000, () -> List.of("a", "b", "c").stream().allMatch(n -> alive(n) == 2))).as("three members").isTrue();
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

    static ModuleNode moduleNode(String node) {
        return cluster.node(node).bean(ModuleNode.class);
    }

    static HttpResponse<String> call(String node, String method, String path, Object body, String user) throws Exception {
        return cluster.call(method, cluster.node(node).url() + path, body == null ? null : JSON.writeValueAsString(body), "X-Drishti-User", user);
    }

    static JsonNode ok(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    static JsonNode upload(String node, Path dmod) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cluster.node(node).url() + "/api/v1/admin/modules/store")).timeout(Duration.ofSeconds(120))
                .header("X-Drishti-User", "admin-one").header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Files.readAllBytes(dmod))).build();
        return ok(HTTP.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    static JsonNode plan(String action, String version, String reason) throws Exception {
        return ok(call("a", "POST", "/api/v1/admin/modules/deployments/plans",
                Map.of("action", action, "module", "greeter", "version", version == null ? "" : version, "reason", reason), "admin-one"));
    }

    static JsonNode apply(String deployment) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cluster.node("a").url() + "/api/v1/admin/modules/deployments/" + deployment + "/apply"))
                .timeout(Duration.ofSeconds(180)).header("X-Drishti-User", "admin-two").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"comment\":\"ok\"}")).build();
        return ok(HTTP.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    static HttpResponse<String> hello(String via) throws Exception {
        return call(via, "GET", "/api/v1/greeter/hello/asha", null, "reader");
    }

    static String versionVia(String via) throws Exception {
        HttpResponse<String> r = hello(via);
        assertThat(r.statusCode()).as(via + ": " + r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).path("version").asText();
    }

    static List<String> needs(String node) throws Exception {
        JsonNode n = ok(call(node, "POST", "/api/v1/modules/needs", Map.of("items", List.of(
                Map.of("id", "view-1", "text", "{\"source\":\"/api/v1/greeter/hello/x\"}"),
                Map.of("id", "alert-2", "text", "when GRT moves by 5%"),
                Map.of("id", "plain-3", "text", "VaR by desk"))), "reader"));
        return iter(n.path("needs").fieldNames());
    }

    static List<String> iter(java.util.Iterator<String> it) {
        List<String> out = new java.util.ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    @Test
    void deployPlanApplyRollBackUpgradeRemoveAndReinstallAcrossThreeNodes() throws Exception {
        Path v1 = new DeployTestModule("greeter", "1.0.0", "GRT").pack(work);
        Path v2 = new DeployTestModule("greeter", "1.1.0", "GRT").pack(work);

        // placement first: the module's api on a and b, nothing on c
        String yaml = "modules:\n  greeter:\n    parts:\n      api: { nodes: [" + id("a") + ", " + id("b") + "] }\n";
        JsonNode pp = ok(call("a", "POST", "/api/v1/admin/cluster/placement/plans", Map.of("placement", yaml, "reason", "greeter on a and b"), "admin-one"));
        ok(call("a", "POST", "/api/v1/admin/cluster/placement/plans/" + pp.path("id").asText() + "/apply", Map.of("comment", "ok"), "admin-two"));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream()
                .allMatch(n -> cluster.node(n).bean(PlacementService.class).current().version() >= 1))).isTrue();

        // upload once: the fifteen checks, stored by id and version; the same bytes again are the same entry
        JsonNode stored = upload("a", v1);
        assertThat(stored.path("version").asText()).isEqualTo("1.0.0");
        assertThat(stored.path("checks")).hasSize(15);
        assertThat(stored.path("checks").toString()).contains("C15 Class loader unloads: passed");
        assertThat(upload("a", v1).path("sha").asText()).isEqualTo(stored.path("sha").asText());

        // the plan: a and b stage and activate; c receives nothing
        JsonNode plan = plan("install", "1.0.0", "first install");
        assertThat(plan.path("action").asText()).isEqualTo("install");
        Map<String, JsonNode> byNode = new java.util.HashMap<>();
        plan.path("nodes").forEach(n -> byNode.put(n.path("node").asText(), n));
        assertThat(byNode.get(id("a")).path("placed").asBoolean()).isTrue();
        assertThat(byNode.get(id("b")).path("placed").asBoolean()).isTrue();
        assertThat(byNode.get(id("c")).path("placed").asBoolean()).isFalse();
        assertThat(byNode.get(id("c")).path("action").asText()).contains("not placed");
        // four eyes
        HttpResponse<String> self = call("a", "POST", "/api/v1/admin/modules/deployments/" + plan.path("id").asText() + "/apply", Map.of(), "admin-one");
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(self.body()).contains("DRS-2007");

        // a failure in phase 2 (b already has a module claiming the word GRT) rolls back every node
        Path squat = new DeployTestModule("squat", "1.0.0", "GRT").pack(work);
        ModuleNode b = moduleNode("b");
        b.chunkFrom("squat-1", squat);
        b.stage("squat-1", "squat", "1.0.0", sha(squat));
        b.activate("squat-1", "squat", "1.0.0");
        JsonNode failed = apply(plan.path("id").asText());
        assertThat(failed.path("state").asText()).isEqualTo("failed");
        assertThat(failed.path("outcome").asText()).contains("rolled back on every node", id("b"));
        assertThat(moduleNode("a").runtime().loaded("greeter")).isEmpty();
        assertThat(b.runtime().loaded("greeter")).isEmpty();
        assertThat(moduleNode("a").entry("greeter")).isEmpty();
        assertThat(hello("a").statusCode()).isNotEqualTo(200);
        b.unload("squat", ModuleRuntime.Leave.REMOVE);

        // the same plan again, applied: a and b serve it, c forwards and holds nothing of it
        JsonNode done = apply(plan("install", "1.0.0", "first install, again").path("id").asText());
        assertThat(done.path("state").asText()).as(done.toString()).isEqualTo("applied");
        assertThat(versionVia("a")).isEqualTo("1.0.0");
        assertThat(versionVia("b")).isEqualTo("1.0.0");
        HttpResponse<String> viaC = hello("c");
        assertThat(viaC.statusCode()).as(viaC.body()).isEqualTo(200);
        assertThat(viaC.headers().firstValue("X-Drishti-Placement").orElse("")).isIn(id("a"), id("b"));
        assertThat(cluster.node("c").root().resolve("data/modules/installed/greeter")).doesNotExist();
        assertThat(moduleNode("c").entry("greeter")).isPresent();
        assertThat(needs("c")).isEmpty();

        // switch-off: 404 everywhere; a plan made before another was applied is stale
        JsonNode off1 = plan("disable", null, "pause it");
        JsonNode off2 = plan("disable", null, "pause it (second plan)");
        assertThat(apply(off2.path("id").asText()).path("state").asText()).isEqualTo("applied");
        HttpResponse<String> stale = call("a", "POST", "/api/v1/admin/modules/deployments/" + off1.path("id").asText() + "/apply", Map.of(), "admin-two");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("DRS-1031");
        for (String n : List.of("a", "b", "c")) {
            HttpResponse<String> r = hello(n);
            assertThat(r.statusCode()).as(n + " " + r.body()).isEqualTo(404);
            assertThat(r.body()).contains("DRS-1025");
        }
        assertThat(needs("a")).contains("view-1", "alert-2").doesNotContain("plain-3");
        assertThat(apply(plan("enable", null, "back on").path("id").asText()).path("state").asText()).isEqualTo("applied");
        assertThat(versionVia("c")).isEqualTo("1.0.0");

        // rolling upgrade under load through c: every request answered, by 1.0.0 or 1.1.0, never a failure
        upload("a", v2);
        JsonNode up = plan("install", "1.1.0", "upgrade");
        assertThat(up.path("action").asText()).isEqualTo("upgrade");
        AtomicBoolean run = new AtomicBoolean(true);
        AtomicInteger bad = new AtomicInteger();
        AtomicInteger served = new AtomicInteger();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Thread load = Thread.ofVirtual().start(() -> {
            while (run.get()) {
                try {
                    HttpResponse<String> r = hello("c");
                    if (r.statusCode() == 200) {
                        seen.add(JSON.readTree(r.body()).path("version").asText());
                        served.incrementAndGet();
                    } else {
                        bad.incrementAndGet();
                    }
                } catch (Exception e) {
                    bad.incrementAndGet();
                }
            }
        });
        Thread.sleep(300);
        JsonNode upgraded = apply(up.path("id").asText());
        Thread.sleep(300);
        run.set(false);
        load.join(10_000);
        assertThat(upgraded.path("state").asText()).as(upgraded.toString()).isEqualTo("applied");
        assertThat(bad.get()).as("failed requests during the rolling upgrade").isZero();
        assertThat(served.get()).isGreaterThan(5);
        assertThat(seen).isSubsetOf("1.0.0", "1.1.0").contains("1.1.0");
        List<String> at = new java.util.ArrayList<>();
        upgraded.path("nodes").forEach(n -> {
            if (n.path("placed").asBoolean()) {
                at.add(n.path("at").asText());
                assertThat(n.path("status").asText()).isEqualTo("activated");
            }
        });
        assertThat(at).hasSize(2).isSorted();
        assertThat(versionVia("a")).isEqualTo("1.1.0");
        assertThat(versionVia("b")).isEqualTo("1.1.0");

        // removal: 410 on every node, saved work marked, the class loader collected on the placed nodes
        JsonNode removed = apply(plan("remove", null, "no longer needed").path("id").asText());
        assertThat(removed.path("state").asText()).as(removed.toString()).isEqualTo("applied");
        removed.path("nodes").forEach(n -> {
            if (n.path("placed").asBoolean()) {
                assertThat(n.path("detail").asText()).as(n.toString()).contains("\"leakFree\":true");
            }
        });
        for (String n : List.of("a", "b", "c")) {
            HttpResponse<String> r = hello(n);
            assertThat(r.statusCode()).as(n + " " + r.body()).isEqualTo(410);
            assertThat(r.body()).contains("DRS-1028");
            assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
        assertThat(needs("c")).contains("view-1", "alert-2").doesNotContain("plain-3");
        JsonNode route = ok(call("b", "GET", "/api/v1/modules/route?path=/m/greeter/greet/asha", null, "reader"));
        assertThat(route.path("status").asInt()).isEqualTo(410);
        assertThat(route.path("by").asText()).isEqualTo("admin-two");

        // reinstall: everything back, nothing marked
        JsonNode again = apply(plan("install", "1.1.0", "reinstall").path("id").asText());
        assertThat(again.path("state").asText()).as(again.toString()).isEqualTo("applied");
        assertThat(versionVia("c")).isEqualTo("1.1.0");
        assertThat(needs("a")).isEmpty();
        assertThat(ok(call("b", "GET", "/api/v1/modules/route?path=/m/greeter/greet/asha", null, "reader")).path("status").asInt()).isEqualTo(200);

        // the audit list
        JsonNode list = ok(call("a", "GET", "/api/v1/admin/modules/deployments", null, "admin-one"));
        assertThat(list.path("deployments").size()).isGreaterThanOrEqualTo(8);
        assertThat(list.path("modules").findValuesAsText("id")).contains("greeter");
    }

    static String sha(Path f) throws Exception {
        try (var in = Files.newInputStream(f)) {
            return com.ash.drishti.module.runtime.Dmod.sha256(in);
        }
    }
}
