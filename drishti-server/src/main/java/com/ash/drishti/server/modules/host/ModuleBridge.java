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

    ModuleBridge(Supplier<SourceRegistry> sources, Supplier<EngineRegistry> engines) {
        port(KindProvider.class, Contributions.Surface.KIND_PROVIDER, (m, k) -> required(sources, "the source registry").attach(new ComputedKindSource(k)));
        port(FunctionProvider.class, Contributions.Surface.FUNCTION, (m, f) -> RachanaFunctions.register(f));
        port(ComputeEngine.class, Contributions.Surface.SPI, (m, e) -> required(engines, "the engine registry").attach(e));
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
        try {
            int n = 0;
            for (Port<?> p : ports) {
                n += connect(m, contributions, p, added);
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
