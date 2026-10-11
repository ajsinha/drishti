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
package com.ash.drishti.server.bi.engine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Streaming BI, {@code drishti.bi.live.*} (docs/admin/CONFIGURATION.md): the live tables kept in memory by the engine and the streams that tell browsers
 * they changed. Every bound has a default and can be changed without a build.
 *
 * @param enabled false: a live table is the short-lived snapshot of phase 2 ({@code drishti.bi.query.live-ttl}), nothing is kept or streamed
 * @param maxRows most rows one live table holds (the oldest updated are evicted past it); a table's own {@code live.rows} may be lower
 * @param batch how often the rows that arrived are applied to a live table (updates of one entity in the interval are merged)
 * @param frame the shortest time between two {@code tick} events to one browser (coalescing: a browser gets the latest state, never a queue)
 * @param status how often a stream says how its tables are doing when nothing changed (also its heartbeat)
 * @param followMax most entities one live table follows; past it the feed stops adding and warns
 * @param resync how often the feed lists the kind again (new entities are added and followed; connectors that cannot push are read again)
 * @param idleClose a live table nobody has queried or streamed for this long is closed and its subscriptions given back
 * @param staleAfter a feed that pushes but has delivered nothing for this long is shown as stale
 * @param maxStreams live streams open at once on the server
 * @param maxStreamsPerUser live streams open at once for one user
 * @param fetchParallel documents read at once when a live table is first loaded
 */
@ConfigurationProperties("drishti.bi.live")
public record BiLiveProperties(Boolean enabled, Integer maxRows, Duration batch, Duration frame, Duration status, Integer followMax, Duration resync, Duration idleClose,
        Duration staleAfter, Integer maxStreams, Integer maxStreamsPerUser, Integer fetchParallel) {

    public BiLiveProperties {
        enabled = enabled == null || enabled;
        maxRows = maxRows == null || maxRows <= 0 ? 50_000 : maxRows;
        batch = positive(batch, Duration.ofMillis(100));
        frame = positive(frame, Duration.ofMillis(250));
        status = positive(status, Duration.ofSeconds(5));
        followMax = followMax == null || followMax <= 0 ? 2_000 : followMax;
        resync = positive(resync, Duration.ofSeconds(30));
        idleClose = positive(idleClose, Duration.ofMinutes(10));
        staleAfter = positive(staleAfter, Duration.ofSeconds(30));
        maxStreams = maxStreams == null || maxStreams <= 0 ? 100 : maxStreams;
        maxStreamsPerUser = maxStreamsPerUser == null || maxStreamsPerUser <= 0 ? 10 : maxStreamsPerUser;
        fetchParallel = fetchParallel == null || fetchParallel <= 0 ? 16 : fetchParallel;
    }

    private static Duration positive(Duration d, Duration dflt) {
        return d == null || d.isNegative() || d.isZero() ? dflt : d;
    }

    /** Defaults for tests and embedded use. */
    public static BiLiveProperties defaults() {
        return new BiLiveProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The same settings with a quicker batch, frame and resync (tests). */
    public BiLiveProperties fast(Duration batchAndFrame, Duration resyncEvery) {
        return new BiLiveProperties(enabled, maxRows, batchAndFrame, batchAndFrame, status, followMax, resyncEvery, idleClose, staleAfter, maxStreams, maxStreamsPerUser, fetchParallel);
    }
}
