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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A built BI file replaces the old one even when Windows holds the old one open for a moment (QA 2026-10-20, DRS-9027). */
class FileSwapTest {

    @TempDir
    Path dir;

    @Test
    void twoBuildsOfOnePartitionNeverShareATemporaryFile() {
        Path file = dir.resolve("part.parquet");
        assertThat(FileSwap.temp(file)).isNotEqualTo(FileSwap.temp(file));
        assertThat(FileSwap.temp(file).getFileName().toString()).startsWith("part.parquet.").endsWith(".tmp");
    }

    @Test
    void aFileHeldOpenForAMomentIsReplacedOnALaterTry() throws Exception {
        Path file = Files.writeString(dir.resolve("part.parquet"), "old");
        Path tmp = Files.writeString(FileSwap.temp(file), "new");
        AtomicInteger tries = new AtomicInteger();
        FileSwap.Mover heldTwice = (from, to) -> {                // Windows: a sharing violation while a reader or the antivirus holds it
            if (tries.incrementAndGet() <= 2) {
                throw new AccessDeniedException(to.toString());
            }
            FileSwap.ATOMIC.move(from, to);
        };
        FileSwap.replace(tmp, file, heldTwice, 5, 1);
        assertThat(Files.readString(file)).isEqualTo("new");
        assertThat(tries).hasValue(3);
        assertThat(tmp).doesNotExist();
    }

    @Test
    void aFileHeldTooLongSaysWhatHeldItAndLeavesNoTemporaryFile() throws Exception {
        Path file = Files.writeString(dir.resolve("part.parquet"), "old");
        Path tmp = Files.writeString(FileSwap.temp(file), "new");
        FileSwap.Mover alwaysHeld = (from, to) -> {
            throw new AccessDeniedException(to.toString());
        };
        assertThatThrownBy(() -> FileSwap.replace(tmp, file, alwaysHeld, 3, 1))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("stayed in use").hasMessageContaining("virus scanner");
        assertThat(Files.readString(file)).isEqualTo("old");
        assertThat(tmp).doesNotExist();
    }

    @Test
    void anHourOldTemporaryFileOfAStoppedBuildIsRemoved() throws Exception {
        Path file = dir.resolve("part.parquet");
        Path stale = Files.writeString(dir.resolve("part.parquet.deadbeef.tmp"), "x");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 7_200_000L));
        Path fresh = Files.writeString(dir.resolve("part.parquet.cafebabe.tmp"), "y");
        FileSwap.temp(file);
        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();                          // another build may be writing it right now
    }
}
