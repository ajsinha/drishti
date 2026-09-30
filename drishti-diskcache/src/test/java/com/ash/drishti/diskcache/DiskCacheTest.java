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
package com.ash.drishti.diskcache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskCacheTest {

    static final ZoneId NY = ZoneId.of("America/New_York");

    @TempDir
    Path dir;

    @Test
    void storesReadsDeletesAndClears() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try (DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC())) {
            c.put("trade/T-1", "{\"mtm\":1}".getBytes(StandardCharsets.UTF_8));
            c.put("trade/T-1", "{\"mtm\":2}".getBytes(StandardCharsets.UTF_8));
            assertThat(new String(c.get("trade/T-1"), StandardCharsets.UTF_8)).isEqualTo("{\"mtm\":2}");
            c.delete("trade/T-1");
            assertThat(c.get("trade/T-1")).isNull();
            c.put("trade/T-2", new byte[] {1});
            c.clear();
            assertThat(c.get("trade/T-2")).isNull();
            assertThat(c.resets()).isEqualTo(1);
            assertThat(c.hits()).isEqualTo(1);
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void aRestartStartsEmpty() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            try (DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC())) {
                c.put("k", new byte[] {1});
            }
            try (DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC())) {
                assertThat(c.get("k")).isNull();
                try (Stream<Path> gens = Files.list(dir)) {
                    assertThat(gens.count()).isEqualTo(1);
                }
            }
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void theNightlyClearingIsTheNextOccurrenceInTheZone() {
        ZonedDateTime evening = ZonedDateTime.of(2026, 9, 30, 21, 0, 0, 0, NY);
        assertThat(DiskCache.nextReset(evening, LocalTime.of(2, 0))).isEqualTo(ZonedDateTime.of(2026, 10, 1, 2, 0, 0, 0, NY));
        ZonedDateTime night = ZonedDateTime.of(2026, 10, 1, 1, 30, 0, 0, NY);
        assertThat(DiskCache.nextReset(night, LocalTime.of(2, 0))).isEqualTo(ZonedDateTime.of(2026, 10, 1, 2, 0, 0, 0, NY));
        // across the autumn clock change the clearing stays at 02:00 local time
        ZonedDateTime beforeDst = ZonedDateTime.of(2026, 10, 31, 22, 0, 0, 0, NY);
        assertThat(DiskCache.nextReset(beforeDst, LocalTime.of(2, 0)).toLocalTime()).isEqualTo(LocalTime.of(2, 0));
    }

    /**
     * Many threads read and write while another clears over and over and finally closes. Each retired generation is
     * closed the moment its last caller leaves, so a caller left inside a closed RocksDB would crash the JVM here.
     */
    @Test
    void readersAndWritersNeverTouchAClosedStore() throws Exception {
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
        ExecutorService workers = Executors.newFixedThreadPool(32);   // platform threads: the workers spin without blocking, which would starve virtual-thread carriers
        DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC());
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong ops = new AtomicLong();
        CountDownLatch started = new CountDownLatch(32);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (int t = 0; t < 32; t++) {
                int id = t;
                running.add(workers.submit(() -> {
                    started.countDown();
                    byte[] v = new byte[512];
                    for (long n = 0; !stop.get(); n++) {
                        String key = "k" + id + "-" + (n % 200);
                        c.put(key, v);
                        byte[] got = c.get(key);
                        if (got != null && got.length != v.length) {
                            throw new AssertionError("torn value");
                        }
                        if (n % 7 == 0) {
                            c.delete(key);
                        }
                        c.sizeOnDisk();
                        ops.incrementAndGet();
                    }
                    return null;
                }));
            }
            started.await();
            for (int i = 0; i < 150; i++) {
                c.clear();
            }
            c.close();                        // while every worker is still running
            Thread.sleep(50);
            stop.set(true);
            for (Future<?> f : running) {
                f.get(30, TimeUnit.SECONDS);  // rethrows anything a worker hit
            }
            assertThat(ops.get()).isGreaterThan(1000);
            assertThat(c.resets()).isEqualTo(150);
            assertThat(c.get("k0-0")).isNull();           // closed: reads miss, writes are dropped
            c.put("k0-0", new byte[] {1});
            c.clear();                                    // no effect after close
            assertThat(c.resets()).isEqualTo(150);
        } finally {
            stop.set(true);
            workers.shutdown();
            workers.awaitTermination(30, TimeUnit.SECONDS);
            sched.shutdown();
            sched.awaitTermination(30, TimeUnit.SECONDS);
        }
        try (Stream<Path> gens = Files.list(dir)) {
            assertThat(gens.toList()).as("every generation closed and deleted").isEmpty();
        }
    }

    @Test
    void closeAndClearFromManyThreadsAtOnce() throws Exception {
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
        ExecutorService workers = Executors.newFixedThreadPool(32);   // platform threads: the workers spin without blocking, which would starve virtual-thread carriers
        DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> all = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            int id = t;
            all.add(workers.submit(() -> {
                go.await();
                for (int i = 0; i < 20; i++) {
                    if (id == 15 && i == 10) {
                        c.close();
                    } else {
                        c.clear();
                        c.put("k", new byte[] {1});
                        c.get("k");
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : all) {
            f.get(60, TimeUnit.SECONDS);
        }
        assertThat(c.isClosed()).isTrue();
        workers.shutdown();
        sched.shutdown();
        sched.awaitTermination(30, TimeUnit.SECONDS);
        try (Stream<Path> gens = Files.list(dir)) {
            assertThat(gens.toList()).as("no generation leaked").isEmpty();
        }
    }

    @Test
    void aPersistentCacheKeepsItsEntriesAcrossRestarts() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            try (DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC(), true)) {
                c.put("trade/T-1", new byte[] {1});
                c.put("trade/T-2", new byte[] {2});
            }
            try (DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC(), true)) {
                assertThat(c.get("trade/T-1")).containsExactly(1);
                List<String> keys = new ArrayList<>();
                c.forEach((k, v) -> keys.add(k));
                assertThat(keys).containsExactly("trade/T-1", "trade/T-2");
            }
            try (DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC(), false)) {
                assertThat(c.get("trade/T-1")).isNull();                       // a plain cache still starts empty
            }
        } finally {
            sched.shutdownNow();
        }
    }
}
