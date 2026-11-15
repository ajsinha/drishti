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
package com.ash.drishti.live;

import java.util.Map;

/**
 * Where the live intake's checkpoints are kept (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 6): the Drishti database, in the tables
 * {@code drishti_live_checkpoint} and {@code drishti_live_position} (the server's {@code JdbcLiveCheckpointStore}), so a node that takes a
 * feed over reads what the last owner recorded. The kernel's module knows only this contract.
 */
public interface LiveCheckpointStore {

    /** No store: checkpoints are kept in memory only (a feed built without a server, a test). */
    LiveCheckpointStore NONE = new LiveCheckpointStore() {
        @Override
        public Map<String, LiveCheckpoints.Kind> load(String connector) {
            return Map.of();
        }

        @Override
        public void save(String connector, String kind, LiveCheckpoints.Kind checkpoint) {
            // nothing is kept
        }
    };

    /** Every kind's checkpoint of {@code connector}, by kind; empty when it has none. Read when the feed starts. */
    Map<String, LiveCheckpoints.Kind> load(String connector);

    /**
     * Records {@code kind}'s checkpoint of {@code connector}. A checkpoint only moves forward: a cut-off or an offset older than the one
     * kept is left as it is, so a stale or duplicate write cannot bring back what a load absorbed.
     */
    void save(String connector, String kind, LiveCheckpoints.Kind checkpoint);
}
