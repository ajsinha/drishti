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
package com.ash.drishti.server.api;

import com.ash.drishti.engine.live.LiveProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * The server-wide cap on open live streams ({@code drishti.live.max-streams}), shared by view and monitor streams.
 * A slot is taken atomically before any work, so concurrent requests can never overshoot the cap, and each slot
 * is released exactly once however its stream ends.
 */
@Component
public class LiveStreamSlots {

    private final AtomicInteger open = new AtomicInteger();
    private final int max;

    public LiveStreamSlots(LiveProperties props) {
        this.max = props.maxStreams();
    }

    /** A held slot; {@link #release()} is idempotent. */
    public final class Slot {
        private final AtomicBoolean held = new AtomicBoolean(true);

        public void release() {
            if (held.compareAndSet(true, false)) {
                open.decrementAndGet();
            }
        }
    }

    /** Takes a slot, or returns null when the server is at its cap. */
    public Slot tryAcquire() {
        if (open.incrementAndGet() > max) {
            open.decrementAndGet();
            return null;
        }
        return new Slot();
    }

    public int open() {
        return open.get();
    }

    AtomicInteger counter() {
        return open;
    }
}
