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
package com.ash.drishti.plugin.news;

import java.util.LinkedHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** A small least-recently-used map with a fixed capacity; safe for many threads (one lock, held only for the map operation). */
final class BoundedCache<V> {

    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, V> map = new LinkedHashMap<>(16, 0.75f, true);
    private final int capacity;

    BoundedCache(int capacity) {
        this.capacity = capacity;
    }

    V get(String key) {
        lock.lock();
        try {
            return map.get(key);
        } finally {
            lock.unlock();
        }
    }

    void put(String key, V value) {
        lock.lock();
        try {
            map.put(key, value);
            while (map.size() > capacity) {
                map.remove(map.keySet().iterator().next());          // the least recently used
            }
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    void clear() {
        lock.lock();
        try {
            map.clear();
        } finally {
            lock.unlock();
        }
    }
}
