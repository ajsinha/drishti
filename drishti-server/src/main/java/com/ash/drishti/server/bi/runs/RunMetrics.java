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

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;

/** The metrics of BI runs (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 17.1), beside {@code BiMetrics}. */
public interface RunMetrics {

    default void submitted(String source) {}

    default void finished(String tier, String outcome, String code) {}

    default void inline(boolean inline) {}

    default void reuse(String mode) {}

    default void seconds(String tier, double seconds) {}

    /** A finished run's actual ÷ estimated seconds (how honest the estimates are, section 4.4). */
    default void estimateRatio(String basis, String engine, double ratio) {}

    /** The time from a cancel to every resource of the run let go (section 11). */
    default void cancelRelease(double seconds) {}

    RunMetrics NONE = new RunMetrics() {};

    /** The gauges of 17.1: {@code drishti.bi.runs.queue} (class), {@code drishti.bi.runs.running} (engine), {@code drishti.bi.runs.store.bytes} (kind). */
    static void gauges(MeterRegistry m, RunService runs) {
        if (m == null || runs == null) {
            return;
        }
        io.micrometer.core.instrument.Gauge.builder("drishti.bi.runs.queue", runs, r -> r.queued()).tag("class", "background").register(m);
        io.micrometer.core.instrument.Gauge.builder("drishti.bi.runs.running", runs, r -> r.running()).tag("engine", "local").register(m);
        io.micrometer.core.instrument.Gauge.builder("drishti.bi.runs.store.bytes", runs, r -> number(r.store().usage().get("bytes"))).tag("kind", "results")
                .register(m);
        io.micrometer.core.instrument.Gauge.builder("drishti.bi.runs.store.bytes", runs, r -> number(r.store().snapshotsUsage().get("bytes")))
                .tag("kind", "snapshots").register(m);
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** Micrometer counters and timers: {@code drishti.bi.runs.*}. */
    static RunMetrics of(MeterRegistry m) {
        if (m == null) {
            return NONE;
        }
        return new RunMetrics() {
            @Override
            public void submitted(String source) {
                m.counter("drishti.bi.runs.submitted", "source", source == null ? "" : source).increment();
            }

            @Override
            public void finished(String tier, String outcome, String code) {
                m.counter("drishti.bi.runs.finished", "tier", tier, "outcome", outcome, "code", code == null ? "" : code).increment();
            }

            @Override
            public void inline(boolean inline) {
                m.counter("drishti.bi.runs.inline", "result", inline ? "inline" : "job").increment();
            }

            @Override
            public void reuse(String mode) {
                m.counter("drishti.bi.runs.reuse", "mode", mode).increment();
            }

            @Override
            public void seconds(String tier, double seconds) {
                m.timer("drishti.bi.runs.seconds", "tier", tier, "engine", "local").record(Duration.ofNanos((long) (seconds * 1e9)));
            }

            @Override
            public void estimateRatio(String basis, String engine, double ratio) {
                io.micrometer.core.instrument.DistributionSummary.builder("drishti.bi.runs.estimate.ratio").tag("basis", basis).tag("engine", engine)
                        .register(m).record(ratio);
            }

            @Override
            public void cancelRelease(double seconds) {
                m.timer("drishti.bi.runs.cancel.release").record(Duration.ofNanos((long) (seconds * 1e9)));
            }
        };
    }
}
