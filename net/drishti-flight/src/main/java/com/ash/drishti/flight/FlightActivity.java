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
package com.ash.drishti.flight;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What Admin → Data out shows of this node (Q9): the live reads with their counters (and a Stop), and the finished reads of the last
 * day with their refusals. In memory only; the durable record is the server's audit. Thread-safe.
 */
public final class FlightActivity {

    /** The most finished reads kept, whatever the period. */
    public static final int MAX_KEPT = 10_000;

    private final Map<String, Live> live = new ConcurrentHashMap<>();
    private final Deque<FlightBackend.Audit> done = new ArrayDeque<>();
    private final AtomicLong ids = new AtomicLong();
    private final Duration kept;
    private final Clock clock;

    public FlightActivity(Duration kept, Clock clock) {
        this.kept = kept;
        this.clock = clock;
    }

    /** A read under way: its counters move as batches are sent; {@link #stop} asks it to end after its current batch. */
    public static final class Live {
        private final String id;
        private final FlightBackend.Caller caller;
        private final FlightRequest request;
        private final int part;
        private final Instant started;
        private final AtomicLong rows = new AtomicLong();
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicLong sequence = new AtomicLong(-1);
        private final AtomicLong memory = new AtomicLong();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile String stoppedBy;

        Live(String id, FlightBackend.Caller caller, FlightRequest request, int part, Instant started) {
            this.id = id;
            this.caller = caller;
            this.request = request;
            this.part = part;
            this.started = started;
        }

        public String id() {
            return id;
        }

        public FlightBackend.Caller caller() {
            return caller;
        }

        public FlightRequest request() {
            return request;
        }

        public int part() {
            return part;
        }

        public Instant started() {
            return started;
        }

        public long rows() {
            return rows.get();
        }

        public long bytes() {
            return bytes.get();
        }

        public long sequence() {
            return sequence.get();
        }

        public long memory() {
            return memory.get();
        }

        public boolean stopped() {
            return stopped.get();
        }

        public String stoppedBy() {
            return stoppedBy;
        }

        void sent(long r, long b, long seq, long mem) {
            rows.addAndGet(r);
            bytes.addAndGet(b);
            if (seq >= 0) {
                sequence.set(seq);
            }
            memory.set(mem);
        }
    }

    Live begin(FlightBackend.Caller caller, FlightRequest request, int part) {
        String id = "f" + ids.incrementAndGet();
        Live l = new Live(id, caller, request, part, clock.instant());
        live.put(id, l);
        return l;
    }

    void end(Live l, FlightBackend.Audit record) {
        live.remove(l.id);
        synchronized (done) {
            done.addLast(record);
            trim();
        }
    }

    /** Asks a live read to end (Admin → Data out, Stop); false when no such read is live. */
    public boolean stop(String id, String by) {
        Live l = live.get(id);
        if (l == null) {
            return false;
        }
        l.stoppedBy = by;
        l.stopped.set(true);
        return true;
    }

    /** The live reads, oldest first. */
    public List<Live> live() {
        List<Live> out = new ArrayList<>(live.values());
        out.sort(Comparator.comparing(Live::started));
        return out;
    }

    /** The finished reads of the kept period, newest first. */
    public List<FlightBackend.Audit> recent() {
        synchronized (done) {
            trim();
            List<FlightBackend.Audit> out = new ArrayList<>(done);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    private void trim() {
        Instant cut = clock.instant().minus(kept);
        while (!done.isEmpty() && (done.size() > MAX_KEPT || done.peekFirst().at().isBefore(cut))) {
            done.removeFirst();
        }
    }
}
