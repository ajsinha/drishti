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

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * How long the caller of a read still waits for it, and whether it has stopped waiting. The engine runs every entity read on a
 * thread of its own and gives up on it after the read's budget; the thread is not interrupted (an interrupt closes the shared file
 * channels a connector reads through), so a connector that waits for a slot of its own ({@link ReadSlots}) asks this instead: a read
 * whose caller has gone leaves the queue and never takes a slot (QA3A-01: hundreds of thousands of abandoned reads stayed queued for a
 * document-read slot and filled the heap).
 *
 * <p>Set by the engine around a read ({@link #within}); a thread without one (a background warm-up) has none, and waits as before.
 */
public final class ReadDeadline {

    private static final ThreadLocal<ReadDeadline> CURRENT = new ThreadLocal<>();

    private final long deadlineNanos;
    private final BooleanSupplier gone;

    private ReadDeadline(long deadlineNanos, BooleanSupplier gone) {
        this.deadlineNanos = deadlineNanos;
        this.gone = gone == null ? () -> false : gone;
    }

    /**
     * Runs {@code body} with a deadline on this thread.
     *
     * @param deadlineNanos the {@link System#nanoTime()} after which the caller no longer waits
     * @param gone true once the caller has stopped waiting earlier (its future completed, it was cancelled)
     */
    public static <T> T within(long deadlineNanos, BooleanSupplier gone, Supplier<T> body) {
        ReadDeadline before = CURRENT.get();
        CURRENT.set(new ReadDeadline(deadlineNanos, gone));
        try {
            return body.get();
        } finally {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        }
    }

    /** The deadline of the read running on this thread, if the engine set one. */
    public static Optional<ReadDeadline> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Nanoseconds left; zero or less once the deadline has passed. */
    public long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }

    /** True when the caller no longer waits: the deadline has passed, or it stopped waiting earlier. */
    public boolean abandoned() {
        return remainingNanos() <= 0 || gone.getAsBoolean();
    }
}
