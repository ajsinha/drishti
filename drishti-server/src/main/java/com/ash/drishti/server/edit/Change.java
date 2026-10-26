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
package com.ash.drishti.server.edit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A change request (WRITE_BACK.md section 6): one or more field changes to one record, with the version the person saw, where it goes
 * (overlay, owned or a source command), the reason, who and when. Kept as JSON in the edit log's {@code body} and in
 * {@code drishti_edit_request} while it awaits approval.
 *
 * @param id the change id ({@code chg-} and a ULID: time-ordered); a retry or resend is recognised by it
 * @param key the queue key: {@code kind:entity}
 * @param kind the record's kind
 * @param entity the record's id
 * @param businessDate the business date the change is for (a past one is a restatement)
 * @param items the field changes
 * @param basedOn the record's version the person saw, or null (a field-level check reads each item's {@code from} too)
 * @param target {@code overlay}, {@code owned} or {@code source}
 * @param connector the connector a source command goes through, or null
 * @param command the connector's {@code writes:} command, or null
 * @param params the command's extra parameters (read from the record being edited)
 * @param reason why, in the person's words
 * @param by who asked
 * @param at when it was submitted
 * @param approvals how many approvals it needs (0, 1 or 2)
 * @param origin where it was made: {@code sutra}, {@code panel}, {@code draft}, the page's {@code pageKind}/{@code pageId}, the rows'
 *     path {@code rows} and key field {@code rowKey} (a form's record shown as a row of another page), {@code mode}
 * @param override true when the person chose "Apply mine anyway" on a conflict (needs the override role)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Change(String id, String key, String kind, String entity, LocalDate businessDate, List<Item> items, Long basedOn,
        String target, String connector, String command, Map<String, Object> params, String reason, String by, Instant at, int approvals,
        Map<String, String> origin, boolean override) {

    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * One field change.
     *
     * @param path the record's field ({@code $.NOTIONAL})
     * @param from the value the person saw (null for a new value)
     * @param to the new value
     */
    public record Item(String path, Object from, Object to) {}

    public Change {
        items = items == null ? List.of() : List.copyOf(items);
        params = params == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
        origin = origin == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(origin));
        reason = reason == null ? "" : reason;
    }

    public static String keyOf(String kind, String entity) {
        return kind + ":" + entity;
    }

    /** The fields it touches. */
    public List<String> paths() {
        return items.stream().map(Item::path).toList();
    }

    public String origin(String name) {
        return origin.get(name);
    }

    public Change withApprovals(int n) {
        return new Change(id, key, kind, entity, businessDate, items, basedOn, target, connector, command, params, reason, by, at, n, origin, override);
    }

    public String json() {
        try {
            return JSON.writeValueAsString(this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Change parse(String json) {
        try {
            return JSON.readValue(json, Change.class);
        } catch (IOException e) {
            throw new UncheckedIOException("an edit-log body that does not read: " + e.getMessage(), e);
        }
    }
}
