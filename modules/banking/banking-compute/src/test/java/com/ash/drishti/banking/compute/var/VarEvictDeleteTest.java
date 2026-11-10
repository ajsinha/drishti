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
package com.ash.drishti.banking.compute.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.compute.vector.MappedMatrix;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An evicted date's hot file is deleted only once every mapping of it is released (Windows refuses to delete a mapped file): the
 * positions', the trades' and the aggregates' matrices, each through {@code FileMaps}' reader counting. Requests reading while the date
 * leaves finish or are told to ask again, never crash; then the file is gone and nothing of it stays mapped.
 */
class VarEvictDeleteTest {

    static final ParallelSums SUMS = new ParallelSums(2, 4096);

    @AfterAll
    static void close() {
        SUMS.close();
    }

    @Test
    void theFileIsDeletedAfterEveryMappingIsReleased(@TempDir Path dir) throws Exception {
        VarBook b = VarHotFileTest.BANK.buildMapped(3, SUMS, Long.MAX_VALUE, dir);
        Path file = b.hot().file();
        OrdinalSet all = b.all();
        OrdinalSet some = OrdinalSet.range(0, b.tradeCount() / 3);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger told = new AtomicInteger();
        AtomicInteger read = new AtomicInteger();
        Thread[] readers = new Thread[3];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        b.vector(all, SUMS);                             // the bank's aggregate (mapped)
                        b.vector(some, SUMS);                            // position rows and aggregates (mapped)
                        read.incrementAndGet();
                    } catch (MappedMatrix.Closed e) {
                        told.incrementAndGet();                          // ask again: the date left while this answer was computed
                        return;
                    }
                }
            });
            readers[i].start();
        }
        while (read.get() < 3) {
            Thread.onSpinWait();
        }
        b.closeAndDelete();
        for (Thread t : readers) {
            t.join(10_000);
        }
        stop.set(true);
        assertThat(told.get()).as("every reader was told to ask again").isEqualTo(readers.length);
        assertThat(b.hot().positions().unmapped()).isTrue();
        assertThat(b.hot().aggregates().unmapped()).isTrue();
        assertThat(b.hot().trades() == null || b.hot().trades().unmapped()).isTrue();
        assertThat(file).as("deleted once unmapped").doesNotExist();
        Path maps = Path.of("/proc/self/maps");
        if (Files.isReadable(maps)) {
            String name = file.getFileName().toString();
            assertThat(Files.readAllLines(maps).stream().filter(l -> l.contains(name)).toList()).as("nothing of it stays mapped").isEmpty();
        }
    }
}
