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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guard G-flat (i7a, S6 of the impact analysis): banking's Spring tests and the VaR goldens run banking on the flat test class path
 * ({@code BankingTestHarness}: one context, no module loader, no bean-name prefix), so they cannot catch a breakage of the module path.
 * This lists exactly which tests do ({@code flat-harness-tests.txt}) and fails when a test starts using the flat harness without being
 * added to the list, or is gone and still listed. G14 ({@code PackagedVarGoldenTest}, I7) is the module-path counterpart.
 */
class FlatHarnessInventoryTest {

    private static final Set<String> GOLDENS = Set.of("VarGoldenMatrixTest.java", "SystemBasketGoldenTest.java", "HierarchyEquivalenceTest.java");
    private static final List<String> MARKS = List.of("SpringBootTest", "BankingTestHarness", "BankingTestServer");

    @Test
    void theTestsOnTheFlatHarnessAreExactlyTheListed() throws IOException {
        Path root = RepositoryFiles.root();
        List<String> found = flat(root);
        Path file = root.resolve("testing/drishti-it/src/test/resources/flat-harness-tests.txt");
        if (Boolean.getBoolean("drishti.flat.write")) {
            List<String> lines = new ArrayList<>(Files.readAllLines(file).stream().filter(l -> l.startsWith("#")).toList());
            lines.addAll(found);
            Files.write(file, lines);
        }
        List<String> listed = Files.readAllLines(file).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        List<String> added = found.stream().filter(f -> !listed.contains(f)).toList();
        List<String> gone = listed.stream().filter(f -> !found.contains(f)).toList();
        assertThat(added).as("tests now on the flat harness but not in flat-harness-tests.txt (prove the module path with G14, then list them)").isEmpty();
        assertThat(gone).as("listed in flat-harness-tests.txt but no longer on the flat harness").isEmpty();
    }

    /** The tests under {@code modules/} that run on the flat harness: a Spring boot test, the harness or its server, or a golden. */
    static List<String> flat(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (!rel.startsWith("modules/") || !rel.contains("/src/test/")) {
                continue;
            }
            String text = Files.readString(p);
            if (GOLDENS.contains(p.getFileName().toString()) || MARKS.stream().anyMatch(text::contains)) {
                out.add(rel);
            }
        }
        return out;
    }
}
