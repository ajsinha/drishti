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
package com.ash.drishti.server.modules.deploy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.module.runtime.Contributions;
import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.module.runtime.LoadedModule;
import com.ash.drishti.module.runtime.ModuleCandidate;
import com.ash.drishti.module.runtime.ModuleDiscovery;
import com.ash.drishti.module.runtime.ModuleHealth;
import com.ash.drishti.module.runtime.ModuleHost;
import com.ash.drishti.module.runtime.ModuleRefusedException;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.module.runtime.ModuleVerifier;
import com.ash.drishti.module.runtime.placement.ModuleParts;
import com.ash.drishti.server.placement.PlacementHost;
import com.ash.drishti.server.placement.PlacementService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/**
 * This node's side of module deployment (KERNEL_AND_MODULES.md sections 4.11.4, 7.6 and 12): the one {@link ModuleRuntime} of the server,
 * the installed modules ({@code modules/installed/<id>/<version>/} and {@code active.yaml}), the staging area of a deployment's phase 1,
 * activation (phase 2), rollback and unload, and the node's copy of the cluster's module registry ({@code registry.json}: every module the
 * cluster deployed, active, off or removed, with the names it owned). A node that holds no part of a module keeps only its registry entry:
 * it forwards the module's requests (placement) and, once the module is removed, answers its paths 410 and marks saved work that used it.
 * Every step is audited by the coordinator; the node logs.
 */
public final class ModuleNode implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleNode.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A module's state in the cluster. */
    public enum State { ACTIVE, OFF, REMOVED }

    /**
     * One module of the cluster's registry.
     *
     * @param claims  the names it owns (API prefix, functions, kinds, lenses), kept after removal so saved work that uses one is marked
     * @param routes  its console route paths ({@code /m/<id>/…})
     * @param manifest its {@code module.yaml}, so a node that holds nothing of it still knows its parts
     */
    public record Entry(String id, String version, State state, String title, String prefix, List<String> claims, List<String> routes,
            String manifest, String by, String at, String deployment) {
    }

    private final Path root;
    private final Path installed;
    private final Path staging;
    private final Path tombstones;
    private final Path activeFile;
    private final Path registryFile;
    private final KernelFacts facts;
    private final PlacementService placement;
    private final ApiMounts mounts;
    private final Map<String, Entry> registry = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Set<String> swapping = ConcurrentHashMap.newKeySet();
    private final Object swapSignal = new Object();
    private final long drainMillis;
    private final List<Path> folders;
    private final com.ash.drishti.server.modules.host.ModuleWiring wiring;
    private ModuleRuntime runtime;

    public ModuleNode(Path root, KernelFacts facts, PlacementService placement, ApiMounts mounts, long drainMillis) {
        this(root, facts, placement, mounts, drainMillis, List.of(), null);
    }

    /**
     * @param folders development mode's exploded module folders ({@code drishti.modules.dev}; empty otherwise): each a module's unpacked
     *                build output (module.yaml at its root) or a folder of {@code <id>/<version>/}; loaded unverified, marked so in Health,
     *                reloaded when they change. Production loads only verified {@code .dmod} packages, through {@code installed/}.
     * @param wiring  the kernel's part of a loaded module (the registration bridge and its entry), or null for none
     */
    public ModuleNode(Path root, KernelFacts facts, PlacementService placement, ApiMounts mounts, long drainMillis, List<Path> folders,
            com.ash.drishti.server.modules.host.ModuleWiring wiring) {
        this.folders = List.copyOf(folders);
        this.wiring = wiring;
        this.root = root;
        this.installed = root.resolve("installed");
        this.staging = root.resolve("staging");
        this.tombstones = root.resolve("tombstones");
        this.activeFile = installed.resolve("active.yaml");
        this.registryFile = root.resolve("registry.json");
        this.facts = facts;
        this.placement = placement;
        this.mounts = mounts;
        this.drainMillis = drainMillis;
    }

    /** Reads the registry, tells placement of every active module, and loads the active modules placed here. */
    public synchronized ModuleNode start(ApplicationContext parent) {
        readRegistry();
        PlacementHost ph = new PlacementHost(placement);
        ModuleHost host = new ModuleHost() {
            @Override
            public boolean placedHere(DmodManifest manifest) {
                return ph.placedHere(manifest);
            }

            @Override
            public void started(LoadedModule module, Contributions contributions) {
                ph.started(module, contributions);
                if (wiring != null) {
                    wiring.started(module, contributions);
                }
                if (mounts != null) {
                    mounts.mount(module, contributions);
                }
            }

            @Override
            public void drain(String module) {
                ModuleNode.this.drain(module);
            }

            @Override
            public void stopping(LoadedModule module) {
                if (wiring != null) {
                    wiring.stopping(module);
                }
            }

            @Override
            public void event(String name, String module) {
                LOG.info("{}: {}", name, module);
            }
        };
        runtime = new ModuleRuntime(facts, parent, ModuleNode.class.getClassLoader(), host, tombstones)
                .development(c -> folders.stream().anyMatch(f -> c.dir().toAbsolutePath().normalize().startsWith(f)))
                .libraries(com.ash.drishti.common.SharedLib.get().any() ? com.ash.drishti.common.SharedLib.get().loader() : null);
        registry.values().stream().filter(e -> e.state() == State.ACTIVE).forEach(this::declare);
        rescan();
        return this;
    }

    /**
     * Looks in the module folders again (this node's installed modules, then development mode's exploded folders) and loads every module
     * that is not loaded. Returns every module's Health row.
     */
    public synchronized List<ModuleHealth> rescan() {
        List<Path> all = new ArrayList<>();
        if (Files.isDirectory(installed)) {
            all.add(installed);
        }
        all.addAll(folders);
        if (all.isEmpty()) {
            return runtime.health();
        }
        Map<String, String> active = new LinkedHashMap<>();
        for (Path f : folders) {
            ModuleDiscovery.readActive(f.resolve("active.yaml")).forEach(active::putIfAbsent);
        }
        active.putAll(ModuleDiscovery.readActive(activeFile));
        List<ModuleHealth> rows = runtime.start(all, active, id -> !registry.containsKey(id) || registry.get(id).state() == State.ACTIVE);
        if (!folders.isEmpty()) {
            LOG.warn("modules in development mode from {}: {} (unverified; never in production)", folders,
                    rows.stream().map(h -> h.id() + " " + h.state()).toList());
            for (ModuleHealth h : rows) {
                runtime.loaded(h.id()).filter(m -> development(m.candidate().dir())).ifPresent(m -> stamps.put(h.id(), stamp(m.candidate().dir())));
            }
        }
        return rows;
    }

    private final Map<String, Long> stamps = new ConcurrentHashMap<>();

    private boolean development(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        return folders.stream().anyMatch(d::startsWith);
    }

    /** A stamp of a folder's files: their count, sizes and last changes. */
    static long stamp(Path dir) {
        long h = 17;
        try (var s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                h = h * 31 + p.toString().hashCode();
                h = h * 31 + Files.size(p);
                h = h * 31 + Files.getLastModifiedTime(p).toMillis();
            }
        } catch (IOException | UncheckedIOException e) {
            return -1;
        }
        return h;
    }

    /**
     * Development mode's reload: every exploded module whose files changed since it loaded is unloaded (leak-checked) and loaded again;
     * one that came to a folder is loaded. Returns what it did, a line a module.
     */
    public synchronized List<String> reloadChanged() {
        List<String> did = new ArrayList<>();
        if (folders.isEmpty() || runtime == null) {
            return did;
        }
        for (Map.Entry<String, Long> e : List.copyOf(stamps.entrySet())) {
            String id = e.getKey();
            long now = loadedStamp(id);                // no reference to the module kept here: the unload's leak check follows
            if (now == Long.MIN_VALUE) {
                stamps.remove(id);
                continue;
            }
            if (now == e.getValue()) {
                continue;
            }
            ModuleRuntime.UnloadReport r = runtime.unload(id, ModuleRuntime.Leave.DISABLE, true);
            stamps.remove(id);
            did.add(id + " changed: unloaded (" + (Boolean.TRUE.equals(r.leakFree()) ? "leak-free" : "LEAK: " + r.failures()) + ")");
        }
        int before = stamps.size();
        rescan();
        if (stamps.size() > before || !did.isEmpty()) {
            did.add("loaded: " + stamps.keySet());
        }
        did.forEach(d -> LOG.info("development mode: {}", d));
        return did;
    }

    /** The stamp of a loaded module's folder, or {@code Long.MIN_VALUE} when it is not loaded. */
    private long loadedStamp(String id) {
        return runtime.loaded(id).map(m -> stamp(m.candidate().dir())).orElse(Long.MIN_VALUE);
    }

    /** True when development mode is on here (exploded folders loaded). */
    public boolean developmentMode() {
        return !folders.isEmpty();
    }

    /**
     * The module that owns an API path by the live route table, for a module the cluster's registry does not hold (one of
     * {@code drishti.modules.path}): active, off (404) or removed (410), as its routes say.
     */
    public Optional<Entry> byApiPath(String path) {
        ModuleRuntime rt = runtime;
        if (rt == null) {
            return Optional.empty();
        }
        return rt.routes().lookup(path).filter(m -> m.route().kind() == com.ash.drishti.module.runtime.RouteTable.Kind.API).map(m -> {
            var r = m.route();
            State s = switch (r.state()) {
                case ACTIVE -> State.ACTIVE;
                case OFF -> State.OFF;
                case TOMBSTONE -> State.REMOVED;
            };
            String prefix = r.path().substring("/api/v1/".length(), r.path().indexOf('/', "/api/v1/".length()));
            return new Entry(r.module(), r.version(), s, r.module(), prefix, List.of(), List.of(), null, null, null, null);
        });
    }

    /** The extension types a loaded module's beans are connected through (empty without the kernel's wiring). */
    public List<String> bridged() {
        return wiring == null ? List.of() : wiring.types();
    }

    public ModuleRuntime runtime() {
        return runtime;
    }

    public Path root() {
        return root;
    }

    // ---- phase 1: stage ------------------------------------------------------------------------------------------------------------

    /** One chunk of a deployment's package (chunk 0 starts the file). */
    public void chunk(String deployment, int index, byte[] bytes) {
        Path f = staged(deployment).resolveSibling(safe(deployment) + ".dmod");
        try {
            Files.createDirectories(f.getParent());
            if (index == 0) {
                Files.write(f, bytes);
            } else {
                Files.write(f, bytes, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A local package for a deployment (the coordinator's own node): copied into staging as if it had arrived. */
    public void chunkFrom(String deployment, Path dmod) {
        try {
            Path f = staged(deployment).resolveSibling(safe(deployment) + ".dmod");
            Files.createDirectories(f.getParent());
            Files.copy(dmod, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Phase 1 on this node: the package's SHA-256 checked here (never trusting another node's word), unpacked into staging, its hash list
     * checked and the verification V1–V9 run against this kernel. Returns the notes; refused with the reason.
     */
    public synchronized List<String> stage(String deployment, String id, String version, String sha) {
        Path f = staged(deployment).resolveSibling(safe(deployment) + ".dmod");
        try {
            if (!Files.isRegularFile(f)) {
                throw refused("no package arrived for " + deployment);
            }
            String got;
            try (InputStream in = Files.newInputStream(f)) {
                got = Dmod.sha256(in);
            }
            if (!got.equalsIgnoreCase(sha)) {
                throw refused("the package's SHA-256 is " + got.substring(0, 12) + "…, not the stored " + sha.substring(0, Math.min(12, sha.length())) + "…");
            }
            Path dir = staged(deployment).resolve(id).resolve(version);
            Dmod.unpack(f, dir);
            Files.copy(f, dir.getParent().resolve(Dmod.fileName(id, version)), StandardCopyOption.REPLACE_EXISTING);
            Dmod.verify(dir, f, id + " " + version);   // V2 here, on this node's own copy (refused when a file is missing or changed)
            ModuleCandidate c = candidate(staged(deployment), id, version);
            if (!c.manifest().id().equals(id) || !c.manifest().version().equals(version)) {
                throw refused("the package is " + c.manifest().label() + ", not " + id + " " + version);
            }
            return new ModuleVerifier(facts).verify(c);
        } catch (ModuleRefusedException e) {
            abort(deployment);
            throw refused(e.check() + " " + e.getMessage());
        } catch (IOException | UncheckedIOException e) {
            abort(deployment);
            throw refused("staging failed: " + e.getMessage());
        }
    }

    /** Removes a deployment's staged files (an abort, or after activation). */
    public void abort(String deployment) {
        delete(staged(deployment));
        try {
            Files.deleteIfExists(staged(deployment).resolveSibling(safe(deployment) + ".dmod"));
        } catch (IOException e) {
            LOG.debug("staging cleanup", e);
        }
    }

    // ---- phase 2: activate, roll back, unload ----------------------------------------------------------------------------------------

    /**
     * Phase 2 on this node: the staged version becomes the installed and active one, the version running here (if any) is unloaded and
     * the new one loaded; requests to the module wait while the two swap (old version stops, new starts). Returns the version it replaced
     * (null for none); refused, the node is left as it was (the old version loaded again).
     */
    public synchronized Map<String, Object> activate(String deployment, String id, String version) {
        Path from = staged(deployment).resolve(id).resolve(version);
        if (!Files.isDirectory(from)) {
            throw refused(id + " " + version + " is not staged here (" + deployment + ")");
        }
        Map<String, String> active = new LinkedHashMap<>(ModuleDiscovery.readActive(activeFile));
        String previous = runtime.loaded(id).map(m -> m.manifest().version()).orElse(null);
        boolean existed = Files.isDirectory(installed.resolve(id).resolve(version));
        Path to = installed.resolve(id).resolve(version);
        try {
            delete(to);
            Files.createDirectories(to.getParent());
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            Path dmod = staged(deployment).resolve(id).resolve(Dmod.fileName(id, version));
            if (Files.isRegularFile(dmod)) {
                Files.copy(dmod, installed.resolve(id).resolve(Dmod.fileName(id, version)), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw refused("installing failed: " + e.getMessage());
        } finally {
            abort(deployment);
        }
        String failure = swap(id, version, active);
        if (failure != null) {
            if (!existed) {
                delete(to);
            }
            if (previous != null) {
                swap(id, previous, active);
            } else {
                active.remove(id);
                writeActive(active);
            }
            throw new DrishtiException(ErrorCode.MODULE_DEPLOY_FAILED, id + " " + version + " did not start on this node: " + failure);
        }
        deleteTombstone(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loaded", version);
        out.put("previous", previous);
        out.put("routes", runtime.routes().snapshot().of(id).size());
        return out;
    }

    /** Unloads what runs here and loads {@code version} instead; requests wait for the swap. Null when it loaded, else why not. */
    private String swap(String id, String version, Map<String, String> active) {
        beginSwap(id);
        try {
            if (runtime.loaded(id).isPresent()) {
                ModuleRuntime.UnloadReport r = runtime.unload(id, ModuleRuntime.Leave.DISABLE, true);
                if (!r.failures().isEmpty()) {
                    LOG.warn("module {}: unload before the swap: {}", id, r.failures());
                }
            }
            active.put(id, version);
            writeActive(active);
            ModuleCandidate c;
            List<String> notes;
            try {
                c = candidate(installed, id, version);
                notes = new ModuleVerifier(facts).verify(c);
            } catch (ModuleRefusedException e) {
                return e.check() + " " + e.getMessage();
            }
            if (runtime.load(c, notes) == null) {
                return runtime.health(id).map(h -> h.reason() == null ? h.state().name() : h.reason()).orElse("refused");
            }
            return null;
        } finally {
            endSwap(id);
        }
    }

    /**
     * Undoes an activation of {@code deployment} here: the new version unloaded and, when there was one before, {@code previous} loaded
     * again; else the module leaves this node (switched off: its saved work is untouched). Idempotent.
     */
    public synchronized Map<String, Object> rollback(String id, String version, String previous) {
        Map<String, String> active = new LinkedHashMap<>(ModuleDiscovery.readActive(activeFile));
        String now = runtime.loaded(id).map(m -> m.manifest().version()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (previous != null) {
            if (!previous.equals(now)) {
                String f = swap(id, previous, active);
                out.put("restored", f == null ? previous : "failed: " + f);
            } else {
                out.put("restored", previous);
            }
        } else {
            if (now != null) {
                out.put("unload", runtime.unload(id, ModuleRuntime.Leave.DISABLE, true).steps().size() + " steps");
            }
            active.remove(id);
            writeActive(active);
            if (!registry.containsKey(id) || registry.get(id).state() != State.ACTIVE) {
                delete(installed.resolve(id).resolve(version));
            }
            out.put("restored", "nothing (it was not here before)");
        }
        return out;
    }

    /** Takes a module off this node: removed (410, tombstone, files kept for a reinstall) or switched off (404). */
    public synchronized Map<String, Object> unload(String id, ModuleRuntime.Leave leave) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (runtime.loaded(id).isPresent()) {
            ModuleRuntime.UnloadReport r = runtime.unload(id, leave, true);
            out.put("steps", r.steps());
            out.put("failures", r.failures());
            out.put("leakFree", r.leakFree());
        } else {
            out.put("steps", List.of("nothing of it was loaded here"));
            out.put("failures", List.of());
        }
        Map<String, String> active = new LinkedHashMap<>(ModuleDiscovery.readActive(activeFile));
        if (leave == ModuleRuntime.Leave.REMOVE && active.remove(id) != null) {
            writeActive(active);
        }
        return out;
    }

    // ---- the registry --------------------------------------------------------------------------------------------------------------

    /** Records the cluster's word on a module (every node, placed or not): placement learns its parts, or forgets it when it leaves. */
    public synchronized void record(Entry e) {
        registry.put(e.id(), e);
        writeRegistry();
        if (e.state() == State.ACTIVE) {
            declare(e);
            deleteTombstone(e.id());
        } else {
            placement.forget(e.id());
        }
    }

    public Optional<Entry> entry(String id) {
        return Optional.ofNullable(registry.get(id));
    }

    public List<Entry> entries() {
        return new TreeMap<>(registry).values().stream().toList();
    }

    /** The registry entry whose API prefix is {@code prefix}. */
    public Optional<Entry> byPrefix(String prefix) {
        return registry.values().stream().filter(e -> prefix.equals(e.prefix())).findFirst();
    }

    /** The registry entry that owns a console path ({@code /m/<id>/…}). */
    public Optional<Entry> byPath(String path) {
        if (path == null || !path.startsWith("/m/")) {
            return Optional.empty();
        }
        String rest = path.substring(3);
        String id = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        return Optional.ofNullable(registry.get(id));
    }

    private void declare(Entry e) {
        try {
            placement.declare(ModuleParts.of(DmodManifest.read(new ByteArrayInputStream(e.manifest().getBytes(StandardCharsets.UTF_8)), e.id())));
        } catch (RuntimeException ex) {
            LOG.warn("module {}: its manifest in the registry does not read: {}", e.id(), ex.getMessage());
        }
    }

    // ---- saved work: needs module X ------------------------------------------------------------------------------------------------

    /**
     * The modules (off or removed) a saved artefact needs, from its stored text: an API path ({@code /api/v1/<prefix>/}), a console path
     * ({@code /m/<id>/}), a function word or a kind the module owned. Nothing is rewritten; a reinstall makes the answer empty again.
     */
    public List<Map<String, Object>> needs(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (Entry e : entries()) {
            if (e.state() == State.ACTIVE) {
                continue;
            }
            if (uses(e, text)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("module", e.id());
                m.put("title", e.title());
                m.put("state", e.state().name().toLowerCase(Locale.ROOT));
                m.put("words", "needs module " + e.id() + (e.state() == State.OFF ? " (off)" : " (not installed)"));
                out.add(m);
            }
        }
        return out;
    }

    static boolean uses(Entry e, String text) {
        if (e.prefix() != null && text.contains("/api/v1/" + e.prefix() + "/")) {
            return true;
        }
        if (text.contains("/m/" + e.id() + "/")) {
            return true;
        }
        for (String claim : e.claims()) {
            int sp = claim.indexOf(' ');
            String kind = sp < 0 ? "" : claim.substring(0, sp);
            String name = sp < 0 ? claim : claim.substring(sp + 1);
            if ((kind.equals("function") || kind.equals("kind")) && !name.isBlank()
                    && Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(name) + "(?![A-Za-z0-9_-])").matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    // ---- requests during a swap and the drain -------------------------------------------------------------------------------------

    /** A request of the module starts here (the route filter counts them, so an unload drains them). */
    public void enter(String id) {
        inFlight.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
    }

    public void leave(String id) {
        AtomicInteger n = inFlight.get(id);
        if (n != null) {
            n.decrementAndGet();
        }
    }

    /** Waits while the module swaps versions here, at most {@code millis}; true when it is no longer swapping. */
    public boolean awaitSwap(String id, long millis) {
        long until = System.currentTimeMillis() + millis;
        synchronized (swapSignal) {
            while (swapping.contains(id)) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                try {
                    swapSignal.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    public boolean swapping(String id) {
        return swapping.contains(id);
    }

    private void beginSwap(String id) {
        swapping.add(id);
    }

    private void endSwap(String id) {
        synchronized (swapSignal) {
            swapping.remove(id);
            swapSignal.notifyAll();
        }
    }

    /** Unload step 3: requests already inside the module finish (at most the drain time). */
    private void drain(String id) {
        long until = System.currentTimeMillis() + drainMillis;
        AtomicInteger n = inFlight.get(id);
        while (n != null && n.get() > 0 && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- this node's view ----------------------------------------------------------------------------------------------------------

    /** What this node holds: each registry module with the version loaded here and its Health row. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", placement.self());
        List<Map<String, Object>> mods = new ArrayList<>();
        Map<String, ModuleHealth> health = new LinkedHashMap<>();
        runtime.health().forEach(h -> health.put(h.id(), h));
        for (Entry e : entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("version", e.version());
            m.put("state", e.state().name().toLowerCase(Locale.ROOT));
            m.put("loaded", runtime.loaded(e.id()).map(x -> x.manifest().version()).orElse(null));
            ModuleHealth h = health.get(e.id());
            m.put("health", h == null ? null : h.state().name());
            m.put("reason", h == null ? null : h.reason());
            m.put("routes", runtime.routes().snapshot().of(e.id()).size());
            mods.add(m);
        }
        for (ModuleHealth h : health.values()) {          // the modules of drishti.modules.path: not deployed, so not in the registry
            if (registry.containsKey(h.id())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id());
            m.put("version", h.version());
            m.put("state", h.state() == ModuleHealth.State.LOADED ? "active" : h.state().name().toLowerCase(Locale.ROOT));
            m.put("loaded", runtime.loaded(h.id()).map(x -> x.manifest().version()).orElse(null));
            m.put("health", h.state().name());
            m.put("reason", h.reason());
            m.put("routes", runtime.routes().snapshot().of(h.id()).size());
            m.put("from", developmentMode() && runtime.loaded(h.id()).map(x -> development(x.candidate().dir())).orElse(false)
                    ? "development (unverified)" : "drishti.modules.path");
            m.put("contributions", runtime.contributions().of(h.id()).stream().map(c -> c.surface() + " " + c.name()).toList());
            mods.add(m);
        }
        out.put("modules", mods);
        out.put("routeTable", runtime.routes().snapshot().version());
        out.put("lib", com.ash.drishti.common.SharedLib.get().info());      // Admin → Modules: the shared lib jars, marked lib
        out.put("development", developmentMode());
        out.put("bridge", bridged());
        return out;
    }

    // ---- files ---------------------------------------------------------------------------------------------------------------------

    private static ModuleCandidate candidate(Path folder, String id, String version) {
        ModuleDiscovery.Found f = ModuleDiscovery.discover(List.of(folder), Map.of(id, version));
        if (f.refused().containsKey(id)) {
            throw f.refused().get(id);
        }
        return f.candidates().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new ModuleRefusedException("V1", id + " " + version + " is not in " + folder.getFileName()));
    }

    private Path staged(String deployment) {
        return staging.resolve(safe(deployment));
    }

    static String safe(String s) {
        if (s == null || !s.matches("[A-Za-z0-9._-]{1,80}") || s.contains("..")) {
            throw refused("not a deployment id: " + s);
        }
        return s;
    }

    private void writeActive(Map<String, String> active) {
        StringBuilder y = new StringBuilder("# the version of each installed module that runs on this node (written by deployments)\nmodules:\n");
        new TreeMap<>(active).forEach((k, v) -> y.append("  ").append(k).append(": \"").append(v).append("\"\n"));
        Dmod.write(activeFile, y.toString());
    }

    private void deleteTombstone(String id) {
        try {
            Files.deleteIfExists(tombstones.resolve(id + ".yaml"));
        } catch (IOException e) {
            LOG.debug("tombstone", e);
        }
    }

    private void readRegistry() {
        if (!Files.isRegularFile(registryFile)) {
            return;
        }
        try {
            List<Entry> all = JSON.readValue(registryFile.toFile(), new TypeReference<List<Entry>>() { });
            all.forEach(e -> registry.put(e.id(), e));
        } catch (IOException e) {
            LOG.warn("modules: {} does not read: {}", registryFile, e.getMessage());
        }
    }

    private void writeRegistry() {
        try {
            Files.createDirectories(root);
            Path tmp = registryFile.resolveSibling("registry.json.tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), entries());
            Files.move(tmp, registryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.debug("delete {}", p, e);
                }
            });
        } catch (IOException e) {
            LOG.debug("delete {}", dir, e);
        }
    }

    static DrishtiException refused(String words) {
        return new DrishtiException(ErrorCode.MODULE_DEPLOY_REFUSED, words);
    }

    /** Now, for the registry's {@code at}. */
    static String now() {
        return Instant.now().toString();
    }

    @Override
    public void close() {
        if (runtime != null) {
            runtime.close();
        }
    }
}
