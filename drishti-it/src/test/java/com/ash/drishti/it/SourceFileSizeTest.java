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

/** No non-UX source file may exceed 1500 lines. UX templates, styles and scripts are exempt. */
class SourceFileSizeTest {

    static final int MAX_LINES = 1500;

    @Test
    void sourceFilesStayUnderTheLimit() throws IOException {
        List<String> over = new ArrayList<>();
        Path root = RepositoryFiles.root();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java", ".py", ".yaml", ".yml", ".xml"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (rel.startsWith("drishti-console/web/")) {
                continue;
            }
            long lines;
            try (var s = Files.lines(p)) {
                lines = s.count();
            }
            if (lines > MAX_LINES) {
                over.add(rel + " (" + lines + ")");
            }
        }
        assertThat(over).isEmpty();
    }
}
