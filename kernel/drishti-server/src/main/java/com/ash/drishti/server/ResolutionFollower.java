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
package com.ash.drishti.server;

import com.ash.drishti.server.bindings.BindingService;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.sources.SourceSystemsWiring;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One rebuild, everything follows (docs/architecture/KERNEL_AND_MODULES.md section 18.7): when a module is switched on or off (installed or
 * unloaded) the resolution is built again (the reconcile reads it), the packs are reconciled with it (a module's packs load or
 * unload), then the connectors (their templates came or went with the packs), then the data bindings (each kind's read order), then the
 * source systems. The inbox reads the current resolution's rules at its next cycle. In that order, on one thread, never inside the request
 * that switched the module.
 */
@Component
public class ResolutionFollower implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ResolutionFollower.class);

    private final ObjectProvider<PackReloader> packs;
    private final ObjectProvider<ConnectorManager> connectors;
    private final ObjectProvider<BindingService> bindings;
    private final ObjectProvider<SourceSystemsWiring> systems;
    private final ObjectProvider<com.ash.drishti.registry.AccessRegistry> access;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("drishti-resolution").factory());
    private final AtomicLong followed = new AtomicLong();
    private volatile AutoCloseable listening;

    public ResolutionFollower(ObjectProvider<PackReloader> packs, ObjectProvider<ConnectorManager> connectors, ObjectProvider<BindingService> bindings,
            ObjectProvider<SourceSystemsWiring> systems, ObjectProvider<com.ash.drishti.registry.AccessRegistry> access) {
        this.packs = packs;
        this.connectors = connectors;
        this.bindings = bindings;
        this.systems = systems;
        this.access = access;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        // this server's own switches (never another server's change of the process-wide resolution, as in a test JVM of many contexts);
        // pack folders that come and go are the reloader's own watch
        com.ash.drishti.registry.AccessRegistry a = access.getIfAvailable();
        if (a != null) {
            listening = a.onChange(() -> worker.execute(this::follow));
        }
    }

    /** How many changes this server has followed (a test waits for it). */
    public long followed() {
        return followed.get();
    }

    /** Brings packs, connectors, bindings and source systems to the current resolution. Never fails: a problem is logged. */
    public void follow() {
        try {
            PackReloader p = packs.getIfAvailable();
            if (p != null) {
                p.reconcile("system", "resolution.changed", Set.of());
            }
            ConnectorManager c = connectors.getIfAvailable();
            if (c != null) {
                c.scan();
            }
            BindingService b = bindings.getIfAvailable();
            if (b != null) {
                b.refresh();
            }
            SourceSystemsWiring s = systems.getIfAvailable();
            if (s != null) {
                s.refresh();
            }
        } catch (RuntimeException e) {
            LOG.warn("resolution.changed: not followed completely: {}", e.toString());
        } finally {
            followed.incrementAndGet();
        }
    }

    @Override
    public void close() throws Exception {
        AutoCloseable l = listening;
        if (l != null) {
            l.close();
        }
        worker.shutdownNow();
    }
}
