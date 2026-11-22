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
package com.ash.drishti.server.lifecycle;

import com.ash.drishti.api.lifecycle.ComponentHealth;
import com.ash.drishti.api.lifecycle.DependencyGraph;
import com.ash.drishti.api.lifecycle.Lifecycle;
import com.ash.drishti.api.lifecycle.LifecycleEvent;
import com.ash.drishti.api.lifecycle.LifecycleState;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.PackLifecycles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The kernel's one entry to the lifecycle of its parts (docs/architecture/LIFECYCLE.md): modules ({@link ModuleLifecycles}), packs
 * ({@link PackLifecycles}) and connectors ({@link SourceRegistry}), each behind the one {@link Lifecycle} contract; the dependency graph; the
 * audit of every transition; and what the cluster digest carries ({@link #wire}). The REST API, Admin, the health entry and the metrics all
 * read it; nothing else keeps lifecycle state.
 */
public final class LifecycleService {

    /** The digest service's name for the parts that are not running here (suspended, failed, shut down). */
    public static final String SERVICE = "lifecycle";

    private final PackLifecycles packs;
    private final ModuleLifecycles modules;
    private final SourceRegistry sources;
    private final Supplier<java.util.Set<String>> connectorNames;
    private final List<Consumer<LifecycleEvent>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public LifecycleService(PackLifecycles packs, ModuleLifecycles modules, SourceRegistry sources, Supplier<java.util.Set<String>> connectorNames,
            AuditLog audit) {
        this.packs = packs;
        this.modules = modules;
        this.sources = sources;
        this.connectorNames = connectorNames;
        com.ash.drishti.api.lifecycle.LifecycleMachine.Listener tell = e -> {
            // a person's transition, and every refusal or failure, is audited; the server's own (a boot, a connector file's restart) is not:
            // it would bury the people's in a row per connector at every start, and those changes have their own audit entries already
            if (!(com.ash.drishti.engine.source.SourceRegistry.SYSTEM.equals(e.actor()) && "done".equals(e.outcome()))) {
                audit.record(e.actor(), e.auditAction(), e.target(), e.words());
            }
            listeners.forEach(l -> l.accept(e));
        };
        packs.onTransition(tell);
        modules.onTransition(tell);
        sources.onLifecycle(tell);
    }

    /** Hears every transition after it is audited (the digest announces it, the metrics follow). */
    public void onTransition(Consumer<LifecycleEvent> l) {
        listeners.add(l);
    }

    public PackLifecycles packs() {
        return packs;
    }

    public ModuleLifecycles modules() {
        return modules;
    }

    /** The part of a type by name, as the one contract. */
    public Lifecycle part(Lifecycle.Type type, String name) {
        return switch (type) {
            case MODULE -> modules.lifecycle(name);
            case PACK -> packs.lifecycle(name);
            case CONNECTOR -> sources.lifecycle(name);
        };
    }

    /** Every part known on this node, by type: modules and packs with a machine, every connector the server knows. */
    public Map<Lifecycle.Type, List<String>> names() {
        Map<Lifecycle.Type, List<String>> out = new LinkedHashMap<>();
        out.put(Lifecycle.Type.MODULE, modules.describe().stream().map(m -> String.valueOf(m.get("name"))).sorted().toList());
        out.put(Lifecycle.Type.PACK, packs.describe().stream().map(m -> String.valueOf(m.get("name"))).sorted().toList());
        out.put(Lifecycle.Type.CONNECTOR, connectorNames.get().stream().sorted().toList());
        return out;
    }

    /** One part's row: type, name, state, since, by, health and stats. */
    public Map<String, Object> row(Lifecycle.Type type, String name) {
        Lifecycle l = part(type, name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type.word());
        m.put("name", name);
        m.put("state", l.state().name());
        machineRow(type, name).forEach(m::putIfAbsent);
        m.put("health", l.health());
        m.put("stats", l.stats());
        return m;
    }

    private Map<String, Object> machineRow(Lifecycle.Type type, String name) {
        return switch (type) {
            case PACK -> packs.machine(name).map(com.ash.drishti.api.lifecycle.LifecycleMachine::describe).orElse(Map.of());
            case CONNECTOR -> sources.machine(name).map(com.ash.drishti.api.lifecycle.LifecycleMachine::describe).orElse(Map.of());
            case MODULE -> modules.describe().stream().filter(r -> name.equals(r.get("name"))).findFirst().orElse(Map.of());
        };
    }

    public DependencyGraph graph() {
        return packs.graph();
    }

    /** {@code pack:market-risk:suspended,connector:trades-kafka:suspended}: only the parts that are not running, so the digest stays small. */
    public String wire() {
        StringBuilder s = new StringBuilder();
        names().forEach((type, list) -> list.forEach(n -> {
            LifecycleState st = part(type, n).state();
            if (st == LifecycleState.SUSPENDED || st == LifecycleState.FAILED || (st == LifecycleState.SHUTDOWN && type != Lifecycle.Type.CONNECTOR)) {
                s.append(s.isEmpty() ? "" : ",").append(type.word()).append(':').append(n).append(':').append(st.word());
            }
        }));
        return s.toString();
    }

    /** A peer's wire as rows: type, name, state. */
    public static List<Map<String, String>> parse(String wire) {
        List<Map<String, String>> out = new ArrayList<>();
        if (wire == null || wire.isBlank()) {
            return out;
        }
        for (String part : wire.split(",")) {
            String[] f = part.split(":", 3);
            if (f.length == 3) {
                out.add(Map.of("type", f[0], "name", f[1], "state", f[2]));
            }
        }
        return out;
    }

    /** The worst health of every part (for the health entry's summary), with each part that is not UP. */
    public Map<String, Object> healthSummary() {
        Map<String, Object> d = new LinkedHashMap<>();
        names().forEach((type, list) -> list.forEach(n -> {
            ComponentHealth h = part(type, n).health();
            if (h.status() != ComponentHealth.Status.UP) {
                d.put(type.word() + "/" + n, h.status() + (h.reasons().isEmpty() ? "" : ": " + String.join("; ", h.reasons())));
            }
        }));
        return d;
    }
}
