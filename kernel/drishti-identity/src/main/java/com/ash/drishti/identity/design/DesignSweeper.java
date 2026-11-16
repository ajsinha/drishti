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
package com.ash.drishti.identity.design;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deletes expired Designs now and then ({@code drishti.builder.designs.sweep-interval}). One daemon thread; a failed sweep is logged and retried next time. */
public final class DesignSweeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DesignSweeper.class);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "design-sweeper");
        t.setDaemon(true);
        return t;
    });

    public DesignSweeper(DesignService designs, Duration every) {
        long period = Math.max(1_000L, every.toMillis());
        timer.scheduleWithFixedDelay(() -> {
            try {
                designs.sweep();
            } catch (RuntimeException e) {
                LOG.warn("design sweep failed: {}", e.toString());
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
