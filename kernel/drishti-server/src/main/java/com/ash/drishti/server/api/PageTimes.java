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

import com.ash.drishti.engine.live.LiveMetrics;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * How long each kind's pages take to build, for Admin -> Health: per kind (any kind of any pack, grouped pages included) the
 * views and their p50, p95 and p99 over a rolling ten minutes, so a screen that grows slow as the data grows shows itself.
 */
@Component
public class PageTimes {

    private record Kind(LiveMetrics times, AtomicLong views) {}

    private static final int MAX_KINDS = 500;
    private final Map<String, Kind> byKind = new ConcurrentHashMap<>();

    public void record(String kind, long nanos) {
        Kind k = byKind.get(kind);
        if (k == null) {
            if (byKind.size() >= MAX_KINDS) {
                return;                                        // a bound: kinds come from packs, not from callers
            }
            k = byKind.computeIfAbsent(kind, x -> new Kind(new LiveMetrics(Duration.ofMinutes(10)), new AtomicLong()));
        }
        k.times().record(nanos / 1e6);
        k.views().incrementAndGet();
    }

    /** Every kind viewed since start, slowest p95 first. */
    public List<Map<String, Object>> summary() {
        return byKind.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.getKey());
            m.put("views", e.getValue().views().get());
            m.put("p50Ms", round(e.getValue().times().percentile(50)));
            m.put("p95Ms", round(e.getValue().times().percentile(95)));
            m.put("p99Ms", round(e.getValue().times().percentile(99)));
            return m;
        }).sorted(Comparator.comparingDouble((Map<String, Object> m) -> -(double) m.get("p95Ms"))).toList();
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
