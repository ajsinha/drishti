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
package com.ash.drishti.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileReplaceTest {

    @TempDir
    Path dir;

    /** The old file held open by a reader that lets go shortly (on Windows the first tries are refused): the replace still lands. */
    @Test
    void aReplaceWaitsForAReaderThatLetsGo() throws Exception {
        Path file = Files.writeString(dir.resolve("state.json"), "old");
        Path tmp = Files.writeString(dir.resolve("state.json.tmp"), "new");
        FileInputStream held = new FileInputStream(file.toFile());           // java.io: no shared delete on Windows
        Thread release = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(300);
                held.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        FileReplace.atomically(tmp, file);
        release.join();
        assertThat(Files.readString(file)).isEqualTo("new");
        assertThat(tmp).doesNotExist();
    }

    @Test
    void onlyAFileHeldOpenIsTriedAgain() {
        assertThat(FileReplace.held(new AccessDeniedException("x"))).isTrue();
        assertThat(FileReplace.held(new FileSystemException("x", null, "being used by another process"))).isTrue();
        assertThat(FileReplace.held(new NoSuchFileException("x"))).isFalse();
        assertThat(FileReplace.held(new AtomicMoveNotSupportedException("a", "b", "no"))).isFalse();
    }
}
