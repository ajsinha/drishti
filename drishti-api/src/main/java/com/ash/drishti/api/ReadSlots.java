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

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A connector's bounded slots for reads that are expensive on their own (a document read from a file): at most {@code permits} run at
 * once, and at most {@code maxWaiting} wait for one. A read that would wait beyond that is refused at once ({@link Busy}: the server is
 * busy, try again) instead of queueing without end, and a waiting read whose caller has stopped waiting ({@link ReadDeadline}: its budget
 * ran out, or it was cancelled) leaves the queue and never takes a slot ({@link Abandoned}). Without a deadline (a background warm-up)
 * a read waits as long as it takes, still within {@code maxWaiting}.
 *
 * <p>Waiting is in short slices, so an abandoned read is noticed within {@link #SLICE_MILLIS} ms without interrupting its thread.
 */
public final class ReadSlots {

    /** How often a waiting read looks whether its caller still waits. */
    public static final long SLICE_MILLIS = 50;

    /** Refused at once: {@code maxWaiting} reads already wait for a slot. */
    public static final class Busy extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Busy(String message) {
            super(message);
        }
    }

    /** Not read: the caller stopped waiting (its budget ran out, or it was cancelled) before a slot was free. */
    public static final class Abandoned extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Abandoned(String message) {
            super(message);
        }
    }

    private final String name;
    private final int permits;
    private final int maxWaiting;
    private final Semaphore slots;
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong abandoned = new AtomicLong();

    /**
     * @param name what the slots are for, as an error says it ({@code document reads of delta-trades})
     * @param permits reads at once (at least 1)
     * @param maxWaiting reads that may wait for a slot (at least 1)
     */
    public ReadSlots(String name, int permits, int maxWaiting) {
        if (permits < 1 || maxWaiting < 1) {
            throw new IllegalArgumentException(name + ": permits and the waiting limit must be at least 1 (" + permits + ", " + maxWaiting + ")");
        }
        this.name = name;
        this.permits = permits;
        this.maxWaiting = maxWaiting;
        this.slots = new Semaphore(permits);
    }

    /** Takes a slot, or throws {@link Busy} or {@link Abandoned}; a slot taken is given back with {@link #release()}. */
    public void acquire() {
        if (slots.tryAcquire()) {
            return;
        }
        ReadDeadline d = ReadDeadline.current().orElse(null);
        if (d != null && d.abandoned()) {
            abandoned.incrementAndGet();
            throw new Abandoned(name + ": the caller stopped waiting before a slot was free");
        }
        if (waiting.incrementAndGet() > maxWaiting) {
            waiting.decrementAndGet();
            refused.incrementAndGet();
            throw new Busy(name + ": " + maxWaiting + " reads already wait for one of its " + permits
                    + " slots; the server is busy, try again in a moment");
        }
        try {
            while (true) {
                long wait = TimeUnit.MILLISECONDS.toNanos(SLICE_MILLIS);
                if (d != null) {
                    wait = Math.max(0, Math.min(wait, d.remainingNanos()));
                }
                if (slots.tryAcquire(wait, TimeUnit.NANOSECONDS)) {
                    if (d != null && d.abandoned()) {
                        slots.release();                       // never read for a caller that has gone
                        abandoned.incrementAndGet();
                        throw new Abandoned(name + ": the caller stopped waiting before a slot was free");
                    }
                    return;
                }
                if (d != null && d.abandoned()) {
                    abandoned.incrementAndGet();
                    throw new Abandoned(name + ": the caller stopped waiting before a slot was free");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandoned.incrementAndGet();
            throw new Abandoned(name + ": interrupted while waiting for a slot");
        } finally {
            waiting.decrementAndGet();
        }
    }

    public void release() {
        slots.release();
    }

    /** Reads waiting for a slot now. */
    public int waiting() {
        return waiting.get();
    }

    /** Slots free now. */
    public int available() {
        return slots.availablePermits();
    }

    public int permits() {
        return permits;
    }

    public int maxWaiting() {
        return maxWaiting;
    }

    /** Reads refused because too many waited, since the start. */
    public long refused() {
        return refused.get();
    }

    /** Reads that left the queue because their caller had gone, since the start. */
    public long abandonedCount() {
        return abandoned.get();
    }
}
