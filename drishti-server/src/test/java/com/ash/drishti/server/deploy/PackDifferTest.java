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
package com.ash.drishti.server.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Java diff and tools/packdiff.py must say the same thing about the same two packs: both suites read
 * tools/testdata/packdiff (old, new, and expected.json, which the Python implementation produced).
 */
class PackDifferTest {

    private static final Path CASES = Path.of("..", "tools", "testdata", "packdiff");

    @Test
    void sayTheSameAsThePythonCliFindingForFinding() throws Exception {
        List<PackDiffer.Finding> found = PackDiffer.diff(PackDiffer.load(CASES.resolve("old")), PackDiffer.load(CASES.resolve("new")));
        JsonNode expected = new ObjectMapper().readTree(CASES.resolve("expected.json").toFile());
        List<String> want = new ArrayList<>();
        expected.forEach(n -> want.add(n.path("level").asText() + "|" + n.path("what").asText() + "|" + n.path("name").asText() + "|" + n.path("detail").asText()));
        List<String> got = found.stream().map(f -> f.level() + "|" + f.what() + "|" + f.name() + "|" + f.detail()).toList();
        assertThat(got).containsExactlyElementsOf(want);
    }

    @Test
    void countsPerLevelInTheFixedOrder() throws Exception {
        var counts = PackDiffer.counts(PackDiffer.diff(PackDiffer.load(CASES.resolve("old")), PackDiffer.load(CASES.resolve("new"))));
        assertThat(counts.keySet()).containsExactly("breaking", "selection", "layout", "change");
        assertThat(counts.get("breaking")).isEqualTo(4);        // kind renamed, kind removed, mnemonic renamed, mnemonic removed
    }

    @Test
    void identicalPacksDifferInNothing() throws Exception {
        assertThat(PackDiffer.diff(PackDiffer.load(CASES.resolve("old")), PackDiffer.load(CASES.resolve("old")))).isEmpty();
    }
}
