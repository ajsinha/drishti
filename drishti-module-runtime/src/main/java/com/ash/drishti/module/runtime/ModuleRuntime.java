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
package com.ash.drishti.module.runtime;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The module runtime (KERNEL_AND_MODULES.md sections 4, 9.8, 10.4 and 12): discovers the modules of the module folders, verifies each
 * (V1-V8), resolves their order, and loads each with its own class loader and a child Spring context under the host's context; keeps the
 * live route table and the registry of contributions; unloads a module in the design's order, with a leak check of its class loader. A
 * refused module is absent: no class loaded, no route, only its Health row. With no module folder (or an empty one) it does nothing.
 */
public final class ModuleRuntime implements AutoCloseable {

    /** How a module leaves: switched off (pages 404, may come back) or removed (pages 410, tombstones). */
    public enum Leave { DISABLE, REMOVE }

    /** What an unload did: each step's line, the failures (retried at the next unload) and the leak check's verdict (null when not hot). */
    public record UnloadReport(String module, List<String> steps, List<String> failures, Boolean leakFree) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ModuleRuntime.class);

    private final KernelFacts facts;
    private final ApplicationContext parent;
    private final ClassLoader kernelLoader;
    private final ModuleHost host;
    private final Path tombstones;
    private final RouteTable routes = new RouteTable();
    private final Contributions contributions = new Contributions();
    private final Claims claims;
    private final Map<String, LoadedModule> loaded = new ConcurrentHashMap<>();
    private final Map<String, ModuleCandidate> known = new ConcurrentHashMap<>();
    private final Map<String, ModuleHealth> health = new ConcurrentHashMap<>();
    private final Map<String, ModuleClassLoader> retained = new ConcurrentHashMap<>();

    /**
     * @param facts        what the host is (versions, reserved names, licence, budget, shared libraries)
     * @param parent       the host's Spring context, parent of every module's (null for none)
     * @param kernelLoader the loader the shared list is asked of (the kernel's)
     * @param host         the host's part of loading and unloading
     * @param tombstones   the folder of tombstone files ({@code modules/tombstones/}), or null for none
     */
    public ModuleRuntime(KernelFacts facts, ApplicationContext parent, ClassLoader kernelLoader, ModuleHost host, Path tombstones) {
        this.facts = facts;
        this.parent = parent;
        this.kernelLoader = kernelLoader == null ? ModuleRuntime.class.getClassLoader() : kernelLoader;
        this.host = host == null ? new ModuleHost() { } : host;
        this.tombstones = tombstones;
        this.claims = new Claims(facts);
    }

    /**
     * Discovers, verifies, orders and loads every module of the folders. {@code enabled} is the site switch (V9: off is not a refusal).
     * Returns every module's Health row.
     */
    public List<ModuleHealth> start(List<Path> folders, Map<String, String> active, Predicate<String> enabled) {
        ModuleDiscovery.Found found = ModuleDiscovery.discover(folders, active == null ? Map.of() : active);
        found.refused().forEach((id, e) -> refuse(id, "", e));
        KernelFacts f = facts;
        if (facts.knownKinds() != null) {
            Set<String> kinds = new HashSet<>(facts.knownKinds());
            found.candidates().forEach(c -> c.manifest().kinds().values().forEach(kinds::addAll));
            f = facts.withKnownKinds(kinds);
        }
        ModuleVerifier verifier = new ModuleVerifier(f);
        Map<String, List<String>> notes = new LinkedHashMap<>();
        Map<String, ModuleRefusedException> refused = new LinkedHashMap<>(found.refused());
        List<ModuleCandidate> ok = new ArrayList<>();
        for (ModuleCandidate c : found.candidates()) {
            known.put(c.id(), c);
            try {
                notes.put(c.id(), verifier.verify(c));
                ok.add(c);
            } catch (ModuleRefusedException e) {
                refused.put(c.id(), e);
                refuse(c.id(), c.manifest().version(), e);
            }
        }
        DependencyResolver.Resolution r = DependencyResolver.resolve(ok, refused);
        r.refused().forEach((id, e) -> refuse(id, known.get(id).manifest().version(), e));
        for (ModuleCandidate c : r.order()) {
            if (loaded.containsKey(c.id())) {
                continue;
            }
            boolean requiresAbsent = c.manifest().requires().stream()
                    .anyMatch(q -> !q.optional() && !loaded.containsKey(q.id()));
            if (enabled != null && !enabled.test(c.id())) {
                health.put(c.id(), new ModuleHealth(c.id(), c.manifest().version(), ModuleHealth.State.OFF, null, "administrator",
                        notes.get(c.id())));
            } else if (!host.placedHere(c.manifest())) {
                health.put(c.id(), new ModuleHealth(c.id(), c.manifest().version(), ModuleHealth.State.NOT_PLACED, null,
                        "no part of it is placed on this node (config/placement.yaml)", notes.get(c.id())));
            } else if (requiresAbsent) {
                health.put(c.id(), new ModuleHealth(c.id(), c.manifest().version(), ModuleHealth.State.OFF, null,
                        "a module it requires is off", notes.get(c.id())));
            } else {
                load(c, notes.get(c.id()));
            }
        }
        return health();
    }

    /** Loads one verified candidate (after its dependencies); a failure refuses it and leaves nothing behind. */
    public synchronized LoadedModule load(ModuleCandidate c, List<String> notes) {
        known.put(c.id(), c);
        List<String> n = new ArrayList<>(notes == null ? List.of() : notes);
        List<RouteTable.Route> rs;
        try {
            rs = routesOf(c, n);
            claims.take(c, rs);
        } catch (ModuleRefusedException e) {
            refuse(c.id(), c.manifest().version(), e);
            return null;
        }
        ModuleClassLoader loader = null;
        AnnotationConfigApplicationContext ctx = null;
        Thread t = Thread.currentThread();
        ClassLoader before = t.getContextClassLoader();
        try {
            DmodManifest.JavaPart java = c.manifest().java();
            if (java != null) {
                List<URL> urls = new ArrayList<>();
                for (String j : java.jars()) {
                    urls.add(c.dir().resolve("java").resolve(j).toUri().toURL());
                }
                loader = new ModuleClassLoader(c.id(), urls.toArray(URL[]::new), kernelLoader, facts.shared());
                ctx = new AnnotationConfigApplicationContext();
                ctx.setId("module:" + c.id());
                ctx.setDisplayName("module " + c.manifest().label());
                ctx.setClassLoader(loader);
                if (parent != null) {
                    ctx.setParent(parent);
                }
                List<Class<?>> classes = new ArrayList<>();
                for (String s : java.spring()) {
                    classes.add(loader.loadClass(s));
                }
                if (java.entry() != null) {
                    classes.add(loader.loadClass(java.entry()));
                }
                t.setContextClassLoader(loader);
                if (!classes.isEmpty()) {
                    ctx.register(classes.toArray(Class<?>[]::new));
                }
                ctx.refresh();
            }
            LoadedModule m = new LoadedModule(c, loader, ctx, List.copyOf(rs), List.copyOf(n));
            routes.register(c.id(), rs);
            contributions.add(c.id(), Contributions.Surface.ROUTE, rs.size() + " routes", null, () -> { });
            loaded.put(c.id(), m);
            host.started(m, contributions);
            health.put(c.id(), new ModuleHealth(c.id(), c.manifest().version(), ModuleHealth.State.LOADED, null, null, n));
            host.event("module.started", c.id());
            LOG.info("module {} loaded ({} routes)", c.manifest().label(), rs.size());
            return m;
        } catch (ClassNotFoundException | MalformedURLException | RuntimeException | LinkageError e) {
            LoadedModule half = loaded.remove(c.id());
            routes.remove(c.id());
            claims.release(c.id());
            for (int step = 1; step <= 9; step++) {
                contributions.withdraw(c.id(), step);
            }
            closeQuietly(ctx, loader);
            String why = e instanceof ClassNotFoundException ? "class " + e.getMessage() + " is not in its jars or the kernel API" : e.toString();
            refuse(c.id(), c.manifest().version(), new ModuleRefusedException("START", c.manifest().label() + ": did not start: " + why, e));
            if (half != null) {
                LOG.debug("module {} withdrawn after a failed start", half.id());
            }
            return null;
        } finally {
            t.setContextClassLoader(before);
        }
    }

    /** The routes a module declares: its API namespace and its console plugin's routes (format 2), all under its own namespace. */
    private static List<RouteTable.Route> routesOf(ModuleCandidate c, List<String> notes) {
        DmodManifest m = c.manifest();
        List<RouteTable.Route> out = new ArrayList<>();
        if (m.api() != null) {
            out.add(new RouteTable.Route(m.id(), m.version(), "api", "/api/v1/" + m.api().prefix() + "/{rest...}", RouteTable.Kind.API,
                    null, null, null, null, RouteTable.State.ACTIVE));
        }
        String plugin = DmodManifest.str(m.console().get("plugin"));
        if (plugin == null) {
            return out;
        }
        Map<String, Object> p = c.yaml(plugin, "V1");
        if (!"2".equals(DmodManifest.str(p.get("format")))) {
            notes.add("console plugin format 1 is read for one release; its routes are not in the route table (move to format 2)");
            return out;
        }
        Set<String> ids = new HashSet<>();
        if (p.get("routes") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> r = DmodManifest.map(o);
                String id = DmodManifest.str(r.get("id"));
                String path = DmodManifest.str(r.get("path"));
                if (id == null || path == null || !path.startsWith("/") || path.contains("..") || !ids.add(id)) {
                    throw new ModuleRefusedException("V1", m.label() + ": " + plugin + ": a route needs a unique id and a path starting with / ("
                            + id + " " + path + ")");
                }
                RouteTable.Kind kind = r.get("sutra") != null ? RouteTable.Kind.SUTRA : r.get("moved") != null ? RouteTable.Kind.MOVED
                        : RouteTable.Kind.PAGE;
                String target = DmodManifest.str(r.get(kind == RouteTable.Kind.SUTRA ? "sutra" : kind == RouteTable.Kind.MOVED ? "moved" : "page"));
                out.add(new RouteTable.Route(m.id(), m.version(), id, "/m/" + m.id() + path, kind, target, DmodManifest.str(r.get("function")),
                        DmodManifest.str(r.get("permission")), DmodManifest.str(r.get("alias")), RouteTable.State.ACTIVE));
            }
        }
        return out;
    }

    /**
     * Unloads a module in the design's order (section 12.2). Each step runs even when an earlier one failed. {@code hot} drops the class
     * loader now and runs the leak check (P5, and every test); otherwise the loader stays loaded but unreachable until the next restart (P2).
     * Refused while another loaded module requires it.
     */
    public synchronized UnloadReport unload(String id, Leave leave, boolean hot) {
        if (!loaded.containsKey(id)) {
            throw new IllegalArgumentException(id + " is not loaded");
        }
        for (LoadedModule other : loaded.values()) {
            if (other.manifest().requires().stream().anyMatch(q -> !q.optional() && q.id().equals(id))) {
                throw new IllegalStateException(id + " is required by " + other.manifest().label() + "; unload that first");
            }
        }
        List<String> steps = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        String version = known.get(id).manifest().version();
        // 1 gate closes
        health.put(id, new ModuleHealth(id, version, ModuleHealth.State.LEAVING, null, null, loaded.get(id).notes()));
        RouteTable.Snapshot s = routes.state(id, leave == Leave.REMOVE ? RouteTable.State.TOMBSTONE : RouteTable.State.OFF);
        failures.addAll(contributions.withdraw(id, 1));
        steps.add("1 gate closed: " + s.of(id).size() + " routes " + (leave == Leave.REMOVE ? "tombstoned" : "off") + " (table v" + s.version() + ")");
        // 2 tell
        step(failures, () -> host.event("module.leaving", id));
        steps.add("2 told: module.leaving");
        // 3 drain
        step(failures, () -> host.drain(id));
        steps.add("3 drained");
        // 4-6 contributions, content, console
        for (int step = 4; step <= 6; step++) {
            int before = contributions.of(id).size();
            failures.addAll(contributions.withdraw(id, step));
            steps.add(step + " withdrew " + (before - contributions.of(id).size()) + (step == 4 ? " contributions" : step == 5 ? " content" : " console surfaces"));
        }
        claims.release(id);
        // 7 server (in its own frame, so nothing here holds the loader during the leak check)
        LeakCheck check = stopServerPart(id, hot, failures);
        Boolean leakFree = null;
        if (check == null) {
            steps.add("7 stopped" + (hot ? "" : ": a class loader, if any, kept until the next restart"));
        } else {
            leakFree = check.collected(20);
            if (!leakFree) {
                failures.add(check.words());
            }
            steps.add("7 stopped: context closed, class loader dropped (" + (leakFree ? "leak-free" : "LEAK") + ")");
        }
        // 8 mark saved work
        if (leave == Leave.REMOVE) {
            step(failures, () -> writeTombstone(id, version));
            steps.add("8 tombstone written" + (tombstones == null ? " (no tombstone folder here)" : ""));
        } else {
            steps.add("8 nothing to mark (off)");
        }
        // 9 record
        failures.addAll(contributions.withdraw(id, 9));
        health.put(id, new ModuleHealth(id, version, leave == Leave.REMOVE ? ModuleHealth.State.REMOVED : ModuleHealth.State.OFF, null,
                leave == Leave.REMOVE ? "no longer installed" : "administrator", failures));
        step(failures, () -> host.event(leave == Leave.REMOVE ? "module.removed" : "module.stopped", id));
        steps.add("9 recorded");
        LOG.info("module {} {}: {}", id, leave == Leave.REMOVE ? "removed" : "off", failures.isEmpty() ? "clean" : failures);
        return new UnloadReport(id, List.copyOf(steps), List.copyOf(failures), leakFree);
    }

    /** Step 7: stop the entry, close the child context; hot: drop the loader and return its leak check (null when nothing to check). */
    private LeakCheck stopServerPart(String id, boolean hot, List<String> failures) {
        LoadedModule m = loaded.remove(id);
        Thread t = Thread.currentThread();
        ClassLoader before = t.getContextClassLoader();
        try {
            if (m.loader() != null) {
                t.setContextClassLoader(m.loader());
            }
            step(failures, () -> host.stopping(m));
            failures.addAll(contributions.withdraw(id, 7));
            if (m.context() != null) {
                step(failures, m.context()::close);
            }
        } finally {
            t.setContextClassLoader(before);
        }
        if (m.loader() == null) {
            return null;
        }
        if (!hot) {
            retained.put(id, m.loader());
            return null;
        }
        LeakCheck check = new LeakCheck(id, m.loader());
        closeQuietly(null, m.loader());
        return check;
    }

    private void writeTombstone(String id, String version) {
        if (tombstones == null) {
            return;
        }
        ModuleCandidate c = known.get(id);
        StringBuilder y = new StringBuilder("# the module's names, kept for saved work (needs module " + id + ")\n");
        y.append("module: ").append(id).append("\nversion: ").append(version).append("\nremoved: ").append(Instant.now()).append('\n');
        y.append("routes:\n");
        routes.snapshot().of(id).forEach(r -> y.append("  - {id: ").append(r.id()).append(", path: \"").append(r.path()).append("\"}\n"));
        y.append("claims:\n");
        Claims.of(c, routes.snapshot().of(id)).forEach(cl -> y.append("  - \"").append(cl).append("\"\n"));
        Dmod.write(tombstones.resolve(id + ".yaml"), y.toString());
    }

    private static void step(List<String> failures, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            failures.add(e.toString());
        }
    }

    private static void closeQuietly(AnnotationConfigApplicationContext ctx, ModuleClassLoader loader) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (RuntimeException e) {
                LOG.debug("closing a module context", e);
            }
        }
        if (loader != null) {
            LeakCheck.clearSharedCaches(loader);
            try {
                loader.close();
            } catch (IOException e) {
                LOG.debug("closing a module loader", e);
            }
        }
    }

    private void refuse(String id, String version, ModuleRefusedException e) {
        LOG.warn("module refused: {}", e.getMessage());
        health.put(id, new ModuleHealth(id, version, ModuleHealth.State.REFUSED, e.check(), e.getMessage(), List.of()));
    }

    /** The live route table. */
    public RouteTable routes() {
        return routes;
    }

    /** The registry of contributions. */
    public Contributions contributions() {
        return contributions;
    }

    /** A loaded module. */
    public Optional<LoadedModule> loaded(String id) {
        return Optional.ofNullable(loaded.get(id));
    }

    /** Every Health row, by id. */
    public List<ModuleHealth> health() {
        return health.values().stream().sorted(java.util.Comparator.comparing(ModuleHealth::id)).toList();
    }

    /** One module's Health row. */
    public Optional<ModuleHealth> health(String id) {
        return Optional.ofNullable(health.get(id));
    }

    /** Stops every loaded module, dependants first (a shutdown: no tombstones, routes off). */
    @Override
    public synchronized void close() {
        List<String> order = new ArrayList<>(loaded.keySet());
        while (!order.isEmpty()) {
            boolean progressed = false;
            for (String id : List.copyOf(order)) {
                try {
                    unload(id, Leave.DISABLE, false);
                    order.remove(id);
                    progressed = true;
                } catch (IllegalStateException required) {
                    // a dependant is still loaded; it goes first
                }
            }
            if (!progressed) {
                break;
            }
        }
        retained.values().forEach(l -> closeQuietly(null, l));
        retained.clear();
    }
}
