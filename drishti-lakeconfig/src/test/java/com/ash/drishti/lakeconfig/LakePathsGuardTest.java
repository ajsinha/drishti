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
package com.ash.drishti.lakeconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A table's location comes from one place: the lake file, by {@link ConnectorLake#expand}. Java sources may not spell a {@code delta/<...>}
 * folder as a string, and only {@code ConnectorLake} splits a table's path into the plugins' root and domain (tools/test_lake_paths.py is
 * the same guard for Python).
 */
class LakePathsGuardTest {

    private static final Pattern SPELT = Pattern.compile("\"[^\" ]*\\bdelta/[a-zA-Z$<{]");
    /** The one place that names the variable's default for the CLI (it mirrors config/lakes/demo.yaml). */
    private static final Set<String> SPELT_ALLOWED = Set.of("CliLauncher.java");
    private static final Pattern SPLITS = Pattern.compile("LakeTable::parent|\\bt\\.parent\\(\\)");

    private static Path repo() {
        Path p = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(p.resolve("drishti-lakeconfig"))) {
            p = p.getParent();
        }
        return p;
    }

    private static List<Path> sources() throws IOException {
        Path root = repo();
        List<Path> out = new ArrayList<>();
        List<String> tops = new ArrayList<>(List.of(".", "plugins"));
        try (Stream<Path> domains = Files.list(root.resolve("modules"))) {         // modules/<domain>/<part>: the domain modules' sources
            domains.filter(Files::isDirectory).forEach(d -> tops.add(root.relativize(d).toString()));
        }
        for (String top : tops) {
            try (Stream<Path> mods = Files.list(root.resolve(top))) {
                for (Path m : (Iterable<Path>) mods.filter(d -> top.startsWith("modules") || d.getFileName().toString().startsWith("drishti-"))::iterator) {
                    Path main = m.resolve("src/main/java");
                    if (Files.isDirectory(main)) {
                        try (Stream<Path> w = Files.walk(main)) {
                            w.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    void nothingSpellsALakeFolderAndOnlyTheOneResolverSplitsATablesPath() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path f : sources()) {
            String name = f.getFileName().toString();
            for (String line : Files.readAllLines(f)) {
                String code = line.strip();
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                    continue;
                }
                if (!SPELT_ALLOWED.contains(name) && SPELT.matcher(code).find()) {
                    found.add(name + ": " + code);
                }
                if (!name.equals("ConnectorLake.java") && !name.equals("LakeTable.java") && SPLITS.matcher(code).find()) {
                    found.add(name + ": " + code);
                }
            }
        }
        assertThat(found).as("a table's folder comes only from the lake file through ConnectorLake.expand").isEmpty();
    }
}
