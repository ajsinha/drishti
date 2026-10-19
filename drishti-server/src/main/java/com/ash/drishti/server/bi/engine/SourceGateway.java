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

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * How the engine reaches the data: the same connectors as the rest of Drishti, by their logical names (RUPAKA.md section 6.1). The server's
 * implementation goes through the connector registry and router; tests give it a small generated lake.
 */
public interface SourceGateway {

    /**
     * The connector for a table: the named one, or whichever running connector serves the kind when the name is blank.
     *
     * @throws com.ash.drishti.common.DrishtiException DRS-9026 when the named connector is not running
     */
    Optional<SourcePlugin> plugin(String connector, String kind);

    /**
     * The connectors a table reads: the named one, or, when the name is blank and the kind is served by a union of connectors (one
     * per source system, setting {@code union: true}), every one of them, their rows put together; else {@link #plugin}'s choice.
     */
    default List<SourcePlugin> plugins(String connector, String kind) {
        return plugin(connector, kind).map(List::of).orElse(List.of());
    }

    /**
     * The version of the data a table reads on a business date (null: the whole table), its connectors' {@link SourcePlugin#dataVersion}
     * joined, or null when a connector cannot tell: a stored layer built at another version is stale.
     */
    default String dataVersion(String connector, String kind, LocalDate date) {
        List<String> v = new java.util.ArrayList<>();
        for (SourcePlugin p : plugins(connector, kind)) {
            Optional<String> d = p.dataVersion(kind, date);
            if (d.isEmpty()) {
                return null;
            }
            v.add(p.manifest().name() + "=" + d.get());
        }
        return v.isEmpty() ? null : String.join(",", v);
    }

    /** Ids of the kind on the business date (null for the latest), at most {@code limit}; the slow path for sources that cannot scan columns. */
    List<EntityRef> ids(String kind, LocalDate date, int limit);

    /** The documents of these entities as of the date (null for the latest). */
    Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, LocalDate date);

    /**
     * Ids of the kind that the given connectors hold (a table that names its connector reads that connector only, never another that serves the same
     * kind); empty {@code plugins}: every connector, as {@link #ids(String, LocalDate, int)}. A gateway with one source (a test lake) need not override it.
     */
    default List<EntityRef> ids(List<SourcePlugin> plugins, String kind, LocalDate date, int limit) {
        return ids(kind, date, limit);
    }

    /** The documents of these entities from the given connectors only; empty {@code plugins}: from whichever holds each, as {@link #read(Collection, LocalDate)}. */
    default Map<EntityRef, EntityDocument> read(List<SourcePlugin> plugins, Collection<EntityRef> refs, LocalDate date) {
        return read(refs, date);
    }

    /**
     * Follows an entity's live updates: through the named connector when {@code connector} is not blank (a stream that keeps no state but ticks what a store
     * serves), else through whichever live source holds the entity (the router's choice, as a live view makes). {@link Subscription#NONE} when nothing pushes it.
     */
    default Subscription subscribe(String connector, EntityRef ref, Consumer<EntityDocument> listener) {
        return Subscription.NONE;
    }

    /** The current business date. */
    LocalDate currentDate();

    /** The business-day calendar of the pack (weekends only when a gateway has none): time-intelligence windows count business days by it. */
    default com.ash.drishti.common.BusinessCalendar calendar() {
        return com.ash.drishti.common.BusinessCalendar.of("WEEKENDS");
    }
}
