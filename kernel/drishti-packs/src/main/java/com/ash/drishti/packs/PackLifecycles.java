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
package com.ash.drishti.packs;

import com.ash.drishti.api.lifecycle.ComponentHealth;
import com.ash.drishti.api.lifecycle.ComponentStats;
import com.ash.drishti.api.lifecycle.ComponentSuspended;
import com.ash.drishti.api.lifecycle.DependencyGraph;
import com.ash.drishti.api.lifecycle.Lifecycle;
import com.ash.drishti.api.lifecycle.LifecycleMachine;
import com.ash.drishti.api.lifecycle.LifecycleState;
import com.ash.drishti.api.lifecycle.Transition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every pack's lifecycle on this node (docs/architecture/LIFECYCLE.md section 3): one {@link LifecycleMachine} per pack instance, the gate
 * every request for a pack's kinds and mnemonics passes ({@link #requireServing}), the cascades the {@link DependencyGraph} computes, and the
 * health and stats of each pack. What a transition does to the server (load, unload, the pack's own connectors) is the host's
 * {@link Actions}; this class decides when and in what order, and refuses what the table or the graph does not allow.
 *
 * <ul>
 *   <li>init: the pack read and validated with the running ones ({@link Actions#validate}); nothing registered or served.</li>
 *   <li>start: its kinds, mnemonics, Sutras, routes and connector templates registered ({@link Actions#load}); served.</li>
 *   <li>suspend: the gate closes (its kinds and mnemonics answer {@code 503 DRS-1037}); the connectors only it uses stop pulling
 *       ({@link Actions#suspendParts}); everything registered, every cache, kept. Requests in flight finish: nothing is torn down.</li>
 *   <li>resume: the gate opens and those connectors pull again; nothing is reloaded, so the answers are the ones it gave before.</li>
 *   <li>shutdown: unregistered ({@link Actions#unload}): nothing of it remains; harmless twice.</li>
 * </ul>
 */
public final class PackLifecycles {

    /** What a pack transition does to the server: the pack reloader and the connector registry, wired by the server. */
    public interface Actions {
        /** Reads {@code pack} and checks it with the running packs, changing nothing; the pack as read, or throws with the words of what is wrong. */
        Pack validate(String pack);

        /** Registers and serves {@code pack} (and the packs it extends, already running or started first by the graph's order). */
        void load(String pack, String actor);

        /** Unregisters {@code pack}: its kinds, mnemonics, Sutras, routes, templates and the connectors only it used. */
        void unload(String pack, String actor);

        /** Suspends the connectors only {@code pack} uses (shared ones keep serving); returns their names. */
        List<String> suspendParts(String pack, String actor);

        /** Resumes the connectors a suspend of {@code pack} suspended. */
        void resumeParts(String pack, List<String> connectors, String actor);
    }

    /** What the graph needs to know of the rest of the node: every connector and engine that exists here, and each module's parts. */
    public record World(Supplier<Set<String>> connectors, Supplier<Set<String>> engines, Supplier<Map<String, List<String>>> modulePacks,
            Supplier<Map<String, List<String>>> moduleConnectors, Function<String, ComponentHealth> connectorHealth,
            Function<String, ComponentHealth> engineHealth) {
        public static final World NONE = new World(Set::of, Set::of, Map::of, Map::of, c -> null, e -> null);
    }

    private final PackRegistry registry;
    private final Map<String, LifecycleMachine> machines = new java.util.concurrent.ConcurrentSkipListMap<>();   // by name: stable listings
    /** Packs initialized and not started yet, as read: in the graph, so a start checks what they declare. */
    private final Map<String, Pack> pending = new ConcurrentHashMap<>();
    /** The connectors a pack's suspend suspended, given back at its resume. */
    private final Map<String, List<String>> suspendedParts = new ConcurrentHashMap<>();
    private final List<LifecycleMachine.Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Actions actions;
    private volatile World world = World.NONE;
    private record Owners(long version, Map<String, String> byKind) {}
    private volatile Owners owners = new Owners(-1, Map.of());

    public PackLifecycles(PackRegistry registry) {
        this.registry = registry;
    }

    /** Wires what transitions do (the server's pack reloader) and what the graph reads. */
    public void wire(Actions a, World w) {
        this.actions = a;
        this.world = w == null ? World.NONE : w;
    }

    /** Hears every pack transition: the audit, the event bus, the cluster digest. */
    public void onTransition(LifecycleMachine.Listener l) {
        listeners.add(l);
        machines.values().forEach(m -> m.listen(l));
    }

    /**
     * The packs the server loaded (at boot, or by the reloader outside the lifecycle: a folder that appeared, a deploy): each gets a machine
     * that is RUNNING; packs that left have their machine shut down. Called after every change of the registry.
     */
    public void sync(String actor) {                 // not synchronized: the reloader calls it holding its own lock (no lock-order cycle)
        Set<String> loaded = new LinkedHashSet<>();
        registry.packs().forEach(p -> loaded.add(p.name()));
        for (String n : loaded) {
            LifecycleMachine m = machines.get(n);
            if (m == null || m.state() == LifecycleState.SHUTDOWN || m.state() == LifecycleState.FAILED || m.state() == LifecycleState.NEW) {
                LifecycleMachine fresh = newMachine(n);
                fresh.transition(Transition.INIT, actor, null);           // read and validated by the loader that loaded it
                fresh.transition(Transition.START, actor, null);          // registered by it
            }
        }
        machines.forEach((n, m) -> {
            if (!loaded.contains(n) && (m.state() == LifecycleState.RUNNING || m.state() == LifecycleState.SUSPENDED)) {
                m.transition(Transition.SHUTDOWN, actor, null);           // unloaded outside the lifecycle (its folder deleted)
            }
        });
    }

    private LifecycleMachine newMachine(String name) {
        LifecycleMachine m = new LifecycleMachine(Lifecycle.Type.PACK, name);
        listeners.forEach(m::listen);
        machines.put(name, m);
        return m;
    }

    public LifecycleState state(String pack) {
        LifecycleMachine m = machines.get(pack);
        return m == null ? LifecycleState.NEW : m.state();
    }

    public java.util.Optional<LifecycleMachine> machine(String pack) {
        return java.util.Optional.ofNullable(machines.get(pack));
    }

    /** Every pack with a machine, with its row (state, since, by). */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        machines.values().forEach(m -> out.add(m.describe()));
        return out;
    }

    // -- the gate ------------------------------------------------------------------------------------------------------------------

    private Map<String, String> kindOwner() {
        Owners o = owners;
        long v = registry.version();
        if (o.version() != v) {
            Map<String, String> m = new LinkedHashMap<>();
            registry.packs().forEach(p -> p.kinds().forEach(k -> m.put(k, p.name())));
            o = new Owners(v, m);
            owners = o;
        }
        return o.byKind();
    }

    /**
     * The gate for a request on {@code kind} (a view, a mnemonic, a record, a form, a calc): {@link ComponentSuspended} ({@code 503 DRS-1037})
     * when the kind's pack is suspended; counted in the pack's stats either way. A kind no pack owns passes.
     */
    public void requireServing(String kind) {
        String owner = kindOwner().get(kind);
        LifecycleMachine m = owner == null ? null : machines.get(owner);
        if (m == null) {
            return;
        }
        if (m.state() == LifecycleState.SUSPENDED) {
            m.check();                                                    // counts the refusal and throws, naming who suspended it
        }
        m.recorder().admitted();
    }

    /** True when the kind's pack is suspended here. */
    public boolean suspended(String kind) {
        String owner = kindOwner().get(kind);
        return owner != null && state(owner) == LifecycleState.SUSPENDED;
    }

    // -- the transitions -----------------------------------------------------------------------------------------------------------

    private Actions actions() {
        Actions a = actions;
        if (a == null) {
            throw new IllegalStateException("the pack lifecycle is not wired yet: the server is still starting");
        }
        return a;
    }

    /** {@code init}: a pack on disk that is not running read and validated; a pack shut down gets a new instance. */
    public synchronized void init(String pack, String actor) {
        LifecycleMachine m = machines.get(pack);
        if (m == null || m.state() == LifecycleState.SHUTDOWN || m.state() == LifecycleState.FAILED) {
            m = newMachine(pack);
        }
        m.transition(Transition.INIT, actor, () -> pending.put(pack, actions().validate(pack)));
    }

    /**
     * {@code start}: refused in one sentence when the graph has a cycle through the pack or a dependency of it does not exist; else the
     * packs it extends that are not running start first (dependencies first), then the pack. A pack never initialized is initialized first.
     */
    public synchronized void start(String pack, String actor) {
        String node = DependencyGraph.id(Lifecycle.Type.PACK, pack);
        LifecycleMachine m = machines.get(pack);
        if (m != null && (m.state() == LifecycleState.RUNNING || m.state() == LifecycleState.SUSPENDED)) {
            m.transition(Transition.START, actor, null);                    // refused by the table, naming the state
        }
        if (m == null || m.state() != LifecycleState.INITIALISED) {
            init(pack, actor);                                              // read and validated first: the graph then knows what it declares
            m = machines.get(pack);
        }
        DependencyGraph g = graph();
        String problem = g.startProblem(List.of(node));
        if (problem != null) {
            m.refuse(Transition.START, actor, problem);                     // it stays INITIALISED: fix the dependency, then start
        }
        for (String n : g.startOrder(List.of(node))) {
            if (n.startsWith("pack:")) {
                String p = n.substring("pack:".length());
                LifecycleMachine pm = machines.get(p);
                if (pm == null || pm.state() == LifecycleState.SHUTDOWN || pm.state() == LifecycleState.FAILED || pm.state() == LifecycleState.NEW) {
                    init(p, actor);
                    pm = machines.get(p);
                }
                if (pm.state() == LifecycleState.INITIALISED) {
                    pm.transition(Transition.START, actor, () -> actions().load(p, actor));
                    pending.remove(p);
                }
            }
        }
    }

    /**
     * {@code suspend}: the packs that extend it are suspended first (they serve its kinds), then the pack: the gate closes, then the
     * connectors only it uses stop pulling. Packs that only share a connector with it are untouched.
     */
    public synchronized List<String> suspend(String pack, String actor) {
        LifecycleMachine self = machines.get(pack);
        if (self == null || self.state() != LifecycleState.RUNNING) {
            refused(pack, Transition.SUSPEND, actor);
        }
        List<String> done = new ArrayList<>();
        for (String n : graph().suspendCascade(DependencyGraph.id(Lifecycle.Type.PACK, pack))) {
            if (!n.startsWith("pack:")) {
                continue;
            }
            String p = n.substring("pack:".length());
            LifecycleMachine m = machines.get(p);
            if (m != null && m.state() == LifecycleState.RUNNING) {
                m.transition(Transition.SUSPEND, actor, () -> suspendedParts.put(p, List.copyOf(actions().suspendParts(p, actor))));
                done.add(p);
            }
        }
        return done;
    }

    /**
     * The pack switch's effect here (docs/plans/MODULE_RATIONALISATION.md, gap G1): switched off, the pack is suspended (its gate closes and the
     * connectors only it uses stop pulling); switched on, it is resumed. One state, not two: Admin's switch and the lifecycle's suspend are the
     * same thing for a running pack. A pack that is not in the matching state (never started here, already suspended) is left as it is.
     *
     * @return the packs whose state changed
     */
    public synchronized List<String> follow(String pack, boolean enabled, String actor) {
        LifecycleMachine m = machines.get(pack);
        if (m == null) {
            return List.of();
        }
        if (!enabled && m.state() == LifecycleState.RUNNING) {          // this pack only: a pack that extends it has its own switch
            m.transition(Transition.SUSPEND, actor, () -> suspendedParts.put(pack, List.copyOf(actions().suspendParts(pack, actor))));
            return List.of(pack);
        }
        if (enabled && m.state() == LifecycleState.SUSPENDED) {
            m.transition(Transition.RESUME, actor, () -> actions().resumeParts(pack, suspendedParts.getOrDefault(pack, List.of()), actor));
            suspendedParts.remove(pack);
            return List.of(pack);
        }
        return List.of();
    }

    /** {@code resume}: the pack first (its connectors pull again, the gate opens), then the packs its suspend suspended with it. */
    public synchronized List<String> resume(String pack, String actor) {
        LifecycleMachine self = machines.get(pack);
        if (self == null || self.state() != LifecycleState.SUSPENDED) {
            refused(pack, Transition.RESUME, actor);
        }
        List<String> order = new ArrayList<>(graph().suspendCascade(DependencyGraph.id(Lifecycle.Type.PACK, pack)));
        java.util.Collections.reverse(order);                               // the dependency before its dependants
        List<String> done = new ArrayList<>();
        for (String n : order) {
            if (!n.startsWith("pack:")) {
                continue;
            }
            String p = n.substring("pack:".length());
            LifecycleMachine m = machines.get(p);
            if (m != null && m.state() == LifecycleState.SUSPENDED) {
                m.transition(Transition.RESUME, actor, () -> actions().resumeParts(p, suspendedParts.getOrDefault(p, List.of()), actor));
                suspendedParts.remove(p);
                done.add(p);
            }
        }
        return done;
    }

    /**
     * {@code shutdown}: refused while a running pack extends it, naming them, unless {@code cascade} (then they shut down first, dependants
     * first). Unregisters everything of the pack; harmless for a pack already shut down.
     */
    public synchronized List<String> shutdown(String pack, String actor, boolean cascade) {
        LifecycleMachine self = machines.get(pack);
        if (self == null || self.state() == LifecycleState.SHUTDOWN) {
            return List.of();
        }
        DependencyGraph g = graph();
        String node = DependencyGraph.id(Lifecycle.Type.PACK, pack);
        Set<String> running = new LinkedHashSet<>();
        machines.forEach((n, m) -> {
            if (m.state() == LifecycleState.RUNNING || m.state() == LifecycleState.SUSPENDED) {
                running.add(DependencyGraph.id(Lifecycle.Type.PACK, n));
            }
        });
        List<String> dependants = g.runningDependants(node, running);
        if (!dependants.isEmpty() && !cascade) {
            self.refuse(Transition.SHUTDOWN, actor, String.join(", ", dependants) + " depend on it: shut those down first, or ask for the cascade");
        }
        List<String> done = new ArrayList<>();
        for (String n : g.shutdownCascade(node)) {
            if (!n.startsWith("pack:") || !running.contains(n)) {
                continue;
            }
            String p = n.substring("pack:".length());
            LifecycleMachine m = machines.get(p);
            List<String> parts = suspendedParts.remove(p);
            m.transition(Transition.SHUTDOWN, actor, () -> {
                if (parts != null && !parts.isEmpty()) {
                    actions().resumeParts(p, parts, actor);                  // its connectors are the unload's to stop, not left paused
                }
                actions().unload(p, actor);
            });
            done.add(p);
        }
        return done;
    }

    private void refused(String pack, Transition t, String actor) {
        LifecycleMachine m = machines.get(pack);
        if (m == null) {
            throw new com.ash.drishti.api.lifecycle.LifecycleRefused(Lifecycle.Type.PACK, pack, LifecycleState.NEW, t);
        }
        m.transition(t, actor, null);                                       // refused by the table, naming the state
    }

    // -- the graph, health and stats -----------------------------------------------------------------------------------------------

    /**
     * The node's dependency graph as the packs, modules and connectors declare it (LIFECYCLE.md section 5): a pack's {@code extends:}, its
     * connectors ({@code connectors:} and {@code routes:}; a template it declares {@code enabled: false} is optional) and its
     * {@code uses.engines} ({@code optional:} as declared); a module's packs and connectors.
     */
    public DependencyGraph graph() {
        World w = world;
        DependencyGraph.Builder b = DependencyGraph.builder();
        w.connectors().get().forEach(c -> b.node("connector:" + c));
        w.engines().get().forEach(e -> b.node(DependencyGraph.engine(e)));
        Map<String, Pack> packs = new LinkedHashMap<>();
        registry.packs().forEach(p -> packs.put(p.name(), p));
        pending.forEach(packs::putIfAbsent);
        machines.keySet().forEach(n -> b.node("pack:" + n));
        for (Pack p : packs.values()) {
            String from = "pack:" + p.name();
            b.node(from);
            p.parents().forEach(parent -> b.depends(from, "pack:" + parent, DependencyGraph.Edge.EXTENDS, false));
            Map<String, Map<String, Object>> templates = p.connectorTemplates();
            for (String c : p.connectorRefs()) {
                Map<String, Object> t = templates.get(c);
                boolean optional = t != null && Boolean.FALSE.equals(t.get("enabled"));
                b.depends(from, "connector:" + c, DependencyGraph.Edge.USES, optional);
            }
            usesEngines(p).forEach((engine, optional) -> b.depends(from, DependencyGraph.engine(engine), DependencyGraph.Edge.USES, optional));
        }
        w.modulePacks().get().forEach((module, list) -> {
            b.node("module:" + module);
            list.forEach(p -> b.depends("module:" + module, "pack:" + p, DependencyGraph.Edge.CONTAINS, false));
        });
        w.moduleConnectors().get().forEach((module, list) -> {
            b.node("module:" + module);
            list.forEach(c -> b.depends("module:" + module, "connector:" + c, DependencyGraph.Edge.CONTAINS, false));
        });
        return b.build();
    }

    /** The engines a pack {@code uses} (engine specification section 10.1), each with whether it is optional. */
    static Map<String, Boolean> usesEngines(Pack p) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (p.manifest().get("uses") instanceof Map<?, ?> uses && uses.get("engines") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> e && e.get("engine") != null) {
                    out.put(String.valueOf(e.get("engine")).strip(), Boolean.TRUE.equals(e.get("optional")));
                } else if (o instanceof String s && !s.isBlank()) {
                    out.put(s.strip(), false);
                }
            }
        }
        return out;
    }

    /**
     * The pack's health: its state, then its required dependencies rolled up by the graph (a connector or engine it needs that is DOWN or
     * SUSPENDED makes it DEGRADED, naming it; a parent pack the same). Never blocks: the connectors' and engines' health are field reads.
     */
    public ComponentHealth health(String pack) {
        return health(pack, graph(), new LinkedHashSet<>());
    }

    private ComponentHealth health(String pack, DependencyGraph g, Set<String> seen) {
        Instant now = Instant.now();
        LifecycleMachine m = machines.get(pack);
        ComponentHealth own = m == null ? ComponentHealth.ofState(LifecycleState.NEW, null, now) : m.stateHealth();
        String problem = registry.problems().get(pack);
        if (problem != null && own.status() == ComponentHealth.Status.UP) {
            own = ComponentHealth.of(ComponentHealth.Status.DEGRADED, "its files have a problem (the last good version runs): " + problem, now);
        }
        if (!seen.add(pack)) {
            return own;                                                       // a cycle: refused at start, never followed here
        }
        World w = world;
        return g.rollUp("pack:" + pack, own, node -> {
            if (node.startsWith("pack:")) {
                return health(node.substring(5), g, seen);
            }
            if (node.startsWith("connector:")) {
                ComponentHealth h = w.connectorHealth().apply(node.substring("connector:".length()));
                return h == null ? ComponentHealth.up(now) : h;
            }
            if (node.startsWith("engine:")) {
                ComponentHealth h = w.engineHealth().apply(node.substring("engine:".length()));
                return h == null ? ComponentHealth.up(now) : h;
            }
            return ComponentHealth.up(now);
        });
    }

    /** The pack's activity: requests through its gate (its kinds and mnemonics opened), refusals while suspended, since it started. */
    public ComponentStats stats(String pack) {
        LifecycleMachine m = machines.get(pack);
        return m == null ? ComponentStats.empty(Instant.now()) : m.stats();
    }

    /** The pack as the kernel's one {@link Lifecycle} contract. */
    public Lifecycle lifecycle(String pack) {
        PackLifecycles self = this;
        return new Lifecycle() {
            @Override
            public Type type() {
                return Type.PACK;
            }

            @Override
            public String name() {
                return pack;
            }

            @Override
            public LifecycleState state() {
                return self.state(pack);
            }

            @Override
            public void init(String actor) {
                self.init(pack, actor);
            }

            @Override
            public void start(String actor) {
                self.start(pack, actor);
            }

            @Override
            public void suspend(String actor) {
                self.suspend(pack, actor);
            }

            @Override
            public void resume(String actor) {
                self.resume(pack, actor);
            }

            @Override
            public void shutdown(String actor) {
                self.shutdown(pack, actor, false);
            }

            @Override
            public ComponentHealth health() {
                return self.health(pack);
            }

            @Override
            public ComponentStats stats() {
                return self.stats(pack);
            }
        };
    }
}
