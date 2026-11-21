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
 * Guard: Drishti compiles every Sutra at Rachana language level 1.0 (the corpus-proven level); the tools' default is 1.2, whose
 * meanings differ. The one holder pins 1.0, and no other main source builds a Rachana instance, changes its level or takes a bare
 * compiler/parser outside the holder.
 */
class LanguageLevelGuardTest {

    @Test
    void theHolderCompilesAtLevel10() {
        assertThat(DrishtiRachana.current().language()).isEqualTo("1.0");
        assertThat(DrishtiRachana.compiler().level()).isEqualTo("1.0");
        assertThat(DrishtiRachana.parser()).isNotNull();
    }

    @Test
    void noOtherMainCodeBuildsAnInstanceOrPicksALevel() throws IOException {
        Pattern bad = Pattern.compile("Rachana\\.(core|with|at)\\(|\\.language\\(\"|Grammar\\.DEFAULT_LEVEL|new\\s+(?:com\\.ash\\.rachana\\.parse\\.)?SutraParser\\(");
        Path root = Path.of("..", "..").toAbsolutePath().normalize();
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
}
