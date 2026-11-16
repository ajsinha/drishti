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
package com.ash.drishti.server.explain;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Per-user sliding windows: questions a minute and questions a day. Thread-safe; memory is bounded by a day's questions. */
public final class AskRateLimiter {

    private final Clock clock;
    private final int perMinute;
    private final int perDay;
    private final ConcurrentHashMap<String, Deque<Long>> asked = new ConcurrentHashMap<>();

    public AskRateLimiter(Clock clock, int perMinute, int perDay) {
        this.clock = clock;
        this.perMinute = perMinute;
        this.perDay = perDay;
    }

    /**
     * Counts a question for the user when it is within both limits.
     *
     * @return 0 when allowed, else the seconds to wait before asking again
     */
    public long tryAcquire(String user) {
        long now = clock.millis();
        Deque<Long> q = asked.computeIfAbsent(user, u -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= 86_400_000L) {
                q.pollFirst();
            }
            if (q.size() >= perDay) {
                return Math.max(1, (q.peekFirst() + 86_400_000L - now + 999) / 1000);
            }
            long inMinute = q.stream().filter(t -> now - t < 60_000L).count();
            if (inMinute >= perMinute) {
                long oldest = q.stream().filter(t -> now - t < 60_000L).findFirst().orElse(now);
                return Math.max(1, (oldest + 60_000L - now + 999) / 1000);
            }
            q.addLast(now);
            return 0;
        }
    }
}
