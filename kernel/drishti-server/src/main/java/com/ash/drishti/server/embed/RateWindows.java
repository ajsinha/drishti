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
package com.ash.drishti.server.embed;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key call counts in one-minute windows: {@link #take} says how many seconds to wait when the key is over its limit, or 0.
 * Lock-free per key, bounded: finished windows are dropped when the map grows.
 */
final class RateWindows {

    private static final long WINDOW_MS = 60_000;
    private static final int MAX_KEYS = 20_000;

    private record Window(long start, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    RateWindows(Clock clock) {
        this.clock = clock;
    }

    /** Counts one call for {@code key}; seconds until the window ends when it is over {@code limit}, else 0. */
    long take(String key, int limit) {
        long now = clock.millis();
        long[] wait = {0};
        windows.compute(key, (k, w) -> {
            Window cur = w == null || now - w.start() >= WINDOW_MS ? new Window(now, 0) : w;
            if (cur.count() >= limit) {
                wait[0] = Math.max(1, (cur.start() + WINDOW_MS - now + 999) / 1000);
                return cur;
            }
            return new Window(cur.start(), cur.count() + 1);
        });
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() >= WINDOW_MS);
        }
        return wait[0];
    }
}
