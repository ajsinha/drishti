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
        server = PackagedServer.start(work, lake, "2026-09-30");
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
        assertThat(sbs.differences).as("route answers that differ between in-tree and engine").isEmpty();
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
        // EUR: the in-tree route takes each child part from the book converted trade by trade, the engine (and the in-tree headline) from
        // the per-currency totals, a few units apart on a part's P&L (the open EUR decision, R2_CUT_PLAN Appendix A); USD must be equal
        assertThat(sbs.differences.stream().filter(x -> !x.contains("/EUR ") || !x.contains(" rows: ") || !x.contains(".fromPnl")).toList())
                .as("path answers that differ between in-tree and engine").isEmpty();
        assertThat(sbs.bitDifferences).as("engine figures whose raw bits differ from the in-tree").isEmpty();
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
        assertThat(sbs.differences).as("route answers that differ between in-tree and engine").isEmpty();
        assertThat(sbs.bitDifferences).as("engine figures whose raw bits differ from the in-tree").isEmpty();
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
