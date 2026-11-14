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
package com.ash.drishti.server.modules.host;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.module.DrishtiModule;
import com.ash.drishti.module.runtime.Contributions;
import com.ash.drishti.module.runtime.LoadedModule;
import com.ash.drishti.server.engines.EngineRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * The kernel's part of a loaded module beyond its routes (docs/architecture/KERNEL_AND_MODULES.md sections 2.3 and 12.3), called by the
 * module host ({@code ModuleNode}): when a module starts, its provider beans go through the registration bridge to the kernel's collection
 * points and its entry ({@link DrishtiModule}) is started with the kernel's {@link com.ash.drishti.kernel.api.module.ModuleContext}; when it
 * stops, the entry is stopped (the bridge's withdrawals run in the unload's own steps, from the registry of contributions).
 */
public final class ModuleWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleWiring.class);

    private final ModuleBridge bridge;
    private final Supplier<SourceRouter> router;
    private final Supplier<AuditLog> audit;
    private final ConfigurableEnvironment env;
    private final Path dataDir;
    private final Map<String, List<DrishtiModule>> entries = new ConcurrentHashMap<>();
    /** Each loaded module's folders, for its connector extensions' settings ({@code module-dir}, {@code data-dir}). */
    private final Map<String, Map<String, String>> folders = new ConcurrentHashMap<>();

    public ModuleWiring(Supplier<SourceRegistry> sources, Supplier<EngineRegistry> engines, Supplier<SourceRouter> router, Supplier<AuditLog> audit,
            ConfigurableEnvironment env) {
        this(sources, engines, router, audit, env, () -> null);
    }

    /** As above, with the server's memory budget (a module's engine caches join it). */
    public ModuleWiring(Supplier<SourceRegistry> sources, Supplier<EngineRegistry> engines, Supplier<SourceRouter> router, Supplier<AuditLog> audit,
            ConfigurableEnvironment env, Supplier<com.ash.drishti.server.caches.MemoryBudget> budget) {
        this.router = router;
        this.audit = audit;
        this.env = env;
        this.dataDir = Path.of(env == null ? "./data" : env.getProperty("drishti.data.dir", "./data")).toAbsolutePath().normalize();
        this.bridge = new ModuleBridge(sources, engines, (id, p) -> folders.getOrDefault(id, Map.of()), budget);
    }

    private volatile Supplier<com.ash.drishti.server.bindings.BindingService> bindings = () -> null;

    /** The data gate a loaded module's declared needs join (its manifest's {@code data: {needs: …}}), withdrawn when it stops. */
    public ModuleWiring bindings(Supplier<com.ash.drishti.server.bindings.BindingService> gate) {
        this.bindings = gate == null ? () -> null : gate;
        return this;
    }

    private volatile ModulePython python;

    /**
     * The compute service a module's Python compute joins (its manifest's {@code python:} section), and whether the module's {@code compute}
     * part is placed on this node (true for a module that declares no such part).
     */
    public ModuleWiring python(Supplier<com.ash.drishti.compute.ComputeService> compute, java.util.function.Predicate<LoadedModule> computeHere) {
        this.python = compute == null ? null : new ModulePython(compute, dataDir, computeHere);
        return this;
    }

    private volatile Supplier<com.ash.drishti.server.PackReloader> packs = () -> null;

    /** The pack loader a site's content module's packs load through when it starts (its manifest's {@code packs: {list}}). */
    public ModuleWiring packs(Supplier<com.ash.drishti.server.PackReloader> reloader) {
        this.packs = reloader == null ? () -> null : reloader;
        return this;
    }

    /** The extension types the bridge connects, by simple name. */
    public List<String> types() {
        return bridge.types();
    }

    private final Map<String, List<String>> defaults = new ConcurrentHashMap<>();

    /**
     * Before the module's context is made: the defaults of its engines ({@code engines/<id>.yaml} in its own jars, as the server's own
     * engines ship theirs) join the server's settings last, so a site's {@code config/engines/<id>.yaml} and every other setting win, and
     * the module's context sees the same. Taken out again when it stops.
     */
    public void preparing(com.ash.drishti.module.runtime.ModuleCandidate c, org.springframework.context.support.GenericApplicationContext ctx) {
        ClassLoader loader = ctx.getClassLoader();
        if (env == null || !(loader instanceof com.ash.drishti.module.runtime.ModuleClassLoader ml)) {
            return;
        }
        List<String> names = new ArrayList<>();
        try {
            org.springframework.core.io.support.PathMatchingResourcePatternResolver r = new org.springframework.core.io.support.PathMatchingResourcePatternResolver(ml);
            org.springframework.boot.env.YamlPropertySourceLoader yaml = new org.springframework.boot.env.YamlPropertySourceLoader();
            for (org.springframework.core.io.Resource res : r.getResources("classpath*:engines/*.yaml")) {
                String name = com.ash.drishti.server.engines.EngineSwitches.moduleCopy(c.id(), res.getFilename());
                Map<String, Object> flat = new java.util.LinkedHashMap<>();
                for (org.springframework.core.env.PropertySource<?> ps : yaml.load(name, res)) {
                    if (ps instanceof org.springframework.core.env.EnumerablePropertySource<?> e) {
                        for (String k : e.getPropertyNames()) {
                            Object v = e.getProperty(k);
                            flat.putIfAbsent(k, v == null ? null : String.valueOf(v));   // plain strings: nothing of the module's loader kept
                        }
                    }
                }
                org.springframework.core.env.MapPropertySource ps = new org.springframework.core.env.MapPropertySource(name, flat);
                env.getPropertySources().addLast(ps);
                ctx.getEnvironment().getPropertySources().addLast(ps);
                names.add(name);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(c.id() + ": its engines' defaults do not read", e);
        }
        defaults.put(c.id(), names);
    }

    /** A module started: its contributions connected, then its entry started. A failure fails the module's start (nothing stays). */
    public void started(LoadedModule module, Contributions contributions) {
        Map<String, String> where = new java.util.LinkedHashMap<>();
        if (module.candidate() != null && module.candidate().dir() != null) {
            where.put("module-dir", module.candidate().dir().toAbsolutePath().toString());
        }
        where.put("data-dir", dataDir.resolve("modules").resolve(module.id()).toString());
        folders.put(module.id(), Map.copyOf(where));
        int n = bridge.connect(module, contributions);
        List<DrishtiModule> started = new ArrayList<>();
        try {
            for (DrishtiModule m : ModuleBridge.found(module, DrishtiModule.class)) {
                m.start(new KernelModuleContext(module.id(), router, audit, dataDir.resolve("modules").resolve(module.id()), env));
                started.add(m);
            }
        } catch (RuntimeException | LinkageError e) {
            started.forEach(ModuleWiring::stopQuietly);
            throw e;
        }
        entries.put(module.id(), started);
        needs(module);
        packs(module);
        ModulePython py = python;
        if (py != null) {
            try {
                py.install(module, contributions);
            } catch (RuntimeException e) {
                started.forEach(ModuleWiring::stopQuietly);
                entries.remove(module.id());
                throw e;
            }
        }
        LOG.info("module {}: {} contributions connected, {} entr{} started", module.id(), n, started.size(), started.size() == 1 ? "y" : "ies");
    }

    /** Unload step 7, before its context closes: the module's entry stops. */
    public void stopping(LoadedModule module) {
        com.ash.drishti.server.bindings.BindingService gate = bindings.get();
        if (gate != null) {
            gate.withdrawNeeds(module.id());
        }
        folders.remove(module.id());
        List<DrishtiModule> started = entries.remove(module.id());
        if (started != null) {
            started.forEach(ModuleWiring::stopQuietly);
        }
        List<String> names = defaults.remove(module.id());
        if (names != null && env != null) {
            names.forEach(env.getPropertySources()::remove);
        }
    }

    /** A site's content module brings its packs: loaded now if not yet (at start the pack loader enabled them already). */
    private void packs(LoadedModule module) {
        com.ash.drishti.server.PackReloader reloader = packs.get();
        if (reloader == null || module.manifest() == null || module.manifest().firstPartyClaim()
                || !(module.manifest().section("packs") instanceof Map<?, ?> p) || !(p.get("list") instanceof List<?> list)) {
            return;
        }
        try {
            List<String> now = reloader.loadModulePacks(module.id(), list.stream().map(String::valueOf).toList());
            if (!now.isEmpty()) {
                LOG.info("module {}: packs {} loaded", module.id(), now);
            }
        } catch (RuntimeException e) {
            LOG.warn("module {}: its packs did not load: {}", module.id(), e.getMessage());
        }
    }

    /** The module's needs file (verified at V7) joins the data gate, so a function of it whose need is unserved is switched off. */
    private void needs(LoadedModule module) {
        com.ash.drishti.server.bindings.BindingService gate = bindings.get();
        com.ash.drishti.module.runtime.ModuleCandidate c = module.candidate();
        if (gate == null || c == null) {
            return;
        }
        Object file = c.manifest().section("data") instanceof Map<?, ?> d ? d.get("needs") : null;
        if (file == null || String.valueOf(file).isBlank()) {
            return;
        }
        com.ash.drishti.packs.bindings.DataNeeds n = com.ash.drishti.packs.bindings.DataNeeds.of(module.id(), c.yaml(String.valueOf(file), "V7"));
        gate.moduleNeeds(module.id(), n);
        if (!n.needs().isEmpty()) {
            LOG.info("module {}: {} data need{} joined the data gate", module.id(), n.needs().size(), n.needs().size() == 1 ? "" : "s");
        }
    }

    private static void stopQuietly(DrishtiModule m) {
        try {
            m.stop();
        } catch (RuntimeException e) {
            LOG.warn("module {}: its entry did not stop cleanly: {}", m.id(), e.toString());
        }
    }
}
