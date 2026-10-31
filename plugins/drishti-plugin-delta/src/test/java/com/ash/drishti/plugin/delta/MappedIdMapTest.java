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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An id map written to a file and read through a memory map answers as the one in the heap. */
class MappedIdMapTest {

    @TempDir
    Path tmp;

    @Test
    void aMappedIdMapAnswersAsTheHeapOneDoesAndADamagedFileIsNotUsed() throws Exception {
        String[] ids = {"1000001", "CLY-3000001", "MX-20000001", "Zürich-7", "a_b-c", "10", "9"};
        java.util.Arrays.sort(ids);
        int[] files = new int[ids.length];
        for (int i = 0; i < files.length; i++) {
            files[i] = i % 3;
        }
        DeltaTable.IdMap heap = new DeltaTable.HeapIdMap(ids, files);
        Path file = tmp.resolve("trade").resolve("7@1").resolve("2026-10-16.idmap");
        MappedIdMap mapped = MappedIdMap.write(heap, file);
        assertThat(mapped.size()).isEqualTo(ids.length);
        for (String id : ids) {
            assertThat(mapped.file(id)).as(id).isEqualTo(heap.file(id));
        }
        assertThat(mapped.file("NOPE")).isEqualTo(-1);
        assertThat(mapped.file("")).isEqualTo(-1);
        assertThat(java.util.Set.of(mapped.ids())).isEqualTo(java.util.Set.of(ids));
        assertThat(MappedIdMap.open(file).file("Zürich-7")).isEqualTo(heap.file("Zürich-7"));       // read again, as after a restart

        Files.write(file, new byte[] {1, 2, 3});                                                // damaged: not used, written again
        assertThat(MappedIdMap.open(file)).isNull();
        assertThat(MappedIdMap.open(tmp.resolve("none.idmap"))).isNull();
    }

    /**
     * Fifty versions of one date in a row (2.9.7, RESOURCES_AND_SCALE.md section 7.3): at most two version folders stay (this one and the
     * one before, for a request started on it), and no deleted file stays mapped.
     */
    @Test
    void fiftyVersionsOfADateLeaveTwoFolders() throws Exception {
        DeltaTable.IdMap heap = new DeltaTable.HeapIdMap(new String[] {"A", "B", "C"}, new int[] {0, 1, 2});
        for (int v = 1; v <= 50; v++) {
            Path file = tmp.resolve("trade").resolve(v + "@" + (1000 + v)).resolve("2026-10-16.idmap");
            MappedIdMap.write(heap, file);
            DeltaSourcePlugin.pruneOtherVersions(file.getParent());
        }
        try (var s = Files.list(tmp.resolve("trade"))) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly("49@1049", "50@1050");
        }
        Path maps = Path.of("/proc/self/maps");
        if (Files.isReadable(maps)) {
            String self = tmp.toRealPath().toString();
            assertThat(Files.readAllLines(maps).stream().filter(l -> l.contains(self) && l.contains("(deleted)")).count()).isZero();
        }
        Files.createDirectories(tmp.resolve("trade/3@1003"));
        Files.writeString(tmp.resolve("trade/50@1050/x.idmap.tmp"), "half");
        DeltaSourcePlugin.sweepIdMaps(tmp, 0);                                                  // at start: the newest two, no leftovers
        try (var s = Files.list(tmp.resolve("trade"))) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly("49@1049", "50@1050");
        }
        assertThat(tmp.resolve("trade/50@1050/x.idmap.tmp")).doesNotExist();
    }
}
