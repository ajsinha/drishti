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
package com.ash.drishti.memlake.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.lakeio.RangeReader;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The memory store's own promises: the hard cap, refusals that keep nothing, readers that outlive a delete, chunks reused and released. */
class MemoryStorageTest {

    private static final int CHUNK = 8192;

    @AfterEach
    void drop() {
        MemoryStores.remove("cap");
    }

    @Test
    void theCapIsHardAndARefusedWriteKeepsNothing() throws Exception {
        MemoryStorage s = MemoryStores.define("cap", 4L * CHUNK, CHUNK, "VX-2026-07");
        s.put("mem://cap/a.bin", new byte[3 * CHUNK]);
        assertThat(s.pool().inUseBytes()).isEqualTo(3L * CHUNK);
        assertThatThrownBy(() -> s.put("mem://cap/b.bin", new byte[2 * CHUNK])).isInstanceOf(MemoryStoreFullException.class)
                .hasMessageContaining("memory store cap is full").hasMessageContaining("contract VX-2026-07");
        assertThat(s.pool().inUseBytes()).as("the refused write's chunk went back").isEqualTo(3L * CHUNK);
        assertThatThrownBy(() -> s.status("mem://cap/b.bin")).isInstanceOf(FileNotFoundException.class);
        assertThat(s.pool().allocatedBytes()).isLessThanOrEqualTo(4L * CHUNK);
        assertThat(s.refusals()).isEqualTo(1);
    }

    @Test
    void theRoomMakerRunsBeforeARefusal() throws Exception {
        MemoryStorage s = MemoryStores.define("cap", 4L * CHUNK, CHUNK, "");
        s.put("mem://cap/old/x.bin", new byte[3 * CHUNK]);
        AtomicInteger asked = new AtomicInteger();
        s.roomMaker((store, bytes, writing) -> {
            asked.incrementAndGet();
            assertThat(writing).isEqualTo("mem://cap/new/y.bin");
            try {
                return store.deleteUnder("mem://cap/old") > 0;
            } catch (java.io.IOException e) {
                return false;
            }
        });
        s.put("mem://cap/new/y.bin", new byte[3 * CHUNK]);
        assertThat(asked.get()).isEqualTo(1);
        assertThat(s.isDirectory("mem://cap/old")).isFalse();
        assertThat(s.status("mem://cap/new/y.bin").size()).isEqualTo(3L * CHUNK);
    }

    @Test
    void aReaderFinishesOnAFileDeletedUnderIt() throws Exception {
        MemoryStorage s = MemoryStores.define("cap", 16L * CHUNK, CHUNK, "");
        byte[] content = new byte[2 * CHUNK + 10];
        content[2 * CHUNK + 5] = 42;
        s.put("mem://cap/t/part.parquet", content);
        try (RangeReader r = s.open("mem://cap/t/part.parquet", -1)) {
            assertThat(s.delete("mem://cap/t/part.parquet")).isTrue();
            assertThat(s.pool().inUseBytes()).as("held by the reader").isEqualTo(3L * CHUNK);
            s.put("mem://cap/t/other.parquet", new byte[4 * CHUNK]);
            ByteBuffer b = ByteBuffer.allocate(10);
            r.readFully(2 * CHUNK, b);
            assertThat(b.get(5)).isEqualTo((byte) 42);
        }
        assertThat(s.pool().inUseBytes()).as("freed when the reader left").isEqualTo(4L * CHUNK);
    }

    @Test
    void chunksAreReusedAndReleasedWhenTheCapIsLowered() throws Exception {
        MemoryStorage s = MemoryStores.define("cap", 32L * CHUNK, CHUNK, "");
        for (int i = 0; i < 50; i++) {                   // a soak of replaces: allocation never grows past one file's chunks plus one
            s.put("mem://cap/t/data.bin", new byte[8 * CHUNK]);
        }
        assertThat(s.pool().allocatedBytes()).isLessThanOrEqualTo(16L * CHUNK);
        s.delete("mem://cap/t/data.bin");
        assertThat(s.pool().inUseBytes()).isZero();
        s.pool().cap(2L * CHUNK);
        assertThat(s.pool().allocatedBytes()).isLessThanOrEqualTo(2L * CHUNK);
        assertThat(s.pool().trim(0)).isGreaterThanOrEqualTo(0);
        assertThat(s.pool().allocatedBytes()).isZero();
        assertThat(ChunkPool.explicitRelease()).isTrue();
    }

    @Test
    void anUndefinedStoreHoldsNothingAndRefuses() {
        MemoryStorage s = MemoryStores.store("cap");
        assertThatThrownBy(() -> s.put("mem://cap/x", new byte[] {1})).isInstanceOf(MemoryStoreFullException.class);
        assertThatThrownBy(() -> s.put("mem://other/x", new byte[] {1})).hasMessageContaining("is not in memory store cap");
        assertThatThrownBy(() -> s.put("mem://cap/../x", new byte[] {1})).hasMessageContaining("climb");
        assertThat(MemoryStores.storeName("mem://cap/news/_delta_log")).isEqualTo("cap");
    }
}
