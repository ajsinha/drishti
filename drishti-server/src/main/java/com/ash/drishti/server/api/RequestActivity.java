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
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The server's activity minute by minute, for Admin -> Health: every API request counted in its minute with its time, for the
 * last hour (a ring of minute buckets, lock-free), so the page can draw requests per minute and the average and slowest response
 * time of each of the last 30 minutes.
 */
public final class RequestActivity extends OncePerRequestFilter {

    static final int MINUTES = 60;
    private final AtomicLongArray minuteOf = new AtomicLongArray(MINUTES);
    private final AtomicLongArray count = new AtomicLongArray(MINUTES);
    private final AtomicLongArray totalMicros = new AtomicLongArray(MINUTES);
    private final AtomicLongArray maxMicros = new AtomicLongArray(MINUTES);
    private final AtomicLongArray errors = new AtomicLongArray(MINUTES);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            record(System.currentTimeMillis() / 60_000, (System.nanoTime() - started) / 1_000, res.getStatus() >= 500);
        }
    }

    void record(long minute, long micros, boolean error) {
        int slot = (int) (minute % MINUTES);
        long had = minuteOf.get(slot);
        if (had > minute) {
            return;                                               // a request from an hour ago or more: its minute is gone
        }
        if (had < minute && minuteOf.compareAndSet(slot, had, minute)) {
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
