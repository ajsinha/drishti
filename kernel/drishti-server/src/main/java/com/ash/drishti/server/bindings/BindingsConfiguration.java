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
package com.ash.drishti.server.bindings;

import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.health.HealthFigures;
import com.ash.drishti.packs.PackEnvironmentPostProcessor;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.packs.bindings.DataNeeds;
import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.server.connectors.ConnectorManager;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The kernel's data bindings (docs/architecture/KERNEL_AND_MODULES.md section 5): the service, the registry's data gate, the Health figure and
 * the refresh that applies an edited {@code bindings.yaml} without a restart ({@code drishti.bindings.refresh-seconds}, default 30).
 */
@Configuration(proxyBeanMethods = false)
public class BindingsConfiguration {

    @Bean
    public BindingService bindingService(SourcesProperties sources, ConnectorManager connectors, SourceRouter router, PackRegistry packs,
            ObjectProvider<AccessRegistry> access, AuditLog audit, Environment env) {
        AccessRegistry reg = access.getIfAvailable();
        BindingService s = new BindingService(PackEnvironmentPostProcessor.bindingsFile(env, sources.connectorsDir()), connectors, router, packs,
                DataNeeds.load(BindingsConfiguration.class.getClassLoader()), m -> reg == null || reg.moduleOn(m),
                () -> Boolean.parseBoolean(env.resolvePlaceholders("${drishti.demo.enabled:${DRISHTI_DEMO_ENABLED:true}}").trim()), audit,
                Path.of(env.getProperty("drishti.data.dir", "./data"), "bindings", "proposals.json"), Clock.systemUTC());
        s.refresh();
        if (reg != null) {
            reg.dataGate(s::offReason);
        }
        return s;
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService bindingsRefresh(BindingService s, Environment env) {
        long every = Math.max(5, env.getProperty("drishti.bindings.refresh-seconds", Long.class, 30L));
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("drishti-bindings").factory());
        ex.scheduleWithFixedDelay(() -> {
            try {
                s.refresh();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(BindingsConfiguration.class).warn("data bindings: refresh failed: {}", e.getMessage());
            }
        }, every, every, TimeUnit.SECONDS);
        return ex;
    }

    @Bean
    public HealthFigures bindingsHealth(BindingService s) {
        return new HealthFigures() {
            @Override
            public String key() {
                return "bindings";
            }

            @Override
            public Object value() {
                return s.health();
            }
        };
    }
}
