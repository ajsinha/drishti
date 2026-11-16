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
package com.ash.drishti.memlake;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One table of the in-memory lake: its settings, its state in words, its write lock (one writer at a time: its replenish job, its
 * compaction, its eviction), its counters and the history of its runs. Thread-safe.
 */
final class MemoryTable {

    /** Where the table stands. */
    enum State {
        EMPTY("empty: its first fill has not run"),
        REFILLING("refilling"),
        READY("ready"),
        EVICTED("evicted to make room; a refill is queued"),
        FAILED("its last fill failed"),
        REFUSED("its last fill was refused: the store is full"),
        REMOTE("held by its owner node");

        final String words;

        State(String words) {
            this.words = words;
        }
    }

    /** One run of the job (or a compaction, an eviction): values-free, ids and counts only. */
    record Run(Instant at, String trigger, String outcome, long rows, long bytes, long version, long millis, String message) {

        Map<String, Object> toMap(String table) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", table);
            m.put("at", at.toString());
            m.put("trigger", trigger);
            m.put("outcome", outcome);
            m.put("rows", rows);
            m.put("bytes", bytes);
            m.put("version", version);
            m.put("millis", millis);
            m.put("message", message);
            return m;
        }
    }

    final MemoryLakeSettings.TableSpec spec;
    final ReentrantLock lock = new ReentrantLock();
    private final int keep;
    private final Deque<Run> history = new ArrayDeque<>();
    private volatile State state = State.EMPTY;
    private volatile Instant since = Instant.now();
    volatile Instant lastRefill;
    volatile Instant nextRefill;
    volatile long version = -1;
    volatile long rows;
    volatile String sourceMark;
    volatile boolean refillQueued;
    final AtomicLong evictions = new AtomicLong();
    final AtomicLong refusals = new AtomicLong();
    final AtomicLong appendsSinceSort = new AtomicLong();
    final AtomicLong rowsReplaced = new AtomicLong();
    final AtomicLong compactions = new AtomicLong();
    /** Rows dropped by the table's retention (its TTL and row cap), since start. */
    final AtomicLong expiredRows = new AtomicLong();
    final AtomicLong savedTotal = new AtomicLong();
    private volatile LocalDate savedDay = LocalDate.now(ZoneId.systemDefault());
    private final AtomicLong savedToday = new AtomicLong();
    /** The decoded size of the rows last written (their JSON), for the compression ratio on Admin, Lakes. */
    volatile long decodedBytes;

    MemoryTable(MemoryLakeSettings.TableSpec spec, int keep) {
        this.spec = spec;
        this.keep = keep;
    }

    String name() {
        return spec.name();
    }

    State state() {
        return state;
    }

    Instant since() {
        return since;
    }

    void state(State s) {
        if (s != state) {
            state = s;
            since = Instant.now();
        }
    }

    void record(Run r) {
        synchronized (history) {
            history.addFirst(r);
            while (history.size() > keep) {
                history.removeLast();
            }
        }
    }

    List<Run> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    void saved(long bytes) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (!today.equals(savedDay)) {
            savedDay = today;
            savedToday.set(0);
        }
        savedToday.addAndGet(Math.max(0, bytes));
        savedTotal.addAndGet(Math.max(0, bytes));
    }

    long savedToday() {
        return LocalDate.now(ZoneId.systemDefault()).equals(savedDay) ? savedToday.get() : 0;
    }

    /**
     * What a screen says while the table is not ready: "news (news): held in memory only; refilling since 08:02, about a minute". Null
     * when it is ready.
     */
    String words(String contract) {
        State s = state;
        if (s == State.READY) {
            return null;
        }
        String held = name() + ": held in memory only" + (contract == null || contract.isBlank() ? "" : " (contract " + contract + ")");
        String at = java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault()).format(since);
        return switch (s) {
            case REFILLING -> held + "; refilling since " + at + ", " + about();
            case EVICTED -> held + "; evicted at " + at + " to make room, a refill is queued";
            case EMPTY -> held + "; not filled yet since the server started, its first fill is queued";
            case FAILED -> held + "; its last fill failed at " + at + " (Admin > Lakes), the next one is queued";
            case REFUSED -> held + "; its last fill was refused at " + at + ": the memory store is full (Admin > Lakes)";
            case REMOTE -> held + " by its owner node";
            case READY -> null;
        };
    }

    /** How long a fill takes, from the last runs: "about a minute". */
    private String about() {
        List<Run> h = history();
        long ms = h.stream().filter(r -> r.outcome().equals("ok")).mapToLong(Run::millis).findFirst().orElse(60_000);
        Duration d = Duration.ofMillis(Math.max(ms, 1000));
        return d.toMinutes() >= 2 ? "about " + d.toMinutes() + " minutes" : d.toSeconds() >= 45 ? "about a minute" : "less than a minute";
    }
}
