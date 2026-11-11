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
package com.ash.drishti.server.net;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What this node sent to its peers (Admin → Net → Routing, Admin → Health, the metrics): every forwarded view, peer fetch, scattered
 * search share and live relay with its peer, kind, outcome and time. Kept as per-minute counts for the last hour per peer and outcome,
 * the last 100 calls, and the Micrometer meters {@code drishti.net.forwarded} (peer, kind, outcome) and {@code drishti.net.peer.latency}
 * (peer). Thread-safe and cheap: a call is one array increment and one short lock for the recent list.
 */
public final class ForwardLog {

    /** One call to a peer. {@code outcome}: ok, not-held, refused, failed, timeout. */
    public record Call(Instant at, String peer, String kind, String what, String outcome, long ms, String detail) {}

    private static final int MINUTES = 60;
    private static final List<String> OUTCOMES = List.of("ok", "not-held", "refused", "failed", "timeout");

    /** Per peer: count per minute slot and outcome, total milliseconds per slot, the minute each slot holds. */
    private static final class PerPeer {
        final AtomicLongArray counts = new AtomicLongArray(MINUTES * OUTCOMES.size());
        final AtomicLongArray millis = new AtomicLongArray(MINUTES);
        final AtomicLongArray minuteOf = new AtomicLongArray(MINUTES);
    }

    private final Map<String, PerPeer> peers = new ConcurrentHashMap<>();
    private final Deque<Call> recent = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final MeterRegistry meters;

    public ForwardLog(MeterRegistry meters) {
        this.meters = meters;
    }

    /** The outcome of an HTTP status from a peer. */
    public static String outcome(int status) {
        if (status / 100 == 2) {
            return "ok";
        }
        if (status == 404) {
            return "not-held";
        }
        if (status == 401 || status == 403) {
            return "refused";
        }
        return status == 504 ? "timeout" : "failed";
    }

    public void record(String peer, String kind, String what, String outcome, long ms, String detail) {
        long minute = System.currentTimeMillis() / 60_000;
        int slot = (int) (minute % MINUTES);
        PerPeer p = peers.computeIfAbsent(peer, x -> new PerPeer());
        long had = p.minuteOf.get(slot);
        if (had != minute && p.minuteOf.compareAndSet(slot, had, minute)) {
            for (int i = 0; i < OUTCOMES.size(); i++) {
                p.counts.set(slot * OUTCOMES.size() + i, 0);
            }
            p.millis.set(slot, 0);
        }
        int o = Math.max(0, OUTCOMES.indexOf(outcome));
        p.counts.incrementAndGet(slot * OUTCOMES.size() + o);
        p.millis.addAndGet(slot, ms);
        if (meters != null) {
            Counter.builder("drishti.net.forwarded").description("Requests this node sent to a peer").tag("peer", peer)
                    .tag("kind", kind == null ? "" : kind).tag("outcome", outcome).register(meters).increment();
            Timer.builder("drishti.net.peer.latency").description("Time a peer took to answer").tag("peer", peer).publishPercentileHistogram()
                    .register(meters).record(Duration.ofMillis(ms));
        }
        lock.lock();
        try {
            recent.addFirst(new Call(Instant.now(), peer, kind, what, outcome, ms, detail));
            while (recent.size() > 100) {
                recent.removeLast();
            }
        } finally {
            lock.unlock();
        }
    }

    public List<Call> recent() {
        lock.lock();
        try {
            return List.copyOf(recent);
        } finally {
            lock.unlock();
        }
    }

    /** The last {@code minutes} minutes per peer, oldest first: counts per outcome and the average time. */
    public Map<String, List<Map<String, Object>>> minutes(int minutes) {
        long now = System.currentTimeMillis() / 60_000;
        Map<String, List<Map<String, Object>>> out = new TreeMap<>();
        peers.forEach((peer, p) -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (long m = now - Math.min(minutes, MINUTES) + 1; m <= now; m++) {
                int slot = (int) (m % MINUTES);
                boolean here = p.minuteOf.get(slot) == m;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("minute", Instant.ofEpochSecond(m * 60).toString());
                long total = 0;
                for (int i = 0; i < OUTCOMES.size(); i++) {
                    long n = here ? p.counts.get(slot * OUTCOMES.size() + i) : 0;
                    row.put(OUTCOMES.get(i), n);
                    total += n;
                }
                row.put("avgMs", total == 0 ? 0.0 : Math.round(p.millis.get(slot) * 10.0 / total) / 10.0);
                rows.add(row);
            }
            out.put(peer, rows);
        });
        return out;
    }

    /** Forwarded calls and failures over the last {@code minutes} minutes, for Admin → Health. */
    public Map<String, Long> totals(int minutes) {
        long ok = 0;
        long bad = 0;
        for (List<Map<String, Object>> rows : minutes(minutes).values()) {
            for (Map<String, Object> r : rows) {
                ok += (Long) r.get("ok") + (Long) r.get("not-held");
                bad += (Long) r.get("refused") + (Long) r.get("failed") + (Long) r.get("timeout");
            }
        }
        return Map.of("forwarded", ok + bad, "failed", bad);
    }

    /** The peers by average time over the last ten minutes, slowest first. */
    public List<Map<String, Object>> slowest() {
        List<Map<String, Object>> out = new ArrayList<>();
        minutes(10).forEach((peer, rows) -> {
            long n = 0;
            double ms = 0;
            for (Map<String, Object> r : rows) {
                long c = 0;
                for (String o : OUTCOMES) {
                    c += (Long) r.get(o);
                }
                n += c;
                ms += (Double) r.get("avgMs") * c;
            }
            if (n > 0) {
                out.add(Map.of("peer", peer, "calls", n, "avgMs", Math.round(ms * 10 / n) / 10.0));
            }
        });
        out.sort(Comparator.comparing((Map<String, Object> m) -> (Double) m.get("avgMs")).reversed());
        return out;
    }
}
