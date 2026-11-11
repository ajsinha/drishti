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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Admin reload, the automatic check, and the release of disk by retired segments. */
class DatedHitIndexReloadTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 1);

    @TempDir
    Path dir;

    private static EntityHit hit(String id) {
        return new EntityHit(EntityRef.of("trade", id), id, "trade · test");
    }

    private static List<EntityHit> hits(String prefix, int n) {
        List<EntityHit> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(hit(prefix + i));
        }
        return out;
    }

    private static List<String> ids(DatedHitIndex idx, LocalDate day, String q) {
        return idx.search("trade", q, 500, day).orElseThrow().stream().map(h -> h.ref().id()).toList();
    }

    private static void waitIdle(DatedHitIndex idx) throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        while (idx.reloadProgress().running() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertThat(idx.reloadProgress().running()).isFalse();
    }

    /** The source: a version per day, and the ids of that version. */
    private static final class Source {
        final Map<LocalDate, Integer> version = new ConcurrentHashMap<>();
        final Map<LocalDate, AtomicInteger> loads = new ConcurrentHashMap<>();
        volatile CountDownLatch gate;
        volatile CountDownLatch entered = new CountDownLatch(1);

        List<EntityHit> load(String kind, LocalDate day) throws Exception {
            loads.computeIfAbsent(day, d -> new AtomicInteger()).incrementAndGet();
            CountDownLatch g = gate;
            if (g != null) {
                entered.countDown();
                g.await(10, TimeUnit.SECONDS);
            }
            return hits("V" + version.getOrDefault(day, 1) + "-", 40);
        }

        int loaded(LocalDate d) {
            AtomicInteger n = loads.get(d);
            return n == null ? 0 : n.get();
        }
    }

    private DatedHitIndex index(Source src, Map<String, String> extra, boolean withWatermark) {
        Map<String, String> s = new java.util.HashMap<>(Map.of("index.days", "3", "index.warm", "lazy", "index.reload-every", "0"));
        s.putAll(extra);
        DatedHitIndex.Watermark wm = withWatermark ? (k, d) -> "v" + src.version.getOrDefault(d, 1) : null;
        DatedHitIndex idx = new DatedHitIndex("t", dir, s, src::load, wm);
        for (int d = 0; d < 3; d++) {
            src.version.put(D0.plusDays(d), 1);
            idx.putDay("trade", D0.plusDays(d), hits("V1-", 40));
        }
        return idx;
    }

    @Test
    void reloadSwapsADayAtomicallyWhileLookupsKeepAnswering() throws Exception {
        for (String storage : List.of("heap", "mmap")) {
            Source src = new Source();
            DatedHitIndex idx = index(src, Map.of("index.storage", storage), false);
            LocalDate past = D0;
            assertThat(idx.onDisk("trade", past)).isEqualTo(storage.equals("mmap"));
            src.version.put(past, 2);
            src.gate = new CountDownLatch(1);
            src.entered = new CountDownLatch(1);
            assertThat(idx.reload("trade", past)).isEqualTo(1);
            assertThat(src.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(idx.status().stream().filter(d -> d.day().equals(past)).findFirst().orElseThrow().building()).isTrue();
            AtomicBoolean failed = new AtomicBoolean();
            AtomicBoolean stop = new AtomicBoolean();
            Thread reader = Thread.ofVirtual().start(() -> {
                while (!stop.get()) {
                    try {
                        List<String> got = ids(idx, past, "v");
                        boolean old = got.stream().allMatch(i -> i.startsWith("V1-"));
                        boolean fresh = got.stream().allMatch(i -> i.startsWith("V2-"));
                        if (got.size() != 40 || !(old || fresh)) {
                            failed.set(true);            // never empty, never a mix of the two versions
                        }
                    } catch (RuntimeException e) {
                        failed.set(true);
                    }
                }
            });
            Thread.sleep(50);
            assertThat(ids(idx, past, "v").getFirst()).startsWith("V1-");           // the old day answers while the new one builds
            src.gate.countDown();
            waitIdle(idx);
            Thread.sleep(20);
            stop.set(true);
            reader.join();
            assertThat(failed).isFalse();
            assertThat(ids(idx, past, "v")).allMatch(i -> i.startsWith("V2-"));
            assertThat(idx.reloadProgress().error()).isNull();
            assertThat(idx.status().stream().filter(d -> d.day().equals(past)).findFirst().orElseThrow().builtAt()).isNotNull();
        }
    }

    @Test
    void aSecondReloadWhileOneRunsIsRefused() throws Exception {
        Source src = new Source();
        DatedHitIndex idx = index(src, Map.of(), false);
        src.gate = new CountDownLatch(1);
        idx.reload(null, null);
        assertThatThrownBy(() -> idx.reload("trade", D0)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already running");
        src.gate.countDown();
        waitIdle(idx);
        assertThat(idx.reload("trade", D0)).isEqualTo(1);                       // free again
        waitIdle(idx);
    }

    @Test
    void reloadRejectsAnUnknownKindOrADayOutsideTheWindow() {
        Source src = new Source();
        DatedHitIndex idx = index(src, Map.of(), false);
        assertThatThrownBy(() -> idx.reload("nope", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> idx.reload("trade", D0.minusDays(30))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailedDayKeepsItsOldContentAndShowsTheError() throws Exception {
        DatedHitIndex idx = new DatedHitIndex("t", dir, Map.of("index.days", "3", "index.reload-every", "0"), (k, d) -> {
            throw new IOException("source is down");
        }, null);
        idx.putDay("trade", D0, hits("V1-", 5));
        idx.reload("trade", D0);
        waitIdle(idx);
        assertThat(idx.reloadProgress().error()).contains("source is down");
        assertThat(idx.status().getFirst().lastError()).contains("source is down");
        assertThat(ids(idx, D0, "v")).hasSize(5);
    }

    @Test
    void anAutomaticCycleRebuildsOnlyTheDayWhoseWatermarkChanged() throws Exception {
        Source src = new Source();
        DatedHitIndex idx = index(src, Map.of("index.reload-every", "10m"), true);
        idx.staggerMax(0);
        assertThat(idx.autoCheck(Instant.now(), true)).isZero();                // nothing changed: nothing rebuilt
        waitIdle(idx);
        assertThat(src.loaded(D0)).isZero();
        src.version.put(D0, 2);                                                   // the oldest day was restated, no signal sent
        assertThat(idx.autoCheck(Instant.now(), true)).isEqualTo(1);
        waitIdle(idx);
        assertThat(src.loaded(D0)).isEqualTo(1);
        assertThat(src.loaded(D0.plusDays(1))).isZero();
        assertThat(ids(idx, D0, "v")).allMatch(i -> i.startsWith("V2-"));
        assertThat(idx.autoCheck(Instant.now(), true)).isZero();                // and now it is current
        assertThat(idx.autoCheckInfo().lastChange()).isNotNull();
        assertThat(idx.autoCheckInfo().lastChangeWhat()).contains("trade " + D0);
    }

    @Test
    void theNewestDayIsLeftToItsOwnFasterRefresh() throws Exception {
        Source src = new Source();
        DatedHitIndex idx = index(src, Map.of("index.reload-every", "10m"), true);
        idx.staggerMax(0);
        src.version.put(D0.plusDays(2), 9);
        assertThat(idx.autoCheck(Instant.now(), true)).isZero();
        assertThat(src.loaded(D0.plusDays(2))).isZero();
    }

    @Test
    void aSourceWithNoWatermarkRebuildsEveryPastDayOncePerCycle() throws Exception {
        Source src = new Source();
        DatedHitIndex idx = index(src, Map.of("index.reload-every", "10m"), false);
        idx.staggerMax(0);
        assertThat(idx.autoCheck(Instant.now(), true)).isEqualTo(2);            // the two past days, not the newest
        waitIdle(idx);
        assertThat(idx.autoCheck(Instant.now(), true)).isEqualTo(2);
        waitIdle(idx);
        assertThat(src.loaded(D0)).isEqualTo(2);
        assertThat(src.loaded(D0.plusDays(1))).isEqualTo(2);
        assertThat(src.loaded(D0.plusDays(2))).isZero();
    }

    @Test
    void zeroSwitchesTheAutomaticCheckOffAndAKindCanOverrideIt() throws Exception {
        Source src = new Source();
        DatedHitIndex off = index(src, Map.of("index.reload-every", "0"), false);
        assertThat(off.autoCheck(Instant.now(), true)).isZero();
        assertThat(off.autoCheckInfo().enabled()).isFalse();
        Source src2 = new Source();
        DatedHitIndex on = index(src2, Map.of("index.reload-every", "0", "index.kinds.trade.reload-every", "5m"), false);
        on.staggerMax(0);
        assertThat(on.autoCheck(Instant.now(), true)).isEqualTo(2);
        waitIdle(on);
    }

    @Test
    void manualAndAutomaticNeverOverlap() throws Exception {
        Source src = new Source();
        DatedHitIndex idx = index(src, Map.of("index.reload-every", "10m"), false);
        idx.staggerMax(0);
        src.gate = new CountDownLatch(1);
        idx.reload("trade", D0);                                                  // a manual reload holds the one slot
        assertThat(idx.autoCheck(Instant.now(), true)).isZero();                 // the cycle is skipped, not queued behind it
        assertThat(idx.reloadProgress().trigger()).isEqualTo("manual");
        src.gate.countDown();
        waitIdle(idx);
        src.gate = new CountDownLatch(1);
        src.entered = new CountDownLatch(1);
        assertThat(idx.autoCheck(Instant.now(), true)).isEqualTo(2);              // and the other way round
        assertThat(src.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> idx.reload("trade", D0)).isInstanceOf(IllegalStateException.class);
        src.gate.countDown();
        waitIdle(idx);
    }

    @Test
    void theSchedulerRunsTheCycleBySelf() throws Exception {
        Source src = new Source();
        DatedHitIndex idx = index(src, Map.of("index.reload-every", "1s"), false);
        idx.staggerMax(0);
        idx.plan("trade", new java.util.TreeSet<>(List.of(D0, D0.plusDays(1), D0.plusDays(2))));
        long end = System.nanoTime() + 8_000_000_000L;
        while (src.loaded(D0) == 0 && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertThat(src.loaded(D0)).isPositive();
        assertThat(idx.autoCheckInfo().nextCheck()).isNotNull();
    }

    // ---- disk: a retired segment gives its blocks back at once ---------------------------------------------------------

    private static boolean linux() {
        return Files.isDirectory(Path.of("/proc/self/fd"));
    }

    private static long deletedOpenIndexFiles(Path root) throws IOException {
        long n = 0;
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            for (Path fd : fds.toList()) {
                try {
                    String target = Files.readSymbolicLink(fd).toString();
                    if (target.endsWith("(deleted)") && target.contains(root.toString())) {
                        n++;
                    }
                } catch (IOException e) {
                    // the descriptor closed meanwhile
                }
            }
        }
        return n;
    }

    private static long folderBytes(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }

    @Test
    void afterARollAndAReloadNoDeletedIndexFileStaysOpenAndTheFolderShrinks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(linux());
        Source src = new Source();
        DatedHitIndex idx = index(src, Map.of("index.storage", "mmap", "index.days", "3"), false);
        for (int i = 0; i < 3; i++) {
            idx.search("trade", "v", 5, D0.plusDays(i));                          // open the descriptors
        }
        long before = folderBytes(dir);
        assertThat(before).isPositive();
        idx.reload("trade", null);                                                 // every file is replaced
        waitIdle(idx);
        assertThat(deletedOpenIndexFiles(dir)).isZero();
        for (int d = 3; d < 12; d++) {                                             // a roll: the oldest day goes every time
            idx.putDay("trade", D0.plusDays(d), hits("R" + d + "-", 40));
        }
        assertThat(deletedOpenIndexFiles(dir)).isZero();
        try (Stream<Path> files = Files.list(dir.resolve("t"))) {
            assertThat(files.filter(f -> f.toString().endsWith(".seg")).count()).isLessThanOrEqualTo(2);     // only the closed days of the window
        }
        assertThat(folderBytes(dir)).isLessThan(before * 3);
    }

    @Test
    void lookupsDuringRetirementNeverFail() throws Exception {
        Source src = new Source();
        DatedHitIndex idx = index(src, Map.of("index.storage", "mmap"), false);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger bad = new AtomicInteger();
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            readers.add(Thread.ofVirtual().start(() -> {
                while (!stop.get()) {
                    try {
                        if (idx.search("trade", "v", 20, D0).map(List::size).orElse(0) != 20) {
                            bad.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        bad.incrementAndGet();
                    }
                }
            }));
        }
        for (int i = 0; i < 25; i++) {
            idx.reload("trade", D0);
            waitIdle(idx);
        }
        stop.set(true);
        for (Thread t : readers) {
            t.join();
        }
        assertThat(bad).hasValue(0);
    }

    @Test
    void aDiskLookupStaysWellUnderAMillisecond() {
        List<EntityHit> big = hits("T", 200_000);
        DatedHitIndex idx = new DatedHitIndex("t", dir, Map.of("index.days", "2", "index.storage", "mmap", "index.reload-every", "0"), null, null);
        idx.putDay("trade", D0, big);
        idx.putDay("trade", D0.plusDays(1), List.of(hit("X")));
        assertThat(idx.onDisk("trade", D0)).isTrue();
        for (int i = 0; i < 2000; i++) {
            idx.search("trade", "t1" + (i % 100), 10, D0);
        }
        long t0 = System.nanoTime();
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            idx.search("trade", "t" + (100 + i * 7919 % 900), 10, D0);
        }
        double ms = (System.nanoTime() - t0) / 1e6 / n;
        System.out.printf("disk-day prefix lookup over 200,000 ids: %.4f ms%n", ms);
        assertThat(ms).isLessThan(1.0);
    }
}
