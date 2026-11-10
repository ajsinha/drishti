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

import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.module.DrishtiModule;
import com.ash.drishti.kernel.api.module.ModuleApi;
import com.ash.drishti.module.runtime.Contributions;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.module.runtime.LoadedModule;
import com.ash.drishti.module.runtime.ModuleDiscovery;
import com.ash.drishti.module.runtime.ModuleHealth;
import com.ash.drishti.module.runtime.ModuleHost;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.module.runtime.RouteTable;
import com.ash.drishti.server.placement.PlacementHost;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.LRUMap;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The module host of the kernel (docs/architecture/KERNEL_AND_MODULES.md sections 4, 9.8 and 12): at start it reads the module folders of
 * {@code drishti.modules.path}, and the module runtime discovers, verifies (V1-V8) and loads every {@code .dmod} module in dependency order,
 * each in its own class loader and child Spring context. A module placed on no part of this node is not loaded ({@link PlacementHost});
 * one switched off ({@code drishti.modules.<id>.enabled=false}) neither. For a loaded module the host connects its contributions through
 * the registration bridge, mounts its controllers under its API namespace, and starts its entry ({@link DrishtiModule#start}); unloading
 * reverses each, in the design's order. With no module folder it does nothing.
 */
public final class KernelModuleHost implements ModuleHost, SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(KernelModuleHost.class);

    /** A module's web part: its handler mapping and adapter, over its own context only. */
    private record ModuleWeb(RequestMappingHandlerMapping mapping, RequestMappingHandlerAdapter adapter) {
    }

    private final ConfigurableEnvironment env;
    private final ApplicationContext kernel;
    private final Supplier<PlacementHost> placement;
    private final ModuleBridge bridge;
    private final Supplier<SourceRouter> router;
    private final Supplier<AuditLog> audit;
    private final Supplier<ObjectMapper> json;
    private final List<Path> folders;
    private final Path dataDir;
    private final Map<String, ModuleWeb> web = new ConcurrentHashMap<>();
    private final Map<String, List<DrishtiModule>> entries = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Integer> connected = new ConcurrentHashMap<>();
    private final RouteTable none = new RouteTable();
    private volatile ModuleRuntime runtime;
    private volatile boolean running;

    KernelModuleHost(ConfigurableEnvironment env, ApplicationContext kernel, Supplier<PlacementHost> placement, ModuleBridge bridge,
            Supplier<SourceRouter> router, Supplier<AuditLog> audit, Supplier<ObjectMapper> json) {
        this.env = env;
        this.kernel = kernel;
        this.placement = placement;
        this.bridge = bridge;
        this.router = router;
        this.audit = audit;
        this.json = json;
        this.folders = folders(env.getProperty("drishti.modules.path", ""));
        this.dataDir = Path.of(env.getProperty("drishti.data.dir", "./data")).toAbsolutePath().normalize();
    }

    /** The module folders of the setting: separated by commas or the platform's path separator; blank for none. */
    static List<Path> folders(String setting) {
        List<Path> out = new ArrayList<>();
        for (String s : setting.split("[," + File.pathSeparator + "]")) {
            if (!s.isBlank()) {
                out.add(Path.of(s.strip()).toAbsolutePath().normalize());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------- lifecycle

    @Override
    public synchronized void start() {
        running = true;
        if (folders.isEmpty()) {
            LOG.info("modules: no module folder (drishti.modules.path); only the modules built into this server");
            return;
        }
        runtime = new ModuleRuntime(KernelFacts.ofKernel(), kernel, KernelModuleHost.class.getClassLoader(), this,
                folders.get(0).resolve("tombstones"));
        rescan();
    }

    /** Discovers the folders again and loads every module not loaded (a new install, or one removed and put back). */
    public synchronized List<ModuleHealth> rescan() {
        ModuleRuntime rt = runtime;
        if (rt == null) {
            return List.of();
        }
        List<ModuleHealth> rows = rt.start(folders, ModuleDiscovery.readActive(folders.get(0).resolve("active.yaml")),
                id -> env.getProperty("drishti.modules." + id + ".enabled", Boolean.class, true));
        LOG.info("modules from {}: {}", folders, rows.stream().map(h -> h.id() + " " + h.state()).toList());
        return rows;
    }

    /** Unloads a module: off (its paths 404, it may come back) or removed (410, a tombstone kept); the unload's report. */
    public synchronized ModuleRuntime.UnloadReport unload(String id, boolean remove) {
        ModuleRuntime rt = runtime;
        if (rt == null || rt.loaded(id).isEmpty()) {
            throw new IllegalArgumentException("no module '" + id + "' is loaded here");
        }
        return rt.unload(id, remove ? ModuleRuntime.Leave.REMOVE : ModuleRuntime.Leave.DISABLE, true);
    }

    @Override
    public synchronized void stop() {
        running = false;
        ModuleRuntime rt = runtime;
        runtime = null;
        if (rt != null) {
            rt.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Late: after every kernel service is up; stopped early, before them. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    // ------------------------------------------------------------------------------------------------------------ the runtime asks

    @Override
    public boolean placedHere(DmodManifest manifest) {
        PlacementHost p = placement.get();
        return p == null || p.placedHere(manifest);
    }

    @Override
    public void started(LoadedModule module, Contributions contributions) {
        String id = module.id();
        PlacementHost p = placement.get();
        if (p != null) {
            p.started(module, contributions);
        }
        inFlight.put(id, new AtomicInteger());
        connected.put(id, bridge.connect(module, contributions));
        if (module.context() != null && module.manifest().api() != null) {
            ModuleWeb w = web(module);
            web.put(id, w);
            contributions.add(id, Contributions.Surface.ROUTE, "controllers of /api/v1/" + module.manifest().api().prefix(),
                    module.loader(), () -> web.remove(id, w));
        }
        List<DrishtiModule> started = new ArrayList<>();
        if (module.context() != null) {
            for (DrishtiModule m : ModuleBridge.found(module, DrishtiModule.class)) {
                m.start(new KernelModuleContext(id, router, audit, dataDir.resolve("modules").resolve(id), env));
                started.add(m);
            }
        }
        entries.put(id, started);
    }

    /** Unload step 3: requests in flight to the module's controllers finish (at most ten seconds). */
    @Override
    public void drain(String module) {
        AtomicInteger n = inFlight.get(module);
        long until = System.nanoTime() + 10_000_000_000L;
        while (n != null && n.get() > 0 && System.nanoTime() < until) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void stopping(LoadedModule module) {
        List<DrishtiModule> started = entries.remove(module.id());
        if (started != null) {
            for (DrishtiModule m : started) {
                try {
                    m.stop();
                } catch (RuntimeException e) {
                    LOG.warn("module {}: its entry did not stop cleanly: {}", module.id(), e.toString());
                }
            }
        }
        web.remove(module.id());
        inFlight.remove(module.id());
        connected.remove(module.id());
    }

    @Override
    public void event(String name, String module) {
        PlacementHost p = placement.get();
        if (p != null) {
            p.event(name, module);
        }
        LOG.info("module {}: {}", module, name);
    }

    // -------------------------------------------------------------------------------------------------------------- dispatch

    private ModuleWeb web(LoadedModule module) {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(module.context());
        mapping.setPathPrefixes(Map.of("/api/v1/" + module.manifest().api().prefix(), HandlerTypePredicate.forAnnotation(ModuleApi.class)));
        mapping.afterPropertiesSet();
        ObjectMapper base = json.get() == null ? new ObjectMapper() : json.get();
        // its own copy and type cache: nothing of the module's classes stays in the kernel's mapper after the unload
        ObjectMapper own = base.copy().setTypeFactory(TypeFactory.defaultInstance().withCache(new LRUMap<>(16, 200)));
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.setApplicationContext(module.context());
        adapter.setMessageConverters(List.of(new ByteArrayHttpMessageConverter(), new StringHttpMessageConverter(StandardCharsets.UTF_8),
                new MappingJackson2HttpMessageConverter(own)));
        adapter.afterPropertiesSet();
        return new ModuleWeb(mapping, adapter);
    }

    /** Hands a request to a loaded module's controllers; false when none of them maps it. */
    boolean dispatch(String module, HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        ModuleWeb w = web.get(module);
        AtomicInteger n = inFlight.get(module);
        if (w == null || n == null) {
            return false;
        }
        n.incrementAndGet();
        Object previous = req.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
        Thread t = Thread.currentThread();
        ClassLoader before = t.getContextClassLoader();
        try {
            ServletRequestPathUtils.parseAndCache(req);
            HandlerExecutionChain chain = w.mapping().getHandler(req);
            if (chain == null) {
                return false;
            }
            runtime().loaded(module).map(LoadedModule::loader).ifPresent(t::setContextClassLoader);
            w.adapter().handle(req, res, chain.getHandler());
            return true;
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        } finally {
            t.setContextClassLoader(before);
            if (previous == null) {
                ServletRequestPathUtils.clearParsedRequestPath(req);
            } else {
                req.setAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE, previous);
            }
            n.decrementAndGet();
        }
    }

    /** A page of a loaded module: {@code console/pages/<target>} in its folder. */
    Optional<Path> pageFile(String module, String target) {
        return runtime().loaded(module).map(m -> {
            Path pages = m.candidate().dir().resolve("console/pages").normalize();
            Path f = pages.resolve(target).normalize();
            return f.startsWith(pages) ? f : null;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------- what others ask

    private ModuleRuntime runtime() {
        ModuleRuntime rt = runtime;
        if (rt == null) {
            throw new IllegalStateException("the module host is not running");
        }
        return rt;
    }

    /** The live route table (an empty one with no module folder). */
    public RouteTable routes() {
        ModuleRuntime rt = runtime;
        return rt == null ? none : rt.routes();
    }

    /** True when any module route (live, off or tombstone) is in the table. */
    boolean hasRoutes() {
        return !routes().snapshot().routes().isEmpty();
    }

    /** Every module's Health row. */
    public List<ModuleHealth> health() {
        ModuleRuntime rt = runtime;
        return rt == null ? List.of() : rt.health();
    }

    /** True when the module is loaded here. */
    public boolean loaded(String id) {
        ModuleRuntime rt = runtime;
        return rt != null && rt.loaded(id).isPresent();
    }

    /** What Health and Admin show: the folders, the bridge's types, and each module's row with what it contributed. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folders", folders.stream().map(Path::toString).toList());
        out.put("bridge", bridge.types());
        List<Map<String, Object>> rows = new ArrayList<>();
        ModuleRuntime rt = runtime;
        for (ModuleHealth h : health()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", h.id());
            r.put("version", h.version());
            r.put("state", h.state().name().toLowerCase(java.util.Locale.ROOT));
            if (h.reason() != null) {
                r.put("reason", h.reason());
            }
            if (rt != null && rt.loaded(h.id()).isPresent()) {
                r.put("contributions", rt.contributions().of(h.id()).stream().map(c -> c.surface() + " " + c.name()).toList());
                r.put("routes", rt.routes().snapshot().of(h.id()).stream().map(RouteTable.Route::path).toList());
            }
            rows.add(r);
        }
        out.put("modules", rows);
        return out;
    }
}
