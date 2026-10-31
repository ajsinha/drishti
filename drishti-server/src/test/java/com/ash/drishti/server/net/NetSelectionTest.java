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

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * One server per screen (RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms, section 7) on two real servers in one JVM: a Sutra Next screen's selection token names the node
 * that issued it; a click or a panel's children carrying a token issued on A, sent to B, are forwarded by B to A for the caller and
 * answered as A would; once A is gone, B says in words that the selections were reset because server A went away.
 */
class NetSelectionTest {

    static final String SECRET = "a-test-token-secret-at-least-32-bytes!!";
    static final TokenVerifier TOKENS = new TokenVerifier(SECRET, 30);
    static final ObjectMapper JSON = new ObjectMapper();
    static final String DESK = "/api/v1/views/portfolio-trades/BOOK-RATES-2";
    static NetNodes cluster;

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "selection-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        String[] common = {"--drishti.packs.enabled=trading,counterparty-risk", "--drishti.security.enabled=true", "--drishti.security.secret=" + SECRET,
                "--drishti.security.registered-users-only=false", "--drishti.security.roles.full.kinds[0]=*", "--drishti.security.roles.full.raw=true"};
        cluster.start("a", p[0], common);
        cluster.start("b", p[1], common);
        assertThat(NetNodes.await(15_000, () -> cluster.node("b").bean(RunCluster.class).peers().size() == 1)).as("b sees a alive").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static String bearer() {
        return "Bearer " + TOKENS.mint("ann", List.of("full"), 300);
    }

    static java.net.http.HttpResponse<String> click(String node, List<String> selections) throws Exception {
        return cluster.call("POST", cluster.node(node).url() + DESK + "/events?sutra=book-desk",
                JSON.writeValueAsString(Map.of("panel", "trades", "event", "open", "row", 0, "selections", selections)), "Authorization", bearer());
    }

    @Test
    void aTokenIssuedOnAIsUsedThroughBAndOnceAIsGoneTheSelectionsAreReset() throws Exception {
        String a = cluster.node("a").net().nodeId();
        var first = click("a", List.of());
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        String token = JSON.readTree(first.body()).path("selection").path("token").asText();
        assertThat(token).startsWith("tok_" + a + "~");

        var through = click("b", List.of(token));                                  // B does not hold it: forwarded to A, answered there
        assertThat(through.statusCode()).as(through.body()).isEqualTo(200);
        JsonNode r = JSON.readTree(through.body());
        assertThat(r.path("selection").path("token").asText()).startsWith("tok_" + a + "~");
        assertThat(r.path("panels").size()).isPositive();
        var kids = cluster.call("GET", cluster.node("b").url() + DESK + "/panels/trades/children?sutra=book-desk&sel="
                + URLEncoder.encode(token, StandardCharsets.UTF_8), null, "Authorization", bearer());
        assertThat(kids.statusCode()).as(kids.body()).isEqualTo(200);
        assertThat(JSON.readTree(kids.body()).get(0).path("hidden").asBoolean()).isFalse();

        cluster.stop("a");
        assertThat(NetNodes.await(15_000, () -> !cluster.node("b").bean(RunCluster.class).alive(a))).as("b takes a for gone").isTrue();
        var gone = click("b", List.of(token));
        assertThat(gone.statusCode()).isEqualTo(502);
        assertThat(gone.body()).contains("DRS-5052").contains("your selections were reset because server " + a + " went away");
        var fresh = click("b", List.of());                                       // the reader picks again: B issues its own
        assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(200);
        assertThat(JSON.readTree(fresh.body()).path("selection").path("token").asText()).startsWith("tok_" + cluster.node("b").net().nodeId() + "~");
    }
}
