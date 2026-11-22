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
 * No non-UX source file may exceed the one code line cap ({@code tools/code_cap.py} CODE_LINE_CAP, which the Python guards import too).
 * UX templates, styles and scripts are exempt here (the console's guards count them), and so is the generated test data.
 */
class SourceFileSizeTest {

    static final int MAX_LINES = cap();

    /** The one cap, read from tools/code_cap.py ({@code CODE_LINE_CAP = <n>}). */
    static int cap() {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^CODE_LINE_CAP = (\\d+)$")
                    .matcher(Files.readString(RepositoryFiles.root().resolve("tools").resolve("code_cap.py")));
            if (!m.find()) {
                throw new IllegalStateException("tools/code_cap.py has no CODE_LINE_CAP = <n> line");
            }
            return Integer.parseInt(m.group(1));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void theCapIsTheKitsCap() {
        assertThat(MAX_LINES).as("tools/code_cap.py and Drishti Tools' guard kit hold one cap").isEqualTo(com.ash.tools.guards.kit.GuardFiles.MAX_LINES);
    }

    @Test
    void sourceFilesStayUnderTheLimit() throws IOException {
        Path root = RepositoryFiles.root();
        List<Path> files = new ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java", ".py", ".yaml", ".yml", ".xml"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (!rel.startsWith("drishti-console/web/") && !rel.startsWith("data/testdata/")) {   // the console's guards count the UX files; the committed test data is not source
                files.add(p);
            }
        }
        assertThat(com.ash.tools.guards.kit.HeaderAndLineCap.overCap(root, files)).isEmpty();
    }
}
