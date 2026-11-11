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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.compute.ComputeService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * What the server gives reactive connectors (DATA_ARCHITECTURE.md › Reactive connectors, section 6.2): who the current read is for, whether they may have a
 * computed kind, the inputs read through the normal routing <em>as that viewer</em> (masks applied), the data versions a pin is made of,
 * the compute workers, audit and schedules. The server installs it with {@link ReactiveHub#host}; tests install their own.
 */
public interface ReactiveHost {

    /** The viewer of the read being served (set by the server per request), if any. */
    Optional<Viewer> viewer();

    /** Why the viewer may not have the kind (its roles may not open it, or the computation is not approved); empty when they may. */
    Optional<String> refusal(Viewer viewer, String connector, String kind);

    /**
     * Reads entities of a kind as the viewer, masked for them: the ids given, or (with {@code ids} null) every entity of the kind on the
     * date, at most {@code limit}. Throws {@link SecurityException} with words when the viewer may not open the kind.
     */
    List<DataNode> read(Viewer viewer, String kind, List<String> ids, AsOf asOf, int limit) throws Exception;

    /** The version of a kind's data for the date (a Delta table's version), or empty when its source cannot say. Cheap. */
    Optional<String> dataVersion(String kind, AsOf asOf);

    /** The business date a live read resolves to. */
    LocalDate today();

    /** The compute workers, or null when this server has none. */
    ComputeService compute();

    /** Whether Java engines that are not Drishti's own may run in the server's process ({@code compute.in-process-java}). */
    boolean inProcessJava();

    /** Records an audit entry. */
    void audit(String actor, String action, String subject, String detail);

    /** The next instant a cron expression (six fields, seconds first) fires after {@code after}, in {@code zone}. */
    Optional<Instant> nextCron(String cron, String zone, Instant after);

    /**
     * Follows one entity of a live kind (a Kafka-fed trade): {@code onChange} receives each new generation until the handle is closed.
     * The default follows nothing (the kind is not live here).
     */
    default AutoCloseable subscribe(String kind, String id, java.util.function.LongConsumer onChange) {
        return () -> { };
    }

    /** The host when there is no server (tests of the connector alone): no viewer, no inputs, no workers. */
    ReactiveHost NONE = new ReactiveHost() {
        @Override
        public Optional<Viewer> viewer() {
            return Viewer.current();
        }

        @Override
        public Optional<String> refusal(Viewer viewer, String connector, String kind) {
            return Optional.empty();
        }

        @Override
        public List<DataNode> read(Viewer viewer, String kind, List<String> ids, AsOf asOf, int limit) {
            return List.of();
        }

        @Override
        public Optional<String> dataVersion(String kind, AsOf asOf) {
            return Optional.empty();
        }

        @Override
        public LocalDate today() {
            return LocalDate.now(java.time.ZoneOffset.UTC);
        }

        @Override
        public ComputeService compute() {
            return null;
        }

        @Override
        public boolean inProcessJava() {
            return false;
        }

        @Override
        public void audit(String actor, String action, String subject, String detail) {
        }

        @Override
        public Optional<Instant> nextCron(String cron, String zone, Instant after) {
            return Optional.empty();
        }
    };
}
