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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.lifecycle.ComponentHealth;
import com.ash.drishti.api.lifecycle.ComponentStats;
import com.ash.drishti.api.lifecycle.Lifecycle;
import com.ash.drishti.api.lifecycle.LifecycleState;

/**
 * A named connector as the kernel's one {@link Lifecycle} contract (docs/architecture/LIFECYCLE.md section 4): a view over the
 * {@link SourceRegistry}, which owns the connector's machine, instance, stand-in and counters. Cheap to make; holds nothing.
 */
final class ConnectorLifecycle implements Lifecycle {

    private final SourceRegistry registry;
    private final String name;

    ConnectorLifecycle(SourceRegistry registry, String name) {
        this.registry = registry;
        this.name = name;
    }

    @Override
    public Type type() {
        return Type.CONNECTOR;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public LifecycleState state() {
        return registry.lifecycleState(name);
    }

    @Override
    public void init(String actor) {
        registry.init(name, actor);
    }

    @Override
    public void start(String actor) {
        registry.start(name, actor);
    }

    @Override
    public void suspend(String actor) {
        registry.suspend(name, actor);
    }

    @Override
    public void resume(String actor) {
        registry.resume(name, actor);
    }

    @Override
    public void shutdown(String actor) {
        registry.shutdown(name, actor);
    }

    @Override
    public ComponentHealth health() {
        return registry.health(name);
    }

    @Override
    public ComponentStats stats() {
        return registry.stats(name);
    }
}
