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
package com.ash.drishti.plugin.demo;

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
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Serves sample entities from pack sample directories ({@code dirs}: comma-separated, each holding
 * {@code catalog.json} and {@code <kind>/<id>.json}). The enabled packs supply the directories. Each fixture's
 * {@code _meta} block becomes the provenance, so views show the source systems of the samples
 * ({@code aero-risk}, {@code port-ops}, ...).
 *
 * <p>Documents marked live tick while someone subscribes: every {@code tick-ms} (default 400) one
 * random-walk step moves prices, rates and MTM, and the generation increases. Setting {@code ticking: false}
 * freezes them (used by golden tests).
 */
public final class DemoSourcePlugin implements SourcePlugin {

    static final String NAME = "demo";

    private final Map<EntityRef, EntityDocument> documents = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private final Map<EntityRef, List<Consumer<EntityDocument>>> listeners = new ConcurrentHashMap<>();
    private final DemoTicker ticker = new DemoTicker(42);
    private final Map<EntityRef, DataNode> walks = new ConcurrentHashMap<>();
    private ScheduledFuture<?> tickTask;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(NAME, "1.0", Set.of(), new SourceCapabilities(true, true, true));
    }

    @Override
    public void start(SourceContext context) throws IOException {
        for (String dir : context.setting("dirs", "").split(",")) {
            if (dir.isBlank()) {
                continue;
            }
            java.nio.file.Path root = java.nio.file.Path.of(dir.trim());
            DataNode catalog = read(context, root.resolve("catalog.json"));
            for (int i = 0; i < catalog.size(); i++) {
                DataNode e = catalog.get(i);
                EntityRef ref = EntityRef.of(e.get("kind").asText(), e.get("id").asText());
                DataNode raw = read(context, root.resolve(ref.kind()).resolve(ref.id() + ".json"));
                documents.put(ref, toDocument(ref, raw));
                walks.put(ref, raw.get("_meta").get("walk"));
                index.add(new EntityHit(ref, e.get("title").asText(), e.get("subtitle").asText()));
            }
        }
        if (Boolean.parseBoolean(context.setting("ticking", "true"))) {
            long ms = Long.parseLong(context.setting("tick-ms", "400"));
            tickTask = context.scheduler().scheduleAtFixedRate(this::tick, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    /** One step for every subscribed live document; runs on the single scheduler thread. */
    void tick() {
        listeners.forEach((ref, subs) -> {
            if (subs.isEmpty()) {
                return;
            }
            EntityDocument d = documents.get(ref);
            if (d == null || !d.provenance().live()) {
                return;
            }
            EntityDocument next = new EntityDocument(ref, ticker.tick(ref.kind(), d.data(), walks.get(ref)), new Provenance(
                    d.provenance().source(), d.provenance().generation() + 1, Instant.now(), true));
            documents.put(ref, next);
            for (Consumer<EntityDocument> l : subs) {
                try {
                    l.accept(next);
                } catch (RuntimeException ignored) {
                    // a failing listener must not stop the ticker
                }
            }
        });
    }

    @Override
    public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        EntityDocument d = documents.get(ref);
        if (d == null || !d.provenance().live()) {
            return Subscription.NONE;
        }
        listeners.compute(ref, (r, subs) -> {
            List<Consumer<EntityDocument>> l = subs == null ? new CopyOnWriteArrayList<>() : subs;
            l.add(listener);
            return l;
        });
        // the entry goes with its last listener, so the ticker stops walking entities nobody watches
        return () -> listeners.computeIfPresent(ref, (r, subs) -> {
            subs.remove(listener);
            return subs.isEmpty() ? null : subs;
        });
    }

    @Override
    public void close() {
        if (tickTask != null) {
            tickTask.cancel(false);
        }
    }

    private static DataNode read(SourceContext context, java.nio.file.Path file) throws IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            return context.parseJson(in);
        }
    }

    static EntityDocument toDocument(EntityRef ref, DataNode raw) {
        DataNode meta = raw.get("_meta");
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) raw).fields());
        fields.remove("_meta");
        Provenance p = new Provenance(meta.get("source").asText(), (long) meta.get("generation").asDouble(),
                Instant.now(), meta.get("live").asBoolean());
        return new EntityDocument(ref, new DataNode.Obj(fields), p);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        EntityDocument d = documents.get(ref);
        return d == null ? Optional.empty()
                : Optional.of(new EntityDocument(ref, d.data(), new Provenance(d.provenance().source(),
                        d.provenance().generation(), Instant.now(), d.provenance().live())));
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        List<EntityRef> out = new ArrayList<>();
        documents.forEach((ref, doc) -> {
            if (ref.kind().equals(kind) && references(doc.data(), target.id())) {
                out.add(ref);
            }
        });
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    private static boolean references(DataNode node, String id) {
        if (node instanceof DataNode.Obj o) {
            for (DataNode v : o.fields().values()) {
                if (v instanceof DataNode.Val val && id.equals(val.asText())) {
                    return true;
                }
                if (v instanceof DataNode.Arr arr) {
                    for (DataNode e : arr.elements()) {
                        if (e instanceof DataNode.Val val && id.equals(val.asText())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    int size() {
        return documents.size();
    }
}
