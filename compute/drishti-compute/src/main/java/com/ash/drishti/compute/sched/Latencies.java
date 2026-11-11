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
package com.ash.drishti.compute.sched;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** The last {@code size} durations of something, in milliseconds, with their percentiles (Admin → Compute, the load test). */
public final class Latencies {

    private final long[] ring;
    private int next;
    private int count;
    private long total;

    public Latencies(int size) {
        ring = new long[size];
    }

    public synchronized void add(long ms) {
        ring[next] = ms;
        next = (next + 1) % ring.length;
        count = Math.min(count + 1, ring.length);
        total++;
    }

    /** The p-th percentile (0..100) of what is kept, or 0 when nothing is. */
    public synchronized long percentile(double p) {
        if (count == 0) {
            return 0;
        }
        long[] s = Arrays.copyOf(ring, count);
        Arrays.sort(s);
        int i = (int) Math.ceil(p / 100.0 * count) - 1;
        return s[Math.max(0, Math.min(count - 1, i))];
    }

    public synchronized long total() {
        return total;
    }

    /** {@code p50}, {@code p95}, {@code p99} and {@code max} in milliseconds, and how many samples there were. */
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("p50", percentile(50));
        m.put("p95", percentile(95));
        m.put("p99", percentile(99));
        m.put("max", percentile(100));
        m.put("samples", total());
        return m;
    }
}
