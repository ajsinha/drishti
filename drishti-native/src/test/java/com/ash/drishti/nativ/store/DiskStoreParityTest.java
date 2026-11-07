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
package com.ash.drishti.nativ.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The disk store's two implementations give the same answers (DRISHTI_NATIVE.md section 8: the same answers for every key, and scans in
 * the same key order), and the Java one keeps a cache's budget and age limit; the mode chooses between them.
 */
class DiskStoreParityTest {

    @TempDir
    Path dir;

    @AfterEach
    void auto() {
        DiskStores.configure("auto");
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Every operation on both, one random workload: the same values for every key and the same scans. */
    @Test
    void rocksDbAndMvStoreGiveTheSameAnswers() throws Exception {
        assertThat(RocksDbStore.load()).as("RocksDB loads on the build platforms").isNull();
        for (DiskStore.Options how : List.of(DiskStore.Options.state(1L << 30, true, false), DiskStore.Options.sorted(true),
                DiskStore.Options.cache(1L << 30, null, false, false))) {
            try (DiskStore rocks = RocksDbStore.open(dir.resolve(how.kind() + "-rocks"), how);
                    DiskStore mv = MvStore.open(dir.resolve(how.kind() + "-mv"), how, System::currentTimeMillis)) {
                Random r = new Random(42);
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < 2_000; i++) {
                    // keys past ASCII too: UTF-8 bytes above 0x7F sort after ASCII in unsigned order, as RocksDB keeps them
                    String k = (r.nextBoolean() ? "k" : "é") + Integer.toString(r.nextInt(500), 36) + (r.nextInt(7) == 0 ? "😀" : "");
                    keys.add(k);
                    byte[] v = new byte[r.nextInt(64)];
                    r.nextBytes(v);
                    switch (r.nextInt(4)) {
                        case 0 -> {
                            rocks.delete(b(k));
                            mv.delete(b(k));
                        }
                        case 1 -> {
                            List<byte[][]> batch = List.of(new byte[][] {b(k), v}, new byte[][] {b(k + "/x"), v});
                            rocks.putAll(batch);
                            mv.putAll(batch);
                        }
                        default -> {
                            rocks.put(b(k), v);
                            mv.put(b(k), v);
                        }
                    }
                }
                for (String k : keys) {
                    assertThat(mv.get(b(k))).as(how.kind() + " " + k).isEqualTo(rocks.get(b(k)));
                }
                assertThat(scan(mv, null)).isEqualTo(scan(rocks, null));
                assertThat(scan(mv, b("k1"))).isEqualTo(scan(rocks, b("k1")));
                rocks.flush();
                mv.flush();
                rocks.compact();
                mv.compact();
                assertThat(scan(mv, null)).isEqualTo(scan(rocks, null));
                assertThat(rocks.impl()).isEqualTo("rocksdb");
                assertThat(mv.impl()).isEqualTo("mvstore");
                assertThat(mv.sizeBytes()).isPositive();
            }
        }
    }

    private static List<String> scan(DiskStore s, byte[] from) throws Exception {
        List<String> out = new ArrayList<>();
        s.scan(from, (k, v) -> out.add(new String(k, StandardCharsets.UTF_8) + "=" + java.util.HexFormat.of().formatHex(v)));
        return out;
    }

    @Test
    void theJavaStoreKeepsWhatItWroteAcrossAReopen() throws Exception {
        Path d = dir.resolve("state");
        try (DiskStore s = MvStore.open(d, DiskStore.Options.state(1L << 20, false, false), System::currentTimeMillis)) {
            s.put(b("a"), b("1"));
            s.flush();
        }
        try (DiskStore s = MvStore.open(d, DiskStore.Options.sorted(false), System::currentTimeMillis)) {
            assertThat(s).isNotNull();
            assertThat(s.get(b("a"))).isEqualTo(b("1"));
        }
        assertThat(MvStore.open(dir.resolve("none"), DiskStore.Options.sorted(false), System::currentTimeMillis)).isNull();
    }

    @Test
    void theJavaCacheEmptiesPastItsBudgetAndDropsExpiredEntries() throws Exception {
        try (DiskStore s = MvStore.open(dir.resolve("budget"), DiskStore.Options.cache(1_000, null, false, false), System::currentTimeMillis)) {
            for (int i = 0; i < 50; i++) {
                s.put(b("k" + i), new byte[30]);
            }
            int[] n = {0};
            s.scan(null, (k, v) -> ++n[0] > 0);
            assertThat(n[0]).as("emptied past 1,000 bytes, then refilled").isLessThan(50).isPositive();
        }
        AtomicLong now = new AtomicLong(1_000_000);
        try (DiskStore s = MvStore.open(dir.resolve("ttl"), DiskStore.Options.cache(1L << 20, Duration.ofSeconds(10), false, false), now::get)) {
            s.put(b("old"), b("1"));
            now.addAndGet(6_000);
            s.put(b("new"), b("2"));
            assertThat(s.get(b("old"))).isEqualTo(b("1"));
            now.addAndGet(6_000);
            assertThat(s.get(b("old"))).as("past its age").isNull();
            assertThat(s.get(b("new"))).isEqualTo(b("2"));
            now.addAndGet(6_000);
            s.compact();
            int[] n = {0};
            s.scan(null, (k, v) -> ++n[0] > 0);
            assertThat(n[0]).isZero();
        }
    }

    @Test
    void theModeChoosesTheImplementation() throws Exception {
        DiskStores.configure("java");
        try (DiskStore s = DiskStores.open(dir.resolve("j"), DiskStore.Options.sorted(true))) {
            assertThat(s.impl()).isEqualTo("mvstore");
        }
        assertThat(DiskStores.inUse(dir.resolve("j"))).as("closed: its lock is free").isFalse();
        assertThat(DiskStores.status().toMap()).containsEntry("impl", "mvstore").containsEntry("status", "OK");
        DiskStores.configure("auto");
        try (DiskStore s = DiskStores.open(dir.resolve("a"), DiskStore.Options.sorted(true))) {
            assertThat(s.impl()).isEqualTo("rocksdb");
        }
        assertThat(DiskStores.status().toMap()).containsEntry("impl", "rocksdb").containsEntry("status", "OK");
        assertThat(DiskStores.Mode.of("off")).isEqualTo(DiskStores.Mode.JAVA);
        assertThat(DiskStores.Mode.of("bogus")).isEqualTo(DiskStores.Mode.AUTO);
    }
}
