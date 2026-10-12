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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The per-process temp folder: made under the base, java.io.tmpdir points into it, stale folders of dead processes go, ours stays until clean-up. */
class TempFilesTest {

    @TempDir Path base;

    @Test
    void startMakesTheProcessFolderPointsTmpdirAtItAndCleansUp() throws Exception {
        String before = System.getProperty("java.io.tmpdir");
        try {
            Path dead = Files.createDirectories(base.resolve("drishti-2147483646"));
            Files.writeString(dead.resolve(TempFiles.MARKER), "x");
            Files.writeString(dead.resolve("spill.tmp"), "data");
            Path lookalike = Files.createDirectories(base.resolve("drishti-2147483645"));            // no marker: not ours, never removed
            Path other = Files.createDirectories(base.resolve("something-else"));
            Path dir = TempFiles.start(base.toString());
            assertThat(dir).isEqualTo(base.toAbsolutePath().resolve("drishti-" + ProcessHandle.current().pid()));
            assertThat(System.getProperty("java.io.tmpdir")).isEqualTo(dir.toString());
            assertThat(dead).doesNotExist();
            assertThat(lookalike).exists();
            assertThat(other).exists();
            Path f = TempFiles.createFile("x-", ".tmp");
            Files.writeString(f, "12345");
            assertThat(f.getParent()).isEqualTo(dir);
            assertThat(TempFiles.createDir("d-").getParent()).isEqualTo(dir);
            assertThat(TempFiles.info()).containsEntry("path", dir.toString()).containsEntry("bytes", 5L + Files.size(dir.resolve(TempFiles.MARKER)));
            assertThat(TempFiles.start(base.toString())).as("a second start returns the same folder").isEqualTo(dir);
            TempFiles.cleanUp();
            assertThat(dir).doesNotExist();
        } finally {
            System.setProperty("java.io.tmpdir", before);
            TempFiles.cleanUp();
        }
    }
}
