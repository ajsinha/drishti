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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.compute.vector.ParallelSums;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hot-file side of the 2.9.6 direct-memory exhaustion: several dates' VaR hot files opened (and their largest sections re-read)
 * on many platform and virtual threads at once. On Java 21 a whole-section read into a heap buffer left a temporary direct buffer as
 * large as the section cached on every thread, until {@code -XX:MaxDirectMemorySize} ran out. Runs alone in a JVM with a small cap
 * (the module's pom), without {@code jdk.nio.maxCachedBufferSize}: the code must hold direct memory flat by itself. 9,000 trades a date.
 */
class HotFileDirectMemorySoakTest {

    private static final int DATES = 3;
    private static final int PLATFORM_THREADS = 24;
    private static final int VIRTUAL_THREADS = 48;

    @TempDir
    Path dir;

    @Test
    void manyThreadsOpeningSeveralDatesKeepDirectMemoryFlat() throws Exception {
        long seconds = Long.getLong("drishti.soak.seconds", 10);
        SyntheticBank bank = new SyntheticBank(2, 3, 4, 3000, 3);             // 24 books, 3,000 positions, 9,000 trades
        List<Path> files = new ArrayList<>();
        try (ParallelSums sums = new ParallelSums(2, 64)) {
            for (int d = 0; d < DATES; d++) {
                VarBook b = bank.buildMapped(40 + d, sums, Long.MAX_VALUE, Files.createDirectories(dir.resolve("date-" + d)));
                files.add(b.hot().file());
                b.close();
            }
        }
        long largest = 0;
        try (FileChannel ch = FileChannel.open(files.get(0), StandardOpenOption.READ)) {
            for (Map.Entry<String, long[]> e : VarHotFile.table(ch).entrySet()) {
                if (!e.getKey().equals("PMAT") && !e.getKey().equals("TMAT")) {
                    largest = Math.max(largest, e.getValue()[1]);              // the sections read whole into the heap
                }
            }
        }
        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(p -> p.getName().equals("direct")).findFirst().orElseThrow();
        long baseline = direct.getMemoryUsed();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicLong opens = new AtomicLong();
        int trades = bank.positions * bank.tradesPerPosition;
        Runnable worker = () -> {
            try {
                while (System.nanoTime() < deadline && failures.isEmpty()) {
                    Path f = files.get(ThreadLocalRandom.current().nextInt(DATES));
                    VarBook b = VarHotFile.open(f, 0);
                    try {
                        assertThat(b.tradeCount()).isEqualTo(trades);
                    } finally {
                        b.close();
                    }
                    opens.incrementAndGet();
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
            assertThat(platform.awaitTermination(60, TimeUnit.SECONDS) & virtual.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
        samples.add(direct.getMemoryUsed() - baseline);
        long peak = samples.stream().mapToLong(Long::longValue).max().orElse(0);
        if (!failures.isEmpty()) {
            Throwable first = failures.peek();
            throw new AssertionError(failures.size() + " opener(s) failed after " + opens.get() + " opens (largest section " + mb(largest)
                    + "); direct memory peaked at " + mb(peak) + ": " + first, first);
        }
        int half = samples.size() / 2;
        long firstHalf = samples.subList(0, half).stream().mapToLong(Long::longValue).max().orElse(0);
        long secondHalf = samples.subList(half, samples.size()).stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.printf("hot-file direct-memory soak: %d opens of %d dates in %d s on %d platform + %d virtual threads (largest section %s);"
                + " direct memory peak %s (first half %s, second half %s)%n", opens.get(), DATES, seconds, PLATFORM_THREADS, VIRTUAL_THREADS,
                mb(largest), mb(peak), mb(firstHalf), mb(secondHalf));
        assertThat(opens.get()).isGreaterThan(50);
        assertThat(peak).as("direct memory stays well under the cap").isLessThan(16L << 20);
        assertThat(secondHalf).as("direct memory is flat, not climbing").isLessThanOrEqualTo(firstHalf + (4L << 20));
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}
