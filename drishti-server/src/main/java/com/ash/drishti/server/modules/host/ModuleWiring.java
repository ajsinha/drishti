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

    public ModuleWiring(Supplier<SourceRegistry> sources, Supplier<EngineRegistry> engines, Supplier<SourceRouter> router, Supplier<AuditLog> audit,
            ConfigurableEnvironment env) {
        this.bridge = new ModuleBridge(sources, engines);
        this.router = router;
        this.audit = audit;
        this.env = env;
        this.dataDir = Path.of(env == null ? "./data" : env.getProperty("drishti.data.dir", "./data")).toAbsolutePath().normalize();
    }

    /** The extension types the bridge connects, by simple name. */
    public List<String> types() {
        return bridge.types();
    }

    /** A module started: its contributions connected, then its entry started. A failure fails the module's start (nothing stays). */
    public void started(LoadedModule module, Contributions contributions) {
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
        LOG.info("module {}: {} contributions connected, {} entr{} started", module.id(), n, started.size(), started.size() == 1 ? "y" : "ies");
    }

    /** Unload step 7, before its context closes: the module's entry stops. */
    public void stopping(LoadedModule module) {
        List<DrishtiModule> started = entries.remove(module.id());
        if (started != null) {
            started.forEach(ModuleWiring::stopQuietly);
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
