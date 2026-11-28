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
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * R2 side by side (VaR leftovers): a leaf node holding more positions than {@code max-parts} on {@code GET /scope/contributions}: the
 * largest {@code max-parts} positions by gross size listed (the host's sizes), the sums over every position, {@code truncated},
 * {@code total} and {@code shown}. One packaged server over {@link RiskSideBySideTest}'s lake with {@code max-parts} 3 (every leaf of the
 * sample holds more), both paths asked; every field equal. Both tiers ({@code -Dsbs.tier=heap}).
 */
class RiskMaxPartsSideBySideTest {

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("side-by-side-max-parts");
    static PackagedServer server;
    static JsonNode golden;

    @BeforeAll
    static void start() throws Exception {
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(PackagedServer.REPO.resolve(PackagedVarGoldenTest.GOLDEN)))) {
            golden = PackagedServer.JSON.readTree(in);
        }
        Path lake = RiskSideBySideTest.FOLDER.resolve("lake-desk-pnl");
        PackagedServer.buildLake(lake, PackagedVarGoldenTest.ONLY + ",market-risk/desk-pnl");
        Path work = FOLDER.resolve("server");
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DRISHTI_VAR_MAX_PARTS", "3");
        String tier = System.getProperty("sbs.tier", "");
        if (!tier.isBlank()) {
            env.put("DRISHTI_VAR_TIER", tier);
            env.put("DRISHTI_VAR_MEMORY_MB", "900");
            env.put("DRISHTI_VAR_OPEN_HEAP_MB", "700");
        }
        server = PackagedServer.start(work, lake, "2026-09-30", env);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void theContributionsOfLeavesOverMaxPartsAreTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        int truncated = 0;
        int refused = 0;
        for (String date : new java.util.TreeSet<>(java.util.List.of("2026-09-28", "2026-09-29", "2026-09-30"))) {
            for (String ccy : java.util.List.of("USD", "EUR")) {
                Iterator<String> ids = golden.path(date + "/509/USD").path("nodes").fieldNames();
                while (ids.hasNext()) {
                    String id = ids.next();
                    String what = date + "/" + ccy + " NODE:" + id;
                    String q = "/api/v1/risk/var/scope/contributions?scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8) + "&asOf=" + date
                            + "&ccy=" + ccy + "&parametric=false";
                    try {
                        server.get(q + "&path=engine");
                    } catch (IllegalStateException e) {
                        assertThat(e.getMessage()).as(what).contains("path=in-tree");
                        refused++;
                        continue;
                    }
                    JsonNode[] both = sbs.both(server, what, q, "canonical", "currency", "by", "var", "es", "sumStandalone", "sumComponent",
                            "diversification", "truncated", "total", "shown", "rows");
                    truncated += both[0].path("truncated").asBoolean() ? 1 : 0;
                }
            }
        }
        System.out.println("side by side, contributions over max-parts 3 (tier " + System.getProperty("sbs.tier", "mapped") + "): " + sbs.compared
                + " answers on both paths, " + truncated + " truncated in-tree, " + refused + " refused on the engine path; differences "
                + sbs.differences.size() + "; first: " + sbs.differences.stream().limit(8).map(x -> x.substring(0, Math.min(400, x.length()))).toList());
        assertThat(truncated).as("leaf answers cut to max-parts").isGreaterThan(100);
        assertThat(refused).as("refused on the engine path").isZero();
        assertThat(sbs.differences).as("answers that differ").isEmpty();
    }
}
