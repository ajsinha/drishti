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
import java.util.ArrayList;
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

/**
 * The providers and models by logical name, and how each is doing. {@link #reload()} reads the source again, builds the new
 * providers through their {@link ProviderFactory} and swaps them in one step; calls in flight finish on the provider they
 * started on. A file that fails is listed with its reason and the rest are unaffected.
 */
public final class LlmRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LlmRegistry.class);

    /** Provider health as the registry knows it: from configuration and from the calls it has seen (no probe is sent). */
    public enum Status { OFF, ERROR, UNKNOWN, UP, DOWN }

    public record Health(Status status, String detail, Instant lastOk, Instant lastFailure, long calls, long failures) {}

    /** A provider in the registry. */
    public record Entry(ProviderConfig config, LlmProvider provider, String error, State state) {
        public boolean usable() {
            return provider != null && config.enabled();
        }
    }

    /** Counters the registry keeps per provider. */
    public static final class State {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        volatile Instant lastOk;
        volatile Instant lastFailure;
        volatile String lastError = "";
        volatile boolean lastWasFailure;
    }

    /** A chat model picked by name. */
    public record Resolved(Entry entry, LlmModel model) {
        public ModelInfo info() {
            return model.info();
        }
    }

    private record Snapshot(Map<String, Entry> entries, Map<String, String> fileErrors, Instant loadedAt) {}

    private final Supplier<ProviderConfigLoader.Loaded> source;
    private final Map<String, ProviderFactory> factories = new LinkedHashMap<>();
    private final String defaultModel;
    private final AtomicReference<Snapshot> snap = new AtomicReference<>(new Snapshot(Map.of(), Map.of(), Instant.EPOCH));

    public LlmRegistry(List<ProviderFactory> factories, Supplier<ProviderConfigLoader.Loaded> source, String defaultModel) {
        for (ProviderFactory f : factories) {
            this.factories.putIfAbsent(f.type().toLowerCase(java.util.Locale.ROOT), f);
        }
        this.source = source;
        this.defaultModel = defaultModel == null ? "" : defaultModel.strip();
    }

    public synchronized void reload() {
        ProviderConfigLoader.Loaded loaded = source.get();
        Map<String, Entry> entries = new TreeMap<>();
        Map<String, String> errors = new TreeMap<>(loaded.errors());
        Snapshot old = snap.get();
        for (ProviderConfig c : loaded.providers()) {
            State state = old.entries().containsKey(c.name()) ? old.entries().get(c.name()).state() : new State();
            if (!c.enabled()) {
                entries.put(c.name(), new Entry(c, null, null, state));
                continue;
            }
            ProviderFactory f = factories.get(c.type().toLowerCase(java.util.Locale.ROOT));
            if (f == null) {
                entries.put(c.name(), new Entry(c, null, "no provider type '" + c.type() + "' is installed (have: " + factories.keySet() + ")", state));
                continue;
            }
            try {
                entries.put(c.name(), new Entry(c, f.create(c), null, state));
            } catch (RuntimeException e) {
                LOG.warn("ai provider {} not loaded: {}", c.name(), e.getMessage());
                entries.put(c.name(), new Entry(c, null, e.getMessage(), state));
            }
        }
        snap.set(new Snapshot(Map.copyOf(entries), Map.copyOf(errors), Instant.now()));
        for (Entry e : old.entries().values()) {
            if (e.provider() != null) {
                e.provider().close();
            }
        }
        LOG.info("ai registry: {} provider(s), {} usable, {} file error(s)", entries.size(), entries.values().stream().filter(Entry::usable).count(), errors.size());
    }

    public List<String> types() {
        return List.copyOf(factories.keySet());
    }

    public List<Entry> entries() {
        return snap.get().entries().values().stream().sorted(java.util.Comparator.comparing(e -> e.config().name())).toList();
    }

    public Map<String, String> fileErrors() {
        return snap.get().fileErrors();
    }

    public Instant loadedAt() {
        return snap.get().loadedAt();
    }

    public Optional<Entry> provider(String name) {
        return Optional.ofNullable(snap.get().entries().get(name));
    }

    /** Every model of every usable provider. */
    public List<ModelInfo> models() {
        List<ModelInfo> out = new ArrayList<>();
        for (Entry e : entries()) {
            if (e.usable()) {
                out.addAll(e.provider().models());
            }
        }
        return out;
    }

    /**
     * The chat model for a logical reference: {@code provider/model}, a model name that exactly one provider has, or (null/blank)
     * the registry default, then the only chat model there is.
     */
    public Resolved chat(String ref) {
        String r = ref == null || ref.isBlank() ? defaultModel : ref.strip();
        List<Resolved> hits = new ArrayList<>();
        if (r.isEmpty()) {
            for (Entry e : entries()) {
                if (e.usable()) {
                    String d = e.config().defaultModel();
                    for (ModelInfo m : e.provider().models()) {
                        if (!m.embedding() && (d.isEmpty() ? true : d.equals(m.name()))) {
                            e.provider().chatModel(m.name()).ifPresent(x -> hits.add(new Resolved(e, x)));
                        }
                    }
                }
            }
            if (hits.size() == 1) {
                return hits.get(0);
            }
            throw new LlmException(LlmException.Kind.NOT_FOUND, hits.isEmpty() ? "no chat model is configured"
                    : "several chat models are configured; name one (provider/model) or set a default-model");
        }
        int slash = r.indexOf('/');
        for (Entry e : entries()) {
            if (!e.usable() || slash >= 0 && !e.config().name().equals(r.substring(0, slash))) {
                continue;
            }
            String model = slash >= 0 ? r.substring(slash + 1) : r;
            e.provider().chatModel(model).ifPresent(x -> hits.add(new Resolved(e, x)));
        }
        if (hits.isEmpty()) {
            throw new LlmException(LlmException.Kind.NOT_FOUND, "no chat model '" + r + "' is configured");
        }
        if (hits.size() > 1) {
            throw new LlmException(LlmException.Kind.NOT_FOUND, "model name '" + r + "' is in several providers; use provider/model");
        }
        return hits.get(0);
    }

    public Optional<EmbeddingModel> embedding(String ref) {
        int slash = ref == null ? -1 : ref.indexOf('/');
        for (Entry e : entries()) {
            if (e.usable() && (slash < 0 || e.config().name().equals(ref.substring(0, slash)))) {
                Optional<EmbeddingModel> m = e.provider().embeddingModel(slash < 0 ? ref : ref.substring(slash + 1));
                if (m.isPresent()) {
                    return m;
                }
            }
        }
        return Optional.empty();
    }

    /** Called by the gateway after each call so health reflects use. */
    public void record(Entry entry, boolean ok, String error) {
        State s = entry.state();
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
            return new Health(Status.OFF, "enabled: false in the provider file", null, null, 0, 0);
        }
        if (e.error() != null) {
            return new Health(Status.ERROR, e.error(), null, null, 0, 0);
        }
        Status st = s.calls.get() == 0 ? Status.UNKNOWN : s.lastWasFailure ? Status.DOWN : Status.UP;
        String detail = st == Status.UNKNOWN ? "configured; no call yet" : st == Status.DOWN ? s.lastError : "last call succeeded";
        return new Health(st, detail, s.lastOk, s.lastFailure, s.calls.get(), s.failures.get());
    }

    @Override
    public void close() {
        for (Entry e : snap.getAndSet(new Snapshot(Map.of(), Map.of(), Instant.EPOCH)).entries().values()) {
            if (e.provider() != null) {
                e.provider().close();
            }
        }
    }
}
