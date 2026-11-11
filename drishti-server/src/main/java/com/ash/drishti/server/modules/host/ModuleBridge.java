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

import com.ash.drishti.common.Extensions;
import com.ash.drishti.engine.source.ComputedKindSource;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.kernel.api.ai.AiToolProvider;
import com.ash.drishti.kernel.api.approvals.ApprovalSource;
import com.ash.drishti.kernel.api.capacity.CapacityFigures;
import com.ash.drishti.kernel.api.data.KindProvider;
import com.ash.drishti.kernel.api.engines.ComputeEngine;
import com.ash.drishti.kernel.api.explain.Explainer;
import com.ash.drishti.kernel.api.function.FunctionProvider;
import com.ash.drishti.kernel.api.health.HealthFigures;
import com.ash.drishti.kernel.api.modules.ApiAlias;
import com.ash.drishti.kernel.api.official.OfficialResults;
import com.ash.drishti.kernel.api.pivot.PivotSourceProvider;
import com.ash.drishti.kernel.api.view.ViewAugmenter;
import com.ash.drishti.kernel.api.vocabulary.DomainVocabulary;
import com.ash.drishti.module.runtime.Contributions;
import com.ash.drishti.module.runtime.LoadedModule;
import com.ash.drishti.server.engines.EngineRegistry;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The registration bridge (docs/architecture/KERNEL_AND_MODULES.md section 12.3): every object of a loaded module that is of one of the
 * kernel's extension types, from its child context's own beans and from the {@link ServiceLoader} entries of its jars, is made visible to
 * the kernel's collection point for that type, and recorded in {@link Contributions} with the action that withdraws it at the unload step
 * the design gives the surface. A failure to add one fails the module's start (what was added is withdrawn).
 */
final class ModuleBridge {

    /** One extension type: the surface it is, and how the kernel takes an object of it in (returns the withdrawal). */
    record Port<T>(Class<T> type, Contributions.Surface surface, BiFunction<String, T, Runnable> attach) {
    }

    private final List<Port<?>> ports = new ArrayList<>();
    private Supplier<SourceRegistry> sources = () -> null;

    ModuleBridge(Supplier<SourceRegistry> sources, Supplier<EngineRegistry> engines) {
        this(sources, engines, (m, p) -> Map.of(), () -> null);
    }

    /**
     * @param settings the settings a module's connector extension starts with ({@code module-dir}: its installed folder, {@code data-dir}:
     *                 its own data folder), by module
     * @param budget   the server's memory budget, which a module's engine caches join ({@link com.ash.drishti.server.caches.CacheGroup}); null
     *                 for none
     */
    ModuleBridge(Supplier<SourceRegistry> sources, Supplier<EngineRegistry> engines, BiFunction<String, com.ash.drishti.api.SourcePlugin, Map<String, String>> settings,
            Supplier<com.ash.drishti.server.caches.MemoryBudget> budget) {
        // a computed kind (the kernel API's KindProvider) and a connector extension (the plugin API's SourcePlugin), one way: a type the
        // site's connector files name (they start with it: banking's var-system-baskets and var-positions), else a source routed under its
        // own name (a VCF reader), while the module is loaded
        port(KindProvider.class, Contributions.Surface.KIND_PROVIDER,
                (m, k) -> required(sources, "the source registry").attachExtension(new ComputedKindSource(k), Map.of()));
        port(com.ash.drishti.api.SourcePlugin.class, Contributions.Surface.KIND_PROVIDER,
                (m, p) -> required(sources, "the source registry").attachExtension(p, settings.apply(m, p)));
        this.sources = sources;
        port(FunctionProvider.class, Contributions.Surface.FUNCTION, (m, f) -> RachanaFunctions.register(f));
        port(ComputeEngine.class, Contributions.Surface.SPI, (m, e) -> required(engines, "the engine registry").attach(e, listed(m, e.id())));
        port(com.ash.drishti.server.caches.CacheGroup.class, Contributions.Surface.SPI, (m, g) -> join(budget.get(), g));
        // the kernel's own extension points a first-party module reaches through its allowance: collected live (Extensions) by the kernel
        live(com.ash.drishti.server.node.ReadinessGate.class, Contributions.Surface.SPI);
        live(com.ash.drishti.server.node.Drainable.class, Contributions.Surface.SPI);
        live(com.ash.drishti.server.caches.CacheReport.Owner.class, Contributions.Surface.SPI);
        live(com.ash.drishti.dataload.ReadyRule.class, Contributions.Surface.SPI);
        live(com.ash.drishti.server.flight.FlightForm.class, Contributions.Surface.SPI);
        live(ApprovalSource.class, Contributions.Surface.SPI);
        live(HealthFigures.class, Contributions.Surface.SPI);           // objects of the module: withdrawn before its loader is checked (step 4, not 9)
        live(CapacityFigures.class, Contributions.Surface.SPI);
        live(Explainer.class, Contributions.Surface.SPI);
        live(ViewAugmenter.class, Contributions.Surface.SPI);
        live(PivotSourceProvider.class, Contributions.Surface.SPI);
        live(DomainVocabulary.class, Contributions.Surface.GLOSSARY);
        live(OfficialResults.class, Contributions.Surface.SPI);
        live(ApiAlias.class, Contributions.Surface.ROUTE);
        live(AiToolProvider.class, Contributions.Surface.AI_TOOL);
    }

    /** The module's own catalogue entry of an engine ({@code META-INF/drishti/engines.yaml} in its jars), or null. */
    private static com.ash.drishti.server.engines.EngineCatalogue.Entry listed(String module, String engine) {
        return CATALOGUES.getOrDefault(module, Map.of()).get(engine);
    }

    /** The modules' engine catalogues while they connect (read from the module's loader in {@link #connect}). */
    private static final Map<String, Map<String, com.ash.drishti.server.engines.EngineCatalogue.Entry>> CATALOGUES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** A module's engine caches join the budget; the withdrawal takes exactly the members joining added out again. */
    private static Runnable join(com.ash.drishti.server.caches.MemoryBudget b, com.ash.drishti.server.caches.CacheGroup g) {
        if (b == null) {
            return () -> { };
        }
        java.util.Set<String> before = b.memberNames();
        g.join(b);
        java.util.Set<String> added = new java.util.HashSet<>(b.memberNames());
        added.removeAll(before);
        return () -> added.forEach(b::leave);
    }

    private <T> void port(Class<T> type, Contributions.Surface surface, BiFunction<String, T, Runnable> attach) {
        ports.add(new Port<>(type, surface, attach));
    }

    /** A type whose collection point reads {@link Extensions}. */
    private <T> void live(Class<T> type, Contributions.Surface surface) {
        port(type, surface, (m, o) -> Extensions.add(type, m, o));
    }

    private static <T> T required(Supplier<T> s, String what) {
        T t = s.get();
        if (t == null) {
            throw new IllegalStateException(what + " is not running on this server");
        }
        return t;
    }

    /** The extension types the bridge knows, by simple name (Health and the developer guide list them). */
    List<String> types() {
        return ports.stream().map(p -> p.type().getSimpleName()).toList();
    }

    /** Connects every contribution of the module; returns how many. A failure withdraws what this call added and is thrown. */
    int connect(LoadedModule m, Contributions contributions) {
        List<Runnable> added = new ArrayList<>();
        if (m.loader() != null) {
            try (java.io.InputStream in = m.loader().getResourceAsStream(com.ash.drishti.server.engines.EngineCatalogue.MODULE_RESOURCE)) {
                if (in != null) {
                    CATALOGUES.put(m.id(), com.ash.drishti.server.engines.EngineCatalogue.moduleEntries(in));
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(m.id() + ": " + com.ash.drishti.server.engines.EngineCatalogue.MODULE_RESOURCE + " does not read", e);
            }
        }
        try {
            int n = 0;
            if (m.loader() != null) {                // its classes by name for the kernel (a pack's reactive engine), until it leaves
                Runnable w = com.ash.drishti.common.ModuleLoaders.add(m.loader());
                added.add(w);
                contributions.add(m.id(), Contributions.Surface.SPI, "class loader " + m.id(), m.loader(), w);
            }
            for (Port<?> p : ports) {
                n += connect(m, contributions, p, added);
            }
            SourceRegistry reg = sources.get();
            if (reg != null) {
                reg.retryFailed();                   // a connector that named the module's engine or type starts now
            }
            return n;
        } catch (RuntimeException | LinkageError e) {
            for (int i = added.size() - 1; i >= 0; i--) {
                try {
                    added.get(i).run();
                } catch (RuntimeException ignored) {
                    // the unload's own withdrawal retries it
                }
            }
            throw e;
        } finally {
            CATALOGUES.remove(m.id());
        }
    }

    private <T> int connect(LoadedModule m, Contributions contributions, Port<T> p, List<Runnable> added) {
        int n = 0;
        for (T o : found(m, p.type())) {
            Runnable w = p.attach().apply(m.id(), o);
            added.add(w);
            contributions.add(m.id(), p.surface(), p.type().getSimpleName() + " " + o.getClass().getName(), o.getClass().getClassLoader(), w);
            n++;
        }
        return n;
    }

    /** The module's own objects of a type: its context's beans (not the kernel's, its parent's), then its jars' service entries. */
    static <T> List<T> found(LoadedModule m, Class<T> type) {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        List<T> out = new ArrayList<>();
        if (m.context() != null) {
            for (T bean : m.context().getBeanFactory().getBeansOfType(type, false, true).values()) {
                if (seen.put(bean, true) == null) {
                    out.add(bean);
                }
            }
        }
        if (m.loader() != null) {
            for (T o : ServiceLoader.load(type, m.loader())) {
                if (o.getClass().getClassLoader() == m.loader() && out.stream().noneMatch(x -> x.getClass() == o.getClass())) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
