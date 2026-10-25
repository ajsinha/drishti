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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where reactive connectors meet the server (REACTIVE_CONNECTORS.md section 16): the {@link ReactiveHost} the server installs, the
 * running connectors by name, the events of every request (delivered to its owner only), and the private sandboxes. Connector plugins
 * are made by the server's plugin loader with no arguments, so they find this one shared hub; the server installs its host at start, and
 * a connector asks for it only when it computes (never while it starts).
 */
public final class ReactiveHub {

    private static final ReactiveHub SHARED = new ReactiveHub();

    private volatile ReactiveHost host = ReactiveHost.NONE;
    private volatile Sandboxes sandboxes = new Sandboxes(null, Clock.systemUTC());
    private final RequestEvents events = new RequestEvents(Duration.ofMinutes(10), Clock.systemUTC());
    private final Map<String, ReactiveConnector> connectors = new ConcurrentHashMap<>();

    /** The hub of this process. */
    public static ReactiveHub get() {
        return SHARED;
    }

    /** Installs the server's host, and keeps sandboxes under {@code <data dir>/compute/sandboxes}. */
    public void install(ReactiveHost h, Path dataDir) {
        host = h == null ? ReactiveHost.NONE : h;
        if (dataDir != null) {
            sandboxes = new Sandboxes(dataDir.resolve("compute").resolve("sandboxes"), Clock.systemUTC());
        }
    }

    public ReactiveHost host() {
        return host;
    }

    public RequestEvents events() {
        return events;
    }

    public Sandboxes sandboxes() {
        return sandboxes;
    }

    void register(ReactiveConnector c) {
        connectors.put(c.name(), c);
    }

    void unregister(ReactiveConnector c) {
        connectors.remove(c.name(), c);
    }

    public Optional<ReactiveConnector> connector(String name) {
        return Optional.ofNullable(connectors.get(name));
    }

    public List<ReactiveConnector> connectors() {
        return new ArrayList<>(connectors.values());
    }

    /**
     * Delivers an event to every reactive connector with a trigger for it (a write-back applied, a feed message, a load published):
     * each ignores what it has already seen and recomputes only what somebody watches. Returns, per connector, what it did.
     */
    public Map<String, Object> publish(ComputeEvent e) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ReactiveConnector c : connectors.values()) {
            out.put(c.name(), c.event(e));
        }
        return out;
    }

    /** Forgets old requests nobody listens to. */
    public int sweep() {
        return events.sweep();
    }
}
