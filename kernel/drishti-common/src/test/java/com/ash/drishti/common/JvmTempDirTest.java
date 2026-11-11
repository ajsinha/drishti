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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The JVM's startup temp folder: a warning when -Djava.io.tmpdir= was not passed, and the sweep of stale native copies in the server's own base. */
class JvmTempDirTest {

    @TempDir Path base;

    @Test
    void warnsWhenTheFlagWasNotPassedAndSaysHowToPassIt() {
        assertThat(JvmTempDir.passed(List.of("-Xmx1g", "-XX:+UseCompactObjectHeaders"), Map.of())).isFalse();
        assertThat(JvmTempDir.passed(List.of("-Xmx1g", "-Djava.io.tmpdir=/srv/drishti/tmp"), Map.of())).isTrue();
        assertThat(JvmTempDir.passed(List.of(), Map.of("JAVA_TOOL_OPTIONS", "-Xss1m -Djava.io.tmpdir=/srv/t"))).isTrue();
        assertThat(JvmTempDir.passed(List.of(), Map.of("JDK_JAVA_OPTIONS", "-Djava.io.tmpdir=/srv/t"))).isTrue();
        assertThat(JvmTempDir.passed(List.of(), Map.of("JAVA_TOOL_OPTIONS", "-Xss1m"))).isFalse();

        String w = JvmTempDir.warning(false, "/tmp", base);
        assertThat(w).contains("system temp folder (/tmp)").contains("DuckDB")
                .contains("-Djava.io.tmpdir=" + base.toAbsolutePath())
                .contains("java -Djava.io.tmpdir=" + base.toAbsolutePath() + " -jar drishti-server-<version>-exec.jar")
                .contains("JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=");
        assertThat(JvmTempDir.warning(true, "/tmp", base)).isNull();
    }

    @Test
    void theTestForkGotTheFlagSoItsHealthCarriesNoWarning() {
        // the parent pom's surefire argLine passes -Djava.io.tmpdir=<module>/target/tmp
        assertThat(JvmTempDir.passed()).isTrue();
        assertThat(JvmTempDir.startup()).endsWith("tmp");
        assertThat(TempFiles.info()).containsEntry("jvmTmpdirPassed", true).containsEntry("jvmTmpdirWarning", "").containsKey("nativeCopiesRemoved");
    }

    @Test
    void sweepRemovesOldNativeCopiesOnlyAtTheTopOfTheBase() throws Exception {
        Instant now = Instant.now();
        FileTime old = FileTime.from(now.minus(Duration.ofHours(2)));
        List<String> natives = List.of("libduckdb_java10768810692187563798.so", "librocksdbjni4170419887234453627.so", "libcatboost4j-prediction5099704291219517293.so",
                "jansi-2.4.2-26366cd7d8724e2-libjansi.so", "jansi-2.4.2-26366cd7d8724e2-libjansi.so.lck", "sqlite-3.50.3.0-1f2e-libsqlitejdbc.so",
                "snappy-1.1.10-6c1d-libsnappyjava.so", "libzstd-jni-1.5.7-6123.so", "libduckdb_java123.dll");
        for (String n : natives) {
            Files.setLastModifiedTime(Files.writeString(base.resolve(n), "x"), old);
        }
        Path young = Files.writeString(base.resolve("libduckdb_java999.so"), "x");                          // may be loading right now
        Path ours = Files.writeString(base.resolve("report-1.part"), "x");                                 // not a native copy
        Files.setLastModifiedTime(ours, old);
        Path nested = Files.createDirectories(base.resolve("drishti-4242")).resolve("libduckdb_java1.so");   // inside a process folder: not touched
        Files.setLastModifiedTime(Files.writeString(nested, "x"), old);
        Path folder = Files.createDirectories(base.resolve("libduckdb_java_dir.so"));                        // a folder, not a file
        Files.setLastModifiedTime(folder, old);

        assertThat(JvmTempDir.sweep(base, JvmTempDir.MIN_AGE, now)).isEqualTo(natives.size());
        for (String n : natives) {
            assertThat(base.resolve(n)).doesNotExist();
        }
        assertThat(young).exists();
        assertThat(ours).exists();
        assertThat(nested).exists();
        assertThat(folder).exists();
        assertThat(JvmTempDir.sweep(base.resolve("missing"), JvmTempDir.MIN_AGE, now)).isZero();
        assertThat(JvmTempDir.isNativeCopy("drishti-temp-folder.txt")).isFalse();
        assertThat(JvmTempDir.isNativeCopy("libnetty_transport_native_epoll_x86_644455.so")).isTrue();
    }

    @Test
    void sweepFollowsNoLink() throws Exception {
        Path outside = Files.createDirectories(base.resolve("outside"));
        Path target = Files.writeString(outside.resolve("libduckdb_java7.so"), "x");
        Path inner = Files.createDirectories(base.resolve("base"));
        try {
            Files.createSymbolicLink(inner.resolve("libduckdb_java8.so"), target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;                                                                    // no links on this file system
        }
        assertThat(JvmTempDir.sweep(inner, Duration.ZERO, Instant.now().plusSeconds(60))).isZero();
        assertThat(target).exists();
    }

    @Test
    void aMissingTmpdirIsMadeAtStart(@TempDir Path dir) {
        String before = System.getProperty("java.io.tmpdir");
        Path missing = dir.resolve("data").resolve("tmp").resolve("server");
        try {
            System.setProperty("java.io.tmpdir", missing.toString());
            assertThat(JvmTempDir.ensureExists()).isEqualTo(missing);   // QA 2026-10-20, Windows: "java.io.tmpdir does not exist"
            assertThat(Files.isDirectory(missing)).isTrue();
            assertThat(JvmTempDir.ensureExists()).isNull();             // there now: nothing to make
        } finally {
            System.setProperty("java.io.tmpdir", before);
        }
    }
}
