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
package com.ash.drishti.api.lifecycle;

import java.time.Duration;
import java.time.Instant;

/**
 * What a module, pack or connector has done (docs/architecture/LIFECYCLE.md section 7): counters since it started and over the recent
 * window, latency percentiles over the window, the queue depth and the last activity. A snapshot: cheap, never blocks. Fields a part does
 * not count are zero.
 *
 * @param since when the counting began (the part's start; a resume does not reset it)
 * @param total the counters since {@code since}
 * @param window the counters over the last {@code windowLength}
 * @param windowLength the recent window ({@link StatsRecorder#WINDOW}, five minutes)
 * @param latency request latency over the window
 * @param queueDepth requests waiting now (in flight for a pack or a connector; a live connector's backlog where it knows one)
 * @param inFlight requests running now
 * @param lastActivity the end of the last request or pull, or null when none yet
 */
public record ComponentStats(Instant since, Counts total, Counts window, Duration windowLength, Latency latency, long queueDepth, long inFlight,
        Instant lastActivity) {

    /**
     * The counters.
     *
     * @param requests requests answered (a read, a mnemonic opened, a pull of a live connector counts once per batch)
     * @param errors requests that failed
     * @param refusals requests refused (suspended, not running, not allowed)
     * @param rowsRead rows (documents, records) read
     * @param rowsWritten rows written
     * @param bytes bytes read or received
     * @param cacheHits answers served from a cache
     * @param cacheMisses answers a cache did not hold
     */
    public record Counts(long requests, long errors, long refusals, long rowsRead, long rowsWritten, long bytes, long cacheHits, long cacheMisses) {
        public static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0, 0, 0);

        public Counts plus(Counts o) {
            return new Counts(requests + o.requests, errors + o.errors, refusals + o.refusals, rowsRead + o.rowsRead, rowsWritten + o.rowsWritten,
                    bytes + o.bytes, cacheHits + o.cacheHits, cacheMisses + o.cacheMisses);
        }
    }

    /**
     * Latency percentiles in milliseconds over the window (from at most {@link StatsRecorder#SAMPLES} recent requests); all zero with no samples.
     *
     * @param samples how many requests the percentiles are taken from
     */
    public record Latency(int samples, double p50, double p95, double p99, double max) {
        public static final Latency NONE = new Latency(0, 0, 0, 0, 0);
    }

    /** No activity (a part that is not started). */
    public static ComponentStats empty(Instant since) {
        return new ComponentStats(since, Counts.ZERO, Counts.ZERO, StatsRecorder.WINDOW, Latency.NONE, 0, 0, null);
    }
}
