/*
 * Drishti · real-time risk and analytics platform.
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
 * The source-text half of the Drishti Tools adoption rules (the bytecode half is {@code ArchitectureRulesTest}): these also read the test code and
 * the Python the server never runs. One digest, one HTTP client builder, one CSV quoting, one workbook writer on the server.
 */
class ToolsAdoptionGuardTest {

    private static List<Path> javaFiles() {
        return RepositoryFiles.withSuffixes(Set.of(".java"));
    }

    private static List<String> codeLines(Path p, String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        int n = 0;
        for (String line : Files.readAllLines(p)) {
            n++;
            String t = line.strip();
            if (t.contains(needle) && !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")) {
                hits.add(RepositoryFiles.root().relativize(p) + ":" + n);
            }
        }
        return hits;
    }

    private static List<String> find(String needle, String... exceptFiles) throws IOException {
        return find(false, needle, exceptFiles);
    }

    private static List<String> find(boolean mainOnly, String needle, String... exceptFiles) throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : javaFiles()) {
            String rel = RepositoryFiles.root().relativize(p).toString().replace('\\', '/');
            if (rel.endsWith("ToolsAdoptionGuardTest.java") || rel.endsWith("ArchitectureRulesTest.java") || rel.startsWith("docs/") || (mainOnly && rel.contains("/src/test/"))
                    || java.util.Arrays.stream(exceptFiles).anyMatch(rel::endsWith)) {
                continue;
            }
            hits.addAll(codeLines(p, needle));
        }
        return hits;
    }

    @Test
    void noDigestIsMadeByHand() throws IOException {
        assertThat(find("MessageDigest.getInstance(")).as("use tools-codec Hashes (tests included)").isEmpty();
    }

    @Test
    void noHttpClientIsBuiltByHand() throws IOException {
        assertThat(find(true, "HttpClient.newBuilder()", "DrishtiTools.java")).as("use DrishtiTools.httpBuilder (main code: the tests' own localhost clients are not the product's)")
                .isEmpty();
    }

    @Test
    void noCsvFieldIsQuotedByHand() throws IOException {
        assertThat(find("replace(\"\\\"\", \"\\\"\\\"\")", "DuckDbExecutor.java", "ModelAnswers.java", "SimmPlaygroundBook.java", "FrtbPlaygroundBook.java",
                "Dialect.java", "SqlComposer.java", "CassandraLayout.java", "DuckDbLayout.java"))
                .as("a doubled quote is CSV quoting (tools-csv CsvWriter.quote) or SQL identifier quoting (the listed files)").isEmpty();
    }

    @Test
    void noJavaCodeRunsAPythonWorkbookWriter() throws IOException {
        // the console's core/export.py and tools/sizing_planner.py write workbooks in Python (the owner keeps them); the server writes its own with tools-xlsx only
        List<String> hits = new ArrayList<>();
        for (String needle : List.of("sizing_planner", "to_xlsx", "export_routes", "core/export.py")) {
            hits.addAll(find(true, needle, "CapacityWorkbook.java", "CapacityPlanner.java"));   // these two name the generator in a message only
        }
        assertThat(hits).as("no Java path calls the Python xlsx writers").isEmpty();
    }
}
