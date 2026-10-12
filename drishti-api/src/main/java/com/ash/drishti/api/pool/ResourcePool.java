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
package com.ash.drishti.api.pool;

import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded pool of connections (anything {@link AutoCloseable}). A caller {@link #borrow borrows} a {@link Lease} in a
 * try-with-resources block, does one unit of work and closes the lease at once: the connection goes back for the next caller.
 *
 * <ul>
 *   <li>at most {@code size} connections exist; a caller waits {@code connectionTimeout} for one, then fails with a message that
 *       says how busy the pool is;
 *   <li>connections are opened on first use (a database that is still starting does not stop the connector) and replaced after
 *       {@code maxLifetime}; extra idle ones are closed after {@code idleTimeout};
 *   <li>a borrow held longer than {@code leakThreshold} is logged once with the stack of the code that borrowed it, and counted;
 *   <li>a lease marked {@link Lease#invalidate} (a broken connection) is closed instead of returned;
 *   <li>{@link #stats} reports open, in use, idle, waiting and the longest current borrow.
 * </ul>
 * Thread-safe; no lock is held while a connection is opened, validated or used.
 *
 * @param <T> the pooled connection type
 */
public final class ResourcePool<T extends AutoCloseable> implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ResourcePool.class.getName());
    private static final ScheduledExecutorService WATCH = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "drishti-pool-watch");
        t.setDaemon(true);
        return t;
    });

    /** How the pool opens, checks and closes a connection. */
    public interface Factory<T extends AutoCloseable> {
        /** Opens a connection. */
        T open() throws Exception;

        /** False when a connection taken from the idle list is no longer usable (it is closed and another is opened). */
        default boolean valid(T connection) {
            return true;
        }

        /** Gets a returned connection ready for the next borrower (roll back, reset flags); false discards it. */
        default boolean reset(T connection) {
            return true;
        }

        /** Closes a connection; never throws. */
        default void close(T connection) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // already broken
            }
        }
    }

    private record Idle<T>(T connection, long openedNanos, long idleSinceNanos) {}

    /** One borrow. Close it (try-with-resources) as soon as the unit of work is done. */
    public final class Lease implements AutoCloseable {
        private final T connection;
        private final long openedNanos;
        private final long borrowedNanos = System.nanoTime();
        private final Throwable borrower;
        private final String thread = Thread.currentThread().getName();
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile boolean broken;
        private volatile boolean warned;

        private Lease(T connection, long openedNanos) {
            this.connection = connection;
            this.openedNanos = openedNanos;
            this.borrower = leak.isZero() ? null : new Throwable("borrowed by " + thread);
        }

        /** The connection; valid until this lease is closed. */
        public T get() {
            return connection;
        }

        /** Marks the connection broken: closing the lease closes it instead of returning it. */
        public void invalidate() {
            broken = true;
        }

        @Override
        public void close() {
            if (done.compareAndSet(false, true)) {
                giveBack(this);
            }
        }
    }

    private final String name;
    private final String type;
    private final PoolSettings settings;
    private final java.time.Duration leak;
    private final Factory<T> factory;
    private final Semaphore permits;
    private final ReentrantLock lock = new ReentrantLock();      // guards the idle list only, never held across I/O
    private final ArrayDeque<Idle<T>> idle = new ArrayDeque<>();
    private final Map<Lease, Boolean> leases = new ConcurrentHashMap<>();
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicLong leaks = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final ScheduledFuture<?> watcher;
    private volatile boolean closed;

    /**
     * @param name the connector's name, for logs and messages
     * @param type what is pooled (jdbc, ...), for the stats
     * @param settings the pool's size and times
     * @param factory how connections are opened, checked and closed
     */
    public ResourcePool(String name, String type, PoolSettings settings, Factory<T> factory) {
        this.name = name;
        this.type = type;
        this.settings = settings;
        this.leak = settings.leakThreshold();
        this.factory = factory;
        this.permits = new Semaphore(settings.size(), true);
        long leakTick = leak.isZero() ? 5_000 : leak.toMillis() / 2;
        long tick = Math.max(50, Math.min(5_000, Math.min(leakTick, settings.idleTimeout().toMillis() / 2 + 1)));
        this.watcher = WATCH.scheduleWithFixedDelay(this::sweep, tick, tick, TimeUnit.MILLISECONDS);
    }

    /** Takes a connection, waiting up to the connection timeout. Close the lease when the unit of work is done. */
    public Lease borrow() throws Exception {
        if (closed) {
            throw new IllegalStateException("pool " + name + " is closed");
        }
        waiting.incrementAndGet();
        boolean got;
        try {
            got = permits.tryAcquire(settings.connectionTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            waiting.decrementAndGet();
        }
        if (!got) {
            timeouts.incrementAndGet();
            PoolStats s = stats();
            throw new PoolExhaustedException("no free connection in the " + name + " pool within " + settings.connectionTimeout().toMillis()
                    + " ms (" + s.inUse() + " of " + settings.size() + " in use, " + s.waiting() + " waiting, longest borrow "
                    + s.longestBorrowMillis() + " ms); raise pool-size or find what holds connections");
        }
        try {
            Lease lease = fromIdle();
            if (lease == null) {
                T c = factory.open();
                open.incrementAndGet();
                lease = new Lease(c, System.nanoTime());
            }
            leases.put(lease, Boolean.TRUE);
            return lease;
        } catch (Exception | Error e) {
            permits.release();
            throw e;
        }
    }

    private Lease fromIdle() {
        while (true) {
            Idle<T> i;
            lock.lock();
            try {
                i = idle.pollFirst();
            } finally {
                lock.unlock();
            }
            if (i == null) {
                return null;
            }
            if (System.nanoTime() - i.openedNanos() > settings.maxLifetime().toNanos() || !factory.valid(i.connection())) {
                discard(i.connection());
                continue;
            }
            return new Lease(i.connection(), i.openedNanos());
        }
    }

    private void giveBack(Lease lease) {
        leases.remove(lease);
        try {
            boolean keep = !lease.broken && !closed && factory.reset(lease.connection)
                    && System.nanoTime() - lease.openedNanos <= settings.maxLifetime().toNanos();
            if (keep) {
                lock.lock();
                try {
                    idle.addFirst(new Idle<>(lease.connection, lease.openedNanos, System.nanoTime()));
                } finally {
                    lock.unlock();
                }
            } else {
                discard(lease.connection);
            }
        } finally {
            permits.release();
        }
    }

    private void discard(T c) {
        open.decrementAndGet();
        factory.close(c);
    }

    /** Leak detection and idle eviction; runs on the shared watcher thread. */
    private void sweep() {
        try {
            long now = System.nanoTime();
            if (!leak.isZero()) {
                for (Lease l : leases.keySet()) {
                    if (!l.warned && now - l.borrowedNanos > leak.toNanos()) {
                        l.warned = true;
                        leaks.incrementAndGet();
                        LOG.log(Level.WARNING, "connection leak suspected in the " + name + " pool: borrowed by thread " + l.thread + " for "
                                + (now - l.borrowedNanos) / 1_000_000 + " ms (pool-leak-threshold " + leak.toMillis() + " ms); borrowed at",
                                l.borrower);
                    }
                }
            }
            java.util.List<T> expired = new java.util.ArrayList<>();
            lock.lock();
            try {
                var it = idle.descendingIterator();                  // oldest idle first
                while (it.hasNext() && idle.size() > settings.minIdle()) {
                    Idle<T> i = it.next();
                    if (now - i.idleSinceNanos() > settings.idleTimeout().toNanos() || now - i.openedNanos() > settings.maxLifetime().toNanos()) {
                        it.remove();
                        expired.add(i.connection());
                    }
                }
            } finally {
                lock.unlock();
            }
            expired.forEach(this::discard);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "pool " + name + " sweep failed", e);
        }
    }

    /** A snapshot: open, in use, idle, waiting and the longest current borrow. */
    public PoolStats stats() {
        long now = System.nanoTime();
        long longest = 0;
        for (Lease l : leases.keySet()) {
            longest = Math.max(longest, (now - l.borrowedNanos) / 1_000_000);
        }
        int inUse = leases.size();
        int o = Math.max(open.get(), inUse);
        return new PoolStats(type, settings.size(), o, inUse, Math.max(0, o - inUse), waiting.get(), longest, leaks.get(), timeouts.get());
    }

    public PoolSettings settings() {
        return settings;
    }

    /** Closes the idle connections; borrowed ones are closed when their lease is. */
    @Override
    public void close() {
        closed = true;
        watcher.cancel(false);
        java.util.List<Idle<T>> all;
        lock.lock();
        try {
            all = new java.util.ArrayList<>(idle);
            idle.clear();
        } finally {
            lock.unlock();
        }
        all.forEach(i -> discard(i.connection()));
    }
}
