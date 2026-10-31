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
package com.ash.drishti.server.edit;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lanes (WRITE_BACK.md › The design and its reasons, sections 7 and 15): random concurrent submissions over many keys on few lanes (so many keys share a lane) are
 * applied, per key, in the order they were appended, none lost or applied twice, and no lane ever has two workers at once; a change
 * acknowledged before a crash is applied once after the restart; a key always maps to the same lane.
 */
class LanesTest {

    @TempDir
    Path dir;
    HikariDataSource ds;

    @AfterEach
    void close() {
        if (ds != null) {
            ds.close();
        }
    }

    static EditSettings settings(int lanes) {
        return EditSettings.defaults().withEnabled(true).withLanes(lanes);
    }

    static Set<Integer> all(int lanes) {
        return IntStream.range(0, lanes).boxed().collect(Collectors.toSet());
    }

    @Test
    void perKeyOrderHoldsAcrossConcurrentSubmissionsAndNoLaneHasTwoWorkers() throws Exception {
        ds = EditTestDb.sqlite(dir);
        EditLog log = new EditLog(ds);
        int lanes = 4;
        Map<String, List<Long>> applied = new ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> active = new ConcurrentHashMap<>();
        AtomicInteger twoAtOnce = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();
        Lanes.Applier applier = (row, node, epoch) -> {
            AtomicInteger a = active.computeIfAbsent(row.lane(), k -> new AtomicInteger());
            if (a.incrementAndGet() > 1) {
                twoAtOnce.incrementAndGet();
            }
            try {
                applied.computeIfAbsent(row.key(), k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(row.seq());
                log.finish(row, node, epoch, EditLog.APPLIED, null, null, null);
                total.incrementAndGet();
            } finally {
                a.decrementAndGet();
            }
        };
        Map<String, List<Long>> appended = new ConcurrentHashMap<>();
        int threads = 8;
        int each = 250;
        try (Lanes l = new Lanes(log, settings(lanes), "n1", applier, () -> all(lanes))) {
            l.start();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < each; i++) {
                        String entity = "E" + ThreadLocalRandom.current().nextInt(200);
                        Change c = EditLogTest.change(entity, i);
                        // per key, the order of submission is the order its appends were acknowledged in: its seq
                        long seq = l.append(c);
                        appended.computeIfAbsent(c.key(), k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(seq);
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
            long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
            while (total.get() < threads * each && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }
        assertThat(total.get()).as("every change applied once").isEqualTo(threads * each);
        assertThat(twoAtOnce.get()).as("a lane with two workers at once").isZero();
        for (Map.Entry<String, List<Long>> e : appended.entrySet()) {
            List<Long> inOrder = new ArrayList<>(e.getValue());
            java.util.Collections.sort(inOrder);
            assertThat(applied.get(e.getKey())).as("the apply order of " + e.getKey()).containsExactlyElementsOf(inOrder);
        }
    }

    @Test
    void aChangeAcknowledgedBeforeACrashIsAppliedOnceAfterTheRestart() throws Exception {
        ds = EditTestDb.sqlite(dir);
        EditLog log = new EditLog(ds);
        log.ensureLanes(4);
        Change c = EditLogTest.change("MX-50000001", 7);
        int lane = Lanes.laneOf(c.key(), 4);
        long epoch = log.claim(lane, "n1", 10_000).orElseThrow();
        long seq = log.append(c, lane, "n1", epoch, 100);               // acknowledged: committed; then the node dies before applying
        log.applying(lane, seq);                                         // ...even while it was applying
        AtomicInteger applies = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        try (Lanes l = new Lanes(log, settings(4), "n1", (row, node, e) -> {
            applies.incrementAndGet();
            log.finish(row, node, e, EditLog.APPLIED, null, null, null);
            done.countDown();
        }, () -> all(4))) {
            l.start();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);
        }
        assertThat(applies.get()).isEqualTo(1);
        assertThat(log.get(c.id()).orElseThrow().state()).isEqualTo(EditLog.APPLIED);
        try (Lanes again = new Lanes(log, settings(4), "n1", (row, node, e) -> applies.incrementAndGet(), () -> all(4))) {
            again.start();
            Thread.sleep(300);
        }
        assertThat(applies.get()).as("not applied again after another restart").isEqualTo(1);
    }

    @Test
    void aKeyAlwaysMapsToTheSameLane() {
        assertThat(Lanes.murmur3("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(0x248bfa47);
        assertThat(Lanes.murmur3(new byte[0])).isZero();
        assertThat(Lanes.laneOf("trade:MX-50000001", 64)).isEqualTo(Lanes.laneOf("trade:MX-50000001", 64)).isBetween(0, 63);
    }

    @Test
    void theSettingsAreOffAsShipped() {
        EditSettings s = EditSettings.load(Path.of("..", "config", "edit", "edit.yaml"), v -> v.replace("${DRISHTI_EDIT_ENABLED:false}", "false"));
        assertThat(s.enabled()).isFalse();
        assertThat(s.lanes()).isEqualTo(64);
        assertThat(s.log()).isEqualTo("auto");
        assertThat(s.policy().needed("trade", "source", 0)).isEqualTo(1);
        assertThat(s.policy().needed("trade", "overlay", 0)).isZero();
        assertThat(s.policy().needed("trade", "overlay", 2)).isEqualTo(2);
        assertThat(s.keep().toDays()).isEqualTo(7 * 365);
    }
}
