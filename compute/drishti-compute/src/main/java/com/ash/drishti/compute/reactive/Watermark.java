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
package com.ash.drishti.compute.reactive;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a result includes (DATA_ARCHITECTURE.md › Reactive connectors, section 8.3): per lane of the edit log or partition of a feed, the highest sequence its
 * inputs reflect ({@code L17 -> 3412}, {@code p3 -> 182277}), and the order in which its computation started. A result is newer than
 * another when every lane is at least as high and one is higher; with the same lanes, the one started later is newer. A late, older
 * result is never shown over a newer one.
 *
 * @param lanes lane or partition to sequence
 * @param started the order in which the computation started on this node (monotonic)
 */
public record Watermark(Map<String, Long> lanes, long started) {

    public static final Watermark NONE = new Watermark(Map.of(), 0);

    public Watermark {
        lanes = lanes == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(lanes));
    }

    /** This watermark with each lane raised to at least the other's. */
    public Watermark merge(Map<String, Long> other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        Map<String, Long> m = new TreeMap<>(lanes);
        other.forEach((k, v) -> m.merge(k, v, Math::max));
        return new Watermark(m, started);
    }

    public Watermark startedAt(long seq) {
        return new Watermark(lanes, seq);
    }

    /** True when this result must not replace {@code current}: its lanes are all at most current's, and lower somewhere or started earlier. */
    public boolean olderThan(Watermark current) {
        if (current == null) {
            return false;
        }
        boolean lower = false;
        for (Map.Entry<String, Long> e : current.lanes.entrySet()) {
            long mine = lanes.getOrDefault(e.getKey(), Long.MIN_VALUE);
            if (mine < e.getValue()) {
                lower = true;
            }
        }
        for (Map.Entry<String, Long> e : lanes.entrySet()) {
            if (e.getValue() > current.lanes.getOrDefault(e.getKey(), Long.MIN_VALUE)) {
                return false;                                   // this one includes something the current does not
            }
        }
        return lower || started < current.started;
    }

    /** {@code L17:3412, p3:182277}, for stamps and words. */
    public String words() {
        StringBuilder b = new StringBuilder();
        lanes.forEach((k, v) -> b.append(b.isEmpty() ? "" : ", ").append(k).append(':').append(v));
        return b.toString();
    }
}
