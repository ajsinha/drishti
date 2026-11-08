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

import com.ash.drishti.server.engines.EngineCluster;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Engines per node in a cluster (ADMIN_GUIDE.md section 9, EngineClusterTest): FRTB-SA runs on a only; b's digest knows it, b's about
 * answer offers it (available in the cluster, not here) and b's FRTB-SA request is answered by a, the same figures, the node named; an
 * administrator on b turns a's FRTB-SA off (signed, written by a to its own file, audited on both); then b answers 404 DRS-1020 within a
 * gossip round or two.
 */
class EngineClusterTest {

    static NetNodes cluster;
    static int[] p = NetNodes.PORTS;

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "engines-" + System.nanoTime()));
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of(p[1]));
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0], "--drishti.engines.dir=" + root.resolve("a/engines"));
        cluster.start("b", p[1], "--drishti.engines.dir=" + root.resolve("b/engines"), "--drishti.engines.frtb-sa.enabled=false");
        assertThat(NetNodes.await(15_000, () -> engines("b").runs("frtb-sa").isPresent())).as("b learns a runs FRTB-SA").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static EngineCluster engines(String node) {
        return cluster.node(node).bean(EngineCluster.class);
    }

    @Test
    void aRequestForAnEngineAnotherNodeRunsIsAnsweredThereAndAfterItStopsIs404() throws Exception {
        String a = cluster.node("a").net().nodeId();
        String about = cluster.call("GET", cluster.node("b").url() + "/public/about", null).body();
        assertThat(about).contains("{\"id\":\"frtb-sa\",\"title\":\"FRTB-SA\",\"here\":false,\"cluster\":true}");
        String path = "/api/v1/risk/frtb/sa?scope=EXAMPLE";
        var direct = cluster.call("GET", cluster.node("a").url() + path, null, "X-Drishti-User", "reader");
        var routed = cluster.call("GET", cluster.node("b").url() + path, null, "X-Drishti-User", "reader");
        assertThat(routed.statusCode()).isEqualTo(direct.statusCode());
        assertThat(routed.headers().firstValue("X-Drishti-Engine-Node")).hasValue(a);
        assertThat(routed.body()).isEqualTo(direct.body());

        var off = cluster.call("POST", cluster.node("b").url() + "/api/v1/admin/engines",
                "{\"reason\":\"capital on one node only\",\"node\":\"" + a + "\",\"changes\":[{\"engine\":\"frtb-sa\",\"on\":false}]}",
                "X-Drishti-User", "net-admin");
        try {
            assertThat(off.statusCode()).as(off.body()).isEqualTo(200);
            assertThat(off.body()).contains("\"forwardedTo\":\"" + a + "\"", "\"outcome\":\"live\"");
            assertThat(NetNodes.await(10_000, () -> engines("b").runs("frtb-sa").isEmpty())).as("b hears a stopped it").isTrue();
            var gone = cluster.call("GET", cluster.node("b").url() + path, null, "X-Drishti-User", "reader");
            assertThat(gone.statusCode()).isEqualTo(404);
            assertThat(gone.body()).contains("DRS-1020", "\"engine\":\"frtb-sa\"");
        } finally {
            cluster.call("POST", cluster.node("a").url() + "/api/v1/admin/engines",
                    "{\"reason\":\"back\",\"changes\":[{\"engine\":\"frtb-sa\",\"on\":true}]}", "X-Drishti-User", "net-admin");
        }
    }
}
