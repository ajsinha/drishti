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
import java.util.Iterator;
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
                        + "&asOf=" + k[0] + "&ccy=" + k[2] + "&window=" + k[1],
                        // limit use only where the golden holds it (USD, 509 scenarios: a limit is the 509-day USD VaR's); elsewhere the
                        // in-tree route divides by another VaR than the one asked (R2_CUT_PLAN appendix A, open)
                        k[2].equals("USD") && k[1].equals("509") ? new String[] {"trades", "var", "es", "limit", "limitUse"}
                                : new String[] {"trades", "var", "es", "limit"});
                JsonNode g = golden.get(c).get("nodes").get(id);
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
