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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The start-up WARNING when the settings name a drishti.home other than the folder the server jar is installed in. */
class LaunchRootsOtherHomeTest {

    @TempDir
    Path dir;

    @Test
    void anOldSettingsFileNamingAnotherFolderIsAWarningAndTheSameFolderIsNot() throws Exception {
        Path installed = Files.createDirectories(dir.resolve("drishti-platform").resolve("drishti"));
        Path jar = Files.createDirectories(installed.resolve("lib")).resolve("drishti-server-exec.jar");
        Files.writeString(jar, "");
        Path other = Files.createDirectories(dir.resolve("drishti-orig"));
        assertThat(LaunchRoots.otherHome(other, jar)).hasValueSatisfying(w -> assertThat(w)
                .startsWith("WARNING: the settings name drishti.home=" + other).contains("installed in " + installed).contains("setup"));
        assertThat(LaunchRoots.otherHome(installed, jar)).isEmpty();
        assertThat(LaunchRoots.otherHome(installed.resolve(".").resolve("lib").resolve(".."), jar)).isEmpty();
    }

    @Test
    void outsideAnInstalledJarThereIsNothingToCompare() {
        assertThat(LaunchRoots.otherHome(dir, null)).isEmpty();
        assertThat(LaunchRoots.otherHome(null, dir.resolve("lib").resolve("x.jar"))).isEmpty();
        assertThat(LaunchRoots.otherHome(dir, dir.resolve("target").resolve("x.jar"))).isEmpty();
        assertThat(LaunchRoots.installedJar("a.jar" + java.io.File.pathSeparator + "b.jar")).isNull();
        assertThat(LaunchRoots.installedJar("/x/classes")).isNull();
        assertThat(LaunchRoots.installedJar("/x/lib/drishti-server-exec.jar")).isEqualTo(Path.of("/x/lib/drishti-server-exec.jar"));
    }
}
