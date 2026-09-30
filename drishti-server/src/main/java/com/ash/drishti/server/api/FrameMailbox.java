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

import com.ash.drishti.engine.live.Frame;
import com.ash.drishti.engine.live.Patch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A one-slot, latest-wins mailbox between a view stream and one SSE client. If the client is slower than
 * the frames, pending frames merge (newest value per strip cell, panel and provenance), so a slow client
 * gets fewer, fuller frames and memory stays bounded.
 */
final class FrameMailbox {


    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private Frame pending;
    private long merged;

    void offer(Frame f) {
        lock.lock();
        try {
            if (pending != null) {
                merged++;
                pending = merge(pending, f);
            } else {
                pending = f;
            }
            ready.signal();
        } finally {
            lock.unlock();
        }
    }

    /** The pending frame, waiting at most {@code millis}; null on timeout. */
    Frame take(long millis) throws InterruptedException {
        lock.lock();
        try {
            long nanos = TimeUnit.MILLISECONDS.toNanos(millis);
            while (pending == null && nanos > 0) {
                nanos = ready.awaitNanos(nanos);
            }
            Frame f = pending;
            pending = null;
            return f;
        } finally {
            lock.unlock();
        }
    }

    long merged() {
        return merged;
    }

    static Frame merge(Frame a, Frame b) {
        Map<String, Patch> byTarget = new LinkedHashMap<>();
        for (Frame f : new Frame[] {a, b}) {
            for (Patch p : f.patches()) {
                String key = switch (p.op()) {
                    case "strip" -> "s" + p.index();
                    case "panel" -> "p" + p.panel().id();
                    default -> p.op();
                };
                byTarget.remove(key);
                byTarget.put(key, p);
            }
        }
        return new Frame(b.seq(), b.generation(), new ArrayList<>(byTarget.values()), b.latencyMs(), b.p99Ms());
    }
}
