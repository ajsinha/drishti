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

    public McpRegistry(List<McpClientFactory> factories, Supplier<McpServerConfigLoader.Loaded> source) {
        factories.forEach(f -> this.factories.putIfAbsent(f.transport().toLowerCase(java.util.Locale.ROOT), f));
        this.source = source;
    }

    public static List<McpClientFactory> discover(List<McpClientFactory> extra) {
        List<McpClientFactory> all = new java.util.ArrayList<>(extra);
        java.util.ServiceLoader.load(McpClientFactory.class).forEach(all::add);
        return all;
    }

    public synchronized void reload() {
        McpServerConfigLoader.Loaded l = source.get();
        Snapshot old = snap.get();
        Map<String, Entry> entries = new TreeMap<>();
        for (McpServerConfig c : l.servers()) {
            State st = old.entries().containsKey(c.name()) ? old.entries().get(c.name()).state() : new State();
            if (!c.enabled()) {
                entries.put(c.name(), new Entry(c, null, null, st));
                continue;
            }
            McpClientFactory f = factories.get(c.transport().toLowerCase(java.util.Locale.ROOT));
            if (f == null) {
                entries.put(c.name(), new Entry(c, null, "no MCP transport '" + c.transport() + "' is installed (have: " + factories.keySet() + ")", st));
                continue;
            }
            try {
                entries.put(c.name(), new Entry(c, f.create(c), null, st));
            } catch (RuntimeException e) {
                LOG.warn("mcp server {} not loaded: {}", c.name(), e.getMessage());
                entries.put(c.name(), new Entry(c, null, e.getMessage(), st));
            }
        }
        snap.set(new Snapshot(Map.copyOf(entries), Map.copyOf(l.errors())));
        old.entries().values().stream().filter(e -> e.client() != null).forEach(e -> e.client().close());
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
