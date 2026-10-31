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
package com.ash.drishti.compute.reactive;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * A reactive connector's results in memory (DATA_ARCHITECTURE.md › Reactive connectors, section 11): by full key (the question, the viewer's signature, the
 * pin, the engine), least recently used first out, each kept at most {@code ttl}; and, by <em>base</em> key (the question and the
 * signature without the pin), the newest result, which is what a panel shows dimmed while it recomputes and what orders results by
 * watermark. Never holds a failed, partial or private (sandbox) result.
 */
public final class ResultCache {

    /**
     * One result.
     *
     * @param key the full key
     * @param base the base key
     * @param document the result's document (fields, RESULTS, TABLES, STAMP)
     * @param watermark what it includes
     * @param at when it was computed
     * @param stale true once a trigger said its inputs changed (it is still shown, in amber, until the new one lands)
     */
    public record Entry(String key, String base, Map<String, Object> document, Watermark watermark, Instant at, boolean stale) {
        Entry markStale() {
            return new Entry(key, base, document, watermark, at, true);
        }
    }

    private final int max;
    private final Duration ttl;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, Entry> byKey = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<String, Entry> newest = new HashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public ResultCache(int max, Duration ttl, Clock clock) {
        this.max = max;
        this.ttl = ttl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** The result for a full key, if one is held and young enough. */
    public Optional<Entry> get(String key) {
        lock.lock();
        try {
            Entry e = byKey.get(key);
            if (e != null && e.at().plus(ttl).isBefore(clock.instant())) {
                byKey.remove(key);
                e = null;
            }
            if (e == null || e.stale()) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            hits.incrementAndGet();
            return Optional.of(e);
        } finally {
            lock.unlock();
        }
    }

    /** The newest result for a base key (any pin), stale or not: what a panel shows while the next one is computed. */
    public Optional<Entry> newest(String base) {
        lock.lock();
        try {
            return Optional.ofNullable(newest.get(base));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Keeps a result unless it is older than the newest of its base key (a late result never replaces a newer one); false when it was
     * dropped for that. With {@code max} 0 nothing is kept by full key, but the newest is still recorded for ordering.
     */
    public boolean put(Entry e) {
        lock.lock();
        try {
            Entry cur = newest.get(e.base());
            if (cur != null && e.watermark().olderThan(cur.watermark())) {
                dropped.incrementAndGet();
                return false;
            }
            newest.put(e.base(), e);
            if (max > 0) {
                byKey.put(e.key(), e);
                while (byKey.size() > max) {
                    Entry old = byKey.remove(byKey.keySet().iterator().next());
                    if (old != null && newest.get(old.base()) == old) {
                        newest.remove(old.base());
                    }
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Marks every result whose base key matches as stale; returns the base keys marked. */
    public List<String> markStale(Predicate<String> base) {
        lock.lock();
        try {
            List<String> out = new ArrayList<>();
            byKey.replaceAll((k, e) -> base.test(e.base()) ? e.markStale() : e);
            newest.replaceAll((b, e) -> {
                if (base.test(b)) {
                    out.add(b);
                    return e.markStale();
                }
                return e;
            });
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** The base keys held, for triggers that affect every result. */
    public List<String> bases() {
        lock.lock();
        try {
            return new ArrayList<>(newest.keySet());
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            byKey.clear();
            newest.clear();
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> stats() {
        lock.lock();
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("results", byKey.size());
            m.put("scopes", newest.size());
            m.put("hits", hits.get());
            m.put("misses", misses.get());
            m.put("lateDropped", dropped.get());
            m.put("max", max);
            m.put("ttl", ttl.toString());
            return m;
        } finally {
            lock.unlock();
        }
    }

    public long dropped() {
        return dropped.get();
    }
}
