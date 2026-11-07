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
package com.ash.drishti.bicompute.scale;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Scan estimates refined by measurement (ARCHITECTURE.md section 23.9.7): the provider's estimate counts rows and bytes from metadata before
 * anything is read; the seconds come from this node's <b>measured</b> scan rate (rows a second over its recent scans, per provider: the
 * median of the last 20 scans that took over 200 ms), divided by the nodes that will share the scan, and always given as a range (half to
 * twice) with how it was made. The answer lists the person's options: run, narrow, a covering rollup (added by the rollup hook), in the
 * background, not now.
 */
public final class ScanRates implements PivotCore.Hooks {

    private static final int KEEP = 20;
    private static final long MIN_MS = 200;

    private final Map<String, Deque<Double>> rates = new ConcurrentHashMap<>();
    private final IntSupplier nodes;

    /** @param nodes the nodes a scan is shared by now (1 alone; the live members running the pivot engine in a cluster) */
    public ScanRates(IntSupplier nodes) {
        this.nodes = nodes == null ? () -> 1 : nodes;
    }

    /** A measured rate (rows a second) for a provider, recorded. */
    public void record(String provider, double rowsPerSecond) {
        if (!(rowsPerSecond > 0) || Double.isInfinite(rowsPerSecond)) {
            return;
        }
        Deque<Double> d = rates.computeIfAbsent(provider, k -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(rowsPerSecond);
            while (d.size() > KEEP) {
                d.removeFirst();
            }
        }
    }

    /** The median measured rate of a provider (rows a second), or -1 when it has none yet. */
    public double rate(String provider) {
        Deque<Double> d = rates.get(provider);
        if (d == null) {
            return -1;
        }
        List<Double> l;
        synchronized (d) {
            l = new ArrayList<>(d);
        }
        if (l.isEmpty()) {
            return -1;
        }
        l.sort(Double::compare);
        return l.get(l.size() / 2);
    }

    public int samples(String provider) {
        Deque<Double> d = rates.get(provider);
        return d == null ? 0 : d.size();
    }

    @Override
    public void ran(Viewer v, PivotProvider p, AggregationPlan plan, Partials parts, long millis) {
        if (millis < MIN_MS || plan.shard() != null) {
            return;
        }
        long rows = parts.stats().rowsRead();
        if (rows < 0) {
            rows = p.estimate(v, plan).rows();
        }
        if (rows > 0) {
            record(p.id(), rows * 1000.0 / millis);
        }
    }

    @Override
    public void estimated(Viewer v, PivotProvider p, AggregationPlan plan, Map<String, Object> m) {
        long rows = m.get("rows") instanceof Number n ? n.longValue() : -1;
        int k = Math.max(1, nodes.getAsInt());
        double rate = rate(p.id());
        if (rows >= 0 && rate > 0) {
            double secs = rows / rate / k;
            m.put("seconds", secs);
            m.put("how", m.get("how") + String.format(Locale.ROOT, "; seconds from this node's measured rate, ≈%,.0f rows a second (median of "
                    + "its last %d scans)%s", rate, samples(p.id()), k > 1 ? ", shared by " + k + " nodes" : ""));
        }
        double secs = m.get("seconds") instanceof Number n ? n.doubleValue() : 0;
        m.put("range", List.of(secs / 2, secs * 2));
        m.put("nodes", k);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = m.get("options") instanceof List<?> l ? (List<Map<String, Object>>) l : new ArrayList<>();
        options.add(0, option("run", "Run (≈" + time(secs) + (k > 1 ? " on " + k + " nodes" : "") + ")", secs));
        options.add(option("narrow", "Narrow it: a filter on the date or a desk reads less", -1));
        options.add(option("background", "Run in the background: you are told when it is ready", secs));
        options.add(option("cancel", "Not now", 0));
        m.put("options", options);
    }

    static Map<String, Object> option(String id, String words, double seconds) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("words", words);
        if (seconds >= 0) {
            o.put("seconds", seconds);
        }
        return o;
    }

    static String time(double secs) {
        if (secs < 1) {
            return "under a second";
        }
        if (secs < 90) {
            return String.format(Locale.ROOT, "%.0f s", secs);
        }
        if (secs < 5400) {
            return String.format(Locale.ROOT, "%.0f min", secs / 60);
        }
        return String.format(Locale.ROOT, "%.1f h", secs / 3600);
    }
}
