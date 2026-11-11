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
package com.ash.drishti.server.api;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.pool.PoolStats;
import com.ash.drishti.engine.source.SourceRegistry;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Prometheus metrics for the database pools, one series per connector (label {@code connector}), refreshed every few seconds while
 * connectors come and go: {@code drishti_db_pool_max}, {@code _open}, {@code _in_use}, {@code _idle}, {@code _waiting},
 * {@code _longest_borrow_seconds} (not reported where the pool cannot tell), {@code _leaks} and {@code _timeouts} (counted since start).
 * The identity database is the connector {@code identity}.
 */
@Component
public class PoolMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(PoolMetrics.class);

    private record Series(String name, MultiGauge gauge, ToDoubleFunction<PoolStats> value) {}

    private final SourceRegistry registry;
    private final ObjectProvider<HikariDataSource> identity;
    private final boolean sqlite;
    private final List<Series> series = new ArrayList<>();
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "drishti-pool-metrics");
        t.setDaemon(true);
        return t;
    });

    public PoolMetrics(MeterRegistry meters, SourceRegistry registry, ObjectProvider<HikariDataSource> identity,
            com.ash.drishti.identity.IdentityProperties identityProperties) {
        this.registry = registry;
        this.identity = identity;
        this.sqlite = identityProperties.sqlite();
        add(meters, "max", "Most connections the pool opens at once", p -> p.max());
        add(meters, "open", "Connections open now", p -> p.open());
        add(meters, "in_use", "Connections borrowed now", p -> p.inUse());
        add(meters, "idle", "Connections open and waiting", p -> p.idle());
        add(meters, "waiting", "Callers waiting for a connection", p -> p.waiting());
        add(meters, "longest_borrow_seconds", "How long the longest current borrow has lasted", p -> p.longestBorrowMillis() / 1000.0);
        add(meters, "leaks", "Borrows that passed the leak threshold since start", p -> p.leaksDetected());
        add(meters, "timeouts", "Callers that gave up waiting for a connection since start", p -> p.timeouts());
        refresher.scheduleWithFixedDelay(this::refresh, 2, 5, TimeUnit.SECONDS);
    }

    private void add(MeterRegistry meters, String name, String description, ToDoubleFunction<PoolStats> value) {
        series.add(new Series(name, MultiGauge.builder("drishti.db.pool." + name).description(description).register(meters), value));
    }

    /** Re-reads every pool and replaces the series, so a connector that was removed or reloaded does not leave a stale one. */
    public void refresh() {
        try {
            Map<String, PoolStats> pools = new LinkedHashMap<>();
            for (SourcePlugin p : registry.plugins()) {
                p.poolStats().ifPresent(s -> pools.put(p.manifest().name(), s));
            }
            PoolReport.identity(identity.getIfAvailable(), sqlite).ifPresent(s -> pools.put("identity", s));
            for (Series s : series) {
                List<MultiGauge.Row<?>> rows = new ArrayList<>();
                pools.forEach((connector, stats) -> {
                    if (s.name().equals("longest_borrow_seconds") && stats.longestBorrowMillis() < 0) {
                        return;
                    }
                    rows.add(MultiGauge.Row.of(Tags.of("connector", connector, "type", stats.type()), stats, s.value()::applyAsDouble));
                });
                s.gauge().register(rows, true);
            }
        } catch (RuntimeException e) {
            LOG.warn("pool metrics not refreshed: {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        refresher.shutdownNow();
    }
}
