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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R2 exposure consumers, the netting-set and counterparty pages' exposure figures ({@code ExposureFigures}, {@code EXPOSURE_ENGINE}) and the role Homes ({@code ExposureHomeMeasures}: the PFE KPI and the exposure-by-counterparty table with each
 * counterparty's credit limit): the credit officer's Home tables read with {@code exposure.route} in-tree and again on the engine (two
 * servers over one counterparty-risk lake, the family switch the only difference); every row must be equal (rows of a table compared in
 * counterparty order: the Home sorts by limit use, and counterparties of equal use keep their source's order). The tier is the default
 * (mapped) unless {@code -Dsbs.tier=heap}. Skipped when no folder of Risk JARs is given (-Ddrishti.risk.jars).
 */
class ExposureHomesSideBySideTest {

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("exposure-homes-side-by-side");
    static final List<String> HOMES = List.of("credit-officer");
    static final List<String> TABLES = List.of("home_kpis", "home_exposure", "home_exceptions");
    static final java.util.Set<String> PANELS = java.util.Set.of("exposure", "exposureEngine");

    @Test
    void theExposureHomeTablesAreTheSameOnBothPaths() throws Exception {
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        Path lake = FOLDER.resolve("lake");
        PackagedServer.buildLake(lake, ExposureSideBySideTest.ONLY);
        Map<String, List<String>> inTree = read(jars, lake, "in-tree");
        Map<String, List<String>> engine = read(jars, lake, "engine");
        List<String> differences = new ArrayList<>();
        int rows = 0;
        int exposureRows = 0;
        int pages = 0;
        int pagesOk = 0;
        for (var e : inTree.entrySet()) {
            if (e.getKey().startsWith("page ")) {
                pages++;
                pagesOk += e.getValue().get(0).contains("exposureEngine") && !e.getValue().get(0).contains("no-paths")
                        && !e.getValue().get(0).contains("unavailable") ? 1 : 0;
            }
            List<String> a = e.getValue();
            List<String> b = engine.get(e.getKey());
            rows += a.size();
            if (e.getKey().endsWith("home_exposure")) {
                exposureRows += a.size();
            }
            if (!a.equals(b)) {
                differences.add(e.getKey() + ": in-tree " + cut(a) + ", engine " + cut(b));
            }
        }
        System.out.println("side by side, exposure Homes and pages (tier " + System.getProperty("sbs.tier", "mapped") + "): " + inTree.size()
                + " tables and pages, " + rows + " rows (" + exposureRows + " counterparty rows; " + pages + " pages, " + pagesOk
                + " with figures); differences: " + differences);
        inTree.entrySet().stream().filter(e -> e.getKey().startsWith("page netting-set/")).findFirst()
                .ifPresent(e -> System.out.println("exposure page sample: " + e.getKey() + " in-tree " + e.getValue().get(0).substring(0, Math.min(3000,
                        e.getValue().get(0).length())) + "; engine " + engine.get(e.getKey())));
        System.out.println("exposure homes sample: " + inTree.entrySet().stream().map(e -> e.getKey() + "=" + cut(e.getValue())).toList());
        assertThat(exposureRows).as("counterparty rows compared").isGreaterThan(5);
        assertThat(pagesOk).as("netting-set and counterparty pages with exposure figures").isGreaterThan(20);
        long official = inTree.keySet().stream().filter(k -> k.startsWith("official ")).count();
        System.out.println("side by side, official record: " + official + " scopes and trades compared; record " + inTree.get("official record"));
        assertThat(official).as("official scopes and trades compared").isGreaterThan(50);
        assertThat(differences).as("Home tables that differ between in-tree and engine").isEmpty();
    }

    /** A page's {@code EXPOSURE_ENGINE} field (the exposure figures computed as it opens), as one row. */
    private static List<String> page(PackagedServer server, String kind, String id) throws IOException, InterruptedException {
        try {
            JsonNode v = server.get("/api/v1/views/" + kind + "/" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8) + "?asOf="
                    + ExposureSideBySideTest.DATE);
            // the page's EXPOSURE_ENGINE field as the netting set's Sutra renders it: the profile and "how it was computed" panels, the engine KPIs
            List<String> out = new ArrayList<>();
            collect(v, out);
            return out.isEmpty() ? List.of("absent") : List.of(String.join(" | ", out));
        } catch (IllegalStateException e) {
            return List.of("refused: " + e.getMessage());
        }
    }

    private static void collect(JsonNode n, List<String> out) {
        if (n.isObject()) {
            String id = n.path("id").asText("");
            String label = n.path("label").asText("");
            if (PANELS.contains(id) || label.endsWith("(engine)")) {
                out.add(n.toString());
                return;
            }
            n.forEach(c -> collect(c, out));
        } else if (n.isArray()) {
            n.forEach(c -> collect(c, out));
        }
    }

    private static String cut(List<String> rows) {
        String s = String.valueOf(rows);
        return s.substring(0, Math.min(600, s.length()));
    }

    private static Map<String, List<String>> read(String jars, Path lake, String path) throws IOException, InterruptedException {
        Path work = FOLDER.resolve("server-" + path);
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        Map<String, String> env = new java.util.HashMap<>(Map.of("DRISHTI_EXPOSURE_ROUTE", path, "DRISHTI_HOMES_ROLES_*", String.join(",", HOMES)));
        String tier = System.getProperty("sbs.tier", "");
        if (!tier.isBlank()) {
            env.put("DRISHTI_EXPOSURE_TIER", tier);
        }
        PackagedServer server = PackagedServer.start(work, lake, ExposureSideBySideTest.DATE, env);
        try {
            // both paths answer the bank's exposure before the Home is read (the engine answers once its day is read)
            server.get("/api/v1/risk/exposure/scope?scope=BANK&asOf=" + ExposureSideBySideTest.DATE + "&path=" + path);
            Map<String, List<String>> out = new java.util.LinkedHashMap<>();
            for (String h : HOMES) {
                for (String t : TABLES) {
                    List<String> rows = new ArrayList<>();
                    try {
                        JsonNode n = server.get("/api/v1/homes/" + h + "/tables/" + t);
                        n.path("rows").forEach(r -> rows.add(r.toString()));
                        rows.sort(null);
                    } catch (IllegalStateException e) {
                        rows.add("refused: " + e.getMessage());
                    }
                    out.put(h + "/" + t, rows);
                }
            }
            // the netting-set and counterparty pages' exposure figures (ExposureFigures, EXPOSURE_ENGINE): every one the tree names
            JsonNode tree = server.get("/api/v1/risk/exposure/tree?asOf=" + ExposureSideBySideTest.DATE + "&path=in-tree");
            for (JsonNode g : tree.path("groups")) {
                for (JsonNode cp : g.path("children")) {
                    out.put("page counterparty/" + cp.path("id").asText(), page(server, "counterparty", cp.path("id").asText()));
                    for (JsonNode ns : cp.path("children")) {
                        out.put("page netting-set/" + ns.path("id").asText(), page(server, "netting-set", ns.path("id").asText()));
                    }
                }
            }
            // the official record (EXPOSURE_GUIDE.md section 20): captured as an administrator, read back with every trade's EE contribution;
            // its figures come from the path exposure.route chose (the engine's record says figuresFrom: engine), its structure the host's
            // the capture at start may hold the record's lock (a whole date's scopes): wait for its record (polled), then capture (unchanged or new)
            long until = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
            while (System.nanoTime() < until) {
                try {
                    server.get("/api/v1/risk/exposure/official?asOf=" + ExposureSideBySideTest.DATE);
                    break;
                } catch (IllegalStateException notYet) {
                    Thread.onSpinWait();
                    java.util.concurrent.locks.LockSupport.parkNanos(2_000_000_000L);
                }
            }
            server.post("/api/v1/risk/exposure/official?asOf=" + ExposureSideBySideTest.DATE, "{}");
            JsonNode off = server.get("/api/v1/risk/exposure/official?asOf=" + ExposureSideBySideTest.DATE + "&trades=true");
            out.put("official from", List.of(off.path("figuresFrom").asText("in-tree").equals(path) ? "the path asked" : "another path: "
                    + off.path("figuresFrom").asText("in-tree")));
            off.path("scopes").fields().forEachRemaining(e -> out.put("official " + e.getKey(), List.of(e.getValue().toString())));
            off.path("tradeContributions").path("trades").fields().forEachRemaining(e -> out.put("official trade " + e.getKey(),
                    List.of(e.getValue().path("nettingSet").asText() + " " + e.getValue().path("ee"))));
            out.put("official record", List.of(off.path("runId").asText(), off.path("confidence").asText(), off.path("collateralModel").asText(),
                    String.valueOf(off.path("scopes").size()), String.valueOf(off.path("tradeContributions").path("trades").size())));
            return out;
        } finally {
            server.close();
        }
    }
}
