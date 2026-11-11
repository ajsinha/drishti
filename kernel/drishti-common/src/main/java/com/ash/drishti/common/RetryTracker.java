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
package com.ash.drishti.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;
import java.util.random.RandomGenerator;

/**
 * The retry state of one thing that failed (a pack, a connector, an AI provider, an MCP server): how many times it has
 * failed in a row, when to try next, and the last error. Thread-safe (a lock, never {@code synchronized}); it schedules
 * nothing itself, the owner asks {@link #due} on its own timer.
 */
public final class RetryTracker {

    /** A snapshot for display: {@code nextAt} is null when no retry is planned (retrying is off). */
    public record Status(int attempt, Instant nextAt, String lastError, boolean permanent, Instant since) {
        /** Whole seconds until the next retry (rounded up), never negative; -1 when none is planned. */
        public long nextInSeconds(Instant now) {
            if (nextAt == null) {
                return -1;
            }
            long ms = Duration.between(now, nextAt).toMillis();
            return ms <= 0 ? 0 : (ms + 999) / 1000;
        }
    }

    private final RetryPolicy policy;
    private final Clock clock;
    private final DoubleSupplier random;
    private final ReentrantLock lock = new ReentrantLock();
    private int failures;
    private int sameKind;
    private boolean lastHint;
    private boolean permanent;
    private Instant nextAt;
    private Instant since;
    private String lastError = "";

    public RetryTracker(RetryPolicy policy, Clock clock, DoubleSupplier random) {
        this.policy = policy;
        this.clock = clock;
        this.random = random;
    }

    public RetryTracker(RetryPolicy policy) {
        this(policy, Clock.systemUTC(), () -> RandomGenerator.getDefault().nextDouble());
    }

    /**
     * Records a failure and plans the next retry.
     *
     * @param permanentHint true when the error is one waiting will not fix; repeated {@code permanent-after} times in a row it
     *     makes the failure permanent (retried only every {@code max})
     */
    public Status failed(String error, boolean permanentHint) {
        lock.lock();
        try {
            Instant now = clock.instant();
            if (failures == 0) {
                since = now;
            }
            failures++;
            sameKind = permanentHint && lastHint ? sameKind + 1 : permanentHint ? 1 : 0;
            lastHint = permanentHint;
            permanent = permanentHint && sameKind >= policy.permanentAfter();
            lastError = error == null ? "" : error;
            nextAt = policy.enabled() ? now.plus(policy.delay(failures, permanent, random.getAsDouble())) : null;
            return status();
        } finally {
            lock.unlock();
        }
    }

    /** True when the thing is failing and its next retry time has come. */
    public boolean due() {
        lock.lock();
        try {
            return failures > 0 && nextAt != null && !clock.instant().isBefore(nextAt);
        } finally {
            lock.unlock();
        }
    }

    /** Makes the next retry due now ("Retry now", or a change that may have fixed it); the failure count is kept. */
    public void retryNow() {
        lock.lock();
        try {
            if (failures > 0) {
                nextAt = clock.instant();
                permanent = false;
                sameKind = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    /** @return the number of failures that preceded the recovery (0 when it had not failed) */
    public int succeeded() {
        lock.lock();
        try {
            int n = failures;
            failures = 0;
            sameKind = 0;
            lastHint = false;
            permanent = false;
            nextAt = null;
            lastError = "";
            since = null;
            return n;
        } finally {
            lock.unlock();
        }
    }

    public boolean failing() {
        lock.lock();
        try {
            return failures > 0;
        } finally {
            lock.unlock();
        }
    }

    public Status status() {
        lock.lock();
        try {
            return new Status(failures, nextAt, lastError, permanent, since);
        } finally {
            lock.unlock();
        }
    }
}
