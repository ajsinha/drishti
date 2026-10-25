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

import com.ash.drishti.api.AsOf;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One computation as an engine sees it (REACTIVE_CONNECTORS.md section 6.1): its correlation id, the connector, kind and id (the
 * question), the typed parameters already checked against the connector's {@code params:}, the pinned as-of, who it is for, its class,
 * the seed any randomness must use, and the trigger that caused it (null for a plain request).
 *
 * @param requestId the correlation id ({@code req-...}): the answer goes to it and nothing else
 * @param connector the connector's name
 * @param kind the kind asked for
 * @param id the entity id: the scope, the question
 * @param params the parameters, normalised (defaults filled, types converted)
 * @param asOf the business date (and knowledge time) the inputs were read for
 * @param pin the pin of the inputs' versions ({@code pin-...})
 * @param viewer who asked, or for whose view signature a trigger recomputes
 * @param cls {@code interactive}, {@code event} or {@code batch}
 * @param seed the seed of any random numbers
 * @param trigger the event that caused this computation, or null
 */
public record EngineRequest(String requestId, String connector, String kind, String id, Map<String, Object> params, AsOf asOf, String pin,
        Viewer viewer, String cls, long seed, ComputeEvent trigger) {

    public EngineRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(kind, "kind");
        id = id == null ? "" : id;
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        asOf = asOf == null ? AsOf.LATEST : asOf;
        pin = pin == null ? "" : pin;
        Objects.requireNonNull(viewer, "viewer");
        cls = cls == null || cls.isBlank() ? "interactive" : cls;
    }

    /** A parameter, or {@code fallback} when it is absent. */
    public Object param(String name, Object fallback) {
        Object v = params.get(name);
        return v == null ? fallback : v;
    }
}
