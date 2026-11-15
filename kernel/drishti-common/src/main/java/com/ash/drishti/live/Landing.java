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

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Where a landed kind's window goes (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 2.4): one batch appended to the kind's table in
 * the lake (Delta, Iceberg or the file store, through the server's lake writer), then the data-load signal. The server provides it; an
 * engine's own checks of the rows (a scenario set, a vector's length) run there and are reported back as rejects.
 */
public interface Landing {

    /**
     * One window of one kind and business date.
     *
     * @param connector the live connector it came from
     * @param kind      the kind
     * @param date      the business date the rows belong to
     * @param batchId   the batch's id, from its first and last transport positions: the same window written twice is one batch
     * @param rows      the newest row per key, in key order
     * @param settings  the connector's settings (the lake the window is written to: {@code lake}; the pack it is announced for: {@code pack})
     */
    record Batch(String connector, String kind, LocalDate date, String batchId, List<ObjectNode> rows, Map<String, String> settings) {}

    /**
     * What landing did.
     *
     * @param written  rows written to the lake
     * @param rejects  each rejected row: its key, its error code ({@code DRS-5201} ...) and why, in words
     * @param error    why nothing was written (the lake could not be written), or null
     */
    record Landed(long written, List<Reject> rejects, String error) {

        public Landed {
            rejects = rejects == null ? List.of() : List.copyOf(rejects);
        }
    }

    /** One rejected row of a batch. */
    record Reject(String key, String code, String why) {}

    /** Writes one batch; never throws for a row (a row that cannot be written is a reject). */
    Landed land(Batch batch);

    /** No lake to land in (a feed outside a server): every batch is said as not written. */
    Landing NONE = b -> new Landed(0, List.of(), "no lake writer on this process: the window of " + b.kind() + " was not landed");
}
