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

import com.ash.drishti.api.compute.ComputeEvent;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.RequestClass;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Where a reactive connector's computation runs: a Python engine in a compute pool ({@link PythonRunner}, over every worker of the pool
 * when the engine says {@code split}) or a Java {@link com.ash.drishti.api.compute.ComputeEngine} in the server's process
 * ({@link JavaRunner}). A failure completes the future with a {@link ComputeException} whose code says what kind it was.
 */
public interface EngineRunner extends AutoCloseable {

    /** A computation under way: its result, and a way to stop it. */
    record Running(CompletableFuture<EngineResult> result, Runnable cancel) {}

    /** The engine's name and version, for keys and stamps ({@code python:drishti_var.connector:VarEngine@0.1.0}). */
    String identity();

    /** Why it cannot run now (compute off, the pool not ready), or empty. */
    Optional<String> unavailable();

    Running start(EngineRequest request, EngineInputs inputs, RequestClass cls, Duration budget, EngineProgress progress);

    /** The engine's incremental update, if it has one for the kind; empty means a full recompute. */
    default Optional<EngineResult> incremental(EngineRequest request, EngineResult previous, List<ComputeEvent> events, EngineInputs inputs) {
        return Optional.empty();
    }

    /** The ids an event touches by an expression the engine evaluates; empty when it cannot say (then the trigger counts as subscribed). */
    default Optional<Set<String>> affected(ComputeEvent event, String expression) {
        return Optional.empty();
    }

    @Override
    default void close() {
    }
}
