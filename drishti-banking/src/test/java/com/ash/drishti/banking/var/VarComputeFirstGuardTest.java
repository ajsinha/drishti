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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Compute-first (VAR_GUIDE.md, "Input, computed, cache and the official record"; implementation plan row B5): the engine writes no
 * derived P&amp;L, position or total to the lake. Its code holds exactly two lake writers, and neither writes anything the engine computes:
 * {@link VarPublisher}, the official record's published rows (scalar figures per node, no vector), and {@link VarPvStream}, which appends
 * the intraday scenario PVs or P&amp;L vectors <b>as received</b> from the PV topic (an input, in its own form). The runtime half (a date
 * loaded, its stress set loaded and officially captured leave every input table as it was) is in {@link VarStressApiTest}.
 */
class VarComputeFirstGuardTest {

    /** The classes of the engine allowed to write to a lake, and why. */
    static final Map<String, String> WRITERS = Map.of(
            "VarPublisher.java", "the official record's published rows (risk-results/var-official)",
            "VarPvStream.java", "the PV topic's records appended as received (an input, never a derived figure)",
            "VarConfiguration.java", "makes the VarPublisher");

    /** What writes a lake table: Drishti's lake writer and command, the official sink, the Iceberg appender. */
    static final List<String> WRITING = List.of("LakeWriter", "LakeCli", "OfficialSink", "IcebergAppender", "LakeAppends");

    static Path sources() {
        Path p = Path.of("").toAbsolutePath();
        for (Path at = p; at != null; at = at.getParent()) {
            Path s = at.resolve("drishti-server/src/main/java/com/ash/drishti/server/var");
            if (Files.isDirectory(s)) {
                return s;
            }
            Path here = at.resolve("src/main/java/com/ash/drishti/server/var");
            if (Files.isDirectory(here)) {
                return here;
            }
        }
        throw new IllegalStateException("the VaR engine's sources are not above " + p);
    }

    @Test
    void onlyThePublisherAndTheInputStreamWriteALake() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> s = Files.list(sources())) {
            for (Path f : s.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(f);
                for (String w : WRITING) {
                    if (text.contains(w) && !WRITERS.containsKey(f.getFileName().toString())) {
                        found.add(f.getFileName() + " uses " + w);
                    }
                }
            }
        }
        assertThat(found).as("a class of the VaR engine that writes to a lake (compute-first: derived figures stay in memory and the hot file)")
                .isEmpty();
    }

    @Test
    void thePublishedRowsCarryNoVectorAndNoPositionOrTradeFigures() throws IOException {
        for (Map.Entry<String, String> c : VarPublisher.COLUMNS.entrySet()) {
            assertThat(c.getValue()).as(c.getKey()).isIn("string", "double", "long");
            String n = c.getKey().toUpperCase(Locale.ROOT);
            assertThat(n).as("a published column").doesNotContain("PNL_VECTOR").doesNotContain("POSITION_ID").doesNotContain("TRADE_ID")
                    .isNotEqualTo("PNL").isNotEqualTo("SCENARIO_PV");
        }
        // the input stream appends to the kinds it receives, in their own vector column: never a derived kind
        String stream = Files.readString(sources().resolve("VarPvStream.java"));
        assertThat(stream).contains("lake.resolve(\"risk\").resolve(kind)").doesNotContain("\"position\"").doesNotContain("var-official");
    }
}
