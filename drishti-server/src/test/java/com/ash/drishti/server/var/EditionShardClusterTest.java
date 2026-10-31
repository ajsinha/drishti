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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.IcebergMirror;
import com.ash.drishti.server.net.NetNodes;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Drishti Risk on Drishti*net (docs/admin/EDITIONS.md › The design as built, section 6): two real members in one JVM (the {@link NetNodes} pattern and its
 * file lock) and a reference server, all three with {@code drishti.edition=risk}, VaR sharded across the two members. Each member reads the
 * same edition (About and its digest say risk), Admin → Net raises no edition warning, the members agree on the shard owners, and every
 * scope's VaR, ES, contributions, P&amp;L path and tree in USD and EUR equals one server's to the cent: the edition hides commands from
 * people, never anything the sharded engine needs. The fixture is {@link VarShardClusterTest}'s (its retired finance pack's routes, so the
 * site switches allow-only off: drishti.edition-spec.packs.allow-only=false, a site override).
 */
class EditionShardClusterTest {

    static final List<String> EDITION = List.of("--drishti.edition=risk", "--drishti.edition-spec.packs.allow-only=false");
    static final List<String> SCOPES = List.of("BANK", "NODE:MGMT~DESK-X", "NODE:LEGAL~LE-A", "NODE:LEGAL~LE-B", "LL:LL-A", "TRD:T1,T3,T5");

    static List<String> with(List<String> args) {
        List<String> out = new ArrayList<>(args);
        out.addAll(EDITION);
        return out;
    }

    @BeforeAll
    static void start() throws Exception {
        VarShardClusterTest.root = com.ash.drishti.server.TestRun.root().resolve(java.nio.file.Path.of("net-it", "edition-shards-" + System.nanoTime()));
        var root = VarShardClusterTest.root;
        VarShardClusterTest.delta = root.resolve("lake/delta");
        VarShardClusterTest.iceberg = root.resolve("lake/iceberg");
        IcebergMirror.into(VarShardClusterTest.iceberg, () -> {
            VarHierarchyFixture.write(VarShardClusterTest.delta);
            VarPvFixture.write(VarShardClusterTest.delta, VarShardClusterTest.DAY);
            return null;
        });
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        for (String n : List.of("ref", "a", "b")) {
            VarShardClusterTest.connectors(root.resolve(n), "delta", VarShardClusterTest.delta);
        }
        VarShardClusterTest.MEMBERS.clear();
        VarShardClusterTest.MEMBERS.addAll(List.of("a", "b"));
        NetNodes cluster = new NetNodes(root);
        VarShardClusterTest.cluster = cluster;
        var ref = cluster.start("ref", 0, with(VarShardClusterTest.common(root.resolve("ref"))).toArray(String[]::new));
        VarShardClusterTest.refUrl = "http://" + NetNodes.HOST + ":" + ref.ctx().getEnvironment().getProperty("local.server.port");
        cluster.start("a", p[0], with(VarShardClusterTest.sharded(root.resolve("a"))).toArray(String[]::new));
        cluster.start("b", p[1], with(VarShardClusterTest.sharded(root.resolve("b"))).toArray(String[]::new));
        assertThat(NetNodes.await(30_000, () -> List.of("a", "b").stream().allMatch(n -> VarShardClusterTest.members(n) == 2)))
                .as("A and B see each other's shards state").isTrue();
        assertThat(NetNodes.await(60_000, VarShardClusterTest::ownersAgree)).as("A and B agree on the owners").isTrue();
    }

    @AfterAll
    static void stop() {
        if (VarShardClusterTest.cluster != null) {
            VarShardClusterTest.cluster.close();
            VarShardClusterTest.cluster = null;
        }
    }

    static JsonNode admin(String full) throws Exception {
        HttpResponse<String> r = VarShardClusterTest.cluster.call("GET", full, null, "Authorization",
                "Bearer " + VarShardClusterTest.TOKENS.mint("root", List.of("admin"), 600));
        assertThat(r.statusCode()).as(full + ": " + r.body()).isEqualTo(200);
        return VarShardClusterTest.JSON.readTree(r.body());
    }

    @Test
    void everyMemberRunsTheSameEditionAndNetRaisesNoEditionWarning() throws Exception {
        for (String n : List.of("ref", "a", "b")) {
            assertThat(VarShardClusterTest.get(VarShardClusterTest.url(n) + "/public/about").path("edition").path("name").asText())
                    .as(n).isEqualTo("risk");
        }
        for (String n : List.of("a", "b")) {
            var digest = VarShardClusterTest.cluster.node(n).ctx().getBean(com.ash.drishti.server.net.DigestService.class).self();
            assertThat(digest.services()).as(n + "'s digest").containsEntry("edition", "risk");
        }
        assertThat(NetNodes.await(30_000, () -> {
            try {
                return admin(VarShardClusterTest.url("a") + "/api/v1/net/capabilities").path("nodes").size() >= 2;
            } catch (Exception e) {
                return false;
            }
        })).as("A sees B's digest").isTrue();
        List<String> warnings = new ArrayList<>();
        admin(VarShardClusterTest.url("a") + "/api/v1/net/capabilities").path("warnings").forEach(w -> warnings.add(w.path("what").asText()));
        assertThat(warnings).doesNotContain("edition");
    }

    @Test
    void shardedVarUnderTheEditionEqualsOneServer() throws Exception {
        assertThat(VarShardClusterTest.ownersAgree()).isTrue();
        VarShardClusterTest.sameAsOneServer(SCOPES, "Drishti Risk, two members");
    }
}
