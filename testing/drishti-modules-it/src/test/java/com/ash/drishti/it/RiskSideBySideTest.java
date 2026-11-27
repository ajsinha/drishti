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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * R2's side by side (docs/plans/R2_CUT_PLAN.md, "Switching a route"): one packaged server with Drishti Risk's JARs in {@code lib/engines}
 * (the folder {@code drishti.risk.jars}), bound by {@code config/engines/market-risk.yaml} and {@code ccr.yaml}. Each switched route is
 * asked on both paths ({@link SideBySide}): the answers' fields must be equal, and the engine's exact figures must equal the in-tree raw
 * bits (the golden's doubles, which VarGoldenMatrixTest holds exactly). Skipped when no folder of Risk JARs is given.
 */
class RiskSideBySideTest {

    /**
     * True on the heap tier ({@code -Dsbs.tier=heap}): the in-tree rows are float64, so a currency other than USD (the book converted first,
     * owner decision 2026-11-27, R2_CUT_PLAN 6.6 (2)) is the engine's to the bit. On the default mapped tier the in-tree book is converted
     * from its float32 rows (MC-12): those answers are counted and printed, not asserted.
     */
    static final boolean HEAP = "heap".equals(System.getProperty("sbs.tier", ""));

    /** The differences asserted on this tier: all of them on the heap tier, the USD ones on the mapped tier. */
    static java.util.List<String> asserted(java.util.List<String> differences) {
        return HEAP ? differences : differences.stream().filter(x -> !x.contains("/EUR ")).toList();
    }

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("side-by-side");
    static PackagedServer server;
    static JsonNode golden;

    @BeforeAll
    static void start() throws Exception {
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(PackagedServer.REPO.resolve(PackagedVarGoldenTest.GOLDEN)))) {
            golden = PackagedServer.JSON.readTree(in);
        }
        Path lake = FOLDER.resolve("lake");                            // its own lake: the golden test builds the other one at the same time
        PackagedServer.buildLake(lake, PackagedVarGoldenTest.ONLY);
        Path work = FOLDER.resolve("server");
        deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        String tier = System.getProperty("sbs.tier", "");               // -Dsbs.tier=heap: the in-tree rows as float64 (the default mapped tier keeps them float32)
        server = tier.isBlank() ? PackagedServer.start(work, lake, "2026-09-30") : PackagedServer.start(work, lake, "2026-09-30", java.util.Map.of("DRISHTI_VAR_TIER", tier, "DRISHTI_VAR_MEMORY_MB", "900"));
        long end = System.currentTimeMillis() + 180_000;               // the engine answers once its day is read: wait for it, once
        while (true) {
            try {
                server.get("/api/v1/risk/var/scope?scope=NODE:MGMT~BANK&asOf=2026-09-30&path=engine");
                break;
            } catch (IllegalStateException e) {
                if (System.currentTimeMillis() > end) {
                    throw e;
                }
                Thread.sleep(3000);
            }
        }
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Parametric VaR (R2_CUT_PLAN 6.6 (4), owner decision 2026-11-27): the headline's block of every golden node (3 dates x 509/250 x
     * USD/EUR) and the tree's cell of every node (3 dates x USD/EUR x 3 hierarchies), in-tree against the engine's {@code node-pvar}:
     * every number within relative 1e-12 (the normal quantile's last bits; the whole-unit figures equal), every word equal. Historical
     * VaR and ES stay the bit-exact proof of the other tests.
     */
    @Test
    void theParametricFiguresOfEveryGoldenNodeAreWithinRelative1e12OnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        int[] numbers = new int[2];                                     // [compared, within 1e-12]
        int blocks = 0;
        Iterator<String> cases = golden.fieldNames();
        while (cases.hasNext()) {
            String c = cases.next();
            String[] k = c.split("/");
            Iterator<String> ids = golden.get(c).get("nodes").fieldNames();
            while (ids.hasNext()) {
                String id = ids.next();
                String what = c + " NODE:" + id;
                JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/scope?scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8)
                        + "&asOf=" + k[0] + "&ccy=" + k[2] + "&window=" + k[1]);
                parametricSame(sbs, numbers, what + " parametric", both[0].path("parametric"), both[1].path("parametric"));
                blocks++;
            }
        }
        for (String date : List.of("2026-09-28", "2026-09-29", "2026-09-30")) {
            for (String ccy : List.of("USD", "EUR")) {
                for (String h : List.of("LEGAL", "LL", "MGMT")) {
                    JsonNode[] both = sbs.both(server, date + "/" + ccy + " tree " + h, "/api/v1/risk/var/tree?hierarchy=" + h + "&asOf=" + date
                            + "&ccy=" + ccy);
                    Map<String, JsonNode> engine = new java.util.HashMap<>();
                    both[1].path("nodes").forEach(n -> engine.put(n.path("id").asText(), n));
                    for (JsonNode n : both[0].path("nodes")) {
                        if (n.has("parametric")) {
                            JsonNode e = engine.get(n.path("id").asText());
                            parametricSame(sbs, numbers, date + "/" + ccy + " tree " + n.path("id").asText() + " parametric", n.path("parametric"),
                                    e == null ? PackagedServer.JSON.missingNode() : e.path("parametric"));
                            blocks++;
                        }
                    }
                }
            }
        }
        System.out.println("side by side, parametric (tier " + System.getProperty("sbs.tier", "mapped") + "): " + blocks + " blocks (headline "
                + "and tree cells) on both paths; " + numbers[0] + " numbers compared, " + numbers[1] + " within relative 1e-12; differences "
                + sbs.differences.size() + " (USD " + sbs.differences.stream().filter(x -> x.contains("/USD")).count() + "); first: "
                + sbs.differences.stream().limit(8).map(x -> x.substring(0, Math.min(300, x.length()))).toList() + "; fields: "
                + sbs.differences.stream().map(x -> x.replaceAll("^.* parametric\\.?([a-zA-Z]*).*$", "$1")).distinct().toList());
        assertThat(blocks).as("parametric blocks compared").isGreaterThan(2000);
        assertThat(asserted(sbs.differences)).as("parametric figures that differ beyond relative 1e-12 or in words").isEmpty();
    }

    /**
     * The history of a scope without official records ({@code GET /scope/history}, the replay): every golden node and every scope kind of
     * {@link #everyScopeKindIsTheSameOnBothPaths}, 3 dates x USD/EUR, in-tree against the engine's {@code scope-backtest}: every row
     * (date, VaR, ES, P&amp;L, exception), the backtest (days, exceptions, zone; the likelihood ratios and p-values within relative 1e-12)
     * and the words. A node with official records (two days or more) is answered in-tree only: counted.
     */
    @Test
    void theReplayedHistoryOfEveryGoldenNodeAndScopeKindIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        int[] numbers = new int[2];
        int official = 0;
        int refused = 0;
        int answers = 0;
        for (String c : List.of("2026-09-28/509/USD", "2026-09-28/509/EUR", "2026-09-29/509/USD", "2026-09-29/509/EUR", "2026-09-30/509/USD",
                "2026-09-30/509/EUR")) {
            String[] k = c.split("/");
            List<String> scopes = new ArrayList<>();
            golden.get(c).get("nodes").fieldNames().forEachRemaining(id -> scopes.add("NODE:" + id));
            scopes.addAll(List.of("BANK - DESK:RATES", "DIV:MARKETS - BOOK:COMM-1", "LE:NY - DESK:RATES", "PORT:PF-USD-RATES", "PORT:PF-EUR-ALL",
                    "PORT:PF-LISTED", "PORT:PF-ASHA-UNWIND", "PORT:PF-EUR-ALL - DESK:FX"));
            scopes.addAll(adHoc(k[0]));
            for (String scope : scopes) {
                String what = c + " " + scope;
                String q = "/api/v1/risk/var/scope/history?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) + "&asOf=" + k[0] + "&ccy=" + k[2];
                JsonNode inTree;
                try {
                    inTree = server.get(q + "&path=in-tree");
                } catch (IllegalStateException e) {                    // refused in-tree: the engine path must refuse it too
                    try {
                        server.get(q + "&path=engine");
                        sbs.differences.add(what + ": in-tree refused (" + e.getMessage() + "), the engine answered");
                    } catch (IllegalStateException fine) {
                        refused++;
                    }
                    continue;
                }
                JsonNode engine;
                try {
                    engine = server.get(q + "&path=engine");
                } catch (IllegalStateException e) {
                    if (e.getMessage() != null && e.getMessage().contains("official records")) {
                        official++;
                        continue;
                    }
                    throw e;
                }
                answers++;
                com.fasterxml.jackson.databind.node.ObjectNode e2 = engine.deepCopy();
                e2.remove("path");
                parametricSame(sbs, numbers, what + " history", inTree, e2);
            }
        }
        System.out.println("side by side, history (replay, tier " + System.getProperty("sbs.tier", "mapped") + "): " + answers + " answers on both "
                + "paths (" + official + " nodes with official records: in-tree only; " + refused + " refused on both); " + numbers[0] + " numbers compared, " + numbers[1]
                + " within relative 1e-12; differences " + sbs.differences.size() + " (USD " + sbs.differences.stream().filter(x -> x.contains("/USD"))
                .count() + "); first: " + sbs.differences.stream().limit(8).map(x -> x.substring(0, Math.min(300, x.length()))).toList());
        assertThat(answers).as("history answers compared").isGreaterThan(500);
        // the mapped tier: a node's figures are its stored aggregate (USD asserted); other scopes sum float32 rows (MC-12): counted
        assertThat(asserted(sbs.differences).stream().filter(x -> HEAP || x.contains(" NODE:")).toList())
                .as("history answers that differ between in-tree and engine").isEmpty();
    }

    /** One parametric block on both paths: numbers within relative 1e-12, whole-unit figures and words equal. */
    private static void parametricSame(SideBySide sbs, int[] numbers, String what, JsonNode inTree, JsonNode engine) {
        if (inTree.isMissingNode() || engine.isMissingNode() || inTree.isNull() != engine.isNull()) {
            if (inTree.isMissingNode() != engine.isMissingNode()) {
                sbs.differences.add(what + ": in-tree " + (inTree.isMissingNode() ? "none" : "a block") + ", engine "
                        + (engine.isMissingNode() ? "none" : "a block"));
            }
            return;
        }
        if (inTree.isObject()) {
            List<String> names = new ArrayList<>();
            inTree.fieldNames().forEachRemaining(names::add);
            List<String> theirs = new ArrayList<>();
            engine.fieldNames().forEachRemaining(theirs::add);
            if (!names.equals(theirs)) {
                sbs.differences.add(what + " fields: in-tree " + names + ", engine " + theirs);
            }
            for (String f : names) {
                parametricSame(sbs, numbers, what + "." + f, inTree.get(f), engine.path(f));
            }
        } else if (inTree.isArray()) {
            if (inTree.size() != engine.size()) {
                sbs.differences.add(what + " size: in-tree " + inTree.size() + ", engine " + engine.size());
                return;
            }
            for (int i = 0; i < inTree.size(); i++) {
                parametricSame(sbs, numbers, what + "[" + i + "]", inTree.get(i), engine.get(i));
            }
        } else if (inTree.isFloatingPointNumber() && engine.isNumber()) {
            numbers[0]++;
            double a = inTree.asDouble();
            double b = engine.asDouble();
            if (a == b || Math.abs(a - b) <= 1e-12 * Math.max(Math.abs(a), Math.abs(b))) {
                numbers[1]++;
            } else {
                sbs.differences.add(what + ": in-tree " + a + ", engine " + b + " (relative " + Math.abs(a - b) / Math.max(Math.abs(a), Math.abs(b)) + ")");
            }
        } else if (!inTree.equals(engine)) {
            if (inTree.isNumber()) {
                numbers[0]++;
            }
            sbs.differences.add(what + ": in-tree " + inTree + ", engine " + engine);
        } else if (inTree.isNumber()) {
            numbers[0]++;
            numbers[1]++;
        }
    }

    @Test
    void theVarHeadlineOfEveryGoldenNodeIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        Iterator<String> cases = golden.fieldNames();
        while (cases.hasNext()) {
            String c = cases.next();
            String[] k = c.split("/");
            Iterator<String> ids = golden.get(c).get("nodes").fieldNames();
            while (ids.hasNext()) {
                String id = ids.next();
                String what = c + " NODE:" + id;
                JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/scope?scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8)
                        + "&asOf=" + k[0] + "&ccy=" + k[2] + "&window=" + k[1] + "&exact=true",
                        // limit use is the date's VaR over the limit on both paths (a window asked recomputes VaR and ES only); in
                        // another currency the in-tree route converts the date's VaR through the per-currency totals, the golden (and
                        // the engine) through the converted book, a few units in the last place apart: there the golden is the reference
                        k[2].equals("USD") ? new String[] {"trades", "var", "es", "limit", "limitUse"} : new String[] {"trades", "var", "es", "limit"});
                JsonNode g = golden.get(c).get("nodes").get(id);
                JsonNode official = golden.get(k[0] + "/509/" + k[2]).get("nodes").get(id);
                if (!k[2].equals("USD") && official.hasNonNull("limit") && official.path("limit").asDouble() != 0) {
                    sbs.bits(what + " LIMIT_USE", official.path("var").asDouble() / official.path("limit").asDouble(), both[1].path("limitUse").asDouble());
                }
                sbs.bits(what + " VAR", g.path("var").asDouble(), both[1].path("varExact").asDouble());
                sbs.bits(what + " ES", g.path("es").asDouble(), both[1].path("esExact").asDouble());
            }
        }
        System.out.println("side by side, VaR headline: " + sbs.compared + " node answers on both paths; " + sbs.sameBits
                + " engine VaR and ES figures equal to the golden (in-tree) raw bits; first differences: " + sbs.differences.stream().limit(10).toList()
                + "; first raw-bit differences: " + sbs.bitDifferences.stream().limit(10).toList());
        assertThat(sbs.compared).as("node answers compared").isGreaterThan(1500);
        assertThat(asserted(sbs.differences)).as("route answers that differ between in-tree and engine").isEmpty();
        assertThat(sbs.bitDifferences).as("engine figures whose raw bits differ from the in-tree golden").isEmpty();
    }

    /**
     * The P&amp;L path ({@code GET /scope/path}) of every golden node with child nodes, each date and currency, on the configured window:
     * the ranked table whole (509 rows a page), the worst 25, the series, the scenario dates, the histogram, VaR rank and ES count, and the
     * part that lost most on each scenario equal on both paths; the engine's VaR and ES equal to the in-tree headline's raw bits. A node
     * without child nodes (its parts are positions) is refused on the engine path in words, and counted.
     */
    @Test
    void thePnlPathOfEveryGoldenNodeIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        int refused = 0;
        Iterator<String> cases = golden.fieldNames();
        java.util.Set<String> done = new java.util.HashSet<>();
        while (cases.hasNext()) {
            String c = cases.next();
            String[] k = c.split("/");
            if (!done.add(k[0] + "/" + k[2])) {
                continue;                                               // the path is on the configured window: one per date and currency
            }
            Iterator<String> ids = golden.get(c).get("nodes").fieldNames();
            while (ids.hasNext()) {
                String id = ids.next();
                String what = k[0] + "/" + k[2] + " NODE:" + id;
                String q = "scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8) + "&asOf=" + k[0] + "&ccy=" + k[2];
                try {
                    server.get("/api/v1/risk/var/scope/path?" + q + "&size=1&parametric=false&path=engine");
                } catch (IllegalStateException e) {
                    assertThat(e.getMessage()).as(what).contains("path=in-tree");
                    refused++;
                    continue;
                }
                JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/scope/path?" + q + "&size=509&parametric=false&exact=true",
                        "canonical", "currency", "var", "es", "k", "esCount", "histogram", "worst", "series", "dates", "scenarioDates", "page",
                        "pageSize", "total", "rows");
                JsonNode head = server.get("/api/v1/risk/var/scope?" + q + "&window=" + both[0].path("total").asInt() + "&exact=true&path=in-tree");
                sbs.bits(what + " VAR", head.path("varExact").asDouble(), both[1].path("varExact").asDouble());   // the date's VaR, in-tree
                sbs.bits(what + " ES", head.path("esExact").asDouble(), both[1].path("esExact").asDouble());
            }
        }
        System.out.println("side by side, P&L path: differences USD " + sbs.differences.stream().filter(x -> x.contains("/USD ")).count() + ", EUR "
                + sbs.differences.stream().filter(x -> x.contains("/EUR ")).count() + " (fields: " + sbs.differences.stream()
                .map(x -> x.replaceAll("^\\S+ \\S+ (\\S+): .*$", "$1")).distinct().toList() + "); USD: " + sbs.differences.stream()
                .filter(x -> x.contains("/USD ")).limit(5).toList());
        System.out.println("side by side, P&L path: " + sbs.compared + " node paths on both paths (" + refused + " leaf nodes refused on the "
                + "engine path); " + sbs.sameBits + " engine VaR and ES figures equal to the in-tree raw bits; first differences: "
                + sbs.differences.stream().limit(5).map(x -> x.substring(0, Math.min(400, x.length()))).toList()
                + "; first raw-bit differences: " + sbs.bitDifferences.stream().limit(10).toList());
        assertThat(sbs.compared).as("node paths compared").isGreaterThan(100);
        // both paths sum the book converted first (R2_CUT_PLAN 6.6 (2)): every field equal on the heap tier, USD on both tiers
        assertThat(asserted(sbs.differences)).as("path answers that differ between in-tree and engine").isEmpty();
        assertThat(asserted(sbs.bitDifferences)).as("engine figures whose raw bits differ from the in-tree").isEmpty();
    }

    /**
     * The pre-deal what-if ({@code POST /what-if}) on every golden node, each date, USD: an unwind of a booked trade, half a desk's
     * P&amp;L as a hedge, and a P&amp;L priced elsewhere; before, after, change, every item's standalone, incremental and component VaR and
     * the limit block equal on both paths (the limit uses are the VaRs over the limit: equal doubles are equal VaR bits).
     */
    @Test
    void theWhatIfOfEveryGoldenNodeIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        Iterator<String> cases = golden.fieldNames();
        java.util.Set<String> done = new java.util.HashSet<>();
        while (cases.hasNext()) {
            String c = cases.next();
            String[] k = c.split("/");
            if (!k[2].equals("USD") || !done.add(k[0])) {
                continue;
            }
            String trade = adHoc(k[0]).get(2);                          // a position (the sample's trades are sent whole, position grain)
            int width = server.get("/api/v1/risk/var/scope/path?scope=NODE:MGMT~BANK&asOf=" + k[0] + "&size=1&parametric=false&path=in-tree")
                    .path("series").size();
            StringBuilder priced = new StringBuilder();
            for (int j = 0; j < width; j++) {
                priced.append(j == 0 ? "" : ",").append((j % 7 - 3) * 125_000.5);
            }
            Iterator<String> ids = golden.get(c).get("nodes").fieldNames();
            while (ids.hasNext()) {
                String id = ids.next();
                String body = "{\"scope\":\"NODE:" + id + "\",\"items\":[{\"copyOf\":\"" + trade + "\",\"factor\":-1},"
                        + "{\"copyOf\":\"DESK:FX\",\"factor\":0.5},{\"label\":\"new swap\",\"pnl\":[" + priced + "],\"factor\":2}]}";
                sbs.bothPost(server, k[0] + " NODE:" + id, "/api/v1/risk/var/what-if?asOf=" + k[0], body, "canonical", "businessDate",
                        "currency", "confidence", "esConfidence", "before", "after", "change", "items", "limit");
            }
        }
        System.out.println("side by side, what-if: " + sbs.compared + " node what-ifs on both paths; first differences: "
                + sbs.differences.stream().limit(5).map(x -> x.substring(0, Math.min(500, x.length()))).toList());
        assertThat(sbs.compared).as("what-ifs compared").isGreaterThan(100);
        assertThat(sbs.differences).as("what-if answers that differ between in-tree and engine").isEmpty();
    }

    /**
     * The pre-deal what-if on every scope kind that is not a node (expressions, system baskets, ad-hoc trade and position lists, a
     * counterparty), each date, USD: the engine's what-if over the scope's row set; before, after, change, every item and the limit block
     * (a system basket's own) equal on both paths. The row sums are the scope kinds': equal on the heap tier ({@code -Dsbs.tier=heap}); the
     * mapped tier keeps rows as float32 (MC-12), so there the differences are counted, not asserted.
     */
    @Test
    void theWhatIfOnEveryScopeKindIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        int refused = 0;
        java.util.Set<String> dates = new java.util.TreeSet<>();
        golden.fieldNames().forEachRemaining(c -> dates.add(c.split("/")[0]));
        for (String date : dates) {
            List<String> scopes = new ArrayList<>(List.of("BANK - DESK:RATES", "DESK:RATES + DESK:FX", "LE:NY - DESK:RATES", "DESK:RATES & LE:NY",
                    "PORT:PF-USD-RATES", "PORT:PF-EUR-ALL", "PORT:PF-SHORT-RISK", "PORT:PF-LISTED", "PORT:PF-NETTED-FLOW", "PORT:PF-EUR-ALL - DESK:FX"));
            List<String> adHoc = adHoc(date);
            scopes.addAll(adHoc);
            String trade = adHoc.get(2);
            for (String scope : scopes) {
                String body = "{\"scope\":\"" + scope + "\",\"items\":[{\"copyOf\":\"" + trade + "\",\"factor\":-1},"
                        + "{\"copyOf\":\"DESK:FX\",\"factor\":0.5}]}";
                try {
                    server.post("/api/v1/risk/var/what-if?asOf=" + date + "&path=in-tree", body);
                } catch (IllegalStateException e) {                     // refused in-tree (a scope with no trades): refused on the engine path too
                    refused++;
                    try {
                        server.post("/api/v1/risk/var/what-if?asOf=" + date + "&path=engine", body);
                        sbs.differences.add(date + " " + scope + ": refused in-tree (" + e.getMessage() + "), answered on the engine path");
                    } catch (IllegalStateException expected) {
                        // both refuse
                    }
                    continue;
                }
                sbs.bothPost(server, date + " " + scope, "/api/v1/risk/var/what-if?asOf=" + date, body, "canonical", "businessDate", "currency",
                        "before", "after", "change", "items", "limit");
            }
        }
        System.out.println("side by side, what-if on scope kinds (tier " + System.getProperty("sbs.tier", "mapped") + "): " + sbs.compared
                + " what-ifs on both paths (" + refused + " refused on both); differences " + sbs.differences.size() + "; first: " + sbs.differences.stream().limit(6)
                .map(x -> x.substring(0, Math.min(400, x.length()))).toList());
        assertThat(sbs.compared).as("what-ifs compared").isGreaterThan(30);
        if ("heap".equals(System.getProperty("sbs.tier", ""))) {
            assertThat(sbs.differences).as("what-if answers that differ between in-tree and engine").isEmpty();
        }
    }

    /**
     * Every scope kind through the host's row set (ENGINE_INTEGRATION_DESIGN 18.1, EN-68): scope expressions, the system baskets (nodes,
     * rules over the positions' columns, named lists), ad-hoc lists of 1 and 50 trades and of 1 and 20 positions, a counterparty, a legal
     * entity; each date, window and currency of the goldens; the route's fields equal and the engine's VaR and ES equal to the in-tree raw
     * bits ({@code exact=true}).
     */
    @Test
    void everyScopeKindIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        List<String> scopes = new ArrayList<>(List.of("BANK - DESK:RATES", "DESK:RATES + DESK:FX", "DIV:MARKETS - BOOK:COMM-1", "LE:LDN", "LE:NY - DESK:RATES",
                "DESK:RATES & LE:NY", "PORT:PF-USD-RATES", "PORT:PF-EUR-ALL", "PORT:PF-SHORT-RISK", "PORT:PF-GOVT-BONDS", "PORT:PF-LISTED",
                "PORT:PF-OPTIONS", "PORT:PF-FX-CROSSES", "PORT:PF-LARGE-LONGS", "PORT:PF-ASHA-UNWIND", "PORT:PF-NETTED-FLOW", "PORT:PF-HEDGE-PAIRS",
                "PORT:PF-TOP-SECURITIES", "PORT:PF-COMM-BOOK-1", "PORT:PF-EUR-ALL - DESK:FX"));
        Iterator<String> cases = golden.fieldNames();
        String firstDate = null;
        while (cases.hasNext()) {
            String c = cases.next();
            String[] k = c.split("/");
            if (firstDate == null || !firstDate.equals(k[0])) {
                firstDate = k[0];
                scopes.removeIf(x -> x.startsWith("TRD:") || x.startsWith("POS:") || x.startsWith("CPTY:"));
                scopes.addAll(adHoc(k[0]));
            }
            for (String scope : scopes) {
                String what = c + " " + scope;
                boolean usd = k[2].equals("USD");
                JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/scope?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
                        + "&asOf=" + k[0] + "&ccy=" + k[2] + "&window=" + k[1] + "&exact=true",
                        usd ? new String[] {"trades", "var", "es", "limit", "limitUse"} : new String[] {"trades", "var", "es", "limit"});
                String node = both[1].path("node").asText(null);
                if (!usd && node != null) {                             // a node whole in another currency: the golden is the reference
                    JsonNode g = golden.get(c).get("nodes").get(node);
                    sbs.bits(what + " VAR", g.path("var").asDouble(), both[1].path("varExact").asDouble());
                    sbs.bits(what + " ES", g.path("es").asDouble(), both[1].path("esExact").asDouble());
                } else {
                    sbs.bits(what + " VAR", both[0].path("varExact").asDouble(), both[1].path("varExact").asDouble());
                    sbs.bits(what + " ES", both[0].path("esExact").asDouble(), both[1].path("esExact").asDouble());
                    if (!usd && both[0].path("limitUse").isNumber()) {
                        sbs.bits(what + " LIMIT_USE", both[0].path("limitUse").asDouble(), both[1].path("limitUse").asDouble());
                    }
                }
            }
        }
        System.out.println("side by side, scope kinds: differences by currency: USD " + sbs.differences.stream().filter(x -> x.contains("/USD ")).count()
                + ", EUR " + sbs.differences.stream().filter(x -> x.contains("/EUR ")).count() + "; raw-bit differences USD "
                + sbs.bitDifferences.stream().filter(x -> x.contains("/USD ")).count() + ", EUR " + sbs.bitDifferences.stream().filter(x -> x.contains("/EUR ")).count()
                + "; USD differences: " + sbs.differences.stream().filter(x -> x.contains("/USD ")).limit(10).toList());
        System.out.println("side by side, scope kinds: " + sbs.compared + " answers on both paths; " + sbs.sameBits
                + " engine VaR and ES figures equal to the in-tree raw bits; first differences: " + sbs.differences.stream().limit(10).toList()
                + "; first raw-bit differences: " + sbs.bitDifferences.stream().limit(10).toList());
        assertThat(sbs.compared).as("scope answers compared").isGreaterThanOrEqualTo(300);
        if (HEAP) {                                                     // the mapped tier's row sums are float32 rows (MC-12): counted above
            assertThat(sbs.differences).as("route answers that differ between in-tree and engine").isEmpty();
            assertThat(sbs.bitDifferences).as("engine figures whose raw bits differ from the in-tree").isEmpty();
        }
    }

    /**
     * The tree ({@code GET /tree}) of every hierarchy, each golden date and currency, on the offered lens: the nodes in the same order with
     * the same fields (name, level, parent, depth, VaR, ES, limit, limit use and status, trades, expandable, the (Blank) note), the
     * hierarchies, titles and system baskets; the engine's VaR and ES of every golden node equal to the golden's raw bits.
     */
    @Test
    void theTreeOfEveryHierarchyIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        java.util.Set<String> done = new java.util.HashSet<>();
        int nodes = 0;
        Iterator<String> cases = golden.fieldNames();
        while (cases.hasNext()) {
            String[] k = cases.next().split("/");
            if (!done.add(k[0] + "/" + k[2])) {
                continue;
            }
            JsonNode g = golden.path(k[0] + "/509/" + k[2]).path("nodes");
            JsonNode first = server.get("/api/v1/risk/var/tree?asOf=" + k[0] + "&ccy=" + k[2] + "&parametric=false&path=in-tree");
            for (JsonNode h : first.path("hierarchies")) {
                String what = k[0] + "/" + k[2] + " " + h.asText();
                JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/tree?asOf=" + k[0] + "&ccy=" + k[2] + "&hierarchy=" + h.asText()
                        + "&parametric=false&exact=true", "hierarchy", "currency", "hierarchies", "hierarchyInfo", "title", "businessDate", "portfolios");
                List<String> a = new ArrayList<>();
                List<String> b = new ArrayList<>();
                both[0].path("nodes").forEach(n -> a.add(n.path("id").asText()));
                both[1].path("nodes").forEach(n -> b.add(n.path("id").asText()));
                if (!a.equals(b)) {
                    sbs.differences.add(what + " node order: in-tree " + a.size() + ", engine " + b.size() + ": " + SideBySide.first(
                            both[0].path("nodes"), both[1].path("nodes")));
                    continue;
                }
                for (int i = 0; i < a.size(); i++) {
                    JsonNode x = both[0].path("nodes").get(i);
                    JsonNode y = both[1].path("nodes").get(i);
                    nodes++;
                    for (String f : List.of("id", "scope", "name", "level", "parent", "depth", "var", "es", "limit", "limitUse", "limitStatus",
                            "limitComparable", "trades", "hidden", "expandable", "blank", "blankNote")) {
                        // EUR limit use: the in-tree tree divides the node's VaR by its limit as the headline does (the open EUR decision)
                        if (!java.util.Objects.equals(String.valueOf(x.get(f)), String.valueOf(y.get(f)))) {
                            sbs.differences.add(what + " " + a.get(i) + " " + f + ": in-tree " + x.get(f) + ", engine " + y.get(f));
                        }
                    }
                    if (g.has(a.get(i))) {
                        sbs.bits(what + " " + a.get(i) + " VAR", g.get(a.get(i)).path("var").asDouble(), y.path("varExact").asDouble());
                        sbs.bits(what + " " + a.get(i) + " ES", g.get(a.get(i)).path("es").asDouble(), y.path("esExact").asDouble());
                    }
                }
            }
        }
        System.out.println("side by side, tree: " + sbs.compared + " trees, " + nodes + " node rows on both paths; " + sbs.sameBits
                + " engine VaR and ES figures equal to the golden raw bits; differences " + sbs.differences.size() + " (USD "
                + sbs.differences.stream().filter(x -> x.contains("/USD ")).count() + "; fields " + sbs.differences.stream()
                .map(x -> x.replaceAll("^\\S+ \\S+ \\S+ (\\S+):.*$", "$1")).distinct().toList() + "); first: "
                + sbs.differences.stream().limit(8).toList() + "; first raw-bit differences: " + sbs.bitDifferences.stream().limit(10).toList());
        assertThat(nodes).as("tree rows compared").isGreaterThan(500);
        assertThat(asserted(sbs.differences)).as("tree answers that differ (EUR too on the heap tier)").isEmpty();
        assertThat(sbs.bitDifferences).as("engine figures whose raw bits differ from the golden").isEmpty();
    }

    /**
     * The contributions ({@code GET /scope/contributions}, the default parts) of every golden node with child nodes, each date and currency:
     * every row (standalone VaR and ES, component VaR and ES, share, incremental VaR) and the sums equal on both paths, and the headline's
     * parts block (children, their kind, the standalone sum, the diversification); VaR and ES equal to the golden's raw bits. A node whose
     * parts are positions is refused on the engine path in words, and counted.
     */
    @Test
    void theContributionsOfEveryGoldenNodeAreTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        java.util.Set<String> done = new java.util.HashSet<>();
        int refused = 0;
        Iterator<String> cases = golden.fieldNames();
        while (cases.hasNext()) {
            String[] k = cases.next().split("/");
            if (!done.add(k[0] + "/" + k[2])) {
                continue;
            }
            JsonNode g = golden.path(k[0] + "/509/" + k[2]).path("nodes");
            Iterator<String> ids = g.fieldNames();
            while (ids.hasNext()) {
                String id = ids.next();
                String what = k[0] + "/" + k[2] + " NODE:" + id;
                String q = "scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8) + "&asOf=" + k[0] + "&ccy=" + k[2];
                try {
                    server.get("/api/v1/risk/var/scope/contributions?" + q + "&parametric=false&path=engine");
                } catch (IllegalStateException e) {
                    assertThat(e.getMessage()).as(what).contains("path=in-tree");
                    refused++;
                    continue;
                }
                JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/scope/contributions?" + q + "&parametric=false&exact=true",
                        "canonical", "currency", "by", "var", "es", "sumStandalone", "sumComponent", "diversification", "truncated", "total",
                        "shown", "rows");
                sbs.bits(what + " VAR", g.get(id).path("var").asDouble(), both[1].path("varExact").asDouble());
                sbs.bits(what + " ES", g.get(id).path("es").asDouble(), both[1].path("esExact").asDouble());
                sbs.both(server, what + " headline", "/api/v1/risk/var/scope?" + q, "children", "childKind",
                        "sumStandalone", "diversification");
            }
        }
        System.out.println("side by side, contributions: " + sbs.compared + " answers (contributions and the headline's parts) on both paths ("
                + refused + " nodes of positions refused on the engine path); " + sbs.sameBits + " engine VaR and ES figures equal to the golden "
                + "raw bits; differences USD " + sbs.differences.stream().filter(x -> x.contains("/USD ")).count() + ", EUR "
                + sbs.differences.stream().filter(x -> x.contains("/EUR ")).count() + "; first: " + sbs.differences.stream().limit(6)
                .map(x -> x.substring(0, Math.min(400, x.length()))).toList() + "; first raw-bit differences: "
                + sbs.bitDifferences.stream().limit(10).toList());
        assertThat(sbs.compared).as("answers compared").isGreaterThan(100);
        // both paths sum the book converted first (R2_CUT_PLAN 6.6 (2)): EUR equal too on the heap tier
        assertThat(asserted(sbs.differences)).as("answers that differ").isEmpty();
        assertThat(sbs.bitDifferences).as("raw bits that differ from the golden").isEmpty();
    }

    /**
     * The headline of every golden node through the other lenses ({@code GET /scope} with {@code horizon=10d} by the square root of 10 and
     * by overlapping sums, {@code set=stressed} at 1 and 10 days), each date (USD; 10-day also EUR): VaR, ES, scenarios, trades, limit and
     * limit use (null where the limit is set for another lens) equal on both paths, the engine's VaR and ES equal to the in-tree raw bits.
     */
    @Test
    void theHorizonsAndTheStressSetAreTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        java.util.Set<String> dates = new java.util.TreeSet<>();
        golden.fieldNames().forEachRemaining(c -> dates.add(c.split("/")[0]));
        java.util.Map<String, Boolean> stress = new java.util.HashMap<>();
        List<String[]> lenses = List.of(new String[] {"USD", "horizon=10d"}, new String[] {"EUR", "horizon=10d"},
                new String[] {"USD", "horizon=10d&method=sum"}, new String[] {"USD", "set=stressed"}, new String[] {"USD", "set=stressed&horizon=10d"});
        for (String date : dates) {
            Iterator<String> ids = golden.path(date + "/509/USD").path("nodes").fieldNames();
            while (ids.hasNext()) {
                String id = ids.next();
                for (String[] l : lenses) {
                    String what = date + "/" + l[0] + " " + l[1] + " NODE:" + id;
                    if (l[1].startsWith("set=stressed") && !stress.computeIfAbsent(date, d -> stressSet(d))) {
                        continue;                                       // the date holds no stress scenario set: refused on both paths
                    }
                    JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/scope?scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8)
                            + "&asOf=" + date + "&ccy=" + l[0] + "&" + l[1] + "&exact=true", "currency", "var", "es", "scenarios", "horizon", "set",
                            "trades", "limit", "limitComparable", "limitUse");
                    sbs.bits(what + " VAR", both[0].path("varExact").asDouble(), both[1].path("varExact").asDouble());
                    sbs.bits(what + " ES", both[0].path("esExact").asDouble(), both[1].path("esExact").asDouble());
                }
            }
        }
        System.out.println("side by side, horizons and the stress set: " + sbs.compared + " node answers on both paths; " + sbs.sameBits
                + " engine VaR and ES figures equal to the in-tree raw bits; differences " + sbs.differences.size() + " (USD "
                + sbs.differences.stream().filter(x -> x.contains("/USD ")).count() + "); first: " + sbs.differences.stream().limit(8)
                .map(x -> x.substring(0, Math.min(300, x.length()))).toList() + "; first raw-bit differences: "
                + sbs.bitDifferences.stream().limit(8).toList());
        assertThat(sbs.compared).as("answers compared").isGreaterThan(1000);
        assertThat(asserted(sbs.differences)).as("answers that differ (EUR too on the heap tier)").isEmpty();
        assertThat(asserted(sbs.bitDifferences)).as("raw bits that differ").isEmpty();
    }

    /** True when the date has a stress scenario set (the in-tree stressed headline of the firm answers). */
    private static boolean stressSet(String date) {
        try {
            server.get("/api/v1/risk/var/scope?scope=NODE:MGMT~BANK&asOf=" + date + "&set=stressed&path=in-tree");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Ad-hoc lists of the date: 1 and 50 trades, 1 and 20 positions (from the in-tree contributions), and a counterparty. */
    private static List<String> adHoc(String date) throws Exception {
        List<String> positions = ids("DESK:RATES", "position", date, "POS:");
        assertThat(positions).as("positions of DESK:RATES on " + date).isNotEmpty();
        List<String> trades = new ArrayList<>();                        // a desk's parts are positions: its trades are asked position by position
        for (int i = 0; i < positions.size() && trades.size() < 50; i++) {
            List<String> t = ids("POS:" + positions.get(i), "trade", date, "TRD:");
            if (t.isEmpty() && positions.get(i).indexOf('~') > 0) {     // a position of one trade answers itself: <book>~<trade id>
                t = List.of(positions.get(i).substring(positions.get(i).lastIndexOf('~') + 1));
            }
            trades.addAll(t);
        }
        assertThat(trades).as("trades of DESK:RATES on " + date).isNotEmpty();
        List<String> out = new ArrayList<>();
        out.add("TRD:" + trades.get(0));
        out.add("TRD:" + String.join(",", trades.subList(0, Math.min(50, trades.size()))));
        out.add("POS:" + positions.get(0));
        out.add("POS:" + String.join(",", positions.subList(0, Math.min(20, positions.size()))));
        out.add("POS:" + String.join(",", positions.subList(0, Math.min(20, positions.size()))) + " + TRD:" + trades.get(trades.size() - 1));
        List<String> cpty = ids("DESK:RATES", "counterparty", date, "CPTY:");
        if (!cpty.isEmpty()) {
            out.add("CPTY:" + cpty.get(0));
        }
        return out;
    }

    private static List<String> ids(String scope, String by, String date, String prefix) throws Exception {
        JsonNode a;
        try {
            a = server.get("/api/v1/risk/var/scope/contributions?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) + "&by=" + by
                    + "&asOf=" + date);
        } catch (IllegalStateException e) {
            if (prefix.equals("CPTY:")) {
                return List.of();                                       // a grouping the scope does not offer
            }
            throw e;
        }
        List<String> out = new ArrayList<>();
        if (!a.path("rows").isArray() || a.path("rows").isEmpty() || !a.path("rows").get(0).path("scope").asText("").startsWith(prefix)) {
            System.out.println("contributions " + scope + " by " + by + ": " + a.toString().substring(0, Math.min(600, a.toString().length())));
        }
        for (JsonNode r : a.path("rows")) {
            String s = r.path("scope").asText("");
            if (s.startsWith(prefix) && s.indexOf(' ') < 0 && s.indexOf(',') < 0) {
                out.add(s.substring(prefix.length()));
            }
        }
        return out;
    }

    static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var s = Files.walk(p)) {
                for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(x);
                }
            }
        }
    }
}
