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
package com.ash.drishti.server;

import com.ash.lake.tck.TestRoots;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * INTERNAL TEST DATA, never shipped: the IMF World Economic Outlook samples of the world-economy pack (economy, macro-indicator,
 * economic-forecast, fiscal-position). The economy module's package leaves them out (content.yaml {@code restricted:}, a licence: internal
 * demos only), and the servers of this project run on the packages, so a test that draws what only those samples hold reads them from
 * here: the files of the checkout's module source, wrapped as a file connector's records in a folder of the test run and routed to their
 * kinds. The package itself is untouched (OnPackagesOnlyTest); nothing is copied anywhere but the run's own target/test-data folder.
 */
final class InternalImfFixture {

    /** The kinds whose samples are IMF-derived (the economy module's restricted list). */
    static final List<String> KINDS = List.of("economy", "macro-indicator", "economic-forecast", "fiscal-position");

    private static final String CONNECTOR = "internal-imf-fixture";
    private static final ObjectMapper JSON = new ObjectMapper();

    private InternalImfFixture() {
    }

    /** Gives the server a file connector holding (its file: {@code testing/connectors/internal-imf-fixture}; the tests name that folder as their connectors-dir) the IMF samples, routed for {@link #KINDS}. */
    static void register(DynamicPropertyRegistry r) throws IOException {
        Path samples = Path.of(TestRoots.repo(), "modules", "external-data", "packs", "world-economy", "samples");
        Path day = TestRun.root().resolve(CONNECTOR);
        Files.createDirectories(day);
        List<String> catalogue = Files.readAllLines(samples.resolve("catalog.jsonl"), StandardCharsets.UTF_8);
        r.add("test-connectors." + CONNECTOR + ".root", day::toString);
        for (String kind : KINDS) {
            List<String> ids = new ArrayList<>();
            for (String line : catalogue) {
                if (!line.isBlank() && kind.equals(JSON.readTree(line).path("kind").asText())) {
                    ids.add(JSON.readTree(line).path("id").asText());
                }
            }
            List<String> lines = Files.readAllLines(samples.resolve(kind + ".jsonl"), StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
            if (lines.size() != ids.size()) {
                throw new IllegalStateException(kind + ": " + lines.size() + " samples, " + ids.size() + " catalogue entries");
            }
            List<String> out = new ArrayList<>();
            for (int n = 0; n < lines.size(); n++) {
                out.add("{\"kind\":" + JSON.writeValueAsString(kind) + ",\"id\":" + JSON.writeValueAsString(ids.get(n)) + ",\"doc\":" + lines.get(n) + "}");
            }
            Files.write(day.resolve(kind + ".jsonl"), out, StandardCharsets.UTF_8);
            r.add("drishti.sources.routes." + kind, () -> CONNECTOR);
        }
    }
}
