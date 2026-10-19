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

import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.SessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase N7 on three real servers in one JVM: a user's saved searches, layouts and pivots written on A are on B within a second, still there
 * after A stops (the console's failover), and a node that joins later is given them; alerts are not copied (they act on every node).
 */
class NetUserStateTest {

    static NetNodes cluster;
    static Path root;

    @BeforeAll
    static void start() throws Exception {
        root = Path.of("target", "net-it", "state-" + System.nanoTime()).toAbsolutePath();
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of(), List.of(), List.of(p[1]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0]);
        cluster.start("b", p[1]);
        assertThat(NetNodes.await(10_000, () -> cluster.node("a").net().node().orElseThrow().table().alive().size() == 1)).isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void savedSearchesLayoutsAndPivotsFollowTheUserToAnotherNode() throws Exception {
        var patch = cluster.call("PATCH", cluster.node("a").url() + "/api/v1/me/settings",
                "{\"savedSearches\":[{\"name\":\"Big trades\",\"path\":\"/s?q=TRD where notional > 1m\"}]}", "X-Drishti-User", "rita");
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
        var json = new ObjectMapper();
        PreferenceStore onA = cluster.node("a").bean(PreferenceStore.class);
        onA.put("rita", "layouts", "trade-view.trade", json.readTree("{\"order\":[\"risk\",\"cashflows\"]}"));
        onA.put("rita", "pivots", "search.trade", json.readTree("{\"rows\":[\"DESK\"],\"values\":[\"NOTIONAL\"]}"));
        onA.put("rita", "alerts", "big-mtm", json.readTree("{\"when\":\"mtm > 1m\"}"));
        PreferenceStore onB = cluster.node("b").bean(PreferenceStore.class);
        assertThat(NetNodes.await(3_000, () -> onB.get("rita", "pivots", "search.trade").isPresent())).isTrue();
        var settings = cluster.call("GET", cluster.node("b").url() + "/api/v1/me/settings", null, "X-Drishti-User", "rita");
        assertThat(settings.body()).contains("Big trades");
        assertThat(onB.get("rita", "layouts", "trade-view.trade")).hasValueSatisfying(v -> assertThat(v.toString()).contains("cashflows"));
        assertThat(onB.get("rita", "alerts", "big-mtm")).as("alerts act: never copied").isEmpty();
        // a change and a delete follow too, the newest winning
        onB.put("rita", "pivots", "search.trade", json.readTree("{\"rows\":[\"BOOK\"]}"));
        assertThat(NetNodes.await(3_000, () -> onA.get("rita", "pivots", "search.trade").map(v -> v.toString().contains("BOOK")).orElse(false))).isTrue();
        onA.delete("rita", "layouts", "trade-view.trade");
        assertThat(NetNodes.await(3_000, () -> onB.get("rita", "layouts", "trade-view.trade").isEmpty())).isTrue();

        // console sign-in sessions follow too (only the hash of the id travels): opened on A, known on B; ended on B, ended on A
        SessionStore sessA = cluster.node("a").bean(SessionStore.class);
        SessionStore sessB = cluster.node("b").bean(SessionStore.class);
        var s1 = sessA.open("rita", java.time.Duration.ofHours(1));
        var s2 = sessA.open("rita", java.time.Duration.ofHours(1));
        assertThat(NetNodes.await(3_000, () -> sessB.find(s1.id()).isPresent() && sessB.find(s2.id()).isPresent())).isTrue();
        assertThat(sessB.find(s1.id()).orElseThrow().user()).isEqualTo("rita");
        sessB.end(s1.id());
        assertThat(NetNodes.await(3_000, () -> sessA.find(s1.id()).isEmpty())).as("a sign-out on B ends the session on A").isTrue();
        assertThat(sessA.find(s2.id())).isPresent();

        // a node that joins later is given what it does not have
        cluster.start("c", NetNodes.PORTS[2]);
        PreferenceStore onC = cluster.node("c").bean(PreferenceStore.class);
        assertThat(NetNodes.await(8_000, () -> onC.get("rita", "pivots", "search.trade").isPresent())).isTrue();
        SessionStore sessC = cluster.node("c").bean(SessionStore.class);
        assertThat(NetNodes.await(8_000, () -> sessC.find(s2.id()).isPresent())).as("the sessions standing are given to a node that joins").isTrue();
        assertThat(sessC.find(s1.id())).isEmpty();

        // after A stops (the console falls over to B), the user's state is there
        cluster.stop("a");
        var after = cluster.call("GET", cluster.node("b").url() + "/api/v1/me/settings", null, "X-Drishti-User", "rita");
        assertThat(after.body()).contains("Big trades");
        assertThat(sessB.find(s2.id())).as("the console's sign-in stands on B after A stopped").isPresent();
        assertThat(cluster.node("b").bean(StateSync.class).summary().get("applied")).isNotEqualTo(0L);
    }
}
