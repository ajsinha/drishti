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
package com.ash.drishti.server.modules.deploy;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.kernel.api.health.HealthFigures;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.placement.PlacementService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Module deployment's beans: this node's module runtime and registry, the coordinator, the route gate and the Health figure. */
@Configuration(proxyBeanMethods = false)
public class ModuleDeployConfiguration {

    static Path root(Environment env) {
        String dir = env.getProperty("drishti.module-deploy.dir", "");
        return (dir.isBlank() ? Path.of(env.getProperty("drishti.data.dir", "./data"), "modules") : Path.of(dir)).toAbsolutePath().normalize();
    }

    @Bean(destroyMethod = "close")
    public ModuleNode moduleNode(PlacementService placement, ApplicationContext ctx, Environment env) {
        ApiMounts mounts = new ApiMounts(() -> ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class), () -> {
            Collection<Object> out = new ArrayList<>(ctx.getBeansOfType(RequestMappingHandlerAdapter.class).values());
            out.addAll(ctx.getBeansOfType(HandlerExceptionResolver.class).values());
            return out;
        });
        long drain = env.getProperty("drishti.module-deploy.drain-seconds", Long.class, 30L) * 1000;
        return new ModuleNode(root(env), KernelFacts.ofKernel(), placement, mounts, drain);
    }

    @Bean
    public ModuleDeployService moduleDeployService(ModuleNode node, PlacementService placement, PeerCalls peers, AuditLog audit, Environment env) {
        NodeCalls calls = new NodeCalls(node, placement::self, peers,
                Duration.ofSeconds(env.getProperty("drishti.module-deploy.node-call-seconds", Long.class, 120L)));
        return new ModuleDeployService(root(env), node, placement, calls, audit, Clock.systemUTC(),
                env.getProperty("drishti.module-deploy.conformance-cycles", Integer.class, 5));
    }

    /** Loads the active modules placed here once the server's web layer is up (the request mapping exists). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startModules(org.springframework.boot.context.event.ApplicationReadyEvent e) {
        e.getApplicationContext().getBean(ModuleNode.class).start(e.getApplicationContext());
    }

    /** After placement's guard (4): removed 410, off 404, a swap waited for, requests counted for the drain. */
    @Bean
    public FilterRegistrationBean<ModuleRouteFilter> moduleRouteFilter(ModuleNode node, Environment env) {
        FilterRegistrationBean<ModuleRouteFilter> r = new FilterRegistrationBean<>(
                new ModuleRouteFilter(node, env.getProperty("drishti.module-deploy.swap-wait-seconds", Long.class, 30L) * 1000));
        r.addUrlPatterns("/api/*");
        r.setOrder(5);
        return r;
    }

    @Bean
    public HealthFigures moduleDeployHealth(ModuleNode node, ObjectProvider<ModuleDeployService> deploy) {
        return new HealthFigures() {
            @Override
            public String key() {
                return "deployedModules";
            }

            @Override
            public Object value() {
                return node.runtime() == null ? List.of() : node.status().get("modules");
            }
        };
    }
}
