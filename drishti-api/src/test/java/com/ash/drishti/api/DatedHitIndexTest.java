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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DatedHitIndexTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 1);

    private static EntityHit hit(String id) {
        return new EntityHit(EntityRef.of("trade", id), id, "trade · test");
    }

    private static List<EntityHit> hits(Collection<String> ids) {
        return ids.stream().map(DatedHitIndexTest::hit).toList();
    }

    private static Map<String, String> days(int n) {
        return Map.of("index.days", String.valueOf(n));
    }

    private static List<String> ids(java.util.Optional<List<EntityHit>> r) {
        return r.orElseThrow().stream().map(h -> h.ref().id()).toList();
    }

    @Test
    void matchesABruteForcePerDaySetOverRandomHistories() {
        Random rnd = new Random(7);
        for (int round = 0; round < 20; round++) {
            DatedHitIndex idx = new DatedHitIndex(days(5), null);
            Map<LocalDate, Set<String>> truth = new HashMap<>();
            for (int d = 0; d < 5; d++) {
                Set<String> set = new TreeSet<>();
                for (int i = 0; i < 300; i++) {
                    set.add("T" + rnd.nextInt(600));
                }
                truth.put(D0.plusDays(d), set);
                idx.putDay("trade", D0.plusDays(d), hits(set));
            }
            for (int q = 0; q < 30; q++) {
                LocalDate day = D0.plusDays(rnd.nextInt(5));
                String prefix = "t" + rnd.nextInt(60);
                List<String> expected = truth.get(day).stream().filter(s -> s.toLowerCase().startsWith(prefix)).sorted().limit(25).toList();
                List<String> got = ids(idx.search("trade", prefix, 25, day)).stream().limit(expected.size()).toList();
                assertThat(got).isEqualTo(expected);
                assertThat(ids(idx.search("trade", "", 1000, day))).containsExactlyInAnyOrderElementsOf(truth.get(day));
            }
        }
    }

    @Test
    void anUnindexedDayAnswersEmptySoTheConnectorFallsBack() {
        DatedHitIndex idx = new DatedHitIndex(days(3), null);
        idx.putDay("trade", D0, List.of(hit("A")));
        assertThat(idx.search("trade", "a", 5, D0.minusDays(1))).isEmpty();
        assertThat(idx.search("trade", "a", 5, null)).isEmpty();
        assertThat(ids(idx.search("trade", "a", 5, D0))).containsExactly("A");
    }

    @Test
    void rollingForwardDropsTheOldestDayAndPurgesIdsNoDayHoldsSoMemoryIsFlat() {
        DatedHitIndex idx = new DatedHitIndex(days(3), null);
        long first = 0;
        for (int d = 0; d < 40; d++) {
            idx.putDay("trade", D0.plusDays(d), dayIds(d, 1000));       // every day brings 1000 ids nobody else has
            if (d == 2) {
                first = idx.stats().memoryBytes();
            }
        }
        assertThat(idx.stats().days()).isEqualTo(3);
        assertThat(idx.stats().ids()).isEqualTo(3000);
        assertThat(idx.stats().memoryBytes()).isLessThan(first + first / 20);
        assertThat(idx.has("trade", D0.plusDays(36))).isFalse();
        assertThat(idx.search("trade", "d0-", 5, D0)).isEmpty();
        assertThat(ids(idx.search("trade", "d39-99", 5, D0.plusDays(39)))).startsWith("D39-99");
    }

    @Test
    void aRestatedPastDayRebuildsThatDayOnly() throws Exception {
        Map<LocalDate, List<String>> source = new HashMap<>();
        for (int d = 0; d < 3; d++) {
            source.put(D0.plusDays(d), List.of("A", "B", "C"));
        }
        DatedHitIndex idx = new DatedHitIndex(days(3), (kind, day) -> hits(source.get(day)));
        source.forEach((day, l) -> idx.putDay("trade", day, hits(l)));
        source.put(D0.plusDays(1), List.of("A", "Z"));
        idx.restated("trade", D0.plusDays(1));
        assertThat(idx.awaitWarm(5000)).isTrue();
        assertThat(ids(idx.search("trade", "", 10, D0.plusDays(1)))).containsExactly("A", "Z");
        assertThat(ids(idx.search("trade", "", 10, D0))).containsExactly("A", "B", "C");
        assertThat(ids(idx.search("trade", "", 10, D0.plusDays(2)))).containsExactly("A", "B", "C");
    }

    @Test
    void maxMemoryDropsTheOldestDaysEarlyAndNeverTheNewest() {
        Map<String, String> s = Map.of("index.days", "5", "index.max-memory", "300000");
        DatedHitIndex idx = new DatedHitIndex(s, null);
        for (int d = 0; d < 5; d++) {
            idx.putDay("trade", D0.plusDays(d), dayIds(d, 1000));
        }
        assertThat(idx.stats().days()).isLessThan(5).isGreaterThanOrEqualTo(1);
        assertThat(idx.has("trade", D0.plusDays(4))).isTrue();
        assertThat(idx.has("trade", D0)).isFalse();
        assertThat(idx.stats().memoryBytes()).isLessThanOrEqualTo(300_000L);
    }

    @Test
    void changingDaysAppliesLive() {
        DatedHitIndex idx = new DatedHitIndex(days(5), null);
        for (int d = 0; d < 5; d++) {
            idx.putDay("trade", D0.plusDays(d), List.of(hit("X" + d)));
        }
        assertThat(idx.stats().days()).isEqualTo(5);
        idx.reconfigure(days(2));
        assertThat(idx.stats().days()).isEqualTo(2);
        assertThat(idx.has("trade", D0.plusDays(3))).isTrue();
        assertThat(idx.has("trade", D0.plusDays(2))).isFalse();
    }

    @Test
    void warmUpBuildsPastDaysNewestFirstInTheBackgroundAndLazyBuildsOnDemand() throws Exception {
        TreeSet<LocalDate> available = new TreeSet<>();
        List<LocalDate> order = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int d = 0; d < 6; d++) {
            available.add(D0.plusDays(d));
        }
        DatedHitIndex bg = new DatedHitIndex(days(4), (k, day) -> {
            order.add(day);
            return List.of(hit("ID-" + day.getDayOfMonth()));
        });
        bg.putDay("trade", D0.plusDays(5), List.of(hit("ID-6")));
        bg.plan("trade", available);
        assertThat(bg.awaitWarm(5000)).isTrue();
        assertThat(order).containsExactly(D0.plusDays(4), D0.plusDays(3), D0.plusDays(2));
        assertThat(bg.stats().days()).isEqualTo(4);
        assertThat(bg.stats().warm()).isEqualTo("warm");

        DatedHitIndex lazy = new DatedHitIndex(Map.of("index.days", "4", "index.warm", "lazy"), (k, day) -> List.of(hit("L" + day.getDayOfMonth())));
        lazy.putDay("trade", D0.plusDays(5), List.of(hit("L6")));
        lazy.plan("trade", available);
        assertThat(lazy.has("trade", D0.plusDays(3))).isFalse();
        assertThat(lazy.search("trade", "l", 5, D0.plusDays(3))).isEmpty();      // falls back, and starts the load
        assertThat(lazy.awaitWarm(5000)).isTrue();
        assertThat(ids(lazy.search("trade", "l", 5, D0.plusDays(3)))).containsExactly("L4");
    }

    @Test
    void anyRecentDayAddsIdsSeenOnlyEarlierMarkedLastSeen() {
        DatedHitIndex idx = new DatedHitIndex(Map.of("index.days", "3", "index.any-recent-day", "true"), null);
        idx.putDay("trade", D0, List.of(hit("OLD-1"), hit("BOTH")));
        idx.putDay("trade", D0.plusDays(1), List.of(hit("OLD-2"), hit("BOTH")));
        idx.putDay("trade", D0.plusDays(2), List.of(hit("NEW"), hit("BOTH")));
        List<EntityHit> today = idx.search("trade", "", 10, D0.plusDays(2)).orElseThrow();
        assertThat(today).extracting(h -> h.ref().id()).containsExactly("BOTH", "NEW", "OLD-1", "OLD-2");
        assertThat(today.get(2).subtitle()).endsWith("last seen " + D0);
        assertThat(today.get(3).subtitle()).endsWith("last seen " + D0.plusDays(1));
        assertThat(idx.search("trade", "", 10, D0.plusDays(1)).orElseThrow()).extracting(h -> h.ref().id()).containsExactly("BOTH", "OLD-2");
    }

    @Test
    void aKindOverridesTheConnectorDefault() {
        Map<String, String> s = Map.of("index.days", "5", "index.kinds.trade.days", "2");
        assertThat(DayIndexSettings.of(s, "trade").days()).isEqualTo(2);
        assertThat(DayIndexSettings.of(s, "book").days()).isEqualTo(5);
        assertThat(DayIndexSettings.of(Map.of("index.max-memory", "2GB"), null).maxMemoryBytes()).isEqualTo(2L << 30);
        assertThat(DayIndexSettings.of(Map.of(), null).multiDay()).isFalse();
    }

    @Test
    void lookupsDuringARollNeverSeeAMixedOrBrokenDay() throws Exception {
        DatedHitIndex idx = new DatedHitIndex(days(3), null);
        for (int d = 0; d < 3; d++) {
            idx.putDay("trade", D0.plusDays(d), dayIds(d, 500));
        }
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean bad = new AtomicBoolean();
        Thread reader = Thread.ofPlatform().start(() -> {
            Random r = new Random(1);
            while (!stop.get()) {
                int d = 2 + r.nextInt(40);
                var res = idx.search("trade", "d" + d + "-", 2000, D0.plusDays(d));
                if (res.isPresent() && (res.get().size() != 500 || res.get().stream().anyMatch(h -> !h.ref().id().startsWith("D" + d + "-")))) {
                    bad.set(true);
                }
            }
        });
        for (int d = 3; d < 42; d++) {
            idx.putDay("trade", D0.plusDays(d), dayIds(d, 500));
        }
        stop.set(true);
        reader.join();
        assertThat(bad).isFalse();
        assertThat(idx.stats().ids()).isEqualTo(1500);
    }

    private static List<EntityHit> dayIds(int d, int n) {
        List<EntityHit> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(hit("D" + d + "-" + i));
        }
        return l;
    }

    @Test
    void prefixLookupStaysSubMillisecondAtAHundredThousandIdsADayForFiveDays() {
        DatedHitIndex idx = new DatedHitIndex(days(5), null);
        for (int d = 0; d < 5; d++) {
            List<EntityHit> l = new ArrayList<>(100_000);
            for (int i = 0; i < 100_000; i++) {
                l.add(hit((i % 2 == 0 ? "MX-" : "CLY-") + (30_000_000 + i + d * 1000)));
            }
            idx.putDay("trade", D0.plusDays(d), l);
        }
        LocalDate day = D0.plusDays(2);
        for (int i = 0; i < 2000; i++) {
            idx.search("trade", "mx-3000", 10, day);                      // warm the JIT
        }
        int runs = 5000;
        long t0 = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            assertThat(idx.search("trade", "mx-300" + (i % 10), 10, day).orElseThrow()).isNotEmpty();
        }
        double ms = (System.nanoTime() - t0) / 1e6 / runs;
        System.out.printf("dated prefix lookup, 100K ids/day x 5 days: %.4f ms%n", ms);
        assertThat(ms).isLessThan(1.0);
    }
}
