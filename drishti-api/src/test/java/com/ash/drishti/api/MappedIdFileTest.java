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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Id map files are released, not left to the collector (2.9.7, RESOURCES_AND_SCALE.md section 7.3): a mapping is shared per file, closed
 * once its readers are done, a read after that is refused ("ask again"), a released file is deleted only after it is unmapped, and the
 * folder is trimmed to its cap least recently mapped first.
 */
class MappedIdFileTest {

    @TempDir
    Path tmp;

    private static MappedIdFile write(Path file, int n) throws Exception {
        return MappedIdFile.write(n, i -> "T-" + i, id -> Integer.parseInt(id.substring(2)) % 7, file);
    }

    @Test
    void readsReleasesAndRefusesAfterwards() throws Exception {
        Path f = tmp.resolve("k/1@1/2026-09-30.idmap");
        MappedIdFile m = write(f, 1_000);
        assertThat(m.size()).isEqualTo(1_000);
        assertThat(m.file("T-15")).isEqualTo(1);
        assertThat(m.file("NOPE")).isEqualTo(-1);
        assertThat(MappedIdFile.open(f)).as("one mapping per file").isSameAs(m);
        MappedIdFile.release(f, true);
        assertThat(m.closed()).isTrue();
        assertThat(f).doesNotExist();
        assertThatThrownBy(() -> m.file("T-15")).isInstanceOf(MappedIdFile.Closed.class).hasMessageContaining("ask again");
    }

    @Test
    void aReplacedFileIsMappedAgain() throws Exception {
        Path f = tmp.resolve("x.idmap");
        MappedIdFile a = write(f, 10);
        MappedIdFile b = write(f, 20);
        assertThat(a.closed()).isTrue();
        assertThat(b.size()).isEqualTo(20);
        Files.write(f, new byte[] {1, 2, 3});                          // damaged: not used, written again
        assertThat(MappedIdFile.open(f)).isNull();
        assertThat(b.closed()).isTrue();
    }

    @Test
    void theFolderIsTrimmedLeastRecentlyMappedFirst() throws Exception {
        long size = 0;
        for (int d = 1; d <= 6; d++) {
            Path f = tmp.resolve("trim/k/7@1/2026-09-0" + d + ".idmap");
            size = write(f, 2_000).fileBytes();
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(1_000_000L * d));
        }
        Files.writeString(tmp.resolve("trim/k/7@1/2026-09-09.idmap.tmp"), "half");
        Path keep = tmp.resolve("trim/k/7@1/2026-09-01.idmap");
        int gone = MappedIdFile.trim(tmp.resolve("trim"), 3 * size, keep);
        assertThat(gone).isEqualTo(4);                                 // the leftover and three of the oldest, never the one kept
        try (var s = Files.list(tmp.resolve("trim/k/7@1"))) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList())
                    .isEqualTo(List.of("2026-09-01.idmap", "2026-09-05.idmap", "2026-09-06.idmap"));
        }
        assertThat(MappedIdFile.folderBytes(tmp.resolve("trim"))).isEqualTo(3 * size);
    }

    /** On Linux, no deleted file stays mapped after fifty versions of a date, each released when the next is written. */
    @Test
    void noDeletedFileStaysMapped() throws Exception {
        Path prev = null;
        for (int v = 0; v < 50; v++) {
            Path f = tmp.resolve("v/k/" + v + "@1/2026-09-30.idmap");
            write(f, 500);
            if (prev != null) {
                MappedIdFile.release(prev, true);
            }
            prev = f;
        }
        Path maps = Path.of("/proc/self/maps");
        if (Files.isReadable(maps)) {
            String self = tmp.toRealPath().toString();
            long deleted = Files.readAllLines(maps).stream().filter(l -> l.contains(self) && l.contains("(deleted)")).count();
            assertThat(deleted).isZero();
        }
        assertThat(MappedIdFile.mapped()[0]).isGreaterThanOrEqualTo(1);
    }
}
