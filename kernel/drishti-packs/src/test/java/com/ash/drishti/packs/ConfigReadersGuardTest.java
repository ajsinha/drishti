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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The guard of configuration resolution (docs/architecture/KERNEL_AND_MODULES.md section 18.3): no Java main code finds a module's packs or
 * connector templates by itself (a folder list of packs, a walk up to a {@code modules} folder beside {@code config}, a module's
 * {@code connectors/} folder joined by hand). Every reader asks {@code com.ash.drishti.api.resolution}; the Python twin's guard is
 * {@code tools/test_config_readers_guard.py}.
 */
class ConfigReadersGuardTest {

    static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();

    /** What only the resolver may do, each with why. */
    static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("PackLoader\\.dirs\\("),                                 // a list of pack folders instead of the resolution
            Pattern.compile("PackLoader\\.folders\\("),
            Pattern.compile("PackFolders\\.moduleRoots"),                            // module packs found by the position of a folder
            Pattern.compile("installedModulePacks"),
            Pattern.compile("resolve\\(\"modules\"\\)\\)\\s*&&.*resolve\\(\"config\"\\)"),   // the walk up to a checkout
            Pattern.compile("ModuleDirs\\.of\\("),                                   // every module folder, on or off
            Pattern.compile("resolve\\(\"connectors\"\\)\\.resolve\\(\"(delta|kafka|iceberg)\\.yaml\"\\)"));

    /** The resolver itself, and the deprecated helpers kept for a test's or a tool's own folder. */
    static final List<String> ALLOWED = List.of("/api/resolution/", "/api/PackFolders.java", "/packs/PackLoader.java", "/lakeconfig/ModuleDirs.java");

    @Test
    void noMainCodeFindsPacksOrTemplatesByItself() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> s = Files.walk(REPO)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/.claude/") || p.startsWith(REPO))
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/data/")).toList()) {
                String rel = REPO.relativize(f).toString();
                if (ALLOWED.stream().anyMatch(a -> f.toString().replace('\\', '/').contains(a))) {
                    continue;
                }
                String text = Files.readString(f);
                for (Pattern p : FORBIDDEN) {
                    if (p.matcher(text).find()) {
                        found.add(rel + ": " + p.pattern());
                    }
                }
            }
        }
        assertThat(found).as("readers that bypass the resolution (ask com.ash.drishti.api.resolution.ConfigResolution)").isEmpty();
    }
}
