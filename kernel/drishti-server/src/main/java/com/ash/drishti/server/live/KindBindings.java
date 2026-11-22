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
package com.ash.drishti.server.live;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What Drishti's bindings give a live table of one kind (LIVE_TABLES 6, "Drishti keeps"): the kind's columnar store when a lake keeps it
 * as columns (the table is then paged from it), else its documents read whole; records by id; the kind's {@code live:} binding; and why
 * that binding is paused. The server's implementation is over the source router; tests give their own.
 */
public interface KindBindings {

    /** A kind a lake keeps as columns: paged from it, its snapshot never held. */
    interface Store {

        /** The store's data version (a load moves it); empty when it cannot say. */
        String version();

        /** For every record: its id and the values of {@code fields}, in one pass; false when it cannot read so. */
        boolean project(List<String> fields, Projection sink) throws Exception;

        /** Whether {@link #project} gives this field (a lake column); others arrive null. */
        default boolean column(String field) {
            return true;
        }

        /** The records of these ids, whole; absent ones left out. */
        Map<String, DataNode> fetch(Collection<String> ids) throws Exception;
    }

    /** One projected record. */
    @FunctionalInterface
    interface Projection {
        void row(String id, Object[] values);
    }

    /**
     * The kind's columnar store at a business date (time travel through the lake's {@code asOf}), when it has one. A binding that knows
     * only the live day answers nothing for another.
     */
    default Optional<Store> store(String kind, com.ash.drishti.api.AsOf at) {
        return at == null || at.isLatest() ? store(kind) : Optional.empty();
    }

    /** Every record of the kind at a business date, to the sink; false when it cannot read that day. */
    default boolean documents(String kind, com.ash.drishti.api.AsOf at, Consumer<EntityDocument> sink) throws Exception {
        return (at == null || at.isLatest()) && documents(kind, sink);
    }

    /** The records of these ids at a business date (absent ones left out). */
    default Map<String, DataNode> fetch(String kind, Collection<String> ids, com.ash.drishti.api.AsOf at) throws Exception {
        return at == null || at.isLatest() ? fetch(kind, ids) : Map.of();
    }

    /** The kind's columnar store, when it has one. */
    Optional<Store> store(String kind);

    /** Every record of the kind now, to the sink; false when the kind has no reader that can do so. */
    boolean documents(String kind, Consumer<EntityDocument> sink) throws Exception;

    /** The records of these ids as the bindings fetch them (absent ones left out). */
    Map<String, DataNode> fetch(String kind, Collection<String> ids) throws Exception;

    /** Every change of the kind through its {@code live:} binding; closing the handle stops them. */
    AutoCloseable subscribe(String kind, Consumer<EntityDocument> listener);

    /** Where a read's failure is kept in the source's words, made safe (the protocol mount tells them with a 503); null keeps none. */
    default ReadFailures failures() {
        return null;
    }

    /**
     * The search titles of the kind's records (its sources' listing: a title that says more than the id), for a FORM result's Title
     * column; empty when the sources give none or cannot list the day.
     */
    default Map<String, String> titles(String kind, com.ash.drishti.api.AsOf at) throws Exception {
        return Map.of();
    }

    /** Why the kind's live binding is paused (its broker absent), one line per source; empty when it is up or has none. */
    default List<String> paused(String kind) {
        return List.of();
    }
}
