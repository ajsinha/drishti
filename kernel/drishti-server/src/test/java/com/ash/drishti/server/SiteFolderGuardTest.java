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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The site-configuration guard (docs/admin/PRODUCTION_STANDARD.md, "Site configuration"): runtime code writes site files only through the one
 * resolver. (1) A class that writes files and reads a path from the release's configuration folder ({@code Roots.config(...)}) is refused:
 * what is written is the site's, so its path is {@code Roots.site(...)}. (2) Every class that writes the site's files (the Admin writers) asks
 * {@code Roots.guardWrite} first, so a write into the release folder is refused with the way out.
 */
class SiteFolderGuardTest {

    /** The Admin writers of site files: each must ask the guard (or write through FileReplace, which does). */
    static final List<String> WRITERS = List.of(
            "kernel/drishti-packs/src/main/java/com/ash/drishti/packs/ConnectorFiles.java",
            "kernel/drishti-packs/src/main/java/com/ash/drishti/packs/bindings/BindingsFile.java",
            "kernel/drishti-server/src/main/java/com/ash/drishti/server/engines/EngineFiles.java",
            "kernel/drishti-server/src/main/java/com/ash/drishti/server/settings/SettingProposals.java",
            "kernel/drishti-server/src/main/java/com/ash/drishti/server/placement/PlacementService.java",
            "kernel/drishti-server/src/main/java/com/ash/drishti/server/admin/ArtifactService.java",
            "kernel/drishti-registry/src/main/java/com/ash/drishti/registry/YamlAccessStore.java");

    /** Classes that write files and name the release's configuration folder, and why that is right. */
    static final List<String> RELEASE_CONFIG_WRITERS_ALLOWED = List.of(
            "kernel/drishti-server/src/main/java/com/ash/drishti/server/cli/");   // command-line tools of the developer's checkout (no server, no release)

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("kernel/drishti-api/src/main/java/com/ash/drishti/api/Roots.java"))) {
            p = p.getParent();
        }
        assertThat(p).as("the repository root").isNotNull();
        return p;
    }

    @Test
    void theAdminWritersAskTheGuard() throws IOException {
        Path root = root();
        List<String> missing = new ArrayList<>();
        for (String w : WRITERS) {
            String src = Files.readString(root.resolve(w));
            if (!src.contains("guardWrite(") && !src.contains("FileReplace.atomically(")) {
                missing.add(w);
            }
        }
        assertThat(missing).as("writers that do not ask Roots.guardWrite").isEmpty();
        assertThat(Files.readString(root.resolve("kernel/drishti-api/src/main/java/com/ash/drishti/api/FileReplace.java"))).contains("Roots.guardWrite(");
    }

    @Test
    void noRuntimeClassWritesWhatItReadsFromTheReleaseConfigFolder() throws IOException {
        Path root = root();
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("kernel"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java") && x.toString().contains("/src/main/java/")).toList()) {
                String rel = root.relativize(f).toString();
                if (RELEASE_CONFIG_WRITERS_ALLOWED.stream().anyMatch(rel::startsWith) || rel.endsWith("/Roots.java")) {
                    continue;
                }
                String src = Files.readString(f);
                boolean writes = src.contains("Files.write") || src.contains("Files.move(") || src.contains("FileReplace.atomically(")
                        || src.contains("Files.copy(") || src.contains("Files.delete");
                boolean readsReleaseConfig = src.contains("Roots.config(");
                if (writes && readsReleaseConfig) {
                    bad.add(rel);
                }
            }
        }
        assertThat(bad).as("classes that write files and take a path from Roots.config (the release's folder): use Roots.site").isEmpty();
    }
}
