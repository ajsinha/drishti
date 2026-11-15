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
package com.ash.drishti.common.live;

import com.ash.drishti.api.live.LivePosition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The live lander of one live connector (docs/architecture/LIVE_CONNECTORS_DESIGN.md sections 2.4 and 7.5): the rows of a landed kind
 * ({@code apply: land}) collect in a window, the newest per key by the contract's order; when the window is due (its age reaches
 * {@code land.window}, 5 minutes; or {@code land.max-keys} keys, 500, wait; or an administrator says "apply now") each kind and business
 * date of it becomes one batch, handed to the {@link Landing} (the lake writer, then the data-load signal). The batch's id comes from its
 * first and last transport positions, so a window written again after a take-over is the same batch. What the engine
 * rejects is reported in the feed's reject log. One flush at a time.
 */
public final class LiveLander {

    /** One waiting row. */
    private record Pending(LiveRecord record, ObjectNode doc) {}

    /** The window settings: how long it collects, and how many keys make it due earlier. */
    public record Window(Duration age, int maxKeys) {

        public static Window of(Map<String, String> settings) {
            List<String> problems = new ArrayList<>();
            Duration age = LiveRules.duration(settings.getOrDefault("land.window", "5m"), Duration.ofMinutes(5), "land.window", problems);
            int max;
            try {
                max = Integer.parseInt(settings.getOrDefault("land.max-keys", "500").strip());
            } catch (NumberFormatException e) {
                max = 500;
            }
            return new Window(age, Math.max(1, max));
        }
    }

    private final String connector;
    private final Map<String, String> settings;
    private final Window window;
    private final Clock clock;
    private final Map<String, Pending> waiting = new LinkedHashMap<>();   // kind|date|key -> the newest row
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantLock flushing = new ReentrantLock();
    private volatile Landing landing;
    private Instant opened;
    private volatile boolean now;
    private final List<Map<String, Object>> lastBatches = new ArrayList<>();

    public LiveLander(String connector, Map<String, String> settings, Landing landing, Clock clock) {
        this.connector = connector;
        this.settings = settings == null ? Map.of() : Map.copyOf(settings);
        this.window = Window.of(this.settings);
        this.landing = landing == null ? Landing.NONE : landing;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Holds a row for the window; returns false when an older one is offered (a redelivery or a late row: the window keeps the newer). A
     * row of a key already waiting replaces it when newer by the contract's order (then the transport position).
     */
    public boolean offer(LiveRecord r, ObjectNode doc) {
        String id = r.kind() + "|" + r.asOf() + "|" + r.key();
        lock.lock();
        try {
            Pending old = waiting.get(id);
            if (old != null && LiveRecord.ORDER.compare(r, old.record()) <= 0) {
                return false;
            }
            if (waiting.isEmpty()) {
                opened = clock.instant();
            }
            waiting.put(id, new Pending(r, doc));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Rows waiting. */
    public int waiting() {
        lock.lock();
        try {
            return waiting.size();
        } finally {
            lock.unlock();
        }
    }

    /** An administrator's "apply now": the waiting window is landed at the next look (within a second). */
    public void applyNow() {
        now = true;
    }

    /** The server's lake writer, once it is there. */
    public void landing(Landing l) {
        landing = l == null ? Landing.NONE : l;
    }

    /** Why the window is due now ({@code window}, {@code max-keys}, {@code apply now}), or null. */
    String due() {
        lock.lock();
        try {
            if (waiting.isEmpty()) {
                return null;
            }
            if (now) {
                return "apply now";
            }
            if (waiting.size() >= window.maxKeys()) {
                return "max-keys " + window.maxKeys();
            }
            return opened != null && !clock.instant().isBefore(opened.plus(window.age())) ? "window " + window.age() : null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Lands the window when it is due; returns each batch's result (empty when nothing was due). The rows are taken out of the window first;
     * a batch the lake could not take is said in its result (its rows are in the reject log with the reason).
     */
    public List<Landing.Landed> flushIfDue(RejectSink rejects) {
        if (!flushing.tryLock()) {
            return List.of();
        }
        try {
            String why = due();
            if (why == null) {
                return List.of();
            }
            Map<String, List<Pending>> batches = new TreeMap<>();
            lock.lock();
            try {
                waiting.values().forEach(p -> batches.computeIfAbsent(p.record().kind() + "|" + p.record().asOf(), k -> new ArrayList<>()).add(p));
                waiting.clear();
                opened = null;
                now = false;
            } finally {
                lock.unlock();
            }
            List<Landing.Landed> out = new ArrayList<>();
            for (List<Pending> rows : batches.values()) {
                LiveRecord first = rows.get(0).record();
                rows.sort(java.util.Comparator.comparing(p -> p.record().key()));
                LocalDate date = first.asOf() == null ? LocalDate.now(clock) : LocalDate.parse(first.asOf().substring(0, 10));
                String batch = batchId(first.kind(), date, rows);
                Landing.Landed l = landing.land(new Landing.Batch(connector, first.kind(), date, batch, rows.stream().map(Pending::doc).toList(),
                        settings));
                for (Landing.Reject x : l.rejects()) {
                    rejects.reject(first.kind(), x.code(), x.key() + ": " + x.why());
                }
                if (l.error() != null) {
                    rejects.reject(first.kind(), com.ash.drishti.common.ErrorCode.SOURCE_FAILED.code(), "batch " + batch + " (" + rows.size()
                            + " rows) was not landed: " + l.error());
                }
                Map<String, Object> said = new LinkedHashMap<>();
                said.put("batchId", batch);
                said.put("kind", first.kind());
                said.put("businessDate", date.toString());
                said.put("why", why);
                said.put("rows", rows.size());
                said.put("written", l.written());
                said.put("rejected", l.rejects().size());
                said.put("error", l.error());
                said.put("at", clock.instant().toString());
                synchronized (lastBatches) {
                    lastBatches.add(said);
                    while (lastBatches.size() > 10) {
                        lastBatches.remove(0);
                    }
                }
                out.add(l);
            }
            return out;
        } finally {
            flushing.unlock();
        }
    }

    /** The newest batches (at most 10), oldest first, for Health and Admin. */
    public List<Map<String, Object>> lastBatches() {
        synchronized (lastBatches) {
            return List.copyOf(lastBatches);
        }
    }

    /** The window as Health shows it. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("waiting", waiting());
        m.put("window", window.age().toString());
        m.put("maxKeys", window.maxKeys());
        m.put("lastBatches", lastBatches());
        return m;
    }

    /**
     * {@code L-<kind>-<date>-<first position>-<last position>}: the batch's transport span (the lowest and highest position of its rows),
     * so the same window written again (a take-over, a retry) carries the same id and the lake's append-once rule keeps it once.
     */
    static String batchId(String kind, LocalDate date, List<Pending> rows) {
        LivePosition lo = null;
        LivePosition hi = null;
        for (Pending p : rows) {
            LivePosition x = p.record().position();
            if (x == null) {
                continue;
            }
            if (lo == null || x.offset() < lo.offset()) {
                lo = x;
            }
            if (hi == null || x.offset() > hi.offset()) {
                hi = x;
            }
        }
        String span = lo == null ? String.valueOf(rows.size()) : lo.partition() + "." + lo.offset() + "-" + hi.partition() + "." + hi.offset();
        return "L-" + kind + "-" + date + "-" + span;
    }

    /** Where the engine's rejects of a batch go: the feed's reject log. */
    @FunctionalInterface
    public interface RejectSink {
        void reject(String kind, String code, String why);
    }
}
