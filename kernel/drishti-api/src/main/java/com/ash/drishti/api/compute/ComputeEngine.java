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
 * A compute engine: Drishti's one interface for it, with two faces an engine implements as it needs.
 *
 * <p><b>Its switch</b> (docs/guides/ADMIN_GUIDE.md section 5.1): {@link #id()}, {@link #start()}, {@link #changing}, {@link #stop()},
 * {@link #memoryBytes()}, {@link #datesOpen()}, {@link #threadPrefixes()}, {@link #running(boolean)} and {@link #running()}. An engine
 * switched on or off per node registers with the server's engine registry as a Spring bean (or a module's contribution): its build adds one
 * bean of this type and one catalogue entry, nothing else. The bean exists either way and is thin; what holds memory or threads (open
 * dates, caches, pools, schedulers, its connectors) is created on first use while it runs and released by {@link #stop()}. While it does
 * not run, its service refuses (404 DRS-1020) and the API filter answers before it is reached.
 *
 * <p><b>Its computation</b> (docs/architecture/DATA_ARCHITECTURE.md › Reactive connectors, section 16.1): {@link #manifest()},
 * {@link #compute}, {@link #incremental}, {@link #affected} and {@link #close()}, the code behind a reactive connector: what a kind's
 * answer is, computed from inputs the server has already read and masked as the requesting viewer. The generic {@code reactive} connector
 * plugin implements the connector once; what differs per computation sits behind this face, implemented by the worker bridge (a Python
 * engine run in a compute pool) or by trusted Java code run in the server's own process ({@code engine.type: java}). It never sees a
 * connector, a credential or the router: only {@link EngineInputs}. It must be thread-safe ({@link #compute} is called for many requests
 * at once), deterministic (the same request and inputs give the same result; random numbers only from {@link EngineRequest#seed()}) and
 * keep no state between requests that one viewer could leak to another.
 *
 * <p>A switched engine names its {@link #id()}; a computing one its {@link #manifest()} (whose name is then its id). The engine model of
 * docs/plans/ENGINE_INTEGRATION_DESIGN.md (EN-25) later moves the computation face to the engine API and keeps the switch.
 */
public interface ComputeEngine extends AutoCloseable {

    // --- the switch ------------------------------------------------------------------------------------------------------------------

    /** The engine's id: its catalogue id ({@code var}, {@code frtb-sa}) for a switched engine; by default its manifest's name. */
    default String id() {
        return manifest().name();
    }

    /** Starts the engine: parameter files read and checked, its connectors and computed kinds registered. Throws to refuse (the reason shown). */
    default void start() {
    }

    /** Told who changes the engine's switch, when and why, just before the change takes effect (an engine that records it in its own state). */
    default void changing(String actor, String at, String reason) {
    }

    /** Releases what it holds: open dates, caches and buffers, threads, its connectors and computed kinds. Requests have drained. */
    default void stop() {
    }

    /** The memory it holds now, in bytes (the heap of open dates and caches, direct buffers). */
    default long memoryBytes() {
        return 0;
    }

    /** Business dates it holds open now. */
    default int datesOpen() {
        return 0;
    }

    /** The name prefixes of its own threads ({@code drishti-var-}), counted for Admin, Engines and checked by the tests. */
    default List<String> threadPrefixes() {
        return List.of();
    }

    /** Told whether the registry has the engine running (the service's own check reads it); a switched engine keeps it. */
    default void running(boolean on) {
    }

    /** Whether the engine runs, as the registry last said; null before the registry said anything (a test that builds the service alone). */
    default Boolean running() {
        return null;
    }

    /** The live threads whose names start with one of the prefixes. */
    static int threadsNamed(List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return 0;
        }
        return (int) Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(t -> prefixes.stream().anyMatch(p -> t.getName().startsWith(p))).count();
    }

    // --- the computation -------------------------------------------------------------------------------------------------------------

    /** The engine's name, version and the kinds it computes; an engine that computes no kinds (only switched) refuses. */
    default EngineManifest manifest() {
        throw new UnsupportedOperationException(getClass().getName() + " computes no kinds (no manifest)");
    }

    /**
     * Computes one answer. Throw {@link EngineException} to refuse in words a person may read (an empty scope, a parameter the engine
     * cannot use); any other exception is a failure whose text goes to the server's log only.
     */
    default EngineResult compute(EngineRequest request, EngineInputs inputs, EngineProgress progress) throws Exception {
        throw new EngineException("the engine " + id() + " computes no kinds");
    }

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

    /** Releases a computing engine's resources (its reactive connector is closed); nothing by default. */
    @Override
    default void close() {
    }
}
