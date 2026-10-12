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
package com.ash.drishti.plugin.mongodb;

import com.ash.drishti.api.pool.PoolSettings;
import com.ash.drishti.api.pool.PoolStats;
import com.mongodb.MongoClientSettings;
import com.mongodb.event.ConnectionCheckOutFailedEvent;
import com.mongodb.event.ConnectionCheckOutStartedEvent;
import com.mongodb.event.ConnectionCheckedInEvent;
import com.mongodb.event.ConnectionCheckedOutEvent;
import com.mongodb.event.ConnectionClosedEvent;
import com.mongodb.event.ConnectionCreatedEvent;
import com.mongodb.event.ConnectionPoolListener;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Mongo driver's own connection pool, sized and watched like every database pool: {@code pool-size} is the driver's
 * {@code maxPoolSize} (default 20), {@code pool-min-idle} its {@code minPoolSize}, {@code pool-idle-timeout} and
 * {@code pool-max-lifetime} its connection idle and life times, {@code pool-connection-timeout} how long an operation waits for a
 * connection, and {@code statement-timeout} the longest an operation waits for the server's answer. The driver checks a connection
 * out for one operation (or while a cursor is open) and in again; this listener counts the checkouts for Admin (open, in use,
 * idle, waiting, longest borrow) and logs one that stays out past {@code pool-leak-threshold} with the stack of the thread that
 * took it.
 */
final class MongoPool implements ConnectionPoolListener {

    private static final System.Logger LOG = System.getLogger(MongoPool.class.getName());

    private record Out(long nanos, Throwable borrower, String thread, boolean[] warned) {}

    private final String name;
    private final PoolSettings settings;
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicLong leaks = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final Map<Long, Throwable> asking = new ConcurrentHashMap<>();       // thread id -> where it asked for a connection
    private final Map<Object, Out> out = new ConcurrentHashMap<>();             // connection id -> its checkout

    MongoPool(String name, PoolSettings settings) {
        this.name = name;
        this.settings = settings;
    }

    /** Puts the pool's size, times and this listener on the client settings. */
    void apply(MongoClientSettings.Builder b) {
        b.applyToConnectionPoolSettings(p -> p.maxSize(settings.size()).minSize(settings.minIdle())
                .maxConnectionIdleTime(settings.idleTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .maxConnectionLifeTime(settings.maxLifetime().toMillis(), TimeUnit.MILLISECONDS)
                .maxWaitTime(settings.connectionTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .addConnectionPoolListener(this));
        if (!settings.statementTimeout().isZero()) {
            b.applyToSocketSettings(s -> s.readTimeout((int) Math.min(Integer.MAX_VALUE, settings.statementTimeout().toMillis()), TimeUnit.MILLISECONDS));
        }
    }

    /** Logs each checkout that has lasted past the leak threshold, once; the connector calls it on a schedule. */
    void sweep() {
        if (settings.leakThreshold().isZero()) {
            return;
        }
        long now = System.nanoTime();
        for (Out o : out.values()) {
            if (now - o.nanos() > settings.leakThreshold().toNanos() && !o.warned()[0]) {
                o.warned()[0] = true;
                leaks.incrementAndGet();
                LOG.log(Level.WARNING, "connection leak suspected in the " + name + " pool: checked out by thread " + o.thread() + " for "
                        + (now - o.nanos()) / 1_000_000 + " ms (pool-leak-threshold " + settings.leakThreshold().toMillis() + " ms); taken at", o.borrower());
            }
        }
    }

    PoolStats stats() {
        long now = System.nanoTime();
        long longest = 0;
        for (Out o : out.values()) {
            longest = Math.max(longest, (now - o.nanos()) / 1_000_000);
        }
        int inUse = out.size();
        int o = Math.max(open.get(), inUse);
        return new PoolStats("mongodb", settings.size(), o, inUse, Math.max(0, o - inUse), waiting.get(), longest, leaks.get(), timeouts.get());
    }

    @Override
    public void connectionCreated(ConnectionCreatedEvent e) {
        open.incrementAndGet();
    }

    @Override
    public void connectionClosed(ConnectionClosedEvent e) {
        open.decrementAndGet();
        out.remove(e.getConnectionId().getLocalValue());
    }

    @Override
    public void connectionCheckOutStarted(ConnectionCheckOutStartedEvent e) {
        waiting.incrementAndGet();
        if (!settings.leakThreshold().isZero()) {
            asking.put(Thread.currentThread().threadId(), new Throwable("taken by " + Thread.currentThread().getName()));
        }
    }

    @Override
    public void connectionCheckedOut(ConnectionCheckedOutEvent e) {
        waiting.decrementAndGet();
        Throwable where = asking.remove(Thread.currentThread().threadId());
        out.put(e.getConnectionId().getLocalValue(), new Out(System.nanoTime(), where, Thread.currentThread().getName(), new boolean[1]));
    }

    @Override
    public void connectionCheckOutFailed(ConnectionCheckOutFailedEvent e) {
        waiting.decrementAndGet();
        asking.remove(Thread.currentThread().threadId());
        if (e.getReason() == ConnectionCheckOutFailedEvent.Reason.TIMEOUT) {
            timeouts.incrementAndGet();
        }
    }

    @Override
    public void connectionCheckedIn(ConnectionCheckedInEvent e) {
        out.remove(e.getConnectionId().getLocalValue());
    }
}
