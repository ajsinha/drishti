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
package com.ash.drishti.server.bi.runs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rates the planner's estimate uses (docs/architecture/BI_RUNS.md sections 4.1 and 4.4), learned from finished runs and seeded from
 * {@code estimate.rates} in {@code config/runs/runs.yaml}:
 *
 * <ul>
 *   <li>the engine's scan rate (rows read per second), from runs that built nothing;
 *   <li>a connector's build rate per path ({@code connector/scan}, {@code connector/record}), from the time a run spent beyond its scan;
 *   <li>the actual ÷ estimated seconds ratios per engine (the last {@link #KEEP}), whose 10th and 90th percentiles replace the range factors once
 *       there are {@code minRatioSamples} of them.
 * </ul>
 *
 * Each learned rate is an exponentially weighted mean, so one odd run moves it a little. In memory; thread-safe.
 */
public final class RunRates {

    static final int KEEP = 200;
    static final double ALPHA = 0.2;

    private final PlannerSettings settings;
    private volatile double scan;
    private final Map<String, Double> build = new ConcurrentHashMap<>();
    private final Map<String, Deque<Double>> ratios = new ConcurrentHashMap<>();

    public RunRates(PlannerSettings settings) {
        this.settings = settings;
        this.scan = settings.scanRowsPerSecond();
    }

    /** The rates in force now. */
    public RunEstimator.Rates now() {
        return new RunEstimator.Rates(Map.copyOf(build), scan);
    }

    /** The range factors in force for an engine: the settings' until enough runs measured them (section 4.4). */
    public double[] factors(String engine) {
        Deque<Double> r = ratios.get(engine);
        List<Double> copy;
        if (r == null) {
            copy = List.of();
        } else {
            synchronized (r) {
                copy = new ArrayList<>(r);
            }
        }
        return RunEstimator.factors(copy, settings);
    }

    /** The measured actual ÷ estimated ratios of an engine (Admin's accuracy, the metric). */
    public List<Double> ratios(String engine) {
        Deque<Double> r = ratios.get(engine);
        if (r == null) {
            return List.of();
        }
        synchronized (r) {
            return List.copyOf(r);
        }
    }

    /**
     * A finished run: what it was estimated to read and build, and how long it took. Returns the actual ÷ estimated ratio (the middle of the range),
     * or null when the estimate had no seconds.
     *
     * @param connectorPath the {@code connector/path} of the table it built most rows of; null when it built nothing
     */
    public Double finished(String engine, RunEstimate e, double seconds, String connectorPath) {
        if (e == null || !e.known() || seconds <= 0) {
            return null;
        }
        long read = e.readRows() == null ? 0 : e.readRows();
        long built = e.buildRows() == null ? 0 : e.buildRows();
        double work = Math.max(seconds - settings.overheadSeconds(), 1e-3);
        if (built == 0 && read > 0) {
            scan = ewma(scan, read / work);
        } else if (built > 0 && connectorPath != null) {
            double buildSeconds = Math.max(work - read / scan, 1e-3);
            build.merge(connectorPath, built / buildSeconds, (old, now) -> ewma(old, now));
        }
        Double ratio = null;
        if (e.secondsLow() != null && e.secondsHigh() != null) {
            double mid = (e.secondsLow() + e.secondsHigh()) / 2 / ((settings.lowFactor() + settings.highFactor()) / 2);
            if (mid > 0) {
                ratio = seconds / mid;
                Deque<Double> r = ratios.computeIfAbsent(engine, k -> new ArrayDeque<>());
                synchronized (r) {
                    r.addLast(ratio);
                    while (r.size() > KEEP) {
                        r.removeFirst();
                    }
                }
            }
        }
        return ratio;
    }

    private static double ewma(double old, double now) {
        return old + ALPHA * (now - old);
    }
}
