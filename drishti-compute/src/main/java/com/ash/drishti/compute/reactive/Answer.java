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

import com.ash.drishti.api.DataNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a read of a computed kind gets back, always (REACTIVE_CONNECTORS.md section 12.4): never an error page, never a blank panel. The
 * state says what the panel draws; the document is the result (or the last good one while a new one is computed, or none); the request id
 * is what the panel listens to for progress and later updates.
 *
 * @param state {@code ready}, {@code computing}, {@code stale}, {@code failed} or {@code refused}
 * @param requestId the correlation id of this read
 * @param connector the reactive connector that answered
 * @param document the result's document (fields, RESULTS, TABLES, STAMP), or null when there is none yet
 * @param message what to say, in words (why it failed or was refused; what it is waiting for)
 * @param progress the latest progress ({@code done}, {@code of}, a partial estimate), or null
 */
public record Answer(String state, String requestId, String connector, Map<String, Object> document, String message, Map<String, Object> progress) {

    public static final String READY = "ready";
    public static final String COMPUTING = "computing";
    public static final String STALE = "stale";
    public static final String FAILED = "failed";
    public static final String REFUSED = "refused";

    /** The field of a computed document that carries its state for the panel. */
    public static final String FIELD = "COMPUTE";

    static Answer refused(String connector, String requestId, String message) {
        return new Answer(REFUSED, requestId, connector, null, message, null);
    }

    public boolean ready() {
        return READY.equals(state);
    }

    /**
     * The answer as the entity document a view binds: the result's fields with {@code COMPUTE} saying the state, the request id, the
     * progress and the message, so a panel knows what to draw and what to listen to.
     */
    public DataNode toDocument(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (document != null) {
            m.putAll(document);
        } else {
            m.put("ID", id);
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("state", state);
        c.put("requestId", requestId);
        c.put("connector", connector);
        c.put("hasResult", document != null);
        if (message != null) {
            c.put("message", message);
        }
        if (progress != null) {
            c.put("progress", progress);
        }
        m.put(FIELD, c);
        return DataNode.of(m);
    }
}
