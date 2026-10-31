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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The engine's admission (docs/architecture/BI_DESIGN.md section 6.3): {@code slots} queries run at once; a waiting query is served by its <b>work class</b>
 * (interactive before standing before background, then by arrival), within its person's share ({@code per-user}: standing and background work do not count
 * against it) and its model's share ({@code per-model-share} of the slots: one hot model cannot take the server). A query that waited {@code wait} is refused
 * {@code DRS-9025} (busy, retry). Each class but interactive also has a cap (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 10.1: {@code background-share},
 * {@code standing-share} of the slots), so background work (runs, dataset refresh) never holds the slots interactive work needs: with 8 slots and
 * both shares at 0.25, at least 4 are always left for interactive questions. A waiter leaves the line at once when its thread is interrupted
 * (a cancelled run). Thread-safe.
 */
public final class Admission {

    /** A place taken: give it back with {@link #close()}. */
    public final class Ticket implements AutoCloseable {
        private final Waiter w;
        private boolean closed;

        private Ticket(Waiter w) {
            this.w = w;
        }

        public long waitedNanos() {
            return w.granted - w.arrived;
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (!closed) {
                    closed = true;
                    release(w);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class Waiter {
        final String user;
        final String model;
        final QueryContext.WorkClass cls;
        final long seq;
        final long arrived = System.nanoTime();
        long granted;
        boolean admitted;

        Waiter(String user, String model, QueryContext.WorkClass cls, long seq) {
            this.user = user;
            this.model = model;
            this.cls = cls;
            this.seq = seq;
        }
    }

    private final int slots;
    private final int perUser;
    private final int perModel;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final List<Waiter> waiting = new ArrayList<>();
    private final Map<String, Integer> byUser = new HashMap<>();
    private final Map<String, Integer> byModel = new HashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong waitedNanos = new AtomicLong();
    private final java.util.ArrayDeque<long[]> recent = new java.util.ArrayDeque<>();      // {when, waited} of interactive admissions, under the lock

    /** The 95th percentile of the interactive admission wait over the last {@code window}, in milliseconds; null when none was admitted then. */
    public Double interactiveWaitP95Ms(java.time.Duration window) {
        long from = System.nanoTime() - window.toNanos();
        List<Long> waits = new ArrayList<>();
        lock.lock();
        try {
            for (long[] r : recent) {
                if (r[0] - from >= 0) {
                    waits.add(r[1]);
                }
            }
        } finally {
            lock.unlock();
        }
        if (waits.isEmpty()) {
            return null;
        }
        waits.sort(Long::compare);
        long p = waits.get(Math.min(waits.size() - 1, (int) Math.ceil(waits.size() * 0.95) - 1));
        return Math.round(p / 1e5) / 10.0;
    }
    private final Map<QueryContext.WorkClass, Integer> byClass = new java.util.EnumMap<>(QueryContext.WorkClass.class);
    private final int backgroundCap;
    private final int standingCap;
    private int running;

    /** No class caps: every class may take every slot (the gate before 2.6.7). */
    public Admission(int slots, int perUser, double perModelShare) {
        this(slots, perUser, perModelShare, 1.0, 1.0);
    }

    /**
     * @param backgroundShare the most of the slots {@code BACKGROUND} work holds at once (at least one slot)
     * @param standingShare   the most of the slots {@code STANDING} work holds at once (at least one slot)
     */
    public Admission(int slots, int perUser, double perModelShare, double backgroundShare, double standingShare) {
        this.slots = Math.max(1, slots);
        this.perUser = Math.max(1, perUser);
        this.perModel = Math.max(1, (int) Math.floor(this.slots * perModelShare));
        this.backgroundCap = cap(this.slots, backgroundShare);
        this.standingCap = cap(this.slots, standingShare);
    }

    private static int cap(int slots, double share) {
        return share <= 0 || share >= 1 ? slots : Math.max(1, (int) Math.floor(slots * share));
    }

    /** The most slots a class may hold at once. */
    public int classCap(QueryContext.WorkClass cls) {
        return switch (cls) {
            case BACKGROUND -> backgroundCap;
            case STANDING -> standingCap;
            case INTERACTIVE -> slots;
        };
    }

    /** Takes a place for a query of {@code user} on {@code model} in its work class, waiting at most {@code wait}; {@code DRS-9025} otherwise. */
    public Ticket enter(String user, String model, QueryContext.WorkClass cls, Duration wait) {
        Waiter w = new Waiter(user, model, cls, seq.incrementAndGet());
        lock.lock();
        try {
            waiting.add(w);
            long left = wait.toNanos();
            while (!w.admitted) {
                grant();
                if (w.admitted) {
                    break;
                }
                if (left <= 0) {
                    waiting.remove(w);
                    refused.incrementAndGet();
                    changed.signalAll();
                    throw busy(w);
                }
                try {
                    left = changed.awaitNanos(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    waiting.remove(w);
                    changed.signalAll();
                    throw new DrishtiException(ErrorCode.QUERY_BUSY, "the query was interrupted while it waited for a place");
                }
            }
            waitedNanos.addAndGet(w.granted - w.arrived);
            if (w.cls == QueryContext.WorkClass.INTERACTIVE) {
                recent.addLast(new long[] {System.nanoTime(), w.granted - w.arrived});   // the fairness signal (BI_DESIGN › BI runs, 17.2)
                while (recent.size() > 4096) {
                    recent.removeFirst();
                }
            }
            return new Ticket(w);
        } finally {
            lock.unlock();
        }
    }

    /** Admits the best waiting queries that fit (under the lock). */
    private void grant() {
        waiting.sort((a, b) -> a.cls != b.cls ? Integer.compare(a.cls.ordinal(), b.cls.ordinal()) : Long.compare(a.seq, b.seq));
        for (Waiter x : List.copyOf(waiting)) {
            if (running >= slots) {
                return;
            }
            boolean userOk = x.cls != QueryContext.WorkClass.INTERACTIVE || byUser.getOrDefault(x.user, 0) < perUser;
            boolean modelOk = byModel.getOrDefault(x.model, 0) < perModel;
            boolean classOk = byClass.getOrDefault(x.cls, 0) < classCap(x.cls);
            if (userOk && modelOk && classOk) {
                waiting.remove(x);
                x.admitted = true;
                x.granted = System.nanoTime();
                running++;
                if (x.cls == QueryContext.WorkClass.INTERACTIVE) {
                    byUser.merge(x.user, 1, Integer::sum);
                }
                byModel.merge(x.model, 1, Integer::sum);
                byClass.merge(x.cls, 1, Integer::sum);
                changed.signalAll();
            }
        }
    }

    private void release(Waiter w) {
        running--;
        if (w.cls == QueryContext.WorkClass.INTERACTIVE) {
            byUser.merge(w.user, -1, Integer::sum);
        }
        byModel.merge(w.model, -1, Integer::sum);
        byClass.merge(w.cls, -1, Integer::sum);
        grant();
        changed.signalAll();
    }

    private DrishtiException busy(Waiter w) {
        String why;
        if (running >= slots) {
            why = "the server is running " + slots + " queries already (drishti.bi.query.max-concurrent)";
        } else if (byClass.getOrDefault(w.cls, 0) >= classCap(w.cls)) {
            why = "background work holds its share of the server already (" + classCap(w.cls) + " of " + slots + " places, drishti.bi.exec."
                    + (w.cls == QueryContext.WorkClass.STANDING ? "standing" : "background") + "-share)";
        } else if (w.cls == QueryContext.WorkClass.INTERACTIVE && byUser.getOrDefault(w.user, 0) >= perUser) {
            why = "you already have " + perUser + " queries running (drishti.bi.query.max-concurrent-per-user)";
        } else {
            why = "the model has " + perModel + " queries running already, its share of the server (drishti.bi.exec.per-model-share)";
        }
        return new DrishtiException(ErrorCode.QUERY_BUSY, why + "; try again");
    }

    /** The places now (Admin, metrics). */
    public Map<String, Object> stats() {
        lock.lock();
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slots", slots);
            m.put("running", running);
            m.put("waiting", waiting.size());
            m.put("perUser", perUser);
            m.put("perModel", perModel);
            m.put("refused", refused.get());
            m.put("waitedSeconds", waitedNanos.get() / 1e9);
            Map<String, Object> classes = new LinkedHashMap<>();
            for (QueryContext.WorkClass c : QueryContext.WorkClass.values()) {
                long queued = waiting.stream().filter(x -> x.cls == c).count();
                classes.put(c.name().toLowerCase(java.util.Locale.ROOT), Map.of("running", byClass.getOrDefault(c, 0), "cap", classCap(c), "waiting", queued));
            }
            m.put("byClass", classes);
            return m;
        } finally {
            lock.unlock();
        }
    }

    public long refused() {
        return refused.get();
    }
}
