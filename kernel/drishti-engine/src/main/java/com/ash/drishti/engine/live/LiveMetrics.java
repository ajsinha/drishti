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
package com.ash.drishti.engine.live;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * Tick-to-send latency, as shown in the top bar ("Live, p99 38 ms"). Recording is lock-free
 * (HdrHistogram {@link Recorder}); percentiles cover a rolling window made of the current and previous
 * interval.
 */
public final class LiveMetrics {

    private final Recorder recorder = new Recorder(3);
    private final long windowNanos;
    private volatile Histogram previous = new Histogram(3);
    private Histogram current = new Histogram(3);
    private long rolledAt = System.nanoTime();
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public LiveMetrics(Duration window) {
        this.windowNanos = window.toNanos() / 2;
    }

    public void record(double millis) {
        recorder.recordValue(Math.max(0, Math.round(millis * 1000)));
        frames.incrementAndGet();
    }

    public void dropped() {
        dropped.incrementAndGet();
    }

    /** Latency percentile over the rolling window, in milliseconds. */
    public synchronized double percentile(double p) {
        current.add(recorder.getIntervalHistogram());
        long now = System.nanoTime();
        if (now - rolledAt > windowNanos) {
            previous = current;
            current = new Histogram(3);
            rolledAt = now;
        }
        Histogram all = previous.copy();
        all.add(current);
        return all.getTotalCount() == 0 ? 0 : all.getValueAtPercentile(p) / 1000.0;
    }

    public long frames() {
        return frames.get();
    }

    public long droppedFrames() {
        return dropped.get();
    }
}
