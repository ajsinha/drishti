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
package com.ash.drishti.server.enginehost;

import com.ash.drishti.api.compute.ComputeEngine;
import com.ash.drishti.enginehost.EngineHost;
import com.ash.drishti.enginehost.HostedEngine;
import com.ash.engine.EngineException;
import java.util.List;

/**
 * A hosted engine as the engine switches see it (Admin -> Engines, {@code drishti.py engine}, the API gate, the cluster's view): one
 * registry, two kinds of entry, as ENGINE_INTEGRATION_DESIGN 5.5 says. On is a start (a new instance when the last was shut down), off a
 * shutdown that closes the sessions and releases the instance's threads; the registry drains the requests in flight first. An engine that
 * may not run here (blocked, not configured) refuses to start with its words, so the registry shows it blocked with them.
 */
final class HostedComputeEngine implements ComputeEngine {

    private final EngineHost host;
    private final HostedEngine engine;
    private volatile String actor = "";
    private volatile Boolean running;

    HostedComputeEngine(EngineHost host, HostedEngine engine) {
        this.host = host;
        this.engine = engine;
    }

    @Override
    public String id() {
        return engine.id();
    }

    @Override
    public void start() {
        if (!engine.startable()) {
            throw new IllegalStateException(engine.words() + (engine.code() == null ? "" : " (" + engine.code() + ")"));
        }
        if ("running".equals(engine.state()) || "suspended".equals(engine.state())) {
            return;
        }
        try {
            host.transition(engine.id(), "start", actor, null);
        } catch (EngineException e) {
            throw new IllegalStateException(e.code() + " " + e.getMessage(), e);
        }
    }

    @Override
    public void changing(String actor, String at, String reason) {
        this.actor = actor == null ? "" : actor;
    }

    @Override
    public void stop() {
        try {
            host.transition(engine.id(), "shutdown", actor, null);
        } catch (EngineException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public long memoryBytes() {
        return engine.memoryBytes();
    }

    @Override
    public int datesOpen() {
        return engine.sessionsOpen();
    }

    @Override
    public List<String> threadPrefixes() {
        return List.of("drishti-engine-" + engine.id() + "-");
    }

    @Override
    public void running(boolean on) {
        running = on;
    }

    @Override
    public Boolean running() {
        return running;
    }
}
