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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SingleFlightTest {

    @Test
    void manyCallersForOneKeyLoadItOnce() throws Exception {
        SingleFlight<String> flight = new SingleFlight<>();
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                results.add(vt.submit(() -> {
                    go.await();
                    return flight.get("k", cache::get, k -> {
                        loads.incrementAndGet();
                        sleep(50);                           // a blocking load
                        return "v";
                    }, cache::put);
                }));
            }
            go.countDown();
            for (Future<String> f : results) {
                assertThat(f.get()).isEqualTo("v");
            }
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void aNullIsNotStored() {
        SingleFlight<String> flight = new SingleFlight<>();
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        assertThat(flight.get("a", cache::get, k -> null, cache::put)).isNull();
        assertThat(cache).isEmpty();
        assertThat(flight.get("a", cache::get, k -> "x", cache::put)).isEqualTo("x");
        assertThat(cache).containsEntry("a", "x");
    }

    @Test
    void aFailedLoadLeavesTheKeyLoadableAgain() {
        SingleFlight<String> flight = new SingleFlight<>();
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        try {
            flight.get("a", cache::get, k -> {
                throw new IllegalStateException("boom");
            }, cache::put);
        } catch (IllegalStateException expected) {
            // the next caller tries again
        }
        assertThat(flight.get("a", cache::get, k -> "ok", cache::put)).isEqualTo("ok");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
