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
package com.ash.drishti.registry;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where the access model is kept (ACCESS_MODEL.md section 4): read, change, watch. YAML files now ({@link YamlAccessStore}); the same model
 * in the one database later (a {@code DatabaseAccessStore}, 2.9.11.i) behind this interface, answering the same.
 */
public interface AccessStore {

    /** The model as stored now. */
    AccessModel read();

    /**
     * Sets the administrator's switch of a module, where the store keeps it.
     *
     * @throws IllegalArgumentException for the core or a module not known here
     */
    void setEnabled(String module, boolean on);

    /** A value that changes whenever the stored model changes (cheap to ask: file times, a version row). */
    long stamp();

    /** Where it keeps the model, in words. */
    String describe();

    /** Calls {@code onChange} whenever {@link #stamp()} changes, checked every {@code every} on a daemon thread. Closing the handle stops it. */
    default AutoCloseable watch(Duration every, Runnable onChange) {
        AtomicLong last = new AtomicLong(stamp());
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drishti-access-watch");
            t.setDaemon(true);
            return t;
        });
        long ms = Math.max(100, every.toMillis());
        ex.scheduleWithFixedDelay(() -> {
            try {
                long now = stamp();
                if (now != last.getAndSet(now)) {
                    onChange.run();
                }
            } catch (RuntimeException e) {
                System.getLogger(AccessStore.class.getName()).log(System.Logger.Level.WARNING, "the access model's watch: " + e);
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
        return ex::shutdownNow;
    }
}
