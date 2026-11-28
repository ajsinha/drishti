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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * R2 speed check (docs/plans/R2_CUT_PLAN.md step 4a, owner: 100,000 positions are enough): one packaged server over a lake of
 * {@code tools/bench/vargen.py} P&amp;L vectors ({@code -Dspeed.positions}, one OTC trade a position, 509 scenarios, 2026-09-30) with
 * Drishti Risk's JARs in {@code lib/engines}; the VaR headline asked in-tree and through the {@code market-risk} engine: the first open of
 * the date on each path, then every node of the management hierarchy warm (two rounds) and the leaf nodes (books: their parts are
 * positions, the leaf headline's cost), p50 and p99 per path, the server's resident memory before and after the engine opens the date, and
 * the figures compared (VaR and ES of every node asked equal between the paths). Gated on {@code -Dspeed.positions}; the tier as the side
 * by side ({@code -Dsbs.tier=heap}; default mapped); the heap by {@code -Dsbs.xmx}. One heavy job: never with another speed run.
 */
class RiskSpeedCheckTest {

    static final String DATE = "2026-09-30";
    static final String ONLY = "market-risk/scenario-set,market-risk/var-limit,core/desk,core/book,core/legal-entity";
    static int positions;
    static Path folder;
    static PackagedServer server;
    static Path work0;
    /** {@code -Dspeed.zero-copy=on}: the host's float32 rows handed to the engine in place (R2_CUT_PLAN 3a); off: the engine builds its own. */
    static final String ZERO_COPY = System.getProperty("speed.zero-copy", "off");

    @BeforeAll
    static void start() throws Exception {
        positions = Integer.getInteger("speed.positions", 0);
        assumeTrue(positions > 0 && positions <= 100_000, "no speed check asked (-Dspeed.positions, at most 100,000)");
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        folder = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("speed-" + positions);
        Path lake = folder.resolve("lake");
        PackagedServer.buildLake(lake, ONLY);
        Path vargen = folder.resolve("vargen");
        if (!Files.exists(vargen.resolve("LOADED"))) {
            run(folder.resolve("vargen.log"), System.getProperty("speed.python", "python3"), PackagedServer.REPO.resolve("tools/bench/vargen.py").toString(),
                    "--out", vargen.toString(), "--positions", Integer.toString(positions), "--portfolios", "300", "--dates", DATE, "--big-baskets", "0",
                    "--kinds", "pnl-vector,scenario-set,var-limit");
            Map<String, String> keys = Map.of("pnl-vector", "VECTOR_ID", "scenario-set", "SCENARIO_SET_ID", "var-limit", "LIMIT_ID");
            for (String kind : List.of("scenario-set", "var-limit", "pnl-vector")) {
                run(folder.resolve("load-" + kind + ".log"), PackagedServer.lakePython(), PackagedServer.REPO.resolve("tools/drishti.py").toString(), "lake",
                        "build", "--from", vargen.resolve(kind).toString(), "--pack", PackagedServer.REPO.resolve("modules/banking/packs/market-risk").toString(),
                        "--connector", "risk-store", "--kind", kind, "--key", keys.get(kind), "--mode", "overwrite-dates", "--lake",
                        lake.resolve("delta").toString(), "--engine", "java", "-q");
            }
            Files.writeString(vargen.resolve("LOADED"), "loaded\n");
        }
        String tier = System.getProperty("sbs.tier", "");
        Path work = folder.resolve("server-" + (tier.isBlank() ? "mapped" : tier) + "-zc-" + ZERO_COPY);
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DRISHTI_VAR_SYSTEM_BASKETS_SEED", "");                 // the sample's baskets name sample positions: none here
        if (!tier.isBlank()) {
            env.put("DRISHTI_VAR_TIER", tier);
        }
        env.put("DRISHTI_VAR_MEMORY_MB", System.getProperty("speed.var-memory-mb", "3000"));   // the in-tree date's room (heap tier: float64 rows)
        env.put("DRISHTI_VAR_OPEN_HEAP_MB", System.getProperty("speed.open-heap-mb", "2500"));
        env.put("DRISHTI_VAR_ZERO_COPY", ZERO_COPY);
        env.put("DRISHTI_MARKET_RISK_MEMORY_MB", System.getProperty("speed.engine-memory-mb", "3000"));   // the engine's books of the date                   // -Dspeed.zero-copy=on: the hot file's rows handed to the engine in place
        work0 = work;
        server = PackagedServer.start(work, lake, DATE, env);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    private static void run(Path log, String... cmd) throws IOException, InterruptedException {
        Files.createDirectories(log.getParent());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(PackagedServer.REPO.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().put("DRISHTI_DATA_DIR", folder.resolve("data").toString());
        pb.environment().put("DRISHTI_TMP_DIR", folder.resolve("tmp").toString());
        Process p = pb.start();
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IllegalStateException(String.join(" ", cmd) + " failed: see " + log);
        }
    }

    @Test
    void theEnginePathAgainstInTreeAtSpeedPositions() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("positions", positions);
        out.put("tier", System.getProperty("sbs.tier", "").isBlank() ? "mapped" : System.getProperty("sbs.tier"));
        out.put("zero_copy", ZERO_COPY);
        out.put("rss_mb_start", rssMb());
        long[] t = new long[2];
        t[0] = timed(headline("MGMT~BANK", "in-tree"));
        out.put("open_in_tree_ms", t[0]);
        out.put("rss_mb_after_in_tree_open", rssMb());
        t[1] = timed(headline("MGMT~BANK", "engine"));
        out.put("open_engine_ms", t[1]);
        out.put("rss_mb_after_engine_open", rssMb());
        List<String> nodes = new ArrayList<>();
        List<String> leaves = new ArrayList<>();
        for (JsonNode n : server.get("/api/v1/risk/var/tree?hierarchy=MGMT&asOf=" + DATE + "&path=in-tree").path("nodes")) {
            (n.path("expandable").asBoolean(true) ? nodes : leaves).add(n.path("id").asText());
        }
        out.put("nodes", nodes.size());
        out.put("leaves", leaves.size());
        List<String> differ = new ArrayList<>();
        for (String path : List.of("in-tree", "engine")) {                 // a warm-up round of the inner nodes on each path
            for (String id : nodes) {
                server.get(headline(id, path));
            }
        }
        Map<String, List<Long>> inner = new LinkedHashMap<>();
        Map<String, List<Long>> leaf = new LinkedHashMap<>();
        Map<String, List<Long>> tree = new LinkedHashMap<>();
        for (int round = 0; round < 2; round++) {
            for (String id : nodes) {
                compare(id, inner, differ);
            }
        }
        List<String> sample = leaves.subList(0, Math.min(leaves.size(), Integer.getInteger("speed.leaves", 200)));
        for (String id : sample) {
            compare(id, leaf, differ);
        }
        for (String path : List.of("in-tree", "engine")) {
            out.put("inner_p50_ms_" + path, pct(inner.get(path), 0.50));
            out.put("inner_p99_ms_" + path, pct(inner.get(path), 0.99));
            out.put("leaf_p50_ms_" + path, pct(leaf.get(path), 0.50));
            out.put("leaf_p99_ms_" + path, pct(leaf.get(path), 0.99));
        }
        out.put("leaf_asked", sample.size());
        for (int round = 0; round < 3; round++) {                           // the tree of the hierarchy (the explorer's left pane), warm
            JsonNode[] tr = new JsonNode[2];
            int i = 0;
            for (String path : List.of("in-tree", "engine")) {
                long t0 = System.nanoTime();
                tr[i++] = server.get("/api/v1/risk/var/tree?hierarchy=MGMT&asOf=" + DATE + "&path=" + path);
                tree.computeIfAbsent(path, k -> new ArrayList<>()).add((System.nanoTime() - t0) / 1_000_000);
            }
            if (round == 0) {
                Map<String, String> a = new LinkedHashMap<>();
                tr[0].path("nodes").forEach(n -> a.put(n.path("id").asText(), n.path("var").asText() + "/" + n.path("es").asText()));
                Map<String, String> b = new LinkedHashMap<>();
                tr[1].path("nodes").forEach(n -> b.put(n.path("id").asText(), n.path("var").asText() + "/" + n.path("es").asText()));
                if (!a.equals(b)) {
                    differ.add("tree: " + a.size() + " rows in-tree, " + b.size() + " engine, not equal");
                }
                out.put("tree_rows", a.size());
            }
        }
        for (String path : List.of("in-tree", "engine")) {
            out.put("tree_first_ms_" + path, tree.get(path).get(0));
            out.put("tree_p50_ms_" + path, pct(tree.get(path), 0.50));
        }
        out.put("figures_differ", differ.size());
        out.put("rss_mb_end", rssMb());
        out.put("zero_copy_served", Files.readString(work0.resolve("server.log")).contains("served to the engine in place"));
        String line = PackagedServer.JSON.writeValueAsString(out);
        System.out.println("speed check: " + line + "; first differences " + differ.stream().limit(5).toList());
        Files.writeString(folder.resolve("speed-" + out.get("tier") + "-zc-" + ZERO_COPY + ".json"), line + "\n");
        assertThat(differ).as("VaR and ES that differ between the paths").isEmpty();
    }

    private static void compare(String id, Map<String, List<Long>> times, List<String> differ) throws IOException, InterruptedException {
        JsonNode[] a = new JsonNode[2];
        int i = 0;
        for (String path : List.of("in-tree", "engine")) {
            long t0 = System.nanoTime();
            a[i++] = server.get(headline(id, path));
            times.computeIfAbsent(path, k -> new ArrayList<>()).add((System.nanoTime() - t0) / 1_000_000);
        }
        for (String f : List.of("var", "es", "trades")) {
            if (!a[0].path(f).asText().equals(a[1].path(f).asText())) {
                differ.add(id + " " + f + ": in-tree " + a[0].path(f) + ", engine " + a[1].path(f));
            }
        }
    }

    private static String headline(String id, String path) {
        return "/api/v1/risk/var/scope?scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8) + "&asOf=" + DATE + "&path=" + path;
    }

    private static long timed(String q) throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        server.get(q);
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static long pct(List<Long> xs, double q) {
        if (xs == null || xs.isEmpty()) {
            return -1;
        }
        List<Long> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.ceil(q * s.size()) - 1));
    }

    private static long rssMb() throws IOException {
        for (String l : Files.readAllLines(Path.of("/proc/" + server.pid() + "/status"))) {
            if (l.startsWith("VmRSS:")) {
                return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
            }
        }
        return -1;
    }
}
