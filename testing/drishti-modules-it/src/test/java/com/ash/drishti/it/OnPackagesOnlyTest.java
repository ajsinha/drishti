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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.lake.tck.TestRoots;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every server this project starts runs on the built module PACKAGES, the way an install runs: never on the repository's module sources.
 * A test that needs the sources hides a product gap (a pack's file the packer leaves out, a reader that only finds a source folder), so
 * the gap is fixed in the product and the test stays on the packages. The surefire setting names target/modules-home; no test source may
 * point a server back at modules/, turn source mode on (drishti.modules.sources) or the development switch, or bring the old ModuleSources
 * extension back (i7a removed it when the packages served what it was kept for).
 */
class OnPackagesOnlyTest {

    /** What turns a server in this project back onto the module sources. */
    static final List<Pattern> SOURCE_SWITCHES = List.of(
            Pattern.compile("\\bModuleSources\\b"),
            Pattern.compile("drishti\\.modules\\.packages\"?\\s*[=,]\\s*\"?false"),
            Pattern.compile("drishti\\.modules\\.sources\"?\\s*[=,]\\s*\"?true"),
            Pattern.compile("drishti\\.modules\\.dev\"?\\s*[=,]\\s*\"?true"),
            Pattern.compile("drishti\\.modules\\.home\"?\\s*,\\s*[^)]*TestRoots\\.repo\\(\\)\\s*,\\s*\"modules\""),
            Pattern.compile("drishti\\.modules\\.home\"?\\s*[=,]\\s*\"?(\\.\\./)*modules/?(?![\\w-])"));

    static final Path TESTS = Path.of(TestRoots.repo(), "testing", "drishti-modules-it", "src", "test").toAbsolutePath().normalize();

    @Test
    void theServersOfThisProjectRunOnTheBuiltPackages() {
        Path home = Path.of(System.getProperty("drishti.modules.home", "")).toAbsolutePath().normalize();
        assertThat(home.getFileName()).as("drishti.modules.home of this project's tests: target/modules-home").hasToString("modules-home");
        assertThat(home.getParent().getFileName()).as("drishti.modules.home of this project's tests: target/modules-home").hasToString("target");
        assertThat(home).isNotEqualTo(Path.of(TestRoots.repo(), "modules").toAbsolutePath().normalize());
        assertThat(System.getProperty("drishti.modules.sources", "false")).as("source mode").isNotEqualTo("true");
        assertThat(System.getProperty("drishti.modules.dev", "false")).as("the development switch").isNotEqualTo("true");
    }

    @Test
    void noTestSourcePointsAServerAtTheModuleSources() {
        Path self = TESTS.resolve(Path.of("java", "com", "ash", "drishti", "it", "OnPackagesOnlyTest.java"));
        List<String> found = new ArrayList<>();
        try (Stream<Path> s = Files.walk(TESTS)) {
            for (Path f : s.filter(Files::isRegularFile).filter(p -> !p.equals(self))
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".yaml") || p.toString().endsWith(".properties")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (Pattern p : SOURCE_SWITCHES) {
                    if (p.matcher(text).find()) {
                        found.add(TESTS.relativize(f) + ": " + p.pattern());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(found).as("tests of drishti-modules-it run on the packages (target/modules-home); fix the product, not the test").isEmpty();
    }
}
