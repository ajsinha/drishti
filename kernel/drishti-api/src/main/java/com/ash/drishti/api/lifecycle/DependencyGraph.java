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
package com.ash.drishti.api.lifecycle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The one dependency graph of the runtime's parts (docs/architecture/LIFECYCLE.md section 5), built from what each part already declares:
 * a pack's {@code extends:} (its parent packs), its connectors ({@code connectors:} and {@code routes:}) and its {@code uses.engines}
 * (engine specification section 10.1); a module's packs ({@code packs.list}) and the connectors it brings. Nodes are written
 * {@code <type>:<name>} ({@code pack:market-risk}, {@code connector:var-engine}, {@code engine:var}, {@code module:banking}).
 *
 * <p>From it, and only from it, the kernel takes: the start order (dependencies first), the cascades of suspend and shutdown (dependants
 * first), the health roll-up, cycle detection and missing dependencies. The rules (LIFECYCLE.md section 5.2):
 * <ul>
 *   <li>{@link Edge#USES} (a pack on a connector or an engine): a suspended or down dependency <b>degrades</b> its dependants: they keep
 *       serving everything that does not read it, and what reads it answers {@code 503 DRS-1037} naming the dependency. A shutdown of the
 *       dependency is refused while a dependant runs, unless the cascade is asked for (then the dependants shut down first).</li>
 *   <li>{@link Edge#EXTENDS} (a pack on its parent): the child serves the parent's kinds, so a suspend or shutdown of the parent
 *       <b>cascades</b>: the children first.</li>
 *   <li>{@link Edge#CONTAINS} (a module on its packs and connectors): the module's transition is its parts' transition: start in the
 *       graph's order (connectors before packs), suspend and shutdown in reverse (packs before connectors).</li>
 * </ul>
 * Immutable; built once per change of the parts (a pack loaded, a module started) with {@link Builder}.
 */
public final class DependencyGraph {

    /** How a dependant depends on a dependency. */
    public enum Edge {
        USES, EXTENDS, CONTAINS
    }

    /** One declared dependency: {@code from} needs {@code to}; an optional one never refuses a start nor degrades. */
    public record Dependency(String from, String to, Edge edge, boolean optional) {}

    private final Set<String> nodes;
    private final List<Dependency> edges;
    private final Map<String, List<Dependency>> out = new LinkedHashMap<>();
    private final Map<String, List<Dependency>> in = new LinkedHashMap<>();

    private DependencyGraph(Set<String> nodes, List<Dependency> edges) {
        this.nodes = Set.copyOf(new LinkedHashSet<>(nodes));
        this.edges = List.copyOf(edges);
        for (Dependency d : edges) {
            out.computeIfAbsent(d.from(), k -> new ArrayList<>()).add(d);
            in.computeIfAbsent(d.to(), k -> new ArrayList<>()).add(d);
        }
    }

    /** {@code pack:market-risk}. */
    public static String id(Lifecycle.Type type, String name) {
        return type.word() + ":" + name;
    }

    /** {@code engine:var}: engines are nodes of the graph but not {@link Lifecycle} parts of the kernel (their lifecycle is the engine spec's). */
    public static String engine(String id) {
        return "engine:" + id;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects the parts that exist and what they declare. */
    public static final class Builder {
        private final Set<String> nodes = new LinkedHashSet<>();
        private final List<Dependency> edges = new ArrayList<>();

        /** A part that exists (a missing dependency is one that no {@code node} names). */
        public Builder node(String id) {
            nodes.add(id);
            return this;
        }

        public Builder depends(String from, String to, Edge edge, boolean optional) {
            if (!from.equals(to)) {
                edges.add(new Dependency(from, to, edge, optional));
            } else {
                edges.add(new Dependency(from, to, edge, false));      // a part on itself: a cycle of one, refused by name
            }
            return this;
        }

        public DependencyGraph build() {
            return new DependencyGraph(nodes, edges);
        }
    }

    public Set<String> nodes() {
        return nodes;
    }

    public List<Dependency> edges() {
        return edges;
    }

    /** What {@code id} depends on directly. */
    public List<Dependency> dependencies(String id) {
        return out.getOrDefault(id, List.of());
    }

    /** What depends on {@code id} directly. */
    public List<Dependency> dependants(String id) {
        return in.getOrDefault(id, List.of());
    }

    // -- checks at start ----------------------------------------------------------------------------------------------------------

    /**
     * The first cycle among the nodes, as the path that closes it ({@code [pack:a, pack:b, pack:a]}), or empty. Optional edges count:
     * a cycle has no order to start in whatever the edges say.
     */
    public List<String> cycle() {
        Map<String, Integer> mark = new LinkedHashMap<>();
        Deque<String> path = new ArrayDeque<>();
        for (String n : allNodes()) {
            List<String> c = cycleFrom(n, mark, path);
            if (!c.isEmpty()) {
                return c;
            }
        }
        return List.of();
    }

    private List<String> cycleFrom(String n, Map<String, Integer> mark, Deque<String> path) {
        Integer m = mark.get(n);
        if (m != null && m == 2) {
            return List.of();
        }
        if (m != null && m == 1) {
            List<String> p = new ArrayList<>(path);
            List<String> c = new ArrayList<>(p.subList(p.indexOf(n), p.size()));
            c.add(n);
            return c;
        }
        mark.put(n, 1);
        path.addLast(n);
        for (Dependency d : dependencies(n)) {
            List<String> c = cycleFrom(d.to(), mark, path);
            if (!c.isEmpty()) {
                return c;
            }
        }
        path.removeLast();
        mark.put(n, 2);
        return List.of();
    }

    /** The required dependencies of {@code id} (transitively through those that exist) that no node names. */
    public List<Dependency> missing(String id) {
        List<Dependency> outList = new ArrayList<>();
        for (Dependency d : dependencies(id)) {
            if (!d.optional() && !nodes.contains(d.to())) {
                outList.add(d);
            }
        }
        return outList;
    }

    /**
     * Refuses a start of {@code ids} in one sentence: the cycle they are part of, or the first required dependency that does not exist.
     * Null when they may start.
     */
    public String startProblem(Collection<String> ids) {
        List<String> c = cycle();
        if (!c.isEmpty() && ids.stream().anyMatch(c::contains)) {
            return "the parts depend on each other in a cycle: " + String.join(" -> ", c) + "; remove one of these dependencies";
        }
        for (String id : order(ids, true)) {
            List<Dependency> m = missing(id);
            if (!m.isEmpty()) {
                Dependency d = m.get(0);
                return id + " needs " + d.to() + ", which does not exist here" + (m.size() > 1 ? " (and " + (m.size() - 1) + " more)" : "")
                        + "; add it, or mark the dependency optional";
            }
        }
        return null;
    }

    // -- orders and cascades -----------------------------------------------------------------------------------------------------

    /**
     * {@code ids} and everything they depend on that exists, dependencies first (the start order); refused with
     * {@link IllegalStateException} naming the cycle when there is one among them.
     */
    public List<String> startOrder(Collection<String> ids) {
        return order(ids, false);
    }

    /** The reverse of the start order: dependants first (the suspend and shutdown order of a module's parts). */
    public List<String> stopOrder(Collection<String> ids) {
        List<String> o = new ArrayList<>(order(ids, false));
        java.util.Collections.reverse(o);
        return o;
    }

    private List<String> order(Collection<String> ids, boolean lenient) {
        LinkedHashSet<String> done = new LinkedHashSet<>();
        for (String id : ids) {
            visit(id, done, new ArrayDeque<>(), lenient);
        }
        return List.copyOf(done);
    }

    private void visit(String n, Set<String> done, Deque<String> path, boolean lenient) {
        if (done.contains(n)) {
            return;
        }
        if (path.contains(n)) {
            if (lenient) {
                return;
            }
            List<String> c = new ArrayList<>(path);
            c = new ArrayList<>(c.subList(c.indexOf(n), c.size()));
            c.add(n);
            throw new IllegalStateException("the parts depend on each other in a cycle: " + String.join(" -> ", c));
        }
        path.addLast(n);
        for (Dependency d : dependencies(n)) {
            if (nodes.contains(d.to())) {
                visit(d.to(), done, path, lenient);
            }
        }
        path.removeLast();
        if (nodes.contains(n)) {
            done.add(n);
        }
    }

    /**
     * What a suspend of {@code id} suspends with it, dependants first, {@code id} last: the parts that extend it (transitively) and, for a
     * module, its packs before its connectors. Parts that only {@link Edge#USES} it are not in the list: they are degraded ({@link #degraded}).
     */
    public List<String> suspendCascade(String id) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        collect(id, set, true);
        return dependantsFirst(set, id);
    }

    /** What a shutdown of {@code id} with the cascade shuts down: every dependant (any edge, transitively) first, then {@code id}. */
    public List<String> shutdownCascade(String id) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        collect(id, set, false);
        return dependantsFirst(set, id);
    }

    private void collect(String id, Set<String> set, boolean suspendOnly) {
        if (!set.add(id)) {
            return;
        }
        for (Dependency d : dependants(id)) {
            if (!suspendOnly || d.edge() == Edge.EXTENDS) {
                collect(d.from(), set, suspendOnly);
            }
        }
        for (Dependency d : dependencies(id)) {
            if (d.edge() == Edge.CONTAINS) {
                collect(d.to(), set, suspendOnly);              // a module's parts go with it
            }
        }
    }

    private List<String> dependantsFirst(Set<String> set, String id) {
        List<String> o = new ArrayList<>(stopOrder(set).stream().filter(set::contains).toList());
        o.remove(id);
        // a module after its parts; any other part after its dependants (the stop order already says so)
        o.add(id);
        return o;
    }

    /** The parts that are running with a required {@link Edge#USES} dependency on {@code id}: degraded while it is suspended or down. */
    public List<String> degraded(String id) {
        return dependants(id).stream().filter(d -> d.edge() == Edge.USES && !d.optional()).map(Dependency::from).distinct().toList();
    }

    /** The parts that still depend on {@code id} (any edge but its own module's containment), among {@code running}. */
    public List<String> runningDependants(String id, Set<String> running) {
        return dependants(id).stream().filter(d -> d.edge() != Edge.CONTAINS).map(Dependency::from).filter(running::contains).distinct().toList();
    }

    // -- health ------------------------------------------------------------------------------------------------------------------

    /**
     * {@code id}'s own health with its required dependencies folded in: a dependency DOWN or SUSPENDED makes the part DEGRADED (never DOWN:
     * it still serves what does not read it), with a reason naming the dependency and its status; DEGRADED dependencies are named too.
     */
    public ComponentHealth rollUp(String id, ComponentHealth own, Function<String, ComponentHealth> healthOf) {
        ComponentHealth h = own;
        for (Dependency d : dependencies(id)) {
            if (d.optional() || d.edge() == Edge.CONTAINS) {
                continue;
            }
            ComponentHealth dep = nodes.contains(d.to()) ? healthOf.apply(d.to()) : null;
            if (dep == null) {
                h = h.with(ComponentHealth.of(ComponentHealth.Status.DEGRADED, "needs " + d.to() + ", which does not exist here", own.checkedAt()), null);
            } else if (dep.status() != ComponentHealth.Status.UP) {
                String why = d.to() + " is " + dep.status().name().toLowerCase(java.util.Locale.ROOT)
                        + (dep.reasons().isEmpty() ? "" : " (" + dep.reasons().get(0) + ")");
                h = h.with(ComponentHealth.of(ComponentHealth.Status.DEGRADED, "needs " + why, own.checkedAt()), null);
            }
        }
        if (own.status() == ComponentHealth.Status.SUSPENDED || own.status() == ComponentHealth.Status.DOWN) {
            return new ComponentHealth(own.status(), h.reasons(), own.checkedAt());
        }
        return h;
    }

    /** Admin's and the REST answer's view: nodes, edges, the start order, and the cycle and missing dependencies if any. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", List.copyOf(nodes));
        m.put("edges", edges.stream().map(d -> Map.of("from", d.from(), "to", d.to(), "edge", d.edge().name(), "optional", d.optional())).toList());
        List<String> c = cycle();
        m.put("cycle", c);
        m.put("startOrder", c.isEmpty() ? startOrder(nodes) : List.of());
        List<String> missing = new ArrayList<>();
        nodes.forEach(n -> missing(n).forEach(d -> missing.add(d.from() + " -> " + d.to())));
        m.put("missing", missing);
        return m;
    }

    private Set<String> allNodes() {
        Set<String> all = new LinkedHashSet<>(nodes);
        edges.forEach(d -> {
            all.add(d.from());
            all.add(d.to());
        });
        return all;
    }
}
