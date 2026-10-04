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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Loads each missing value once at a time per key, for a loader that blocks (a file read, a network call, a query).
 *
 * <p>Do not use {@code ConcurrentHashMap.computeIfAbsent} or Caffeine's {@code get(key, loader)} for that: they run
 * the loader inside a {@code synchronized} bin, and on Java 21 a virtual thread that blocks inside {@code synchronized}
 * pins its carrier thread (fixed in Java 24). Here the wait is on a {@link ReentrantLock}, which parks a virtual thread
 * properly, and the value is stored in the caller's own cache before the lock is released.
 *
 * @param <K> the key
 */
public final class SingleFlight<K> {

    private final ConcurrentHashMap<K, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * The value for {@code key}: {@code peek}ed from the cache, else {@code load}ed by one caller while the others wait
     * and then find it with {@code peek}. A loaded value that is not null is handed to {@code store}.
     */
    public <V> V get(K key, Function<K, V> peek, Function<K, V> load, BiConsumer<K, V> store) {
        V v = peek.apply(key);
        if (v != null) {
            return v;
        }
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            v = peek.apply(key);
            if (v == null) {
                v = load.apply(key);
                if (v != null) {
                    store.accept(key, v);
                }
            }
            return v;
        } finally {
            lock.unlock();
            locks.remove(key, lock);
        }
    }
}
