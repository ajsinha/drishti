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

import com.ash.drishti.api.compute.ComputeEngine;
import com.ash.drishti.api.compute.ComputeEvent;
import com.ash.drishti.api.compute.EngineException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a Java {@link ComputeEngine} in the server's own process (PYTHON_COMPUTE.md section 10): only for trusted, light code. Drishti's own
 * engines ({@code com.ash.drishti.*}, shipped with the server) always may; any other class only when the site allows it
 * ({@code compute.in-process-java: true}); heavy or untrusted Java belongs in a Java worker (planned, C6). Each computation runs on its own
 * virtual thread with the request's budget; a computation past its budget is interrupted.
 */
public final class JavaRunner implements EngineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(JavaRunner.class);

    private final ComputeEngine engine;
    private final String identity;

    private JavaRunner(ComputeEngine engine) {
        this.engine = engine;
        this.identity = "java:" + engine.manifest().name() + "@" + engine.manifest().version();
    }

    /** The engine of class {@code className}, if this server lets it run in process; refused in words otherwise. */
    public static JavaRunner of(String className, boolean inProcessAllowed, String connector) {
        boolean shipped = className.startsWith("com.ash.drishti.");
        if (!shipped && !inProcessAllowed) {
            throw new IllegalArgumentException(connector + ": engine " + className + " would run inside the server; this server allows only "
                    + "the server's own Java engines there (compute.in-process-java: false)");
        }
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader() != null ? Thread.currentThread().getContextClassLoader() : JavaRunner.class.getClassLoader();
            Class<?> c = Class.forName(className, true, cl);
            if (!ComputeEngine.class.isAssignableFrom(c)) {
                throw new IllegalArgumentException(connector + ": " + className + " is not a ComputeEngine");
            }
            return new JavaRunner((ComputeEngine) c.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalArgumentException(connector + ": the engine class " + className + " cannot be loaded: " + e);
        }
    }

    /** A runner of an engine instance (tests, and engines a server builds itself). */
    public static JavaRunner of(ComputeEngine engine) {
        return new JavaRunner(engine);
    }

    @Override
    public String identity() {
        return identity;
    }

    public ComputeEngine engine() {
        return engine;
    }

    @Override
    public Optional<String> unavailable() {
        return Optional.empty();
    }

    @Override
    public Running start(EngineRequest request, EngineInputs inputs, RequestClass cls, Duration budget, EngineProgress progress) {
        CompletableFuture<EngineResult> f = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().name("reactive-java-" + request.requestId()).unstarted(() -> {
            try {
                f.complete(engine.compute(request, inputs, progress));
            } catch (EngineException e) {
                f.completeExceptionally(new ComputeException(ComputeException.Code.ENGINE, e.getMessage(), request.requestId(), null));
            } catch (InterruptedException e) {
                f.completeExceptionally(new ComputeException(ComputeException.Code.CANCELLED, "stopped", request.requestId(), null));
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                LOG.warn("reactive: engine {} failed on {} ({}): {}", identity, request.requestId(), request.kind(), e.toString(), e);
                f.completeExceptionally(new ComputeException(ComputeException.Code.FAILED, "the computation failed (request " + request.requestId() + ")",
                        request.requestId(), e));
            }
        });
        t.start();
        CompletableFuture<EngineResult> bounded = f.orTimeout(budget.toMillis(), TimeUnit.MILLISECONDS).handle((r, e) -> {
            if (e == null) {
                return r;
            }
            Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
            if (c instanceof TimeoutException) {
                t.interrupt();
                throw new ComputeException(ComputeException.Code.TIMEOUT, "no result within " + budget.toMillis() + " ms", request.requestId(), null);
            }
            throw c instanceof ComputeException ce ? ce : new ComputeException(ComputeException.Code.FAILED, String.valueOf(c), request.requestId(), c);
        });
        return new Running(bounded, () -> {
            t.interrupt();
            f.completeExceptionally(new ComputeException(ComputeException.Code.CANCELLED, "cancelled", request.requestId(), null));
        });
    }

    @Override
    public Optional<EngineResult> incremental(EngineRequest request, EngineResult previous, List<ComputeEvent> events, EngineInputs inputs) {
        if (!engine.manifest().incremental().contains(request.kind())) {
            return Optional.empty();
        }
        try {
            return engine.incremental(request, previous, events, inputs);
        } catch (Exception e) {
            LOG.info("reactive: {} could not update {} incrementally ({}); computing it again", identity, request.kind(), e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Set<String>> affected(ComputeEvent event, String expression) {
        try {
            return Optional.of(engine.affected(event, expression));
        } catch (RuntimeException e) {
            LOG.warn("reactive: {} could not say what event {} affects ({}): {}", identity, event.id(), expression, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        try {
            engine.close();
        } catch (Exception e) {
            LOG.debug("closing {}: {}", identity, e.toString());
        }
    }
}
