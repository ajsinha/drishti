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
}
