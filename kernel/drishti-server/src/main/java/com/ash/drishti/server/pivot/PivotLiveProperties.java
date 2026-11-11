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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.live.LivePivots;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Live pivots ({@code drishti.pivot.live}, config/engines/pivot.yaml; PIVOT_GUIDE.md, live pivots).
 *
 * @param enabled false: no live pivots on this node (the Live switch is not offered)
 * @param maxPerPerson live views one person may hold
 * @param maxTotal live views this node holds
 * @param minIntervalMs the least time between two recomputes of one view; signals in between are coalesced
 * @param maxCells the most visible cells a live view keeps current
 * @param maxRows the most rows an exact live recompute may read (estimated); a larger view is live only when it asked ≈ (0: no limit)
 * @param idleSeconds a live view no stream reads is ended after this
 * @param heartbeatSeconds a {@code status} event to every stream this often (also how often idle views are ended)
 */
@ConfigurationProperties("drishti.pivot.live")
public record PivotLiveProperties(Boolean enabled, Integer maxPerPerson, Integer maxTotal, Long minIntervalMs, Integer maxCells, Long maxRows,
        Integer idleSeconds, Integer heartbeatSeconds) {

    public LivePivots.Settings settings() {
        LivePivots.Settings d = LivePivots.Settings.defaults();
        return new LivePivots.Settings(enabled == null || enabled, pos(maxPerPerson, d.maxPerPerson()), pos(maxTotal, d.maxTotal()),
                minIntervalMs == null ? d.minIntervalMs() : Math.max(0, minIntervalMs), pos(maxCells, d.maxCells()),
                maxRows == null ? d.maxRows() : Math.max(0, maxRows), pos(idleSeconds, d.idleSeconds()));
    }

    public int heartbeat() {
        return pos(heartbeatSeconds, 15);
    }

    private static int pos(Integer v, int d) {
        return v == null || v <= 0 ? d : v;
    }
}
