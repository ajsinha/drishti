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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the lifecycle of the enabled source plugins. Plugins start in parallel on virtual threads; a
 * plugin that fails to start is logged and left out rather than failing the application.
 */
public final class SourceRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SourceRegistry.class);

    private final Map<String, SourcePlugin> plugins;
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;

    public SourceRegistry(List<SourcePlugin> discovered, SourcesProperties props, JsonCodec codec) {
        this.scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("drishti-source-sched-", 0).factory());
        Map<String, SourcePlugin> started = new LinkedHashMap<>();
        List<SourcePlugin> enabled = discovered.stream()
                .filter(p -> props.settingsFor(p.manifest().name()).enabled())
                .toList();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> starts = new ArrayList<>();
            for (SourcePlugin p : enabled) {
                var ctx = new EngineSourceContext(props.settingsFor(p.manifest().name()).settings(), codec, scheduler);
                starts.add(exec.submit(() -> {
                    p.start(ctx);
                    return null;
                }));
            }
            for (int i = 0; i < enabled.size(); i++) {
                SourcePlugin p = enabled.get(i);
                try {
                    starts.get(i).get();
                    started.put(p.manifest().name(), p);
                    LOG.info("source plugin started: {}", p.manifest().name());
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    failures.put(p.manifest().name(), String.valueOf(cause.getMessage()));
                    LOG.error("source plugin {} failed to start", p.manifest().name(), cause);
                }
            }
        }
        this.plugins = Collections.unmodifiableMap(started);
    }

    public Optional<SourcePlugin> plugin(String name) {
        return Optional.ofNullable(plugins.get(name));
    }

    public Collection<SourcePlugin> plugins() {
        return plugins.values();
    }

    public Map<String, String> failures() {
        return Collections.unmodifiableMap(failures);
    }

    @Override
    public void close() {
        plugins.values().forEach(p -> {
            try {
                p.close();
            } catch (Exception e) {
                LOG.warn("closing {} failed", p.manifest().name(), e);
            }
        });
        scheduler.shutdownNow();
    }
}
