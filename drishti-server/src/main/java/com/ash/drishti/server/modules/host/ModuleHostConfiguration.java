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
package com.ash.drishti.server.modules.host;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.health.HealthFigures;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.server.placement.PlacementHost;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * The kernel's module host (docs/architecture/KERNEL_AND_MODULES.md sections 4 and 12): the host, the dispatch of requests to the loaded
 * modules (after the caller is known and the module gate, before the engine gate), and its Health card.
 */
@Configuration(proxyBeanMethods = false)
public class ModuleHostConfiguration {

    @Bean
    public KernelModuleHost kernelModuleHost(ConfigurableEnvironment env, ApplicationContext context, ObjectProvider<PlacementHost> placement,
            ObjectProvider<SourceRegistry> sources, ObjectProvider<EngineRegistry> engines, ObjectProvider<SourceRouter> router,
            ObjectProvider<AuditLog> audit, ObjectProvider<ObjectMapper> json) {
        return new KernelModuleHost(env, context, placement::getIfAvailable, new ModuleBridge(sources::getIfAvailable, engines::getIfAvailable),
                router::getIfAvailable, audit::getIfAvailable, json::getIfAvailable);
    }

    @Bean
    public FilterRegistrationBean<ModuleDispatchFilter> moduleDispatchFilter(KernelModuleHost host) {
        FilterRegistrationBean<ModuleDispatchFilter> r = new FilterRegistrationBean<>(new ModuleDispatchFilter(host));
        r.addUrlPatterns("/api/*");
        r.setOrder(3);
        return r;
    }

    /** Health's card of the {@code .dmod} modules: the folders, the bridge's types, each module's state, routes and contributions. */
    @Bean
    public HealthFigures moduleHostHealth(KernelModuleHost host) {
        return new HealthFigures() {
            @Override
            public String key() {
                return "dmod-modules";
            }

            @Override
            public Object value() {
                return host.describe();
            }
        };
    }
}
