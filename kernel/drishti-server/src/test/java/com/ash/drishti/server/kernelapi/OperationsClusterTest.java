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
package com.ash.drishti.server.kernelapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Operation;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.module.runtime.placement.ModuleParts;
import com.ash.drishti.module.runtime.placement.PartClass;
import com.ash.drishti.server.net.NetNodes;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.placement.PlacementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 * K3 in a cluster (KERNEL_AND_MODULES.md section 2.3): an operation follows placement as its REST path does. Two nodes; the sample
 * module's compute part is placed on b only. A typed call made on a is asked on b, for the same person, and answers what the REST path
 * through a answers; on b it runs in place. With b down, the typed call is refused with the REST path's {@code 503 DRS-1032}, in the
 * same words, and never runs on a.
 */
class OperationsClusterTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static NetNodes cluster;
    static final int[] P = NetNodes.PORTS;

    /** The question and the answer of the provider; the consumer has records of its own of the same fields. */
    public record Ask(String x) {
    }

    public record Ran(String ranOn, String x, String user) {
    }

    /** The provider: the sample module's compute, served as an operation and as its REST path, one method. */
    public static final class SampleOps {
        final AtomicInteger ran = new AtomicInteger();
        final NetService net;

        SampleOps(NetService net) {
            this.net = net;
        }

        @Operation(value = "compute", rest = "GET /api/v1/sample/compute")
        public Ran compute(Person p, Ask a) {
            ran.incrementAndGet();
            return new Ran(net.nodeId(), a.x(), p.user());
        }
    }

    /** The consumer's view: its own records. */
    public interface Compute {
        record Question(String x) {
        }

        record Answer(String ranOn, String x, String user) {
        }

        @Operation("compute")
        Answer compute(Person p, Question q);
    }

    /** On every node: the sample module's REST path, and a probe that asks the operation typed for the request's person. */
    @RestController
    public static class Sample {
        final SampleOps ops;
        final Kernel kernel;

        public Sample(NetService net, Kernel kernel, OperationRegistry registry) {
            this.ops = new SampleOps(net);
            this.kernel = kernel;
            registry.register("sample", ops);
        }

        @GetMapping("/api/v1/sample/compute/{x}")
        public Ran rest(@PathVariable String x) {
            return ops.compute(kernel.person(), new Ask(x));
        }

        @GetMapping("/api/v1/op-probe/{x}")
        public Map<String, Object> typed(@PathVariable String x) {
            Map<String, Object> out = new LinkedHashMap<>();
            try {
                Compute.Answer a = kernel.operations().of("sample", Compute.class).compute(kernel.person(), new Compute.Question(x));
                out.put("answer", a);
            } catch (Kernel.Refused r) {
                out.put("refused", Map.of("status", r.status(), "code", r.code(), "detail", r.getMessage()));
            }
            return out;
        }
    }

    static final ModuleParts SAMPLE = ModuleParts.of("sample", "1.0.0", "sample", List.of(
            new ModuleParts.Part("api", PartClass.LIGHT, 0, List.of()), new ModuleParts.Part("compute", PartClass.HEAVY, 1024, List.of("compute"))));

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "operations-" + System.nanoTime()));
        NetNodes.folder(root.resolve("a/net"), P[0], List.of("web"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), P[1], List.of("heavy", "web"), List.of(), List.of(P[0]));
        cluster = new NetNodes(root);
        cluster.start("a", P[0], List.of(Sample.class), "--drishti.placement.refresh-seconds=1");
        cluster.start("b", P[1], List.of(Sample.class), "--drishti.placement.refresh-seconds=1");
        assertThat(NetNodes.await(30_000, () -> List.of("a", "b").stream().allMatch(n -> alive(n) == 1))).as("two members").isTrue();
        List.of("a", "b").forEach(n -> placement(n).declare(SAMPLE));
        String yaml = "modules:\n  sample:\n    parts:\n      api: { roles: [web] }\n      compute: { roles: [heavy], max-nodes: 1 }\n";
        String pid = JSON.readTree(call("a", "/api/v1/admin/cluster/placement/plans", Map.of("placement", yaml, "reason", "operations"), "admin-one")
                .body()).path("id").asText();
        assertThat(call("a", "/api/v1/admin/cluster/placement/plans/" + pid + "/apply", Map.of("comment", "ok"), "admin-two").statusCode()).isEqualTo(200);
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b").stream().allMatch(n -> placement(n).current().version() >= 1)))
                .as("both members adopt the placement").isTrue();
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

    static HttpResponse<String> call(String node, String path, Object body, String user) throws Exception {
        return cluster.call(body == null ? "GET" : "POST", cluster.node(node).url() + path, body == null ? null : JSON.writeValueAsString(body),
                "X-Drishti-User", user);
    }

    static JsonNode typed(String node, String x, String user) throws Exception {
        HttpResponse<String> r = call(node, "/api/v1/op-probe/" + x, null, user);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    /** The REST answer as the probe writes a typed one: {@code {answer}} or {@code {refused: {status, code, detail}}}. */
    static JsonNode rest(String node, String x, String user) throws Exception {
        HttpResponse<String> r = call(node, "/api/v1/sample/compute/" + x, null, user);
        JsonNode body = JSON.readTree(r.body());
        if (r.statusCode() < 300) {
            return JSON.createObjectNode().set("answer", body);
        }
        var refused = JSON.createObjectNode().put("status", r.statusCode()).put("code", body.path("code").asText()).put("detail", body.path("detail").asText());
        return JSON.createObjectNode().set("refused", refused);
    }

    @Test
    void anOperationPlacedOnAnotherNodeIsAskedThereAndANodeDownRefusesAsTheRestPathDoes() throws Exception {
        SampleOps onA = cluster.node("a").bean(Sample.class).ops;
        SampleOps onB = cluster.node("b").bean(Sample.class).ops;

        // through a: the REST path is forwarded to b, and so is the typed call, for the same person; the answers are the same
        for (String x : List.of("1", "2", "3")) {
            JsonNode t = typed("a", x, "reader");
            assertThat(t).as("typed through a").isEqualTo(rest("a", x, "reader"));
            assertThat(t.path("answer").path("ranOn").asText()).isEqualTo(id("b"));
            assertThat(t.path("answer").path("user").asText()).as("the person asked on b is the caller").isEqualTo("reader");
        }
        // on b: in place
        JsonNode local = typed("b", "9", "writer");
        assertThat(local).isEqualTo(rest("b", "9", "writer"));
        assertThat(local.path("answer").path("ranOn").asText()).isEqualTo(id("b"));
        assertThat(onA.ran.get()).as("nothing computed on a").isZero();
        assertThat(onB.ran.get()).isEqualTo(8);

        // the forward path is a node's only: a person cannot call it
        HttpResponse<String> direct = call("b", KernelOperations.PATH + "sample/compute", Map.of("request", Map.of("x", "1")), "reader");
        assertThat(direct.statusCode()).isEqualTo(401);
        assertThat(direct.body()).contains("DRS-5051");

        // b down: the REST path and the typed call through a refuse alike (503 DRS-1032), and nothing runs on a
        cluster.stop("b");
        assertThat(NetNodes.await(20_000, () -> {
            try {
                JsonNode r = rest("a", "5", "reader");
                return r.path("refused").path("code").asText().equals("DRS-1032") && r.path("refused").path("detail").asText().contains("none of its placed nodes is up");
            } catch (Exception e) {
                return false;
            }
        })).as("a knows b is down").isTrue();
        JsonNode refused = typed("a", "5", "reader");
        assertThat(refused).isEqualTo(rest("a", "5", "reader"));
        assertThat(refused.path("refused").path("status").asInt()).isEqualTo(503);
        assertThat(onA.ran.get()).isZero();
    }
}
