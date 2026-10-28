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
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * VaR sharded across three real Drishti*net members in one JVM equals VaR on one server, to the cent (VAR_SHARDING.md section 12). One
 * risk lake (the limit-letter fixture of {@link VarFormatEqualityTest}, its scenario PVs and FX, in Delta and the same lines in Iceberg) is
 * read by a reference server (sharding off, Drishti*net off) and by members A, B and C (sharding on: two shards, one per legal entity and
 * desk, two owners each). For every scope of the fixture in USD and EUR, the headline (VaR, ES, the tail, limits, diversification), the
 * contributions of every child, the P&amp;L path and the MGMT tree of every member equal the reference's; then the same on Iceberg; then an
 * intraday batch applied live on every member equals the reference's; the official record of the day is the same rows. On the way: C joins
 * while A and B answer without a single failed or different answer (the handover never leaves a shard without an owner); a partial labelled
 * with another data version is refused (409, {@code DRS-5216}), an unsigned one too (401); one owner of a shard stopping changes nothing (its
 * replica answers); both owners stopping makes a scope that needs the shard's rows a clear {@code 503 DRS-5215} on the member left (a node's
 * figures, held on every member, still answer), and once the membership has settled that member owns the shard and answers it again.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarShardClusterTest {

    static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";
    static final TokenVerifier TOKENS = new TokenVerifier(SECRET, 30);
    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalDate DAY = VarHierarchyFixture.DAY;
    static final List<String> SCOPES = List.of("BANK", "NODE:MGMT~DESK-X", "NODE:MGMT~DESK-Y", "NODE:MGMT~BOOK-1", "NODE:LEGAL~LE-A",
            "NODE:LEGAL~LE-B", "LL:LL-A", "LL:LL-B", "NODE:LL~LL-A/DESK-Y", "TRD:T1", "TRD:T1,T3,T5", "BANK - TRD:T4", "NODE:MGMT~DESK-X + TRD:T5");
    static final String PNL_COLUMNS = "TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,LIMIT_LETTER,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,"
            + "CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,SCENARIO_SET_ID,BATCH_ID,RUN_KIND,"
            + "COMPUTED_AT,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY";
    static final String PV_COLUMNS = "TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,LIMIT_LETTER,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,"
            + "COUNTERPARTY,DIRECTION,QUANTITY,BASE_PV,VALUATION_DATE,BASE_VALUATION_DATE,TRADE_STATUS,MATURITY_DATE,STATUS,SCENARIO_SET_ID,BATCH_ID,"
            + "RUN_KIND,COMPUTED_AT,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS";
    static final List<String> INTRADAY_SCOPES = List.of("BANK", "NODE:MGMT~DESK-X", "NODE:MGMT~DESK-Y", "NODE:MGMT~BOOK-1", "NODE:LEGAL~LE-B",
            "TRD:T1", "TRD:N1", "TRD:P1", "TRD:T3,T5", "BANK - TRD:T4");
    /** Rows of 20 scenarios as float32: 80 bytes; a target of under four rows makes each legal entity and desk a shard of its own. */
    static final String TARGET_MB = "0.0003";
    /** Member C's node id: S1 is pinned to it, so C takes a shard when it joins whatever the hashing gives. */
    static final String C_ID = "n-member-c";
    static NetNodes cluster;
    static Path root;
    static Path delta;
    static Path iceberg;
    static Path intraday;
    static String refUrl;
    static final List<String> MEMBERS = new ArrayList<>(List.of("a", "b"));

    static String rita() {
        return "Bearer " + TOKENS.mint("rita", List.of("risk"), 600);
    }

    /** The risk lake's connector file for one node (as VarFormatEqualityTest's): the plugin, its root, and (Iceberg) the node's own caches. */
    static String riskLake(String plugin, Path lake, Path node) {
        return "plugin: " + plugin + "\n"
                + "kinds: [pnl-vector, scenario-set, risk-hierarchy, portfolio, var-limit, scenario-pv, fx-scenario, fx-spot]\n"
                + "settings:\n"
                + "  root: " + lake + "\n"
                + "  domain: risk\n"
                + "  refresh-seconds: 1\n"
                + "  engine: native\n"
                + ("iceberg".equals(plugin) ? "  id-map-disk.dir: " + node.resolve("cache/idmaps") + "\n  doc-disk-cache.dir: "
                        + node.resolve("cache/docs") + "\n" : "")
                + "  layout.pnl-vector.columns: " + PNL_COLUMNS + "\n"
                + "  layout.pnl-vector.vectors: PNL\n"
                + "  layout.scenario-pv.columns: " + PV_COLUMNS + "\n"
                + "  layout.scenario-pv.vectors: SCENARIO_PV\n";
    }

    static void connectors(Path node, String plugin, Path lake) throws Exception {
        Path c = Files.createDirectories(node.resolve("connectors"));
        Files.writeString(c.resolve("risk-lake.yaml"), riskLake(plugin, lake, node));
    }

    static List<String> common(Path node) {
        List<String> a = new ArrayList<>(List.of("--drishti.packs.enabled=finance", "--drishti.sources.plugins.demo.enabled=false",
                "--drishti.sources.connectors.finance-lake.enabled=false", "--drishti.sources.connectors.var-positions.plugin=var-positions",
                "--drishti.sources.connectors.var-positions.kinds[0]=position", "--drishti.sources.routes.pnl-vector=risk-lake",
                "--drishti.sources.routes.position=var-positions", "--drishti.sources.routes.scenario-set=risk-lake",
                "--drishti.sources.routes.risk-hierarchy=risk-lake", "--drishti.sources.routes.portfolio=risk-lake",
                "--drishti.sources.routes.var-limit=risk-lake", "--drishti.sources.routes.scenario-pv=risk-lake",
                "--drishti.sources.routes.fx-scenario=risk-lake", "--drishti.sources.routes.fx-spot=risk-lake",
                "--drishti.var.positions.rules[0].name=securities", "--drishti.var.positions.rules[0].groups[0]=Fixed income",
                "--drishti.var.positions.rules[0].key[0]=SECURITY_ID", "--drishti.var.hierarchies.LL.title=Limit letters",
                "--drishti.var.hierarchies.LL.levels=LIMIT_LETTER > DESK", "--drishti.var.hierarchies.LL.word=LL",
                "--drishti.var.hierarchies.LL.limits=var-limit", "--drishti.var.official.on-start=false",
                "--drishti.var.official.root=" + node.resolve("official"), "--drishti.var.official.currencies[0]=EUR",
                "--drishti.var.threads=2", "--drishti.var.tier.close-grace-seconds=0", "--drishti.var.currencies[0]=USD",
                "--drishti.security.enabled=true", "--drishti.security.secret=" + SECRET, "--drishti.security.registered-users-only=false",
                "--drishti.security.roles.risk.kinds[0]=*", "--drishti.security.roles.risk.raw=true"));
        return a;
    }

    static List<String> sharded(Path node) {
        List<String> a = common(node);
        a.addAll(List.of("--drishti.var.sharding.enabled=true", "--drishti.var.sharding.target-shard-mb=" + TARGET_MB,
                "--drishti.var.sharding.replicas=2", "--drishti.var.sharding.stable-seconds=15", "--drishti.var.sharding.grace-seconds=3",
                "--drishti.var.sharding.rebalance-seconds=1", "--drishti.var.sharding.pins.S1=" + C_ID));
        return a;
    }

    @BeforeAll
    static void start() throws Exception {
        root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "var-shards-" + System.nanoTime()));
        delta = root.resolve("lake/delta");
        iceberg = root.resolve("lake/iceberg");
        intraday = root.resolve("lake/intraday");
        IcebergMirror.into(iceberg, () -> {
            VarHierarchyFixture.write(delta);
            VarPvFixture.write(delta, DAY);
            return null;
        });
        VarIntradayLake.morning(intraday, DAY);
        // the morning run without T9 (its BOOK-2 sits under a second legal entity: one book under two shards, a date that is not shardable)
        List<Map<String, Object>> morning = new ArrayList<>(VarIntradayLake.morningVectors(DAY));
        morning.removeIf(r -> "T9".equals(r.get("TRADE_ID")));
        VarIntradayLake.writeVectors(intraday, morning, "overwrite-dates");
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of(), List.of(), List.of(p[0], p[1]));
        // C's id fixed and S1 pinned to it (sharded()): node ids are random, and with three members and two owners a shard C owns none of
        // the two shards about one run in nine, which the join test could not tell from a handover that never happened
        Files.writeString(root.resolve("c/net/node.yaml"), "node-id: " + C_ID + "\n", java.nio.file.StandardOpenOption.APPEND);
        for (String n : List.of("ref", "a", "b", "c")) {
            connectors(root.resolve(n), "delta", delta);
        }
        cluster = new NetNodes(root);
        var ref = cluster.start("ref", 0, common(root.resolve("ref")).toArray(String[]::new));
        refUrl = "http://" + NetNodes.HOST + ":" + ref.ctx().getEnvironment().getProperty("local.server.port");
        cluster.start("a", p[0], sharded(root.resolve("a")).toArray(String[]::new));
        cluster.start("b", p[1], sharded(root.resolve("b")).toArray(String[]::new));
        assertThat(NetNodes.await(30_000, () -> MEMBERS.stream().allMatch(n -> members(n) == 2))).as("A and B see each other's shards state").isTrue();
        assertThat(NetNodes.await(60_000, VarShardClusterTest::ownersAgree)).as("A and B agree on the owners").isTrue();
    }

    /** Every member reports the same shard map and the same owners, two for each shard. */
    static boolean ownersAgree() {
        try {
            JsonNode first = null;
            for (String n : MEMBERS) {
                JsonNode m = map(n);
                for (JsonNode s : m.path("shards")) {
                    if (s.path("owners").size() != 2) {
                        return false;
                    }
                }
                if (first != null && !(first.path("digest").equals(m.path("digest")) && owners(first).equals(owners(m)))) {
                    return false;
                }
                first = m;
            }
            return first != null;
        } catch (Exception e) {
            return false;
        }
    }

    static List<String> owners(JsonNode map) {
        List<String> out = new ArrayList<>();
        map.path("shards").forEach(s -> out.add(s.path("owners").toString()));
        return out;
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    /** The members a node's VaR shards see sharding on. */
    static int members(String node) {
        try {
            JsonNode s = get(cluster.node(node).url() + "/api/v1/var/shards");
            int n = 0;
            for (JsonNode m : s.path("members")) {
                n += m.path("on").asBoolean() ? 1 : 0;
            }
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    static String url(String node) {
        return node.equals("ref") ? refUrl : cluster.node(node).url();
    }

    static HttpResponse<String> call(String base, String path) throws Exception {
        return cluster.call("GET", base + path, null, "Authorization", rita());
    }

    static JsonNode get(String full) throws Exception {
        HttpResponse<String> r = cluster.call("GET", full, null, "Authorization", rita());
        assertThat(r.statusCode()).as(full + ": " + r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    static String q(String scope, String ccy) {
        return "scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) + "&asOf=" + DAY + "&ccy=" + ccy;
    }

    /** What must equal one server's: the headline, the contributions, the P&amp;L path and the tree, every figure to the cent. */
    static JsonNode figures(String node, String scope, String ccy) throws Exception {
        String base = url(node);
        ObjectNode out = JSON.createObjectNode();
        JsonNode sc = get(base + "/api/v1/var/scope?" + q(scope, ccy));
        out.set("rejections", sc.at("/provenance/rejections"));
        out.set("positions", sc.at("/provenance/positions"));
        out.set("trades", sc.at("/provenance/trades"));
        out.set("scope", cents(strip(sc)));
        out.set("contributions", cents(strip(get(base + "/api/v1/var/scope/contributions?" + q(scope, ccy)))));
        out.set("path", cents(strip(get(base + "/api/v1/var/scope/path?" + q(scope, ccy) + "&size=20"))));
        return out;
    }

    static Map<String, JsonNode> all(String node, List<String> scopes) throws Exception {
        Map<String, JsonNode> out = new TreeMap<>();
        for (String ccy : List.of("USD", "EUR")) {
            for (String scope : scopes) {
                out.put(scope + " in " + ccy, figures(node, scope, ccy));
            }
            out.put("tree in " + ccy, cents(strip(get(url(node) + "/api/v1/var/tree?hierarchy=MGMT&asOf=" + DAY + "&ccy=" + ccy))));
        }
        return out;
    }

    /** Every member's figures equal the reference server's. */
    static void sameAsOneServer(List<String> scopes, String what) throws Exception {
        Map<String, JsonNode> ref = all("ref", scopes);
        assertThat(ref.get("BANK in USD").at("/scope/var").asDouble()).as(what + ": the reference answers").isPositive();
        for (String n : MEMBERS) {
            Map<String, JsonNode> mine = all(n, scopes);
            assertThat(mine.keySet()).isEqualTo(ref.keySet());
            ref.forEach((k, v) -> assertThat(mine.get(k)).as(what + ": " + k + " on member " + n).isEqualTo(v));
        }
    }

    static JsonNode strip(JsonNode n) {
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(x -> a.add(strip(x)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!k.equals("provenance") && !k.contains("version") && !k.equals("inputs") && !k.equals("vectors") && !k.equals("computedat")
                        && !k.equals("elapsedms") && !k.equals("computems") && !k.equals("took") && !k.equals("loadedms") && !k.equals("loadms")) {
                    o.set(e.getKey(), strip(e.getValue()));
                }
            }
            return o;
        }
        return n;
    }

    /** The figures of an answer only (its numbers, to the cent), without what names a run (ids, versions, times). */
    static JsonNode numbers(JsonNode n) {
        if (n.isNumber()) {
            return n.isIntegralNumber() ? n : JSON.getNodeFactory().numberNode(Math.round(n.asDouble() * 100) / 100.0);
        }
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(x -> a.add(numbers(x)));
            return a;
        }
        ObjectNode o = JSON.createObjectNode();
        if (n.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!k.contains("version") && !k.endsWith("at") && !k.endsWith("ms") && (e.getValue().isContainerNode() || e.getValue().isNumber())) {
                    o.set(e.getKey(), numbers(e.getValue()));
                }
            }
        }
        return o;
    }

    static JsonNode cents(JsonNode n) {
        if (n.isNumber() && !n.isIntegralNumber()) {
            return JSON.getNodeFactory().numberNode(Math.round(n.asDouble() * 100) / 100.0);
        }
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(x -> a.add(cents(x)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                o.set(e.getKey(), cents(e.getValue()));
            }
            return o;
        }
        return n;
    }

    /** The date's shard map and owners as a member reports them. */
    static JsonNode map(String node) throws Exception {
        return get(url(node) + "/api/v1/var/shards?asOf=" + DAY).path("map");
    }

    @Test
    @Order(1)
    void twoMembersEqualOneServerToTheCentInUsdAndEurAndAgreeOnTheMapAndOwners() throws Exception {
        sameAsOneServer(SCOPES, "Delta");
        JsonNode a = map("a");
        JsonNode b = map("b");
        assertThat(a.path("shards").size()).as("one shard per legal entity and desk").isEqualTo(2);
        assertThat(a.path("digest").asText()).isEqualTo(b.path("digest").asText());
        for (int i = 0; i < 2; i++) {
            assertThat(a.path("shards").get(i).path("owners")).as("S" + i + "'s owners").isEqualTo(b.path("shards").get(i).path("owners"));
            assertThat(a.path("shards").get(i).path("owners").size()).isEqualTo(2);
        }
        JsonNode sc = get(url("a") + "/api/v1/var/scope?" + q("TRD:T1,T3,T5", "USD"));
        assertThat(sc.at("/provenance/shards/shards").asInt()).isEqualTo(2);
        assertThat(sc.at("/provenance/shards/shardsAsked").asInt()).as("T1, T3 and T5 sit in both shards").isEqualTo(2);
        assertThat(sc.at("/provenance/tier").asText()).isNotBlank();
        JsonNode node = get(url("b") + "/api/v1/var/scope?" + q("NODE:MGMT~DESK-X", "USD"));
        assertThat(node.at("/provenance/shards/shardsAsked").asInt()).as("a node: its aggregate, on every member").isZero();
    }

    @Test
    @Order(2)
    void aThirdMemberJoinsAndTakesItsShardsWithoutOneFailedOrDifferentAnswer() throws Exception {
        JsonNode expect = figures("ref", "TRD:T1,T3,T5", "USD");
        JsonNode expectEur = figures("ref", "BANK - TRD:T4", "EUR");
        int[] p = NetNodes.PORTS;
        cluster.start("c", p[2], sharded(root.resolve("c")).toArray(String[]::new));
        MEMBERS.add("c");
        long end = System.currentTimeMillis() + 60_000;
        int asked = 0;
        boolean took = false;
        while (System.currentTimeMillis() < end) {
            for (String n : List.of("a", "b")) {
                assertThat(figures(n, "TRD:T1,T3,T5", "USD")).as("during the handover, on " + n).isEqualTo(expect);
                assertThat(figures(n, "BANK - TRD:T4", "EUR")).as("during the handover, on " + n).isEqualTo(expectEur);
                asked += 2;
            }
            JsonNode m = map("a");
            took = false;
            boolean settled = true;
            String c = cluster.node("c").net().nodeId();
            for (JsonNode s : m.path("shards")) {
                List<String> owners = new ArrayList<>();
                s.path("owners").forEach(x -> owners.add(x.asText()));
                List<String> ready = new ArrayList<>();
                s.path("ready").forEach(x -> ready.add(x.asText()));
                took |= owners.contains(c) && ready.contains(c);
                settled &= ready.containsAll(owners) && owners.containsAll(ready);    // the old owner has released it
            }
            if (took && settled && members("a") == 3 && ownersAgree()) {
                break;
            }
            Thread.sleep(300);
        }
        assertThat(took).as("C owns and holds a shard after the membership settled").isTrue();
        assertThat(asked).isPositive();
        sameAsOneServer(SCOPES, "three members");
        int byOwners = 0;
        for (String n : MEMBERS) {                                     // with three members, each misses a shard: its rows come from an owner
            JsonNode sc = get(url(n) + "/api/v1/var/scope?" + q("TRD:T1,T3,T5 + TRD:T2", "USD"));
            assertThat(sc.at("/provenance/shards/shardsAsked").asInt()).isEqualTo(2);
            byOwners += sc.at("/provenance/shards/answeredByOwners").asInt();
        }
        assertThat(byOwners).as("partial sums answered by other members").isPositive();
    }

    @Test
    @Order(3)
    void onIcebergEveryMemberEqualsOneServer() throws Exception {
        for (String n : List.of("ref", "a", "b", "c")) {
            use(n, "iceberg", iceberg);
        }
        sameAsOneServer(SCOPES, "Iceberg");
    }

    @Test
    @Order(4)
    void anIntradayBatchAppliedLiveEqualsOneServer() throws Exception {
        for (String n : List.of("ref", "a", "b", "c")) {
            use(n, "delta", intraday);
        }
        sameAsOneServer(INTRADAY_SCOPES, "intraday morning");
        String at = DAY + "T1";
        String before = cluster.node("a").bean(VarService.class).loader().version(DAY);
        VarIntradayLake.writeVectors(intraday, List.of(
                VarIntradayLake.vector(DAY, "T1", "BOOK-1", "DESK-X", "LE-A", "USD", 150, VarLakeFixture.scale(VarLakeFixture.U, 150), "AMENDED",
                        "INTRADAY", "B-I1", at + "0:00:00Z"),
                VarIntradayLake.vector(DAY, "T2", "BOOK-1", "DESK-X", "LE-A", "USD", -40, null, "CANCELLED", "INTRADAY", "B-I1", at + "0:00:00Z"),
                VarIntradayLake.vector(DAY, "N1", "BOOK-3", "DESK-Y", "LE-B", "USD", -30, VarLakeFixture.scale(VarLakeFixture.V, -30), "NEW",
                        "INTRADAY", "B-I1", at + "0:00:00Z")), "append");
        VarIntradayLake.writePvs(intraday, List.of(
                VarIntradayLake.pv(DAY, "P1", "EUR", 1200, VarPvFixture.plus(1200, VarLakeFixture.U, 30), "INTRADAY", "B-I2", at + "1:00:00Z", "HS-20")),
                "append");
        String[] was = before.split("\\|", 2);                           // both tables seen new: P&L vectors, then |scenario-pv:...
        for (String n : List.of("ref", "a", "b", "c")) {
            VarService svc = cluster.node(n).bean(VarService.class);
            assertThat(NetNodes.await(30_000, () -> {
                String[] now = svc.loader().version(DAY).split("\\|", 2);
                return !now[0].equals(was[0]) && (was.length < 2 || now.length == 2 && !now[1].equals(was[1]));
            })).as(n + " sees both batches (P&L vectors and scenario PVs)").isTrue();
            cluster.node(n).bean(VarOfficial.class).onLoad("pnl-vector", DAY);
            cluster.node(n).bean(VarOfficial.class).onLoad("scenario-pv", DAY);
        }
        converged(List.of("B-I1", "B-I2"));
        sameAsOneServer(INTRADAY_SCOPES, "intraday batches");
        assertThat(get(url("a") + "/api/v1/var/scope?" + q("TRD:N1", "USD")).path("var").asDouble()).as("the new trade counts").isNotZero();
        // the official record of the day (the morning run, every node of every hierarchy) is the same rows on a member and on one server
        Map<String, JsonNode> official = new TreeMap<>();
        for (String n : List.of("ref", "a")) {
            cluster.node(n).bean(VarOfficial.class).capture(DAY);
            official.put(n, numbers(get(url(n) + "/api/v1/var/official?asOf=" + DAY).path("nodes")));
        }
        assertThat(official.get("ref").size()).as("the official record has every node").isGreaterThan(5);
        assertThat(official.get("a")).as("the official record").isEqualTo(official.get("ref"));
    }

    @Test
    @Order(5)
    void aPartialOfAnotherDataVersionOrWithoutTheClusterKeyIsRefused() throws Exception {
        VarShards a = cluster.node("a").bean(VarService.class).shards();
        com.ash.drishti.compute.shard.ShardMap m = a.map(DAY);
        String body = new String(VarShardRows.request(DAY, "USD", m, 0, "0".repeat(64), com.ash.drishti.compute.vector.OrdinalSet.of(0)),
                StandardCharsets.UTF_8);
        var signed = cluster.node("a").net().http().send(cluster.node("b").url(), "POST", "/api/v1/var/partial",
                Map.of("Content-Type", "application/json", com.ash.drishti.net.security.NetHeaders.HOP, "1"), body.getBytes(StandardCharsets.UTF_8),
                java.time.Duration.ofSeconds(20));
        assertThat(signed.status()).as(signed.text()).isEqualTo(409);
        assertThat(signed.text()).contains("DRS-5216");
        HttpResponse<String> plain = cluster.call("POST", cluster.node("b").url() + "/api/v1/var/partial", body, "Authorization", rita());
        assertThat(plain.statusCode()).as(plain.body()).isEqualTo(401);
        assertThat(plain.body()).contains("DRS-5051");
    }

    @Test
    @Order(6)
    void anOwnerDownChangesNothingBothDownIsAClearErrorThenTheShardMovesAndAnswersAgain() throws Exception {
        JsonNode m = map("a");
        Map<String, String> ids = new LinkedHashMap<>();
        for (String n : MEMBERS) {
            ids.put(cluster.node(n).net().nodeId(), n);
        }
        JsonNode s0 = m.path("shards").get(0);
        List<String> owners = new ArrayList<>();
        s0.path("owners").forEach(x -> owners.add(ids.get(x.asText())));
        String left = MEMBERS.stream().filter(n -> !owners.contains(n)).findFirst().orElseThrow();
        String scope = "TRD:T1,T3,T5";                                      // rows of both shards
        JsonNode expect = figures("ref", scope, "USD");
        cluster.stop(owners.get(0));
        MEMBERS.remove(owners.get(0));
        assertThat(figures(left, scope, "USD")).as("one owner of S0 down: its replica answers").isEqualTo(expect);
        cluster.stop(owners.get(1));
        MEMBERS.remove(owners.get(1));
        HttpResponse<String> none = null;
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            none = call(url(left), "/api/v1/var/scope?" + q(scope, "USD"));
            if (none.statusCode() != 200) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(none.statusCode()).as(none.body()).isEqualTo(503);
        assertThat(none.body()).contains("DRS-5215").contains("no partial VaR");
        double bank = get(refUrl + "/api/v1/var/scope?" + q("BANK", "USD")).path("var").asDouble();
        assertThat(get(url(left) + "/api/v1/var/scope?" + q("BANK", "USD")).path("var").asDouble()).as("a node's figures: held here")
                .isEqualTo(bank);
        // once the members see both owners gone, the rebalancing rounds put the date together again: the replacement cannot be completed
        // without S0, and the date loaded keeps answering a node's figures (it is never dropped to be put together again)
        VarShards ls = cluster.node(left).bean(VarService.class).shards();
        List<String> gone = new ArrayList<>();
        s0.path("owners").forEach(x -> gone.add(x.asText()));
        assertThat(NetNodes.await(60_000, () -> ls.members().stream().noneMatch(p -> gone.contains(p.node()) && p.routable())))
                .as("the member left sees both owners of S0 gone").isTrue();
        long rounds = System.currentTimeMillis() + 3_000;                  // past the rounds' wait before a date is put together again
        while (System.currentTimeMillis() < rounds) {
            ls.tick();
            Thread.sleep(100);
        }
        Throwable why = ls.rebuild(DAY).handle((ok, e) -> e).join();
        assertThat(why).as("a replacement of the date needs S0").isNotNull();
        assertThat(String.valueOf(why)).contains("DRS-5215");
        assertThat(get(url(left) + "/api/v1/var/scope?" + q("BANK", "USD")).path("var").asDouble())
                .as("a node's figures while the date cannot be put together again").isEqualTo(bank);
        HttpResponse<String> still = call(url(left), "/api/v1/var/scope?" + q(scope, "USD"));
        assertThat(still.statusCode()).as(still.body()).isEqualTo(503);
        assertThat(still.body()).contains("DRS-5215");
        JsonNode[] back = {null};
        assertThat(NetNodes.await(90_000, () -> {
            try {
                HttpResponse<String> r = call(url(left), "/api/v1/var/scope/contributions?" + q(scope, "USD"));
                if (r.statusCode() != 200) {
                    return false;
                }
                back[0] = figures(left, scope, "USD");
                return true;
            } catch (Exception e) {
                return false;
            }
        })).as("the member left owns S0 once the membership settled").isTrue();
        assertThat(back[0]).isEqualTo(expect);
    }

    /**
     * Waits as a client would until every server answers with the day's batches: the provenance of the bank (a node: its aggregate) and of
     * a set summed from the shards' rows, in USD and in EUR, lists {@code batches}. A member whose signal was a full run (the batches were
     * all applied by the signal before) puts the date together again meanwhile; no fixed sleep.
     */
    static void converged(List<String> batches) throws Exception {
        List<String> nodes = new ArrayList<>(List.of("ref"));
        nodes.addAll(MEMBERS);
        String[] last = {""};
        boolean done = NetNodes.await(60_000, () -> {
            for (String n : nodes) {
                for (String ccy : List.of("USD", "EUR")) {
                    for (String scope : List.of("BANK", "BANK - TRD:T4")) {
                        try {
                            HttpResponse<String> r = call(url(n), "/api/v1/var/scope?" + q(scope, ccy));
                            JsonNode got = r.statusCode() == 200 ? JSON.readTree(r.body()).at("/provenance/intraday/batches") : null;
                            if (got == null || !got.toString().equals(JSON.valueToTree(batches).toString())) {
                                last[0] = n + " " + scope + " in " + ccy + ": " + r.statusCode() + " " + got;
                                return false;
                            }
                        } catch (Exception e) {
                            last[0] = n + ": " + e;
                            return false;
                        }
                    }
                }
            }
            return true;
        });
        assertThat(done).as("every server answers with the batches " + batches + "; last seen " + last[0]).isTrue();
    }

    /** Switches a node's risk lake to {@code plugin} at {@code lake} and waits until the day is read from it. */
    static void use(String n, String plugin, Path lake) throws Exception {
        var node = cluster.node(n);
        VarService svc = node.bean(VarService.class);
        String before = svc.loader().version(DAY);
        node.bean(com.ash.drishti.packs.ConnectorFiles.class).write("risk-lake", riskLake(plugin, lake, node.root()));
        node.bean(com.ash.drishti.server.connectors.ConnectorManager.class).scan();
        assertThat(NetNodes.await(60_000, () -> !svc.loader().version(DAY).equals(before))).as(n + " reads the day from " + plugin).isTrue();
        svc.reload();
    }
}
