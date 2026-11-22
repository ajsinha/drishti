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
package com.ash.drishti.server.lifecycle;

import com.ash.drishti.api.lifecycle.LifecycleState;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackLifecycles;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.PackReloader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a pack transition does to this server (docs/architecture/LIFECYCLE.md section 3): the pack reloader validates, registers and
 * unregisters (one path, the same one a pack folder change takes), and the source registry suspends and resumes the connectors that only the
 * pack uses. Connectors another running pack also uses are never touched by one pack's suspend.
 */
final class PackLifecycleActions implements PackLifecycles.Actions {

    private final PackReloader reloader;
    private final PackRegistry registry;
    private final SourceRegistry sources;

    PackLifecycleActions(PackReloader reloader, PackRegistry registry, SourceRegistry sources) {
        this.reloader = reloader;
        this.registry = registry;
        this.sources = sources;
    }

    @Override
    public void validate(String pack) {
        reloader.check(pack);
    }

    @Override
    public void load(String pack, String actor) {
        reloader.startPack(pack, actor);
    }

    @Override
    public void unload(String pack, String actor) {
        reloader.stopPack(pack, actor);
    }

    /** The connectors {@code pack} names that no other running pack names. */
    List<String> ownConnectors(String pack) {
        Set<String> mine = new LinkedHashSet<>();
        Set<String> others = new LinkedHashSet<>();
        for (Pack p : registry.packs()) {
            (p.name().equals(pack) ? mine : others).addAll(p.connectorRefs());
        }
        mine.removeAll(others);
        return List.copyOf(mine);
    }

    @Override
    public List<String> suspendParts(String pack, String actor) {
        List<String> done = new ArrayList<>();
        try {
            for (String c : ownConnectors(pack)) {
                if (sources.lifecycleState(c) == LifecycleState.RUNNING) {
                    sources.suspend(c, actor);
                    done.add(c);
                }
            }
        } catch (RuntimeException e) {
            resumeParts(pack, done, actor);                    // all or nothing: the pack serves on as it was
            throw e;
        }
        return done;
    }

    @Override
    public void resumeParts(String pack, List<String> connectors, String actor) {
        RuntimeException first = null;
        for (String c : connectors) {
            if (sources.lifecycleState(c) == LifecycleState.SUSPENDED) {
                try {
                    sources.resume(c, actor);
                } catch (RuntimeException e) {
                    first = first == null ? e : first;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
