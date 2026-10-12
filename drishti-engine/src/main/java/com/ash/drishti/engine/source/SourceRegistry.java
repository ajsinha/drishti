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
public final class SourceRegistry implements AutoCloseable, com.ash.drishti.common.LiveSettings {

    private static final Logger LOG = LoggerFactory.getLogger(SourceRegistry.class);

    /** Copy-on-write: readers take the current snapshot and never lock; changes swap in a new one under {@link #swap}. */
    private volatile Map<String, SourcePlugin> plugins = Map.of();
    private final Map<String, String> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<SourcePlugin> discovered;
    private final JsonCodec codec;
    private final SourcesProperties props;
    private final Map<String, java.util.concurrent.locks.ReentrantLock> locks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, ConnectorStatus> statuses = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, SourcesProperties.ConnectorSettings> applied = new java.util.concurrent.ConcurrentHashMap<>();
    /** Connector names in the order first seen (configuration order is the read order, and a restart keeps its place). */
    private final java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Where a named connector stands.
     *
     * @param state {@code RUNNING}, {@code DISABLED}, {@code IDLE} (installed, not configured) or {@code FAILED}
     * @param problem why it is not running, or null
     * @param since when this state began
     */
    public record ConnectorStatus(String state, String problem, java.time.Instant since) {}
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

    /** Reads through the router, which is built after the registry: bound by {@link #attach}. */
    private final RouterReader reader = new RouterReader();

    static final class RouterReader implements com.ash.drishti.api.EntityReader {
        private static final java.time.Duration BUDGET = java.time.Duration.ofSeconds(20);
        private volatile SourceRouter router;

        private SourceRouter router() {
            SourceRouter r = router;
            if (r == null) {
                throw new IllegalStateException("the server is still starting: other kinds cannot be read yet");
            }
            return r;
        }

        @Override
        public List<com.ash.drishti.api.EntityRef> list(String kind, com.ash.drishti.api.AsOf asOf, int limit) {
            return router().search(kind, "", limit, BUDGET, asOf).stream().map(com.ash.drishti.api.EntityHit::ref).toList();
        }

        @Override
        public Map<com.ash.drishti.api.EntityRef, com.ash.drishti.api.EntityDocument> read(Collection<com.ash.drishti.api.EntityRef> refs,
                com.ash.drishti.api.AsOf asOf) {
            return router().fetchAll(refs, BUDGET, asOf);
        }

        @Override
        public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, Collection<String> paths, com.ash.drishti.api.AsOf asOf) {
            return router().columns(kind, paths, asOf, BUDGET);
        }
    }

    /** Called by the router once it exists, so connectors may read other kinds through it. */
    void attach(SourceRouter router) {
        reader.router = router;
    }
    private final ScheduledExecutorService scheduler;
    /** Connectors that failed to start or lost their connection: tried again on the {@code drishti.retry.connectors} schedule. */
    private final com.ash.drishti.common.RetryBoard retry;
    /** Plugins that run as themselves (not through a connector file) and failed to start: the instance and the settings to start it with. */
    private final Map<String, Map<String, String>> plainSettings = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, SourcePlugin> plainInstances = new java.util.concurrent.ConcurrentHashMap<>();
    /** Plugins that run as themselves, with the settings they were started with (so a pack that changes them can restart just those). */
    private final Map<String, Map<String, String>> plainApplied = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, SourcePlugin> plainAll = new java.util.concurrent.ConcurrentHashMap<>();
    /** Connectors whose failure is a lost connection (health says DOWN) rather than a start that failed. */
    private final java.util.Set<String> lost = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong sweeps = new java.util.concurrent.atomic.AtomicLong();

    public SourceRegistry(List<SourcePlugin> discovered, SourcesProperties props, JsonCodec codec) {
        this(discovered, props, codec, com.ash.drishti.common.RetryPolicy.defaults());
    }

    public SourceRegistry(List<SourcePlugin> discovered, SourcesProperties props, JsonCodec codec, com.ash.drishti.common.RetryPolicy policy) {
        this.retry = new com.ash.drishti.common.RetryBoard("connector", policy);
        this.discovered = List.copyOf(discovered);
        this.codec = codec;
        this.props = props;
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
            applied.put(name, c);
            order.add(name);
            if (!c.enabled()) {
                statuses.put(name, new ConnectorStatus("DISABLED", null, java.time.Instant.now()));
                return;
            }
            try {
                SourcePlugin instance = instantiate(name, c);
                enabled.add(instance);
                settings.put(instance, ownSettings(name, c));
            } catch (IllegalStateException e) {
                failures.put(name, e.getMessage());
                statuses.put(name, new ConnectorStatus("FAILED", e.getMessage(), java.time.Instant.now()));
                retry.failed(name, String.valueOf(e.getMessage()), true);
            }
        });
        for (SourcePlugin p : enabled) {
            if (!applied.containsKey(p.manifest().name())) {
                plainApplied.put(p.manifest().name(), settings.get(p));
                plainAll.put(p.manifest().name(), p);
            }
        }
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> starts = new ArrayList<>();
            for (SourcePlugin p : enabled) {
                var ctx = new EngineSourceContext(settings.get(p), codec, scheduler, reader);
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
                    register(p, settings.get(p));
                    LOG.info("source plugin started: {}", p.manifest().name());
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof com.ash.drishti.api.PluginNotConfigured) {
                        LOG.info("source plugin {} is installed but not configured ({}); it stays idle", p.manifest().name(), cause.getMessage());
                        if (applied.containsKey(p.manifest().name())) {
                            statuses.put(p.manifest().name(), new ConnectorStatus("IDLE", String.valueOf(cause.getMessage()), java.time.Instant.now()));
                        }
                        continue;
                    }
                    failures.put(p.manifest().name(), String.valueOf(cause.getMessage()));
                    if (applied.containsKey(p.manifest().name())) {
                        statuses.put(p.manifest().name(), new ConnectorStatus("FAILED", String.valueOf(cause.getMessage()), java.time.Instant.now()));
                    } else {
                        plainSettings.put(p.manifest().name(), settings.get(p));
                        plainInstances.put(p.manifest().name(), p);
                    }
                    LOG.error("source plugin {} failed to start", p.manifest().name(), cause);
                    retry.failed(p.manifest().name(), String.valueOf(cause.getMessage()), com.ash.drishti.common.RetryPolicy.looksPermanent(cause));
                }
            }
        }
        started.keySet().forEach(n -> {
            if (applied.containsKey(n)) {
                statuses.put(n, new ConnectorStatus("RUNNING", null, java.time.Instant.now()));
            }
        });
        this.plugins = Collections.unmodifiableMap(started);
        scheduler.scheduleWithFixedDelay(this::sweep, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** One pass of the retry timer (every second): retries what is due, and every few seconds looks for connections that were lost. */
    private void sweep() {
        try {
            if (retry.policy().enabled()) {
                for (String name : retry.due()) {
                    retryOne(name);
                }
                if (sweeps.incrementAndGet() % Math.max(1, retry.policy().initial().toSeconds()) == 0) {
                    watchHealth();
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("connector retry pass failed; it runs again in a second", e);
        }
    }

    private void retryOne(String name) {
        if (lost.contains(name)) {
            SourcePlugin p = plugins.get(name);
            String h = p == null ? "DOWN" : healthOf(p);
            if (!h.startsWith("DOWN")) {
                lost.remove(name);
                retry.succeeded(name);
                return;
            }
            int attempt = retry.status(name).map(com.ash.drishti.common.RetryTracker.Status::attempt).orElse(1);
            retry.failed(name, "connection lost: " + h, false);
            if (attempt % 3 == 0 && applied.containsKey(name)) {          // still down after a few looks: start it afresh (it stays listed until it answers)
                applyLocked(name, applied.get(name));
            }
            return;
        }
        if (applied.containsKey(name)) {
            applyLocked(name, applied.get(name));
        } else if (plainInstances.containsKey(name)) {
            startPlain(name);
        } else {
            retry.forget(name);
        }
    }

    private void startPlain(String name) {
        SourcePlugin p = plainInstances.get(name);
        Map<String, String> own = plainSettings.get(name);
        var lock = locks.computeIfAbsent(name, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            closeQuietly(p);
            p.start(new EngineSourceContext(own, codec, scheduler, reader));
            register(p, own);
            swap(name, p);
            failures.remove(name);
            plainInstances.remove(name);
            plainSettings.remove(name);
            LOG.info("source plugin started: {}", name);
            retry.succeeded(name);
        } catch (com.ash.drishti.api.PluginNotConfigured e) {
            retry.forget(name);
        } catch (Exception e) {
            failures.put(name, String.valueOf(e.getMessage()));
            retry.failed(name, String.valueOf(e.getMessage()), com.ash.drishti.common.RetryPolicy.looksPermanent(e));
        } finally {
            lock.unlock();
        }
    }

    private static String healthOf(SourcePlugin p) {
        try {
            String h = p.health();
            return h == null ? "UP" : h;
        } catch (RuntimeException e) {
            return "DOWN: " + e.getMessage();
        }
    }

    /** A running connector whose plugin says DOWN has lost its connection: it is listed as failing and looked at on the schedule. */
    private void watchHealth() {
        for (var e : plugins.entrySet()) {
            String name = e.getKey();
            if (!applied.containsKey(name) || retry.failing(name)) {
                continue;
            }
            String h = healthOf(e.getValue());
            if (h.startsWith("DOWN")) {
                lost.add(name);
                retry.failed(name, "connection lost: " + h, false);
            }
        }
    }

    /**
     * Packs changed their settings for a plugin that runs as itself (the sample-data folders of the demo plugin, for one): the
     * plugins whose settings differ now are restarted, one at a time, each taken out of routing while it restarts; the others are
     * not touched. A plugin that fails to start is listed and retried like any connector.
     */
    @Override
    public Runnable prepare(org.springframework.core.env.Environment candidate) {
        Map<String, SourcesProperties.PluginSettings> fresh = org.springframework.boot.context.properties.bind.Binder.get(candidate)
                .bind("drishti.sources.plugins", org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, SourcesProperties.PluginSettings.class))
                .orElse(Map.of());
        Map<String, Map<String, String>> next = new LinkedHashMap<>();
        plainApplied.keySet().forEach(n -> next.put(n, fresh.getOrDefault(n, new SourcesProperties.PluginSettings(true, Map.of())).settings()));
        return () -> next.forEach((name, own) -> {
            if (!own.equals(plainApplied.get(name))) {
                restartPlain(name, own);
            }
        });
    }

    private void restartPlain(String name, Map<String, String> own) {
        SourcePlugin p = plainAll.get(name);
        var lock = locks.computeIfAbsent(name, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            if (plugins.get(name) != null) {
                swap(name, null);
                unregister(name, p);
                closeQuietly(p);
            }
            plainApplied.put(name, own);
            plainSettings.put(name, own);
            plainInstances.put(name, p);
        } finally {
            lock.unlock();
        }
        retry.forget(name);
        startPlain(name);
    }

    /** Retry state of a connector that is failing, or empty. */
    public Optional<com.ash.drishti.common.RetryTracker.Status> retryStatus(String name) {
        return retry.status(name);
    }

    /** Admin "Retry now": the connector (or, with null, every failing one) is tried at once, on the calling thread. Returns how many. */
    public int retryNow(String name) {
        List<String> names = name == null ? new ArrayList<>(retry.statuses().keySet()) : retry.failing(name) ? List.of(name) : List.of();
        for (String n : names) {
            retry.retryNow(n);
            retryOne(n);
        }
        return names.size();
    }

    /** Hears every failure and recovery, for the audit log. */
    public void retryListener(com.ash.drishti.common.RetryBoard.Listener l) {
        retry.listener(l);
    }

    public com.ash.drishti.common.RetryPolicy retryPolicy() {
        return retry.policy();
    }

    /** A fresh instance of the connector's plugin, named and limited to its kinds. */
    private SourcePlugin instantiate(String name, SourcesProperties.ConnectorSettings c) {
        SourcePlugin proto = discovered.stream().filter(p -> p.manifest().name().equals(c.plugin())).findFirst().orElse(null);
        if (proto == null) {
            throw new IllegalStateException("no plugin named '" + c.plugin() + "'");
        }
        try {
            return new ConnectorInstance(name, new java.util.HashSet<>(c.kinds()), proto.getClass().getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create " + c.plugin() + ": " + e.getMessage());
        }
    }

    private static Map<String, String> ownSettings(String name, SourcesProperties.ConnectorSettings c) {
        Map<String, String> own = new LinkedHashMap<>(com.ash.drishti.common.ConnectorSecrets.resolve(c.settings()));
        String given = own.get("source-name");
        if (given == null || given.isBlank()) {
            own.put("source-name", name);       // documents say which connector they came from (a blank setting, as a form sends, counts as none)
        }
        return own;
    }

    /** Makes a started plugin findable by name and by the source name its documents carry, with its freshness limit. */
    private void register(SourcePlugin p, Map<String, String> own) {
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
    }

    private void unregister(String name, SourcePlugin p) {
        bySource.values().removeIf(x -> x == p);
        staleAfter.keySet().removeIf(k -> !bySource.containsKey(k));
    }

    private synchronized void swap(String name, SourcePlugin next) {
        if (next != null && !order.contains(name)) {
            order.add(name);
        }
        Map<String, SourcePlugin> m = new LinkedHashMap<>(plugins);
        if (next == null) {
            m.remove(name);
        } else {
            m.put(name, next);
        }
        Map<String, SourcePlugin> sorted = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (!order.contains(k)) {
                sorted.put(k, v);                    // plugins that run as themselves come first
            }
        });
        order.forEach(k -> {
            if (m.containsKey(k)) {
                sorted.put(k, m.get(k));
            }
        });
        plugins = Collections.unmodifiableMap(sorted);
    }

    /**
     * Brings a named connector to the given settings while the server runs: stops the instance that is running (it is
     * taken out of routing at once, kept open for {@code connectors-drain} so reads in flight finish, then closed),
     * and starts a new one unless the settings say disabled. One change per connector at a time; others are not held up.
     *
     * @return the state it ends in
     */
    public ConnectorStatus apply(String name, SourcesProperties.ConnectorSettings c) {
        retry.forget(name);                                   // new settings: the count starts again
        lost.remove(name);
        return applyLocked(name, c);
    }

    private ConnectorStatus applyLocked(String name, SourcesProperties.ConnectorSettings c) {
        var lock = locks.computeIfAbsent(name, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            stopLocked(name);
            applied.put(name, c);
            if (!c.enabled()) {
                failures.remove(name);
                retry.forget(name);
                return status(name, "DISABLED", null);
            }
            SourcePlugin instance;
            try {
                instance = instantiate(name, c);
            } catch (IllegalStateException e) {
                failures.put(name, e.getMessage());
                retry.failed(name, String.valueOf(e.getMessage()), true);
                return status(name, "FAILED", e.getMessage());
            }
            Map<String, String> own;
            try {
                own = ownSettings(name, c);
            } catch (IllegalStateException e) {
                failures.put(name, e.getMessage());
                retry.failed(name, String.valueOf(e.getMessage()), true);
                return status(name, "FAILED", e.getMessage());
            }
            try {
                instance.start(new EngineSourceContext(own, codec, scheduler, reader));
            } catch (Exception e) {
                closeQuietly(instance);
                if (e instanceof com.ash.drishti.api.PluginNotConfigured) {
                    failures.remove(name);
                    retry.forget(name);
                    return status(name, "IDLE", e.getMessage());
                }
                failures.put(name, String.valueOf(e.getMessage()));
                if (!retry.failing(name)) {
                    LOG.error("connector {} failed to start", name, e);
                }
                retry.failed(name, String.valueOf(e.getMessage()), com.ash.drishti.common.RetryPolicy.looksPermanent(e));
                return status(name, "FAILED", String.valueOf(e.getMessage()));
            }
            register(instance, own);
            swap(name, instance);
            failures.remove(name);
            LOG.info("connector {} started ({})", name, c.plugin());
            if (!lost.contains(name)) {
                retry.succeeded(name);                          // a lost connection is cleared when the plugin says it is up, not by the restart
            }
            return status(name, "RUNNING", null);
        } finally {
            lock.unlock();
        }
    }

    /** Stops a named connector and forgets its settings (its file was deleted). */
    public void remove(String name) {
        var lock = locks.computeIfAbsent(name, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            stopLocked(name);
            applied.remove(name);
            statuses.remove(name);
            failures.remove(name);
            retry.forget(name);
            lost.remove(name);
        } finally {
            lock.unlock();
        }
    }

    private ConnectorStatus status(String name, String state, String problem) {
        ConnectorStatus st = new ConnectorStatus(state, problem, java.time.Instant.now());
        statuses.put(name, st);
        return st;
    }

    private void stopLocked(String name) {
        SourcePlugin old = plugins.get(name);
        if (old == null) {
            return;
        }
        swap(name, null);
        unregister(name, old);
        long drain = props.connectorsDrain().toMillis();
        if (drain > 0) {
            try {
                Thread.sleep(drain);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly(old);
        LOG.info("connector {} stopped", name);
    }

    private static void closeQuietly(SourcePlugin p) {
        try {
            p.close();
        } catch (Exception e) {
            LOG.warn("closing {} failed", p.manifest().name(), e);
        }
    }

    /** The settings a named connector was last started (or switched off) with, if it is known. */
    public Optional<SourcesProperties.ConnectorSettings> applied(String name) {
        return Optional.ofNullable(applied.get(name));
    }

    /** State of every named connector the registry knows. */
    public Map<String, ConnectorStatus> connectorStatuses() {
        return Map.copyOf(statuses);
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

    /** The connector a document's {@code provenance.source} names: a connector's own name or the system-of-record name it serves. */
    public Optional<SourcePlugin> connector(String source) {
        return source == null ? Optional.empty() : Optional.ofNullable(bySource.get(source));
    }

    public Optional<SourcePlugin> plugin(String name) {
        return Optional.ofNullable(plugins.get(name));
    }

    public Collection<SourcePlugin> plugins() {
        return plugins.values();
    }

    public Map<String, String> failures() {
        return Map.copyOf(failures);
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
