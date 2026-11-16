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
package com.ash.drishti.lakeio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The 2.9.6 direct-memory exhaustion, reproduced and held off: many platform and virtual threads read multi-megabyte
 * ranges of local files into heap buffers through {@link LocalStorage} and {@link RangeCursor} (the path the Delta
 * kernel's streams take). Before the fix each thread's JDK temporary direct buffer grew to the read's size and stayed
 * cached, so direct memory climbed past {@code -XX:MaxDirectMemorySize} within seconds ("Cannot reserve 8192 bytes of
 * direct buffer memory"). This test runs in its own JVM with {@code -XX:MaxDirectMemorySize=64m} (see the module's
 * pom) and asserts that no read fails, every read returns the right bytes, and direct memory stays flat and well under
 * the cap. {@code -Ddrishti.soak.seconds} lengthens it for a local soak.
 */
class DirectMemorySoakTest {

    private static final int FILES = 4;
    private static final int FILE_BYTES = 8 << 20;
    private static final int READ_BYTES = 4 << 20;
    private static final int PLATFORM_THREADS = 24;
    private static final int VIRTUAL_THREADS = 48;

    @TempDir
    Path dir;

    @Test
    void manyThreadsReadingLargeRangesKeepDirectMemoryFlat() throws Exception {
        long seconds = Long.getLong("drishti.soak.seconds", 12);
        byte[][] content = new byte[FILES][];
        Random random = new Random(42);
        for (int i = 0; i < FILES; i++) {
            content[i] = new byte[FILE_BYTES];
            random.nextBytes(content[i]);
            Files.write(dir.resolve("part-" + i + ".bin"), content[i]);
        }
        LocalStorage storage = new LocalStorage();
        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(b -> b.getName().equals("direct")).findFirst().orElseThrow();
        long baseline = direct.getMemoryUsed();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicLong reads = new AtomicLong();

        Runnable worker = () -> {
            byte[] into = new byte[READ_BYTES];
            ThreadLocalRandom r = ThreadLocalRandom.current();
            try {
                while (System.nanoTime() < deadline && failures.isEmpty()) {
                    int file = r.nextInt(FILES);
                    int len = READ_BYTES / 2 + r.nextInt(READ_BYTES / 2);
                    int at = r.nextInt(FILE_BYTES - len);
                    RangeCursor cursor = new RangeCursor(storage.open(dir.resolve("part-" + file + ".bin").toString(), FILE_BYTES));
                    try {
                        cursor.seek(at);
                        if (r.nextBoolean()) {
                            cursor.readFully(ByteBuffer.wrap(into, 0, len));
                        } else {
                            int got = 0;
                            while (got < len) {
                                int n = cursor.read(ByteBuffer.wrap(into, got, len - got));
                                assertTrue(n > 0, "a read inside the file returned " + n);
                                got += n;
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                    if (r.nextInt(8) == 0) {
                        assertArrayEquals(Arrays.copyOfRange(content[file], at, at + len), Arrays.copyOf(into, len), "the bytes read");
                    }
                    reads.incrementAndGet();
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        };

        List<Long> samples = new ArrayList<>();
        ExecutorService platform = Executors.newFixedThreadPool(PLATFORM_THREADS);
        ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < PLATFORM_THREADS; i++) {
                platform.execute(worker);
            }
            for (int i = 0; i < VIRTUAL_THREADS; i++) {
                virtual.execute(worker);
            }
            while (System.nanoTime() < deadline && failures.isEmpty()) {
                samples.add(direct.getMemoryUsed() - baseline);
                Thread.sleep(50);
            }
        } finally {
            platform.shutdown();
            virtual.shutdown();
            assertTrue(platform.awaitTermination(60, TimeUnit.SECONDS) & virtual.awaitTermination(60, TimeUnit.SECONDS), "the readers stop");
        }
        samples.add(direct.getMemoryUsed() - baseline);

        if (!failures.isEmpty()) {
            Throwable first = failures.peek();
            throw new AssertionError(failures.size() + " reader(s) failed after " + reads.get() + " reads; direct memory peaked at "
                    + mb(samples.stream().mapToLong(Long::longValue).max().orElse(0)) + ": " + first, first);
        }
        long peak = samples.stream().mapToLong(Long::longValue).max().orElse(0);
        int half = samples.size() / 2;
        long firstHalf = samples.subList(0, half).stream().mapToLong(Long::longValue).max().orElse(0);
        long secondHalf = samples.subList(half, samples.size()).stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.printf("direct-memory soak: %d reads in %d s on %d platform + %d virtual threads; direct memory peak %s (first half %s,"
                + " second half %s)%n", reads.get(), seconds, PLATFORM_THREADS, VIRTUAL_THREADS, mb(peak), mb(firstHalf), mb(secondHalf));
        assertTrue(reads.get() > 100, "the readers did work: " + reads.get());
        assertTrue(peak < 32L << 20, "direct memory stays well under the 64 MB cap: peak " + mb(peak));
        assertTrue(secondHalf <= firstHalf + (4L << 20), "direct memory is flat, not climbing: " + mb(firstHalf) + " then " + mb(secondHalf));
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}
