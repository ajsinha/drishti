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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The ids of events already processed, for a window (DATA_ARCHITECTURE.md › Reactive connectors, section 8.4): an event seen again (a redelivered Kafka
 * message, a change resent after a fail-over, the same schedule tick on a restarted timer) is ignored. Bounded in time and in count.
 */
public final class SeenEvents {

    private final Duration window;
    private final int max;
    private final Clock clock;
    private final Map<String, Instant> seen = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private long ignored;

    public SeenEvents(Duration window, int max, Clock clock) {
        this.window = window;
        this.max = Math.max(16, max);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** True the first time an id is offered within the window; false (and counted) for a repeat. */
    public boolean first(String id) {
        lock.lock();
        try {
            Instant now = clock.instant();
            Iterator<Map.Entry<String, Instant>> it = seen.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Instant> e = it.next();
                if (seen.size() > max || e.getValue().plus(window).isBefore(now)) {
                    it.remove();
                } else {
                    break;
                }
            }
            if (seen.containsKey(id)) {
                ignored++;
                return false;
            }
            seen.put(id, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public long ignored() {
        lock.lock();
        try {
            return ignored;
        } finally {
            lock.unlock();
        }
    }
}
