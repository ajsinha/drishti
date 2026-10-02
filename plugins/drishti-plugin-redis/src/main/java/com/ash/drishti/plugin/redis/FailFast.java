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
package com.ash.drishti.plugin.redis;

import com.ash.drishti.api.UnreadableData;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * A circuit breaker driven by the connector's health: while the store is known to be down (the connection dropped, a
 * read failed, the catalogue could not be read), reads fail at once instead of each waiting for its timeout, and one
 * background check asks the store every {@code recheck} whether it answers again; the first answer closes the breaker,
 * so reads resume as soon as the store is back. Thread-safe: any number of readers call {@link #check} at once, and at
 * most one recheck runs.
 */
final class FailFast {

    /** One attempt to reach the store; returns normally when it answered (and the connector's health is UP again). */
    @FunctionalInterface
    interface Recheck {
        void run() throws Exception;
    }

    private final boolean enabled;
    private final Duration recheck;
    private final BooleanSupplier down;
    private final Recheck attempt;
    private final String name;
    private final AtomicBoolean rechecking = new AtomicBoolean();
    private volatile boolean closed;

    /**
     * @param enabled false: reads always go to the store (and wait for their timeout when it is down)
     * @param recheck how often a down store is asked again
     * @param down true while the connector's health says the store is down
     * @param attempt asks the store once
     * @param name the connector, for the recheck thread's name
     */
    FailFast(boolean enabled, Duration recheck, BooleanSupplier down, Recheck attempt, String name) {
        this.enabled = enabled;
        this.recheck = recheck;
        this.down = down;
        this.attempt = attempt;
        this.name = name;
    }

    /**
     * Before a read: passes while the store is up; fails at once while it is known to be down (and makes sure a recheck
     * runs). The message is shown to whoever asked, so it names no host or credentials (Admin → Health does).
     */
    void check() {
        if (!enabled || closed || !down.getAsBoolean()) {
            return;
        }
        startRecheck();
        throw new UnreadableData("the Redis store is not reachable; reads fail at once until it answers again (checked every " + recheck.toMillis()
                + " ms; Admin → Health says more)", null);
    }

    /** True while the breaker is open: the store is down and reads fail at once. */
    boolean open() {
        return enabled && !closed && down.getAsBoolean();
    }

    Duration recheck() {
        return recheck;
    }

    /** Called when the connector learns that the store is down, so the recheck starts without waiting for a read. */
    void startRecheck() {
        if (enabled && !closed && rechecking.compareAndSet(false, true)) {
            Thread.ofVirtual().name("redis-recheck-" + name).start(this::loop);
        }
    }

    private void loop() {
        try {
            while (!closed && down.getAsBoolean()) {
                Thread.sleep(recheck);
                try {
                    attempt.run();
                } catch (Exception e) {
                    // still down: ask again after the next interval
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            rechecking.set(false);
        }
        if (!closed && down.getAsBoolean()) {
            startRecheck();                                     // went down again while the loop was ending
        }
    }

    void close() {
        closed = true;
    }
}
