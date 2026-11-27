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
 * R2 consumers, the role Homes (VarHomeMeasures, VarHomeBacktest): the Homes' tables read with {@code var.consumers.route} in-tree and
 * again with it on the engine (two servers over one lake, the family switch the only difference); the rows must be equal. Skipped when
 * no folder of Risk JARs is given (-Ddrishti.risk.jars).
 */
class HomesSideBySideTest {

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("homes-side-by-side");
    static final List<String> HOMES = List.of("risk-manager", "risk-officer", "quant", "trader");
    static final List<String> TABLES = List.of("home_kpis", "home_desk_limits", "home_backtest");

    @Test
    void theHomesVarTablesAreTheSameOnBothPaths() throws Exception {
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        Path lake = FOLDER.resolve("lake");
        PackagedServer.buildLake(lake, PackagedVarGoldenTest.ONLY);
        Map<String, JsonNode> inTree = read(jars, lake, "in-tree");
        Map<String, JsonNode> engine = read(jars, lake, "engine");
        List<String> differences = new ArrayList<>();
        int rows = 0;
        for (var e : inTree.entrySet()) {
            JsonNode a = e.getValue();
            JsonNode b = engine.get(e.getKey());
            rows += a.path("rows").size();
            if (!a.equals(b)) {
                differences.add(e.getKey() + ": in-tree " + cut(a) + ", engine " + cut(b));
            }
        }
        System.out.println("side by side, role Homes: " + inTree.size() + " tables, " + rows + " rows; differences: " + differences);
        System.out.println("homes sample: " + inTree.entrySet().stream().limit(6).map(e -> e.getKey() + "=" + cut(e.getValue())).toList());
        assertThat(inTree).isNotEmpty();
        assertThat(rows).as("rows compared").isGreaterThan(0);
        assertThat(differences).as("Home tables that differ between in-tree and engine").isEmpty();
    }

    private static String cut(JsonNode n) {
        String s = String.valueOf(n);
        return s.substring(0, Math.min(400, s.length()));
    }

    private static Map<String, JsonNode> read(String jars, Path lake, String path) throws IOException, InterruptedException {
        Path work = FOLDER.resolve("server-" + path);
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        PackagedServer server = PackagedServer.start(work, lake, "2026-09-30", Map.of("DRISHTI_VAR_CONSUMERS_ROUTE", path, "DRISHTI_HOMES_ROLES_*", String.join(",", HOMES)));
        if (path.equals("engine")) {                              // the engine starts with the server but answers when its day is read: wait for it
            long end = System.currentTimeMillis() + 180_000;
            while (true) {
                try {
                    server.get("/api/v1/risk/var/scope?scope=NODE:BANK&asOf=2026-09-30&window=250&path=engine");
                    break;
                } catch (IllegalStateException e) {
                    if (System.currentTimeMillis() > end) {
                        server.close();
                        throw e;
                    }
                    Thread.sleep(3000);
                }
            }
        }
        try {
            Map<String, JsonNode> out = new java.util.LinkedHashMap<>();
            for (String h : HOMES) {
                for (String t : TABLES) {
                    try {
                        out.put(h + "/" + t, server.get("/api/v1/homes/" + h + "/tables/" + t));
                    } catch (IllegalStateException e) {
                        out.put(h + "/" + t, PackagedServer.JSON.readTree("{\"refused\":\"" + e.getMessage().replace("\"", "'").replace("\\", "/") + "\"}"));
                    }
                }
            }
            return out;
        } finally {
            server.close();
        }
    }
}
