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
        return HEAP ? differences : differences.stream().filter(x -> !x.contains("/EUR ") && !x.startsWith(POSITIONS)).toList();
    }

    /**
     * The tag of a difference in a leaf node's position parts (lane G part 6). On the default mapped tier the in-tree book reads a whole
     * position from the hot file's float32 position rows while the engine sums the position's rows, so those figures may differ in the
     * last bits there (counted and printed, MC-12's float32 view closes it, as EUR/CAD); on the heap tier they are asserted to the bit.
     */
    static final String POSITIONS = "[positions] ";

    /** Tags the differences from {@code from} on as a leaf node's position parts when {@code positions}. */
    static void tagPositions(java.util.List<String> differences, int from, boolean positions) {
        for (int i = from; positions && i < differences.size(); i++) {
            differences.set(i, POSITIONS + differences.get(i));
        }
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
        Path lake = FOLDER.resolve("lake-desk-pnl");                   // with the desk P&L (a lake built without it is kept by its stamp)                            // its own lake: the golden test builds the other one at the same time
        PackagedServer.buildLake(lake, PackagedVarGoldenTest.ONLY + ",market-risk/desk-pnl");   // + the desk P&L: the backtest's input
        Path work = FOLDER.resolve("server");
        deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        String tier = System.getProperty("sbs.tier", "");               // -Dsbs.tier=heap: the in-tree rows as float64 (the default mapped tier keeps them float32)
        server = tier.isBlank() ? PackagedServer.start(work, lake, "2026-09-30") : PackagedServer.start(work, lake, "2026-09-30", java.util.Map.of("DRISHTI_VAR_TIER", tier, "DRISHTI_VAR_MEMORY_MB", "900",
                // the heap tier's books are float64: with the leaf nodes' positions now compared on both paths (lane G part 6) the
                // in-tree side keeps their position vectors too, past the automatic net of a 1.5 GB heap (DRS-9502 on the third date)
                "DRISHTI_VAR_OPEN_HEAP_MB", "700"));
        captureThreeDates(server);
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

    /**
     * Captures the official records of the three dates (oldest first) and waits, polling, until the published records' reader (var-results,
     * Delta, refresh-seconds 10) serves the bank's record of each: the engine's first read of var-history (lazy, once per session) must
     * come after it has seen them.
     */
    static void captureThreeDates(PackagedServer server) throws Exception {
        List<String> published = new ArrayList<>();                     // each record's id: <NODE_ID>@<date>#v<RESULT_VERSION>
        for (String d : List.of("2026-09-28", "2026-09-29", "2026-09-30")) {   // the official records of the three dates, oldest first: a
            JsonNode o = server.post("/api/v1/risk/var/official?asOf=" + d, "{}");   // node's history on 09-29 and 09-30 is its records
            published.add("MGMT~BANK%40" + d + "%23v" + o.path("resultVersion").asLong(1) + "?asOf=" + d);   // (mode official); 09-30 may be the start's
        }
        // the published records' reader (var-results, Delta) checks the table's latest version every refresh-seconds (10): the engine's
        // first read of var-history (lazy, once per session) must come after it has seen the three captures, so wait (no fixed sleep)
        // until the reader serves the bank's record of every captured date (the start may have captured 09-30 before the others)
        long seen = System.currentTimeMillis() + 120_000;
        for (String id : published) {
          while (true) {
            try {
                JsonNode v = server.get("/api/v1/views/var-official/" + id);   // '~' as it is
                if (v.toString().contains("\"$.NODE_NAME\"")) {               // the record itself (its fields), not an empty page
                    break;
                }
                throw new IllegalStateException("not yet: " + v.toString().substring(0, Math.min(300, v.toString().length())));
            } catch (IllegalStateException e) {
                assertThat(System.currentTimeMillis()).as("the published records' reader serves the newest capture within 120 s: " + e.getMessage())
                        .isLessThan(seen);
                Thread.sleep(250);                                        // a poll interval, not a wait for something to happen
            }
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
     * USD/EUR), the tree's cell of every node (3 dates x USD/EUR x 3 hierarchies) and the P&amp;L path's block with its normal curve of
     * every node with child nodes (3 dates x USD/EUR), in-tree against the engine's {@code node-pvar}:
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
        int curves = 0;                                                 // the P&L path's block and normal curve of every node with child nodes
        int parts = 0;                                                  // the contributions' parametric columns of their children
        int matrices = 0;                                               // and the matrix of their children
        for (String date : List.of("2026-09-28", "2026-09-29", "2026-09-30")) {
            for (String ccy : List.of("USD", "EUR")) {
                Iterator<String> ids = golden.get(date + "/509/" + ccy).get("nodes").fieldNames();
                while (ids.hasNext()) {
                    String id = ids.next();
                    String q = "/api/v1/risk/var/scope/path?scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8) + "&asOf=" + date
                            + "&ccy=" + ccy + "&size=1";
                    JsonNode engine;
                    try {
                        engine = server.get(q + "&path=engine");
                    } catch (IllegalStateException e) {
                        assertThat(e.getMessage()).as(date + " NODE:" + id).contains("path=in-tree");   // a leaf node: in-tree
                        continue;
                    }
                    JsonNode inTree = server.get(q + "&path=in-tree");
                    parametricSame(sbs, numbers, date + "/" + ccy + " path NODE:" + id + " parametric", inTree.path("parametric"), engine.path("parametric"));
                    curves++;
                    // the contributions' parametric columns of each child and the totals block (the matrix stays in-tree)
                    String c = "/api/v1/risk/var/scope/contributions?scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8) + "&asOf="
                            + date + "&ccy=" + ccy;
                    JsonNode ce = server.get(c + "&path=engine");
                    JsonNode ci = server.get(c + "&path=in-tree");
                    Map<String, JsonNode> byKey = new java.util.HashMap<>();
                    ce.path("rows").forEach(r -> byKey.put(r.path("key").asText(), r));
                    if (ci.path("rows").size() != ce.path("rows").size()) {
                        sbs.differences.add(date + "/" + ccy + " contributions NODE:" + id + " rows: in-tree " + ci.path("rows").size() + ", engine "
                                + ce.path("rows").size());
                    }
                    for (JsonNode r : ci.path("rows")) {
                        JsonNode e = byKey.getOrDefault(r.path("key").asText(), PackagedServer.JSON.missingNode());
                        com.fasterxml.jackson.databind.node.ObjectNode pi = PackagedServer.JSON.createObjectNode();
                        com.fasterxml.jackson.databind.node.ObjectNode pe = PackagedServer.JSON.createObjectNode();
                        r.fieldNames().forEachRemaining(f -> {
                            if (f.startsWith("p") && f.length() > 1 && Character.isUpperCase(f.charAt(1))
                                    || List.of("rho", "sigma", "mu", "cov", "zCovOverSigma", "describe").contains(f)) {
                                pi.set(f, r.get(f));
                                pe.set(f, e.path(f).isMissingNode() ? PackagedServer.JSON.nullNode() : e.get(f));
                            }
                        });
                        parametricSame(sbs, numbers, date + "/" + ccy + " contributions NODE:" + id + " " + r.path("key").asText(), pi, pe);
                        parts++;
                    }
                    parametricSame(sbs, numbers, date + "/" + ccy + " contributions NODE:" + id + " parametric", ci.path("parametric"),
                            ce.path("parametric"));
                    if (ci.path("rows").size() >= 2) {                  // the parts' covariance and correlation matrix (scope-pmatrix)
                        JsonNode mi = server.get(c + "&matrix=true&path=in-tree").path("parametric").path("matrix");
                        JsonNode me = server.get(c + "&matrix=true&path=engine").path("parametric").path("matrix");
                        parametricSame(sbs, numbers, date + "/" + ccy + " matrix NODE:" + id, mi, me);
                        matrices++;
                    }
                }
            }
        }
        System.out.println("side by side, parametric (tier " + System.getProperty("sbs.tier", "mapped") + "): " + blocks + " blocks (headline "
                + "and tree cells), " + curves + " P&L path blocks with their curves and as many contributions' totals, " + parts
                + " contributions' rows, " + matrices + " matrices on both paths; " + numbers[0] + " numbers compared, " + numbers[1] + " within relative 1e-12; differences "
                + sbs.differences.size() + " (USD " + sbs.differences.stream().filter(x -> x.contains("/USD")).count() + "); first: "
                + sbs.differences.stream().limit(8).map(x -> x.substring(0, Math.min(300, x.length()))).toList() + "; fields: "
                + sbs.differences.stream().map(x -> x.replaceAll("^.* parametric\\.?([a-zA-Z]*).*$", "$1")).distinct().toList());
        assertThat(blocks).as("parametric blocks compared").isGreaterThan(2000);
        assertThat(curves).as("P&L path parametric blocks compared").isGreaterThan(100);
        assertThat(parts).as("contributions' parametric rows compared").isGreaterThan(300);
        assertThat(matrices).as("parametric matrices compared").isGreaterThan(100);
        assertThat(asserted(sbs.differences)).as("parametric figures that differ beyond relative 1e-12 or in words").isEmpty();
    }

    /**
     * The history ({@code GET /scope/history}): every golden node and every scope kind of {@link #everyScopeKindIsTheSameOnBothPaths}, 3
     * dates x USD/EUR, in-tree against the engine: a scope without official records replays ({@code scope-backtest}); a node with official
     * records (USD, two days or more: the captures of the three dates in {@link #start}) is its records ({@code official-history} and
     * {@code official-backtest} over {@code var-history}, the published records). Every row (date, VaR, ES, P&amp;L, exception), the
     * backtest (days, exceptions, zone; the likelihood ratios and p-values within relative 1e-12) and the words.
     */
    @Test
    void theReplayedHistoryOfEveryGoldenNodeAndScopeKindIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        int[] numbers = new int[2];
        int official = 0;
        int refused = 0;
        int answers = 0;
        int officialAnswers = 0;
        int officialRows = 0;
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
                if ("official".equals(inTree.path("mode").asText())) {
                    officialAnswers++;
                    officialRows += inTree.path("rows").size();
                }
                com.fasterxml.jackson.databind.node.ObjectNode e2 = engine.deepCopy();
                e2.remove("path");
                parametricSame(sbs, numbers, what + " history", inTree, e2);
            }
        }
        System.out.println("side by side, history (tier " + System.getProperty("sbs.tier", "mapped") + "): " + answers + " answers on both "
                + "paths (" + officialAnswers + " of them official, " + officialRows + " official rows; " + official + " in-tree only; " + refused
                + " refused on both); " + numbers[0] + " numbers compared, " + numbers[1]
                + " within relative 1e-12; differences " + sbs.differences.size() + " (USD " + sbs.differences.stream().filter(x -> x.contains("/USD"))
                .count() + "); first: " + sbs.differences.stream().limit(8).map(x -> x.substring(0, Math.min(300, x.length()))).toList());
        assertThat(answers).as("history answers compared").isGreaterThan(500);
        assertThat(officialAnswers).as("official histories compared (USD nodes on 09-29 and 09-30)").isGreaterThan(200);
        assertThat(official).as("nodes answered in-tree only").isZero();
        // the mapped tier: a node's figures are its stored aggregate (USD asserted); other scopes sum float32 rows (MC-12): counted
        assertThat(asserted(sbs.differences).stream().filter(x -> HEAP || x.contains(" NODE:")).toList())
                .as("history answers that differ between in-tree and engine").isEmpty();
    }

    /**
     * The period statistics of the official records ({@code GET /statistics}): every golden node and three scope words, each measure
     * (VaR, ES, stressed VaR and ES), four periods (the default fiscal year, a quarter, a range of two days, a year with no record), in-tree
     * against the engine's {@code official-statistics} over the published records (the three dates captured at start): every field equal,
     * every number to the bit (the averages summed in the in-tree order).
     */
    @Test
    void theStatisticsOfEveryGoldenNodeAreTheSameOnBothPaths() throws Exception {
        List<String> scopes = new ArrayList<>(List.of("BANK", "DESK:RATES", "LE:LE-NY"));
        golden.get("2026-09-30/509/USD").get("nodes").fieldNames().forEachRemaining(id -> scopes.add("NODE:" + id));
        List<String> differences = new ArrayList<>();
        int answers = 0;
        int withFigures = 0;
        int numbers = 0;
        for (String scope : scopes) {
            for (String measure : List.of("var", "es", "svar", "ses")) {
                for (String period : List.of("", "FY2026Q4", "2026-09-28..2026-09-29", "FY2025")) {
                    String q = "/api/v1/risk/var/statistics?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) + "&measure=" + measure
                            + (period.isEmpty() ? "" : "&period=" + period);
                    JsonNode inTree = server.get(q + "&path=in-tree");
                    com.fasterxml.jackson.databind.node.ObjectNode engine = server.get(q + "&path=engine").deepCopy();
                    assertThat(engine.path("path").asText()).isEqualTo("engine");
                    engine.remove("path");
                    answers++;
                    withFigures += inTree.has("periodEnd") ? 1 : 0;
                    numbers += countNumbers(inTree);
                    if (!inTree.equals(engine)) {
                        differences.add(scope + " " + measure + " " + period + ": in-tree " + inTree + ", engine " + engine);
                    }
                }
            }
        }
        System.out.println("side by side, statistics: " + answers + " answers on both paths (" + withFigures + " with figures), " + numbers
                + " numbers; differences " + differences.size() + "; first: " + differences.stream().limit(3).map(x -> x.substring(0, Math.min(900,
                x.length()))).toList());
        assertThat(withFigures).as("statistics with figures compared").isGreaterThan(500);
        assertThat(differences).as("statistics that differ between in-tree and engine (every field, every bit)").isEmpty();
    }

    /**
     * The regulatory backtest ({@code GET /backtesting}, decision 6.6 (5)): every golden node and three scope words, to each official
     * date and to the newest, in-tree (the store's records, each keeping its VaR at 97.5% as {@code varAt}) against the engine's
     * {@code backtest} and {@code backtest-days} over the published records (contract 1.6: {@code BACKTEST_CONFIDENCE},
     * {@code BACKTEST_VAR}) and the desk P&amp;L: every field equal, every number to the bit (the exceptions at 0.99 and 0.975 counted
     * alike, the zone, the multiplier, the tested days with the VaR of the day before at both confidences, the desks' rows); Kupiec and
     * Christoffersen as the answer rounds them (4 decimals).
     */
    @Test
    void theBacktestOfEveryGoldenNodeIsTheSameOnBothPaths() throws Exception {
        List<String> scopes = new ArrayList<>(List.of("BANK", "DESK:RATES", "LE:LE-NY"));
        golden.get("2026-09-30/509/USD").get("nodes").fieldNames().forEachRemaining(id -> scopes.add("NODE:" + id));
        List<String> differences = new ArrayList<>();
        int answers = 0;
        int numbers = 0;
        int[] tested = new int[2];                                        // tested days at 0.99 and at 0.975 (the node and its desks)
        int[] exceptions = new int[2];
        for (String scope : scopes) {
            for (String asOf : List.of("", "2026-09-29", "2026-09-30")) {
                String q = "/api/v1/risk/var/backtesting?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
                        + (asOf.isEmpty() ? "" : "&asOf=" + asOf);
                JsonNode inTree = server.get(q + "&path=in-tree");
                com.fasterxml.jackson.databind.node.ObjectNode engine = server.get(q + "&path=engine").deepCopy();
                assertThat(engine.path("path").asText()).isEqualTo("engine");
                engine.remove("path");
                answers++;
                numbers += countNumbers(inTree);
                List<JsonNode> parts = new ArrayList<>(List.of(inTree));
                inTree.path("parts").forEach(parts::add);                 // the node again, then its desks
                for (JsonNode part : parts) {
                    for (JsonNode c : part.path("counts")) {
                        int k = Math.abs(c.path("confidence").asDouble() - 0.99) < 1e-9 ? 0 : 1;
                        tested[k] += c.path("days").asInt();
                        exceptions[k] += c.path("actual").asInt() + c.path("hypothetical").asInt();
                    }
                }
                if (!inTree.equals(engine)) {
                    differences.add(scope + " " + asOf + ": in-tree " + SideBySide.first(inTree, engine));
                }
            }
        }
        System.out.println("side by side, backtesting: " + answers + " answers on both paths, " + numbers + " numbers; tested days at 0.99 "
                + tested[0] + " and at 0.975 " + tested[1] + ", exceptions (actual + hypothetical) " + exceptions[0] + " and " + exceptions[1]
                + "; differences " + differences.size() + "; first: " + differences.stream().limit(3).map(x -> x.substring(0, Math.min(1500,
                x.length()))).toList());
        assertThat(tested[1]).as("days tested at 97.5%").isGreaterThan(0).isEqualTo(tested[0]);
        assertThat(differences).as("backtests that differ between in-tree and engine (every field, every bit)").isEmpty();
    }

    /**
     * Captures and compares (decision 6.6 (6)): a capture stays Drishti's (provenance, data version, label, kind, members), its figures
     * come from the path {@code var.route} selects. Every golden node with child nodes (3 dates x USD/EUR) captured on both paths: every
     * field of the two captures equal (headline, k, the child contributions, the worst scenarios, the rounded vector, the scenario dates,
     * the members, the provenance) but the capture's own id, time and owner; the compare of the two shows no change at all; and on the
     * newest date the compare of the in-tree capture with now is the same on both paths. Nodes whose parts are positions are refused on
     * the engine path in words (counted).
     */
    @Test
    void theCapturesOfEveryGoldenNodeAreTheSameOnBothPaths() throws Exception {
        List<String> differences = new ArrayList<>();
        int pairs = 0;
        int refused = 0;
        int nowCompared = 0;
        Map<String, Integer> reasons = new java.util.TreeMap<>();
        for (String date : List.of("2026-09-28", "2026-09-29", "2026-09-30")) {
            for (String ccy : List.of("USD", "EUR")) {
                Iterator<String> ids = golden.get(date + "/509/" + ccy).get("nodes").fieldNames();
                while (ids.hasNext()) {
                    String id = ids.next();
                    String what = date + "/" + ccy + " NODE:" + id;
                    String body = "{\"scope\":\"NODE:" + id + "\",\"ccy\":\"" + ccy + "\",\"note\":\"side by side\"}";
                    JsonNode e;
                    try {
                        e = server.created("/api/v1/risk/var/captures?asOf=" + date + "&path=engine", body);
                    } catch (IllegalStateException x) {
                        assertThat(x.getMessage()).as(what + ": refused in words").startsWith("400 ");
                        String why = x.getMessage().replaceAll(".*\"detail\":\"", "").replaceAll("[A-Z]+~[^ :,)]*", "<node>");
                        reasons.merge(why.substring(0, Math.min(140, why.length())), 1, Integer::sum);
                        refused++;
                        continue;
                    }
                    JsonNode i = server.created("/api/v1/risk/var/captures?asOf=" + date + "&path=in-tree", body);
                    com.fasterxml.jackson.databind.node.ObjectNode ci = server.get("/api/v1/risk/var/captures/" + i.path("id").asText()).deepCopy();
                    com.fasterxml.jackson.databind.node.ObjectNode ce = server.get("/api/v1/risk/var/captures/" + e.path("id").asText()).deepCopy();
                    assertThat(ce.path("figuresFrom").asText()).isEqualTo("engine");
                    for (String f : List.of("id", "capturedAt", "owner", "figuresFrom")) {
                        ci.remove(f);
                        ce.remove(f);
                    }
                    for (com.fasterxml.jackson.databind.node.ObjectNode c : List.of(ci, ce)) {   // when and how fast each was computed,
                        if (c.path("provenance") instanceof com.fasterxml.jackson.databind.node.ObjectNode pv) {   // and whether from the
                            pv.remove(List.of("computedAt", "computeMs", "cache"));                                // in-tree cache: run facts
                        }
                    }
                    pairs++;
                    if (!ci.equals(ce)) {
                        List<String> fields = new ArrayList<>();
                        ci.fieldNames().forEachRemaining(f -> {
                            if (!ci.get(f).equals(ce.get(f))) {
                                fields.add(f + ": in-tree " + SideBySide.first(ci.get(f), ce.path(f)));
                            }
                        });
                        differences.add(what + " capture: " + fields);
                    }
                    JsonNode cmp = server.get("/api/v1/risk/var/compare?a=" + i.path("id").asText() + "&b=" + e.path("id").asText());
                    boolean still = cmp.path("varChange").asDouble() == 0 && cmp.path("esChange").asDouble() == 0
                            && cmp.path("membersAdded").isEmpty() && cmp.path("membersRemoved").isEmpty();
                    for (JsonNode c : cmp.path("components")) {
                        still &= c.path("change").asDouble() == 0;
                    }
                    if (!still) {
                        differences.add(what + " compare of the two: " + cmp);
                    }
                    if (date.equals("2026-09-30")) {
                        String q = "/api/v1/risk/var/compare?a=" + i.path("id").asText() + "&b=now";
                        com.fasterxml.jackson.databind.node.ObjectNode ni = server.get(q + "&path=in-tree").deepCopy();
                        com.fasterxml.jackson.databind.node.ObjectNode ne = server.get(q + "&path=engine").deepCopy();
                        ((com.fasterxml.jackson.databind.node.ObjectNode) ni.path("b")).remove("capturedAt");
                        ((com.fasterxml.jackson.databind.node.ObjectNode) ne.path("b")).remove("capturedAt");
                        nowCompared++;
                        if (!ni.equals(ne)) {
                            differences.add(what + " compare with now: " + SideBySide.first(ni, ne));
                        }
                    }
                }
            }
        }
        System.out.println("side by side, captures: " + pairs + " pairs of captures (" + refused + " nodes refused on the engine path: parts are "
                + "positions: " + reasons + "), " + nowCompared + " compares with now; differences " + differences.size() + " (asserted "
                + asserted(differences).size() + "); first: " + differences.stream().limit(3).map(x -> x.substring(0, Math.min(1500, x.length()))).toList());
        assertThat(pairs).as("pairs of captures compared").isGreaterThan(100);
        assertThat(asserted(differences)).as("captures that differ between in-tree and engine (every field, every bit)").isEmpty();
    }

    private static int countNumbers(JsonNode n) {
        if (n.isNumber()) {
            return 1;
        }
        int c = 0;
        for (JsonNode x : n) {
            c += countNumbers(x);
        }
        return c;
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
                int from = sbs.differences.size();
                JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/scope/path?" + q + "&size=509&parametric=false&exact=true",
                        "canonical", "currency", "var", "es", "k", "esCount", "histogram", "worst", "series", "dates", "scenarioDates", "page",
                        "pageSize", "total", "rows");
                boolean leaf = both[0].path("rows").path(0).path("fromScope").asText().startsWith("POS:");
                for (int i = from; leaf && i < sbs.differences.size(); i++) {   // only the part that lost most: the series is the node's own
                    if (sbs.differences.get(i).contains(" rows: ")) {
                        sbs.differences.set(i, POSITIONS + sbs.differences.get(i));
                    }
                }
                JsonNode head = server.get("/api/v1/risk/var/scope?" + q + "&window=" + both[0].path("total").asInt() + "&exact=true&path=in-tree");
                sbs.bits(what + " VAR", head.path("varExact").asDouble(), both[1].path("varExact").asDouble());   // the date's VaR, in-tree
                sbs.bits(what + " ES", head.path("esExact").asDouble(), both[1].path("esExact").asDouble());
            }
        }
        System.out.println("side by side, P&L path: differences USD " + sbs.differences.stream().filter(x -> x.contains("/USD ")).count() + ", EUR "
                + sbs.differences.stream().filter(x -> x.contains("/EUR ")).count() + " (fields: " + sbs.differences.stream()
                .map(x -> x.replaceAll("^\\S+ \\S+ (\\S+): .*$", "$1")).distinct().toList() + "); USD: " + sbs.differences.stream()
                .filter(x -> x.contains("/USD ")).limit(5).toList());
        System.out.println("side by side, P&L path: " + sbs.compared + " node paths on both paths (" + sbs.differences.stream()
                .filter(x -> x.startsWith(POSITIONS)).count() + " differences in a leaf node's position parts; " + refused + " refused on the "
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
        int positionNodes = 0;
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
                int from = sbs.differences.size();
                JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/scope/contributions?" + q + "&parametric=false&exact=true",
                        "canonical", "currency", "by", "var", "es", "sumStandalone", "sumComponent", "diversification", "truncated", "total",
                        "shown", "rows");
                sbs.bits(what + " VAR", g.get(id).path("var").asDouble(), both[1].path("varExact").asDouble());
                sbs.bits(what + " ES", g.get(id).path("es").asDouble(), both[1].path("esExact").asDouble());
                sbs.both(server, what + " headline", "/api/v1/risk/var/scope?" + q, "children", "childKind",
                        "sumStandalone", "diversification");
                boolean leaf = "position".equals(both[0].path("by").asText());
                positionNodes += leaf ? 1 : 0;
                tagPositions(sbs.differences, from, leaf);
            }
        }
        System.out.println("side by side, contributions: " + sbs.compared + " answers (contributions and the headline's parts) on both paths ("
                + positionNodes + " leaf nodes whose parts are positions, " + sbs.differences.stream().filter(x -> x.startsWith(POSITIONS)).count()
                + " position-part differences; " + refused + " refused on the engine path); " + sbs.sameBits + " engine VaR and ES figures equal to the golden "
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
        SideBySide pSbs = new SideBySide();
        int[] pNumbers = new int[2];
        int pBlocks = 0;
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
                    // the parametric block through the lens (node-pvar on the lens's vectors; decision 6.6 (4): within relative 1e-12)
                    parametricSame(pSbs, pNumbers, what + " parametric", both[0].path("parametric"), both[1].path("parametric"));
                    pBlocks += both[0].has("parametric") ? 1 : 0;
                }
            }
        }
        System.out.println("side by side, horizons and the stress set, parametric: " + pBlocks + " blocks; " + pNumbers[0] + " numbers compared, "
                + pNumbers[1] + " within relative 1e-12; differences " + pSbs.differences.size() + " (USD " + pSbs.differences.stream()
                .filter(x -> x.contains("/USD ")).count() + "); first: " + pSbs.differences.stream().limit(6).map(x -> x.substring(0,
                Math.min(300, x.length()))).toList());
        assertThat(pBlocks).as("parametric blocks through a lens").isGreaterThan(1000);
        assertThat(asserted(pSbs.differences)).as("parametric figures through a lens beyond relative 1e-12 or in words").isEmpty();
        System.out.println("side by side, horizons and the stress set: " + sbs.compared + " node answers on both paths; " + sbs.sameBits
                + " engine VaR and ES figures equal to the in-tree raw bits; differences " + sbs.differences.size() + " (USD "
                + sbs.differences.stream().filter(x -> x.contains("/USD ")).count() + "); first: " + sbs.differences.stream().limit(8)
                .map(x -> x.substring(0, Math.min(300, x.length()))).toList() + "; first raw-bit differences: "
                + sbs.bitDifferences.stream().limit(8).toList());
        assertThat(sbs.compared).as("answers compared").isGreaterThan(1000);
        assertThat(asserted(sbs.differences)).as("answers that differ (EUR too on the heap tier)").isEmpty();
        assertThat(asserted(sbs.bitDifferences)).as("raw bits that differ").isEmpty();
    }

    /**
     * The headline of every golden node at the other offered confidences ({@code GET /scope} with {@code confidence} and
     * {@code esConfidence} from the offered lists, R2 lane G part 6: {@code node-var-horizon} and {@code node-pvar} at the lens's
     * confidences), each date, USD and EUR, 1-day and 10-day by sums: VaR, ES, the confidences, scenarios, trades and the limit block equal
     * on both paths, the engine's VaR and ES equal to the in-tree raw bits, the parametric block within relative 1e-12 (decision 6.6 (4)).
     */
    @Test
    void theOtherConfidencesAreTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        SideBySide pSbs = new SideBySide();
        int[] pNumbers = new int[2];
        int pBlocks = 0;
        java.util.Set<String> dates = new java.util.TreeSet<>();
        golden.fieldNames().forEachRemaining(c -> dates.add(c.split("/")[0]));
        List<String[]> lenses = List.of(new String[] {"USD", "confidence=0.975"}, new String[] {"USD", "confidence=0.95&esConfidence=0.99"},
                new String[] {"EUR", "confidence=0.995&esConfidence=0.95"}, new String[] {"USD", "confidence=0.975&horizon=10d&method=sum"});
        for (String date : dates) {
            Iterator<String> ids = golden.path(date + "/509/USD").path("nodes").fieldNames();
            while (ids.hasNext()) {
                String id = ids.next();
                for (String[] l : lenses) {
                    String what = date + "/" + l[0] + " " + l[1] + " NODE:" + id;
                    JsonNode[] both = sbs.both(server, what, "/api/v1/risk/var/scope?scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8)
                            + "&asOf=" + date + "&ccy=" + l[0] + "&" + l[1] + "&exact=true", "currency", "var", "es", "confidence", "esConfidence",
                            "scenarios", "horizon", "set", "trades", "limit", "limitComparable", "limitUse");
                    sbs.bits(what + " VAR", both[0].path("varExact").asDouble(), both[1].path("varExact").asDouble());
                    sbs.bits(what + " ES", both[0].path("esExact").asDouble(), both[1].path("esExact").asDouble());
                    parametricSame(pSbs, pNumbers, what + " parametric", both[0].path("parametric"), both[1].path("parametric"));
                    pBlocks += both[0].has("parametric") ? 1 : 0;
                }
            }
        }
        System.out.println("side by side, other confidences, parametric: " + pBlocks + " blocks; " + pNumbers[0] + " numbers compared, "
                + pNumbers[1] + " within relative 1e-12; differences " + pSbs.differences.size() + "; first: " + pSbs.differences.stream().limit(6)
                .map(x -> x.substring(0, Math.min(300, x.length()))).toList());
        System.out.println("side by side, other confidences: " + sbs.compared + " node answers on both paths; " + sbs.sameBits
                + " engine VaR and ES figures equal to the in-tree raw bits; differences " + sbs.differences.size() + " (USD "
                + sbs.differences.stream().filter(x -> x.contains("/USD ")).count() + "); first: " + sbs.differences.stream().limit(8)
                .map(x -> x.substring(0, Math.min(300, x.length()))).toList() + "; first raw-bit differences: "
                + sbs.bitDifferences.stream().limit(8).toList());
        assertThat(sbs.compared).as("answers compared").isGreaterThan(1000);
        assertThat(pBlocks).as("parametric blocks at other confidences").isGreaterThan(1000);
        assertThat(asserted(pSbs.differences)).as("parametric figures at other confidences beyond relative 1e-12 or in words").isEmpty();
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
