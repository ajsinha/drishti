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
package com.ash.drishti.bicompute.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The partial-state cache (ARCHITECTURE.md section 26.6): states, not figures, keyed by (provider, data version, source, date, filters, cell
 * set, function), weighed in bytes. A view that shares cells with an earlier one (a measure added, a node opened, the same view again) reads
 * them from here; a new data version is a new key, so nothing stale is read. The host may give its own (the server's sits in the one memory
 * budget); {@link #lru} is the module's own, a byte-bounded least-recently-used map.
 */
public interface StateCache {

    /** The value kept under the key, or null. */
    Object get(String key);

    /** Keeps a value of {@code bytes}. */
    void put(String key, Object value, long bytes);

    /** The bytes held. */
    long bytes();

    /** The entries held. */
    long entries();

    void clear();

    /** No cache: every view computes every cell. */
    static StateCache none() {
        return new StateCache() {
            @Override
            public Object get(String key) {
                return null;
            }

            @Override
            public void put(String key, Object value, long bytes) {}

            @Override
            public long bytes() {
                return 0;
            }

            @Override
            public long entries() {
                return 0;
            }

            @Override
            public void clear() {}
        };
    }

    /** A least-recently-used cache of at most {@code maxBytes}. */
    static StateCache lru(long maxBytes) {
        return new StateCache() {
            private final LinkedHashMap<String, Object[]> map = new LinkedHashMap<>(64, 0.75f, true);
            private long held;

            @Override
            public synchronized Object get(String key) {
                Object[] e = map.get(key);
                return e == null ? null : e[0];
            }

            @Override
            public synchronized void put(String key, Object value, long bytes) {
                long b = bytes + 64L + 2L * key.length();
                if (b > maxBytes) {
                    return;
                }
                Object[] old = map.put(key, new Object[] {value, b});
                if (old != null) {
                    held -= (long) old[1];
                }
                held += b;
                Iterator<Map.Entry<String, Object[]>> it = map.entrySet().iterator();
                while (held > maxBytes && it.hasNext()) {
                    held -= (long) it.next().getValue()[1];
                    it.remove();
                }
            }

            @Override
            public synchronized long bytes() {
                return held;
            }

            @Override
            public synchronized long entries() {
                return map.size();
            }

            @Override
            public synchronized void clear() {
                map.clear();
                held = 0;
            }
        };
    }
}
