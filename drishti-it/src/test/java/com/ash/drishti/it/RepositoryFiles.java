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
import java.util.stream.Stream;

/** Walks the repository source tree, skipping build output and third-party code. */
final class RepositoryFiles {

    static final Set<String> SKIP_DIRS =
            Set.of("target", ".git", ".venv", "vendor", "recorded", "__pycache__", ".mvn", ".idea", ".pytest_cache", "requirements");
    static final Set<String> SKIP_FILES = Set.of("mvnw", "mvnw.cmd", "LICENSE");

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

    static List<Path> withSuffixes(Set<String> suffixes) {
        Path root = root();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> root.relativize(p).getNameCount() > 0)
                    .filter(p -> {
                        for (Path part : root.relativize(p)) {
                            if (SKIP_DIRS.contains(part.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .filter(p -> !SKIP_FILES.contains(p.getFileName().toString()))
                    .filter(p -> suffixes.stream().anyMatch(x -> p.getFileName().toString().endsWith(x)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
