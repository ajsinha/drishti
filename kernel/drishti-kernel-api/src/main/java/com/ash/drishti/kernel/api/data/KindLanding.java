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
package com.ash.drishti.kernel.api.data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An engine's landing of its landed kinds (docs/architecture/LIVE_CONNECTORS_DESIGN.md sections 2.4 and 7.5): the kernel's live lander
 * collects a window of a kind whose contract says {@code live.apply: land} (the newest row per key) and hands it here, one batch per kind
 * and business date; the engine checks the rows by its own rules, writes them to the lake as one batch and announces the load. A module
 * provides one as a bean; what it rejects is reported in the live connector's reject log, never on a page.
 */
public interface KindLanding {

    /** The kinds this landing takes ({@code scenario-pv}, {@code pnl-vector}). */
    Set<String> kinds();

    /** Lands one batch; a row it refuses is a reject in the answer, never an exception. */
    Landed land(Batch batch);

    /**
     * One window of one kind and business date.
     *
     * @param connector the live connector it came from
     * @param kind      the kind
     * @param date      the business date its rows belong to
     * @param batchId   its id, from its first and last transport positions (the same window landed twice is one batch)
     * @param rows      the newest row per key, each as JSON text, in key order
     * @param settings  the connector's settings ({@code lake}: the lake it is written to; {@code pack}: the pack it is announced for)
     */
    record Batch(String connector, String kind, LocalDate date, String batchId, List<String> rows, Map<String, String> settings) {}

    /**
     * What landing did.
     *
     * @param written rows written to the lake
     * @param rejects each refused row
     * @param error   why nothing was written (the lake could not be written), or null
     */
    record Landed(long written, List<Rejected> rejects, String error) {

        public Landed {
            rejects = rejects == null ? List.of() : List.copyOf(rejects);
        }
    }

    /** One refused row: its key, its error code ({@code DRS-5201} ...) and why, in words. */
    record Rejected(String key, String code, String why) {}
}
