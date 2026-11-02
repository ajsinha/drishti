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
package com.ash.drishti.server.engines;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.net.DigestService;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.net.RunCluster;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * The compute engines' beans (docs/architecture/COMPUTE_ENGINES.md): the catalogue, each switch as the server starts with it, the site's
 * files, the History, the registry (which finds every {@link ComputeEngine} bean the engines' own configurations make), the API's gate,
 * the cluster's view, the {@code engines} health entry, and the digest's {@code engines} service.
 */
@Configuration
public class EngineConfiguration {

    @Bean
    public EngineCatalogue engineCatalogue() {
        return EngineCatalogue.load();
    }

    @Bean
    public EngineSwitches engineSwitches(EngineCatalogue catalogue, ConfigurableEnvironment env) {
        return new EngineSwitches(catalogue, env);
    }

    @Bean(destroyMethod = "close")
    public EngineRegistry engineRegistry(EngineCatalogue catalogue, EngineSwitches switches, ConfigurableEnvironment env,
            ObjectProvider<ComputeEngine> engines, ObjectProvider<AuditLog> audit, ObjectProvider<NetService> net) {
        EngineFiles files = new EngineFiles(Path.of(env.getProperty("drishti.engines.dir", "./config/engines")), System::getenv);
        EngineHistory history = new EngineHistory(Path.of(env.getProperty("drishti.data.dir", "./data"), "engines", "history.jsonl"));
        Duration drain = env.getProperty("drishti.engines.drain", Duration.class, Duration.ofSeconds(30));
        EngineRegistry r = new EngineRegistry(catalogue, switches, files, history, audit.getIfAvailable(), () -> {
            NetService n = net.getIfAvailable();
            String id = n == null ? "" : n.nodeId();
            return id == null || id.isBlank() ? "this server" : id;
        }, drain, engines.orderedStream().toList());
        r.watch(Duration.ofSeconds(env.getProperty("drishti.engines.watch-seconds", Integer.class, 5)));
        return r;
    }

    @Bean
    public EngineCluster engineCluster(EngineRegistry registry, ObjectProvider<DigestService> digests, ObjectProvider<RunCluster> cluster) {
        return new EngineCluster(registry, digests::getIfAvailable, cluster::getIfAvailable);
    }

    /** After the caller is known (the token filter, the caller filter), beside the other forwarding filters: an engine's API here or routed. */
    @Bean
    public FilterRegistrationBean<EngineGateFilter> engineGateFilter(EngineRegistry registry, EngineCluster cluster) {
        FilterRegistrationBean<EngineGateFilter> r = new FilterRegistrationBean<>(new EngineGateFilter(registry, cluster));
        r.addUrlPatterns("/api/*");
        r.setOrder(4);
        return r;
    }

    /** {@code /actuator/health}'s {@code engines}: each engine's state; an engine that is off is {@code off}, never down (off is not a fault). */
    @Bean
    public HealthIndicator enginesHealthIndicator(EngineRegistry registry) {
        return () -> {
            Map<String, Object> d = new LinkedHashMap<>();
            for (EngineCatalogue.Entry e : registry.entries()) {
                String s = registry.state(e.id()).word();
                d.put(e.id(), registry.reason(e.id()).isEmpty() ? s : s + ": " + registry.reason(e.id()));
            }
            return Health.up().withDetails(d).build();
        };
    }

    /** When the server is ready: the digest carries the engines, and a change is announced at once (peers pull it within a round). */
    @EventListener(ApplicationReadyEvent.class)
    public void publish(ApplicationReadyEvent e) {
        var ctx = e.getApplicationContext();
        EngineCluster cluster = ctx.getBean(EngineCluster.class);
        EngineRegistry registry = ctx.getBean(EngineRegistry.class);
        // BI: the datasets and models an engine computes are not offered while it runs nowhere (the catalogue's bi: prefixes)
        ctx.getBeanProvider(com.ash.drishti.server.edition.EditionBi.class).ifAvailable(b -> b.engines(name -> registry.entries().stream()
                .anyMatch(en -> en.list("bi").stream().anyMatch(name::startsWith) && !cluster.available(en.id()))));
        ctx.getBeanProvider(DigestService.class).ifAvailable(d -> {
            d.service(EngineCluster.SERVICE, cluster::wire);
            registry.onChange(d::announce);
            d.announce();
        });
    }
}
