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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server's start, in a JVM of its own: without {@code -Djava.io.tmpdir=} it warns and says how to pass it; with it, it does not. Either way it
 * removes the stale native-library copies a killed JVM left in its temp base. The child loads no native library, so it unpacks nothing.
 */
class TmpdirWarningTest {

    @TempDir Path dir;

    /** The child: the server's first step ({@link DrishtiApplication#startTempFolder}), then what it would repeat in the log. */
    public static void main(String[] args) {
        DrishtiApplication.startTempFolder(args);
        String w = com.ash.drishti.common.TempFiles.startWarning();
        System.out.println("WARNING=" + (w == null ? "none" : w));
        System.out.println("REMOVED=" + com.ash.drishti.common.TempFiles.info().get("nativeCopiesRemoved"));
    }

    private String start(Path base, String... jvmFlags) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-Xmx64m"));
        cmd.addAll(List.of(jvmFlags));
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), TmpdirWarningTest.class.getName(), "--drishti.tmp-dir=" + base));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("JDK_JAVA_OPTIONS");
        pb.environment().remove("_JAVA_OPTIONS");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        return out;
    }

    @Test
    void warnsAtStartWhenJavaWasStartedWithoutTheFlagAndSweepsItsBase() throws Exception {
        Path base = Files.createDirectories(dir.resolve("server"));
        Path left = Files.writeString(base.resolve("libduckdb_java4711.so"), "left by a killed JVM");
        Files.setLastModifiedTime(left, FileTime.from(Instant.now().minus(Duration.ofHours(1))));

        String out = start(base);
        assertThat(out).contains("WARNING=java.io.tmpdir was not set when Java started")
                .contains("-Djava.io.tmpdir=" + base.toAbsolutePath())
                .contains("JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=")
                .contains("REMOVED=1");
        assertThat(out.lines().filter(l -> l.contains("WARN") && l.contains("java.io.tmpdir was not set"))).as("logged too: " + out).isNotEmpty();
        assertThat(left).doesNotExist();
    }

    @Test
    void saysNothingWhenTheFlagWasPassed() throws Exception {
        Path base = Files.createDirectories(dir.resolve("server"));
        String out = start(base, "-Djava.io.tmpdir=" + base);
        assertThat(out).contains("WARNING=none").doesNotContain("java.io.tmpdir was not set");
    }
}
