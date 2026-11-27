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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R2 consumers other than the role Homes (R2_CUT_PLAN Appendix A): what reads VaR figures inside Drishti, read with
 * {@code var.consumers.route} in-tree and again on the engine (two servers over one lake, the family switch the only difference), each
 * after the official records of the three dates are captured and published. Now: the regulatory VaR report runs (every report of the
 * reports folder for its current period: the table's period statistics, its facts' backtest, the backtesting chart). Every field equal,
 * every number to the bit, but the run's {@code frozenAt} (the clock) and each server's own folder in the texts (written {@code <server>}). Skipped when no folder of Risk JARs is given (-Ddrishti.risk.jars).
 */
class ConsumersSideBySideTest {

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("consumers-side-by-side");

    @Test
    void theRegulatoryReportRunsAreTheSameOnBothPaths() throws Exception {
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        Path lake = FOLDER.resolve("lake-desk-pnl");
        PackagedServer.buildLake(lake, PackagedVarGoldenTest.ONLY + ",market-risk/desk-pnl");   // + the desk P&L: the backtest's input
        Map<String, JsonNode> inTree = read(jars, lake, "in-tree");
        Map<String, JsonNode> engine = read(jars, lake, "engine");
        List<String> differences = new ArrayList<>();
        int refused = 0;
        for (var e : inTree.entrySet()) {
            JsonNode a = e.getValue();
            JsonNode b = engine.get(e.getKey());
            refused += a.has("refused") ? 1 : 0;
            if (!a.equals(b)) {
                List<String> where = new ArrayList<>();
                diff("", a, b, where);
                differences.add(e.getKey() + ": " + where.subList(0, Math.min(20, where.size())));
            }
        }
        System.out.println("side by side, consumers: " + inTree.size() + " answers (" + refused + " refused in-tree), keys " + inTree.keySet()
                + "; differences " + differences.size() + ": " + differences);
        assertThat(inTree.size() - refused).as("report runs compared").isGreaterThan(0);
        assertThat(differences).as("consumer answers that differ between in-tree and engine").isEmpty();
    }

    /** The paths where two answers differ, each with both values. */
    static void diff(String at, JsonNode a, JsonNode b, List<String> out) {
        if (a != null && b != null && a.isObject() && b.isObject()) {
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            for (String n : names) {
                diff(at + "." + n, a.get(n), b.get(n), out);
            }
        } else if (a != null && b != null && a.isArray() && b.isArray() && a.size() == b.size()) {
            for (int i = 0; i < a.size(); i++) {
                diff(at + "[" + i + "]", a.get(i), b.get(i), out);
            }
        } else if (a == null ? b != null : !a.equals(b)) {
            String x = String.valueOf(a);
            String y = String.valueOf(b);
            out.add(at + ": in-tree " + x.substring(0, Math.min(200, x.length())) + ", engine " + y.substring(0, Math.min(200, y.length())));
        }
    }

    private static Map<String, JsonNode> read(String jars, Path lake, String path) throws Exception {
        Path work = FOLDER.resolve("server-" + path);
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        PackagedServer server = PackagedServer.start(work, lake, "2026-09-30", Map.of("DRISHTI_VAR_CONSUMERS_ROUTE", path));
        try {
            RiskSideBySideTest.captureThreeDates(server);
            if (path.equals("engine")) {                              // the engine answers once its day is read: wait for it, once
                long end = System.currentTimeMillis() + 180_000;
                while (true) {
                    try {
                        server.get("/api/v1/risk/var/scope?scope=NODE:MGMT~BANK&asOf=2026-09-30&path=engine");
                        break;
                    } catch (IllegalStateException e) {
                        assertThat(System.currentTimeMillis()).as("the engine answers within 180 s: " + e.getMessage()).isLessThan(end);
                        Thread.sleep(3000);                           // a poll interval
                    }
                }
            }
            Map<String, JsonNode> out = new LinkedHashMap<>();
            for (JsonNode r : server.get("/api/v1/risk/var/reports").path("reports")) {
                String id = r.path("report").asText();
                out.put("report " + id, run(server, "/api/v1/risk/var/reports/" + id + "/runs", work.toString()));
            }
            if (path.equals("engine")) {                              // the runs were the engine's (the switch reached the reports)
                server.close();
                assertThat(Files.readString(work.resolve("server.log"))).contains("figures from the market-risk engine");
            }
            return out;
        } finally {
            server.close();
        }
    }

    /** A run generated now, without its clock ({@code frozenAt}); a refusal as its words. */
    private static JsonNode run(PackagedServer server, String url, String folder) throws IOException, InterruptedException {
        try {
            JsonNode n = server.post(url, "{}");
            strip(n, folder);
            return n;
        } catch (IllegalStateException e) {
            return PackagedServer.JSON.readTree("{\"refused\":" + PackagedServer.JSON.writeValueAsString(e.getMessage()) + "}");
        }
    }

    /** Leaves out the clock and writes each server's own folder (in a definition's file and the cells' lineage) as {@code <server>}. */
    private static void strip(JsonNode n, String folder) {
        if (n instanceof ObjectNode o) {
            o.remove("frozenAt");
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            for (String k : names) {
                JsonNode v = o.get(k);
                if (v.isTextual() && v.asText().contains(folder)) {
                    o.put(k, v.asText().replace(folder, "<server>"));
                } else {
                    strip(v, folder);
                }
            }
        } else if (n.isArray()) {
            n.forEach(x -> strip(x, folder));
        }
    }
}
