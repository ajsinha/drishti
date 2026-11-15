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

import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The write endpoints of Admin → Net on three real servers (CLUSTER_GUIDE.md sections 13 and 14), each with its effect read back and a refusal
 * with a DRS code: a seed added (saved as a peer file, repeated without harm, a bad address refused), a file checked without being written,
 * a file deleted (version checked, repeated), a console failover recorded in the audit, a leave and rejoin that the others see, a dead
 * member removed (an alive one refused); and the node-only endpoints, refused with 401 DRS-5051 without the cluster credential.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetAdminWritesClusterTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static NetNodes cluster;
    static Path root;

    @BeforeAll
    static void start() throws Exception {
        root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "adminwrites-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of(), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        for (int i = 0; i < 3; i++) {
            cluster.start(String.valueOf((char) ('a' + i)), p[i]);
        }
        assertThat(NetNodes.await(30_000, () -> alive("a").size() == 2 && alive("b").size() == 2 && alive("c").size() == 2)).as("three members").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static List<String> alive(String node) {
        return cluster.node(node).net().node().map(n -> n.table().alive().stream().map(Member::nodeId).toList()).orElse(List.of());
    }

    static String url(String node, String path) {
        return cluster.node(node).url() + path;
    }

    static HttpResponse<String> call(String node, String method, String path, String body, String... headers) throws Exception {
        return cluster.call(method, url(node, path), body, headers);
    }

    static JsonNode ok(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    @Test
    @Order(1)
    void everyNodeOnlyEndpointRefusesACallWithoutTheClusterCredential() throws Exception {
        for (String path : List.of("/edits/append", "/edits/changed", "/state", "/pivot/partials", "/placement", "/modules/stage/d1",
                "/modules/stage/d1/chunk/0", "/modules/activate/d1", "/modules/abort/d1", "/modules/rollback", "/modules/unload", "/modules/registry")) {
            HttpResponse<String> r = call("a", "POST", "/api/v1/net" + path, "{}");
            assertThat(r.statusCode()).as(path + ": " + r.body()).isEqualTo(401);
            assertThat(r.body()).as(path).contains("DRS-5051");
        }
        assertThat(cluster.node("a").net().refusals()).anySatisfy(x -> assertThat(x.reason()).contains("no cluster credential"));
        HttpResponse<String> person = call("a", "POST", "/api/v1/net/engines", "{\"reason\":\"x\",\"changes\":[{\"engine\":\"pivot\",\"on\":false}]}");
        assertThat(person.statusCode()).as("a person's call to the node-to-node engine endpoint").isEqualTo(403);
        assertThat(person.body()).contains("DRS-");
    }

    @Test
    @Order(2)
    void aSeedIsSavedAsAPeerFileTriedAndRepeatedWithoutHarm() throws Exception {
        int dead = 19991;
        String body = "{\"url\":\"http://" + NetNodes.HOST + ":" + dead + "\",\"name\":\"extra-node\"}";
        JsonNode added = ok(call("a", "POST", "/api/v1/net/seeds", body));
        assertThat(added.path("file").asText()).isEqualTo("peers/extra-node.yaml");
        assertThat(added.path("answered").asBoolean()).as("nothing listens there").isFalse();
        assertThat(Files.readString(root.resolve("a/net/peers/extra-node.yaml"))).contains(NetNodes.HOST + ":" + dead);
        assertThat(ok(call("a", "GET", "/api/v1/net/file?id=peers/extra-node.yaml", null)).path("text").asText()).contains(":" + dead);
        // the same seed again: the same file, no second copy
        assertThat(ok(call("a", "POST", "/api/v1/net/seeds", body)).path("file").asText()).isEqualTo("peers/extra-node.yaml");
        try (var files = Files.list(root.resolve("a/net/peers"))) {
            assertThat(files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("extra")).count()).isEqualTo(1);
        }
        // a name from the address when none is given
        assertThat(ok(call("a", "POST", "/api/v1/net/seeds", "{\"url\":\"http://seed.example.org:8080\"}")).path("file").asText())
                .isEqualTo("peers/seed-example-org-8080.yaml");
        // refusals: no port, a name that is no file name, nothing at all
        for (String bad : List.of("{\"url\":\"no-port-here\"}", "{\"url\":\"http://x.example.org:8080\",\"name\":\"Bad Name!\"}", "{\"url\":\"\"}")) {
            HttpResponse<String> r = call("a", "POST", "/api/v1/net/seeds", bad);
            assertThat(r.statusCode()).as(bad + ": " + r.body()).isEqualTo(400);
            assertThat(r.body()).as(bad).contains("DRS-");
        }
        assertThat(root.resolve("a/net/peers/Bad Name!.yaml")).doesNotExist();
        assertThat(ok(call("a", "GET", "/api/v1/admin/audit", null, "X-Drishti-User", "admin-one")).toString()).contains("net-seed-added");
    }

    @Test
    @Order(3)
    void aFileIsCheckedWithoutBeingWrittenThenDeletedWithItsVersionAndRepeatedWithoutHarm() throws Exception {
        String before = Files.readString(root.resolve("a/net/gossip.yaml"));
        JsonNode good = ok(call("a", "POST", "/api/v1/net/file/check?id=gossip.yaml", "{\"text\":\"interval: 2s\\nsuspect-after: 6s\\ndead-after: 8s\\n\"}"));
        assertThat(good.path("ok").asBoolean()).isTrue();
        JsonNode bad = ok(call("a", "POST", "/api/v1/net/file/check?id=gossip.yaml", "{\"text\":\"fanout: lots\\n\"}"));
        assertThat(bad.path("ok").asBoolean()).isFalse();
        assertThat(bad.path("problems").toString()).contains("fanout");
        assertThat(Files.readString(root.resolve("a/net/gossip.yaml"))).as("a check writes nothing").isEqualTo(before);
        HttpResponse<String> notAFile = call("a", "POST", "/api/v1/net/file/check?id=secrets.yaml", "{\"text\":\"x: 1\"}");
        assertThat(notAFile.statusCode()).isEqualTo(400);
        assertThat(notAFile.body()).contains("DRS-");

        // delete: a wrong version is refused and the file stays; the right one removes it (and the history keeps it)
        Path seed = root.resolve("a/net/peers/seed-example-org-8080.yaml");
        assertThat(seed).exists();
        String etag = ok(call("a", "GET", "/api/v1/net/file?id=peers/seed-example-org-8080.yaml", null)).path("etag").asText();
        HttpResponse<String> stale = call("a", "DELETE", "/api/v1/net/file?id=peers/seed-example-org-8080.yaml", null, "If-Match", "0000000000000000");
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(stale.body()).contains("DRS-");
        assertThat(seed).as("a refused delete changed nothing").exists();
        JsonNode gone = ok(call("a", "DELETE", "/api/v1/net/file?id=peers/seed-example-org-8080.yaml", null, "If-Match", etag));
        assertThat(gone.path("deleted").asBoolean()).isTrue();
        assertThat(seed).doesNotExist();
        assertThat(ok(call("a", "GET", "/api/v1/net/file?id=peers/seed-example-org-8080.yaml", null)).path("exists").asBoolean()).isFalse();
        // again: nothing left to delete, and no error
        assertThat(ok(call("a", "DELETE", "/api/v1/net/file?id=peers/seed-example-org-8080.yaml", null)).path("deleted").asBoolean()).isFalse();
        // the extra seed of the earlier test goes without a version
        assertThat(ok(call("a", "DELETE", "/api/v1/net/file?id=peers/extra-node.yaml", null)).path("deleted").asBoolean()).isTrue();
        HttpResponse<String> alien = call("a", "DELETE", "/api/v1/net/file?id=../secret.yaml", null);
        assertThat(alien.statusCode()).isEqualTo(400);
        assertThat(alien.body()).contains("DRS-");
        assertThat(ok(call("a", "GET", "/api/v1/admin/audit", null, "X-Drishti-User", "admin-one")).toString()).contains("net-file-deleted");
    }

    @Test
    @Order(4)
    void aConsoleFailoverIsRecordedInTheAudit() throws Exception {
        JsonNode r = ok(call("a", "POST", "/api/v1/net/console-event",
                "{\"from\":\"http://old:18480\",\"to\":\"http://new:18480\",\"why\":\"old stopped answering\",\"console\":\"console-1\"}"));
        assertThat(r.path("recorded").asBoolean()).isTrue();
        assertThat(ok(call("a", "GET", "/api/v1/admin/audit", null, "X-Drishti-User", "admin-one")).toString())
                .contains("net-console-failover", "console console-1 moved from http://old:18480 to http://new:18480: old stopped answering");
        HttpResponse<String> none = call("a", "POST", "/api/v1/net/console-event", null);
        assertThat(none.statusCode()).as(none.body()).isBetween(400, 499);
        assertThat(none.body()).contains("DRS-");
    }

    @Test
    @Order(5)
    void aNodeLeavesAndRejoinsAndTheOthersSeeBoth() throws Exception {
        String bid = cluster.node("b").net().nodeId();
        assertThat(ok(call("b", "POST", "/api/v1/net/leave", null)).path("left").asBoolean()).isTrue();
        assertThat(NetNodes.await(8_000, () -> cluster.node("a").net().node().orElseThrow().table().known(bid)
                .map(k -> k.member().state() == MemberState.LEFT).orElse(false))).as("a sees b left").isTrue();
        // repeated: still left, no error
        assertThat(ok(call("b", "POST", "/api/v1/net/leave", null)).path("left").asBoolean()).isTrue();
        assertThat(ok(call("b", "POST", "/api/v1/net/rejoin", null)).path("left").asBoolean()).isFalse();
        assertThat(NetNodes.await(15_000, () -> alive("a").contains(bid) && alive("c").contains(bid))).as("b is alive again for a and c").isTrue();
        assertThat(ok(call("b", "POST", "/api/v1/net/rejoin", null)).path("left").asBoolean()).as("rejoin again: no harm").isFalse();
        assertThat(ok(call("b", "GET", "/api/v1/admin/audit", null, "X-Drishti-User", "admin-one")).toString()).contains("net-leave", "net-rejoin");
    }

    @Test
    @Order(6)
    void aDeadMemberIsRemovedAndAnAliveOneIsRefused() throws Exception {
        String cid = cluster.node("c").net().nodeId();
        HttpResponse<String> alive = call("a", "DELETE", "/api/v1/net/members/" + cid, null);
        assertThat(alive.statusCode()).as(alive.body()).isEqualTo(400);
        assertThat(alive.body()).contains("DRS-", "only a dead or left member");
        assertThat(alive("a")).contains(cid);
        cluster.stop("c");
        assertThat(NetNodes.await(8_000, () -> cluster.node("a").net().node().orElseThrow().table().known(cid)
                .map(k -> k.member().state().gone()).orElse(false))).as("a knows c is gone").isTrue();
        assertThat(ok(call("a", "DELETE", "/api/v1/net/members/" + cid, null)).path("removed").asText()).isEqualTo(cid);
        assertThat(cluster.node("a").net().node().orElseThrow().table().known(cid)).as("c is out of the table").isEmpty();
        assertThat(ok(call("a", "GET", "/api/v1/net/members", null)).path("members").findValuesAsText("nodeId")).doesNotContain(cid);
        HttpResponse<String> again = call("a", "DELETE", "/api/v1/net/members/" + cid, null);
        assertThat(again.statusCode()).isEqualTo(400);
        assertThat(again.body()).contains("DRS-", "no member");
        assertThat(ok(call("a", "GET", "/api/v1/admin/audit", null, "X-Drishti-User", "admin-one")).toString()).contains("net-member-removed");
    }
}
