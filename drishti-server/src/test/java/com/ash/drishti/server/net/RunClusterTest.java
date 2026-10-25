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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.server.bi.runs.Run;
import com.ash.drishti.server.bi.runs.RunService;
import com.ash.drishti.server.bi.runs.RunState;
import com.ash.drishti.server.bi.runs.Tier;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * BI runs on two real servers in one JVM over one shared identity database (docs/architecture/BI_RUNS.md sections 13 and 19.4): a heavy run submitted on
 * the node that does not own the model runs on the owner (forwarded for the caller, so their masks hold there); its status, result and the person's list
 * answer from either node; and when the owner dies while a run of its is unfinished, the other node adopts it once its lease lapsed and runs it again.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunClusterTest {

    static final String TOKEN_SECRET = "a-test-token-secret-at-least-32-bytes!!";
    static final TokenVerifier TOKENS = new TokenVerifier(TOKEN_SECRET, 30);
    static final ObjectMapper JSON = new ObjectMapper();
    static final String MODEL = "lake-runs";
    static NetNodes cluster;
    static String owner;
    static String other;

    static String bearer(String user, String... roles) {
        return "Bearer " + TOKENS.mint(user, List.of(roles), 300);
    }

    static String rita() {
        return bearer("rita", "bi-raw");
    }

    static String ann() {
        return bearer("ann", "bi-masked");
    }

    static void bi(Path folder, Path lake) throws Exception {
        Files.createDirectories(folder.resolve("datasets"));
        Files.createDirectories(folder.resolve("models"));
        Files.writeString(folder.resolve("datasets/lake-trade.dataset.yaml"), """
                rachana: 2
                dataset: lake-trade
                version: 1.0.0
                source: { connector: run-lake, kind: trade }
                grain: { keys: [id] }
                columns:
                  DESK:     { type: string }
                  TRADER:   { type: string }
                  NOTIONAL: { type: decimal, aggregate: sum }
                security:
                  rows:
                    - { role: [bi-raw, bi-masked, admin], where: "true" }
                  masked: [TRADER]
                """);
        Files.writeString(folder.resolve("models/lake-runs.model.yaml"), """
                rachana: 2
                model: lake-runs
                version: 1.0.0
                datasets:
                  trades: { dataset: lake-trade, role: fact }
                measures:
                  Notional: { expr: "sum(trades.NOTIONAL)" }
                """);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add("{\"TRADE_ID\":\"RL-" + i + "\",\"DESK\":\"DESK-" + (i % 3) + "\",\"TRADER\":\"Trader " + (char) ('A' + i % 4) + "\",\"NOTIONAL\":"
                    + 1000.0 * (i + 1) + "}");
        }
        Path day = lake.resolve("2026-10-08").resolve("trade.jsonl");
        Files.createDirectories(day.getParent());
        Files.write(day, lines, StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "runs-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        bi(root.resolve("bi"), root.resolve("lake"));
        Files.writeString(root.resolve("runs.yaml"), "enabled: true\nlease:\n  renew: 500ms\n  ttl: 2s\n");
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        Path identity = root.resolve("identity");
        List<String> common = List.of("--drishti.bi.dir=" + root.resolve("bi"), "--drishti.sources.plugins.file.enabled=false",
                "--drishti.sources.connectors.finance-lake.enabled=false", "--drishti.sources.connectors.run-lake.plugin=file",
                "--drishti.sources.connectors.run-lake.kinds[0]=trade", "--drishti.sources.connectors.run-lake.settings.root=" + root.resolve("lake"),
                "--drishti.sources.connectors.run-lake.settings.id-field=TRADE_ID", "--drishti.sources.connectors.run-lake.settings.rescan-seconds=3600",
                "--drishti.sources.plugins.demo.enabled=false", "--drishti.bi.runs.file=" + root.resolve("runs.yaml"),
                // one identity database for both: the run rows are shared, so a node that dies has its runs adopted (13.3)
                "--drishti.identity.database-url=jdbc:sqlite:" + identity.resolve("identity.db"),
                "--drishti.identity.users-file=" + identity.resolve("users.json"),
                "--drishti.store.root=" + root.resolve("fstore"),                 // one content store for both: BI content and runs (CONTENT_STORE.md 9)
                "--drishti.security.enabled=true", "--drishti.security.secret=" + TOKEN_SECRET, "--drishti.security.registered-users-only=false");
        List<String> a = new ArrayList<>(common);
        a.add("--drishti.bi.query.store-dir=" + root.resolve("a/bi-store"));
        List<String> b = new ArrayList<>(common);
        b.add("--drishti.bi.query.store-dir=" + root.resolve("b/bi-store"));
        cluster.start("a", p[0], a.toArray(String[]::new));
        cluster.start("b", p[1], b.toArray(String[]::new));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b").stream().allMatch(n -> cluster.node(n).bean(RunCluster.class).peers().size() == 1)))
                .as("each node sees the other alive").isTrue();
        String admin = bearer("drishti-dev-admin", "admin");
        for (String n : List.of("a", "b")) {
            for (String role : List.of("bi-raw", "bi-masked")) {
                var r = cluster.call("PUT", cluster.node(n).url() + "/api/v1/admin/role-definitions/" + role, JSON.writeValueAsString(Map.of("kinds", List.of("*"),
                        "approve", false, "raw", role.equals("bi-raw"), "bi", List.of("bi"))), "Authorization", admin);
                assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            }
        }
        for (String u : List.of("rita:bi-raw", "ann:bi-masked")) {
            String[] x = u.split(":");
            var r = cluster.call("POST", cluster.node("a").url() + "/api/v1/admin/users", JSON.writeValueAsString(Map.of("username", x[0], "displayName", x[0],
                    "email", x[0] + "@desk.test", "roles", List.of(x[1]), "password", "long-enough-pass-1")), "Authorization", admin);
            assertThat(r.statusCode()).as(r.body()).isIn(201, 409);
        }
        String ownerId = cluster.node("a").bean(RunCluster.class).owner(MODEL);
        assertThat(cluster.node("b").bean(RunCluster.class).owner(MODEL)).as("both nodes agree on the owner without talking").isEqualTo(ownerId);
        owner = cluster.node("a").net().nodeId().equals(ownerId) ? "a" : "b";
        other = owner.equals("a") ? "b" : "a";
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static Map<String, Object> mq(List<String> fields) {
        return Map.of("mq", 1, "model", MODEL, "select", Map.of("fields", fields, "measures", List.of("Notional")), "time", Map.of("as-of", "2026-10-08"));
    }

    static JsonNode ok(java.net.http.HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isBetween(200, 299);
        return JSON.readTree(r.body());
    }

    static JsonNode waitDone(String node, String id, String who) throws Exception {
        JsonNode[] last = new JsonNode[1];
        boolean done = NetNodes.await(20_000, () -> {
            try {
                last[0] = ok(cluster.call("GET", cluster.node(node).url() + "/api/v1/bi/runs/" + id, null, "Authorization", who));
                return last[0].path("state").asText().equals("done");
            } catch (Exception e) {
                return false;
            }
        });
        assertThat(done).as(String.valueOf(last[0])).isTrue();
        return last[0];
    }

    static String submit(String node, String who, List<String> fields) throws Exception {
        var r = cluster.call("POST", cluster.node(node).url() + "/api/v1/bi/runs", JSON.writeValueAsString(Map.of("source", Map.of("type", "mq", "mq", mq(fields)),
                "options", Map.of("wait", 0, "confirm", true))), "Authorization", who);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        return JSON.readTree(r.body()).path("run").path("id").asText();
    }

    @Test
    @Order(1)
    void aHeavyRunSubmittedOnTheOtherNodeRunsOnTheModelsOwnerAndAnswersFromEither() throws Exception {
        String ownerId = cluster.node(owner).net().nodeId();
        String id = submit(other, rita(), List.of("trades.DESK"));
        assertThat(id).as("the owner made the run").startsWith("r-" + ownerId + "-");
        JsonNode run = waitDone(other, id, rita());
        assertThat(run.path("node").asText()).isEqualTo(ownerId);
        // the result lives on the owner: asked of the other node, it is fetched from there; equal from both
        JsonNode viaOther = ok(cluster.call("GET", cluster.node(other).url() + "/api/v1/bi/runs/" + id + "/result", null, "Authorization", rita()));
        JsonNode onOwner = ok(cluster.call("GET", cluster.node(owner).url() + "/api/v1/bi/runs/" + id + "/result", null, "Authorization", rita()));
        assertThat(viaOther.path("rows")).isEqualTo(onOwner.path("rows"));
        assertThat(viaOther.path("rows")).hasSize(3);
        // the person's list from either node holds it, once
        for (String n : List.of("a", "b")) {
            JsonNode list = ok(cluster.call("GET", cluster.node(n).url() + "/api/v1/bi/runs?limit=50", null, "Authorization", rita()));
            List<String> ids = new ArrayList<>();
            list.path("runs").forEach(x -> ids.add(x.path("id").asText()));
            assertThat(ids).as(n).containsOnlyOnce(id);
            assertThat(list.path("nodes").path("failed")).isEmpty();
        }
        // another person cannot read it through either node
        var anns = cluster.call("GET", cluster.node(other).url() + "/api/v1/bi/runs/" + id + "/result", null, "Authorization", ann());
        assertThat(anns.statusCode()).isEqualTo(404);
    }

    @Test
    @Order(2)
    void masksHoldAcrossTheForward() throws Exception {
        String id = submit(other, ann(), List.of("trades.DESK", "trades.TRADER"));
        assertThat(id).startsWith("r-" + cluster.node(owner).net().nodeId() + "-");
        waitDone(other, id, ann());
        JsonNode anns = ok(cluster.call("GET", cluster.node(other).url() + "/api/v1/bi/runs/" + id + "/result", null, "Authorization", ann()));
        assertThat(anns.path("rows").size()).isPositive();
        anns.path("rows").forEach(r -> assertThat(r.get(1).asText()).isEqualTo(DataNode.MASK));
        assertThat(anns.toString()).doesNotContain("Trader A").doesNotContain("Trader B");
        // computed as ann on the owner: the same as her own direct question there
        String ritasId = submit(other, rita(), List.of("trades.DESK", "trades.TRADER"));
        waitDone(other, ritasId, rita());
        JsonNode ritas = ok(cluster.call("GET", cluster.node(other).url() + "/api/v1/bi/runs/" + ritasId + "/result", null, "Authorization", rita()));
        assertThat(ritas.toString()).contains("Trader A");
    }

    @Test
    @Order(3)
    void whenTheOwnerDiesItsUnfinishedRunIsAdoptedByTheOtherNodeOnce() throws Exception {
        String ownerId = cluster.node(owner).net().nodeId();
        String otherId = cluster.node(other).net().nodeId();
        RunService onOther = cluster.node(other).bean(RunService.class);
        // the owner's run as it was when the owner died mid-run: running, its lease about to lapse (the row both nodes share)
        String question = JSON.writeValueAsString(Map.of("type", "mq", "model", MODEL, "mq", mq(List.of("trades.DESK"))));
        Run running = Run.planned("r-" + ownerId + "-01JA0000000000000000000099", "rita", "rita", "mq", question, "cd".repeat(32), "s", Tier.AD_HOC_HEAVY,
                "local", "{}", ownerId, Instant.now()).moveTo(RunState.QUEUED, null, null, Instant.now()).moveTo(RunState.RUNNING, null, null, Instant.now());
        Run.Builder b = running.toBuilder();
        b.sourceId = MODEL;
        b.leaseUntil = Instant.now().plusSeconds(1);
        b.title = "Notional by desk";
        onOther.records().save(b.build());
        cluster.stop(owner);
        assertThat(NetNodes.await(15_000, () -> !cluster.node(other).bean(RunCluster.class).alive(ownerId))).as("the owner is taken for gone").isTrue();
        assertThat(NetNodes.await(20_000, () -> onOther.records().get(running.id()).map(r -> r.state() == RunState.DONE).orElse(false)))
                .as(String.valueOf(onOther.records().get(running.id()))).isTrue();
        Run adopted = onOther.records().get(running.id()).orElseThrow();
        assertThat(adopted.node()).isEqualTo(otherId);
        assertThat(adopted.attempt()).as("attempt 2 of 2: started again once").isEqualTo(2);
        JsonNode result = ok(cluster.call("GET", cluster.node(other).url() + "/api/v1/bi/runs/" + running.id() + "/result", null, "Authorization", rita()));
        assertThat(result.path("rows")).hasSize(3);
        // adopted once: the conditional update finds the row is no longer the dead node's
        assertThat(onOther.records().adopt(running.id(), ownerId, otherId, Instant.now(), "again")).isFalse();
    }
}
