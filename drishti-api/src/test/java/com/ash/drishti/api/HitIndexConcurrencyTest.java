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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** A rebuild never shows searchers an empty or half-built index, and concurrent additions are all kept. */
class HitIndexConcurrencyTest {

    private static List<EntityHit> hits(int n) {
        List<EntityHit> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new EntityHit(EntityRef.of("trade", "T-" + i), "T-" + i, "trade"));
        }
        return out;
    }

    @Test
    void searchesDuringRebuildsAlwaysSeeACompleteIndex() throws Exception {
        HitIndex index = new HitIndex();
        List<EntityHit> all = hits(500);
        index.replaceAll(all);
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        Future<?> rebuilder = pool.submit(() -> {
            while (!stop.get()) {
                index.replaceAll(all);
            }
        });
        List<Future<Integer>> searchers = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            searchers.add(pool.submit(() -> {
                int smallest = Integer.MAX_VALUE;
                for (int i = 0; i < 2000; i++) {
                    smallest = Math.min(smallest, index.search("trade", "", 1000).size());
                }
                return smallest;
            }));
        }
        for (Future<Integer> f : searchers) {
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(500);
        }
        stop.set(true);
        rebuilder.get(10, TimeUnit.SECONDS);
        pool.shutdown();
    }

    @Test
    void concurrentAdditionsAreAllKept() throws Exception {
        HitIndex index = new HitIndex();
        List<EntityHit> all = hits(800);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (EntityHit h : all) {
                pool.submit(() -> index.add(h));
            }
        }
        assertThat(index.size()).isEqualTo(800);
    }
}
