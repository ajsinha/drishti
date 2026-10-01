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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Routes entity reads to plugins. The configured route for a kind is tried first, then the default route,
 * then any other plugin that serves the kind. Every read runs on a virtual thread with a deadline.
 */
public final class SourceRouter {

    private final SourceRegistry registry;
    private final SourcesProperties props;
    private final ExecutorService executor;

    private final SourceStats stats = new SourceStats();

    /** Reads per connector, for the health page. */
    public SourceStats stats() {
        return stats;
    }

    public SourceRouter(SourceRegistry registry, SourcesProperties props, ExecutorService virtualExecutor) {
        this.registry = registry;
        this.props = props;
        this.executor = virtualExecutor;
    }

    /** Plugins to try for {@code kind}, in order. */
    List<SourcePlugin> candidates(String kind) {
        Map<String, SourcePlugin> ordered = new LinkedHashMap<>();
        for (String name : new String[] {props.routes().get(kind), props.defaultRoute()}) {
            if (name != null) {
                registry.plugin(name).filter(p -> p.manifest().serves(kind)).ifPresent(p -> ordered.put(name, p));
            }
        }
        for (SourcePlugin p : registry.plugins()) {
            if (p.manifest().serves(kind)) {
                ordered.putIfAbsent(p.manifest().name(), p);
            }
        }
        return new ArrayList<>(ordered.values());
    }

    public CompletableFuture<EntityDocument> fetch(EntityRef ref) {
        return fetch(ref, props.fetchTimeout(), AsOf.LATEST);
    }

    public CompletableFuture<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        return fetch(ref, props.fetchTimeout(), asOf);
    }

    public CompletableFuture<EntityDocument> fetch(EntityRef ref, Duration timeout) {
        return fetch(ref, timeout, AsOf.LATEST);
    }

    /**
     * Reads {@code ref} as of {@code asOf}. Dated sources get the date and stamp the business date their document
     * is for; undated ones answer with what they hold and their documents carry no date, so the view can say so.
     */
    public CompletableFuture<EntityDocument> fetch(EntityRef ref, Duration timeout, AsOf asOf) {
        List<SourcePlugin> candidates = candidates(ref.kind());
        if (candidates.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "no source serves kind '" + ref.kind() + "'"));
        }
        if (!asOf.live()) {
            // a picked date: history comes from dated sources first; undated ones only answer what nothing dated holds
            candidates.sort(java.util.Comparator.comparing(p -> !p.manifest().capabilities().dated()));
        } else {
            liveFirst(candidates);
        }
        return CompletableFuture.supplyAsync(() -> readFirst(ref, candidates, asOf), executor)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionallyCompose(e -> CompletableFuture.failedFuture(translate(ref, e)));
    }

    private EntityDocument readFirst(EntityRef ref, List<SourcePlugin> candidates, AsOf asOf) {
        for (SourcePlugin p : candidates) {
            Optional<EntityDocument> d;
            boolean dated = p.manifest().capabilities().dated();
            String name = p.manifest().name();
            long t0 = System.nanoTime();
            try {
                d = dated ? p.fetch(ref, asOf) : p.fetch(ref);
            } catch (Exception e) {
                stats.failed(name, System.nanoTime() - t0, e);
                throw new DrishtiException(ErrorCode.SOURCE_FAILED, name + " failed reading " + ref, e);
            }
            if (d.isPresent()) {
                stats.found(name, System.nanoTime() - t0);
                return d.get();
            }
            stats.notHeld(name, System.nanoTime() - t0);
        }
        throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no source holds " + ref);
    }

    private static Throwable translate(EntityRef ref, Throwable e) {
        Throwable t = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        if (t instanceof TimeoutException) {
            return new DrishtiException(ErrorCode.SOURCE_TIMEOUT, "timed out reading " + ref, t);
        }
        return t;
    }

    /** Reads several entities concurrently; entries that fail are absent from the result. */
    public Map<EntityRef, EntityDocument> fetchAll(Collection<EntityRef> refs, Duration budget) {
        return fetchAll(refs, budget, AsOf.LATEST);
    }

    public Map<EntityRef, EntityDocument> fetchAll(Collection<EntityRef> refs, Duration budget, AsOf asOf) {
        Map<EntityRef, CompletableFuture<EntityDocument>> futures = new LinkedHashMap<>();
        refs.forEach(r -> futures.put(r, fetch(r, budget, asOf)));
        Map<EntityRef, EntityDocument> out = new LinkedHashMap<>();
        futures.forEach((r, f) -> {
            try {
                out.put(r, f.join());
            } catch (CompletionException | DrishtiException ignored) {
                // a missing link must not fail the caller
            }
        });
        return out;
    }

    /** Searches every search-capable plugin in parallel; slow plugins are dropped after {@code budget}. */
    public List<EntityHit> search(String kind, String text, int limit, Duration budget) {
        return search(kind, text, limit, budget, AsOf.LATEST);
    }

    public List<EntityHit> search(String kind, String text, int limit, Duration budget, AsOf asOf) {
        List<CompletableFuture<List<EntityHit>>> parts = new ArrayList<>();
        for (SourcePlugin p : registry.plugins()) {
            if (p.manifest().capabilities().search() && (kind == null || p.manifest().serves(kind))) {
                boolean dated = p.manifest().capabilities().dated();
                parts.add(CompletableFuture.supplyAsync(() -> dated ? p.search(kind, text, limit, asOf) : p.search(kind, text, limit), executor)
                        .completeOnTimeout(List.of(), budget.toMillis(), TimeUnit.MILLISECONDS)
                        .exceptionally(e -> List.of()));
            }
        }
        Map<EntityRef, EntityHit> merged = new LinkedHashMap<>();
        parts.forEach(f -> f.join().forEach(h -> merged.putIfAbsent(h.ref(), h)));
        return merged.values().stream().limit(limit).toList();
    }

    /**
     * Subscribes to live updates from the first live plugin that holds {@code ref}. Returns
     * {@link Subscription#NONE} when no source can push it (the view then stays static).
     */
    public Subscription subscribe(EntityRef ref, java.util.function.Consumer<EntityDocument> listener) {
        List<SourcePlugin> live = new ArrayList<>(candidates(ref.kind()).stream().filter(p -> p.manifest().capabilities().live()).toList());
        liveFirst(live);
        // ticks come from the source the view read: the first live source that holds the entity (a real stream before the
        // demo samples, as in fetch); one that holds nothing yet is used only when none does
        for (SourcePlugin p : live) {
            if (holds(p, ref)) {
                Subscription s = p.subscribe(ref, listener);
                if (s != Subscription.NONE) {
                    return s;
                }
            }
        }
        for (SourcePlugin p : live) {                 // a stream that keeps no state but ticks what a store serves
            if (pushes(p, ref)) {
                Subscription s = p.subscribe(ref, listener);
                if (s != Subscription.NONE) {
                    return s;
                }
            }
        }
        for (SourcePlugin p : live) {
            Subscription s = p.subscribe(ref, listener);
            if (s != Subscription.NONE) {
                return s;
            }
        }
        return Subscription.NONE;
    }

    /** Live: sources that stream first, so the view ticks; among them a real stream (Kafka) before the default route. */
    private void liveFirst(List<SourcePlugin> candidates) {
        String fallback = props.defaultRoute();
        candidates.sort(java.util.Comparator.<SourcePlugin, Boolean>comparing(p -> !p.manifest().capabilities().live())
                .thenComparing(p -> p.manifest().capabilities().live() && p.manifest().name().equals(fallback)));
    }

    /**
     * True when some live source pushes updates for {@code ref} although another answers its reads (a Kafka connector
     * in ticks mode behind a lake): the view is live even though the document that answered is not.
     */
    public boolean pushes(EntityRef ref) {
        return candidates(ref.kind()).stream().anyMatch(p -> p.manifest().capabilities().live() && pushes(p, ref));
    }

    private static boolean pushes(SourcePlugin p, EntityRef ref) {
        try {
            return p.pushes(ref);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean holds(SourcePlugin p, EntityRef ref) {
        try {
            return p.fetch(ref).isPresent();          // live sources answer from memory or their own store
        } catch (Exception e) {
            return false;
        }
    }

    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        List<EntityRef> out = new ArrayList<>();
        for (SourcePlugin p : registry.plugins()) {
            if (p.manifest().capabilities().reverseLookup()) {
                (p.manifest().capabilities().dated() ? p.reverse(target, kind, asOf) : p.reverse(target, kind)).stream().filter(r -> !out.contains(r)).forEach(out::add);
            }
        }
        return out;
    }
}
