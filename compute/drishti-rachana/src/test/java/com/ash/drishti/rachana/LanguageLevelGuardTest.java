/*
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary: see LICENSE.
 */
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guard: Drishti runs the newest Rachana language level. The one holder pins it (the newest the runtime implements), no other main
 * source builds a Rachana instance, changes its level or takes a bare compiler/parser outside the holder, and every shipped Sutra,
 * fragment and block declares that level ({@code rachana: 1.2}): an older file is upgraded with {@code rachana upgrade}
 * ({@code drishti.py sutra upgrade --write}), never left to be read at an older level. A user's Sutra of an older level still compiles
 * at its own level and is offered the upgrade ({@link LanguageLevels}).
 */
class LanguageLevelGuardTest {

    @Test
    void theHolderCompilesAtTheNewestLevel() {
        String newest = com.ash.rachana.profile.Grammar.LEVELS.get(com.ash.rachana.profile.Grammar.LEVELS.size() - 1);
        assertThat(DrishtiRachana.LEVEL).isEqualTo(newest);
        assertThat(DrishtiRachana.current().language()).isEqualTo(newest);
        assertThat(DrishtiRachana.compiler().level()).isEqualTo(newest);
        assertThat(DrishtiRachana.parser()).isNotNull();
    }

    @Test
    void noOtherMainCodeBuildsAnInstanceOrPicksALevel() throws IOException {
        Pattern bad = Pattern.compile("Rachana\\.(core|with|at)\\(|\\.language\\(\"|Grammar\\.DEFAULT_LEVEL|new\\s+(?:com\\.ash\\.rachana\\.parse\\.)?SutraParser\\(");
        Path root = Path.of(com.ash.lake.tck.TestRoots.repo()).toAbsolutePath().normalize();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(f -> f.toString().endsWith(".java") && f.toString().contains("/src/main/") && !f.toString().contains("/target/"))
                    .filter(f -> !f.getFileName().toString().equals("DrishtiRachana.java"))
                    .filter(f -> {
                        try {
                            return bad.matcher(Files.readString(f)).find();
                        } catch (IOException e) {
                            return false;
                        }
                    }).map(f -> root.relativize(f).toString()).toList();
        }
        assertThat(offenders).as("main sources that build a Rachana instance or pick a level outside DrishtiRachana").isEmpty();
    }

    @Test
    void everyShippedSutraFragmentAndBlockDeclaresTheServersLevel() throws IOException {
        Path root = Path.of(com.ash.lake.tck.TestRoots.repo()).toAbsolutePath().normalize();
        List<String> files = new java.util.ArrayList<>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                String n = d.getFileName() == null ? "" : d.getFileName().toString();
                boolean skip = !d.equals(root) && (n.startsWith(".") || n.equals("target") || n.equals("node_modules")
                        || d.getParent().equals(root) && (n.equals("data") || n.equals("lib")));
                return skip ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                String n = f.getFileName().toString();
                if (n.endsWith(".sutra.yaml") || n.endsWith(".fragment.yaml") || n.endsWith(".block.yaml")) {
                    files.add(root.relativize(f).toString());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path f, IOException e) {
                return java.nio.file.FileVisitResult.CONTINUE;          // a file another test removed meanwhile
            }
        });
        List<String> older = files.stream().filter(f -> {
            try {
                return !DrishtiRachana.LEVEL.equals(LanguageLevels.declared(Files.readString(root.resolve(f))));
            } catch (IOException e) {
                return true;
            }
        }).toList();
        assertThat(files).as("Sutra, fragment and block files found").hasSizeGreaterThan(500);
        assertThat(older).as("files that do not declare rachana: " + DrishtiRachana.LEVEL + " (run drishti.py sutra upgrade <path> --write)").isEmpty();
    }

    @Test
    void anOlderSutraIsReadAtItsOwnLevelAndOfferedTheUpgrade() {
        String old = "rachana: 1\nsutra: probe\nversion: 1\nmatch: { kind: trade }\npanels:\n  - { id: p, kind: kv, columns: [{ label: R, bind: \"range($.L, 'X')\" }] }\n";
        assertThat(LanguageLevels.declared(old)).isEqualTo("1.0");
        assertThat(LanguageLevels.older(old)).isTrue();
        assertThat(LanguageLevels.advice(old, "probe.v1.sutra.yaml")).contains("1.0", DrishtiRachana.LEVEL, "drishti.py sutra upgrade probe.v1.sutra.yaml --write");
        assertThat(DrishtiRachana.parser().parse(old, "probe.v1.sutra.yaml", "").level()).isEqualTo("1.0");
        var up = LanguageLevels.upgrade("probe.v1.sutra.yaml", old);
        assertThat(up.writable()).isTrue();
        assertThat(up.text()).startsWith("rachana: " + DrishtiRachana.LEVEL + "\n").contains("span($.L, 'X')");
        assertThat(LanguageLevels.older(up.text())).isFalse();
        assertThat(LanguageLevels.advice(up.text(), "probe.v1.sutra.yaml")).isNull();
    }
}
