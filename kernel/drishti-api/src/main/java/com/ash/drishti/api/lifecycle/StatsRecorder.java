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
package com.ash.drishti.api.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts what a part does for its {@link ComponentStats}: lock-free adders for the totals, a ring of one-minute buckets for the recent
 * window, and a ring of the last {@link #SAMPLES} latencies (with their time) for the percentiles. Recording is a few atomic adds; a snapshot
 * copies at most {@link #SAMPLES} longs and sorts them. One recorder per part; the part keeps it across suspend and resume.
 */
public final class StatsRecorder {

    /** The recent window. */
    public static final Duration WINDOW = Duration.ofMinutes(5);
    /** The latencies kept for the percentiles. */
    public static final int SAMPLES = 1024;
    private static final int BUCKETS = 5;
    private static final long BUCKET_MS = WINDOW.toMillis() / BUCKETS;
    private static final int FIELDS = 8;

    private final Clock clock;
    private final Instant since;
    private final LongAdder[] total = new LongAdder[FIELDS];
    /** Per bucket: its minute number, then the eight counters. */
    private final AtomicLongArray buckets = new AtomicLongArray(BUCKETS * (FIELDS + 1));
    private final AtomicLongArray latencyNanos = new AtomicLongArray(SAMPLES);
    private final AtomicLongArray latencyAt = new AtomicLongArray(SAMPLES);
    private final AtomicLong next = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private volatile long lastActivity;
    private volatile java.util.function.LongSupplier queue = () -> 0;

    public StatsRecorder() {
        this(Clock.systemUTC());
    }

    public StatsRecorder(Clock clock) {
        this.clock = clock;
        this.since = clock.instant();
        for (int i = 0; i < FIELDS; i++) {
            total[i] = new LongAdder();
        }
    }

    /** Where the queue depth comes from (a live connector's backlog); in flight by default. */
    public void queueDepth(java.util.function.LongSupplier q) {
        this.queue = q == null ? () -> 0 : q;
    }

    /** A request begins: counted in flight until the returned ticket is closed. */
    public Ticket begin() {
        inFlight.incrementAndGet();
        return new Ticket(System.nanoTime());
    }

    /** One request in flight; {@link #ok}, {@link #failed} or {@link #refused} ends it once. */
    public final class Ticket implements AutoCloseable {
        private final long t0;
        private boolean done;

        private Ticket(long t0) {
            this.t0 = t0;
        }

        public void ok(long rows, long bytes) {
            end(0, rows, bytes);
        }

        public void failed() {
            end(1, 0, 0);
        }

        /** Ends it as answered with nothing counted (a close without ok/failed). */
        @Override
        public void close() {
            end(0, 0, 0);
        }

        private synchronized void end(int error, long rows, long bytes) {
            if (done) {
                return;
            }
            done = true;
            inFlight.decrementAndGet();
            request(System.nanoTime() - t0, error == 1, rows, bytes);
        }
    }

    /** One request answered in {@code nanos}: an error or not, rows read and bytes. */
    public void request(long nanos, boolean error, long rows, long bytes) {
        add(0, 1);
        if (error) {
            add(1, 1);
        }
        add(3, rows);
        add(5, bytes);
        long i = next.getAndIncrement() % SAMPLES;
        long now = clock.millis();
        latencyNanos.set((int) i, Math.max(0, nanos));
        latencyAt.set((int) i, now);
        lastActivity = now;
    }

    /** A request admitted where its end is not seen (a mnemonic or a kind opened through the gate): counted, no latency sample. */
    public void admitted() {
        add(0, 1);
        lastActivity = clock.millis();
    }

    /** A request refused (suspended, not running). */
    public void refused() {
        add(2, 1);
    }

    /** Rows read outside a request (a live connector's pull). */
    public void rowsRead(long rows, long bytes) {
        add(3, rows);
        add(5, bytes);
        lastActivity = clock.millis();
    }

    public void rowsWritten(long rows) {
        add(4, rows);
        lastActivity = clock.millis();
    }

    public void cache(boolean hit) {
        add(hit ? 6 : 7, 1);
    }

    private void add(int field, long n) {
        if (n == 0) {
            return;
        }
        total[field].add(n);
        long minute = clock.millis() / BUCKET_MS;
        int b = (int) (minute % BUCKETS);
        int base = b * (FIELDS + 1);
        long seen = buckets.get(base);
        if (seen != minute) {
            synchronized (buckets) {                       // the first add of a new minute empties its bucket; rare
                if (buckets.get(base) != minute) {
                    for (int f = 1; f <= FIELDS; f++) {
                        buckets.set(base + f, 0);
                    }
                    buckets.set(base, minute);
                }
            }
        }
        buckets.addAndGet(base + 1 + field, n);
    }

    /** The snapshot. */
    public ComponentStats snapshot() {
        long[] t = new long[FIELDS];
        for (int i = 0; i < FIELDS; i++) {
            t[i] = total[i].sum();
        }
        long[] w = new long[FIELDS];
        long minute = clock.millis() / BUCKET_MS;
        for (int b = 0; b < BUCKETS; b++) {
            int base = b * (FIELDS + 1);
            long m = buckets.get(base);
            if (m > minute - BUCKETS && m <= minute) {
                for (int f = 0; f < FIELDS; f++) {
                    w[f] += buckets.get(base + 1 + f);
                }
            }
        }
        long cutoff = clock.millis() - WINDOW.toMillis();
        long[] lat = new long[SAMPLES];
        int n = 0;
        int filled = (int) Math.min(next.get(), SAMPLES);
        for (int i = 0; i < filled; i++) {
            if (latencyAt.get(i) >= cutoff) {
                lat[n++] = latencyNanos.get(i);
            }
        }
        ComponentStats.Latency latency = ComponentStats.Latency.NONE;
        if (n > 0) {
            long[] s = Arrays.copyOf(lat, n);
            Arrays.sort(s);
            latency = new ComponentStats.Latency(n, ms(pct(s, 0.50)), ms(pct(s, 0.95)), ms(pct(s, 0.99)), ms(s[n - 1]));
        }
        long last = lastActivity;
        long flying = inFlight.get();
        long q = Math.max(flying, queue.getAsLong());
        return new ComponentStats(since, counts(t), counts(w), WINDOW, latency, q, flying, last == 0 ? null : Instant.ofEpochMilli(last));
    }

    private static ComponentStats.Counts counts(long[] c) {
        return new ComponentStats.Counts(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
    }

    /** The nearest-rank percentile of sorted samples. */
    static long pct(long[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    public long inFlight() {
        return inFlight.get();
    }
}
