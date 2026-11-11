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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * {@link VectorReads}: files decoded several at once reach the sink exactly as one thread reading them in order gives them (ids, every
 * value to the bit, texts, missing vectors, the order), always on the reading thread; a failing file fails the read with its own error and
 * nothing after it reaches the sink; a sink that throws stops the workers; the rows waiting are bounded.
 */
class VectorReadsTest {

    static final int FILES = 7;
    static final int WIDTH = 509;

    /** Rows of one file, deterministic per file: some without a vector, some texts null, a few NaN and negative zero values. */
    static void file(int f, VectorSink to, int rowsPerFile) {
        Random r = new Random(1000 + f);
        double[] v = new double[WIDTH];
        String[] t = new String[3];
        for (int i = 0; i < rowsPerFile + f * 37; i++) {
            String id = "F" + f + "-T" + i;
            t[0] = "DESK-" + (i % 5);
            t[1] = i % 11 == 0 ? null : "BOOK-" + (i % 17);
            t[2] = Double.toString(i * 0.5);
            if (i % 97 == 3) {
                to.row(id, v, -1, t);
                continue;
            }
            int len = i % 50 == 7 ? WIDTH - 9 : WIDTH;
            for (int j = 0; j < len; j++) {
                v[j] = j == 5 && i % 13 == 0 ? Double.NaN : j == 6 && i % 13 == 0 ? -0.0 : r.nextGaussian() * 1e5;
            }
            to.row(id, v, len, t);
        }
    }

    /** What reached a sink: one line per row, every value's bits. */
    static final class Seen implements VectorSink {
        final List<String> rows = new ArrayList<>();
        final Thread owner = Thread.currentThread();
        boolean otherThread;

        @Override
        public void row(String id, double[] values, int length, String[] texts) {
            otherThread |= Thread.currentThread() != owner;
            StringBuilder b = new StringBuilder(id).append('|').append(length).append('|').append(Arrays.toString(texts));
            for (int i = 0; i < length; i++) {
                b.append(',').append(Long.toHexString(Double.doubleToRawLongBits(values[i])));
            }
            rows.add(b.toString());
        }
    }

    static int[] all() {
        int[] f = new int[FILES];
        for (int i = 0; i < FILES; i++) {
            f[i] = i;
        }
        return f;
    }

    @Test
    void parallelDeliversTheSameRowsInFileOrderOnTheReadingThread() {
        Seen serial = new Seen();
        VectorReads.inFileOrder(all(), serial, (f, to) -> file(f, to, 900));
        for (int threads : new int[] {2, 3, 8}) {
            for (long buffer : new long[] {1L << 20, 64L << 20}) {
                Seen par = new Seen();
                VectorReads.inFileOrder(all(), VectorReads.parallel(par, new VectorReads.Options(threads, buffer)), (f, to) -> {
                    if (f % 2 == 0) {
                        Thread.sleep(5);                             // the earlier files finish later: order still holds
                    }
                    file(f, to, 900);
                });
                assertThat(par.otherThread).isFalse();
                assertThat(par.rows).as(threads + " threads, " + buffer + " bytes").isEqualTo(serial.rows);
            }
        }
        assertThat(serial.rows).hasSize(FILES * 900 + 37 * (FILES * (FILES - 1) / 2));
    }

    @Test
    void aSubsetOfFilesAndOneThreadReadInTheGivenOrder() {
        int[] some = {1, 4, 6};
        Seen serial = new Seen();
        VectorReads.inFileOrder(some, serial, (f, to) -> file(f, to, 50));
        Seen one = new Seen();
        VectorReads.inFileOrder(some, VectorReads.parallel(one, new VectorReads.Options(1, 1L << 20)), (f, to) -> file(f, to, 50));
        Seen par = new Seen();
        VectorReads.inFileOrder(some, VectorReads.parallel(par, new VectorReads.Options(4, 1L << 20)), (f, to) -> file(f, to, 50));
        assertThat(one.rows).isEqualTo(serial.rows);
        assertThat(par.rows).isEqualTo(serial.rows);
        assertThat(VectorReads.parallel(serial, VectorReads.Options.serial())).isSameAs(serial);
    }

    @Test
    void aFailingFileFailsTheReadAndNothingAfterItIsGiven() {
        Seen serial = new Seen();
        IllegalStateException boom = new IllegalStateException("file 3 is not a Parquet file");
        VectorReads.FileRead failing = (f, to) -> {
            if (f == 3) {
                file(f, (id, v, len, t) -> {
                    if (id.endsWith("-T500")) {
                        throw boom;
                    }
                    to.row(id, v, len, t);
                }, 900);
                return;
            }
            file(f, to, 900);
        };
        assertThatThrownBy(() -> VectorReads.inFileOrder(all(), serial, failing)).isSameAs(boom);
        Seen par = new Seen();
        assertThatThrownBy(() -> VectorReads.inFileOrder(all(), VectorReads.parallel(par, new VectorReads.Options(4, 1L << 20)), failing))
                .isSameAs(boom);
        assertThat(par.rows).isEqualTo(serial.rows);                     // files 0-2 and file 3 up to its failure, as one thread gives them
        assertThat(par.rows).noneMatch(r -> r.startsWith("F4-") || r.startsWith("F5-") || r.startsWith("F6-"));
    }

    @Test
    void aCheckedFailureIsWrapped() {
        Seen par = new Seen();
        assertThatThrownBy(() -> VectorReads.inFileOrder(all(), VectorReads.parallel(par, new VectorReads.Options(3, 1L << 20)), (f, to) -> {
            if (f == 1) {
                throw new java.io.IOException("unreadable");
            }
            file(f, to, 10);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("unreadable");
    }

    @Test
    void aSinkThatThrowsStopsTheWorkers() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger given = new AtomicInteger();
        RuntimeException full = new RuntimeException("no room for this date");
        VectorSink sink = VectorReads.parallel((id, v, len, t) -> {
            if (given.incrementAndGet() == 2000) {
                throw full;
            }
        }, new VectorReads.Options(4, 1L << 20));
        assertThatThrownBy(() -> VectorReads.inFileOrder(all(), sink, (f, to) -> {
            running.incrementAndGet();
            try {
                file(f, to, 20_000);
            } finally {
                running.decrementAndGet();
            }
        })).isSameAs(full);
        long until = System.currentTimeMillis() + 10_000;
        while (running.get() > 0 && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        assertThat(running.get()).as("workers still decoding after the read stopped").isZero();
    }

    @Test
    void theRowsWaitingAreBounded() {
        AtomicLong made = new AtomicLong();
        AtomicLong taken = new AtomicLong();
        AtomicLong most = new AtomicLong();
        int threads = 4;
        VectorSink slow = VectorReads.parallel((id, v, len, t) -> {
            long now = made.get() - taken.incrementAndGet();
            most.accumulateAndGet(now, Math::max);
            if (taken.get() % 200 == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, new VectorReads.Options(threads, 1L << 20));
        VectorReads.inFileOrder(all(), slow, (f, to) -> file(f, (id, v, len, t) -> {
            made.incrementAndGet();
            to.row(id, v, len, t);
        }, 3000));
        // threads files in flight, each with at most 3 queued chunks and the one it fills, and the chunk being replayed: chunks of at most
        // 1 MB / (threads x 5) of rows (a row of the shorter vectors is smaller: one more row a chunk)
        long rowBytes = 56 + 8L * (WIDTH - 9) + 40 * 3;
        long perChunk = Math.max(64L << 10, (1L << 20) / (threads * 5L)) / rowBytes + 2;
        assertThat(most.get()).isLessThanOrEqualTo((threads * 4L + 1) * perChunk);
    }
}
