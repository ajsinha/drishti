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
import java.io.Reader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * R2 side by side for the exposure family (docs/plans/R2_CUT_PLAN.md, "Switching a route"): one packaged server over the counterparty-risk
 * sample day with Drishti Risk's JARs dropped into {@code lib/engines}; the exposure scope route asked in-tree and through the {@code ccr}
 * engine for the bank, every legal entity, every counterparty and every netting set with paths: the figures, the peak date and the profile
 * must be equal; the engine's exact doubles must equal the recorder's raw bits ({@code drishti-risk tck/recorded/ccr}, the in-tree answers at
 * the freeze commit) for every recorded scope. Run:
 * {@code ./mvnw -o verify -pl testing/drishti-modules-it -Dtest=ExposureSideBySideTest -Ddrishti.risk.jars=<folder> -Ddrishti.python=<python>
 * -Ddrishti.risk.recorded=<drishti-risk>/tck/recorded/ccr/recorder-2026-09-30.yaml}.
 */
class ExposureSideBySideTest {

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("side-by-side-ccr");
    static final String DATE = "2026-09-30";
    static final String ONLY = "counterparty-risk/simulation-run,counterparty-risk/exposure-path,counterparty-risk/netting-set,counterparty-risk/csa,"
            + "counterparty-risk/collateral-balance,counterparty-risk/credit-limit,market-risk/trade-valuation,core/counterparty,core/counterparty-group,"
            + "core/legal-entity,core/desk,core/book";
    static final String[] FIGURES = {"ce", "epe", "eepe", "peakEe", "peakPfe", "peakPfeDate", "peakPfeTenor"};
    static final String[] POINT = {"date", "tenor", "ee", "ene", "eee", "pfe", "eeUncoll"};
    /** The recorder's summary and profile columns, and the engine path's exact keys. */
    static final String[][] SUMMARY_BITS = {{"CE", "ceExact"}, {"EPE", "epeExact"}, {"EEPE", "eepeExact"}, {"PEAK_EE", "peakEeExact"},
        {"PEAK_PFE", "peakPfeExact"}};
    static final String[][] PROFILE_BITS = {{"EE", "eeExact"}, {"ENE", "eneExact"}, {"EEE", "eeeExact"}, {"PFE", "pfeExact"}, {"EE_UNCOLL", "eeUncollExact"}};
    static final String[] COLLATERAL = {"csa", "margined", "thresholdThem", "thresholdUs", "mtaThem", "independentAmount", "mporDays", "heldToday",
        "imHeld", "netMtm", "trades"};
    /** The tier both paths keep the netting-set matrices in: mapped (the default) unless {@code -Dsbs.tier=heap}. */
    static final boolean MAPPED = !"heap".equals(System.getProperty("sbs.tier", ""));
    static PackagedServer server;
    static List<Map<String, Object>> recorded;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void start() throws Exception {
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        String rec = System.getProperty("drishti.risk.recorded", "");
        assumeTrue(!rec.isBlank() && Files.isRegularFile(Path.of(rec)), "no recorder file of the ccr cases given (-Ddrishti.risk.recorded)");
        try (Reader r = Files.newBufferedReader(Path.of(rec))) {
            recorded = (List<Map<String, Object>>) ((Map<String, Object>) new Yaml().load(r)).get("cases");
        }
        Path lake = FOLDER.resolve("lake");
        PackagedServer.buildLake(lake, ONLY);
        Path work = FOLDER.resolve("server");
        deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        // both tiers (R2 MC-12): the default mapped tier keeps the netting-set matrices float32 in the hot file and the engine follows it
        // (config/engines/ccr.yaml vector-tier from drishti.ccr.exposure.tier); -Dsbs.tier=heap: float64 on both paths
        String tier = System.getProperty("sbs.tier", "");
        server = tier.isBlank() ? PackagedServer.start(work, lake, DATE) : PackagedServer.start(work, lake, DATE, Map.of("DRISHTI_EXPOSURE_TIER", tier));
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * The uncollateralised view ({@code collateral=none}) at the default confidence and at 0.99, the scope route and the profile route: it
     * needs no CSA terms, so it is compared now; the collateralised view waits for the host's as-of read of the csa kind (below).
     */
    @Test
    void theUncollateralisedExposureOfEveryScopeIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        Set<String> scopes = scopes();
        for (String extra : List.of("&collateral=none", "&collateral=none&q=0.99")) {
            compare(sbs, scopes, extra);
        }
        System.out.println("side by side, uncollateralised exposure: " + sbs.compared + " answers on both paths; first differences: "
                + sbs.differences.stream().limit(Integer.getInteger("sbs.show", 10)).toList());
        assertThat(sbs.compared).as("answers compared").isGreaterThan(4 * 50);
        assertThat(sbs.differences).as("route answers that differ between in-tree and engine").isEmpty();
    }

    /**
     * The counterparty grain ({@code counterparty-summary}, for the Exposure Home): every counterparty row of the engine against the in-tree
     * scope {@code CPTY:<id>} (figures, peak date, trades) and the in-tree tree's counterparty nodes (the same set of counterparties),
     * uncollateralised at the default confidence and at 0.99.
     */
    @Test
    void theCounterpartyGrainIsTheSameAsEachCounterpartyScopeInTree() throws Exception {
        SideBySide sbs = new SideBySide();
        Set<String> tree = new java.util.TreeSet<>();
        for (String s : scopes()) {
            if (s.startsWith("CPTY:")) {
                tree.add(s);
            }
        }
        for (Double q : java.util.Arrays.asList(null, 0.99)) {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("collateral", "none");
            if (q != null) {
                params.put("confidence", q);
            }
            JsonNode a = post("/api/v1/engines/ccr/output", Map.of("outputs", List.of("counterparty-summary"), "params", params,
                    "snapshot", Map.of("date", DATE)));
            JsonNode t = a.path("tables").path(0);
            List<String> cols = new ArrayList<>();
            t.path("columns").forEach(c -> cols.add(c.path("name").asText()));
            Set<String> seen = new java.util.TreeSet<>();
            for (JsonNode r : t.path("rows")) {
                String scope = "CPTY:" + r.get(cols.indexOf("COUNTERPARTY")).asText();
                seen.add(scope);
                String what = scope + (q == null ? "" : " q=" + q);
                JsonNode in = server.get("/api/v1/risk/exposure/scope?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) + "&asOf=" + DATE
                        + "&collateral=none" + (q == null ? "" : "&q=" + q) + "&path=in-tree");
                sbs.compared++;
                String[][] f = {{"CE", "ce"}, {"EPE", "epe"}, {"EEPE", "eepe"}, {"PEAK_EE", "peakEe"}, {"PEAK_PFE", "peakPfe"}};
                for (String[] x : f) {
                    long e = Math.round(r.get(cols.indexOf(x[0])).asDouble());
                    if (e != in.path(x[1]).asLong()) {
                        sbs.differences.add(what + " " + x[1] + ": in-tree " + in.path(x[1]) + ", engine " + e);
                    }
                }
                if (!r.get(cols.indexOf("PEAK_PFE_DATE")).asText().equals(in.path("peakPfeDate").asText())) {
                    sbs.differences.add(what + " peakPfeDate: in-tree " + in.path("peakPfeDate") + ", engine " + r.get(cols.indexOf("PEAK_PFE_DATE")));
                }
                if (r.get(cols.indexOf("TRADES")).asLong() != in.path("provenance").path("trades").asLong()
                        || r.get(cols.indexOf("NETTING_SETS")).asLong() != in.path("provenance").path("nettingSets").asLong()) {
                    sbs.differences.add(what + " trades/netting sets: in-tree " + in.path("provenance").path("trades") + "/"
                            + in.path("provenance").path("nettingSets") + ", engine " + r.get(cols.indexOf("TRADES")) + "/" + r.get(cols.indexOf("NETTING_SETS")));
                }
            }
            if (!seen.equals(tree)) {
                sbs.differences.add("counterparties: in-tree tree " + tree + ", engine " + seen);
            }
        }
        System.out.println("side by side, counterparty grain: " + sbs.compared + " counterparty rows; first differences: "
                + sbs.differences.stream().limit(Integer.getInteger("sbs.show", 10)).toList());
        assertThat(sbs.compared).as("counterparty rows compared").isGreaterThan(2 * 10);
        assertThat(sbs.differences).as("counterparty rows that differ between in-tree and engine").isEmpty();
    }

    /**
     * The pre-deal check (the exact method): for every counterparty with paths, a copy of the first trade of its first netting set as it
     * is, and the opposite side at 100m into that netting set; the decision, the netting set, the limit's figures and status, each tenor
     * bucket and the IMM EAD before and after must be equal on both paths.
     */
    @Test
    void thePreDealCheckIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        JsonNode t = server.get("/api/v1/risk/exposure/tree?asOf=" + DATE);
        String[] fields = {"decision", "template.tradeId", "nettingSet.id", "limit.id", "limit.limit", "limit.before", "limit.after", "limit.increment",
            "limit.useBefore", "limit.useAfter", "limit.status", "exposure.base.ead", "exposure.with.ead"};
        String[] bucket = {"bucket", "limit", "before", "after", "useAfter", "status"};
        for (JsonNode g : t.path("groups")) {
            for (JsonNode cp : g.path("children")) {
                JsonNode ns = null;
                for (JsonNode n : cp.path("children")) {
                    if (!n.path("noPaths").asBoolean()) {
                        ns = n;
                        break;
                    }
                }
                if (ns == null) {
                    continue;
                }
                JsonNode c = server.get("/api/v1/risk/exposure/scope/contributions?scope=" + URLEncoder.encode(ns.path("scope").asText(), StandardCharsets.UTF_8)
                        + "&by=trade&asOf=" + DATE);
                String trade = c.path("rows").path(0).path("key").asText();
                String id = cp.path("id").asText();
                List<Map<String, Object>> bodies = List.of(Map.of("counterparty", id, "like", trade),
                        Map.of("counterparty", id, "like", trade, "notional", "100m", "side", "REVERSE", "nettingSet", ns.path("id").asText()));
                for (Map<String, Object> body : bodies) {
                    String what = "predeal " + body;
                    JsonNode in = post("/api/v1/risk/exposure/predeal?path=in-tree&asOf=" + DATE, body);
                    JsonNode en = post("/api/v1/risk/exposure/predeal?path=engine&asOf=" + DATE, body);
                    sbs.compared++;
                    for (String f : fields) {
                        JsonNode a = in.at("/" + f.replace('.', '/'));
                        JsonNode b = en.at("/" + f.replace('.', '/'));
                        if (!Objects.equals(text(a), text(b))) {
                            sbs.differences.add(what + " " + f + ": in-tree " + a + ", engine " + b);
                        }
                    }
                    JsonNode ib = in.path("limit").path("buckets");
                    JsonNode eb = en.path("limit").path("buckets");
                    if (ib.size() != eb.size()) {
                        sbs.differences.add(what + " buckets: in-tree " + ib.size() + ", engine " + eb.size());
                        continue;
                    }
                    for (int i = 0; i < ib.size(); i++) {
                        for (String f : bucket) {
                            if (!Objects.equals(text(ib.get(i).path(f)), text(eb.get(i).path(f)))) {
                                sbs.differences.add(what + " buckets[" + i + "]." + f + ": in-tree " + ib.get(i).path(f) + ", engine " + eb.get(i).path(f));
                            }
                        }
                    }
                }
            }
        }
        System.out.println("side by side, pre-deal: " + sbs.compared + " checks on both paths; first differences: "
                + sbs.differences.stream().limit(Integer.getInteger("sbs.show", 10)).toList());
        assertThat(sbs.compared).as("checks compared").isGreaterThan(10);
        assertThat(sbs.differences).as("pre-deal answers that differ between in-tree and engine").isEmpty();
    }

    /**
     * The contributions route ({@code /scope/contributions}) on both paths for every scope: by netting set, legal entity and asset (each
     * trade's share, read from its paths) and by the scope's child where it is a netting set (the bank, a counterparty), each figure (PFE,
     * EE, EPE) and the EE at a tenor: every field and every row equal (key, scope, label, kind, value, share, incremental), the total,
     * the date and the parts.
     */
    @Test
    void theContributionsOfEveryScopeAreTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        String[] fields = {"canonical", "by", "figure", "date", "total", "rows", "parts", "truncated"};
        int rows = 0;
        for (String scope : scopes()) {
            List<String> asks = new ArrayList<>(List.of("by=netting-set&figure=PFE", "by=netting-set&figure=EE", "by=netting-set&figure=EPE",
                    "by=legal-entity&figure=PFE", "by=asset&figure=PFE", "by=netting-set&figure=EE&date=1Y", "by=asset&figure=EE&date=5Y"));
            if (scope.equals("BANK") || scope.startsWith("CPTY:")) {
                asks.add("figure=PFE");
                asks.add("by=child&figure=EPE&collateral=none");
            }
            for (String a : asks) {
                String what = scope + " " + a;
                JsonNode[] both = sbs.both(server, what, "/api/v1/risk/exposure/scope/contributions?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
                        + "&asOf=" + DATE + "&" + a, fields);
                rows += both[0].path("rows").size();
            }
        }
        System.out.println("side by side, contributions (tier " + (MAPPED ? "mapped" : "heap") + "): " + sbs.compared + " answers, " + rows
                + " rows on both paths; first differences: " + sbs.differences.stream().limit(Integer.getInteger("sbs.show", 10)).toList());
        assertThat(rows).as("contribution rows compared").isGreaterThan(500);
        assertThat(sbs.differences).as("contributions that differ between in-tree and engine").isEmpty();
    }

    private static JsonNode post(String path, Object body) throws IOException, InterruptedException {
        java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(server.base + path)).timeout(java.time.Duration.ofMinutes(5)).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(PackagedServer.JSON.writeValueAsString(body))).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException(r.statusCode() + " for " + path + ": " + r.body().substring(0, Math.min(300, r.body().length())));
        }
        return PackagedServer.JSON.readTree(r.body());
    }

    @Test
    void theExposureOfEveryScopeIsTheSameOnBothPathsAndTheRecordedBits() throws Exception {
        SideBySide sbs = new SideBySide();
        Set<String> scopes = scopes();
        for (Map<String, Object> c : recorded) {
            scopes.add(String.valueOf(c.get("id")));
        }
        Map<String, JsonNode> engine = compare(sbs, scopes, "");
        int recordedScopes = 0;
        int skipped = 0;
        for (Map<String, Object> c : recorded) {
            String scope = String.valueOf(c.get("id"));
            JsonNode en = engine.get(scope);
            // the recorder's raw bits are float64 netting-set matrices (the heap tier's); its BANK is the bank of its own eight netting sets,
            // not of this lake: the BANK case and, on the mapped tier, every case are compared as route answers only (above); the float32 rows
            // the mapped tier sums are proven equal to the hot file's bit for bit in drishti-risk (ExposureParityTest)
            if (MAPPED || "BANK".equals(scope)) {
                skipped++;
                recordedScopes++;
                continue;
            }
            Map<String, Object> tables = table(c);
            List<Object> srow = rows(tables, "exposure-summary").get(0);
            List<String> scols = columns(tables, "exposure-summary");
            for (String[] b : SUMMARY_BITS) {
                sbs.bits(scope + " " + b[0], bits(srow.get(scols.indexOf(b[0]))), en.path(b[1]).asDouble());
            }
            List<List<Object>> prows = rows(tables, "exposure-profile");
            List<String> pcols = columns(tables, "exposure-profile");
            for (int i = 0; i < prows.size(); i++) {
                for (String[] b : PROFILE_BITS) {
                    sbs.bits(scope + " profile[" + i + "] " + b[0], bits(prows.get(i).get(pcols.indexOf(b[0]))), en.path("profile").path(i).path(b[1]).asDouble());
                }
            }
            recordedScopes++;
        }
        System.out.println("side by side, exposure scope (tier " + (MAPPED ? "mapped" : "heap") + "): " + sbs.compared + " answers on both paths ("
                + recordedScopes + " recorded, " + skipped + " of them not bit-compared); " + sbs.sameBits
                + " engine figures equal to the recorder's raw bits; first differences: " + sbs.differences.stream().limit(Integer.getInteger("sbs.show", 10)).toList()
                + "; first raw-bit differences: " + sbs.bitDifferences.stream().limit(Integer.getInteger("sbs.show", 10)).toList());
        assertThat(recordedScopes).as("recorded scopes compared").isEqualTo(recorded.size());
        assertThat(sbs.compared).as("answers compared").isGreaterThan(recorded.size());
        assertThat(sbs.differences).as("route answers that differ between in-tree and engine").isEmpty();
        assertThat(sbs.bitDifferences).as("engine figures whose raw bits differ from the recorder's").isEmpty();
        if (!MAPPED) {
            assertThat(sbs.sameBits).as("engine figures equal to the recorder's raw bits").isGreaterThanOrEqualTo(8 * 60);
        }
    }

    /** Every scope's scope route and profile route on both paths with {@code extra} appended; the engine's scope answers by scope. */
    private static Map<String, JsonNode> compare(SideBySide sbs, Set<String> scopes, String extra) throws IOException, InterruptedException {
        Map<String, JsonNode> engine = new java.util.HashMap<>();
        for (String scope : scopes) {
            String what = scope + extra;
            String q = "?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) + "&asOf=" + DATE + extra;
            JsonNode[] both = sbs.both(server, what, "/api/v1/risk/exposure/scope" + q, FIGURES);
            JsonNode in = both[0];
            JsonNode en = both[1];
            engine.put(scope, en);
            if (in.path("provenance").path("trades").asLong() != en.path("trades").asLong()) {
                sbs.differences.add(what + " trades: in-tree " + in.path("provenance").path("trades") + ", engine " + en.path("trades"));
            }
            if (scope.startsWith("NSET:")) {                           // the netting set's CSA terms and holdings
                for (String f : COLLATERAL) {
                    JsonNode a = in.path("collateral").path(f);
                    JsonNode b = en.path("collateral").path(f);
                    if (!Objects.equals(text(a), text(b))) {
                        sbs.differences.add(what + " collateral." + f + ": in-tree " + a + ", engine " + b);
                    }
                }
            }
            profile(sbs, what, in.path("profile"), en.path("profile"));
            JsonNode[] prof = sbs.both(server, what + " profile", "/api/v1/risk/exposure/scope/profile" + q);
            profile(sbs, what + " /profile", prof[0].path("profile"), prof[1].path("profile"));
        }
        return engine;
    }

    private static void profile(SideBySide sbs, String what, JsonNode ip, JsonNode ep) {
        if (ip.size() != ep.size() || ip.isEmpty()) {
            sbs.differences.add(what + " profile points: in-tree " + ip.size() + ", engine " + ep.size());
            return;
        }
        for (int i = 0; i < ip.size(); i++) {
            for (String f : POINT) {
                if (!Objects.equals(text(ip.get(i).path(f)), text(ep.get(i).path(f)))) {
                    sbs.differences.add(what + " profile[" + i + "]." + f + ": in-tree " + ip.get(i).path(f) + ", engine " + ep.get(i).path(f));
                }
            }
        }
    }

    /** The bank, every legal entity, every counterparty and every netting set with paths, from the in-tree tree. */
    static Set<String> scopes() throws IOException, InterruptedException {
        Set<String> out = new LinkedHashSet<>();
        out.add("BANK");
        JsonNode t = server.get("/api/v1/risk/exposure/tree?asOf=" + DATE);
        for (JsonNode le : t.path("legalEntities")) {
            out.add(le.path("scope").asText());
        }
        for (JsonNode g : t.path("groups")) {
            for (JsonNode cp : g.path("children")) {
                if (!cp.path("noPaths").asBoolean()) {
                    out.add(cp.path("scope").asText());
                }
                for (JsonNode ns : cp.path("children")) {
                    if (!ns.path("noPaths").asBoolean()) {
                        out.add(ns.path("scope").asText());
                    }
                }
            }
        }
        return out;
    }

    private static String text(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.isNumber() ? n.decimalValue().stripTrailingZeros().toPlainString() : n.asText();
    }

    private static double bits(Object hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(String.valueOf(hex).substring(2), 16));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> c) {
        return (Map<String, Object>) ((Map<String, Object>) c.get("expect")).get("tables");
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> rows(Map<String, Object> tables, String name) {
        return new ArrayList<>((List<List<Object>>) ((Map<String, Object>) tables.get(name)).get("rows"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> columns(Map<String, Object> tables, String name) {
        return (List<String>) ((Map<String, Object>) tables.get(name)).get("columns");
    }

    private static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var s = Files.walk(p)) {
                for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(x);
                }
            }
        }
    }
}
