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
import com.ash.drishti.api.lifecycle.ComponentStats;
import com.ash.drishti.api.lifecycle.DependencyGraph;
import com.ash.drishti.api.lifecycle.Lifecycle;
import com.ash.drishti.api.lifecycle.LifecycleMachine;
import com.ash.drishti.api.lifecycle.LifecycleState;
import com.ash.drishti.api.lifecycle.Transition;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.packs.PackLifecycles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Every module's lifecycle on this node (docs/architecture/LIFECYCLE.md section 2): a module's transition is its parts' transitions in the
 * dependency graph's order. Start: its connectors, then its packs (dependencies first); suspend and shutdown: its packs, then its connectors
 * (dependants first); resume: the start's order. A shutdown also unloads the module's code (the runtime's hot unload: routes off, context
 * closed, loader dropped and checked for leaks); a start after it loads the module again from the candidate verified at boot.
 */
public final class ModuleLifecycles {

    private final Supplier<ModuleRuntime> runtime;
    private final PackLifecycles packs;
    private final SourceRegistry sources;
    private final Map<String, LifecycleMachine> machines = new ConcurrentHashMap<>();
    private final List<LifecycleMachine.Listener> listeners = new CopyOnWriteArrayList<>();

    public ModuleLifecycles(Supplier<ModuleRuntime> runtime, PackLifecycles packs, SourceRegistry sources) {
        this.runtime = runtime;
        this.packs = packs;
        this.sources = sources;
    }

    public void onTransition(LifecycleMachine.Listener l) {
        listeners.add(l);
        machines.values().forEach(m -> m.listen(l));
    }

    /** Each module loaded at boot (or by Admin → Modules) gets a RUNNING machine; a module unloaded outside the lifecycle is shut down. */
    public void sync(String actor) {
        ModuleRuntime r = runtime.get();
        if (r == null) {
            return;
        }
        java.util.Set<String> loaded = r.loadedIds();
        for (String id : loaded) {
            LifecycleMachine m = machines.get(id);
            if (m == null || m.state() == LifecycleState.SHUTDOWN || m.state() == LifecycleState.FAILED || m.state() == LifecycleState.NEW) {
                LifecycleMachine fresh = newMachine(id);
                fresh.transition(Transition.INIT, actor, null);
                fresh.transition(Transition.START, actor, null);
            }
        }
        machines.forEach((id, m) -> {
            if (!loaded.contains(id) && (m.state() == LifecycleState.RUNNING || m.state() == LifecycleState.SUSPENDED)) {
                m.transition(Transition.SHUTDOWN, actor, null);
            }
        });
    }

    private LifecycleMachine newMachine(String id) {
        LifecycleMachine m = new LifecycleMachine(Lifecycle.Type.MODULE, id);
        listeners.forEach(m::listen);
        machines.put(id, m);
        return m;
    }

    public LifecycleState state(String id) {
        LifecycleMachine m = machines.get(id);
        return m == null ? LifecycleState.NEW : m.state();
    }

    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        machines.values().forEach(m -> out.add(m.describe()));
        return out;
    }

    /** The module's parts in the graph: its packs and connectors (CONTAINS edges). */
    List<String> parts(DependencyGraph g, String id) {
        return g.dependencies("module:" + id).stream().filter(d -> d.edge() == DependencyGraph.Edge.CONTAINS).map(DependencyGraph.Dependency::to).toList();
    }

    private LifecycleMachine machine(String id, Transition t) {
        LifecycleMachine m = machines.get(id);
        if (m == null) {
            throw new com.ash.drishti.api.lifecycle.LifecycleRefused(Lifecycle.Type.MODULE, id, LifecycleState.NEW, t,
                    "it is not loaded on this node");
        }
        return m;
    }

    public synchronized void init(String id, String actor) {
        LifecycleMachine m = machines.get(id);
        if (m == null || m.state() == LifecycleState.SHUTDOWN || m.state() == LifecycleState.FAILED) {
            m = newMachine(id);
        }
        m.transition(Transition.INIT, actor, () -> {
            ModuleRuntime r = runtime.get();
            if (r == null || r.candidate(id).isEmpty()) {
                throw new IllegalStateException("module " + id + " was not verified on this node: install its .dmod in the modules folder and restart");
            }
        });
    }

    /** {@code start}: refused in one sentence on a cycle or a missing dependency of its parts; else the code, then the parts in order. */
    public synchronized void start(String id, String actor) {
        LifecycleMachine m = machines.get(id);
        if (m == null || m.state() == LifecycleState.SHUTDOWN || m.state() == LifecycleState.FAILED || m.state() == LifecycleState.NEW) {
            init(id, actor);
            m = machines.get(id);
        }
        DependencyGraph g = packs.graph();
        String problem = g.startProblem(List.of("module:" + id));
        if (problem != null) {
            m.refuse(Transition.START, actor, problem);
        }
        m.transition(Transition.START, actor, () -> {
            ModuleRuntime r = runtime.get();
            if (r.loaded(id).isEmpty()) {
                if (r.load(r.candidate(id).orElseThrow(), List.of("started by " + actor)) == null) {
                    throw new IllegalStateException("module " + id + " was refused: " + r.health(id).map(Object::toString).orElse("see Admin -> Modules"));
                }
            }
            DependencyGraph now = packs.graph();                     // its packs are known now
            for (String n : now.startOrder(parts(now, id))) {
                startPart(n, actor);
            }
        });
    }

    public synchronized void suspend(String id, String actor) {
        LifecycleMachine m = machine(id, Transition.SUSPEND);
        DependencyGraph g = packs.graph();
        m.transition(Transition.SUSPEND, actor, null, () -> {
            for (String n : g.stopOrder(parts(g, id))) {
                if (n.startsWith("pack:") && packs.state(n.substring(5)) == LifecycleState.RUNNING) {
                    packs.suspend(n.substring(5), actor);
                } else if (n.startsWith("connector:") && sources.lifecycleState(n.substring(10)) == LifecycleState.RUNNING) {
                    sources.suspend(n.substring(10), actor);
                }
            }
        });
    }

    public synchronized void resume(String id, String actor) {
        LifecycleMachine m = machine(id, Transition.RESUME);
        DependencyGraph g = packs.graph();
        m.transition(Transition.RESUME, actor, () -> {
            for (String n : g.startOrder(parts(g, id))) {
                if (n.startsWith("connector:") && sources.lifecycleState(n.substring(10)) == LifecycleState.SUSPENDED) {
                    sources.resume(n.substring(10), actor);
                } else if (n.startsWith("pack:") && packs.state(n.substring(5)) == LifecycleState.SUSPENDED) {
                    packs.resume(n.substring(5), actor);
                }
            }
        });
    }

    /** {@code shutdown}: its packs, then its connectors, then its code; harmless twice. */
    public synchronized void shutdown(String id, String actor) {
        LifecycleMachine m = machines.get(id);
        if (m == null) {
            return;
        }
        DependencyGraph g = packs.graph();
        m.transition(Transition.SHUTDOWN, actor, null, () -> {
            for (String n : g.stopOrder(parts(g, id))) {
                if (n.startsWith("pack:")) {
                    packs.shutdown(n.substring(5), actor, true);
                } else if (n.startsWith("connector:")) {
                    sources.shutdown(n.substring(10), actor);
                }
            }
            ModuleRuntime r = runtime.get();
            if (r != null && r.loaded(id).isPresent()) {
                r.unload(id, true);
            }
        });
    }

    private void startPart(String n, String actor) {
        if (n.startsWith("connector:")) {
            String c = n.substring(10);
            if (sources.lifecycleState(c) != LifecycleState.RUNNING && sources.applied(c).isPresent()) {
                sources.start(c, actor);
            }
        } else if (n.startsWith("pack:")) {
            String p = n.substring(5);
            if (packs.state(p) != LifecycleState.RUNNING && packs.state(p) != LifecycleState.SUSPENDED) {
                packs.start(p, actor);
            }
        }
    }

    /** The module's state, with each part that is not UP named (a part DOWN or SUSPENDED makes the module DEGRADED). */
    public ComponentHealth health(String id) {
        LifecycleMachine m = machines.get(id);
        ComponentHealth h = m == null ? ComponentHealth.ofState(LifecycleState.NEW, null, Instant.now()) : m.stateHealth();
        if (h.status() != ComponentHealth.Status.UP) {
            return h;
        }
        DependencyGraph g = packs.graph();
        for (String n : parts(g, id)) {
            ComponentHealth part = n.startsWith("pack:") ? packs.health(n.substring(5)) : sources.health(n.substring(10));
            if (part.status() != ComponentHealth.Status.UP) {
                h = h.with(new ComponentHealth(ComponentHealth.Status.DEGRADED, part.reasons().isEmpty()
                        ? List.of(part.status().name().toLowerCase(java.util.Locale.ROOT)) : part.reasons(), part.checkedAt()), n);
            }
        }
        return h;
    }

    /** The module's activity: the sum of its parts' counters (its own gate counts its refusals). */
    public ComponentStats stats(String id) {
        LifecycleMachine m = machines.get(id);
        ComponentStats own = m == null ? ComponentStats.empty(Instant.now()) : m.stats();
        ComponentStats.Counts total = own.total();
        ComponentStats.Counts window = own.window();
        long inFlight = own.inFlight();
        long queue = own.queueDepth();
        Instant last = own.lastActivity();
        ComponentStats.Latency latency = own.latency();
        DependencyGraph g = packs.graph();
        for (String n : parts(g, id)) {
            ComponentStats s = n.startsWith("pack:") ? packs.stats(n.substring(5)) : sources.stats(n.substring(10));
            total = total.plus(s.total());
            window = window.plus(s.window());
            inFlight += s.inFlight();
            queue += s.queueDepth();
            if (s.lastActivity() != null && (last == null || s.lastActivity().isAfter(last))) {
                last = s.lastActivity();
            }
            if (s.latency().samples() > latency.samples()) {
                latency = s.latency();                              // the busiest part's percentiles: a sum of percentiles means nothing
            }
        }
        return new ComponentStats(own.since(), total, window, own.windowLength(), latency, queue, inFlight, last);
    }

    /** The module as the kernel's one {@link Lifecycle} contract. */
    public Lifecycle lifecycle(String id) {
        ModuleLifecycles self = this;
        return new Lifecycle() {
            public Type type() {
                return Type.MODULE;
            }

            public String name() {
                return id;
            }

            public LifecycleState state() {
                return self.state(id);
            }

            public void init(String actor) {
                self.init(id, actor);
            }

            public void start(String actor) {
                self.start(id, actor);
            }

            public void suspend(String actor) {
                self.suspend(id, actor);
            }

            public void resume(String actor) {
                self.resume(id, actor);
            }

            public void shutdown(String actor) {
                self.shutdown(id, actor);
            }

            public ComponentHealth health() {
                return self.health(id);
            }

            public ComponentStats stats() {
                return self.stats(id);
            }
        };
    }
}
