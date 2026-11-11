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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Walks the repository source tree, skipping build output and third-party code. */
final class RepositoryFiles {

    static final Set<String> SKIP_DIRS =
            Set.of("target", ".git", ".claude", ".venv", "vendor", "recorded", "__pycache__", ".mvn", ".idea", ".pytest_cache", "requirements",
                    "connector-upgrade", // byte-exact copies of a past release's generated connector files (ConnectorUpgradeTest)
                    "archetype-resources"); // the Module SDK's templates: a generated module carries its own publisher's header
    static final Set<String> SKIP_FILES = Set.of("mvnw", "mvnw.cmd", "LICENSE",
            "dependency-reduced-pom.xml"); // the shade plugin's build output (git-ignored), not source

    private RepositoryFiles() {}

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("LICENSE"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repository root not found");
        }
        return p;
    }

    /**
     * What git ignores (runtime data such as data/identity, data/packs, data/tmp): not source. A folder ignored as a whole is one entry, so
     * the walk never enters it (a build beside it creates and deletes its temporary folders there). Empty when git is unavailable.
     */
    static Set<Path> ignored(Path root) {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "--others", "--ignored", "--exclude-standard", "--directory").directory(root.toFile())
                    .redirectErrorStream(true).start();
            java.util.Set<Path> out = new java.util.HashSet<>();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                r.lines().filter(l -> !l.isBlank()).forEach(l -> out.add(root.resolve(l).normalize()));
            }
            return p.waitFor() == 0 ? out : Set.of();
        } catch (IOException e) {
            return Set.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        }
    }

    static List<Path> withSuffixes(Set<String> suffixes) {
        Path root = root();
        Set<Path> skip = ignored(root);
        List<Path> out = new java.util.ArrayList<>();
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                    boolean pruned = !d.equals(root) && (SKIP_DIRS.contains(d.getFileName().toString()) || skip.contains(d.normalize()));
                    return pruned ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                    String name = f.getFileName().toString();
                    if (a.isRegularFile() && !skip.contains(f.normalize()) && !SKIP_FILES.contains(name) && suffixes.stream().anyMatch(name::endsWith)) {
                        out.add(f);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(null);
        return out;
    }
}
