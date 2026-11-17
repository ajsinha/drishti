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

import com.ash.drishti.api.resolution.ModuleHomes;
import com.ash.drishti.module.runtime.Contributions;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.FirstParty;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.module.runtime.LoadedModule;
import com.ash.drishti.module.runtime.ModuleBoot;
import com.ash.drishti.module.runtime.ModuleCandidate;
import com.ash.drishti.module.runtime.ModuleDiscovery;
import com.ash.drishti.module.runtime.ModuleHealth;
import com.ash.drishti.module.runtime.ModuleHost;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.module.runtime.RouteTable;
import com.ash.drishti.module.runtime.placement.ModuleParts;
import com.ash.drishti.server.placement.PlacementHost;
import com.ash.drishti.server.placement.PlacementService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/**
 * This node's modules (KERNEL_AND_MODULES.md sections 4.1 and 4.2): the one {@link ModuleRuntime} of the server, loaded at start from the
 * modules the module boot accepted ({@link ModuleBoot#current}: the {@code .dmod} files beside the server, or under the development switch
 * their source folders). Nothing is deployed, upgraded or removed while the server runs: a module is a file and a restart. A node that
 * holds no part of a module keeps only its entry: placement forwards the module's requests to the node that does.
 *
 * <p>TODO(I8c): the reads below (entries, needs, status, fingerprint, console folders) are what the console, the about answer, the
 * edition and the packs take from here; I8c rewrites them over the boot result and adds the content hash for the cluster's digest.
 */
public final class ModuleNode implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleNode.class);

    /** A module's state on this node: switched on or off. */
    public enum State { ACTIVE, OFF }

    /**
     * One module of this server's boot.
     *
     * @param claims   the names it owns (API prefix, functions, kinds, lenses), so saved work that uses one says it needs the module
     * @param routes   its console route paths ({@code /m/<id>/…})
     * @param manifest its {@code module.yaml}
     */
    public record Entry(String id, String version, State state, String title, String prefix, List<String> claims, List<String> routes,
            String manifest) {
    }

    private final KernelFacts facts;
    private final PlacementService placement;
    private final ApiMounts mounts;
    private final com.ash.drishti.server.modules.host.ModuleWiring wiring;
    private final Map<String, Entry> registry = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Path> consoleDirs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private ModuleRuntime runtime;
    private volatile Predicate<String> siteSwitch = id -> true;
    private volatile Supplier<String> developmentRefusal = () -> null;
    private volatile boolean loadSources;

    /**
     * @param facts   what this host is (the boot's facts, with its first-party list)
     * @param wiring  the kernel's part of a loaded module (the registration bridge and its entry), or null for none
     */
    public ModuleNode(KernelFacts facts, PlacementService placement, ApiMounts mounts, com.ash.drishti.server.modules.host.ModuleWiring wiring) {
        this.facts = facts;
        this.placement = placement;
        this.mounts = mounts;
        this.wiring = wiring;
    }

    /**
     * The site's switch of each module ({@code config/modules/<id>.yaml} {@code enabled}, {@code drishti.modules.<id>.enabled}): a module
     * switched off there is not loaded (Health: off, administrator).
     */
    public ModuleNode siteSwitch(Predicate<String> on) {
        this.siteSwitch = on == null ? id -> true : on;
        return this;
    }

    /**
     * The development switch: modules read from source folders are loaded as code only when {@code load} (a developer's own server); in
     * source mode they are known (manifests, packs) and not loaded. {@code refusal} says why loading is refused here (a cluster,
     * production), or null when allowed.
     */
    public ModuleNode development(boolean load, Supplier<String> refusal) {
        this.loadSources = load;
        this.developmentRefusal = refusal == null ? () -> null : refusal;
        return this;
    }

    /** Tells placement of every module the boot accepted and loads those placed here. */
    public ModuleNode start(ApplicationContext parent) {
        lock.lock();
        try {
            PlacementHost ph = new PlacementHost(placement);
            ModuleHost host = new ModuleHost() {
                @Override
                public boolean placedHere(DmodManifest manifest) {
                    return ph.placedHere(manifest);
                }

                @Override
                public void preparing(ModuleCandidate candidate, org.springframework.context.support.GenericApplicationContext context) {
                    if (wiring != null) {
                        wiring.preparing(candidate, context);
                    }
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
            ModuleBoot.Result boot = ModuleBoot.current().orElse(null);
            List<ModuleCandidate> candidates = new ArrayList<>();           // the modules to load
            java.util.Set<String> development = new java.util.HashSet<>();  // the ones read from source folders
            ModuleHomes homes = ModuleHomes.current().orElse(ModuleHomes.none());
            if (boot != null) {
                String refused = developmentRefusal.get();
                for (ModuleBoot.Module m : boot.modules().values()) {
                    boolean on = siteSwitch.test(m.id());
                    ModuleCandidate c = m.candidate();
                    registry.put(m.id(), entryOf(c, on ? State.ACTIVE : State.OFF));   // every module of the boot is known here
                    homes.dir(m.id()).ifPresent(d -> consoleDirs.put(m.id(), d));
                    if (m.origin() == ModuleHomes.Origin.DEVELOPMENT) {
                        development.add(m.id());
                        if (!loadSources) {
                            continue;                                      // source mode: known (manifests, packs, console), not loaded as code
                        }
                        if (refused != null) {
                            LOG.error("the module {} is a source folder (the development switch), which is refused: {}; only verified .dmod packages load",
                                    m.id(), refused);
                            continue;
                        }
                    } else if (on) {
                        try {
                            placement.declare(ModuleParts.of(c.manifest()));
                        } catch (RuntimeException ex) {
                            LOG.warn("module {}: its parts are not known to placement: {}", m.id(), ex.getMessage());
                        }
                    }
                    candidates.add(c);
                }
                LOG.info("modules from the boot: {}", candidates.stream().map(c -> c.id() + " " + c.manifest().version()).toList());
            }
            runtime = new ModuleRuntime(facts, parent, ModuleNode.class.getClassLoader(), host)
                    .development(c -> development.contains(c.id()))
                    .libraries(com.ash.drishti.common.SharedLib.get().any() ? com.ash.drishti.common.SharedLib.get().loader() : null);
            placement.firstParty(id -> origin(id).map(FirstParty.Origin::firstParty).orElse(false));
            runtime.start(new ModuleDiscovery.Found(List.copyOf(candidates), Map.of()), siteSwitch);
            return this;
        } finally {
            lock.unlock();
        }
    }

    /** The extension types a loaded module's beans are connected through (empty without the kernel's wiring). */
    public List<String> bridged() {
        return wiring == null ? List.of() : wiring.types();
    }

    public ModuleRuntime runtime() {
        return runtime;
    }

    /** True when any module of this boot is read from a source folder (the development switch). */
    public boolean developmentMode() {
        ModuleHomes h = ModuleHomes.current().orElse(null);
        return h != null && h.homes().values().stream().anyMatch(x -> x.origin() == ModuleHomes.Origin.DEVELOPMENT);
    }

    /** The route table of this node (the console's route answer asks it). */
    public RouteTable.Snapshot routes() {
        return runtime.routes().snapshot();
    }

    /** A module's verified origin here ({@code first-party} only when the first-party list verifies it). */
    public Optional<FirstParty.Origin> origin(String id) {
        return runtime == null ? Optional.empty() : runtime.origin(id);
    }

    /**
     * The folder a console takes the module's console part from ({@code module.yaml}, {@code console/}, {@code elements/}, {@code docs/}):
     * its boot folder (the site's overrides laid over it), when the module is on, is that version and has a {@code console/plugin.yaml};
     * null otherwise.
     */
    public Path consoleFolder(String id, String version) {
        Entry e = registry.get(id);
        Path dir = consoleDirs.get(id);
        if (e == null || e.state() != State.ACTIVE || !e.version().equals(version) || dir == null
                || !Files.isRegularFile(dir.resolve("console").resolve("plugin.yaml"))) {
            return null;
        }
        return dir;
    }

    /** Whether the module's boot folder has a console part. */
    public boolean hasConsole(String id) {
        Path dir = consoleDirs.get(id);
        return dir != null && Files.isRegularFile(dir.resolve("console").resolve("plugin.yaml"));
    }

    /** The entry of a module's manifest folder: its names (claims), console routes and manifest. */
    private static Entry entryOf(ModuleCandidate c, State state) {
        DmodManifest m = c.manifest();
        List<String> routes = new ArrayList<>();
        List<String> claims;
        try {
            Object plugin = m.console() == null ? null : m.console().get("plugin");
            if (plugin != null && c.yaml(String.valueOf(plugin), "V1").get("routes") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> r && r.get("path") != null) {
                        routes.add("/m/" + m.id() + r.get("path"));
                    }
                }
            }
            claims = com.ash.drishti.module.runtime.Claims.of(c, List.of());
        } catch (RuntimeException ex) {
            claims = List.of();
        }
        String manifest;
        try {
            manifest = Files.readString(c.dir().resolve(DmodManifest.FILE));
        } catch (java.io.IOException e) {
            manifest = null;
        }
        return new Entry(m.id(), m.version(), state, m.title(), m.api() == null ? null : m.api().prefix(), claims, routes, manifest);
    }

    public Optional<Entry> entry(String id) {
        return Optional.ofNullable(registry.get(id));
    }

    public List<Entry> entries() {
        return new TreeMap<>(registry).values().stream().toList();
    }

    /** A word that changes whenever a module of the boot comes, goes, changes version or state (the console follows it). */
    public String fingerprint() {
        StringBuilder print = new StringBuilder();
        entries().forEach(e -> print.append(e.id()).append('@').append(e.version()).append(':').append(e.state()).append('|'));
        return Integer.toHexString(print.toString().hashCode());
    }

    /** The entry whose API prefix is {@code prefix}. */
    public Optional<Entry> byPrefix(String prefix) {
        return registry.values().stream().filter(e -> prefix.equals(e.prefix())).findFirst();
    }

    /** The entry that owns a console path ({@code /m/<id>/…}). */
    public Optional<Entry> byPath(String path) {
        if (path == null || !path.startsWith("/m/")) {
            return Optional.empty();
        }
        String rest = path.substring(3);
        String id = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        return Optional.ofNullable(registry.get(id));
    }

    // ---- saved work: needs module X ------------------------------------------------------------------------------------------------

    /**
     * The modules a saved artefact needs that are not on here (switched off, or not installed: a module this boot never accepted is
     * unknown, so a name it once owned is not found), from its stored text: an API path ({@code /api/v1/<prefix>/}), a console path
     * ({@code /m/<id>/}), a function word or a kind a module of this boot owns. Nothing is rewritten.
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
                m.put("words", "needs module " + e.id() + " (off)");
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

    // ---- this node's view ----------------------------------------------------------------------------------------------------------

    /** What this node holds: each module of the boot with the version loaded here and its Health row. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", placement.self());
        List<Map<String, Object>> mods = new ArrayList<>();
        Map<String, ModuleHealth> health = new LinkedHashMap<>();
        runtime.health().forEach(h -> health.put(h.id(), h));
        ModuleHomes homes = ModuleHomes.current().orElse(ModuleHomes.none());
        for (Entry e : entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("version", e.version());
            m.put("state", e.state().name().toLowerCase(Locale.ROOT));
            m.put("loaded", runtime.loaded(e.id()).map(x -> x.manifest().version()).orElse(null));
            ModuleHealth h = health.get(e.id());
            m.put("health", h == null ? null : h.state().name());
            m.put("reason", h == null ? null : h.reason());
            m.put("origin", origin(e.id()).map(FirstParty.Origin::word).orElse(null));
            m.put("originWhy", origin(e.id()).map(FirstParty.Origin::why).orElse(null));
            m.put("routes", runtime.routes().snapshot().of(e.id()).size());
            m.put("contentHash", homes.home(e.id()).map(ModuleHomes.Home::contentHash).orElse(null));
            m.put("contributions", runtime.contributions().of(e.id()).stream().map(c -> c.surface() + " " + c.name()).toList());
            mods.add(m);
        }
        out.put("modules", mods);
        out.put("routeTable", runtime.routes().snapshot().version());
        out.put("lib", com.ash.drishti.common.SharedLib.get().info());      // Admin → Modules: the shared lib jars, marked lib
        out.put("development", developmentMode());
        out.put("bridge", bridged());
        return out;
    }

    @Override
    public void close() {
        if (runtime != null) {
            runtime.close();
        }
    }
}
