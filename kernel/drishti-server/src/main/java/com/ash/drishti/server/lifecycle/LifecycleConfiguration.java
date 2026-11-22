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
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackLifecycles;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.PackReloader;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.server.modules.ModuleNode;
import com.ash.drishti.server.net.DigestService;
import com.ash.drishti.server.security.PackAccess;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * The lifecycle of modules, packs and connectors (docs/architecture/LIFECYCLE.md): the services, the health entry {@code lifecycle} on
 * {@code /actuator/health} (beside {@code engines}: one health system), the metrics on {@code /actuator/prometheus}, the digest's
 * {@code lifecycle} service, and the wiring once the server is ready (the packs and modules that booted get RUNNING machines).
 */
@Configuration(proxyBeanMethods = false)
public class LifecycleConfiguration {

    @Bean
    public PackLifecycles packLifecycles(PackRegistry registry) {
        return new PackLifecycles(registry);
    }

    @Bean
    public ModuleLifecycles moduleLifecycles(ObjectProvider<ModuleNode> node, PackLifecycles packs, SourceRegistry sources) {
        return new ModuleLifecycles(() -> {
            ModuleNode n = node.getIfAvailable();
            return n == null ? null : n.runtime();
        }, packs, sources);
    }

    @Bean
    public LifecycleService lifecycleService(PackLifecycles packs, ModuleLifecycles modules, SourceRegistry sources, ConnectorManager connectors,
            AuditLog audit) {
        return new LifecycleService(packs, modules, sources, () -> connectorNames(connectors, sources), audit);
    }

    static Set<String> connectorNames(ConnectorManager connectors, SourceRegistry sources) {
        Set<String> out = new LinkedHashSet<>(connectors.names());
        sources.plugins().forEach(p -> out.add(p.manifest().name()));
        return out;
    }

    /**
     * {@code /actuator/health}'s {@code lifecycle}: each module, pack and connector that is not UP, with its status and reasons. Always UP
     * itself: a suspended or degraded part is an administrator's choice or a dependency's state, never the server's fault (as {@code engines}).
     */
    @Bean
    public HealthIndicator lifecycleHealthIndicator(LifecycleService service) {
        return () -> Health.up().withDetails(service.healthSummary()).build();
    }

    /** When the server is ready: the actions and the world wired, the booted parts RUNNING, the gate in place, the digest and metrics on. */
    @EventListener(ApplicationReadyEvent.class)
    public void ready(ApplicationReadyEvent e) {
        var ctx = e.getApplicationContext();
        Environment env = ctx.getEnvironment();
        LifecycleService service = ctx.getBean(LifecycleService.class);
        PackLifecycles packs = service.packs();
        PackRegistry registry = ctx.getBean(PackRegistry.class);
        SourceRegistry sources = ctx.getBean(SourceRegistry.class);
        ConnectorManager connectors = ctx.getBean(ConnectorManager.class);
        PackReloader reloader = ctx.getBean(PackReloader.class);
        ObjectProvider<EngineRegistry> engines = ctx.getBeanProvider(EngineRegistry.class);
        // the modules' packs come from the resolution (a scan of folders): read once here and after each change of the packs, never per health call
        java.util.concurrent.atomic.AtomicReference<Map<String, List<String>>> modulePacks = new java.util.concurrent.atomic.AtomicReference<>(modulePacks(env));
        reloader.onChange(actor -> modulePacks.set(modulePacks(env)));
        packs.wire(new PackLifecycleActions(reloader, registry, sources), new PackLifecycles.World(
                () -> connectorNames(connectors, sources),
                () -> {
                    EngineRegistry r = engines.getIfAvailable();
                    Set<String> out = new LinkedHashSet<>();
                    if (r != null) {
                        r.entries().forEach(x -> out.add(x.id()));
                    }
                    return out;
                },
                modulePacks::get,
                () -> moduleConnectors(modulePacks.get(), registry),
                sources::health,
                id -> engineHealth(engines.getIfAvailable(), id)));
        reloader.onChange(packs::sync);
        packs.sync(com.ash.drishti.engine.source.SourceRegistry.SYSTEM);
        service.modules().sync(com.ash.drishti.engine.source.SourceRegistry.SYSTEM);
        ctx.getBean(PackAccess.class).lifecycles(packs);
        ctx.getBeanProvider(DigestService.class).ifAvailable(d -> {
            d.service(LifecycleService.SERVICE, service::wire);
            service.onTransition(x -> d.announce());          // a suspend is seen by the peers within a gossip round
        });
        ctx.getBeanProvider(MeterRegistry.class).ifAvailable(m -> {
            LifecycleMetrics metrics = new LifecycleMetrics(m, service);
            metrics.refresh();
            service.onTransition(x -> metrics.refresh());
        });
        service.onTransition(x -> ctx.publishEvent(x));        // the kernel's events: lifecycle listeners in modules hear it
    }

    /** The packs each module brings, as the resolution found them (origin {@code module:<id>}). */
    static Map<String, List<String>> modulePacks(Environment env) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        com.ash.drishti.packs.PackPaths.resolve(env).packs().packs().forEach((name, at) -> {
            String origin = at.origin();
            if (origin.startsWith("module:")) {
                out.computeIfAbsent(origin.substring("module:".length()), k -> new ArrayList<>()).add(name);
            }
        });
        return out;
    }

    /** The connectors a module brings: those its packs name that no pack outside the module names. */
    static Map<String, List<String>> moduleConnectors(Map<String, List<String>> packsOf, PackRegistry registry) {
        Map<String, String> moduleOf = new LinkedHashMap<>();
        packsOf.forEach((m, list) -> list.forEach(p -> moduleOf.put(p, m)));
        Map<String, Set<String>> byConnector = new LinkedHashMap<>();
        for (Pack p : registry.packs()) {
            String m = moduleOf.getOrDefault(p.name(), "");
            p.connectorRefs().forEach(c -> byConnector.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(m));
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        byConnector.forEach((c, owners) -> {
            if (owners.size() == 1 && !owners.iterator().next().isEmpty()) {
                out.computeIfAbsent(owners.iterator().next(), k -> new ArrayList<>()).add(c);
            }
        });
        return out;
    }

    /** An engine's health from the engine registry's state: running is UP, starting DEGRADED, anything else DOWN with its word and reason. */
    static ComponentHealth engineHealth(EngineRegistry r, String id) {
        Instant now = Instant.now();
        if (r == null || !r.known(id)) {
            return ComponentHealth.of(ComponentHealth.Status.DOWN, "not installed", now);
        }
        EngineRegistry.State s = r.state(id);
        String why = s.word() + (r.reason(id).isEmpty() ? "" : ": " + r.reason(id));
        return switch (s) {
            case RUNNING -> ComponentHealth.up(now);
            case STARTING -> ComponentHealth.of(ComponentHealth.Status.DEGRADED, why, now);
            default -> ComponentHealth.of(ComponentHealth.Status.DOWN, why, now);
        };
    }
}
