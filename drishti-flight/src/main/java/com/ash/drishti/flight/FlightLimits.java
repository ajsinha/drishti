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
package com.ash.drishti.flight;

import java.time.Duration;

/**
 * What one Flight request may take (docs/architecture/DATA_ARCHITECTURE.md section 12 Q8): rows and bytes sent, the time it runs, the Arrow memory it holds
 * at once. A live stream's time limit is its idle limit per batch, not its whole life (a subscription runs all day). Zero or less: no limit.
 */
public record FlightLimits(long rows, long bytes, Duration time, long memory) {

    /** The shipped default: 50 million rows, 8 GB, 10 minutes, 512 MB of Arrow memory. */
    public static final FlightLimits DEFAULT = new FlightLimits(50_000_000L, 8L << 30, Duration.ofMinutes(10), 512L << 20);

    public FlightLimits {
        time = time == null ? Duration.ZERO : time;
    }

    /** Each limit the larger of the two (no limit wins). */
    public FlightLimits max(FlightLimits o) {
        return new FlightLimits(big(rows, o.rows), big(bytes, o.bytes),
                time.isZero() || o.time.isZero() ? Duration.ZERO : (time.compareTo(o.time) >= 0 ? time : o.time), big(memory, o.memory));
    }

    public FlightLimits withMemory(long m) {
        return new FlightLimits(rows, bytes, time, m);
    }

    private static long big(long a, long b) {
        return a <= 0 || b <= 0 ? 0 : Math.max(a, b);
    }
}
