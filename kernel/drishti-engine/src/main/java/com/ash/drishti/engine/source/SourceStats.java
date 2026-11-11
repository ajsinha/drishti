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
package com.ash.drishti.engine.source;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * What each connector has been doing, for the health page: reads (found, not held, failed), the last error and when,
 * the last success, and latency percentiles over the last 512 reads. Lock-free: counters are adders, latencies a ring
 * of atomics, so recording costs a few nanoseconds on the read path. Thread-safe.
 */
public final class SourceStats {

    private static final int RING = 512;

    /** One connector's counters. */
    static final class Stats {
        final LongAdder hits = new LongAdder();
        final LongAdder misses = new LongAdder();
        final LongAdder errors = new LongAdder();
        final AtomicLongArray latencyMicros = new AtomicLongArray(RING);
        final AtomicInteger next = new AtomicInteger();
        volatile String lastError;
        volatile Instant lastErrorAt;
        volatile Instant lastOkAt;

        void latency(long nanos) {
            latencyMicros.set(Math.floorMod(next.getAndIncrement(), RING), Math.max(1, nanos / 1_000));
        }
    }

    private final Map<String, Stats> bySource = new ConcurrentHashMap<>();

    private Stats of(String source) {
        return bySource.computeIfAbsent(source, s -> new Stats());
    }

    public void found(String source, long nanos) {
        Stats s = of(source);
        s.hits.increment();
        s.latency(nanos);
        s.lastOkAt = Instant.now();
    }

    public void notHeld(String source, long nanos) {
        Stats s = of(source);
        s.misses.increment();
        s.latency(nanos);
        s.lastOkAt = Instant.now();
    }

    public void failed(String source, long nanos, Throwable e) {
        Stats s = of(source);
        s.errors.increment();
        s.latency(nanos);
        s.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        s.lastErrorAt = Instant.now();
    }

    /** A snapshot for one connector: counts, last error and success, p50 and p99 in milliseconds. */
    public Map<String, Object> snapshot(String source) {
        Stats s = bySource.get(source);
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            m.put("reads", 0L);
            return m;
        }
        long hits = s.hits.sum();
        long misses = s.misses.sum();
        long errors = s.errors.sum();
        m.put("reads", hits + misses + errors);
        m.put("found", hits);
        m.put("notHeld", misses);
        m.put("errors", errors);
        m.put("lastError", s.lastError);
        m.put("lastErrorAt", s.lastErrorAt);
        m.put("lastOkAt", s.lastOkAt);
        long[] l = new long[Math.min(RING, (int) Math.min(Integer.MAX_VALUE, hits + misses + errors))];
        for (int i = 0; i < l.length; i++) {
            l[i] = s.latencyMicros.get(i);
        }
        Arrays.sort(l);
        m.put("p50Ms", l.length == 0 ? null : l[(int) (l.length * 0.5)] / 1000.0);
        m.put("p99Ms", l.length == 0 ? null : l[Math.min(l.length - 1, (int) (l.length * 0.99))] / 1000.0);
        return m;
    }
}
