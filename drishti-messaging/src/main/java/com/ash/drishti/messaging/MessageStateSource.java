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
package com.ash.drishti.messaging;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.diskcache.DiskCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The shared half of a message-queue connector (ActiveMQ, RabbitMQ). A queue delivers each message once and keeps no
 * history, so the connector keeps the latest document of every entity itself: on local disk (a persistent RocksDB
 * store that survives restarts, one per connector, never cleared unless configured) with the most recent ones in a
 * size-bounded memory cache. Every change is pushed to live subscribers. Subclasses only connect, receive and
 * acknowledge; they hand each message to {@link #accept}.
 *
 * <p>Messages: with a kind configured for the destination ({@code kind.<destination>} or {@code kind}), the body is
 * the document and the id is the {@code id} header or the document's id field ({@code id-field.<destination>},
 * default {@code id}). Otherwise the body is an envelope {@code {"kind", "id", "doc"}}. An empty body, a
 * {@code "doc": null} envelope, or a {@code deleted} header removes the entity. Thread-safe.
 */
public abstract class MessageStateSource implements SourcePlugin {

    /**
     * One received message, broker-neutral.
     *
     * @param destination the queue or topic it came from
     * @param id the id header, if the producer set one
     * @param body the body text (null or blank for a delete)
     * @param deleted the producer marked it a delete
     */
    public record Inbound(String destination, String id, String body, boolean deleted) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    protected SourceContext context;
    protected String sourceName;
    private final Set<String> kinds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> kindOf = new ConcurrentHashMap<>();
    private final Map<String, String> idFieldOf = new ConcurrentHashMap<>();
    private String defaultKind;
    private String defaultIdField = "id";
    private DiskCache store;
    private Cache<EntityRef, EntityDocument> memory;
    private final Map<EntityRef, Integer> weights = new ConcurrentHashMap<>();
    private final Map<EntityRef, List<Consumer<EntityDocument>>> listeners = new ConcurrentHashMap<>();
    private final Set<EntityRef> known = ConcurrentHashMap.newKeySet();
    private final HitIndex index = new HitIndex();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    protected final AtomicReference<String> health = new AtomicReference<>("DOWN: not started");

    /** The plugin's own name ({@code activemq}, {@code rabbitmq}): the default source name and the state folder. */
    protected abstract String plugin();

    /** Connect, subscribe, and keep reconnecting until {@link #close} (typically on a virtual thread). */
    protected abstract void connect() throws Exception;

    @Override
    public PluginManifest manifest() {
        Set<String> k = new HashSet<>(kinds);
        k.addAll(kindOf.values());
        if (defaultKind != null) {
            k.add(defaultKind);
        }
        return new PluginManifest(sourceName == null ? plugin() : sourceName, "1.0", k, new SourceCapabilities(true, false, true, false));
    }

    @Override
    public final void start(SourceContext ctx) throws Exception {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", plugin());
        this.defaultKind = ctx.setting("kind", null);
        this.defaultIdField = ctx.setting("id-field", "id");
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("kind.")) {
                kindOf.put(k.substring(5), v);
            } else if (k.startsWith("id-field.")) {
                idFieldOf.put(k.substring(9), v);
            }
        });
        long cacheMb = Long.parseLong(ctx.setting("cache-mb", "128"));
        this.memory = Caffeine.newBuilder().maximumWeight(cacheMb * 1024 * 1024)
                .weigher((EntityRef r, EntityDocument d) -> weights.getOrDefault(r, 1024)).build();
        String dir = ctx.setting("state.dir", Path.of(ctx.setting("state.root", "./data/state"), sourceName).toString());
        String reset = ctx.setting("state.reset-at", "never");
        this.store = new DiskCache(Path.of(dir), (long) (Double.parseDouble(ctx.setting("state.max-gb", "10")) * 1024 * 1024 * 1024),
                "never".equals(reset) ? null : LocalTime.parse(reset), ZoneId.of(ctx.setting("state.zone", "America/New_York")),
                ctx.scheduler(), Clock.systemUTC(), true);
        store.forEach((key, value) -> {                   // what earlier runs received: the queue will not send it again
            String[] kv = key.split("/", 2);
            if (kv.length == 2) {
                EntityRef ref = EntityRef.of(kv[0], kv[1]);
                known.add(ref);
                kinds.add(ref.kind());
            }
        });
        rebuildIndex();
        connect();
    }

    /** Applies one message: stores the entity's latest document (or removes it) and pushes it to live views. */
    protected final void accept(Inbound m) {
        received.incrementAndGet();
        try {
            String kind = kindOf.getOrDefault(m.destination(), defaultKind);
            String id;
            JsonNode doc;
            boolean blank = m.body() == null || m.body().isBlank();
            if (kind != null) {
                doc = blank ? null : JSON.readTree(m.body());
                id = m.id() != null && !m.id().isBlank() ? m.id() : doc == null ? null : doc.path(idFieldOf.getOrDefault(m.destination(), defaultIdField)).asText(null);
            } else {
                JsonNode env = blank ? null : JSON.readTree(m.body());
                if (env == null) {
                    rejected.incrementAndGet();
                    return;
                }
                kind = env.path("kind").asText(null);
                id = env.path("id").asText(m.id());
                doc = env.get("doc");
            }
            if (kind == null || id == null || id.isBlank()) {
                rejected.incrementAndGet();
                return;
            }
            EntityRef ref = EntityRef.of(kind, id);
            if (m.deleted() || doc == null || doc.isNull()) {
                store.delete(key(ref));
                memory.invalidate(ref);
                if (known.remove(ref)) {
                    rebuildIndex();
                }
                return;
            }
            byte[] bytes = JSON.writeValueAsBytes(doc);
            store.put(key(ref), bytes);
            kinds.add(kind);
            EntityDocument d = document(ref, bytes);
            weights.put(ref, bytes.length * 2 + 64);
            memory.put(ref, d);
            if (known.add(ref)) {
                index.add(new EntityHit(ref, ref.id(), ref.kind() + " · " + sourceName));
            }
            List<Consumer<EntityDocument>> subs = listeners.get(ref);
            if (subs != null) {
                subs.forEach(l -> {
                    try {
                        l.accept(d);
                    } catch (RuntimeException ignored) {
                        // one broken view must not stop the others
                    }
                });
            }
        } catch (Exception e) {
            rejected.incrementAndGet();                   // not JSON, or not a document: skipped, counted
        }
    }

    private EntityDocument document(EntityRef ref, byte[] bytes) throws java.io.IOException {
        DataNode data = context.parseJson(new ByteArrayInputStream(bytes));
        return new EntityDocument(ref, data, new Provenance(sourceName, generation.incrementAndGet(), Instant.now(), true));
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        EntityDocument hit = memory.getIfPresent(ref);
        if (hit != null) {
            return Optional.of(hit);
        }
        byte[] bytes = store.get(key(ref));
        if (bytes == null) {
            return Optional.empty();
        }
        EntityDocument d = document(ref, bytes);
        weights.put(ref, bytes.length * 2 + 64);
        memory.put(ref, d);
        return Optional.of(d);
    }

    @Override
    public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        listeners.compute(ref, (r, subs) -> {
            List<Consumer<EntityDocument>> l = subs == null ? new CopyOnWriteArrayList<>() : subs;
            l.add(listener);
            return l;
        });
        return () -> listeners.computeIfPresent(ref, (r, subs) -> {
            subs.remove(listener);
            return subs.isEmpty() ? null : subs;
        });
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    private void rebuildIndex() {
        index.replaceAll(known.stream().map(r -> new EntityHit(r, r.id(), r.kind() + " · " + sourceName)).toList());
    }

    @Override
    public Map<String, Object> cacheStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entities", known.size());
        out.put("memoryEntries", memory.estimatedSize());
        out.put("stateMb", Math.round(store.sizeOnDisk() / 1048576.0 * 10) / 10.0);
        out.put("received", received.get());
        out.put("rejected", rejected.get());
        return out;
    }

    /** Drops the memory cache only: the state on disk is the source of truth (a queue cannot send it again). */
    @Override
    public void purgeCaches() {
        memory.invalidateAll();
    }

    @Override
    public String health() {
        return health.get();
    }

    /** Closes the state store; subclasses close their connection first, then call this. */
    @Override
    public void close() {
        if (store != null) {
            store.close();
        }
    }

    private static String key(EntityRef ref) {
        return ref.kind() + "/" + ref.id();
    }

    /** Waits before a reconnect; false when interrupted (closing). */
    protected static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
