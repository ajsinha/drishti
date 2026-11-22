package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every source, config, template and document file carries the proprietary copyright header. */
class LicenseHeaderTest {

    private static final Set<String> SUFFIXES =
            Set.of(".java", ".py", ".js", ".css", ".html", ".yaml", ".yml", ".xml", ".md", ".properties", ".sh");

    @Test
    void everyFileCarriesTheHeader() throws IOException {
        // the one header guard of the family: Drishti Tools' kit (the first 20 lines carry the mark)
        List<String> missing = com.ash.tools.guards.kit.HeaderAndLineCap.withoutHeader(RepositoryFiles.root(), RepositoryFiles.withSuffixes(SUFFIXES));
        assertThat(missing).as("run: python3 tools/license_headers.py --fix").isEmpty();
    }
}
