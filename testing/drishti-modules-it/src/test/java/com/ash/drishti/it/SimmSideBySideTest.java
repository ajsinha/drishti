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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * R2 side by side for SIMM (docs/plans/R2_CUT_PLAN.md, Appendix A): one packaged server over the sample day with Drishti Risk's JARs in
 * {@code lib/engines}; {@code GET /api/v1/risk/simm} asked in-tree and through the {@code ccr} engine for every netting set the person sees,
 * with the default switches and with concentration and the historical volatility ratios off. The engine reads every SIMM input as a lake
 * table bound in {@code config/engines/ccr.yaml} (the position sensitivities' elements, the factor dictionary, the licensed parameter
 * files as the {@code simm-parameters} kind: the test data holds the TOY file of the SIMM tests, never ISDA's); the in-tree path reads the
 * same TOY file from its parameter folder ({@code DRISHTI_SIMM_PARAMETERS}). Every figure of the answer (both directions, by product class
 * and by risk class, the version's SHA-256, the regimes, trades) must be equal, numbers as JSON (every double to the bit). Both tiers
 * ({@code -Dsbs.tier=heap}: the exposure tier the server keeps; SIMM reads no vector). Run as {@link ExposureSideBySideTest}.
 */
class SimmSideBySideTest {

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("side-by-side-simm");
    static final String DATE = "2026-09-30";
    static final String ONLY = ExposureSideBySideTest.ONLY + ",market-risk/position-sensitivity,market-risk/risk-factor,counterparty-risk/simm-parameters"
            // the VaR book: the in-tree run takes the person's rows from the VaR scope members (the engine path does too)
            + ",market-risk/scenario-pv,market-risk/fx-scenario,market-risk/scenario-set,market-risk/var-limit,trades/";
    /** Every key of the in-tree headline but its provenance (the host's words about what it read). */
    static final String[] FIELDS = {"SCOPE", "KIND", "ANALYTIC", "NETTING_SET", "COUNTERPARTY", "CSA", "BUSINESS_DATE", "SIMM_VERSION", "PARAMETER_SHA256",
        "CALCULATION_CURRENCY", "IM_COLLECT", "IM_POST", "COLLECT_REGIME", "POST_REGIME", "SIMM_COLLECT", "ADDON_COLLECT", "SIMM_POST", "ADDON_POST",
        "BY_PRODUCT_CLASS", "BY_RISK_CLASS", "TRADES", "MISSING_TRADES", "SWITCHES", "OVERRIDES", "REGULATORY", "notRegulatory", "label", "refused"};
    static PackagedServer server;

    @BeforeAll
    static void start() throws Exception {
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        Path lake = FOLDER.resolve("lake");
        PackagedServer.buildLake(lake, ONLY);
        Path work = FOLDER.resolve("server");
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        // the in-tree path's parameter folder: the same TOY file the lake's simm-parameters row holds (data/testdata/counterparty-risk/simm-parameters)
        Path params = Files.createDirectories(FOLDER.resolve("simm-parameters"));
        Files.copy(PackagedServer.REPO.resolve("modules/banking/java/banking-compute/src/test/resources/simm/simm-toy.yaml"), params.resolve("simm-toy.yaml"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DRISHTI_SIMM_PARAMETERS", params.toString());
        String tier = System.getProperty("sbs.tier", "");
        if (!tier.isBlank()) {
            env.put("DRISHTI_EXPOSURE_TIER", tier);
        }
        server = PackagedServer.start(work, lake, DATE, env);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void theSimmOfEveryNettingSetIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        List<String> sets = new ArrayList<>();
        // every netting set of the date (the exposure tree lists them all, with or without paths); the SIMM list itself shows only the
        // sets whose trade ids are among the VaR members, which the sample's position-grain members are not (both paths ask the same)
        JsonNode t = server.get("/api/v1/risk/exposure/tree?asOf=" + DATE);
        for (JsonNode g : t.path("groups")) {
            for (JsonNode cp : g.path("children")) {
                for (JsonNode ns : cp.path("children")) {
                    String sc = ns.path("scope").asText("");
                    if (sc.startsWith("NSET:") && !sets.contains(sc.substring(5))) {
                        sets.add(sc.substring(5));
                    }
                }
            }
        }
        int withFigures = 0;
        int refusedBoth = 0;
        List<String> engineOnlyRefused = new ArrayList<>();
        for (String set : sets) {
            for (String extra : List.of("", "&concentration=off", "&hvr=off&concentration=off")) {
                String q = "/api/v1/risk/simm?asOf=" + DATE + "&nettingSet=" + set + extra;
                Outcome o = ask(sbs, set + extra, q);
                if (o == Outcome.FIGURES) {
                    withFigures++;
                } else if (o == Outcome.BOTH_REFUSED) {
                    refusedBoth++;
                } else {
                    engineOnlyRefused.add(set + extra);
                }
            }
        }
        // two analytic scopes (the host resolves the records, calculation currency USD, no regime)
        for (String scope : List.of("BANK", "DESK:DESK-RATES")) {
            Outcome o = ask(sbs, scope, "/api/v1/risk/simm?asOf=" + DATE + "&scope=" + scope);
            if (o == Outcome.FIGURES) {
                withFigures++;
            } else if (o == Outcome.BOTH_REFUSED) {
                refusedBoth++;
            } else {
                engineOnlyRefused.add(scope);
            }
        }
        System.out.println("side by side, SIMM: " + sets.size() + " netting sets, " + sbs.compared + " answers with figures on both paths ("
                + withFigures + "), refused on both " + refusedBoth + ", refused on the engine path only " + engineOnlyRefused.size() + " "
                + engineOnlyRefused.stream().limit(10).toList() + "; first differences: " + sbs.differences.stream().limit(Integer.getInteger("sbs.show", 10)).toList());
        assertThat(withFigures).as("answers with figures on both paths").isGreaterThan(30);
        assertThat(engineOnlyRefused).as("answered in-tree, refused on the engine path").isEmpty();
        assertThat(sbs.differences).as("SIMM answers that differ between in-tree and engine").isEmpty();
    }

    static final String[] FIELDS_IN_ORDER = java.util.Arrays.stream(FIELDS).filter(f -> !f.equals("BY_RISK_CLASS")).toArray(String[]::new);

    /** The rows by product class and risk class, each row's JSON (numbers as written: every double to the bit). */
    private static java.util.TreeMap<String, String> sorted(JsonNode rows) {
        java.util.TreeMap<String, String> out = new java.util.TreeMap<>();
        rows.forEach(r -> out.put(r.path("PRODUCT_CLASS").asText() + "|" + r.path("RISK_CLASS").asText(), r.toString()));
        return out;
    }

    enum Outcome { FIGURES, BOTH_REFUSED, ENGINE_REFUSED }

    static final List<String> LAST = new ArrayList<>();

    private static Outcome ask(SideBySide sbs, String what, String q) throws IOException, InterruptedException {
        JsonNode inTree;
        try {
            inTree = server.get(q + "&path=in-tree");
        } catch (IllegalStateException e) {
            try {
                server.get(q + "&path=engine");
                sbs.differences.add(what + ": refused in-tree (" + e.getMessage() + "), answered on the engine path");
            } catch (IllegalStateException x) {
                if (!status(e).equals(status(x))) {
                    sbs.differences.add(what + ": refused with " + status(e) + " in-tree and " + status(x) + " on the engine path: " + x.getMessage());
                }
            }
            return Outcome.BOTH_REFUSED;
        }
        try {
            server.get(q + "&path=engine");
        } catch (IllegalStateException x) {
            LAST.add(what + ": " + x.getMessage());
            System.out.println("engine path refused " + what + ": " + x.getMessage());
            return Outcome.ENGINE_REFUSED;
        }
        assertThat(inTree.path("SCOPE").isMissingNode()).isFalse();
        JsonNode[] both = sbs.both(server, what, q, FIELDS_IN_ORDER);
        // BY_RISK_CLASS: every row equal; the in-tree run lists the classes as its elements first name them, the engine path in SIMM's order
        if (!sorted(both[0].path("BY_RISK_CLASS")).equals(sorted(both[1].path("BY_RISK_CLASS")))) {
            sbs.differences.add(what + " BY_RISK_CLASS: " + SideBySide.first(both[0].path("BY_RISK_CLASS"), both[1].path("BY_RISK_CLASS")));
        }
        return Outcome.FIGURES;
    }

    private static String status(IllegalStateException e) {
        String m = String.valueOf(e.getMessage());
        int sp = m.indexOf(' ');
        return sp > 0 ? m.substring(0, sp) : m;
    }
}
