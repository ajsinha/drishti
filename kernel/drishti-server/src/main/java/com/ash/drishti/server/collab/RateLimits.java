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
package com.ash.drishti.server.collab;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Per-user sliding-window rate limits for collaboration (shares, comments, directory searches). Each (bucket, user) keeps the
 * times of its recent hits; a hit over the limit is refused with {@link RateLimitedException} saying when the oldest hit leaves the
 * window. Memory is bounded by the limit per active user; idle entries are dropped as they empty.
 */
public final class RateLimits {

    private static final class Window {
        final ReentrantLock lock = new ReentrantLock();
        final ArrayDeque<Long> hits = new ArrayDeque<>();
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier nanos;

    public RateLimits() {
        this(System::nanoTime);
    }

    /** With a clock, for tests. */
    public RateLimits(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /**
     * Counts one hit of {@code user} in {@code bucket}; throws when the user already made {@code limit} hits within {@code window}.
     *
     * @param what what is limited, for the message ("shares")
     */
    public void hit(String bucket, String user, int limit, Duration window, String what) {
        Window w = windows.computeIfAbsent(bucket + '\u0000' + user, k -> new Window());
        long now = nanos.getAsLong();
        long span = window.toNanos();
        w.lock.lock();
        try {
            while (!w.hits.isEmpty() && now - w.hits.peekFirst() >= span) {
                w.hits.pollFirst();
            }
            if (w.hits.size() >= limit) {
                long waitNanos = span - (now - w.hits.peekFirst());
                throw new RateLimitedException(what, Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L));
            }
            w.hits.addLast(now);
        } finally {
            w.lock.unlock();
        }
    }
}
