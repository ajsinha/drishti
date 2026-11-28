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
 * reports folder for its current period: the table's period statistics, its facts' backtest, the backtesting chart) and the VaR figures
 * of the desk and legal-entity pages (VarFigures' {@code VAR_ENGINE}: the headline, and each of an entity's books' VaR), the whole page
 * as the view answers it (its figures as the page renders them: the bits are RiskSideBySideTest's, the node headline), and (R2 lane B) the
 * system-basket and position pages (the headline of the row set, its parts by position or by trade with the engine's components, the
 * position's book VaR and component, the P&amp;L series): every system basket of the seed and three member positions of each. Every field equal,
 * every number to the bit, but the clocks (a run's {@code frozenAt}, a view's {@code fetchedAt} and {@code timings}) and each server's
 * own folder in the texts (written {@code <server>}). Skipped when no folder of Risk JARs is given (-Ddrishti.risk.jars).
 */
class ConsumersSideBySideTest {

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("consumers-side-by-side");

    @Test
    void theVarConsumersAreTheSameOnBothPaths() throws Exception {
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
        assertThat(inTree.size() - refused).as("answers compared").isGreaterThan(0);
        long pages = inTree.entrySet().stream().filter(e -> !e.getKey().startsWith("report ") && figures(e.getValue()) > 0).count();
        int items = inTree.entrySet().stream().filter(e -> !e.getKey().startsWith("report ")).mapToInt(e -> figures(e.getValue())).sum();
        System.out.println("side by side, desk, legal-entity, system-basket and position pages: " + pages + " with the engine's VaR figures, " + items + " items shown");
        assertThat(pages).as("desk and legal-entity pages with VaR figures compared").isEqualTo(inTree.size() - 2L);
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
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DRISHTI_VAR_CONSUMERS_ROUTE", path);
        if (RiskSideBySideTest.HEAP) {                                 // -Dsbs.tier=heap: float64 rows on both paths
            env.put("DRISHTI_VAR_TIER", "heap");
            env.put("DRISHTI_VAR_MEMORY_MB", "900");
            env.put("DRISHTI_VAR_OPEN_HEAP_MB", "700");
        }
        env.put("DRISHTI_VAR_ZERO_COPY", System.getProperty("sbs.zero-copy", "off"));   // -Dsbs.zero-copy=on: the host's rows handed over in place
        PackagedServer server = PackagedServer.start(work, lake, "2026-09-30", env);
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
            // the pages' VaR figures (VarFigures, VAR_ENGINE): every desk and entity, every system basket, and three positions of each
            for (String kind : List.of("desk", "legal-entity", "system-basket", "position")) {
                for (String id : kind.startsWith("system") || kind.equals("position") ? seeded(kind) : ids(kind)) {
                    JsonNode v;
                    try {
                        v = server.get("/api/v1/views/" + kind + "/" + id + "?asOf=2026-09-30");
                        strip(v, work.toString());
                    } catch (IllegalStateException e) {
                        v = PackagedServer.JSON.readTree("{\"refused\":" + PackagedServer.JSON.writeValueAsString(e.getMessage()) + "}");
                    }
                    out.put(kind + " " + id, v);
                }
            }
            if (path.equals("engine")) {                              // the runs were the engine's (the switch reached the reports)
                assertThat(Files.readString(work.resolve("server.log"))).contains("figures from the market-risk engine");
            }
            return out;
        } finally {
            server.close();
        }
    }

    /** The ids of a kind's records of the date in the lake's test data (core/desk, core/legal-entity). */
    private static List<String> ids(String kind) throws IOException {
        Path f = PackagedServer.REPO.resolve("data/testdata/core/" + kind + "/" + kind + ".2026-09-30.jsonl.gz");
        List<String> out = new ArrayList<>();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(Files.newInputStream(f)),
                java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                JsonNode n = PackagedServer.JSON.readTree(line);
                out.add(n.path(kind.equals("desk") ? "DESK_ID" : "ENTITY_ID").asText());
            }
        }
        return out;
    }

    /**
     * The system baskets of the seed the server imports (market-risk seeds/system-baskets.yaml) and the first three member positions
     * of each (unique, in the file's order).
     */
    private static List<String> seeded(String kind) throws IOException {
        List<String> out = new ArrayList<>();
        int taken = 0;
        for (String line : Files.readAllLines(PackagedServer.REPO.resolve("modules/banking/packs/market-risk/seeds/system-baskets.yaml"))) {
            String t = line.trim();
            if (t.startsWith("- id: ")) {
                taken = 0;
                if (kind.equals("system-basket")) {
                    out.add(t.substring(6).trim());
                }
            } else if (kind.equals("position") && t.startsWith("- position: ") && taken < 3) {
                String id = t.substring(12).trim();
                taken++;
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** How many of a view's items show the engine's VaR figures (their {@code path} under {@code $.VAR_ENGINE}). */
    static int figures(JsonNode n) {
        int c = n.isTextual() && n.asText().startsWith("$.VAR_ENGINE") ? 1 : 0;
        for (JsonNode x : n) {
            c += figures(x);
        }
        return c;
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

    /** Leaves out the clocks and writes each server's own folder (in a definition's file and the cells' lineage) as {@code <server>}. */
    private static void strip(JsonNode n, String folder) {
        if (n instanceof ObjectNode o) {
            o.remove("frozenAt");
            o.remove("fetchedAt");                                    // a view's clock and its own timings
            o.remove("timings");
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
