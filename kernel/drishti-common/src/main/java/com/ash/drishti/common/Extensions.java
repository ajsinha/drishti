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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live contributions of loaded modules (docs/architecture/KERNEL_AND_MODULES.md section 12.3): the objects a module adds to one of the
 * kernel's extension types (a health figure, an explainer, an approval source, a vocabulary ...) while it is loaded. Each collection point of
 * the kernel reads its fixed set (its beans, its {@link java.util.ServiceLoader} finds) and then this one, so a module's contribution is
 * seen the moment it is added and gone the moment it is withdrawn. Only the kernel's module host adds; the action it gets back withdraws.
 * Nothing per person is held here.
 */
public final class Extensions {

    private record Entry(Class<?> type, String owner, Object value) {
    }

    private static final List<Entry> LIVE = new CopyOnWriteArrayList<>();
    private static final AtomicLong VERSION = new AtomicLong();

    private Extensions() {
    }

    /** Adds a module's object of an extension type; the returned action withdraws it (idempotent). */
    public static <T> Runnable add(Class<T> type, String owner, T value) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(value + " is not a " + type.getName());
        }
        Entry e = new Entry(type, owner, value);
        LIVE.add(e);
        VERSION.incrementAndGet();
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true) && LIVE.remove(e)) {
                VERSION.incrementAndGet();
            }
        };
    }

    /** The live objects of a type, in the order they were added. */
    public static <T> List<T> of(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Entry e : LIVE) {
            if (e.type() == type) {
                out.add(type.cast(e.value()));
            }
        }
        return out;
    }

    /** The fixed set followed by the live objects of the type: what a collection point iterates. */
    public static <T> List<T> with(Collection<? extends T> fixed, Class<T> type) {
        List<T> live = of(type);
        if (live.isEmpty()) {
            return fixed == null ? List.of() : List.copyOf(fixed);
        }
        List<T> out = new ArrayList<>(fixed == null ? List.of() : fixed);
        out.addAll(live);
        return out;
    }

    /** Every live object's owner and type, in words ({@code demo: HealthFigures}); one owner's, or every one's for null. */
    public static List<String> describe(String owner) {
        List<String> out = new ArrayList<>();
        for (Entry e : LIVE) {
            if (owner == null || owner.equals(e.owner())) {
                out.add(e.owner() + ": " + e.type().getSimpleName());
            }
        }
        return out;
    }

    /** Changes of the live set under way: the server's first load of its modules, a module swapping versions. */
    private static final java.util.concurrent.atomic.AtomicInteger CHANGING = new java.util.concurrent.atomic.AtomicInteger();
    // a lock, not a monitor: a virtual thread waiting for the live set to settle must not pin its carrier (Java 21)
    private static final java.util.concurrent.locks.ReentrantLock SETTLE_LOCK = new java.util.concurrent.locks.ReentrantLock();
    private static final java.util.concurrent.locks.Condition SETTLED = SETTLE_LOCK.newCondition();
    private static final AtomicLong STARTED = new AtomicLong();

    /** How many changes of the live set have begun (a read is calm when this did not move and nothing was changing across it). */
    public static long changes() {
        return STARTED.get();
    }

    /**
     * A change of the live set begins that takes a module's contributions away for a moment or brings them late: the server's first load of
     * its modules (its web layer answers before the modules have loaded), a module unloaded and loaded again in another version. Until the
     * returned action runs (idempotent), a collection point that misses what it was asked for can {@link #awaitSettled wait} for the change
     * instead of answering "not here".
     */
    public static Runnable changing() {
        STARTED.incrementAndGet();
        CHANGING.incrementAndGet();
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) {
                CHANGING.decrementAndGet();
                SETTLE_LOCK.lock();
                try {
                    SETTLED.signalAll();
                } finally {
                    SETTLE_LOCK.unlock();
                }
            }
        };
    }

    /** True when no change of the live set is under way. */
    public static boolean settled() {
        return CHANGING.get() <= 0;
    }

    /** Waits until no change of the live set is under way, at most {@code millis}; true when settled. */
    public static boolean awaitSettled(long millis) {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        SETTLE_LOCK.lock();
        try {
            while (!settled()) {
                long left = until - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    SETTLED.awaitNanos(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        } finally {
            SETTLE_LOCK.unlock();
        }
        return true;
    }

    /** A counter that changes whenever a contribution is added or withdrawn (caches over the live set follow it). */
    public static long version() {
        return VERSION.get();
    }
}
