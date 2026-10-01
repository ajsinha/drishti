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
package com.ash.drishti.engine.source.derived;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityReader;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Derived kinds (plugin {@code derived}): entities computed from other kinds, read through the server's routing, such as
 * a book's P&amp;L from its trades or a currency's exposure. A pack declares them as a connector (see {@link DerivedKind}
 * for the settings). Each business date is computed once per {@code refresh} (default 30 s) and shared by every
 * reader; a picked date is computed from that date's members, so derived kinds have history wherever their members do.
 */
public final class DerivedSourcePlugin implements SourcePlugin {

    /** One computation: every group of every derived kind, for one business date. */
    private record Snapshot(Map<String, Map<String, DataNode>> byKind, Map<String, Integer> scanned, LocalDate dataDate, long generation,
            Instant at) {}

    private static final int CACHED_DATES = 16;
    private List<DerivedKind> kinds = List.of();
    private EntityReader reader;
    private Duration refresh = Duration.ofSeconds(30);
    private int maxScan = 50_000;
    private String sourceName = "derived";
    private final AtomicLong generation = new AtomicLong();
    private final Map<String, CompletableFuture<Snapshot>> cache = new LinkedHashMap<>(CACHED_DATES, 0.75f, true);
    private volatile String health = "UP (nothing computed yet)";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("derived", "1.0", kinds.stream().map(DerivedKind::kind).collect(Collectors.toSet()),
                new SourceCapabilities(false, false, true, true));
    }

    @Override
    public void start(SourceContext context) {
        kinds = DerivedKind.parse(context.settings(), new ElCompiler(), Formats.defaults());
        if (kinds.isEmpty()) {
            throw new PluginNotConfigured("derived needs at least one <kind>.from setting");
        }
        reader = context.reader();
        refresh = org.springframework.boot.convert.DurationStyle.detectAndParse(context.setting("refresh", "30s"));
        maxScan = Integer.parseInt(context.setting("max-scan", "50000"));
        sourceName = context.setting("source-name", "derived");
        // the first computation after start, so the first reader of a large book finds it ready
        if (context.scheduler() == null) {
            return;
        }
        context.scheduler().schedule(() -> {
            try {
                snapshot(AsOf.LATEST);
            } catch (RuntimeException e) {
                // the first reader computes it
            }
        }, 15, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        Snapshot s = snapshot(asOf);
        DataNode doc = s.byKind().getOrDefault(ref.kind(), Map.of()).get(ref.id());
        return doc == null ? Optional.empty()
                : Optional.of(new EntityDocument(ref, doc, new Provenance(sourceName, s.generation(), s.at(), false, s.dataDate())));
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return search(kind, text, limit, AsOf.LATEST);
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        DerivedKind k = kinds.stream().filter(d -> d.kind().equals(kind)).findFirst().orElse(null);
        if (k == null) {
            return List.of();
        }
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        Map<String, DataNode> groups = snapshot(asOf).byKind().getOrDefault(kind, Map.of());
        return groups.keySet().stream().sorted().filter(id -> id.toLowerCase(Locale.ROOT).contains(t)).limit(limit)
                .map(id -> new EntityHit(EntityRef.of(kind, id), id, kind + " · from " + k.from())).toList();
    }

    @Override
    public Map<String, Object> cacheStats() {
        synchronized (cache) {
            return Map.of("dates", cache.size(), "kinds", kinds.size());
        }
    }

    @Override
    public void purgeCaches() {
        synchronized (cache) {
            cache.clear();
        }
    }

    @Override
    public String health() {
        return health;
    }

    /** The computation for this date: shared while it runs, reused until it is {@code refresh} old. */
    private Snapshot snapshot(AsOf asOf) {
        String key = asOf.live() ? "live" : asOf.businessDate() + "@" + asOf.knownAt();
        CompletableFuture<Snapshot> f;
        boolean mine = false;
        synchronized (cache) {
            f = cache.get(key);
            boolean stale = f != null && f.isDone() && !f.isCompletedExceptionally()
                    && f.join().at().plus(refresh).isBefore(Instant.now());
            if (stale) {
                // serve the last computation now and refresh it behind: a reader never waits for a recomputation
                Snapshot last = f.join();
                CompletableFuture<Snapshot> next = new CompletableFuture<>();
                cache.put(key, CompletableFuture.completedFuture(new Snapshot(last.byKind(), last.scanned(), last.dataDate(), last.generation(),
                        Instant.now())));                                  // one refresh at a time
                Thread.ofVirtual().name("derived-refresh").start(() -> {
                    try {
                        next.complete(compute(asOf));
                        synchronized (cache) {
                            cache.put(key, next);
                        }
                    } catch (RuntimeException e) {
                        health = "DOWN: " + e.getMessage();
                    }
                });
                return last;
            }
            if (f == null || f.isCompletedExceptionally()) {
                f = new CompletableFuture<>();
                cache.put(key, f);
                mine = true;
                while (cache.size() > CACHED_DATES) {
                    cache.remove(cache.keySet().iterator().next());
                }
            }
        }
        if (mine) {
            try {
                f.complete(compute(asOf));
            } catch (RuntimeException e) {
                health = "DOWN: " + e.getMessage();
                f.completeExceptionally(e);
            }
        }
        try {
            return f.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : e;
        }
    }

    private Snapshot compute(AsOf asOf) {
        Map<String, Map<String, DataNode>> byKind = new HashMap<>();
        Map<String, Integer> scanned = new HashMap<>();
        LocalDate dataDate = null;
        Map<String, Map<EntityRef, EntityDocument>> read = new HashMap<>();   // one read per source kind, shared by the derived kinds on it
        for (DerivedKind k : kinds) {
            Optional<java.util.Set<String>> paths = k.paths();
            Optional<com.ash.drishti.api.ColumnSet> cols = paths.isEmpty() ? Optional.empty() : reader.columns(k.from(), paths.get(), asOf);
            if (cols.isPresent()) {                       // every member, from columns: no document is read
                com.ash.drishti.api.ColumnSet c = cols.get();
                byKind.put(k.kind(), k.buildAll(c, k.groupBySource()));
                scanned.put(k.kind(), c.size());
                if (c.businessDate() != null && (dataDate == null || c.businessDate().isAfter(dataDate))) {
                    dataDate = c.businessDate();
                }
                continue;
            }
            Map<EntityRef, EntityDocument> members = read.computeIfAbsent(k.from(), from -> reader.read(reader.list(from, asOf, maxScan), asOf));
            Map<String, Map<String, DataNode>> groups = new HashMap<>();
            for (EntityDocument d : members.values()) {
                String g = k.keyOf(d.data());
                if (g != null) {
                    groups.computeIfAbsent(g, x -> new HashMap<>()).put(d.ref().id(), d.data());
                }
                LocalDate dd = d.provenance().businessDate();
                if (dd != null && (dataDate == null || dd.isAfter(dataDate))) {
                    dataDate = dd;
                }
            }
            Map<String, DataNode> built = new HashMap<>();
            groups.forEach((g, m) -> built.put(g, k.build(g, m)));
            byKind.put(k.kind(), built);
            scanned.put(k.kind(), members.size());
        }
        health = "UP";
        return new Snapshot(byKind, scanned, asOf.live() ? null : dataDate, generation.incrementAndGet(), Instant.now());
    }
}
