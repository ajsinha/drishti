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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The failing things of one area, by name, each with a {@link RetryTracker}. Logging is rate limited: failures number
 * 1, 2, 4, 8, ... are logged (a thing that fails for an hour does not write a line every few seconds), and a recovery is
 * always logged. A {@link Listener} gets every event, for the audit log. Usable by any area that keeps something running
 * from files (packs, connectors, AI providers, MCP servers, BI artifacts): call {@link #failed} and {@link #succeeded} as
 * attempts end, and ask {@link #due} on a timer.
 */
public final class RetryBoard {

    /** Called outside any lock, on the thread that reported the event. */
    public interface Listener {
        /** Every failure; {@code logged} is true when the log line was written (a listener may rate limit the same way). */
        void failed(String area, String key, RetryTracker.Status status, boolean logged);

        void recovered(String area, String key, int failures, String lastError);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RetryBoard.class);

    private final String area;
    private final RetryPolicy policy;
    private final Clock clock;
    private final Map<String, RetryTracker> trackers = new ConcurrentHashMap<>();
    private volatile Listener listener;

    public RetryBoard(String area, RetryPolicy policy, Clock clock) {
        this.area = area;
        this.policy = policy;
        this.clock = clock;
    }

    public RetryBoard(String area, RetryPolicy policy) {
        this(area, policy, Clock.systemUTC());
    }

    public RetryPolicy policy() {
        return policy;
    }

    public String area() {
        return area;
    }

    public void listener(Listener l) {
        this.listener = l;
    }

    /** Records that {@code key} failed and plans its next retry. */
    public RetryTracker.Status failed(String key, String error, boolean permanentHint) {
        RetryTracker t = trackers.computeIfAbsent(key, k -> new RetryTracker(policy, clock, () -> RandomGenerator.getDefault().nextDouble()));
        RetryTracker.Status s = t.failed(error, permanentHint);
        boolean log = Integer.bitCount(s.attempt()) == 1;
        if (log) {
            LOG.warn("{} '{}' failed ({}): {}; {}", area, key, s.attempt() == 1 ? "first time" : "attempt " + s.attempt(), error,
                    s.nextAt() == null ? "retrying is off" : (s.permanent() ? "looks permanent, next try " : "next try ") + s.nextAt());
        }
        Listener l = listener;
        if (l != null) {
            l.failed(area, key, s, log);
        }
        return s;
    }

    /** Records that {@code key} works now. Returns true when it had been failing. */
    public boolean succeeded(String key) {
        RetryTracker t = trackers.remove(key);
        if (t == null) {
            return false;
        }
        String last = t.status().lastError();
        int n = t.succeeded();
        if (n == 0) {
            return false;
        }
        LOG.info("{} '{}' recovered after {} failed attempt(s); it was: {}", area, key, n, last);
        Listener l = listener;
        if (l != null) {
            l.recovered(area, key, n, last);
        }
        return true;
    }

    /** Stops tracking {@code key} without a recovery (it was removed or switched off). */
    public void forget(String key) {
        trackers.remove(key);
    }

    /** Makes {@code key} due now. Returns false when it is not failing. */
    public boolean retryNow(String key) {
        RetryTracker t = trackers.get(key);
        if (t == null) {
            return false;
        }
        t.retryNow();
        return true;
    }

    /** Makes everything failing due now; returns how many there are. */
    public int retryAllNow() {
        trackers.values().forEach(RetryTracker::retryNow);
        return trackers.size();
    }

    /** The keys whose next retry time has come. */
    public List<String> due() {
        List<String> out = new ArrayList<>();
        trackers.forEach((k, t) -> {
            if (t.due()) {
                out.add(k);
            }
        });
        return out;
    }

    public Optional<RetryTracker.Status> status(String key) {
        RetryTracker t = trackers.get(key);
        return t == null ? Optional.empty() : Optional.of(t.status());
    }

    public boolean failing(String key) {
        return trackers.containsKey(key);
    }

    public Map<String, RetryTracker.Status> statuses() {
        Map<String, RetryTracker.Status> out = new TreeMap<>();
        trackers.forEach((k, t) -> out.put(k, t.status()));
        return out;
    }

    /** A map for JSON: attempt, next retry in seconds (-1 when none), last error, permanent. */
    public static Map<String, Object> describe(RetryTracker.Status s, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempt", s.attempt());
        m.put("nextRetryInSeconds", s.nextInSeconds(now));
        m.put("nextRetryAt", s.nextAt() == null ? null : s.nextAt().toString());
        m.put("lastError", s.lastError());
        m.put("permanent", s.permanent());
        m.put("failingSince", s.since() == null ? null : s.since().toString());
        return m;
    }
}
