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
package com.ash.drishti.server.memlake;

import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.memlake.MemoryBinding;
import com.ash.drishti.memlake.MemoryLake;
import com.ash.drishti.memlake.MemoryLakeSettings;
import com.ash.drishti.memlake.ReplenishSource;
import com.ash.drishti.memlake.Retention;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;

/**
 * The server's wiring of the in-memory lake (docs/architecture/IN_MEMORY_LAKE.md; the lake itself is the plain-Java module
 * {@code drishti-memlake}): {@code drishti.lake.memory.*} bound onto {@link MemoryLakeSettings}, the stores and replenish jobs, the guard
 * that keeps memory-only data off every disk, the cron parser, and the joins to the memory budget (an off-heap pool) and busy protection.
 * The stores are defined when the bean is made, before the connectors read them; the warm-up starts when the server is ready.
 */
@Configuration
public class MemoryLakeConfiguration {

    /** {@code drishti.lake.memory.*}, bound onto the module's settings record (none configured: no stores, no tables). */
    @Bean
    public MemoryLakeSettings memoryLakeSettings(Environment env) {
        MemoryLakeSettings bound = Binder.get(env).bind("drishti.lake.memory", MemoryLakeSettings.class).orElseGet(MemoryLakeSettings::none);
        // the stores are lakes (config/lakes/<store>.yaml, root: mem://<store>, LAKE_GUIDE.md section 6); the engine file keeps the switch,
        // the replenish jobs and the compaction policy (the engine's work, not the lake's description)
        return bound.withLakeStores(com.ash.drishti.lakeconfig.Lakes.current(),
                m -> org.slf4j.LoggerFactory.getLogger(MemoryLakeConfiguration.class).warn(m));
    }

    @Bean(destroyMethod = "close")
    public MemoryLake memoryLake(MemoryLakeSettings configured, SourceRegistry registry,
            ObjectProvider<com.ash.drishti.server.net.NetService> net,
            ObjectProvider<com.ash.drishti.server.admission.RequestsInFlight> inFlight,
            ObjectProvider<com.ash.drishti.server.engines.EngineSwitches> switches) {
        // off (config/engines/memory-lake.yaml, COMPUTE_ENGINES.md): no store, no table, no job; on needs a restart (the direct-memory limit)
        com.ash.drishti.server.engines.EngineSwitches sw = switches.getIfAvailable();
        MemoryLakeSettings props = sw == null || sw.of("memory-lake").on() ? configured : MemoryLakeSettings.none();
        Set<String> owned = ownedHere(net.getIfAvailable());
        com.ash.drishti.server.admission.RequestsInFlight f = inFlight.getIfAvailable();
        MemoryLake lake = new MemoryLake(props, new ReplenishSource(registry), owned, () -> f == null || f.counter().get() <= 1,
                (line, after) -> CronExpression.parse(line).next(after));
        MemoryBinding.install(lake, registry);
        lake.onChange(() -> registry.plugins().stream().filter(com.ash.drishti.api.SourcePlugin::memoryOnly)
                .forEach(com.ash.drishti.api.SourcePlugin::refreshVersions));
        return lake;
    }

    /**
     * The in-memory lake as a compute engine (docs/architecture/COMPUTE_ENGINES.md): off live releases its stores; on needs a restart (the
     * launchers add the stores' caps to the direct-memory limit at start), so Admin, Engines writes the file and says "restart needed".
     */
    @Bean
    public com.ash.drishti.server.engines.BasicEngine memoryLakeComputeEngine(MemoryLake lake) {
        return new com.ash.drishti.server.engines.BasicEngine("memory-lake").onStop(lake::close).memory(lake::usedBytes)
                .threads("memory-lake-");
    }

    /** node.yaml {@code memory-stores} when this node is in a cluster and names some; otherwise null (each store's {@code owner}). */
    static Set<String> ownedHere(com.ash.drishti.server.net.NetService net) {
        if (net == null || !net.config().node().enabled()) {
            return null;
        }
        List<String> named = net.config().node().memoryStores();
        return named.isEmpty() ? null : new LinkedHashSet<>(named);
    }

    @Bean
    public Retention retention(SourceRegistry registry, ObjectProvider<Mnemonics> mnemonics, MemoryLakeSettings props) {
        Mnemonics m = mnemonics.getIfAvailable();
        Retention r = new Retention(registry, m == null ? k -> null : m::codeFor,
                store -> props.stores().containsKey(store) ? props.stores().get(store).contract() : "");
        Retention.install(r);
        return r;
    }

    @Bean
    public MemoryLakeController memoryLakeController(MemoryLake lake, Retention retention,
            com.ash.drishti.server.security.Entitlements entitlements, ObjectProvider<com.ash.drishti.identity.AuditLog> audit) {
        return new MemoryLakeController(lake, retention, entitlements, audit.getIfAvailable());
    }

    /** When the server is ready: the joins to the memory budget and busy protection, then the warm-up. */
    @EventListener(ApplicationReadyEvent.class)
    public void start(ApplicationReadyEvent e) {
        var ctx = e.getApplicationContext();
        MemoryLake lake = ctx.getBean(MemoryLake.class);
        if (!lake.configured()) {
            return;
        }
        ctx.getBeanProvider(com.ash.drishti.server.caches.MemoryBudget.class).ifAvailable(b ->
                b.offHeap(new com.ash.drishti.server.caches.MemoryBudget.OffHeap("memory-lake", lake::usedBytes, lake::capBytes)));
        ctx.getBeanProvider(com.ash.drishti.server.admission.AdmissionGate.class).ifAvailable(g -> {
            for (String store : lake.stores().keySet()) {
                if (lake.owns(store)) {
                    g.memoryStore(store, () -> com.ash.drishti.memlake.store.MemoryStores.store(store).pool().allocatedBytes(),
                            () -> com.ash.drishti.memlake.store.MemoryStores.store(store).pool().capBytes());
                }
            }
        });
        lake.start();
    }
}
