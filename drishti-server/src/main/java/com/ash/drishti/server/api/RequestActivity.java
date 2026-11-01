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
package com.ash.drishti.server.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The server's activity minute by minute, for Admin -> Health: every API request counted in its minute with its time, for the
 * last hour (a ring of minute buckets, lock-free), so the page can draw requests per minute and the average and slowest response
 * time of each of the last 30 minutes. Also, for Admin -> Capacity: the people who asked something in the last minutes (the signed-in user of
 * each request, a bounded map of when each was last seen) and today's busiest minute (UTC days), with the most people seen in one day.
 */
public final class RequestActivity extends OncePerRequestFilter {

    static final int MINUTES = 60;
    private final AtomicLongArray minuteOf = new AtomicLongArray(MINUTES);
    private final AtomicLongArray count = new AtomicLongArray(MINUTES);
    private final AtomicLongArray totalMicros = new AtomicLongArray(MINUTES);
    private final AtomicLongArray maxMicros = new AtomicLongArray(MINUTES);
    private final AtomicLongArray errors = new AtomicLongArray(MINUTES);
    /** At most this many people remembered (the oldest seen dropped first). */
    static final int MAX_USERS = 10_000;
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final AtomicLong peakDay = new AtomicLong(-1);
    private final AtomicLong peakMinuteRequests = new AtomicLong();
    private final AtomicLong peakUsers = new AtomicLong();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            record(System.currentTimeMillis() / 60_000, (System.nanoTime() - started) / 1_000, res.getStatus() >= 500);
            if (req.getAttribute(com.ash.drishti.server.security.Principal.ATTRIBUTE) instanceof com.ash.drishti.server.security.Principal p
                    && p.user() != null && !p.roles().contains("service")) {
                seen(p.user(), System.currentTimeMillis());
            }
        }
    }

    void record(long minute, long micros, boolean error) {
        int slot = (int) (minute % MINUTES);
        long had = minuteOf.get(slot);
        if (had > minute) {
            return;                                               // a request from an hour ago or more: its minute is gone
        }
        if (had < minute && minuteOf.compareAndSet(slot, had, minute)) {
            int prev = (int) ((minute - 1) % MINUTES);
            if (minuteOf.get(prev) == minute - 1) {
                peak(minute - 1, count.get(prev));                // the minute before is complete: today's busiest so far?
            }
            count.set(slot, 0);                                   // a new minute in this slot: what it held is an hour old
            totalMicros.set(slot, 0);
            maxMicros.set(slot, 0);
            errors.set(slot, 0);
        }
        count.incrementAndGet(slot);
        totalMicros.addAndGet(slot, micros);
        maxMicros.accumulateAndGet(slot, micros, Math::max);
        if (error) {
            errors.incrementAndGet(slot);
        }
    }

    /** A person asked something at {@code atMillis}. */
    public void seen(String user, long atMillis) {
        lastSeen.put(user, atMillis);
        if (lastSeen.size() > MAX_USERS) {
            lastSeen.entrySet().stream().min(Map.Entry.comparingByValue()).ifPresent(e -> lastSeen.remove(e.getKey(), e.getValue()));
        }
    }

    /** The people who asked something in the last {@code minutes} minutes. */
    public int usersActive(int minutes) {
        long since = System.currentTimeMillis() - minutes * 60_000L;
        int n = (int) lastSeen.values().stream().filter(t -> t >= since).count();
        peakUsersToday(n);
        return n;
    }

    private void peakUsersToday(int n) {
        long today = System.currentTimeMillis() / 86_400_000L;
        rollDay(today);
        peakUsers.accumulateAndGet(n, Math::max);
    }

    /** The most people active at once today (as counted when asked), at least those active now. */
    public long usersPeakToday() {
        usersActive(5);
        return peakUsers.get();
    }

    private void peak(long minute, long requests) {
        long day = minute / 1_440;
        if (day < System.currentTimeMillis() / 86_400_000L) {
            return;                                               // yesterday's last minute
        }
        rollDay(day);
        peakMinuteRequests.accumulateAndGet(requests, Math::max);
    }

    private void rollDay(long day) {
        long had = peakDay.get();
        if (had < day && peakDay.compareAndSet(had, day)) {
            peakMinuteRequests.set(0);
            peakUsers.set(0);
        }
    }

    /** Requests a second in today's busiest complete minute (UTC), or in the minute still filling when it is busier. */
    public double requestsPerSecondPeakToday() {
        long now = System.currentTimeMillis() / 60_000;
        rollDay(now / 1_440);
        long best = peakMinuteRequests.get();
        for (Map<String, Object> m : lastMinutes(MINUTES)) {
            if (java.time.Instant.parse((String) m.get("minute")).getEpochSecond() / 86_400 == now / 1_440) {
                best = Math.max(best, ((Number) m.get("requests")).longValue());
            }
        }
        return Math.round(best / 6.0) / 10.0;
    }

    /** Requests a second over the last complete minute. */
    public double requestsPerSecondNow() {
        List<Map<String, Object>> two = lastMinutes(2);
        return Math.round(((Number) two.get(0).get("requests")).longValue() / 6.0) / 10.0;
    }

    /** The last {@code minutes} minutes, oldest first, the current (still filling) minute last; a minute with no request is zero. */
    public List<Map<String, Object>> lastMinutes(int minutes) {
        long now = System.currentTimeMillis() / 60_000;
        List<Map<String, Object>> out = new ArrayList<>();
        for (long m = now - Math.min(minutes, MINUTES) + 1; m <= now; m++) {
            int slot = (int) (m % MINUTES);
            boolean here = minuteOf.get(slot) == m;
            long n = here ? count.get(slot) : 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("minute", Instant.ofEpochSecond(m * 60).toString());
            row.put("requests", n);
            row.put("avgMs", n == 0 ? 0.0 : Math.round(totalMicros.get(slot) / (double) n / 100) / 10.0);
            row.put("maxMs", here ? Math.round(maxMicros.get(slot) / 100.0) / 10.0 : 0.0);
            row.put("errors", here ? errors.get(slot) : 0);
            out.add(row);
        }
        return out;
    }
}
