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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Per connector, a fragment pool and a circuit breaker (docs/architecture/BI_DESIGN.md section 6.6): at most {@code max-fragments} source fragments of one
 * connector run at once (a layer build, a direct query); a connector that fails {@code breaker-failures} times in a row opens its breaker, and for
 * {@code breaker-open} every fragment for it is refused at once ({@code DRS-9026}, the connector named) instead of waiting on a dead source; then one fragment
 * may try (half open): its success closes the breaker, its failure opens it again. Thread-safe.
 */
public final class FragmentPools {

    /** A breaker's state. */
    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    /** One connector's pool and breaker. */
    static final class Pool {
        final String connector;
        final Semaphore permits;
        final ReentrantLock lock = new ReentrantLock();
        State state = State.CLOSED;
        int failures;
        Instant openedAt;
        boolean trial;
        final AtomicLong runs = new AtomicLong();
        final AtomicLong refused = new AtomicLong();
        final AtomicLong nanos = new AtomicLong();
        volatile String lastError;

        Pool(String connector, int permits) {
            this.connector = connector;
            this.permits = new Semaphore(permits, true);
        }
    }

    private final ExecProperties props;
    private final Clock clock;
    private volatile java.util.function.BiConsumer<String, Double> onFragment = (c, s) -> { };

    /** Who hears each fragment's time (the metrics: {@code drishti_bi_fragment_seconds{connector}}). */
    public void onFragment(java.util.function.BiConsumer<String, Double> c) {
        this.onFragment = c == null ? (x, s) -> { } : c;
    }
    private final Map<String, Pool> pools = new ConcurrentHashMap<>();

    public FragmentPools(ExecProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    private Pool pool(String connector) {
        return pools.computeIfAbsent(connector, c -> new Pool(c, props.fragmentsOf(c)));
    }

    /** Runs one source fragment of {@code connector} within its pool and breaker. */
    public <T> T run(String connector, Supplier<T> fragment) {
        Pool p = pool(connector);
        enter(p);
        boolean got = false;
        try {
            got = p.permits.tryAcquire(props.queue().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!got) {
            p.refused.incrementAndGet();
            leaveTrial(p);
            throw new DrishtiException(ErrorCode.QUERY_BUSY, "connector '" + connector + "' is running " + props.fragmentsOf(connector)
                    + " fragments already (drishti.bi.exec.max-fragments): try again");
        }
        long t0 = System.nanoTime();
        try {
            T out = fragment.get();
            success(p);
            return out;
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.QUERY_SOURCE || e.errorCode() == ErrorCode.QUERY_TIMEOUT) {
                failure(p, e.getMessage());
            } else {
                success(p);                                                     // a refusal of the question, not of the connector
            }
            throw e;
        } catch (RuntimeException e) {
            failure(p, String.valueOf(e.getMessage()));
            throw e;
        } finally {
            p.runs.incrementAndGet();
            long took = System.nanoTime() - t0;
            p.nanos.addAndGet(took);
            onFragment.accept(connector, took / 1e9);
            p.permits.release();
        }
    }

    private void enter(Pool p) {
        p.lock.lock();
        try {
            if (p.state == State.OPEN) {
                Duration open = Duration.between(p.openedAt, clock.instant());
                if (open.compareTo(props.breakerOpen()) < 0) {
                    p.refused.incrementAndGet();
                    throw new DrishtiException(ErrorCode.QUERY_SOURCE, "connector '" + p.connector + "' is failing (" + p.lastError + "): its circuit breaker is open for "
                            + Math.max(1, props.breakerOpen().minus(open).toSeconds()) + " s more (drishti.bi.exec.breaker-open)");
                }
                p.state = State.HALF_OPEN;
                p.trial = false;
            }
            if (p.state == State.HALF_OPEN) {
                if (p.trial) {
                    p.refused.incrementAndGet();
                    throw new DrishtiException(ErrorCode.QUERY_SOURCE, "connector '" + p.connector + "' is being tried again after failing: retry in a moment");
                }
                p.trial = true;
            }
        } finally {
            p.lock.unlock();
        }
    }

    private void leaveTrial(Pool p) {
        p.lock.lock();
        try {
            p.trial = false;
        } finally {
            p.lock.unlock();
        }
    }

    private void success(Pool p) {
        p.lock.lock();
        try {
            p.failures = 0;
            p.state = State.CLOSED;
            p.trial = false;
        } finally {
            p.lock.unlock();
        }
    }

    private void failure(Pool p, String error) {
        p.lock.lock();
        try {
            p.lastError = error;
            p.failures++;
            if (p.state == State.HALF_OPEN || p.failures >= props.breakerFailures()) {
                p.state = State.OPEN;
                p.openedAt = clock.instant();
            }
            p.trial = false;
        } finally {
            p.lock.unlock();
        }
    }

    /** A connector's breaker state (CLOSED for one never used). */
    public State state(String connector) {
        Pool p = pools.get(connector);
        if (p == null) {
            return State.CLOSED;
        }
        p.lock.lock();
        try {
            return p.state;
        } finally {
            p.lock.unlock();
        }
    }

    /** Per connector: the breaker, fragments run and refused, in use now, time spent (Admin, metrics). */
    public List<Map<String, Object>> stats() {
        return pools.values().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("connector", p.connector);
            m.put("breaker", state(p.connector).name().toLowerCase(java.util.Locale.ROOT));
            m.put("maxFragments", props.fragmentsOf(p.connector));
            m.put("inUse", props.fragmentsOf(p.connector) - p.permits.availablePermits());
            m.put("runs", p.runs.get());
            m.put("refused", p.refused.get());
            m.put("seconds", p.nanos.get() / 1e9);
            m.put("lastError", p.lastError);
            return m;
        }).toList();
    }

    /** The seconds each connector's fragments took, for the metrics. */
    Map<String, Pool> pools() {
        return pools;
    }
}
