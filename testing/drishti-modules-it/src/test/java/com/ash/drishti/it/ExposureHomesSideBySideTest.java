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
 * R2 exposure consumers, the role Homes ({@code ExposureHomeMeasures}: the PFE KPI and the exposure-by-counterparty table with each
 * counterparty's credit limit): the credit officer's Home tables read with {@code exposure.route} in-tree and again on the engine (two
 * servers over one counterparty-risk lake, the family switch the only difference); every row must be equal (rows of a table compared in
 * counterparty order: the Home sorts by limit use, and counterparties of equal use keep their source's order). The tier is the default
 * (mapped) unless {@code -Dsbs.tier=heap}. Skipped when no folder of Risk JARs is given (-Ddrishti.risk.jars).
 */
class ExposureHomesSideBySideTest {

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("exposure-homes-side-by-side");
    static final List<String> HOMES = List.of("credit-officer");
    static final List<String> TABLES = List.of("home_kpis", "home_exposure", "home_exceptions");

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
        for (var e : inTree.entrySet()) {
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
        System.out.println("side by side, exposure Homes (tier " + System.getProperty("sbs.tier", "mapped") + "): " + inTree.size() + " tables, " + rows
                + " rows (" + exposureRows + " counterparty rows); differences: " + differences);
        System.out.println("exposure homes sample: " + inTree.entrySet().stream().map(e -> e.getKey() + "=" + cut(e.getValue())).toList());
        assertThat(exposureRows).as("counterparty rows compared").isGreaterThan(5);
        assertThat(differences).as("Home tables that differ between in-tree and engine").isEmpty();
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
            return out;
        } finally {
            server.close();
        }
    }
}
