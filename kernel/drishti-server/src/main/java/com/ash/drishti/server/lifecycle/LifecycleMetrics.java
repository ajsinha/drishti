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
package com.ash.drishti.server.lifecycle;

import com.ash.drishti.api.lifecycle.ComponentHealth;
import com.ash.drishti.api.lifecycle.ComponentStats;
import com.ash.drishti.api.lifecycle.Lifecycle;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * The lifecycle on {@code /actuator/prometheus} (docs/architecture/LIFECYCLE.md section 7), one series per part (tags {@code type},
 * {@code name}; bounded by the parts the node has):
 * <ul>
 *   <li>{@code drishti.lifecycle.state}: 0 new, 1 initialized, 2 running, 3 suspended, 4 failed, 5 shut down;</li>
 *   <li>{@code drishti.lifecycle.health}: 0 up, 1 degraded, 2 suspended, 3 down;</li>
 *   <li>{@code drishti.lifecycle.requests}, {@code .errors}, {@code .refusals}, {@code .rows.read}, {@code .rows.written}, {@code .bytes},
 *       {@code .cache.hits} (counters since the part started);</li>
 *   <li>{@code drishti.lifecycle.latency.p50|p95|p99} (milliseconds over the recent window), {@code drishti.lifecycle.queue.depth},
 *       {@code drishti.lifecycle.in.flight}.</li>
 * </ul>
 * Every value is read from the part's own snapshot when scraped; a part is registered the first time it is seen.
 */
final class LifecycleMetrics {

    private final MeterRegistry registry;
    private final LifecycleService service;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    /** The last snapshot per part, so one scrape reads each part once (the gauges of a part share it for a second). */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, ComponentStats stats, ComponentHealth health) {}

    LifecycleMetrics(MeterRegistry registry, LifecycleService service) {
        this.registry = registry;
        this.service = service;
    }

    /** Registers the parts not seen yet. */
    void refresh() {
        service.names().forEach((type, names) -> names.forEach(n -> ensure(type, n)));
    }

    private void ensure(Lifecycle.Type type, String name) {
        String key = type.word() + "/" + name;
        if (!seen.add(key)) {
            return;
        }
        Tags tags = Tags.of("type", type.word(), "name", name);
        LifecycleMetrics self = this;
        Gauge.builder("drishti.lifecycle.state", self, m -> m.service.part(type, name).state().ordinal()).tags(tags)
                .description("the part's lifecycle state: 0 new, 1 initialized, 2 running, 3 suspended, 4 failed, 5 shut down").register(registry);
        Gauge.builder("drishti.lifecycle.health", self, m -> m.snap(type, name).health().status().ordinal()).tags(tags)
                .description("the part's health: 0 up, 1 degraded, 2 suspended, 3 down").register(registry);
        counter("drishti.lifecycle.requests", tags, s -> s.total().requests(), type, name);
        counter("drishti.lifecycle.errors", tags, s -> s.total().errors(), type, name);
        counter("drishti.lifecycle.refusals", tags, s -> s.total().refusals(), type, name);
        counter("drishti.lifecycle.rows.read", tags, s -> s.total().rowsRead(), type, name);
        counter("drishti.lifecycle.rows.written", tags, s -> s.total().rowsWritten(), type, name);
        counter("drishti.lifecycle.bytes", tags, s -> s.total().bytes(), type, name);
        counter("drishti.lifecycle.cache.hits", tags, s -> s.total().cacheHits(), type, name);
        gauge("drishti.lifecycle.latency.p50", tags, s -> s.latency().p50(), type, name);
        gauge("drishti.lifecycle.latency.p95", tags, s -> s.latency().p95(), type, name);
        gauge("drishti.lifecycle.latency.p99", tags, s -> s.latency().p99(), type, name);
        gauge("drishti.lifecycle.queue.depth", tags, s -> s.queueDepth(), type, name);
        gauge("drishti.lifecycle.in.flight", tags, s -> s.inFlight(), type, name);
    }

    private void counter(String metric, Tags tags, ToDoubleFunction<ComponentStats> f, Lifecycle.Type type, String name) {
        FunctionCounter.builder(metric, this, m -> f.applyAsDouble(m.snap(type, name).stats())).tags(tags).register(registry);
    }

    private void gauge(String metric, Tags tags, ToDoubleFunction<ComponentStats> f, Lifecycle.Type type, String name) {
        Gauge.builder(metric, this, m -> f.applyAsDouble(m.snap(type, name).stats())).tags(tags).register(registry);
    }

    private Cached snap(Lifecycle.Type type, String name) {
        String key = type.word() + "/" + name;
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c == null || now - c.at() > 1_000) {
            Lifecycle l = service.part(type, name);
            c = new Cached(now, l.stats(), l.health());
            cache.put(key, c);
        }
        return c;
    }
}
