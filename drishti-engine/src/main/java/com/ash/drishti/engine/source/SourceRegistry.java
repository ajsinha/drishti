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
 * Owns the lifecycle of the enabled source plugins and of the named connector instances
 * ({@code drishti.sources.connectors}), each a fresh instance of its plugin. Plugins start in parallel on virtual threads; a
 * plugin that fails to start is logged and left out rather than failing the application.
 */
public final class SourceRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SourceRegistry.class);

    private final Map<String, SourcePlugin> plugins;
    private final Map<String, String> failures = new LinkedHashMap<>();
    /** By connector name and by the name its documents carry (source-name): the source and its stale-after. */
    private final Map<String, SourcePlugin> bySource = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.time.Duration> staleAfter = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How fresh a source's data is.
     *
     * @param lastUpdate when it last received new data, or null when it cannot tell
     * @param staleAfter its {@code stale-after} setting, or null for none
     * @param stale true when it has received nothing for longer than {@code staleAfter}
     */
    public record Freshness(String source, java.time.Instant lastUpdate, java.time.Duration staleAfter, boolean stale) {}
    private final ScheduledExecutorService scheduler;

    public SourceRegistry(List<SourcePlugin> discovered, SourcesProperties props, JsonCodec codec) {
        // Shared by every plugin for refreshes, rescans, ticks and nightly cache clearing, several of which block on
        // I/O (a feed download, an Aerospike scan, a Delta reindex). Virtual-thread workers: a blocked task releases
        // its carrier, and sixteen of them cost next to nothing, so one slow task never delays the others.
        this.scheduler = Executors.newScheduledThreadPool(16, Thread.ofVirtual().name("drishti-source-sched-", 0).factory());
        Map<String, SourcePlugin> started = new LinkedHashMap<>();
        // a plugin used through named connectors runs as itself only if it is configured under plugins explicitly
        java.util.Set<String> viaConnectors = new java.util.HashSet<>();
        props.connectors().values().forEach(c -> viaConnectors.add(c.plugin()));
        List<SourcePlugin> enabled = new ArrayList<>(discovered.stream()
                .filter(p -> !viaConnectors.contains(p.manifest().name()) || props.plugins().containsKey(p.manifest().name()))
                .filter(p -> props.settingsFor(p.manifest().name()).enabled())
                .toList());
        Map<SourcePlugin, Map<String, String>> settings = new java.util.IdentityHashMap<>();
        enabled.forEach(p -> settings.put(p, props.settingsFor(p.manifest().name()).settings()));
        props.connectors().forEach((name, c) -> {
            if (!c.enabled()) {
                return;
            }
            SourcePlugin proto = discovered.stream().filter(p -> p.manifest().name().equals(c.plugin())).findFirst().orElse(null);
            if (proto == null) {
                failures.put(name, "no plugin named '" + c.plugin() + "'");
                return;
            }
            try {
                SourcePlugin fresh = proto.getClass().getDeclaredConstructor().newInstance();
                SourcePlugin instance = new ConnectorInstance(name, new java.util.HashSet<>(c.kinds()), fresh);
                enabled.add(instance);
                Map<String, String> own = new LinkedHashMap<>(c.settings());
                own.putIfAbsent("source-name", name);   // documents say which connector they came from
                settings.put(instance, own);
            } catch (ReflectiveOperationException e) {
                failures.put(name, "cannot create " + c.plugin() + ": " + e.getMessage());
            }
        });
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> starts = new ArrayList<>();
            for (SourcePlugin p : enabled) {
                var ctx = new EngineSourceContext(settings.get(p), codec, scheduler);
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
                    Map<String, String> own = settings.get(p);
                    String sourceName = own.getOrDefault("source-name", p.manifest().name());
                    bySource.put(p.manifest().name(), p);
                    bySource.put(sourceName, p);
                    String after = own.get("stale-after");
                    if (after != null && !after.isBlank()) {
                        try {
                            java.time.Duration d = org.springframework.boot.convert.DurationStyle.detectAndParse(after.trim());
                            staleAfter.put(p.manifest().name(), d);
                            staleAfter.put(sourceName, d);
                        } catch (IllegalArgumentException bad) {
                            LOG.warn("{}: stale-after '{}' is not a duration (15m, 2h, 1d); ignored", p.manifest().name(), after);
                        }
                    }
                    LOG.info("source plugin started: {}", p.manifest().name());
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof com.ash.drishti.api.PluginNotConfigured) {
                        LOG.info("source plugin {} is installed but not configured ({}); it stays idle", p.manifest().name(), cause.getMessage());
                        continue;
                    }
                    failures.put(p.manifest().name(), String.valueOf(cause.getMessage()));
                    LOG.error("source plugin {} failed to start", p.manifest().name(), cause);
                }
            }
        }
        this.plugins = Collections.unmodifiableMap(started);
    }

    /** Freshness of the source a document names ({@code provenance.source}), as of now. */
    public Freshness freshness(String source) {
        SourcePlugin p = source == null ? null : bySource.get(source);
        java.time.Instant last = null;
        if (p != null) {
            try {
                last = p.lastUpdate();
            } catch (RuntimeException e) {
                last = null;
            }
        }
        java.time.Duration after = source == null ? null : staleAfter.get(source);
        boolean stale = after != null && last != null && java.time.Duration.between(last, java.time.Instant.now()).compareTo(after) > 0;
        return new Freshness(source, last, after, stale);
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
