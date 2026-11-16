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

import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.InboxStore;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes inbox rows older than {@code drishti.collab.inbox.keep-days} (the per-user cap {@code inbox.keep} still applies). Runs at
 * start and then every {@code drishti.collab.retention.interval}. Inbox rows are pointers, not records: removing one loses no
 * comment, share or hold-relevant content.
 */
public final class InboxPurge implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InboxPurge.class);

    private final InboxStore store;
    private final CollabProperties props;
    private final Clock clock;
    private ScheduledExecutorService loop;

    public InboxPurge(InboxStore store, CollabProperties props, Clock clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    /** One pass now; returns how many rows were removed. */
    public int run() {
        int removed = store.purgeBefore(clock.instant().minus(Duration.ofDays(props.inbox().keepDays())));
        if (removed > 0) {
            LOG.info("inbox: removed {} rows older than {} days (drishti.collab.inbox.keep-days)", removed, props.inbox().keepDays());
        }
        return removed;
    }

    /** Starts the schedule (a no-op when collaboration is off). */
    public synchronized void start() {
        if (loop != null || !props.enabled()) {
            return;
        }
        loop = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("drishti-inbox-purge").unstarted(r));
        long ms = Math.max(1000, props.retention().interval().toMillis());
        loop.scheduleWithFixedDelay(() -> {
            try {
                run();
            } catch (RuntimeException e) {
                LOG.warn("inbox purge failed: {}", e.toString());
            }
        }, 60_000, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (loop != null) {
            loop.shutdownNow();
            loop = null;
        }
    }
}
