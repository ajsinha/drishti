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
package com.ash.drishti.server.disk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Heap dumps kept within a count (2.9.7): the newest stay, other files are never touched. */
class DumpsTest {

    @TempDir
    Path dir;

    @Test
    void theNewestDumpsStay() throws Exception {
        for (int i = 1; i <= 5; i++) {
            Path d = dir.resolve("java_pid" + i + ".hprof");
            Files.writeString(d, "dump " + i);
            Files.setLastModifiedTime(d, FileTime.fromMillis(1_000_000L * i));
        }
        Files.writeString(dir.resolve("notes.txt"), "keep");
        assertThat(Dumps.prune(dir, 2)).isEqualTo(3);
        assertThat(Dumps.list(dir)).extracting(p -> p.getFileName().toString()).containsExactly("java_pid5.hprof", "java_pid4.hprof");
        assertThat(dir.resolve("notes.txt")).exists();
        assertThat(Dumps.prune(dir.resolve("none"), 2)).isZero();
        assertThat(DiskProperties.defaults().maxDumps()).isEqualTo(2);
    }
}
