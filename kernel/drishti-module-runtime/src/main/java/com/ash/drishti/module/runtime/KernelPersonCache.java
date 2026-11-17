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
package com.ash.drishti.module.runtime;

import com.ash.drishti.kernel.api.module.PersonCache;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The kernel's {@link PersonCache}, made by {@link com.ash.drishti.kernel.api.services.Kernel#cache} (the server's and the conformance harness's): at most {@code size}
 * answers, each for {@code ttlMillis}, the least recently used going first; one computation per key at a time (two panels asking for the
 * same reads wait for one answer). Every key holds the person's {@link com.ash.drishti.kernel.api.services.Person#key()}: nothing is shared between people.
 *
 * @param <V> the answer
 */
public final class KernelPersonCache<V> implements PersonCache<V> {

    private record Kept<V>(V value, long at) {
    }

    private final int size;
    private final long ttlMillis;
    private final Clock clock;
    private final LinkedHashMap<String, Kept<V>> kept;
    private final Map<String, CompletableFuture<V>> flight = new ConcurrentHashMap<>();

    /** A cache of at most {@code size} answers, each kept {@code ttlMillis}, on the system clock. */
    public KernelPersonCache(int size, long ttlMillis) {
        this(size, ttlMillis, Clock.systemUTC());
    }

    /** As above, on {@code clock} (tests). */
    public KernelPersonCache(int size, long ttlMillis, Clock clock) {
        this.size = Math.max(1, size);
        this.ttlMillis = Math.max(0, ttlMillis);
        this.clock = clock;
        this.kept = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** The answer kept for {@code key} while it is fresh, else null. */
    @Override
    public synchronized V get(String key) {
        Kept<V> k = kept.get(key);
        if (k == null) {
            return null;
        }
        if (clock.millis() - k.at() > ttlMillis) {
            kept.remove(key);
            return null;
        }
        return k.value();
    }

    /** Keeps an answer (null is not kept) and gives it back. */
    @Override
    public synchronized V put(String key, V value) {
        if (value != null) {
            kept.put(key, new Kept<>(value, clock.millis()));
            while (kept.size() > size) {
                kept.remove(kept.keySet().iterator().next());
            }
        }
        return value;
    }

    /**
     * The fresh answer for {@code key}, or {@code make}'s, computed once however many ask at the same time and kept. A failure is not
     * kept: it reaches every waiter and the next call tries again.
     */
    @Override
    public V once(String key, Supplier<V> make) {
        V hit = get(key);
        if (hit != null) {
            return hit;
        }
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = flight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException r ? r : e;
            }
        }
        try {
            V v = put(key, make.get());
            mine.complete(v);
            return v;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            flight.remove(key, mine);
        }
    }

    /** Drops everything kept (tests). */
    @Override
    public synchronized void clear() {
        kept.clear();
    }
}
