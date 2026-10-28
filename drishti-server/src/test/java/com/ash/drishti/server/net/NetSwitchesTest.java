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

import com.ash.drishti.identity.SwitchLedger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Admin → Commands across Drishti*net (docs/admin/USER_MANAGEMENT.md, "Several servers"): two real servers in one JVM, each with its own SQLite
 * identity database. A command turned off on one is off on the other within a gossip round, written to that node's database, and refused
 * there with {@code 403 DRS-1016}; a node that was down while a change was made loads its old state from its database at start, then
 * catches up from its peer (anti-entropy on rejoin), and both end with the same switches hash.
 */
class NetSwitchesTest {

    static NetNodes cluster;
    static int[] p = NetNodes.PORTS;

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "switches-" + System.nanoTime()));
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of(p[1]));
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0]);
        cluster.start("b", p[1]);
        assertThat(NetNodes.await(15_000, () -> alive("a") == 1 && alive("b") == 1)).as("a and b see each other").isTrue();
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

    static SwitchLedger ledger(String node) {
        return cluster.node(node).bean(SwitchLedger.class);
    }

    /** What each node holds and what it hears of the other's hash (for a failure's message). */
    static String seen() {
        StringBuilder s = new StringBuilder();
        for (String n : List.of("a", "b")) {
            s.append(n).append(": own ").append(ledger(n).hash()).append(", hears ");
            cluster.node(n).net().node().ifPresent(x -> x.table().entries().forEach(m -> s.append(m.nodeId()).append('=').append(m.switchesHash()).append(' ')));
            s.append("; ");
        }
        return s.toString();
    }

    static int change(String node, String name, boolean on) throws Exception {
        return cluster.call("POST", cluster.node(node).url() + "/api/v1/admin/commands",
                "{\"reason\":\"net test\",\"changes\":[{\"scope\":\"command\",\"name\":\"" + name + "\",\"on\":" + on + "}]}",
                "X-Drishti-User", "net-admin").statusCode();
    }

    @Test
    void aSwitchMadeOnOneNodeReachesTheOtherAndARejoiningNodeCatchesUp() throws Exception {
        assertThat(change("a", "TRD", false)).isEqualTo(200);
        assertThat(NetNodes.await(10_000, () -> !ledger("b").on(SwitchLedger.COMMAND, "TRD"))).as("b learns TRD is off: " + seen()).isTrue();
        assertThat(ledger("b").find(SwitchLedger.COMMAND, "TRD")).get().extracting(SwitchLedger.Entry::reason).isEqualTo("net test");
        var refused = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/trade/IRS-48213", null, "X-Drishti-User", "reader");
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("DRS-1016", "TRD is not active on this server");
        assertThat(NetNodes.await(5_000, () -> ledger("a").hash().equals(ledger("b").hash()))).as("the same switches on both").isTrue();
        String list = cluster.call("GET", cluster.node("a").url() + "/api/v1/admin/commands", null, "X-Drishti-User", "net-admin").body();
        assertThat(list).contains("\"inStep\":true").doesNotContain("\"inStep\":false");

        // b goes down; TRD is turned on again on a; b starts on its own database (TRD still off there), then catches up
        cluster.stop("b");
        assertThat(change("a", "TRD", true)).isEqualTo(200);
        cluster.start("b", p[1]);
        assertThat(NetNodes.await(15_000, () -> ledger("b").on(SwitchLedger.COMMAND, "TRD"))).as("b catches up on rejoining").isTrue();
        assertThat(ledger("b").history(SwitchLedger.COMMAND, "TRD", 10)).hasSize(2);
        assertThat(NetNodes.await(5_000, () -> ledger("a").hash().equals(ledger("b").hash()))).isTrue();
        var open = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/trade/IRS-48213", null, "X-Drishti-User", "reader");
        assertThat(open.statusCode()).isEqualTo(200);
    }
}
