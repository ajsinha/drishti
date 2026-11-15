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

import com.ash.drishti.testroots.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The guard: a Java test never reaches a repository or module file through a path that depends on the folder the JVM was started in.
 * A literal such as {@code Path.of("../../config")}, {@code Path.of("src", "test", "resources")} or {@code Path.of("target")} works only when
 * the working folder is the module's; from an IDE at the repository root, or any other folder, it fails or writes in the wrong place. Use
 * {@code TestRoots.repo()} (the repository) or {@code drishti.module.root} (the module), both absolute and set in the
 * parent pom's surefire configuration. The scan flags a path whose first segment is {@code ""}, {@code .}, {@code ..}, {@code src},
 * {@code target} or the name of a top-level folder of the repository. A relative name that is only a name (a file name handed to a parser, a
 * sub-folder of a temp folder) is not a path to a repository file and is not flagged.
 */
class TestPathsGuardTest {

    static final Path REPO = Path.of(TestRoots.repo());

    /** The call forms that build a path from literals. */
    static final Pattern CALL = Pattern.compile("(?:Path\\.of|Paths\\.get|new File)\\(\\s*\"([^\"]*)\"");
    /** A data or configuration folder given to a test server as a relative path: the configuration imports read it before any root is resolved. */
    static final Pattern IMPORTED = Pattern.compile("\"(?:--)?(?:drishti\\.(?:data|config)\\.dir|DRISHTI_(?:DATA|CONFIG|ENGINES)_DIR|DRISHTI_PACKS_OVERLAY)=(?![/$\"])");

    /**
     * Relative by design, each with its reason: "file-suffix|text on the line". Empty today: every test path is anchored. Add an entry only
     * for a path that is meant to be relative to the working folder (for example a test of the relative-path resolver itself).
     */
    static final List<String> ALLOWED = List.of();

    @Test
    void noTestBuildsAPathFromTheWorkingFolder() throws IOException {
        Set<String> roots = new HashSet<>(Set.of("", ".", "..", "src", "target"));
        try (Stream<Path> top = Files.list(REPO)) {
            top.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".")).forEach(roots::add);
        }
        List<String> offenders = new ArrayList<>();
        for (Path file : testSources()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (IMPORTED.matcher(lines.get(i)).find()) {
                    offenders.add(REPO.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).trim());
                }
                Matcher m = CALL.matcher(lines.get(i));
                while (m.find()) {
                    if (m.group(1).startsWith("/") || m.group(1).matches("[A-Za-z]:.*")) continue;   // absolute
                    String first = m.group(1).split("/", -1)[0];
                    if (!roots.contains(first)) continue;
                    String where = REPO.relativize(file) + ":" + (i + 1);
                    String line = lines.get(i);
                    if (ALLOWED.stream().anyMatch(a -> where.endsWith(a.split("\\|")[0]) && line.contains(a.split("\\|")[1]))) continue;
                    offenders.add(where + "  " + lines.get(i).trim());
                }
            }
        }
        assertThat(offenders).as("tests that build a path from the working folder; anchor them on drishti.repo.root or drishti.module.root").isEmpty();
    }

    private static List<Path> testSources() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(REPO)) {
            s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> REPO.relativize(p).toString().replace('\\', '/').contains("/src/test/java/"))
                    .filter(p -> !REPO.relativize(p).toString().contains("/target/") && !REPO.relativize(p).toString().contains("node_modules"))
                    .filter(p -> !p.endsWith("TestPathsGuardTest.java"))
                    .forEach(out::add);
        }
        return out;
    }
}
