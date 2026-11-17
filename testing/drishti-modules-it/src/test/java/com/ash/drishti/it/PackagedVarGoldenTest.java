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

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Guard G14 (i7a): the VaR golden matrix through a server on the module path. {@code VarGoldenMatrixTest} and {@code SystemBasketGoldenTest}
 * (in {@code drishti-banking}) run banking on the flat test class path: one context, no module loader, no loader order, no second copy of a
 * maths library, no missing {@code ServiceLoader} provider. This test starts the exec jar with the built {@code .dmod} packages beside it
 * ({@link PackagedServer}), over a lake of the same committed test data, and asks it, over HTTP, for every case of the same golden file
 * ({@code var/golden-matrix.json.gz}): three dates, the 509 and 250 windows, USD and EUR, every node of the three hierarchies and the 28
 * system baskets.
 *
 * <p>What the API answers is compared with the golden exactly where the API carries the same figure: the node's members, its VaR and ES (the API
 * rounds to whole currency units: the golden's figure rounded the same way, equal), its limit, and its limit use (to 1e-6, as the golden test
 * compares it). The tree (parents and children) is compared as the golden holds it. Worst scenarios and component VaR are not in a headline:
 * the flat tests keep them.
 */
class PackagedVarGoldenTest {

    static final String GOLDEN = "modules/banking/java/banking-service/src/test/resources/var/golden-matrix.json.gz";
    /** The tables the VaR engine reads (the browser files' lake: VaR tables, limits, the desk, book and entity masters, and both trade sets). */
    static final String ONLY = "market-risk/scenario-pv,market-risk/fx-scenario,market-risk/scenario-set,market-risk/var-limit,core/desk,core/book,core/legal-entity,"
            + "trades/murex,trades/endur";
    static final Path FOLDER = Path.of("target", "g14").toAbsolutePath();

    static PackagedServer server;
    static JsonNode golden;

    @BeforeAll
    static void start() throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(PackagedServer.REPO.resolve(GOLDEN)))) {
            golden = PackagedServer.JSON.readTree(in);
        }
        Path lake = FOLDER.resolve("lake");
        PackagedServer.buildLake(lake, ONLY);
        Path work = FOLDER.resolve("server");
        deleteTree(work);
        server = PackagedServer.start(work, lake, "2026-09-30");
    }

    @AfterAll
    static void stop() throws IOException {
        if (server != null) {
            server.close();
        }
        deleteTree(FOLDER.resolve("server"));
    }

    @Test
    void everyCaseOfTheGoldenMatrixAsTheServerOnPackagesAnswersIt() throws Exception {
        assertThat(golden.size()).as("three dates x two windows x two currencies").isEqualTo(12);
        List<String> wrong = new ArrayList<>();
        int compared = 0;
        Iterator<String> cases = golden.fieldNames();
        while (cases.hasNext()) {
            String c = cases.next();
            String[] k = c.split("/");
            JsonNode want = golden.get(c);
            for (String section : List.of("nodes", "baskets")) {
                Iterator<Map.Entry<String, JsonNode>> it = want.get(section).fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    compare(wrong, c, section.equals("nodes") ? "NODE:" : "PORT:", e.getKey(), e.getValue(), k[0], k[1], k[2]);
                    compared++;
                }
            }
        }
        assertThat(compared).as("scopes asked").isGreaterThan(1500);
        assertThat(wrong).as("figures that differ from the golden").isEmpty();
    }

    private static void compare(List<String> wrong, String c, String prefix, String id, JsonNode w, String date, String window, String ccy) throws Exception {
        String scope = prefix + id;
        JsonNode got = server.get("/api/v1/risk/var/scope?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) + "&asOf=" + date + "&ccy=" + ccy
                + (window.equals("509") ? "" : "&window=" + window));
        String what = c + " " + scope;
        same(wrong, what + " members", w.get("members").asLong(), got.path("trades").asLong());
        same(wrong, what + " VaR", Math.round(w.get("var").asDouble()), got.path("var").asLong());
        same(wrong, what + " ES", Math.round(w.get("es").asDouble()), got.path("es").asLong());
        if (ccy.equals("USD") && window.equals("509")) {                      // limits are in USD, and a limit is the 509-day VaR's
            JsonNode limit = w.get("limit");
            same(wrong, what + " limit", limit == null || limit.isNull() ? null : Math.round(limit.asDouble()), got.path("limit").isNull() ? null : got.path("limit").asLong());
            JsonNode use = w.get("limitUse");
            if (use != null && !use.isNull()) {
                if (Math.abs(use.asDouble() - got.path("limitUse").asDouble(Double.NaN)) > 1e-6) {
                    wrong.add(what + " limit use: golden " + use.asDouble() + ", server " + got.path("limitUse"));
                }
            }
        }
    }

    private static void same(List<String> wrong, String what, Object want, Object got) {
        if (!java.util.Objects.equals(want, got)) {
            wrong.add(what + ": golden " + want + ", server " + got);
        }
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

    @Test
    void theGoldenFileIsTheOneTheFlatTestsUse() {
        assertThat(PackagedServer.REPO.resolve(GOLDEN)).isRegularFile();
        Map<String, Integer> nodes = new TreeMap<>();
        golden.fieldNames().forEachRemaining(c -> nodes.put(c, golden.get(c).get("nodes").size()));
        assertThat(nodes.values()).allMatch(n -> n > 100);
    }
}
