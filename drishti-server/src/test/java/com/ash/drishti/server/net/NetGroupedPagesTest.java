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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Grouped pages across the cluster (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 8) on three real servers in one JVM: a book's page (the derived kind book-pnl,
 * grouped from trades) whose trades are split three ways, one trade on two nodes, is gathered on B from every node: every aggregate is what
 * one node holding all the trades computes (checked against such a node at the end), the shared trade counted once; a field C masks stays
 * masked on B though B does not mask it, and a field B masks is masked there; a role limited to two nodes gathers from those two only; with C
 * stopped the page is partial and names C.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetGroupedPagesTest {

    static final String TOKEN_SECRET = "net-grouped-token-secret-0123456789abcdef";
    static final TokenVerifier TOKENS = new TokenVerifier(TOKEN_SECRET, 30);
    static final ObjectMapper JSON = new ObjectMapper();
    static NetNodes cluster;
    static Path root;
    /** Every trade of book BOOK-X, on whichever node (SHARED-1 once): id, MTM, NOTIONAL, CURRENCY. */
    static final List<Object[]> BOOK_X = new ArrayList<>();
    static JsonNode gathered;

    static final String CONNECTOR = """
            plugin: derived
            kinds: [book-pnl]
            settings:
              refresh: 1s
              book-pnl:
                from: trade
                group-by: $.BOOK
                id-field: BOOK
                members: TRADE_IDS
                fields:
                  TRADE_COUNT: count
                  MTM: sum $.MTM
                  LARGEST_NOTIONAL: max $.NOTIONAL
                  SMALLEST_NOTIONAL: min $.NOTIONAL
                  AVG_NOTIONAL: avg $.NOTIONAL
                  CURRENCIES: distinct $.CURRENCY
                  FIRST_TRADE: first $.TRADE_ID
                rows:
                  TRADE: $.TRADE_ID
                  MTM: $.MTM
            """;

    static String trade(String id, String book, long mtm, long notional, String ccy) {
        return "{\"TRADE_ID\":\"" + id + "\",\"BOOK\":\"" + book + "\",\"MTM\":" + mtm + ",\"NOTIONAL\":" + notional + ",\"CURRENCY\":\"" + ccy
                + "\",\"LEGAL_ENTITY\":\"LE-NY\"}";
    }

    /** A node's trades: n of BOOK-X (and one of BOOK-Y), SHARED-1 too when {@code shared}. */
    static List<String> trades(String node, int n, long base, String ccy, boolean shared, boolean record) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String id = "T-" + node.toUpperCase() + "-" + i;
            long mtm = (i % 2 == 0 ? -1 : 1) * (base / 100 + i);
            long notional = base + i * 1_000;
            lines.add(trade(id, "BOOK-X", mtm, notional, ccy));
            if (record) {
                BOOK_X.add(new Object[] {id, mtm, notional, ccy});
            }
        }
        lines.add(trade("Y-" + node.toUpperCase(), "BOOK-Y", 5, 10, ccy));
        if (shared) {
            lines.add(trade("SHARED-1", "BOOK-X", 777, 7_777_777, "USD"));
        }
        return lines;
    }

    static String[] args(Path dir, String... more) {
        List<String> a = new ArrayList<>(List.of("--drishti.security.enabled=true", "--drishti.security.secret=" + TOKEN_SECRET,
                "--drishti.security.registered-users-only=false", "--drishti.sources.plugins.demo.enabled=false",
                "--drishti.sources.plugins.file.settings.root=" + dir.resolve("feeds"), "--drishti.sources.plugins.file.settings.id-field=TRADE_ID"));
        a.addAll(List.of(more));
        return a.toArray(String[]::new);
    }

    static void files(Path dir, List<String> lines) throws Exception {
        Files.createDirectories(dir.resolve("feeds"));
        Files.write(dir.resolve("feeds/trade.jsonl"), lines);
        Files.createDirectories(dir.resolve("connectors"));
        Files.writeString(dir.resolve("connectors/book-totals.yaml"), CONNECTOR);
    }

    @BeforeAll
    static void start() throws Exception {
        root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "grouped-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        files(root.resolve("a"), trades("a", 4, 1_000_000, "EUR", true, true));
        files(root.resolve("b"), trades("b", 3, 3_000_000, "GBP", false, true));
        files(root.resolve("c"), trades("c", 5, 2_000_000, "USD", true, true));
        BOOK_X.add(new Object[] {"SHARED-1", 777L, 7_777_777L, "USD"});
        NetNodes.folder(root.resolve("a/net"), p[0], List.of("region:emea"), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of("region:emea"), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of("region:apac"), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0], args(root.resolve("a")));
        cluster.start("b", p[1], args(root.resolve("b"), "--drishti.security.redact=CURRENCIES"));
        cluster.start("c", p[2], args(root.resolve("c"), "--drishti.security.redact=MTM"));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b", "c").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 3)))
                .as("every node has every digest").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static String bearer(String user, String... roles) {
        return "Bearer " + TOKENS.mint(user, List.of(roles), 300);
    }

    static JsonNode raw(String node, String id, String user, String role) throws Exception {
        var r = cluster.call("GET", cluster.node(node).url() + "/api/v1/entities/book-pnl/" + id + "/raw", null, "Authorization", bearer(user, role));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        return body.has("data") ? body.path("data") : body;
    }

    @Test
    @Order(1)
    void bGathersTheBookFromEveryNodeWithTheSharedTradeOnce() throws Exception {
        JsonNode page = raw("b", "BOOK-X", "rita", "risk");
        long mtm = BOOK_X.stream().mapToLong(t -> (Long) t[1]).sum();
        long max = BOOK_X.stream().mapToLong(t -> (Long) t[2]).max().orElseThrow();
        long min = BOOK_X.stream().mapToLong(t -> (Long) t[2]).min().orElseThrow();
        double avg = BOOK_X.stream().mapToLong(t -> (Long) t[2]).average().orElseThrow();
        assertThat(page.path("memberCount").asLong()).as(page.toString()).isEqualTo(BOOK_X.size());      // 12 own trades + SHARED-1 once
        assertThat(page.path("TRADE_COUNT").asLong()).isEqualTo(BOOK_X.size());
        assertThat(page.path("MTM").asLong()).isEqualTo(mtm);
        assertThat(page.path("LARGEST_NOTIONAL").asLong()).isEqualTo(max);
        assertThat(page.path("SMALLEST_NOTIONAL").asLong()).isEqualTo(min);
        assertThat(page.path("AVG_NOTIONAL").asDouble()).isCloseTo(avg, org.assertj.core.data.Offset.offset(0.001));
        assertThat(page.path("CURRENCIES").toString()).isEqualTo("[\"EUR\",\"GBP\",\"USD\"]");
        assertThat(page.path("FIRST_TRADE").asText()).isEqualTo("SHARED-1");
        assertThat(page.path("TRADE_IDS").size()).isEqualTo(BOOK_X.size());
        assertThat(page.path("rows").size()).isEqualTo(BOOK_X.size());
        assertThat(page.has("partial")).isFalse();
        assertThat(raw("a", "BOOK-X", "rita", "risk")).as("the same page from any node").isEqualTo(page);
        gathered = page;
        var view = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/book-pnl/BOOK-X", null, "Authorization", bearer("rita", "risk"));
        assertThat(view.statusCode()).as(view.body()).isEqualTo(200);
    }

    @Test
    @Order(2)
    void howThisViewWasBuiltNamesTheNodesForAnAdministratorOnly() throws Exception {
        String a = cluster.node("a").net().nodeId();
        String b = cluster.node("b").net().nodeId();
        String c = cluster.node("c").net().nodeId();
        var admin = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/book-pnl/BOOK-X", null, "Authorization", bearer("ada", "admin"));
        assertThat(admin.statusCode()).as(admin.body()).isEqualTo(200);
        String line = NetForwardTest.nodeLine(admin.body());
        assertThat(line).as(admin.body()).startsWith(b + " · scatter-gather from ").contains(a).contains(b).contains(c).doesNotContain("missing");
        var user = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/book-pnl/BOOK-X", null, "Authorization", bearer("rita", "risk"));
        assertThat(NetForwardTest.nodeLine(user.body())).as("a user cannot tell which servers answered").isNull();
        assertThat(user.body()).doesNotContain(a).doesNotContain(c);
        // a trade only C holds, opened on B: built on B from C's document
        var trade = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/trade/T-C-1", null, "Authorization", bearer("ada", "admin"));
        assertThat(trade.statusCode()).as(trade.body()).isEqualTo(200);
        assertThat(NetForwardTest.nodeLine(trade.body())).isEqualTo(b + " · built here, data from " + c);
        var tradeUser = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/trade/T-C-1", null, "Authorization", bearer("rita", "risk"));
        assertThat(NetForwardTest.nodeLine(tradeUser.body())).isNull();
    }

    @Test
    @Order(2)
    void aFieldCMasksStaysMaskedOnBAndAFieldBMasksIsMaskedThere() throws Exception {
        JsonNode tina = raw("b", "BOOK-X", "tina", "trader");
        assertThat(tina.path("MTM").asText()).as("masked on C, so masked in the page B gathers").isEqualTo("•••");
        assertThat(tina.path("CURRENCIES").asText()).as("masked by B itself").isEqualTo("•••");
        assertThat(tina.path("LARGEST_NOTIONAL").asLong()).isEqualTo(gathered.path("LARGEST_NOTIONAL").asLong());
        assertThat(tina.path("memberCount").asLong()).isEqualTo(BOOK_X.size());
        JsonNode rita = raw("b", "BOOK-X", "rita", "risk");
        assertThat(rita.path("MTM").isNumber()).as("risk reads raw values").isTrue();
    }

    @Test
    @Order(3)
    void aRoleLimitedToTwoNodesGathersFromThoseTwoOnly() throws Exception {
        for (String n : List.of("a", "b", "c")) {
            Files.writeString(root.resolve(n + "/net/access.yaml"), "roles:\n  trader:\n    nodes: [region:emea]\n");
            cluster.node(n).net().reload();
        }
        assertThat(NetNodes.await(10_000, () -> {
            try {
                return raw("b", "BOOK-X", "tina", "trader").path("memberCount").asLong() == 4 + 3 + 1;
            } catch (Exception e) {
                return false;
            }
        })).as("A's four, B's three and SHARED-1 (on A): nothing of C's").isTrue();
        assertThat(raw("b", "BOOK-X", "tina", "trader").has("partial")).as("a node the role may not reach is not missing").isFalse();
        assertThat(raw("b", "BOOK-X", "rita", "risk").path("memberCount").asLong()).isEqualTo(BOOK_X.size());
    }

    @Test
    @Order(4)
    void withCStoppedThePageIsPartialAndNamesC() throws Exception {
        String cid = cluster.node("c").net().nodeId();
        cluster.stop("c");
        assertThat(NetNodes.await(6_000, () -> cluster.node("b").net().node().orElseThrow().table().known(cid)
                .map(k -> !k.member().routable()).orElse(true))).isTrue();
        JsonNode page = raw("b", "BOOK-X", "rita", "risk");
        assertThat(page.path("partial").asBoolean()).as(page.toString()).isTrue();
        assertThat(page.path("missing").toString()).contains(cid);
        assertThat(page.path("memberCount").asLong()).isEqualTo(4 + 3 + 1);
        // and the view says so to everyone, not only to administrators: never a silently smaller page
        var view = cluster.call("GET", cluster.node("b").url() + "/api/v1/views/book-pnl/BOOK-X", null, "Authorization", bearer("rita", "risk"));
        assertThat(view.statusCode()).as(view.body()).isEqualTo(200);
        JsonNode pv = JSON.readTree(view.body()).path("provenance");
        assertThat(pv.path("partial").asBoolean()).as(pv.toString()).isTrue();
        assertThat(pv.path("missing").toString()).contains(cid);
    }

    @Test
    @Order(5)
    void oneNodeHoldingEveryTradeBuildsTheSamePage() throws Exception {
        cluster.close();
        Path solo = root.resolve("solo");
        List<String> all = new ArrayList<>();
        all.addAll(trades("a", 4, 1_000_000, "EUR", true, false));
        all.addAll(trades("b", 3, 3_000_000, "GBP", false, false));
        all.addAll(trades("c", 5, 2_000_000, "USD", false, false));
        files(solo, all);
        Files.createDirectories(solo.resolve("net"));                    // no node.yaml: Drishti*net off, one server as before
        cluster = new NetNodes(root);
        cluster.start("solo", NetNodes.PORTS[0], args(solo));
        assertThat(cluster.node("solo").net().running()).isFalse();
        JsonNode one = raw("solo", "BOOK-X", "rita", "risk");
        assertThat(one).as("the page one node with every trade builds").isEqualTo(gathered);
    }
}
