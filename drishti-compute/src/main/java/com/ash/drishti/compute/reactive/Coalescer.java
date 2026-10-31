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
package com.ash.drishti.compute.reactive;

import com.ash.drishti.api.compute.ComputeEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns bursts of events for one key into few computations (DATA_ARCHITECTURE.md › Reactive connectors, section 10.1): after an event it waits until none
 * has arrived for {@code quiet}, but never longer than {@code maxWait} after the first of the burst; at most one computation per key runs
 * at a time, and events that arrive during it cause exactly one more afterwards. Keys, not events, are queued, so a burst for one key is
 * one entry; at most {@code maxKeys} keys wait (a new key beyond that is refused, and its results are only marked stale).
 *
 * @param <K> the key
 */
public final class Coalescer<K> {

    /** Runs one computation for a key over the events coalesced into it; may block (it runs on its own virtual thread). */
    @FunctionalInterface
    public interface Runner<K> {
        void run(K key, List<ComputeEvent> events);
    }

    private static final Logger LOG = LoggerFactory.getLogger(Coalescer.class);

    private final class State {
        final List<ComputeEvent> events = new ArrayList<>();
        Instant first;
        ScheduledFuture<?> timer;
        boolean running;
    }

    private final Duration quiet;
    private final Duration maxWait;
    private final int maxKeys;
    private final ScheduledExecutorService scheduler;
    private final Runner<K> runner;
    private final Clock clock;
    private final Map<K, State> states = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong eventsIn = new AtomicLong();
    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    public Coalescer(Duration quiet, Duration maxWait, int maxKeys, ScheduledExecutorService scheduler, Runner<K> runner, Clock clock) {
        this.quiet = quiet;
        this.maxWait = maxWait;
        this.maxKeys = maxKeys;
        this.scheduler = scheduler;
        this.runner = runner;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Adds an event for a key; false when the queue of keys is full and the key is not already waiting. */
    public boolean offer(K key, ComputeEvent event) {
        lock.lock();
        try {
            State st = states.get(key);
            if (st == null) {
                if (states.size() >= maxKeys) {
                    refused.incrementAndGet();
                    return false;
                }
                st = new State();
                states.put(key, st);
            }
            eventsIn.incrementAndGet();
            st.events.add(event);
            if (st.first == null) {
                st.first = clock.instant();
            }
            if (!st.running) {
                schedule(key, st);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void schedule(K key, State st) {
        if (st.timer != null) {
            st.timer.cancel(false);
        }
        long untilMax = Duration.between(clock.instant(), st.first.plus(maxWait)).toMillis();
        long delay = Math.max(0, Math.min(quiet.toMillis(), untilMax));
        st.timer = scheduler.schedule(() -> fire(key), delay, TimeUnit.MILLISECONDS);
    }

    private void fire(K key) {
        List<ComputeEvent> batch;
        lock.lock();
        try {
            State st = states.get(key);
            if (st == null || st.running || st.events.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(st.events);
            st.events.clear();
            st.first = null;
            st.timer = null;
            st.running = true;
        } finally {
            lock.unlock();
        }
        runs.incrementAndGet();
        Thread.ofVirtual().name("reactive-recompute").start(() -> {
            try {
                runner.run(key, batch);
            } catch (RuntimeException e) {
                LOG.warn("reactive: a recompute for {} failed: {}", key, e.getMessage());
            } finally {
                done(key);
            }
        });
    }

    private void done(K key) {
        lock.lock();
        try {
            State st = states.get(key);
            if (st == null) {
                return;
            }
            st.running = false;
            if (st.events.isEmpty()) {
                states.remove(key);
            } else {
                st.first = clock.instant();                   // what arrived meanwhile: exactly one more computation
                schedule(key, st);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Events offered, computations run, keys refused because the queue was full. */
    public Map<String, Long> stats() {
        return Map.of("eventsIn", eventsIn.get(), "computations", runs.get(), "refused", refused.get());
    }

    public int waiting() {
        lock.lock();
        try {
            return states.size();
        } finally {
            lock.unlock();
        }
    }
}
