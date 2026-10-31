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

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The code behind a reactive connector (docs/architecture/DATA_ARCHITECTURE.md › Reactive connectors, section 16.1): what a kind's answer is, computed from
 * inputs the server has already read and masked as the requesting viewer. The generic {@code reactive} connector plugin implements the
 * connector once; what differs per computation sits behind this interface, implemented by the worker bridge (a Python engine run in a
 * compute pool) or by trusted Java code run in the server's own process ({@code engine.type: java}).
 *
 * <p>An engine never sees a connector, a credential or the router: only {@link EngineInputs}. It must be thread-safe ({@link #compute} is
 * called for many requests at once), deterministic (the same request and inputs give the same result; random numbers only from
 * {@link EngineRequest#seed()}) and keep no state between requests that one viewer could leak to another.
 */
public interface ComputeEngine extends AutoCloseable {

    /** The engine's name, version and the kinds it computes. */
    EngineManifest manifest();

    /**
     * Computes one answer. Throw {@link EngineException} to refuse in words a person may read (an empty scope, a parameter the engine
     * cannot use); any other exception is a failure whose text goes to the server's log only.
     */
    EngineResult compute(EngineRequest request, EngineInputs inputs, EngineProgress progress) throws Exception;

    /**
     * Updates {@code previous} with the events since it was computed (DATA_ARCHITECTURE.md › Reactive connectors, section 10.2), or empty when a full
     * recompute is needed (too many events, a kind of event it cannot apply). Only called for kinds the manifest lists as incremental.
     */
    default Optional<EngineResult> incremental(EngineRequest request, EngineResult previous, List<ComputeEvent> events, EngineInputs inputs)
            throws Exception {
        return Optional.empty();
    }

    /**
     * The ids of the kind's answers an event touches, for a trigger whose {@code affects:} names a function the engine supplies
     * ({@code scopes_of(trade.TT_BOOK)}); empty when it touches none. Must be cheap: it runs for every event before anything is computed.
     */
    default Set<String> affected(ComputeEvent event, String expression) {
        return Set.of();
    }

    @Override
    default void close() {
    }
}
