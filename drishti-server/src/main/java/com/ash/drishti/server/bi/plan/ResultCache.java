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
package com.ash.drishti.server.bi.plan;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The L1 result cache (docs/architecture/BI_DESIGN.md section 6.1): answers keyed by <b>plan hash</b> (the question, the model at its fingerprint, the
 * resolved time), <b>security signature</b> (readers with identical effective security share an entry; different security never does) and the <b>generation</b>
 * of the model's data (any load, refresh or live batch moves it, so a stale answer is never served). An as-of answer has no time to live (only idle eviction);
 * an answer for the current state lives {@code cache-ttl} at most. Disposable: dropping it changes no answer, only latency. Thread-safe.
 */
public final class ResultCache<V> {

    /** The key: plan, signature, generation; and whether the answer is for a fixed date. */
    public record Key(String plan, String signature, long generation, boolean immutable) {
        String text() {
            return plan + "|" + signature + "|g" + generation;
        }
    }

    private record Entry<V>(V value, boolean immutable) {}

    private final Cache<String, Entry<V>> cache;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public ResultCache(PlanProperties props) {
        long ttl = props.cacheTtl().toNanos();
        long idle = props.cacheIdle().toNanos();
        this.cache = props.cacheEntries() == 0 ? null : Caffeine.newBuilder().maximumSize(props.cacheEntries()).expireAfter(new Expiry<String, Entry<V>>() {
            @Override
            public long expireAfterCreate(String key, Entry<V> e, long now) {
                return e.immutable() ? idle : Math.min(ttl, idle);
            }

            @Override
            public long expireAfterUpdate(String key, Entry<V> e, long now, long current) {
                return expireAfterCreate(key, e, now);
            }

            @Override
            public long expireAfterRead(String key, Entry<V> e, long now, long current) {
                return e.immutable() ? idle : current;                          // reading an as-of answer keeps it; a current one still ends at its ttl
            }
        }).build();
    }

    public boolean enabled() {
        return cache != null;
    }

    public V get(Key k) {
        if (cache == null) {
            return null;
        }
        Entry<V> e = cache.getIfPresent(k.text());
        if (e == null) {
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return e.value();
    }

    public void put(Key k, V v) {
        if (cache != null) {
            cache.put(k.text(), new Entry<>(v, k.immutable()));
        }
    }

    public long size() {
        return cache == null ? 0 : cache.estimatedSize();
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    /** For tests: the entries are counted per key, so one question asked by two readers of one signature is one entry. */
    public void clear() {
        if (cache != null) {
            cache.invalidateAll();
        }
    }
}
