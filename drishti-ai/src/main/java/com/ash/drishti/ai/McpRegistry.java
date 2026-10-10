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
package com.ash.drishti.ai;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP servers by logical name; reloaded from the folder like the LLM registry. Connections open on first use. */
public final class McpRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(McpRegistry.class);

    /** Counters per server. */
    public static final class State {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        volatile Instant lastOk;
        volatile Instant lastFailure;
        volatile String lastError = "";
        volatile boolean lastWasFailure;
    }

    public record Entry(McpServerConfig config, McpClient client, String error, State state) {
        public boolean usable() {
            return client != null && config.enabled();
        }
    }

    public record Health(LlmRegistry.Status status, String detail, Instant lastOk, Instant lastFailure, long calls, long failures) {}

    private record Snapshot(Map<String, Entry> entries, Map<String, String> fileErrors) {}

    private final Supplier<McpServerConfigLoader.Loaded> source;
    private final Map<String, McpClientFactory> factories = new LinkedHashMap<>();
    private final AtomicReference<Snapshot> snap = new AtomicReference<>(new Snapshot(Map.of(), Map.of()));
    /** Servers that could not be set up, or whose last call failed: set up again / reconnected on the {@code drishti.retry.mcp} schedule. */
    private final com.ash.drishti.common.RetryBoard retry;
    private final java.util.concurrent.locks.ReentrantLock reloadLock = new java.util.concurrent.locks.ReentrantLock();

    public McpRegistry(List<McpClientFactory> factories, Supplier<McpServerConfigLoader.Loaded> source) {
        this(factories, source, com.ash.drishti.common.RetryPolicy.defaults());
    }

    public McpRegistry(List<McpClientFactory> factories, Supplier<McpServerConfigLoader.Loaded> source, com.ash.drishti.common.RetryPolicy policy) {
        this.retry = new com.ash.drishti.common.RetryBoard("mcp server", policy);
        factories.forEach(f -> this.factories.putIfAbsent(f.transport().toLowerCase(java.util.Locale.ROOT), f));
        this.source = source;
    }

    public static List<McpClientFactory> discover(List<McpClientFactory> extra) {
        List<McpClientFactory> all = new java.util.ArrayList<>(extra);
        java.util.ServiceLoader.load(McpClientFactory.class).forEach(all::add);
        return all;
    }

    /** Retry state of the servers that failed to set up or lost their connection. */
    public com.ash.drishti.common.RetryBoard retry() {
        return retry;
    }

    /** Admin "Retry now": servers that failed to set up are set up again, and servers whose last call failed reconnect, at once. */
    public int retryNow() {
        int n = retry.retryAllNow();
        reload();
        return n;
    }

    /**
     * Reads the folder again and sets up the servers (a server refused earlier, with the same file, waits for its retry time).
     * Then every server whose last call failed is reconnected when its retry time has come. No lock is held while connecting.
     */
    public void reload() {
        reloadLock.lock();
        try {
            reloadLocked();
        } finally {
            reloadLock.unlock();
        }
        probeDown();
    }

    private void reloadLocked() {
        McpServerConfigLoader.Loaded l = source.get();
        Snapshot old = snap.get();
        Map<String, Entry> entries = new TreeMap<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (McpServerConfig c : l.servers()) {
            names.add(c.name());
            Entry prev = old.entries().get(c.name());
            State st = prev != null ? prev.state() : new State();
            if (!c.enabled()) {
                retry.forget(c.name());
                entries.put(c.name(), new Entry(c, null, null, st));
                continue;
            }
            if (prev != null && prev.error() != null && prev.config().equals(c) && retry.failing(c.name())
                    && (!retry.policy().enabled() || !retry.due().contains(c.name()))) {
                entries.put(c.name(), prev);
                continue;
            }
            if (prev != null && !prev.config().equals(c)) {
                retry.forget(c.name());
            }
            McpClientFactory f = factories.get(c.transport().toLowerCase(java.util.Locale.ROOT));
            if (f == null) {
                String why = "no MCP transport '" + c.transport() + "' is installed (have: " + factories.keySet() + ")";
                retry.failed(c.name(), why, true);
                entries.put(c.name(), new Entry(c, null, why, st));
                continue;
            }
            try {
                entries.put(c.name(), new Entry(c, f.create(c), null, st));
                if (prev == null || prev.error() != null) {
                    retry.succeeded(c.name());                    // it was refused before; a lost connection is cleared by a reconnect, not by a rebuild
                }
            } catch (RuntimeException e) {
                retry.failed(c.name(), String.valueOf(e.getMessage()), com.ash.drishti.common.RetryPolicy.looksPermanent(e));
                entries.put(c.name(), new Entry(c, null, e.getMessage(), st));
            }
        }
        retry.statuses().keySet().stream().filter(k -> !names.contains(k)).forEach(retry::forget);
        snap.set(new Snapshot(Map.copyOf(entries), Map.copyOf(l.errors())));
        old.entries().values().stream().filter(e -> e.client() != null && entries.get(e.config().name()) != e).forEach(e -> e.client().close());
    }

    /** A server whose last call failed is listed as failing; when its retry is due it is closed and connected afresh. */
    private void probeDown() {
        if (!retry.policy().enabled()) {
            return;
        }
        for (Entry e : entries()) {
            String name = e.config().name();
            if (!e.usable()) {
                continue;
            }
            State s = e.state();
            if (s.lastWasFailure) {
                if (!retry.failing(name)) {
                    retry.failed(name, "connection lost: " + s.lastError, com.ash.drishti.common.RetryPolicy.looksPermanent(s.lastError));
                }
                if (retry.due().contains(name)) {
                    try {
                        e.client().close();
                        e.client().connect();
                        s.lastWasFailure = false;
                        s.lastOk = Instant.now();
                        retry.succeeded(name);
                    } catch (RuntimeException ex) {
                        s.lastError = String.valueOf(ex.getMessage());
                        retry.failed(name, "connection lost: " + s.lastError, com.ash.drishti.common.RetryPolicy.looksPermanent(ex));
                    }
                }
            } else if (e.error() == null) {
                retry.succeeded(name);                        // a call went through since: recovered
            }
        }
    }

    public List<Entry> entries() {
        return snap.get().entries().values().stream().sorted(Comparator.comparing(e -> e.config().name())).toList();
    }

    public Map<String, String> fileErrors() {
        return snap.get().fileErrors();
    }

    public Optional<Entry> server(String name) {
        return Optional.ofNullable(snap.get().entries().get(name));
    }

    public void record(Entry e, boolean ok, String error) {
        State s = e.state();
        s.calls.incrementAndGet();
        if (ok) {
            s.lastOk = Instant.now();
            s.lastWasFailure = false;
        } else {
            s.failures.incrementAndGet();
            s.lastFailure = Instant.now();
            s.lastError = error == null ? "" : error;
            s.lastWasFailure = true;
        }
    }

    public Health health(Entry e) {
        State s = e.state();
        if (!e.config().enabled()) {
            return new Health(LlmRegistry.Status.OFF, "enabled: false in the server file", null, null, 0, 0);
        }
        if (e.error() != null) {
            return new Health(LlmRegistry.Status.ERROR, e.error(), null, null, 0, 0);
        }
        LlmRegistry.Status st = s.calls.get() == 0 ? LlmRegistry.Status.UNKNOWN : s.lastWasFailure ? LlmRegistry.Status.DOWN : LlmRegistry.Status.UP;
        return new Health(st, st == LlmRegistry.Status.DOWN ? s.lastError : st == LlmRegistry.Status.UP ? "last call succeeded" : "configured; no call yet",
                s.lastOk, s.lastFailure, s.calls.get(), s.failures.get());
    }

    @Override
    public void close() {
        snap.getAndSet(new Snapshot(Map.of(), Map.of())).entries().values().stream().filter(e -> e.client() != null).forEach(e -> e.client().close());
    }
}
