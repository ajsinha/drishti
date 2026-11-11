package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every source, config, template and document file carries the proprietary copyright header. */
class LicenseHeaderTest {

    private static final String MARK = "Copyright (c) 2026 Ashutosh Sinha";
    private static final Set<String> SUFFIXES =
            Set.of(".java", ".py", ".js", ".css", ".html", ".yaml", ".yml", ".xml", ".md", ".properties", ".sh");

    @Test
    void everyFileCarriesTheHeader() throws IOException {
        List<Path> missing = new java.util.ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(SUFFIXES)) {
            String head = Files.readString(p);
            if (!head.substring(0, Math.min(head.length(), 2000)).contains(MARK)) {
                missing.add(RepositoryFiles.root().relativize(p));
            }
        }
        assertThat(missing).as("run: python3 tools/license_headers.py --fix").isEmpty();
    }
}
