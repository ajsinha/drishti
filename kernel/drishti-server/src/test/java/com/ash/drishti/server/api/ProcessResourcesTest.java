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
package com.ash.drishti.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Admin, Health's process figures: direct memory against its limit, open files, descriptors held on deleted files. */
class ProcessResourcesTest {

    @TempDir
    Path dir;

    @Test
    void directMemoryIsMeasuredAgainstItsLimit() {
        ByteBuffer held = ByteBuffer.allocateDirect(1 << 20);
        Map<String, Object> m = ProcessResources.snapshot();
        assertThat((Long) m.get("directMaxBytes")).isPositive();
        assertThat((Long) m.get("directUsedBytes")).isGreaterThanOrEqualTo(held.capacity());
        assertThat((Long) m.get("directBuffers")).isPositive();
        assertThat(m).containsKeys("mappedUsedBytes", "mappedBuffers", "directPercent", "directWarning", "ioPoolBytes");
        assertThat((Integer) m.get("directPercent")).isBetween(0, 100);
        assertThat(m.get("directWarning")).isEqualTo((Integer) m.get("directPercent") >= ProcessResources.WARN_PERCENT);
    }

    @Test
    void openFilesAndDeletedOnesAreCountedWhereTheSystemTells() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "Linux only");
        Path f = Files.writeString(dir.resolve("gone.bin"), "x");
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            Files.delete(f);
            assertThat(ProcessResources.deletedOpenFiles(Path.of("/proc/self/fd"))).isPositive();   // at least this one
            Map<String, Object> m = ProcessResources.snapshot();
            assertThat((Long) m.get("openFiles")).isPositive();
            assertThat((Long) m.get("maxFiles")).isGreaterThanOrEqualTo((Long) m.get("openFiles"));
        }
        assertThat(ProcessResources.deletedOpenFiles(dir.resolve("no-such-folder"))).isNull();
    }
}
