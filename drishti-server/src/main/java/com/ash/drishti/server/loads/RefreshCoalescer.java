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
package com.ash.drishti.server.loads;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data-load signals refresh a connector at most once per window: the first signal refreshes it at once; signals while that refresh
 * runs, or within {@code window} of its start, are merged into one more refresh at the end of the window. So a connector has at most
 * one refresh running and one waiting, however many signals arrive (QA3A-01: sixty signals of a backfill each dropped the caches and
 * started a full read of every trade document beside the others, and the server ran out of memory).
 */
public final class RefreshCoalescer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshCoalescer.class);

    /** What became of one request: refreshed now, or merged into the one waiting (which runs {@code inMillis} from now). */
    public record Outcome(boolean now, long inMillis) {}

    private static final class State {
        boolean running;
        boolean waiting;
        long lastStart = Long.MIN_VALUE;
        long runs;
    }

    private final Duration window;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "drishti-load-refresh");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong runs = new AtomicLong();
    private final AtomicInteger concurrent = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    public RefreshCoalescer(Duration window) {
        this.window = window == null ? Duration.ZERO : window;
    }

    /**
     * Refreshes {@code name} with {@code refresh} now (on this thread, so the caller's next step sees it), or merges the request into the
     * refresh waiting for it. A zero window refreshes every time, as before.
     */
    public Outcome request(String name, Runnable refresh) {
        long now = System.nanoTime();
        State s = states.computeIfAbsent(name, k -> new State());
        long due;
        synchronized (s) {
            boolean recent = s.lastStart != Long.MIN_VALUE && now - s.lastStart < window.toNanos();
            if (window.isZero() || !s.running && !recent && !s.waiting) {
                s.running = true;
                s.lastStart = now;
                due = -1;
            } else {
                due = s.running ? window.toNanos() : Math.max(0, s.lastStart + window.toNanos() - now);
                if (!s.waiting) {
                    s.waiting = true;
                    timer.schedule(() -> later(name, s, refresh), due, TimeUnit.NANOSECONDS);
                }
            }
        }
        if (due >= 0) {
            return new Outcome(false, TimeUnit.NANOSECONDS.toMillis(due));
        }
        run(name, s, refresh);
        return new Outcome(true, 0);
    }

    /** The waiting refresh, on a thread of its own (the timer only times). */
    private void later(String name, State s, Runnable refresh) {
        Thread.ofVirtual().name("drishti-load-refresh-" + name).start(() -> {
            try {
                trailing(name, s, refresh);
            } catch (RuntimeException e) {
                // said in the log by run()
            }
        });
    }

    /** The waiting refresh: runs once the running one has ended (it waits for it, never beside it). */
    private void trailing(String name, State s, Runnable refresh) {
        synchronized (s) {
            if (s.running) {
                timer.schedule(() -> later(name, s, refresh), Math.max(1, window.toMillis() / 4), TimeUnit.MILLISECONDS);
                return;
            }
            s.waiting = false;
            s.running = true;
            s.lastStart = System.nanoTime();
        }
        run(name, s, refresh);
    }

    private void run(String name, State s, Runnable refresh) {
        runs.incrementAndGet();
        synchronized (s) {
            s.runs++;
        }
        peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
        try {
            refresh.run();
        } catch (RuntimeException e) {
            LOG.warn("data load: the refresh of {} failed: {}", name, e.getMessage());
            throw e;
        } finally {
            concurrent.decrementAndGet();
            synchronized (s) {
                s.running = false;
            }
        }
    }

    /** Refreshes run since the start. */
    public long runs() {
        return runs.get();
    }

    /** Refreshes of one connector run since the start. */
    public long runs(String name) {
        State s = states.get(name);
        if (s == null) {
            return 0;
        }
        synchronized (s) {
            return s.runs;
        }
    }

    /** The most refreshes that ran at once (of any connector) since the start. */
    public int peak() {
        return peak.get();
    }

    /** Refreshes waiting now (merged signals), over every connector. */
    public int waiting() {
        return (int) states.values().stream().filter(s -> {
            synchronized (s) {
                return s.waiting;
            }
        }).count();
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
