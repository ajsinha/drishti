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
package com.ash.drishti.api.compute;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An event a reactive connector's trigger reacts to (DATA_ARCHITECTURE.md › Reactive connectors, section 7): a write-back applied in the edit log, a message
 * of a live feed, a new data version of a static connector, a schedule's tick. Its {@link #id()} makes processing idempotent (an event
 * seen again is ignored) and its {@link #watermark()} orders results (a result never replaces one with a higher watermark).
 *
 * @param id the event's id: the change id, {@code <connector>/<partition>/<offset>}, {@code schedule/<tick>},
 *     {@code data-version/<kind>/<date>/<version>}
 * @param type {@code write-back}, {@code live}, {@code data-version} or {@code schedule}
 * @param kind the kind it is about (the trade amended, the kind whose version moved); empty for a schedule
 * @param entity the entity it is about, or empty
 * @param businessDate the business date it is for, or null for the current one
 * @param watermark per lane or partition, the sequence this event carries ({@code L17 -> 3412}, {@code p3 -> 182277})
 * @param at when it happened
 * @param by who caused it (the submitter of a change), or empty
 * @param paths the fields it changed (a write-back), never their values
 * @param payload anything else the source said (a schedule's tick, a version's old and new value)
 */
public record ComputeEvent(String id, String type, String kind, String entity, LocalDate businessDate, Map<String, Long> watermark,
        Instant at, String by, List<String> paths, Map<String, Object> payload) {

    public static final String WRITE_BACK = "write-back";
    public static final String LIVE = "live";
    public static final String DATA_VERSION = "data-version";
    public static final String SCHEDULE = "schedule";

    public ComputeEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        kind = kind == null ? "" : kind;
        entity = entity == null ? "" : entity;
        watermark = watermark == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(watermark));
        at = at == null ? Instant.now() : at;
        by = by == null ? "" : by;
        paths = paths == null ? List.of() : List.copyOf(paths);
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
