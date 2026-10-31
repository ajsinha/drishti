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

import com.ash.drishti.api.compute.ComputeRef;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.compute.ComputeEvent;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.compute.RequestClass;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code reactive} connector plugin (DATA_ARCHITECTURE.md › Reactive connectors, section 5): a connector whose answer is a computation. Its file names the
 * kinds it computes with their typed parameters, the inputs it reads as the viewer, the engine (a Python engine in a compute pool, or a
 * Java engine in the server), the triggers, coalescing, the cache and the limits; the server hands that computation to the plugin as the
 * setting {@code reactive.spec}. A read ({@code fetch}) is answered at once with the result, or with the last good one marked
 * <em>computing</em>, or with why it failed or was refused, in the document's {@code COMPUTE} field; never an exception, never empty. The
 * id is the question with its parameters ({@link ComputeRef}).
 */
public final class ReactiveSourcePlugin implements SourcePlugin {

    public static final String NAME = "reactive";

    private volatile String source = NAME;
    private volatile ReactiveSpec spec;
    private volatile ReactiveConnector connector;
    private final ReactiveHub hub;

    public ReactiveSourcePlugin() {
        this(ReactiveHub.get());
    }

    public ReactiveSourcePlugin(ReactiveHub hub) {
        this.hub = hub;
    }

    @Override
    public PluginManifest manifest() {
        ReactiveSpec s = spec;
        return new PluginManifest(NAME, "1.0", s == null ? Set.of() : s.kinds().keySet(), new SourceCapabilities(false, false, false, true));
    }

    @Override
    public void start(SourceContext context) {
        source = context.setting("source-name", NAME);
        String json = context.settings().get("reactive.spec");
        if (json == null || json.isBlank()) {
            throw new com.ash.drishti.api.PluginNotConfigured("the reactive plugin runs only as a connector file that declares a computation "
                    + "(kinds, engine, reads, triggers): docs/connectors/REACTIVE_CONNECTOR.md");
        }
        ReactiveSpec s = ReactiveSpec.parse(json, source + ".yaml", v -> v);
        EngineRunner runner = "java".equals(s.engine().type()) ? new DeferredJava(s.engine().entry(), source, hub)
                : new PythonRunner(() -> hub.host().compute(), s.engine(), s.engine().reduce());
        if ("java".equals(s.engine().type()) && s.engine().entry().startsWith("com.ash.drishti.")) {
            ((DeferredJava) runner).resolve();                          // Drishti's own: load it now, so a wrong class name fails the start
        }
        spec = s;
        connector = new ReactiveConnector(source, s, runner, hub, context.scheduler(), Clock.systemUTC()).start();
    }

    /** The running connector (Admin, the HTTP API, tests). */
    public ReactiveConnector connector() {
        return connector;
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        ReactiveConnector c = connector;
        if (c == null || c.spec().kind(ref.kind()) == null) {
            return Optional.empty();
        }
        Answer a = c.answer(new ReactiveConnector.Ask(ref.kind(), ref.id(), asOf, hub.host().viewer().orElse(null), RequestClass.INTERACTIVE,
                c.spec().limits().answerWait()));
        long generation = a.document() != null && a.document().get("STAMP") instanceof Map<?, ?> st && st.get("computedAt") != null
                ? Instant.parse(String.valueOf(st.get("computedAt"))).toEpochMilli() : 0;
        return Optional.of(new EntityDocument(ref, a.toDocument(ref.id()), new Provenance(source, generation, Instant.now(), false,
                asOf == null ? null : asOf.businessDate())));
    }

    /** A computed kind's answer depends on its inputs: a load of one of them is a {@code data-version} event, and an old pin's results age out. */
    @Override
    public boolean dependsOn(String kind) {
        ReactiveSpec s = spec;
        return s != null && s.readKinds().contains(kind);
    }

    @Override
    public void refreshVersions() {
        ReactiveConnector c = connector;
        if (c != null) {
            c.checkVersions();
        }
    }

    @Override
    public void purgeCaches() {
        ReactiveConnector c = connector;
        if (c != null) {
            c.checkVersions();
            c.purgeCaches();
        }
    }

    @Override
    public Map<String, Object> cacheStats() {
        ReactiveConnector c = connector;
        return c == null ? Map.of() : c.describe();
    }

    @Override
    public String health() {
        ReactiveConnector c = connector;
        if (c == null) {
            return "DOWN: not started";
        }
        Object why = c.describe().get("available");
        return "ready".equals(why) ? "UP" : "DEGRADED: " + why;
    }

    @Override
    public void close() {
        ReactiveConnector c = connector;
        if (c != null) {
            c.close();
        }
    }

    /**
     * A Java engine loaded when it is first needed, so the server's choice about engines that are not Drishti's own
     * ({@code compute.in-process-java}) is known by then.
     */
    static final class DeferredJava implements EngineRunner {
        private final String className;
        private final String connector;
        private final ReactiveHub hub;
        private volatile JavaRunner runner;
        private volatile String refusal;

        DeferredJava(String className, String connector, ReactiveHub hub) {
            this.className = className;
            this.connector = connector;
            this.hub = hub;
        }

        JavaRunner resolve() {
            JavaRunner r = runner;
            if (r == null) {
                synchronized (this) {
                    if (runner == null) {
                        try {
                            runner = JavaRunner.of(className, hub.host().inProcessJava(), connector);
                            refusal = null;
                        } catch (IllegalArgumentException e) {
                            refusal = e.getMessage();
                            throw e;
                        }
                    }
                    r = runner;
                }
            }
            return r;
        }

        @Override
        public String identity() {
            unavailable();
            JavaRunner r = runner;
            return r != null ? r.identity() : "java:" + className;
        }

        @Override
        public Optional<String> unavailable() {
            try {
                resolve();
                return Optional.empty();
            } catch (IllegalArgumentException e) {
                return Optional.of(refusal == null ? e.getMessage() : refusal);
            }
        }

        @Override
        public Running start(EngineRequest request, EngineInputs inputs, RequestClass cls, Duration budget, EngineProgress progress) {
            return resolve().start(request, inputs, cls, budget, progress);
        }

        @Override
        public Optional<EngineResult> incremental(EngineRequest request, EngineResult previous, List<ComputeEvent> events, EngineInputs inputs) {
            return resolve().incremental(request, previous, events, inputs);
        }

        @Override
        public Optional<Set<String>> affected(ComputeEvent event, String expression) {
            return resolve().affected(event, expression);
        }

        @Override
        public void close() {
            JavaRunner r = runner;
            if (r != null) {
                r.close();
            }
        }
    }
}
