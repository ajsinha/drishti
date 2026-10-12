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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The memory-mapped day segments and the disk tier of the multi-day index. */
class DaySegmentTest {

    @TempDir
    Path tmp;

    private static final LocalDate D0 = LocalDate.of(2026, 9, 1);

    private static EntityHit hit(String id) {
        return new EntityHit(EntityRef.of("trade", id), id, "trade · test");
    }

    private static List<EntityHit> hits(int day, int n) {
        List<EntityHit> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(hit("D" + day + "-" + i));
        }
        return l;
    }

    private static List<String> ids(List<EntityHit> l) {
        return l.stream().map(h -> h.ref().id()).toList();
    }

    @Test
    void aMappedSegmentAnswersExactlyLikeTheHeapDay() {
        Random rnd = new Random(11);
        for (int round = 0; round < 10; round++) {
            TreeSet<String> set = new TreeSet<>();
            for (int i = 0; i < 700; i++) {
                set.add((rnd.nextBoolean() ? "MX-" : "cly-") + rnd.nextInt(5000));
            }
            List<EntityHit> all = set.stream().map(DaySegmentTest::hit).toList();
            DatedHitIndex heap = new DatedHitIndex(Map.of("index.days", "2"), null);
            heap.putDay("trade", D0, all);
            DaySegment seg = DaySegment.write(tmp.resolve("r" + round + ".seg"), "w1", all);
            assertThat(seg.count()).isEqualTo(set.size());
            assertThat(seg.watermark()).isEqualTo("w1");
            for (String q : new String[] {"", "mx-1", "MX-49", "cly", "x-12", "test", "zzz", "mx-4999", "CLY-0"}) {
                for (int limit : new int[] {1, 7, 50, 5000}) {
                    List<EntityHit> got = new ArrayList<>();
                    seg.search(q.toLowerCase(), limit, got);
                    assertThat(ids(got)).as("q=%s limit=%d", q, limit).isEqualTo(ids(heap.search("trade", q, limit, D0).orElseThrow()));
                }
            }
        }
    }

    @Test
    void aSegmentIsRefusedWhenTruncatedOrCorruptAndAHalfWrittenOneIsNeverVisible() throws IOException {
        Path f = tmp.resolve("a.seg");
        DaySegment.write(f, null, hits(0, 500));
        byte[] bytes = Files.readAllBytes(f);
        Files.write(f, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertThat(DaySegment.open(f)).isEmpty();
        assertThat(f).doesNotExist();                                            // deleted, not served
        DaySegment.write(f, null, hits(0, 500));
        byte[] flipped = Files.readAllBytes(f);
        flipped[flipped.length / 2] ^= 0x5a;
        Files.write(f, flipped);
        assertThat(DaySegment.open(f)).isEmpty();
        // a crash mid-write leaves only a temporary name, which nobody opens
        Files.write(tmp.resolve("b.seg.tmp-123"), new byte[] {1, 2, 3});
        assertThat(DaySegment.open(tmp.resolve("b.seg"))).isEmpty();
    }

    @Test
    void storageMmapKeepsClosedDaysOnDiskAndAnswersThemFromTheMap() {
        Path dir = tmp.resolve("idx");
        Map<String, String> s = Map.of("index.days", "4", "index.storage", "mmap");
        DatedHitIndex idx = new DatedHitIndex("conn", dir, s, null, null);
        for (int d = 0; d < 4; d++) {
            idx.putDay("trade", D0.plusDays(d), hits(d, 300));
        }
        assertThat(idx.onDisk("trade", D0.plusDays(3))).isFalse();                // the newest day stays on the heap
        for (int d = 0; d < 3; d++) {
            assertThat(idx.onDisk("trade", D0.plusDays(d))).isTrue();
            assertThat(ids(idx.search("trade", "d" + d + "-29", 50, D0.plusDays(d)).orElseThrow())).contains("D" + d + "-29").doesNotContain("D3-29");
        }
        assertThat(idx.stats().diskDays()).isEqualTo(3);
        assertThat(idx.summary()).contains("3 on disk");
        // a roll drops the oldest day and its file
        idx.putDay("trade", D0.plusDays(4), hits(4, 300));
        assertThat(idx.has("trade", D0)).isFalse();
        assertThat(dir.resolve("conn").resolve("trade-" + D0 + ".seg")).doesNotExist();
        assertThat(idx.stats().diskDays()).isEqualTo(3);
    }

    @Test
    void reachingMaxMemoryMovesTheOldestClosedDaysToDiskInsteadOfDroppingThem() {
        Map<String, String> s = Map.of("index.days", "5", "index.max-memory", "250000");
        DatedHitIndex idx = new DatedHitIndex("conn", tmp.resolve("idx"), s, null, null);
        for (int d = 0; d < 5; d++) {
            idx.putDay("trade", D0.plusDays(d), hits(d, 1000));
        }
        assertThat(idx.stats().days()).isEqualTo(5);
        assertThat(idx.stats().diskDays()).isGreaterThan(0);
        assertThat(idx.stats().memoryBytes()).isLessThanOrEqualTo(250_000L);
        assertThat(idx.onDisk("trade", D0)).isTrue();
        assertThat(ids(idx.search("trade", "d0-99", 5, D0).orElseThrow())).startsWith("D0-99");
    }

    @Test
    void theDiskCapDropsTheOldestSegments() {
        Map<String, String> s = Map.of("index.days", "5", "index.storage", "mmap", "index.max-disk", "6000");
        DatedHitIndex idx = new DatedHitIndex("conn", tmp.resolve("idx"), s, null, null);
        for (int d = 0; d < 5; d++) {
            idx.putDay("trade", D0.plusDays(d), hits(d, 400));
        }
        assertThat(idx.stats().diskBytes()).isLessThanOrEqualTo(6_000L);
        assertThat(idx.stats().diskDays()).isLessThan(4);
        assertThat(idx.has("trade", D0.plusDays(4))).isTrue();
        assertThat(idx.has("trade", D0)).isFalse();
    }

    @Test
    void aRestartReusesAFileWhileTheSourceWatermarkMatchesAndRebuildsItWhenItDoesNot() throws Exception {
        Path root = tmp.resolve("idx");
        Map<String, String> s = Map.of("index.days", "3", "index.storage", "mmap");
        String[] mark = {"load-1"};
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        DatedHitIndex.DayLoader loader = (k, d) -> {
            loads.incrementAndGet();
            return hits(0, 50);
        };
        DatedHitIndex first = new DatedHitIndex("c", root, s, loader, (k, d) -> mark[0]);
        for (int d = 0; d < 3; d++) {
            first.putDay("trade", D0.plusDays(d), hits(d, 50));
        }
        TreeSet<LocalDate> available = new TreeSet<>(List.of(D0, D0.plusDays(1), D0.plusDays(2)));

        DatedHitIndex same = new DatedHitIndex("c", root, s, loader, (k, d) -> mark[0]);
        same.putDay("trade", D0.plusDays(2), hits(2, 50));
        same.plan("trade", available);
        assertThat(same.awaitWarm(5000)).isTrue();
        assertThat(loads.get()).isZero();                                         // both closed days came from their files
        assertThat(ids(same.search("trade", "d1-", 100, D0.plusDays(1)).orElseThrow())).hasSize(50).allMatch(i -> i.startsWith("D1-"));

        mark[0] = "load-2";                                                       // the source changed while the server was down
        DatedHitIndex changed = new DatedHitIndex("c", root, s, loader, (k, d) -> mark[0]);
        changed.putDay("trade", D0.plusDays(2), hits(2, 50));
        changed.plan("trade", available);
        assertThat(changed.awaitWarm(5000)).isTrue();
        assertThat(loads.get()).isEqualTo(2);                                     // both were rebuilt from the source
        assertThat(changed.onDisk("trade", D0)).isTrue();
    }

    @Test
    void aCrashLeftoverIsCleanedAtTheNextPlanAndNeverServed() throws Exception {
        Path root = tmp.resolve("idx");
        Files.createDirectories(root.resolve("c"));
        Files.write(root.resolve("c").resolve("trade-" + D0 + ".seg.tmp-9"), new byte[] {9, 9});
        Files.write(root.resolve("c").resolve("trade-" + D0.minusDays(30) + ".seg"), new byte[] {9, 9});   // a day long gone
        DatedHitIndex idx = new DatedHitIndex("c", root, Map.of("index.days", "2"), (k, d) -> hits(0, 5), (k, d) -> "w");
        idx.putDay("trade", D0.plusDays(1), hits(1, 5));
        idx.plan("trade", new TreeSet<>(List.of(D0, D0.plusDays(1))));
        idx.awaitWarm(5000);
        try (var files = Files.list(root.resolve("c"))) {
            assertThat(files.map(f -> f.getFileName().toString()).toList()).noneMatch(n -> n.contains(".tmp-") || n.contains(D0.minusDays(30).toString()));
        }
        assertThat(ids(idx.search("trade", "d0-", 10, D0).orElseThrow())).hasSize(5);
    }

    @Test
    void lookupsDuringARollWithDiskDaysNeverSeeABrokenDay() throws Exception {
        Map<String, String> s = Map.of("index.days", "3", "index.storage", "mmap");
        DatedHitIndex idx = new DatedHitIndex("c", tmp.resolve("idx"), s, null, null);
        for (int d = 0; d < 3; d++) {
            idx.putDay("trade", D0.plusDays(d), hits(d, 300));
        }
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean bad = new java.util.concurrent.atomic.AtomicBoolean();
        Thread reader = Thread.ofPlatform().start(() -> {
            Random r = new Random(2);
            while (!stop.get()) {
                int d = 2 + r.nextInt(25);
                try {
                    var res = idx.search("trade", "d" + d + "-", 1000, D0.plusDays(d));
                    if (res.isPresent() && (res.get().size() != 300 || res.get().stream().anyMatch(h -> !h.ref().id().startsWith("D" + d + "-")))) {
                        bad.set(true);
                    }
                } catch (RuntimeException e) {
                    bad.set(true);
                }
            }
        });
        for (int d = 3; d < 28; d++) {
            idx.putDay("trade", D0.plusDays(d), hits(d, 300));
        }
        stop.set(true);
        reader.join();
        assertThat(bad).isFalse();
        assertThat(idx.stats().ids()).isEqualTo(900);
    }

    @Test
    void aMappedDayStaysSubMillisecond() {
        List<EntityHit> l = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            l.add(hit((i % 2 == 0 ? "MX-" : "CLY-") + (30_000_000 + i)));
        }
        DaySegment seg = DaySegment.write(tmp.resolve("big.seg"), null, l);
        for (int i = 0; i < 3000; i++) {
            seg.search("mx-3000", 10, new ArrayList<>());
        }
        int runs = 5000;
        long t0 = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            List<EntityHit> out = new ArrayList<>();
            seg.search("mx-300" + (i % 10), 10, out);
            assertThat(out).isNotEmpty();
        }
        double ms = (System.nanoTime() - t0) / 1e6 / runs;
        System.out.printf("mapped segment prefix lookup, 100K ids (%d KB on disk): %.4f ms%n", seg.bytes() / 1024, ms);
        assertThat(ms).isLessThan(1.0);
    }
}
